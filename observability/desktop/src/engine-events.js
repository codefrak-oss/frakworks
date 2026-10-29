// engine-events.js — the engine's event stream, held in the main process
// (engine/README.md, "Events").
//
// One connection per repo in use: `GET /events?repo=<owner/name>` for the
// selected repo (`repo=*` in All repos), reopened when the selection moves,
// and one more for a ticket detail open on another repo while it is open
// (`subscribe`/`unsubscribe`, refcounted by `owner/name`). The body is read as
// it streams and parsed by the SSE grammar here (`parseSse`: `event:`,
// `data:`, a blank line ends a frame, `:` opens a comment; no `id:` is
// expected and none is kept), so this one file knows the engine's URL and
// controls the reconnect and the snapshot hand-off. No `EventSource`.
//
// Every connect takes one path. Main waits for `hello`, pushes `engine:stream`
// `catching-up` to every window and queues each frame after it; the renderer
// takes its snapshot through the reads it already has (renderer/engine-events.js,
// the catch-up: every mounted view's refetch once) and answers
// `engine:snapshotted`; main then pushes the queued frames in order as
// `engine:event` `{type, data}`, then `engine:stream` `live`. The engine put
// the subscriber on its bus before it wrote `hello`, so a change after `hello`
// is in the queue and one before it is in the snapshot: nothing is lost, and
// nothing depends on what the engine remembers of this client, which is
// nothing (no `Last-Event-ID`, no replay). A dropped connection pushes `down`
// and reconnects with a capped backoff (1, 2, then 4 s on; leader-only traffic
// leaves only seconds with no ready endpoint); 45 s without a
// frame or a `: ping` (the engine sends one every 15 s when idle) is a dead
// connection and is reconnected the same way. Nothing polls meanwhile.
//
// Frames also reach main-side listeners at once (`"frame"` and `"hello"` on
// the stream itself): First mate's "Filed as #N" row (src/first-mate.js) takes
// `intake` frames here, with no IPC.
//
// The engine: the Engine URL setting, as src/engine-data.js reads it; unset, no connection is opened.
"use strict";

const { EventEmitter } = require("node:events");
const { engineUrl, NO_ENGINE_PROMPT, REPO_FULL } = require("./engine-data");

// The reconnect backoff's bounds, and how long a silent connection lives.
const BACKOFF_MIN_MS = 1000;
const BACKOFF_MAX_MS = 4 * 1000;
const SILENCE_MS = 45 * 1000;
// The subscription that receives every event (the engine's EngineEvent.FLEET).
const FLEET = "*";
const STATES = ["down", "catching-up", "live"];

/**
 * The SSE grammar over `text`, the bytes received so far that no frame has
 * consumed: a frame is `event:` and `data:` lines ended by a blank line; a line
 * opening with `:` is a comment (the engine's `: ping`) and is counted, never
 * delivered; `id:`, `retry:` and unknown fields are ignored; a frame with no
 * `data` is dropped. Pure: returns `{ frames: [{ type, data }], comments, rest }`,
 * `rest` the text of the frame still open (its complete field lines and the
 * partial last line), to be prepended to the next chunk. `data` is the raw
 * string (lines joined with "\n"); the caller parses it.
 */
function parseSse(text) {
  const frames = [];
  let comments = 0;
  const lines = String(text).split("\n");
  // The last piece has no "\n" after it yet: a partial line, or "".
  const tail = lines.pop();
  let pending = [];
  for (let line of lines) {
    if (line.endsWith("\r")) line = line.slice(0, -1);
    if (line === "") {
      const f = frameOf(pending);
      if (f) frames.push(f);
      pending = [];
    } else if (line.startsWith(":")) {
      comments += 1;
    } else {
      pending.push(line);
    }
  }
  const rest = pending.length ? `${pending.join("\n")}\n${tail}` : tail;
  return { frames, comments, rest };
}

// One frame from its complete field lines, or null without a `data` line.
function frameOf(lines) {
  let type = "message";
  const data = [];
  for (const line of lines) {
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "event") type = value;
    else if (field === "data") data.push(value);
  }
  return data.length ? { type, data: data.join("\n") } : null;
}

/** `owner/name`, or `*` for the fleet: the only `repo` the stream opens. */
const validRepo = (repo) => repo === FLEET || (typeof repo === "string" && REPO_FULL.test(repo));

