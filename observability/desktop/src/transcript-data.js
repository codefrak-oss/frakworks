// transcript-data.js — the main-process side of the third IPC call: one live
// Smith's stream-json events, read out of VictoriaLogs (issue #1283).
//
// The proposal's open question 6 was whether this needs an SSH tail from the
// app. It does not: Alloy on each Smith host already ships
// dispatch/logs/issue-*.log into VictoriaLogs line by line
// (observability/alloy/smith-runner.alloy), one VictoriaLogs row per
// stream-json event, with `issue`, `host`, `filename`, `phase` and `model`
// labels lifted from the path. So this module needs no credential, no shell
// and no change on a Smith host — only the two read-only endpoints on vm/465
// the rest of the app already reads.
//
// Like src/ticket-data.js it never rejects: every failure becomes `error` on
// the document, which the pane draws as the widget's `.err` block while the
// rest of the detail view keeps its data.
//
// Two backends, because "is there a live run" and "what did it say" live in
// different places:
//
//   smith_run_active{issue="<n>"}   VictoriaMetrics — the fleet exporter's
//                                   gauge, which exists only while the run
//                                   does. Its `host` label says which Smith
//                                   host's logs to read, and its absence is
//                                   how the pane knows to stop polling.
//   issue:<n> AND host:<h>          VictoriaLogs — the events, narrowed to
//                                   the newest `filename` (= the newest
//                                   session; runner-local.sh writes one log
//                                   file per run) and to everything after the
//                                   caller's cursor.
//
// The base URLs are the caller's: `base` VictoriaLogs and `vm` VictoriaMetrics,
// `logs_url` and `metrics_url` in agent-army.config.json
// (src/deployment-config.js). Either missing is an error naming the key.
//
// Environment (shared with ticket-data, none new):
//   TICKET_DATA_TIMEOUT_SECS per-request timeout (default 10)
"use strict";

const http = require("node:http");
const https = require("node:https");

const { postLogs, parseLines, timeoutOf, DEFAULT_TIMEOUT_MS } = require("./ticket-data");
const { formatEvent } = require("./transcript-format");

// How far back to look for the run's log. A Smith run is capped well under
// this; it only has to be long enough that a pane opened late in a run still
// finds the run's first event.
const TRANSCRIPT_WINDOW = "24h";
// Most events to return from one poll. The first poll of a long run is the
// only one that ever approaches it; incremental polls return a handful.
const TRANSCRIPT_LIMIT = 500;

const METRICS_URL_MISSING = "`metrics_url` is not set in agent-army.config.json";

// --- transport --------------------------------------------------------------

// GET a JSON endpoint (VictoriaMetrics' /api/v1/query) and resolve to the
// parsed body. Rejects on transport failure; every caller catches.
function getJson({ base, path, params = {}, timeoutMs = DEFAULT_TIMEOUT_MS }) {
  return new Promise((resolve, reject) => {
    if (!base) return reject(new Error(METRICS_URL_MISSING));
    let url;
    try { url = new URL(path, base.endsWith("/") ? base : base + "/"); }
    catch (e) { return reject(new Error(`bad metrics_url ${base}: ${e.message}`)); }
    for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
    const lib = url.protocol === "https:" ? https : http;
    const req = lib.request(url, { method: "GET", timeout: timeoutMs }, (res) => {
      let out = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { out += c; });
      res.on("end", () => {
        if (res.statusCode !== 200) return reject(new Error(`VictoriaMetrics HTTP ${res.statusCode}`));
        try { resolve(JSON.parse(out)); }
        catch (e) { reject(new Error(`VictoriaMetrics produced no JSON: ${e.message}`)); }
      });
    });
    req.on("timeout", () => { req.destroy(new Error(`VictoriaMetrics timed out after ${Math.round(timeoutMs / 1000)}s`)); });
    req.on("error", reject);
    req.end();
  });
}

// --- the queries ------------------------------------------------------------

const activeRunQuery = (ticket) => `smith_run_active{issue="${ticket}"}`;

// A VictoriaLogs `_time` as it comes back from a query: RFC3339 UTC, with or
// without fractional seconds. The only shape `since` is allowed to have.
const SINCE_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/;

// Every filter here is the exact-match form (`field:="value"`) rather than the
// bare word filter the acceptance criteria write as `issue:<n> AND host:<h>`.
// It has to be: VictoriaLogs' word filter tokenises on `-`, so `host:smith-runner`
// also matches `smith-runner-2`, and a `filename:` path would match on any of
// its segments. Same fields, same shape, no cross-host bleed.
const sessionQuery = (ticket, host, window = TRANSCRIPT_WINDOW) =>
  `_time:${window} issue:=${JSON.stringify(String(ticket))}` +
  (host ? ` AND host:=${JSON.stringify(String(host))}` : "") +
  " | sort by (_time) desc | limit 1 | fields _time, filename, host, phase, model";

