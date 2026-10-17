// The main-process side of the live transcript pane (stage 4, #1283): the
// VictoriaMetrics liveness probe, the two LogsQL shapes, the cursor that makes
// a poll incremental, and the promise that it never rejects.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const {
  fetchTranscript, fetchActiveRun, renderRows, activeRunQuery, sessionQuery,
  eventsQuery, eventKey, TRANSCRIPT_WINDOW, TRANSCRIPT_LIMIT,
} = require("../src/transcript-data");

const HERE = path.join(__dirname, "..");
const LOG = "/home/user/frakworks/dispatch/logs/issue-1283-implementation-opus-20260921-031702.log";
const fixture = fs.readFileSync(path.join(HERE, "fixture-transcript.jsonl"), "utf8")
  .split("\n").filter(Boolean);
const expected = fs.readFileSync(path.join(HERE, "fixture-transcript.expected.txt"), "utf8")
  .replace(/\n$/, "").split("\n\n");

// One VictoriaLogs row per fixture event, with ascending `_time` — the shape
// Alloy's loki.source.file produces (observability/alloy/smith-runner.alloy).
const rows = fixture.map((msg, i) => ({
  _time: `2026-09-21T03:17:${String(10 + i).padStart(2, "0")}.000000000Z`,
  _msg: msg, filename: LOG, host: "smith-runner", issue: "1283",
}));
const LINES = (rs) => rs.map((r) => JSON.stringify(r)).join("\n") + "\n";

// A VictoriaLogs stand-in, same idea as test/ticket-data.test.js's: record
// every `query=` that went over the wire, answer from `routes`.
function logsServer(handler) {
  const seen = [];
  const srv = http.createServer((req, res) => {
    let body = "";
    req.on("data", (c) => { body += c; });
    req.on("end", () => {
      const query = new URLSearchParams(body).get("query");
      seen.push({ path: req.url, query });
      const out = handler(query, seen.length);
      if (out === null) { res.writeHead(500); return res.end("nope"); }
      res.writeHead(200, { "content-type": "application/json" });
      res.end(out);
    });
  });
  return new Promise((resolve) => srv.listen(0, "127.0.0.1", () =>
    resolve({ base: `http://127.0.0.1:${srv.address().port}`, seen, close: () => srv.close() })));
}

// The VictoriaMetrics side is a plain injected getter — one GET, no streaming.
const vmGetter = (result) => async () => ({ status: "success", data: { result } });
const ACTIVE = [{ metric: { __name__: "smith_run_active", issue: "1283", host: "smith-runner",
                            kind: "job", model: "opus" }, value: [1789960644, "1"] }];

