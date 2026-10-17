// The engine's event stream in main (src/engine-events.js; docs/proposals/
// engine-sse.md "The app side"): the SSE parser alone, then the stream against
// a fake engine (`http.createServer` on port 0 as ENGINE_URL) that writes frames and drops connections on
// cue, with the clock faked so the backoff and the 45 s silence rule run in
// no time.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");

const { EngineEventStream, parseSse, SILENCE_MS, BACKOFF_MIN_MS, BACKOFF_MAX_MS, FLEET } = require("../src/engine-events");

// ---------------------------------------------------------------------------
// The parser: a pure function over the bytes received so far.
// ---------------------------------------------------------------------------

test("parseSse: two frames in one chunk, in order, with the type and the raw data", () => {
  const r = parseSse('event: hello\ndata: {"repo":"*"}\n\nevent: flow\ndata: {"number":1}\n\n');
  assert.deepEqual(r.frames, [{ type: "hello", data: '{"repo":"*"}' }, { type: "flow", data: '{"number":1}' }]);
  assert.equal(r.comments, 0);
  assert.equal(r.rest, "");
});

test("parseSse: a frame split across chunks is held in `rest` and completes with the next chunk", () => {
  const a = parseSse('event: intake\ndata: {"intakeId":"i-1","sta');
  assert.deepEqual(a.frames, []);
  assert.equal(a.rest, 'event: intake\ndata: {"intakeId":"i-1","sta');
  const b = parseSse(`${a.rest}te":"filed"}\n\nevent: fl`);
  assert.deepEqual(b.frames, [{ type: "intake", data: '{"intakeId":"i-1","state":"filed"}' }]);
  assert.equal(b.rest, "event: fl");
  // The split may land exactly on the blank line: nothing is dispatched until it arrives.
  const c = parseSse("event: x\ndata: 1\n");
  assert.deepEqual(c.frames, []);
  assert.equal(c.rest, "event: x\ndata: 1\n");
  assert.deepEqual(parseSse(`${c.rest}\n`).frames, [{ type: "x", data: "1" }]);
});

test("parseSse: a `: ping` is counted and never a frame, alone or inside a frame; a frame without data is dropped", () => {
  const r = parseSse(": ping\n\n");
  assert.deepEqual(r.frames, []);
  assert.equal(r.comments, 1);
  assert.equal(r.rest, "");
  // A comment line that is complete but whose blank line has not come counts now and is not kept.
  const half = parseSse(": ping\n");
  assert.equal(half.comments, 1);
  assert.equal(half.rest, "");
  const mixed = parseSse(": keepalive\nevent: ticket\n: another\ndata: {}\n\nevent: empty\n\n");
  assert.deepEqual(mixed.frames, [{ type: "ticket", data: "{}" }]);
  assert.equal(mixed.comments, 2);
});

test("parseSse: CRLF line ends, several data lines, no space after the colon, unknown fields and id ignored", () => {
  const r = parseSse("event:flow\r\nid: 7\r\nretry: 100\r\ndata: a\r\ndata: b\r\n\r\ndata: lone\n\n");
  assert.deepEqual(r.frames, [{ type: "flow", data: "a\nb" }, { type: "message", data: "lone" }]);
});

// ---------------------------------------------------------------------------
// The stream against a fake engine.
// ---------------------------------------------------------------------------