// `since` is the caller's cursor — the `_time` of the newest event it already
// has. The range is inclusive at both ends so no event is lost to nanosecond
// rounding; the duplicate that costs is dropped by key in the renderer.
const eventsQuery = (ticket, host, filename, { since = null, window = TRANSCRIPT_WINDOW,
                                               limit = TRANSCRIPT_LIMIT } = {}) =>
  (since ? `_time:[${since}, now]` : `_time:${window}`) +
  ` issue:=${JSON.stringify(String(ticket))}` +
  (host ? ` AND host:=${JSON.stringify(String(host))}` : "") +
  (filename ? ` AND filename:=${JSON.stringify(String(filename))}` : "") +
  ` | sort by (_time) desc | limit ${limit} | fields _time, _msg, filename`;

// --- the pieces -------------------------------------------------------------

// The live run for this ticket, or null. `smith_run_active` is a gauge that
// exists only while a Smith is running, so an empty result is the answer
// "no live run", not a failure.
async function fetchActiveRun(ticket, { vm, timeoutMs, getter = getJson } = {}) {
  const body = await getter({
    base: vm, path: "api/v1/query", params: { query: activeRunQuery(ticket) }, timeoutMs,
  });
  const result = ((body || {}).data || {}).result || [];
  if (!result.length) return null;
  const m = result[0].metric || {};
  return {
    host: m.host || null,
    kind: m.kind || null,
    model: m.model || null,
    repo: m.repo || null,
  };
}

// The newest log file for this ticket — one file per run (runner-local.sh), so
// the newest `filename` *is* the newest session. Null when nothing was shipped.
async function fetchNewestSession(ticket, host, opts) {
  const body = await postLogs({ ...opts, path: "select/logsql/query",
                                query: sessionQuery(ticket, host) });
  const rows = parseLines(body);
  if (!rows.length) return null;
  const r = rows[0];
  return {
    filename: r.filename || null,
    host: r.host || host || null,
    phase: r.phase || null,
    model: r.model || null,
    last: r._time || null,
  };
}

// A stable identity for one shipped event, so the renderer can append only what
// it has not seen across the inclusive-cursor overlap. Claude's stream-json
// carries a per-event `uuid`; Codex's does not, so the raw line stands in.
const eventKey = (row, event) => {
  const uuid = event && typeof event.uuid === "string" ? event.uuid : null;
  return `${row._time || ""}|${uuid || String(row._msg || "").slice(0, 160)}`;
};

// Rows -> the rendered events, oldest first. The query sorts descending so
// `limit` keeps the NEWEST events of a long run rather than the oldest; the
// order is put back here. An event the formatter renders to nothing (a
// thinking block, a `system` subtype it does not name) is dropped, exactly as
// `dispatch/smith-tail` drops it.
function renderRows(rows) {
  const out = [];
  let cursor = null;
  for (const row of [...rows].reverse()) {
    const raw = String(row._msg || "");
    if (row._time && (!cursor || row._time > cursor)) cursor = row._time;
    if (!raw.startsWith("{")) continue;
    let event;
    try { event = JSON.parse(raw); } catch (e) { continue; }
    const lines = formatEvent(event);
    if (!lines.length) continue;
    out.push({ key: eventKey(row, event), time: row._time || null, lines });
  }
  return { events: out, cursor };
}

async function fetchEvents(ticket, host, filename, opts) {
  const body = await postLogs({ ...opts, path: "select/logsql/query",
                                query: eventsQuery(ticket, host, filename, opts) });
  return renderRows(parseLines(body));
}

// --- the document -----------------------------------------------------------

