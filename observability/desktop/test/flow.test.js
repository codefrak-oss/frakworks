// The issue detail the Issues board opens: src/engine-data.js fetchFlow
// against a stand-in fetch, and flow.jsx against a flow recorded from the
// local stack (fixture-flow.json, `curl localhost:8090/intake/<id>`,
// 2026-09-24: intake #28 waiting on its question; the base64 run log
// was cut to 4100 characters, still over the limit fetchFlow drops at).
// That engine predates the steps' times, so the times are tested inline.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { fetchFlow, DEFAULT_ENGINE_URL } = require("../src/engine-data");
const { instanceOf, stepRows, DoctorRuns, DoctorTranscript, issueNumberOf } = require("../dist/flow.cjs");
const { TicketDetail } = require("../dist/index.cjs");

const raw = fs.readFileSync(path.join(__dirname, "..", "fixture-flow.json"), "utf8");
const board = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-issues.json"), "utf8"));
const ID = JSON.parse(raw).intakeId;
const stubFetch = (status, body, calls = []) => async (url) => {
  calls.push(url);
  if (status instanceof Error) throw status;
  return { ok: status >= 200 && status < 300, status, text: async () => body };
};
const render = (props) => renderToStaticMarkup(React.createElement(TicketDetail, props));

test("fetchFlow reads /intake/<id> and keeps the blobs in the main process", async () => {
  const calls = [];
  const r = await fetchFlow(ID, { env: {}, fetch: stubFetch(200, raw, calls) });
  assert.equal(r.error, null);
  assert.equal(calls[0], `${DEFAULT_ENGINE_URL}/intake/${ID}`);
  assert.deepEqual(r.flow.omitted, ["log"]);
  assert.equal(r.flow.variables.log, undefined);
  assert.equal(r.flow.variables.outcome, "ask");
  assert.ok(JSON.stringify(r.flow).length < 20000);
});

test("fetchFlow refuses an id that is not one before any request, and says a 404 plainly", async () => {
  const calls = [];
  for (const bad of ["../accounts", "", null, "x y"]) {
    assert.match((await fetchFlow(bad, { env: {}, fetch: stubFetch(200, raw, calls) })).error, /not a flow instance id/);
  }
  assert.equal(calls.length, 0);
  assert.match((await fetchFlow(ID, { env: {}, fetch: stubFetch(404, "") })).error, /has no flow/);
  assert.match((await fetchFlow(ID, { env: {}, fetch: stubFetch(200, "{}") })).error, /no `steps` list/);
  assert.match((await fetchFlow(ID, { env: {}, fetch: stubFetch(new Error("ECONNREFUSED")) })).error, /unreachable/);
});

test("an issue's flow is its approval's instance, else the intake its question waits on", () => {
  assert.equal(instanceOf({ flow: { instance: "a" }, pendingQuestion: { intake: "b" } }), "a");
  assert.equal(instanceOf({ flow: null, pendingQuestion: { intake: "b" } }), "b");
  assert.equal(instanceOf({ flow: { instance: null }, pendingQuestion: null }), null);
});

test("the steps drop the unnamed seams; without times the last step of a running flow is open", async () => {
  const { flow } = await fetchFlow(ID, { env: {}, fetch: stubFetch(200, raw) });
  const rows = stepRows(flow);
  assert.ok(rows.every((r) => r.name));
  assert.equal(rows.at(-1).label, "answer the smith's question");
  assert.deepEqual(rows.filter((r) => r.open).map((r) => r.label), ["answer the smith's question"]);
  assert.ok(!rows.some((r) => r.label.includes("\n")));
});

test("with times, every step without an end is open, and none once the flow ended", () => {
  const steps = [
    { process: "chore", activity: "a", name: "one", startedAt: "2026-09-24T10:00:00Z", endedAt: "2026-09-24T10:01:00Z" },
    { process: "smith-run", activity: "b", name: "poll smith", startedAt: "2026-09-24T10:01:00Z", endedAt: null },
  ];
  assert.deepEqual(stepRows({ ended: false, steps }).map((r) => r.open), [false, true]);
  assert.deepEqual(stepRows({ ended: true, steps }).map((r) => r.open), [false, false]);
});