// A fake clock: `setTimeout` arms a timer at `t + ms`, `advance(ms)` fires what is due.
const fakeClock = () => {
  const c = { t: 1_000_000, timers: new Map(), seq: 0 };
  c.now = () => c.t;
  c.setTimeout = (fn, ms) => { const id = ++c.seq; c.timers.set(id, { at: c.t + ms, fn }); return id; };
  c.clearTimeout = (id) => { c.timers.delete(id); };
  c.advance = (ms) => {
    const end = c.t + ms;
    for (;;) {
      const due = [...c.timers.entries()].filter(([, x]) => x.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      c.t = due[1].at;
      c.timers.delete(due[0]);
      due[1].fn();
    }
    c.t = end;
  };
  c.pending = () => [...c.timers.values()].map((x) => x.at - c.t);
  return c;
};

// The fake engine: records every request, keeps each response open, answers
// `hello` only when the test says so.
const fakeEngine = async () => {
  const e = { requests: [], open: [] };
  e.server = http.createServer((req, res) => {
    e.requests.push({ url: req.url, headers: req.headers });
    res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
    res.flushHeaders();
    e.open.push(res);
    res.on("close", () => { e.open = e.open.filter((r) => r !== res); });
  });
  await new Promise((r) => e.server.listen(0, "127.0.0.1", r));
  e.url = `http://127.0.0.1:${e.server.address().port}`;
  e.frame = (res, type, data) => res.write(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`);
  e.hello = (res, repo = FLEET) => e.frame(res, "hello", { repo, at: "2026-10-05T10:00:00Z" });
  e.last = () => e.open[e.open.length - 1];
  e.close = () => { for (const r of e.open) r.destroy(); e.server.close(); };
  return e;
};

// Wait, in real time, until `cond()` holds (I/O is real; only the stream's timers are faked).
const until = async (cond, what = "condition") => {
  for (let i = 0; i < 400; i++) {
    if (cond()) return;
    await new Promise((r) => setTimeout(r, 5));
  }
  throw new Error(`timed out waiting for ${what}`);
};

const make = async (opts = {}) => {
  const engine = await fakeEngine();
  const clock = fakeClock();
  const pushes = [];
  const stream = new EngineEventStream({
    env: { ENGINE_URL: engine.url },
    send: (channel, payload) => pushes.push([channel, payload]),
    now: clock.now, setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout,
    log: () => {},
    ...opts,
  });
  const channels = (ch) => pushes.filter(([c]) => c === ch).map(([, p]) => p);
  const states = () => channels("engine:stream").map((p) => p.state);
  const events = () => channels("engine:event").map((p) => [p.type, p.data]);
  const done = () => { stream.close(); engine.close(); };
  return { engine, clock, pushes, stream, channels, states, events, done };
};

test("the request is GET /events?repo= on ENGINE_URL with accept: text/event-stream and no Origin", async () => {
  const { engine, stream, done } = await make();
  stream.setSelected("acme/widgets");
  await until(() => engine.requests.length === 1, "the request");
  const req = engine.requests[0];
  assert.equal(req.url, `/events?repo=${encodeURIComponent("acme/widgets")}`);
  assert.equal(req.headers.accept, "text/event-stream");
  assert.equal(req.headers.origin, undefined);
  done();
});

test("hello, then frames reach the renderer in order once it has snapshotted; main-side listeners get them at once", async () => {
  const { engine, stream, states, events, channels, done } = await make();
  const seen = [];
  stream.on("frame", (f) => seen.push([f.type, f.subscription]));
  let hellos = 0;
  stream.on("hello", () => { hellos += 1; });
  stream.setSelected(null);
  await until(() => engine.open.length === 1, "the connection");
  assert.equal(engine.requests[0].url, "/events?repo=*");
  const res = engine.last();
  // Nothing before hello is a frame of the stream.
  engine.frame(res, "flow", { number: 0 });
  engine.hello(res);
  await until(() => states().length === 1, "catching-up");
  assert.deepEqual(states(), ["catching-up"]);
  assert.equal(hellos, 1);
  assert.equal(channels("engine:stream")[0].repo, "*");
  engine.frame(res, "flow", { repo: "a/b", number: 1 });
  engine.frame(res, "ticket", { repo: "a/b", number: 1, action: "labeled" });
  await until(() => seen.length === 2, "main-side frames");
  assert.deepEqual(seen, [["flow", "*"], ["ticket", "*"]]);
  // Queued for the renderer until it says it has snapshotted.
  assert.deepEqual(events(), []);
  assert.deepEqual(stream.streamState().state, "catching-up");
  stream.snapshotted();
  assert.deepEqual(events(), [["flow", { repo: "a/b", number: 1 }], ["ticket", { repo: "a/b", number: 1, action: "labeled" }]]);
  assert.deepEqual(states(), ["catching-up", "live"]);
  // Live: a frame goes straight through.
  engine.frame(res, "needs", { repo: "a/b", kind: "question", state: "added" });
  await until(() => events().length === 3, "the live frame");
  assert.deepEqual(events()[2], ["needs", { repo: "a/b", kind: "question", state: "added" }]);
  assert.equal(stream.streamState().state, "live");
  assert.equal(stream.streamState().connections["*"].state, "live");
  done();
});

test("a frame between hello and engine:snapshotted is delivered after the snapshot, not before", async () => {
  const { engine, stream, pushes, done } = await make();
  stream.setSelected("acme/widgets");
  await until(() => engine.open.length === 1, "the connection");
  const res = engine.last();
  engine.hello(res, "acme/widgets");
  engine.frame(res, "intake", { repo: "acme/widgets", intakeId: "i-1", state: "filed", ticket: 9 });
  await until(() => pushes.length === 1, "catching-up");
  // The renderer reads its snapshot here; the frame waits.
  await new Promise((r) => setTimeout(r, 20));
  assert.deepEqual(pushes.map(([c, p]) => [c, p.state || p.type]), [["engine:stream", "catching-up"]]);
  stream.snapshotted("acme/widgets");
  assert.deepEqual(pushes.map(([c, p]) => [c, p.state || p.type]),
    [["engine:stream", "catching-up"], ["engine:event", "intake"], ["engine:stream", "live"]]);
  // A second ack changes nothing.
  stream.snapshotted();
  assert.equal(pushes.length, 3);
  done();
});

test("a dropped connection reconnects after the backoff, runs the hello/snapshot path again: down, catching-up, live", async () => {
  const { engine, clock, stream, states, channels, done } = await make();
  stream.setSelected(null);
  await until(() => engine.open.length === 1, "the first connection");
  engine.hello(engine.last());
  await until(() => states().length === 1, "catching-up");
  stream.snapshotted();
  assert.deepEqual(states(), ["catching-up", "live"]);
  const lastFrameAt = clock.t;

  engine.last().destroy();
  await until(() => states().length === 3, "down");
  assert.deepEqual(states(), ["catching-up", "live", "down"]);
  const down = channels("engine:stream")[2];
  assert.equal(down.lastFrameAt, lastFrameAt);
  assert.equal(down.since, clock.t);
  // Nothing reconnects until the backoff has run; no request, no poll.
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(engine.requests.length, 1);
  assert.deepEqual(clock.pending(), [BACKOFF_MIN_MS]);
  clock.advance(BACKOFF_MIN_MS);
  await until(() => engine.open.length === 1, "the second connection");
  assert.equal(engine.requests.length, 2);
  assert.equal(engine.requests[1].url, "/events?repo=*");
  assert.equal(engine.requests[1].headers["last-event-id"], undefined, "no replay is asked for");
  // Frames before the second hello are not the stream's; the queue is fresh.
  engine.frame(engine.last(), "flow", { number: 2 });
  engine.hello(engine.last());
  await until(() => states().length === 4, "catching-up again");
  stream.snapshotted();
  assert.deepEqual(states(), ["catching-up", "live", "down", "catching-up", "live"]);
  assert.deepEqual(channels("engine:event"), []);
  done();
});

test("the backoff goes 1, 2, 4 s and holds at 4 s while the engine is down, and resets on hello", async () => {
  const { engine, clock, stream, states, done } = await make();
  engine.server.close();
  for (const r of engine.open) r.destroy();
  stream.setSelected(null);
  const expected = [1000, 2000, 4000, 4000, 4000];
  for (const ms of expected) {
    await until(() => clock.pending().length === 1, `the retry timer (${ms})`);
    assert.deepEqual(clock.pending(), [ms]);
    clock.advance(ms);
  }
  assert.equal(BACKOFF_MAX_MS, 4000);
  assert.deepEqual(states(), [], "down from the start: no transition to push");
  assert.equal(stream.streamState().state, "down");
  stream.close();
});

test("45 s without a frame or a ping is a dead connection and reconnects; a `: ping` resets that clock and is not an event", async () => {
  const { engine, clock, stream, states, events, done } = await make();
  stream.setSelected("acme/widgets");
  await until(() => engine.open.length === 1, "the connection");
  engine.hello(engine.last(), "acme/widgets");
  await until(() => states().length === 1, "catching-up");
  stream.snapshotted();
  // The silence timer is armed at hello; a ping 40 s later re-arms it.
  assert.deepEqual(clock.pending(), [SILENCE_MS]);
  clock.advance(40_000);
  engine.last().write(": ping\n\n");
  await until(() => clock.pending()[0] === SILENCE_MS, "the ping re-armed the silence clock");
  clock.advance(40_000);
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(engine.requests.length, 1, "80 s with a ping at 40 s is a live connection");
  assert.deepEqual(events(), [], "the ping is not an event");
  assert.deepEqual(states(), ["catching-up", "live"]);
  // Then nothing for 45 s: aborted, down, reconnected after the backoff.
  clock.advance(SILENCE_MS);
  await until(() => states().length === 3, "down");
  assert.equal(states()[2], "down");
  await until(() => engine.open.length === 0, "the old socket closed");
  assert.deepEqual(clock.pending(), [BACKOFF_MIN_MS]);
  clock.advance(BACKOFF_MIN_MS);
  await until(() => engine.requests.length === 2, "the reconnect");
  assert.equal(engine.requests[1].url, `/events?repo=${encodeURIComponent("acme/widgets")}`);
  done();
});

test("a second subscription for another repo opens a second connection with that repo query; refcounted; the last close ends it", async () => {
  const { engine, stream, channels, done } = await make();
  stream.setSelected("acme/widgets");
  await until(() => engine.open.length === 1, "the selected repo's connection");
  assert.deepEqual(stream.subscribe("other/repo"), { ok: true, refs: 1 });
  await until(() => engine.open.length === 2, "the second connection");
  assert.equal(engine.requests[1].url, `/events?repo=${encodeURIComponent("other/repo")}`);
  assert.deepEqual(stream.subscribe("other/repo"), { ok: true, refs: 2 });
  assert.equal(engine.requests.length, 2, "a second holder opens nothing");
  engine.hello(engine.open[1], "other/repo");
  await until(() => channels("engine:stream").length === 1, "its catching-up");
  assert.equal(channels("engine:stream")[0].repo, "other/repo");
  assert.deepEqual(stream.unsubscribe("other/repo"), { ok: true, refs: 1 });
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(engine.open.length, 2, "still held once");
  assert.deepEqual(stream.unsubscribe("other/repo"), { ok: true, refs: 0 });
  await until(() => engine.open.length === 1, "the second connection closed");
  assert.deepEqual(stream.unsubscribe("other/repo"), { error: 'no subscription for "other/repo"' });
  assert.deepEqual(Object.keys(stream.streamState().connections), ["acme/widgets"]);
  // Not a repo: refused before any request.
  assert.match(stream.subscribe("nope").error, /not an owner\/name repo or \*/);
  assert.match(stream.subscribe("").error, /not an owner\/name repo or \*/);
  done();
});

test("repo:set reopens the selected subscription: the new repo's connection opens, the old one closes, a detail's own stays", async () => {
  const { engine, stream, done } = await make();
  stream.setSelected(null);
  await until(() => engine.open.length === 1, "the fleet connection");
  stream.subscribe("acme/widgets");
  await until(() => engine.open.length === 2, "the detail's connection");
  stream.setSelected("acme/widgets");
  await until(() => engine.open.length === 1, "the fleet connection closed");
  assert.equal(engine.requests.length, 2, "the selected repo's stream is the detail's: no third connection");
  assert.equal(stream.streamState().repo, "acme/widgets");
  stream.setSelected("acme/widgets");
  assert.equal(engine.requests.length, 2, "the same selection again opens nothing");
  stream.unsubscribe("acme/widgets");
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(engine.open.length, 1, "the selection still holds it");
  stream.setSelected(null);
  await until(() => engine.requests.length === 3, "the fleet connection again");
  assert.equal(engine.requests[2].url, "/events?repo=*");
  done();
});

test("a window opened later asks engine:stream-state and gets the selected repo's state", async () => {
  const { engine, stream, done } = await make();
  assert.deepEqual(stream.streamState(), { repo: null, state: "down", since: null, lastFrameAt: null, connections: {} });
  stream.setSelected(null);
  await until(() => engine.open.length === 1, "the connection");
  engine.hello(engine.last());
  await until(() => stream.streamState().state === "catching-up", "catching-up");
  const s = stream.streamState();
  assert.equal(s.repo, "*");
  assert.equal(typeof s.since, "number");
  assert.equal(typeof s.lastFrameAt, "number");
  done();
});

test("close() ends every connection and nothing reconnects", async () => {
  const { engine, clock, stream, done } = await make();
  stream.setSelected(null);
  stream.subscribe("acme/widgets");
  await until(() => engine.open.length === 2, "two connections");
  stream.close();
  await until(() => engine.open.length === 0, "both closed");
  clock.advance(BACKOFF_MAX_MS * 2);
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(engine.requests.length, 2);
  assert.deepEqual(stream.subscribe("x/y"), { error: "the stream is closed" });
  done();
});

test("the module names no poll: no setInterval, no Last-Event-ID, no replay", () => {
  const fs = require("node:fs");
  const path = require("node:path");
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "engine-events.js"), "utf8");
  assert.doesNotMatch(src, /setInterval/);
  assert.doesNotMatch(src, /["']last-event-id["']\s*:|lastEventId/i, "no replay header is sent");
  assert.doesNotMatch(src, /EventSource\(/);
  assert.doesNotMatch(src, /auto[S]napshot/, "the part-3 interim is gone: the renderer answers engine:snapshotted (#976)");
});

// The git proxy (`/repos/<o>/<n>/git`, #960) is read on the stream's `push`
// frame and on `hello`, never on a timer: every module under src/ that names
// the proxy route, the stream URL or the clone keeper arms no setInterval, and
// only src/fresh-code.js names the proxy at all.
test("no module that touches /events or /repos/*/git arms a timer; fresh-code.js is the proxy's one caller", () => {
  const fs = require("node:fs");
  const path = require("node:path");
  const srcDir = path.join(__dirname, "..", "src");
  const files = fs.readdirSync(srcDir).filter((f) => /\.(js|ts)$/.test(f));
  const touches = [];
  const names = [];
  for (const f of files) {
    const text = fs.readFileSync(path.join(srcDir, f), "utf8");
    const proxy = /\/repos\/[^\n]*\/git\b|repos\/\$\{[^}]+\}\/\$\{[^}]+\}\/git/.test(text);
    const stream = /\/events\?repo=/.test(text);
    if (proxy) names.push(f);
    if (proxy || stream || f === "fresh-code.js") {
      touches.push(f);
      assert.doesNotMatch(text, /setInterval/, `${f} arms a timer against the engine`);
    }
  }
  assert.deepEqual(touches.sort(), ["engine-events.js", "fresh-code.js"]);
  assert.deepEqual(names, ["fresh-code.js"]);
});

test("the SSE fetch carries the bearer the headers option answers, and nothing when it answers none (#2206)", async () => {
  const { engine, stream } = await make({ headers: () => ({ authorization: "Bearer frk_test_x" }) });
  try {
    stream.setSelected(null);
    await until(() => engine.requests.length >= 1, "the first connect");
    assert.equal(engine.requests[0].headers.authorization, "Bearer frk_test_x");
    assert.equal(engine.requests[0].headers.accept, "text/event-stream");
  } finally { stream.close(); engine.close(); }
  const bare = await make({ headers: () => ({}) });
  try {
    bare.stream.setSelected(null);
    await until(() => bare.engine.requests.length >= 1, "the first connect");
    assert.equal(bare.engine.requests[0].headers.authorization, undefined);
  } finally { bare.stream.close(); bare.engine.close(); }
});

test("the default timers are called unbound, as a browser's clearTimeout needs (web version)", () => {
  // A browser's clearTimeout throws "Illegal invocation" when called as a method of
  // another object; Node's does not, so stand one in that checks its receiver.
  const real = globalThis.clearTimeout;
  globalThis.clearTimeout = function (t) {
    if (this !== undefined && this !== globalThis) throw new TypeError("Illegal invocation");
    return real(t);
  };
  const stream = new EngineEventStream({ env: { ENGINE_URL: "http://127.0.0.1:1" }, send: () => {}, log: () => {} });
  try {
    const t = stream.setTimeoutFn(() => {}, 60000);
    assert.doesNotThrow(() => stream.clearTimeoutFn(t));
  } finally {
    globalThis.clearTimeout = real;
    stream.close();
  }
});