// Never rejects. `live` answers "keep polling?", `session` answers "is this
// still the same run?" (the renderer clears the pane when it changes), and
// `cursor` is what to pass back as `since` on the next poll.
// `run`, when given, is the live run the engine's `session` `started` frame
// named, `{ filename, host }` (engine/README.md, "Events"): the gauge is not
// asked and the "which session?" query is skipped; the run is live until the
// renderer hears `ended`. Without it (the mount read, the catch-up's read) the
// gauge is read once, as before.
async function fetchTranscript(ticket, {
  since = null, base = null, vm = null, timeoutMs = timeoutOf(),
  getter = getJson, now = () => Math.floor(Date.now() / 1000), run = null,
} = {}) {
  const n = Number(ticket);
  // The cursor is the one value that reaches a LogsQL query from the renderer,
  // so it is checked for shape here rather than trusted: preload.js promises
  // "the renderer cannot ask VictoriaLogs anything of its own", and this is
  // what makes that true by construction. Anything that is not a VictoriaLogs
  // `_time` falls back to the default window — a wider read, never a different
  // query. (Reviewer note on #1307.)
  const cursor = SINCE_RE.test(String(since ?? "")) ? String(since) : null;
  const doc = {
    // `liveKnown` separates "the gauge says no run" from "the gauge did not
    // answer": only the first means the run ended, and only the first is a
    // reason for the renderer to stop polling.
    ticket: n, now: now(), live: false, liveKnown: false,
    host: null, kind: null, model: null,
    session: null, events: [], cursor, error: null,
  };
  if (!Number.isInteger(n) || n <= 0) {
    doc.error = `not a ticket number: ${JSON.stringify(ticket)}`;
    doc.liveKnown = true;               // settled: there is nothing to poll for
    return doc;
  }
  const fail = (what, e) => {
    const msg = `${what}: ${e && e.message ? e.message : e}`;
    doc.error = doc.error ? `${doc.error} | ${msg}` : msg;
  };
  const known = run && typeof run === "object" && typeof run.filename === "string" && run.filename ? run : null;
  if (known) {
    doc.live = true; doc.liveKnown = true;
    doc.host = typeof known.host === "string" && known.host ? known.host : null;
  } else {
    try {
      const active = await fetchActiveRun(n, { vm, timeoutMs, getter });
      doc.liveKnown = true;               // the gauge answered, run or no run
      if (active) {
        doc.live = true;
        doc.host = active.host;
        doc.kind = active.kind;
        doc.model = active.model;
      }
    } catch (e) {
      // A dead VictoriaMetrics must not cost us the transcript: the log query
      // below still works without a host, it just cannot rule out a same-issue
      // run on another host. Say so and carry on.
      fail("live-run lookup failed", e);
    }
  }
  const opts = { base, timeoutMs, since: cursor };
  try {
    const session = known ? { filename: known.filename, host: doc.host } : await fetchNewestSession(n, doc.host, opts);
    if (!session || !session.filename) {
      doc.now = now();
      return doc;                                    // nothing shipped for this ticket yet
    }
    doc.session = session.filename;
    doc.host = session.host || doc.host;
    doc.model = doc.model || session.model;
    const { events, cursor } = await fetchEvents(n, doc.host, session.filename, opts);
    doc.events = events;
    if (cursor) doc.cursor = cursor;
  } catch (e) {
    fail("transcript lookup failed", e);
  }
  doc.now = now();
  return doc;
}

// --- one doctor run's transcript ----------------------------------------------

// A doctor run's Smith (engine/README.md, "Doctor") ships like any other run,
// to its own log file, `issue-<n>-doctor-<runId>.log` (the engine's
// SmithTelemetry RunLabels), with `runId` the smith-run instance GET
// /doctor/history answers as `smithRunId`. So one run's transcript is that one
// file, whether the run is going or ended long ago: no liveness gauge, no
// newest-session pick. The window reaches back as far as the engine keeps the
// run's history (30 days); a doctor run is short, so the limit is never the
// newest of a longer one.
const DOCTOR_TRANSCRIPT_WINDOW = "30d";
// A process instance id: the one value of the run that reaches the query.
const RUN_ID_RE = /^[A-Za-z0-9-]{1,64}$/;

const doctorLogName = (ticket, runId) => `issue-${ticket}-doctor-${runId}.log`;

// The same exact-match form as eventsQuery, narrowed to the run's file.
const doctorTranscriptQuery = (ticket, runId, { window = DOCTOR_TRANSCRIPT_WINDOW, limit = TRANSCRIPT_LIMIT } = {}) =>
  `_time:${window} issue:=${JSON.stringify(String(ticket))}` +
  ` AND phase:="doctor" AND filename:=${JSON.stringify(doctorLogName(ticket, runId))}` +
  ` | sort by (_time) desc | limit ${limit} | fields _time, _msg, filename`;

// Never rejects: `{ ticket, runId, session, lines, error }`, `lines` the
// formatted entries oldest first (transcript-format.js, as the live pane's),
// `session` the log file's name.
async function fetchDoctorTranscript(ticket, runId, { base = null, timeoutMs = timeoutOf(), poster = postLogs } = {}) {
  const n = Number(ticket);
  const doc = { ticket: n, runId: runId == null ? null : String(runId), session: null, lines: [], error: null };
  if (!Number.isInteger(n) || n <= 0) { doc.error = `not a ticket number: ${JSON.stringify(ticket)}`; return doc; }
  if (!RUN_ID_RE.test(String(runId ?? ""))) { doc.error = `not a run id: ${JSON.stringify(runId)}`; return doc; }
  doc.session = doctorLogName(n, runId);
  try {
    const body = await poster({ base, timeoutMs, path: "select/logsql/query", query: doctorTranscriptQuery(n, runId) });
    doc.lines = renderRows(parseLines(body)).events.flatMap((e) => e.lines).map(String);
  } catch (e) {
    doc.error = `transcript lookup failed: ${e && e.message ? e.message : e}`;
  }
  return doc;
}

module.exports = {
  fetchDoctorTranscript, doctorTranscriptQuery, doctorLogName, DOCTOR_TRANSCRIPT_WINDOW,
  fetchTranscript, fetchActiveRun, fetchNewestSession, fetchEvents, renderRows,
  activeRunQuery, sessionQuery, eventsQuery, eventKey, getJson, METRICS_URL_MISSING, SINCE_RE,
  TRANSCRIPT_WINDOW, TRANSCRIPT_LIMIT,
};
