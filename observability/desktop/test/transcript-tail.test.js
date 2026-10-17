// The main-process live tail (src/transcript-tail.js, #1006), driven against a
// fake VictoriaLogs: a local `http` server whose /select/logsql/tail writes
// newline-delimited JSON rows and keeps the connection open, and whose
// /select/logsql/query answers the gap and final reads.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const { TranscriptTail, tailQuery, TAIL_OFFSET, TAIL_REFRESH_INTERVAL, TAIL_START_OFFSET } = require("../src/transcript-tail");

const LOG = "/logs/issue-1006-work-opus-20261005-100000.log";
const row = (sec, text) => JSON.stringify({
  _time: `2026-10-05T10:00:${String(sec).padStart(2, "0")}.000000000Z`, _stream_id: "s1", filename: LOG,
  _msg: JSON.stringify({ type: "assistant", uuid: `u${sec}`, message: { content: [{ type: "text", text }] } }),
});

test("the tail query is eventsQuery's exact-match filter without the sort, limit and offset pipes, and selects _time", () => {
  const q = tailQuery(1006, "smith-runner", LOG);
  assert.ok(q.includes('issue:="1006"'), q);
  assert.ok(q.includes('host:="smith-runner"'), q);
  assert.ok(q.includes(`filename:=${JSON.stringify(LOG)}`), q);
  const pipes = q.split("|").slice(1).map((p) => p.trim());
  for (const p of pipes) assert.doesNotMatch(p, /^(sort|limit|offset|stats|uniq|top)\b/, p);
  assert.doesNotMatch(q, /sort by|\| *limit|\| *offset/);
  assert.match(q, /\| fields _time, _stream_id, _msg, filename$/);
  // no host known: no host filter, never an empty one
  assert.doesNotMatch(tailQuery(1006, null, LOG), /host:/);
});

// The fake VictoriaLogs. `tails` are the open tail responses, `tailBodies` and
// `queries` what each request asked.
const fakeLogs = async () => {
  const s = { tails: [], tailBodies: [], queries: [], queryRows: [] };
  s.server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (c) => { body += c; });
    req.on("end", () => {
      const params = new URLSearchParams(body);
      if (req.url === "/select/logsql/tail") {
        s.tailBodies.push(Object.fromEntries(params));
        res.writeHead(200, { "content-type": "application/stream+json" });
        res.flushHeaders();
        s.tails.push(res);
        if (s.onTail) s.onTail(res);
      } else if (req.url === "/select/logsql/query") {
        s.queries.push(params.get("query"));
        res.end(s.queryRows.map((r) => r + "\n").join(""));
      } else { res.writeHead(404); res.end(); }
    });
  });
  await new Promise((r) => s.server.listen(0, "127.0.0.1", r));
  s.base = `http://127.0.0.1:${s.server.address().port}`;
  s.close = () => new Promise((r) => { s.server.closeAllConnections(); s.server.close(r); });
  return s;
};
const until = async (cond, ms = 2000) => {
  const t0 = Date.now();
  while (!cond()) {
    if (Date.now() - t0 > ms) throw new Error("timed out waiting");
    await new Promise((r) => setTimeout(r, 5));
  }
};
const linesOf = (frames) => frames.filter((f) => f.events).flatMap((f) => f.events.flatMap((e) => e.lines));

test("each line the tail writes reaches the callback in order, with the tail's arguments on the request", async () => {
  const vl = await fakeLogs();
  const frames = [];
  const tail = new TranscriptTail({ ticket: 1006, host: "h", filename: LOG, base: vl.base, onFrame: (f) => frames.push(f) }).start();
  try {
    await until(() => vl.tails.length === 1);
    assert.deepEqual(vl.tailBodies[0], { query: tailQuery(1006, "h", LOG), offset: TAIL_OFFSET,
      refresh_interval: TAIL_REFRESH_INTERVAL, start_offset: TAIL_START_OFFSET });
    vl.tails[0].write(row(1, "one") + "\n" + row(2, "two").slice(0, 20));
    vl.tails[0].write(row(2, "two").slice(20) + "\n");
    vl.tails[0].write(row(3, "three") + "\n");
    await until(() => linesOf(frames).length === 3);
    assert.deepEqual(linesOf(frames), ["one", "two", "three"]);
    assert.deepEqual(frames[0], { state: "tailing" });
    assert.equal(tail.cursor, "2026-10-05T10:00:03.000000000Z", "the cursor is the newest _time seen");
    assert.equal(tail.open, true, "and the connection stays open");
  } finally { tail.close(); await vl.close(); }
});

