// doctor.jsx — the Doctor page (Explorer > Analytics > Doctor, #706), drawn
// from fixture-doctor-runs.json (the shape GET /doctor/runs answers,
// engine/README.md "Doctor"): the success classification, the strip's counts,
// the runs table with its detail row, and the one-line error.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const {
  classifyDoctorRuns, doctorStats, positionKey, fmtUtc, fmtSinceStarted, DoctorRunsView, DoctorStrip, DEFAULT_WINDOW, sinceOf,
} = require("../dist/doctor.cjs");

const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-doctor-runs.json"), "utf8"));
const render = (C, props) => renderToStaticMarkup(React.createElement(C, props));
const verdicts = (runs) => Object.fromEntries(classifyDoctorRuns(runs).map((r) => [r.id, r.verdict]));

test("each run is classified by the success definition in force", () => {
  assert.deepEqual(verdicts(fixture.runs), {
    "d-1": "failed",    // applied, then d-3 on the same root instance and position: stuck again
    "d-2": "succeeded", // applied, no later run there
    "d-3": "succeeded", // applied, the newest run at its position
    "d-5": "succeeded", // applied; d-6 came later on the same root but at another position
    "d-6": "failed",    // the remedy failed
    "d-7": "none",      // healthy: no intervention
    "d-8": "running",   // not judged
    "d-0a": "failed",   // applied, then d-0b at the same leaf under a new incident id (#751)
    "d-0b": "succeeded", // applied, the newest run at its leaves
  });
  const byId = Object.fromEntries(classifyDoctorRuns(fixture.runs).map((r) => [r.id, r]));
  assert.match(byId["d-1"].why, /stuck again/);
  assert.match(byId["d-0a"].why, /stuck again at the same leaves/);
  assert.equal(byId["d-6"].why, "the doctor run failed (no remedy was applied)");
  assert.equal(byId["d-2"].why, undefined);
});

test("the classification does not depend on the order the runs come in, and keeps the runs' fields", () => {
  const shuffled = [...fixture.runs].reverse();
  assert.deepEqual(verdicts(shuffled), verdicts(fixture.runs));
  const out = classifyDoctorRuns(shuffled);
  assert.deepEqual(out.map((r) => r.id), fixture.runs.map((r) => r.id), "newest first");
  assert.equal(out[0].rootInstanceId, "root-a");
  assert.equal(positionKey(fixture.runs[3]), "chore:task-open-pr@none,incident:inc-5");
});

test("a later run still going at the same position makes an applied run a failure", () => {
  const base = { rootInstanceId: "r", position: ["p@none"], repo: "o/r", issue: 1 };
  assert.deepEqual(verdicts([
    { ...base, id: "a", startedAt: "2026-09-27T10:00:00Z", outcome: "applied" },
    { ...base, id: "b", startedAt: "2026-09-27T11:00:00Z", outcome: "running" },
  ]), { a: "failed", b: "running" });
  // another root instance at the same position is not the same place
  assert.deepEqual(verdicts([
    { ...base, id: "a", startedAt: "2026-09-27T10:00:00Z", outcome: "applied" },
    { ...base, id: "b", rootInstanceId: "r2", startedAt: "2026-09-27T11:00:00Z", outcome: "applied" },
  ]), { a: "succeeded", b: "succeeded" });
});

test("stuck again compares leaves: incident ids are ignored, a different leaf is not the same place", () => {
  const base = { rootInstanceId: "r", repo: "o/r", issue: 1, outcome: "applied" };
  assert.deepEqual(verdicts([
    { ...base, id: "a", position: ["x@none", "incident:1"], startedAt: "2026-09-27T10:00:00Z" },
    { ...base, id: "b", position: ["incident:2", "x@none"], startedAt: "2026-09-27T11:00:00Z" },
  ]), { a: "failed", b: "succeeded" });
  assert.deepEqual(verdicts([
    { ...base, id: "a", position: ["x@none", "incident:1"], startedAt: "2026-09-27T10:00:00Z" },
    { ...base, id: "b", position: ["y@none", "incident:1"], startedAt: "2026-09-27T11:00:00Z" },
  ]), { a: "succeeded", b: "succeeded" });
  // no leaves: the full position decides, never an empty key
  assert.deepEqual(verdicts([
    { ...base, id: "a", position: ["incident:1"], startedAt: "2026-09-27T10:00:00Z" },
    { ...base, id: "b", position: ["incident:2"], startedAt: "2026-09-27T11:00:00Z" },
  ]), { a: "succeeded", b: "succeeded" });
});