test("the detail draws the board's row, the waiting question and the ask", async () => {
  const issue = board.issues.find((i) => i.pendingQuestion) || {
    repo: "codefrak-oss/frakworks", number: 28, title: "t", author: "a", labels: ["intake"], url: "u",
    flow: null, pendingQuestion: JSON.parse(raw).questions[0],
  };
  const { flow } = await fetchFlow(ID, { env: {}, fetch: stubFetch(200, raw) });
  const html = render({ repo: issue.repo, number: issue.number, issue, flowResult: { flow }, now: Date.parse("2026-09-24T15:00:00Z") });
  assert.match(html, /reading the HITLP task/, "the waiting question's HITLP task, inline (#100070)");
  assert.match(html, /waiting on you/);
  assert.doesNotMatch(html, /c21pdGg6/); // the base64 log never draws
});

test("the ticket detail draws no Steps fold, remembered open or not", async () => {
  const { flow } = await fetchFlow(ID, { env: {}, fetch: stubFetch(200, raw) });
  global.localStorage = { getItem: () => "true", setItem: () => {} };
  let html;
  try {
    html = render({ repo: "codefrak-oss/frakworks", number: 28, issue: null, flowResult: { flow }, now: Date.parse("2026-09-24T15:00:00Z") });
  } finally { delete global.localStorage; }
  assert.doesNotMatch(html, /<\/span>Steps|flSteps/);
});

test("the run log's tail never draws, even though the flow carries it", () => {
  const issue = {
    repo: "codefrak-oss/frakworks", number: 28, title: "t", author: "a", labels: [], url: "u",
    flow: { flow: "chore", outcome: "started", instance: "x", ended: true }, pendingQuestion: null,
  };
  const flow = {
    ended: true, questions: [], steps: [],
    variables: { outcome: "done", logTail: "NEEDLE-run-log-line-that-must-not-render-anywhere" },
  };
  const html = render({ repo: issue.repo, number: issue.number, issue, flowResult: { flow } });
  assert.doesNotMatch(html, /Last of the run log/);
  assert.doesNotMatch(html, /NEEDLE-run-log-line-that-must-not-render-anywhere/);
});