// A server that answers the session probe with the newest row and the events
// query with everything at or after the cursor — i.e. what VictoriaLogs does.
const transcriptServer = (rs) => logsServer((query) => {
  if (/limit 1 /.test(query)) return LINES([rs[rs.length - 1]]);
  const m = /^_time:\[([^,]+),/.exec(query);
  const kept = m ? rs.filter((r) => r._time >= m[1]) : rs;
  return LINES([...kept].reverse());                 // the query sorts descending
});

test("the liveness probe is the fleet exporter's own gauge", async () => {
  assert.equal(activeRunQuery(1283), 'smith_run_active{issue="1283"}');
  assert.deepEqual(await fetchActiveRun(1283, { getter: vmGetter(ACTIVE) }),
    { host: "smith-runner", kind: "job", model: "opus", repo: null });
  // an empty result is the answer "no live run", not a failure
  assert.equal(await fetchActiveRun(1283, { getter: vmGetter([]) }), null);
});

test("the LogsQL is the acceptance criteria's shape, with exact-match filters", () => {
  const s = sessionQuery(1283, "smith-runner");
  assert.match(s, /^_time:24h issue:="1283" AND host:="smith-runner" \| sort by \(_time\) desc \| limit 1 /);
  assert.match(s, /fields _time, filename, host, phase, model$/);
  assert.equal(TRANSCRIPT_WINDOW, "24h");

  const e = eventsQuery(1283, "smith-runner", LOG);
  assert.match(e, /^_time:24h issue:="1283" AND host:="smith-runner" AND filename:="[^"]+" \| sort by \(_time\) desc \| limit 500 \| fields _time, _msg, filename$/);
  assert.equal(TRANSCRIPT_LIMIT, 500);

  // exact-match, not the bare word filter: VictoriaLogs tokenises on `-`, so
  // `host:smith-runner` would also match smith-runner-2's lines.
  assert.ok(!/host:smith-runner\b/.test(e), "no bare word filter on host");

  // with a cursor the window becomes a range starting at it
  assert.match(eventsQuery(1283, "smith-runner", LOG, { since: "2026-09-21T03:17:12.000000000Z" }),
    /^_time:\[2026-09-21T03:17:12\.000000000Z, now\] issue:="1283"/);
});

test("rows render oldest-first into smith-tail's lines, with stable keys", () => {
  const { events, cursor } = renderRows([...rows].reverse());   // as the query returns them
  assert.deepEqual(events.flatMap((e) => e.lines), expected);
  assert.equal(cursor, rows[rows.length - 1]._time, "the cursor is the newest _time seen");
  // the thinking-only event rendered nothing and so is not an event here
  assert.ok(events.length < rows.length);
  // keys are stable across re-delivery and unique within the batch
  assert.equal(new Set(events.map((e) => e.key)).size, events.length);
  assert.deepEqual(renderRows([...rows].reverse()).events.map((e) => e.key), events.map((e) => e.key));
  assert.equal(eventKey(rows[0], JSON.parse(rows[0]._msg)),
    `${rows[0]._time}|${JSON.parse(rows[0]._msg).uuid}`);
});

test("a live run yields the whole session, and the cursor makes the next poll incremental", async () => {
  const srv = await transcriptServer(rows);
  try {
    const first = await fetchTranscript(1283, { base: srv.base, getter: vmGetter(ACTIVE) });
    assert.equal(first.error, null);
    assert.equal(first.live, true);
    assert.equal(first.host, "smith-runner");
    assert.equal(first.kind, "job");
    assert.equal(first.session, LOG);
    assert.deepEqual(first.events.flatMap((e) => e.lines), expected);
    assert.equal(first.cursor, rows[rows.length - 1]._time);

    const next = await fetchTranscript(1283, { base: srv.base, getter: vmGetter(ACTIVE), since: first.cursor });
    // inclusive at both ends, so exactly the event the cursor named comes back
    assert.equal(next.events.length, 1);
    assert.equal(next.events[0].key, first.events[first.events.length - 1].key);
    assert.ok(srv.seen.some((s) => /^_time:\[/.test(s.query)), "the second poll used the range form");
  } finally { srv.close(); }
});

test("a cursor that is not a VictoriaLogs timestamp is discarded, not interpolated", async () => {
  // `since` is the one value the renderer supplies that reaches a LogsQL
  // query, so it is checked for shape rather than trusted (reviewer note on
  // PR #1307). A bad one falls back to the default window.
  const srv = await transcriptServer(rows);
  try {
    const doc = await fetchTranscript(1283, {
      base: srv.base, getter: vmGetter(ACTIVE),
      since: '2026-09-21T03:17:12.000000000Z | drop _msg',
    });
    assert.equal(doc.cursor, rows[rows.length - 1]._time, "the poll still produces a good cursor");
    const queries = srv.seen.map((s) => s.query);
    assert.ok(queries.every((q) => !q.includes("drop _msg")), queries.join("\n"));
    assert.ok(queries.every((q) => !/^_time:\[/.test(q)), "it fell back to the window form");
  } finally { srv.close(); }
});

test("no live run: the last session is still readable and live is false", async () => {
  const srv = await transcriptServer(rows);
  try {
    const doc = await fetchTranscript(1283, { base: srv.base, getter: vmGetter([]) });
    assert.equal(doc.live, false, "nothing to poll for");
    assert.equal(doc.error, null, "a finished run is not an error");
    assert.ok(doc.events.length > 0, "the ended run's transcript is still there");
    // with no gauge there is no host to narrow on, so the session probe asks
    // across hosts rather than inventing one
    assert.ok(!/host:=/.test(srv.seen[0].query));
  } finally { srv.close(); }
});

test("a dead VictoriaLogs becomes `error` and never a rejection", async () => {
  const srv = await logsServer(() => null);
  try {
    const doc = await fetchTranscript(1283, { base: srv.base, getter: vmGetter(ACTIVE) });
    assert.match(doc.error, /^transcript lookup failed: VictoriaLogs HTTP 500$/);
    assert.deepEqual(doc.events, []);
    assert.equal(doc.live, true, "the gauge still answered, so the pane keeps polling");
  } finally { srv.close(); }
});

test("a dead VictoriaMetrics costs liveness but not the transcript", async () => {
  const srv = await transcriptServer(rows);
  const getter = async () => { throw new Error("connect ECONNREFUSED"); };
  try {
    const doc = await fetchTranscript(1283, { base: srv.base, getter });
    assert.match(doc.error, /^live-run lookup failed: connect ECONNREFUSED$/);
    assert.equal(doc.live, false);
    assert.ok(doc.events.length > 0, "the events still came through");
  } finally { srv.close(); }
});

test("a bad ticket number is answered, not thrown", async () => {
  const doc = await fetchTranscript("not-a-number", { getter: vmGetter([]) });
  assert.match(doc.error, /^not a ticket number: /);
  assert.deepEqual(doc.events, []);
  assert.equal(doc.live, false);
});

test("nothing shipped for the ticket yet is an empty pane, not an error", async () => {
  const srv = await logsServer(() => "");
  try {
    const doc = await fetchTranscript(1283, { base: srv.base, getter: vmGetter(ACTIVE) });
    assert.equal(doc.error, null);
    assert.equal(doc.session, null);
    assert.deepEqual(doc.events, []);
  } finally { srv.close(); }
});

// One doctor run's transcript (#705): the ticket detail's Doctor section reads
// the run's own log file, named by GET /doctor/history's `smithRunId`.
const { fetchDoctorTranscript, doctorTranscriptQuery, doctorLogName, DOCTOR_TRANSCRIPT_WINDOW } = require("../src/transcript-data");
const RUN = "1a2b3c4d-9a1c-11f1-8f5e-0242ac120002";

test("a named doctor run's transcript query is its one log file, in the exact-match form", () => {
  assert.equal(doctorLogName(697, RUN), `issue-697-doctor-${RUN}.log`);
  assert.equal(doctorTranscriptQuery(697, RUN),
    `_time:${DOCTOR_TRANSCRIPT_WINDOW} issue:="697" AND phase:="doctor" AND filename:="issue-697-doctor-${RUN}.log"` +
    ` | sort by (_time) desc | limit ${TRANSCRIPT_LIMIT} | fields _time, _msg, filename`);
});

test("a doctor run that ended is read whole, through the transcript formatter, oldest first", async () => {
  const asked = [];
  const poster = async (req) => { asked.push(req); return LINES([...rows].reverse()); };
  const d = await fetchDoctorTranscript(697, RUN, { base: "http://logs", poster });
  assert.equal(d.error, null);
  assert.equal(d.session, `issue-697-doctor-${RUN}.log`);
  assert.deepEqual(d.lines, expected);
  assert.equal(asked.length, 1);
  assert.equal(asked[0].query, doctorTranscriptQuery(697, RUN));
});

test("a doctor transcript read never rejects, and refuses a run id that is not one before any request", async () => {
  let asked = 0;
  const poster = async () => { asked++; throw new Error("connect ECONNREFUSED"); };
  const dead = await fetchDoctorTranscript(697, RUN, { poster });
  assert.match(dead.error, /transcript lookup failed: connect ECONNREFUSED/);
  assert.deepEqual(dead.lines, []);
  for (const bad of ['x" OR issue:*', "", null]) {
    const d = await fetchDoctorTranscript(697, bad, { poster });
    assert.match(d.error, /not a run id/);
  }
  assert.match((await fetchDoctorTranscript("abc", RUN, { poster })).error, /not a ticket number/);
  assert.equal(asked, 1, "only the well-formed read reached VictoriaLogs");
});

test("no logs_url or metrics_url: the transcript names each missing key and never rejects", async () => {
  const doc = await fetchTranscript(1283, {});
  assert.match(doc.error, /`metrics_url` is not set in agent-army\.config\.json/);
  assert.match(doc.error, /`logs_url` is not set in agent-army\.config\.json/);
  assert.deepEqual(doc.events, []);
  const d = await fetchDoctorTranscript(697, "abc-1");
  assert.match(d.error, /`logs_url` is not set/);
});
