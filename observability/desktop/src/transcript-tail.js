// transcript-tail.js — the live transcript pane's VictoriaLogs live tail
// (#1006): while a run is live, one open `/select/logsql/tail` connection for
// that run's session, each line pushed to the renderer as it arrives. It
// replaced the 4 s query timer: VictoriaLogs stays the transcript's source,
// the engine only says when a run starts and ends (`session` frames).
//
// One TranscriptTail per (window, run). main.js opens it on the renderer's
// `transcript:tail-open` (after the renderer's one query read of the lines so
// far, `fetchTranscript`), and sends each frame on `transcript:tail`:
//   { events, cursor }           lines, rendered as fetchTranscript renders them
//   { state: "tailing" }         the connection is open (again)
//   { state: "lapsed", error }   it dropped while the run is live; reopening
// A drop reopens with a backoff, and the lines missed in the gap are read once
// by the query endpoint from the last `_time` seen (`cursor`). `end()` (the
// run's `session` `ended`) keeps the tail open for END_GRACE_MS, reads the run
// once more from the cursor, then drops the connection; `close()` (scraping
// off, the detail closed, the window gone) drops it at once. Neither reconnects.
//
// Long-lived by design: the tail has no socket timeout (TICKET_DATA_TIMEOUT_SECS
// is for query reads, which the gap and final reads still use).
"use strict";

const http = require("node:http");
const https = require("node:https");

const { timeoutOf, LOGS_URL_MISSING } = require("./ticket-data");
const { fetchEvents, renderRows } = require("./transcript-data");

// The tail's arguments (VictoriaLogs v1.15.0's ProcessLiveTailRequest, which
// takes all three; a bare number there is seconds). Each refresh queries
// (now - offset - 5 s, now - offset] and drops what a stream already sent, so:
// - `offset` 1s: a line is sent at most offset + refresh_interval = 2 s after
//   its `_time`, where the 4 s poll took up to 4 s plus the query. The 5 s
//   look-back each refresh still makes is what absorbs Alloy's shipping lag (a
//   line ingested up to ~5 s after its `_time` is still sent); a longer offset
//   would only delay every line.
// - `refresh_interval` 1s: VictoriaLogs' default, one small query a second on
//   the server for one open pane.
// - `start_offset` 30s: the overlap with the query read the tail follows (the
//   mount's, a reconnect's gap read). That read ends at its own "now"; the tail
//   opens a moment later and first sends the last 30 s, so nothing lands
//   between the two, and a line both return has one key (`eventKey`: `_time`
//   plus the event's uuid), which mergeTranscript in the renderer keeps once.
const TAIL_OFFSET = "1s";
const TAIL_REFRESH_INTERVAL = "1s";
const TAIL_START_OFFSET = "30s";
// How long `end()` keeps the tail open after the engine's `ended`: offset plus
// refresh interval plus the 5 s look-back, so a line Alloy shipped late is
// still sent, before the final query read and the close.
const END_GRACE_MS = 8000;
// The reconnect backoff after a drop, the last step repeated.
const RECONNECT_BACKOFF_MS = [1000, 2000, 5000, 10000, 30000];

// The tail's LogsQL: eventsQuery's exact-match filter (transcript-data.js)
// without its `_time` window and without the `sort`, `limit` pipes, which the
// tail rejects. `_time` must be selected; `_stream_id` is what the tail orders
// and dedupes by.
const tailQuery = (ticket, host, filename) =>
  `issue:=${JSON.stringify(String(ticket))}` +
  (host ? ` AND host:=${JSON.stringify(String(host))}` : "") +
  ` AND filename:=${JSON.stringify(String(filename))}` +
  " | fields _time, _stream_id, _msg, filename";

class TranscriptTail {
  constructor({ ticket, host = null, filename, since = null, onFrame, base = null, timeoutMs = timeoutOf(),
                gapRead = null, graceMs = END_GRACE_MS, backoffMs = RECONNECT_BACKOFF_MS }) {
    this.ticket = Number(ticket);
    this.host = host || null;
    this.filename = String(filename);
    this.cursor = since || null;
    this.onFrame = onFrame;
    this.base = base;
    this.graceMs = graceMs;
    this.backoffMs = backoffMs;
    this.gapRead = gapRead || ((cursor) => fetchEvents(this.ticket, this.host, this.filename, { base, timeoutMs, since: cursor }));
    this.req = null;
    this.timer = null;
    this.attempt = 0;
    this.ending = false;
    this.stopped = false;
  }