test("the strip's counts: runs, distinct tickets, each verdict, judged and the rate", () => {
  assert.deepEqual(doctorStats(classifyDoctorRuns(fixture.runs)),
    { runs: 9, tickets: 5, succeeded: 4, failed: 3, none: 1, running: 1, judged: 7, rate: 4 / 7 });
  const unjudged = doctorStats(classifyDoctorRuns(fixture.runs.filter((r) => r.outcome === "none" || r.outcome === "running")));
  assert.equal(unjudged.judged, 0);
  assert.equal(unjudged.rate, null);
  assert.deepEqual(doctorStats([]), { runs: 0, tickets: 0, succeeded: 0, failed: 0, none: 0, running: 0, judged: 0, rate: null });
});

test("the page draws the window switch, the strip and the runs newest first", () => {
  const html = render(DoctorRunsView, { result: { runs: fixture.runs, since: fixture.since }, win: DEFAULT_WINDOW });
  assert.equal(DEFAULT_WINDOW, "7d");
  assert.match(html, /<h1>Doctor<\/h1>/);
  for (const w of ["24h", "7d", "30d", "90d"]) assert.ok(html.includes(`>${w}</button>`), w);
  assert.match(html, /<button class="on">7d<\/button>/, "the window in force is marked");
  assert.match(html, /<b>9<\/b><span>runs<\/span>/);
  assert.match(html, /<b>5<\/b><span>tickets<\/span>/);
  assert.match(html, /<b>4 \/ 7<\/b><span>succeeded \/ judged · 57%<\/span>/);
  assert.match(html, /<b>4<\/b><span>succeeded<\/span>/);
  assert.match(html, /<b>3<\/b><span>failed<\/span>/);
  assert.match(html, /<b>1<\/b><span>no intervention<\/span>/);
  assert.match(html, /<b>1<\/b><span>running<\/span>/);
  const order = ["d-8", "d-7", "d-6", "d-5", "d-3", "d-2", "d-1", "d-0b", "d-0a"].map((id) => html.indexOf(fmtUtc(fixture.runs.find((r) => r.id === id).startedAt)));
  assert.ok(order.every((i) => i > 0));
  assert.deepEqual([...order].sort((a, b) => a - b), order, "newest first");
  assert.ok(html.includes('title="2026-09-27 11:00 UTC"'), "started at, in UTC, as the tooltip");
  assert.match(html, /<a href="https:\/\/github.com\/codefrak-oss\/frakworks\/issues\/650"[^>]*>codefrak-oss\/frakworks#650<\/a>/);
  assert.ok(html.includes(">chore:task-open-pr@none,incident:inc-5<"), "the position, joined");
  assert.ok(html.includes(">retry → inc-5<"), "remedy and target");
  assert.ok(html.includes(">collect-exit<"), "a remedy with no target");
  assert.match(html, /stuck again at the same leaves/);
  assert.match(html, />no intervention</);
  // the running run's nulls are dashes
  const running = html.slice(html.indexOf("verdict-running"), html.indexOf("verdict-none"));
  assert.equal((running.match(/>—</g) || []).length, 2, "diagnosis and remedy → target");
  assert.doesNotMatch(html, /doctorDetail/, "no row is open");
});

test("a row opens to show its detail and stuckSince, and toggles by its id", () => {
  const toggled = [];
  const html = render(DoctorRunsView, { result: { runs: fixture.runs }, win: "7d", open: { "d-6": true }, onToggle: (id) => toggled.push(id) });
  const detail = html.slice(html.indexOf('class="doctorDetail"'));
  assert.ok(html.indexOf('class="doctorDetail"') > html.indexOf("verdict-failed"));
  assert.match(detail, /stuck since<\/span> 2026-09-27 08:00 UTC/);
  assert.match(detail, /incident inc-6 is no longer part of the ticket&#x27;s position/);
  assert.equal((html.match(/class="doctorDetail"/g) || []).length, 1);
  const closedDetail = render(DoctorRunsView, { result: { runs: fixture.runs }, open: { "d-5": true } });
  assert.match(closedDetail, /detail<\/span> —/, "a null detail is a dash");
});

test("the rate is a dash when nothing could be judged", () => {
  const html = render(DoctorStrip, { stats: doctorStats(classifyDoctorRuns([fixture.runs[0]])), win: "7d" });
  assert.match(html, /<b>–<\/b><span>succeeded \/ judged<\/span>/);
  assert.match(html, /<b>1<\/b><span>running<\/span>/);
  const loading = render(DoctorStrip, { stats: null, win: "7d" });
  assert.match(loading, /<b>–<\/b><span>runs<\/span>/);
});

test("an error or an older engine is one line, with no table", () => {
  for (const error of ["this engine has no /doctor/runs (older than #706)", "engine answered 500 for /doctor/runs"]) {
    const html = render(DoctorRunsView, { result: { error }, win: "7d" });
    assert.equal((html.match(/class="err"/g) || []).length, 1);
    assert.ok(html.includes(error.replace(/</g, "&lt;")));
    assert.doesNotMatch(html, /<table/);
  }
  assert.match(render(DoctorRunsView, { result: { runs: [] }, win: "7d" }), /no doctor runs in the window/);
  assert.match(render(DoctorRunsView, { result: { loading: true } }), /asking the engine/);
});

test("the window switch asks from the window's start", () => {
  const now = Date.parse("2026-09-28T12:00:00Z");
  assert.equal(sinceOf("24h", now), "2026-09-27T12:00:00.000Z");
  assert.equal(sinceOf("7d", now), "2026-09-21T12:00:00.000Z");
  assert.equal(fmtUtc(null), "—");
  assert.equal(fmtUtc("2026-09-27T13:05:00+02:00"), "2026-09-27 11:05 UTC");
});

test("every row cell but the date and the result clips with an ellipsis, so no text overlaps its neighbour (#788)", () => {
  const html = render(DoctorRunsView, { result: { runs: fixture.runs }, win: "7d" });
  const rows = html.match(/<tr class="jevRow[^"]*"[^>]*>.*?<\/tr>/g);
  assert.ok(rows && rows.length);
  for (const row of rows) {
    const tds = row.match(/<td[^>]*>/g);
    assert.equal(tds.length, 7);
    tds.slice(1, 6).forEach((td) => assert.match(td, /class="jevText"/));
  }
});

test("fmtSinceStarted: mm:ss, h:mm:ss, days and hours, the dash and the clamp", () => {
  const now = Date.parse("2026-10-05T12:00:00Z");
  assert.equal(fmtSinceStarted("2026-10-05T11:57:24Z", now), "02:36 ago");
  assert.equal(fmtSinceStarted("2026-10-05T08:52:48Z", now), "3:07:12 ago");
  assert.equal(fmtSinceStarted("2026-10-01T10:00:00Z", now), "4d 2h ago");
  assert.equal(fmtSinceStarted(null, now), "—");
  assert.equal(fmtSinceStarted("nope", now), "—");
  assert.equal(fmtSinceStarted("2026-10-05T12:00:05Z", now), "00:00 ago");
});

test("a row's started cell: the elapsed time at a fixed now, the UTC instant as its title", () => {
  const run = { id: "x", repo: "o/r", issue: 1, startedAt: "2026-10-05T11:57:24Z", outcome: "applied", position: [] };
  const html = render(DoctorRunsView, { result: { runs: [run] }, win: "7d", now: Date.parse("2026-10-05T12:00:00Z") });
  assert.match(html, /<td class="jevWhen" title="2026-10-05 11:57 UTC">02:36 ago<\/td>/);
  assert.match(html, /<th class="jevWhen">started<\/th>/);
});