// Every open window, when main does not say where to push (tests do).
function sendToAllWindows(channel, payload) {
  const { BrowserWindow } = require("electron");
  for (const w of BrowserWindow.getAllWindows()) {
    if (!w.isDestroyed()) w.webContents.send(channel, payload);
  }
}

/**
 * The stream. Events: `"frame"` `{ type, data, subscription }` for every frame
 * after a connection's `hello` (at once, for main-side listeners); `"hello"`
 * `{ repo, data }` on every connect. Options, all injectable for tests:
 *   fetch, env             as src/engine-data.js's getJson takes them
 *   headers()              extra request headers per connect (default:
 *                          engine-data's authHeaders, the bearer token when
 *                          the desktop is signed in, #2206)
 *   send(channel, payload) the push to every window (default: every BrowserWindow)
 *   now, setTimeout, clearTimeout   the clock
 */
class EngineEventStream extends EventEmitter {
  constructor({ fetch, env, send, now, setTimeout: st, clearTimeout: ct, log, headers } = {}) {
    super();
    this.fetchFn = fetch || ((...a) => globalThis.fetch(...a));
    this.headersFn = typeof headers === "function" ? headers : require("./engine-data").authHeaders;
    this.env = env || process.env;
    this.send = send || sendToAllWindows;
    this.now = now || (() => Date.now());
    this.setTimeoutFn = st || ((fn, ms) => { const t = setTimeout(fn, ms); if (t.unref) t.unref(); return t; });
    this.clearTimeoutFn = ct || ((t) => clearTimeout(t));
    this.log = log || ((line) => console.log(`[events] ${line}`));
    // repo -> connection
    this.connections = new Map();
    // The selected repo's subscription (`*` in All repos), or null before the selection is known.
    this.selected = null;
    this.closed = false;
  }

  /** The connection for the selected repo (`null` is All repos, `*`), swapped when it moves. */
  setSelected(repo) {
    const want = repo ? repo : FLEET;
    if (want === this.selected) return;
    const was = this.selected;
    this.selected = want;
    this.subscribe(want);
    if (was) this.unsubscribe(was);
  }

  /** One more holder of `repo`'s stream (`owner/name` or `*`); the first opens it. `{ ok }` or `{ error }`. */
  subscribe(repo) {
    if (!validRepo(repo)) return { error: `not an owner/name repo or *: ${JSON.stringify(repo)}` };
    if (this.closed) return { error: "the stream is closed" };
    let c = this.connections.get(repo);
    if (c) { c.refs += 1; return { ok: true, refs: c.refs }; }
    c = {
      repo, refs: 1, closed: false, ctl: null, state: "down", since: this.now(), lastFrameAt: null,
      snapshotted: false, queue: [], backoff: BACKOFF_MIN_MS, silence: null, retry: null, connects: 0,
    };
    this.connections.set(repo, c);
    this._run(c);
    return { ok: true, refs: 1 };
  }

  /** One holder fewer; the last closes the connection. `{ ok }` or `{ error }`. */
  unsubscribe(repo) {
    const c = this.connections.get(repo);
    if (!c) return { error: `no subscription for ${JSON.stringify(repo)}` };
    c.refs -= 1;
    if (c.refs > 0) return { ok: true, refs: c.refs };
    this.connections.delete(repo);
    this._end(c);
    return { ok: true, refs: 0 };
  }

  /**
   * The renderer has taken its snapshot: the frames queued since `hello` go out
   * in order, then `live`. For `repo`'s connection, or every one catching up.
   */
  snapshotted(repo) {
    const targets = repo ? [this.connections.get(repo)].filter(Boolean) : [...this.connections.values()];
    for (const c of targets) {
      if (c.state !== "catching-up") continue;
      c.snapshotted = true;
      const queued = c.queue.splice(0);
      for (const f of queued) this.send("engine:event", f);
      this._setState(c, "live");
    }
    return { ok: true };
  }

  /** The selected repo's stream state (`engine:stream-state`), with every connection's beside it. */
  streamState() {
    const c = this.selected ? this.connections.get(this.selected) : null;
    const of = (x) => ({ repo: x.repo, state: x.state, since: x.since, lastFrameAt: x.lastFrameAt });
    const all = {};
    for (const x of this.connections.values()) all[x.repo] = of(x);
    return { ...(c ? of(c) : { repo: this.selected, state: "down", since: null, lastFrameAt: null }), connections: all };
  }