  start() { this.connect(); return this; }

  emit(frame) { if (!this.stopped) this.onFrame(frame); }

  // Lines from a read or the tail, the cursor carried forward.
  deliver({ events, cursor }) {
    if (cursor && (!this.cursor || cursor > this.cursor)) this.cursor = cursor;
    if (events.length) this.emit({ events, cursor: this.cursor });
  }

  connect() {
    if (this.stopped) return;
    // No VictoriaLogs configured: say so once; reopening cannot help.
    if (!this.base) { this.emit({ state: "lapsed", error: `transcript tail lapsed: ${LOGS_URL_MISSING}` }); return; }
    let url;
    try { url = new URL("select/logsql/tail", this.base.endsWith("/") ? this.base : this.base + "/"); }
    catch (e) { return this.lapse(new Error(`bad logs_url ${this.base}: ${e.message}`)); }
    const body = new URLSearchParams({
      query: tailQuery(this.ticket, this.host, this.filename),
      offset: TAIL_OFFSET, refresh_interval: TAIL_REFRESH_INTERVAL, start_offset: TAIL_START_OFFSET,
    }).toString();
    const lib = url.protocol === "https:" ? https : http;
    let lapsed = false;
    const drop = (e) => { if (!lapsed) { lapsed = true; if (this.req === req) this.req = null; this.lapse(e); } };
    const req = lib.request(url, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", "content-length": Buffer.byteLength(body) },
    }, (res) => {
      if (res.statusCode !== 200) { res.resume(); return drop(new Error(`VictoriaLogs tail HTTP ${res.statusCode}`)); }
      this.attempt = 0;
      this.emit({ state: "tailing" });
      let buf = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => {
        buf += chunk;
        const parts = buf.split("\n");
        buf = parts.pop();
        const rows = [];
        for (const l of parts) {
          if (!l.trim()) continue;
          try { rows.push(JSON.parse(l)); } catch (e) { /* a torn or foreign line: skip it */ }
        }
        // The tail sends oldest first; renderRows wants the query's newest first.
        if (rows.length) this.deliver(renderRows(rows.reverse()));
      });
      res.on("end", () => drop(new Error("VictoriaLogs closed the tail")));
      res.on("error", drop);
      res.on("close", () => drop(new Error("VictoriaLogs closed the tail")));
    });
    req.on("error", drop);
    this.req = req;
    req.end(body);
  }

  // The connection dropped while the run is live: say so, and reopen after the
  // backoff with one gap read from the cursor first. Not while ending: the
  // final read covers what is left.
  lapse(e) {
    if (this.stopped || this.ending) return;
    this.emit({ state: "lapsed", error: `transcript tail lapsed: ${e && e.message ? e.message : e}` });
    const delay = this.backoffMs[Math.min(this.attempt, this.backoffMs.length - 1)];
    this.attempt++;
    this.timer = setTimeout(() => { this.timer = null; this.reopen(); }, delay);
  }

  async reopen() {
    if (this.stopped || this.ending) return;
    try { this.deliver(await this.gapRead(this.cursor)); }
    catch (e) { /* the tail's start_offset still covers its last 30 s; the next drop reads again */ }
    this.connect();
  }

  // The run ended: keep reading for the grace period, read once more from the
  // cursor, then drop the connection. Resolves when it is dropped.
  end() {
    if (this.stopped) return Promise.resolve();
    if (this.endP) return this.endP;
    this.ending = true;
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
    this.endP = new Promise((resolve) => {
      setTimeout(async () => {
        try { if (!this.stopped) this.deliver(await this.gapRead(this.cursor)); }
        catch (e) { /* the lines already sent stay; nothing more to read */ }
        this.close();
        resolve();
      }, this.graceMs);
    });
    return this.endP;
  }

  close() {
    this.stopped = true;
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
    if (this.req) { const r = this.req; this.req = null; r.destroy(); }
  }

  get open() { return this.req != null; }
}

const openTail = (opts) => new TranscriptTail(opts).start();

module.exports = {
  TranscriptTail, openTail, tailQuery,
  TAIL_OFFSET, TAIL_REFRESH_INTERVAL, TAIL_START_OFFSET, END_GRACE_MS, RECONNECT_BACKOFF_MS,
};