test("an issue gone from the board and a failed flow read both draw .err, never a blank pane", () => {
  assert.match(render({ repo: "o/r", number: 9, issue: null, flowResult: null, offBoard: { error: null } }), /class="err">o\/r#9 is not on the engine&#x27;s board/);
  const issue = { repo: "o/r", number: 9, title: "t", author: "a", labels: [], url: "u", flow: { flow: "chore", outcome: "started", instance: "x", ended: false }, pendingQuestion: null };
  assert.match(render({ repo: "o/r", number: 9, issue, flowResult: { error: "engine unreachable" } }), /class="err">engine unreachable/);
});

test("the detail lists a split parent's parts with their states, and a child's parent", () => {
  const parent = board.issues.find((i) => i.number === 60);
  const html = render({ repo: parent.repo, number: 60, issue: parent, flowResult: null, now: Date.parse("2026-09-25T12:00:00Z") });
  assert.match(html, /<h2>Parts<\/h2>/);
  assert.match(html, /href="https:\/\/github.com\/codefrak-oss\/frakworks\/issues\/61">#61<\/a>/);
  assert.match(html, /merged \(PR #70\)/);
  assert.match(html, /Part three: the desktop<\/td><td>chore<\/td><td>pending/);
  const child = board.issues.find((i) => i.number === 62);
  const childHtml = render({ repo: child.repo, number: 62, issue: child, flowResult: null, now: Date.parse("2026-09-25T12:00:00Z") });
  assert.match(childHtml, /part of <a href="https:\/\/github.com\/codefrak-oss\/frakworks\/issues\/60">#60<\/a>/);
  assert.doesNotMatch(childHtml, /<h2>Parts<\/h2>/);
});

// The Doctor section, against a fixture shaped like engine/README.md's
// GET /doctor/history example.
const doctor = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-doctor-history.json"), "utf8"));
const drawDoctor = (result) => renderToStaticMarkup(React.createElement(DoctorRuns, { result }));

test("the Doctor section lists every run's times, position, outcome, remedy, target, diagnosis and detail, never null", () => {
  const html = drawDoctor({ runs: doctor.runs });
  for (const want of ["2026-09-27T10:05:00Z", "running", "chore:task-open-pr@none", "incident:5c1d2e3f-9a1c-11f1-8f5e-0242ac120002",
    "2026-09-27T09:40:00Z", "2026-09-26T18:30:00Z", "2026-09-26T18:31:12Z", "chore:task-work@none", "2026-09-26T18:00:00Z",
    "applied", "retry", "incident:9d8e7f6a", "the work run&#x27;s incident is a transient 502", "retried the incident"]) {
    assert.ok(html.includes(want), `shows ${want}`);
  }
  assert.ok(html.indexOf("2026-09-27T10:05:00Z") < html.indexOf("2026-09-26T18:30:00Z"), "newest first, as the engine sends them");
  assert.ok(!/null/.test(html), "no null text");
});

test("the Doctor section says No doctor runs for none, and an error as one line", () => {
  assert.match(drawDoctor({ runs: [] }), /No doctor runs/);
  assert.doesNotMatch(drawDoctor({ runs: [] }), /<table/);
  assert.match(drawDoctor({ error: "engine answered 400" }), /<div class="err">engine answered 400<\/div>/);
});

test("the issue detail has a Doctor fold heading", () => {
  const issue = board.issues[0];
  const html = render({ number: issue.number, repo: issue.repo, issue, flowResult: null, now: Date.now() });
  assert.match(html, /<h2 class="tdFold"[^>]*>[\s\S]*?<span class="tdFoldArrow">▸<\/span>Doctor/);
});

test("the Doctor section folds behind its heading, collapsed by default and remembered per viewer", () => {
  const issue = board.issues[0];
  const opts = { number: issue.number, repo: issue.repo, issue, flowResult: null, doctor: doctor, now: Date.now() };

  // Nothing remembered: collapsed, the heading carries the fold's classes and the arrow points right.
  const store = {};
  global.localStorage = { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = v; } };
  try {
    let html = render(opts);
    assert.match(html, /<h2 class="tdFold"[^>]*>[\s\S]*?<span class="tdFoldArrow">▸<\/span>Doctor/);
    assert.doesNotMatch(html, /flDoctor/);

    // Remembered open: the table draws and the arrow flips.
    store["ticket.doctorOpen"] = "true";
    html = render(opts);
    assert.match(html, /<span class="tdFoldArrow">▾<\/span>Doctor/);
    assert.match(html, /flDoctor/);
  } finally { delete global.localStorage; }
});

// The board's ticket detail (#705): the Doctor section lists the ticket's
// doctor runs with their outcomes, each with its transcript, next to the
// incident actions.
test("the ticket detail's Doctor section lists both fixture runs, their outcomes, and a transcript for each", () => {
  global.localStorage = { getItem: (k) => (k === "ticket.doctorOpen" ? "true" : null), setItem: () => {} };
  let html;
  try {
    html = render({ number: 7, repo: "acme/widgets", flowResult: null, doctor: doctor, now: Date.now() });
  } finally { delete global.localStorage; }
  const section = html.slice(html.indexOf("Doctor"));
  assert.ok(html.includes("Doctor"));
  assert.equal((section.match(/<tr>/g) || []).length, 2, "both runs");
  assert.ok(section.indexOf("<b>running</b>") >= 0 && section.indexOf("<b>applied</b>") > section.indexOf("<b>running</b>"),
    "both outcomes, newest first");
  for (const want of ["remedy retry", "target incident:9d8e7f6a", "the work run&#x27;s incident is a transient 502", "retried the incident"]) {
    assert.ok(section.includes(want), `shows ${want}`);
  }
  assert.equal((section.match(/▸ Transcript/g) || []).length, 2, "each run offers its transcript, the ended one too");
  assert.ok(section.includes(`issue-7-doctor-${doctor.runs[1].smithRunId}.log`), "the ended run's log file");
});

test("a doctor run's transcript shows its formatted lines, and a run with no Smith run says so", () => {
  const open = renderToStaticMarkup(React.createElement(DoctorTranscript, {
    ticket: 7, runId: "7e6f5a4b-9a1c-11f1-8f5e-0242ac120002", initialOpen: true,
    initial: { lines: ["Reading the stuck position.", "── result: success · 3 turns"], error: null },
  }));
  assert.match(open, /▾ Hide transcript/);
  assert.match(open, /<pre class="tdTranscript"><span>Reading the stuck position.\n\n<\/span><span>── result/);
  const failed = renderToStaticMarkup(React.createElement(DoctorTranscript, {
    ticket: 7, runId: "x", initialOpen: true, initial: { lines: [], error: "transcript lookup failed: down" } }));
  assert.match(failed, /<div class="err">transcript lookup failed: down<\/div>/);
  assert.match(failed, /no events shipped for this run/);
  assert.match(renderToStaticMarkup(React.createElement(DoctorTranscript, { ticket: 7, runId: null, running: true })),
    /no transcript: its Smith has not started/);
  assert.doesNotMatch(drawDoctor({ runs: doctor.runs }), /Transcript/, "no ticket, no transcript (the bare table)");
  assert.equal(issueNumberOf("https://github.com/acme/widgets/issues/7"), 7);
  assert.equal(issueNumberOf("https://github.com/acme/widgets/pull/7"), null);
});