test("a dropped tail while the run is live says so, reads the gap from the last _time once, and reopens", async () => {
  const vl = await fakeLogs();
  const frames = [];
  const tail = new TranscriptTail({ ticket: 1006, host: "h", filename: LOG, base: vl.base, backoffMs: [20],
    onFrame: (f) => frames.push(f) }).start();
  try {
    await until(() => vl.tails.length === 1);
    vl.tails[0].write(row(1, "one") + "\n");
    await until(() => linesOf(frames).length === 1);
    // VictoriaLogs restarts: every connection goes.
    vl.queryRows = [row(2, "two"), row(1, "one")];            // the query answers newest first
    vl.server.closeAllConnections();
    await until(() => vl.tails.length === 2);
    // The server sees the reconnect before the client draws it: wait for the gap's lines and the reopened tail.
    await until(() => linesOf(frames).length === 3 && frames[frames.length - 1].state === "tailing");
    assert.ok(frames.some((f) => f.state === "lapsed" && /lapsed/.test(f.error)), "the lapse is said");
    assert.equal(vl.queries.length, 1, "one gap read");
    assert.match(vl.queries[0], /^_time:\[2026-10-05T10:00:01\.000000000Z, now\] issue:="1006" AND host:="h" AND filename:=/);
    assert.deepEqual(linesOf(frames), ["one", "one", "two"], "the gap's lines (the renderer's key drops the repeat)");
    assert.equal(frames[frames.length - 1].state, "tailing", "and the tail is open again");
    vl.tails[1].write(row(3, "three") + "\n");
    await until(() => linesOf(frames).includes("three"));
  } finally { tail.close(); await vl.close(); }
});

test("`end()` closes the connection within the grace period, after a last read, and no reconnect follows", async () => {
  const vl = await fakeLogs();
  const frames = [];
  const tail = new TranscriptTail({ ticket: 1006, host: "h", filename: LOG, base: vl.base, graceMs: 150, backoffMs: [10],
    onFrame: (f) => frames.push(f) }).start();
  let closedAt = null;
  vl.onTail = (res) => res.on("close", () => { closedAt = Date.now(); });
  try {
    await until(() => vl.tails.length === 1);
    const t0 = Date.now();
    vl.queryRows = [row(4, "last")];
    const done = tail.end();
    // Lines still arriving during the grace period are delivered.
    vl.tails[0].write(row(2, "late") + "\n");
    await until(() => linesOf(frames).includes("late"));
    await done;
    await until(() => closedAt != null);
    assert.ok(closedAt - t0 < 150 + 500, `closed ${closedAt - t0} ms after end`);
    assert.ok(closedAt - t0 >= 140, "not before the grace period");
    assert.equal(vl.queries.length, 1, "one last read");
    assert.deepEqual(linesOf(frames), ["late", "last"]);
    assert.equal(tail.open, false);
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(vl.tails.length, 1, "no reconnect after the end");
    assert.ok(!frames.some((f) => f.state === "lapsed"), "and no lapse said");
  } finally { tail.close(); await vl.close(); }
});

test("`close()` drops the tail at once and nothing reopens it", async () => {
  const vl = await fakeLogs();
  const frames = [];
  const tail = new TranscriptTail({ ticket: 1006, host: "h", filename: LOG, base: vl.base, backoffMs: [10],
    onFrame: (f) => frames.push(f) }).start();
  try {
    await until(() => vl.tails.length === 1);
    tail.close();
    await new Promise((r) => setTimeout(r, 80));
    assert.equal(vl.tails.length, 1);
    assert.equal(vl.queries.length, 0);
    assert.ok(!frames.some((f) => f.state === "lapsed"));
  } finally { await vl.close(); }
});

test("a tail VictoriaLogs refuses (an HTTP 400) is a lapse, retried with the backoff", async () => {
  const vl = await fakeLogs();
  let refused = 0;
  vl.server.removeAllListeners("request");
  vl.server.on("request", (req, res) => { req.resume(); if (req.url === "/select/logsql/tail") refused++; res.writeHead(400); res.end("bad query"); });
  const frames = [];
  const tail = new TranscriptTail({ ticket: 1006, host: "h", filename: LOG, base: vl.base, backoffMs: [10],
    onFrame: (f) => frames.push(f) }).start();
  try {
    await until(() => refused >= 2);
    assert.match(frames.find((f) => f.state === "lapsed").error, /HTTP 400/);
  } finally { tail.close(); await vl.close(); }
});

test("no logs_url: the tail lapses once, naming the missing key, and does not reopen", async () => {
  const frames = [];
  const tail = new TranscriptTail({ ticket: 1006, host: "h", filename: "f.log", onFrame: (f) => frames.push(f), backoffMs: [5] }).start();
  await new Promise((r) => setTimeout(r, 30));
  tail.close();
  assert.equal(frames.length, 1);
  assert.equal(frames[0].state, "lapsed");
  assert.match(frames[0].error, /`logs_url` is not set in agent-army\.config\.json/);
});