  /** Closes every connection; nothing reconnects after this. */
  close() {
    this.closed = true;
    for (const c of this.connections.values()) this._end(c);
    this.connections.clear();
  }

  _end(c) {
    c.closed = true;
    if (c.silence) { this.clearTimeoutFn(c.silence); c.silence = null; }
    if (c.retry) { this.clearTimeoutFn(c.retry); c.retry = null; }
    if (c.ctl) c.ctl.abort();
  }

  _setState(c, state, why) {
    if (!STATES.includes(state)) throw new Error(`not a stream state: ${state}`);
    if (c.state === state) return;
    c.state = state;
    c.since = this.now();
    if (why) this.log(`${c.repo}: ${state} (${why})`);
    this.send("engine:stream", { repo: c.repo, state, since: c.since, lastFrameAt: c.lastFrameAt });
  }

  _url(repo) { return `${engineUrl(this.env)}/events?repo=${encodeURIComponent(repo)}`; }

  // The connection's life: connect, read until it ends, back off, again.
  async _run(c) {
    if (engineUrl(this.env) == null) { this.log(`no connection for ${c.repo}: ${NO_ENGINE_PROMPT}`); return; }
    while (!c.closed) {
      await this._connectOnce(c);
      if (c.closed) return;
      await new Promise((resolve) => { c.retry = this.setTimeoutFn(() => { c.retry = null; resolve(); }, c.backoff); });
      c.backoff = Math.min(c.backoff * 2, BACKOFF_MAX_MS);
    }
  }

  async _connectOnce(c) {
    const ctl = new AbortController();
    c.ctl = ctl;
    let rest = "";
    let helloSeen = false;
    const armSilence = () => {
      if (c.silence) this.clearTimeoutFn(c.silence);
      c.silence = this.setTimeoutFn(() => { c.silence = null; ctl.abort(new Error(`${SILENCE_MS / 1000} s without a frame`)); }, SILENCE_MS);
    };
    try {
      const res = await this.fetchFn(this._url(c.repo), { signal: ctl.signal, headers: { ...(this.headersFn() || {}), accept: "text/event-stream" } });
      if (!res.ok) throw new Error(`engine answered ${res.status}`);
      if (!res.body || typeof res.body.getReader !== "function") throw new Error("the body does not stream");
      armSilence();
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        const parsed = parseSse(rest + decoder.decode(value, { stream: true }));
        rest = parsed.rest;
        if (parsed.frames.length || parsed.comments) { c.lastFrameAt = this.now(); armSilence(); }
        for (const f of parsed.frames) {
          let data;
          try { data = JSON.parse(f.data); } catch (e) { this.log(`${c.repo}: a ${f.type} frame that is not JSON was dropped`); continue; }
          if (!helloSeen) {
            if (f.type !== "hello") continue;
            helloSeen = true;
            this._onHello(c, data);
          } else {
            this._onFrame(c, f.type, data);
          }
        }
      }
      throw new Error("the engine closed the stream");
    } catch (e) {
      if (c.closed) return;
      const why = ctl.signal.aborted && ctl.signal.reason instanceof Error ? ctl.signal.reason.message : (e && e.message) || String(e);
      this._setState(c, "down", why);
    } finally {
      if (c.silence) { this.clearTimeoutFn(c.silence); c.silence = null; }
      c.ctl = null;
    }
  }

  _onHello(c, data) {
    c.connects += 1;
    c.backoff = BACKOFF_MIN_MS;
    c.snapshotted = false;
    c.queue = [];
    this._setState(c, "catching-up", `hello, connect ${c.connects}`);
    this.emit("hello", { repo: c.repo, data });
  }

  _onFrame(c, type, data) {
    const frame = { type, data, subscription: c.repo };
    this.emit("frame", frame);
    if (c.snapshotted) this.send("engine:event", frame);
    else c.queue.push(frame);
  }
}

module.exports = { EngineEventStream, parseSse, validRepo, BACKOFF_MIN_MS, BACKOFF_MAX_MS, SILENCE_MS, FLEET };
