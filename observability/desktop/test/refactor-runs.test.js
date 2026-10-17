// Analytics > Refactoring (#1781): src/renderer/refactor-runs.jsx's run line
// (#1653), one repo's runs, the page's sections and loadSection over a stand-in fleet.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { runLine, RefactorRuns, RefactorRunsView, loadSection, RUNS_LIMIT } = require("../dist/refactor-runs.cjs");

const draw = (el) => renderToStaticMarkup(el);

test("runLine: a filing run, a nothing-filed run, a failed run and a scan that found nothing", () => {
  const filed = runLine({ instance: "a", status: "done", scanIssue: 100, scanEndedAt: "t", filed: [101, 102], skipped: { overCap: 1 } });
  assert.deepEqual([filed.badge, filed.filed, filed.text], ["filed", [101, 102], "skipped 1 over the cap"]);
  assert.equal(runLine({ status: "nothing", summary: "filed nothing: refactor-scan #42 is still open" }).text, "did nothing: refactor-scan #42 is still open");
  assert.equal(runLine({ status: "failed", outcome: "failed: refactoring is off for acme/widgets" }).text, "did nothing: refactoring is off for acme/widgets");
  assert.equal(runLine({ status: "done", scanIssue: 100, scanEndedAt: "t", filed: [], foundNothing: true }).text, "did nothing: the scan found nothing to propose");
  assert.equal(runLine({ status: "done", scanIssue: 100, scanEndedAt: "t", filed: [], skipped: { open: 2 }, foundNothing: false }).text, "did nothing: every proposal was skipped (2 open)");
  assert.equal(runLine({ status: "done", scanIssue: 100, scanEndedAt: "t", scanOutcome: "failed" }).text, "did nothing: the scan ended failed");
  assert.equal(runLine({ status: "done", scanIssue: 100 }).badge, "scanning");
  assert.equal(runLine({}).badge, "running");
});

test("RefactorRuns: filed tickets are frakworks://ticket links, and an empty history says so", () => {
  const html = renderToStaticMarkup(React.createElement(RefactorRuns, { repo: "acme/widgets", runs: { runs: [
    { instance: "a", startedAt: "2026-10-07T10:00:00Z", status: "done", scanIssue: 100, scanEndedAt: "t", filed: [101] },
    { instance: "b", startedAt: "2026-10-07T11:00:00Z", status: "nothing", summary: "filed nothing: refactor-scan #100 is still open" },
  ] } }));
  assert.match(html, /href="frakworks:\/\/ticket\/acme\/widgets\/101"/);
  assert.match(html, /did nothing: refactor-scan #100 is still open/);
  assert.match(renderToStaticMarkup(React.createElement(RefactorRuns, { repo: "acme/widgets", runs: { runs: [] } })), /no runs yet/);
});

test("RefactorRunsView: one section per repo, headed by its name, with its runs, an empty history, refactoring off and an error", () => {
  const html = draw(React.createElement(RefactorRunsView, { sections: [
    { repo: "acme/widgets", jobId: "j1", runs: { runs: [{ instance: "a", startedAt: "2026-10-07T10:00:00Z", endedAt: "2026-10-07T10:05:00Z", status: "done", scanIssue: 100, scanEndedAt: "t", filed: [101] }] } },
    { repo: "acme/empty", jobId: "j2", runs: { runs: [] } },
    { repo: "acme/off", jobId: null },
    { repo: "acme/broken", jobId: "j3", runs: { error: "engine said 500" } },
  ] }));
  assert.match(html, /<span class="acName">acme\/widgets<\/span>/);
  assert.match(html, /href="frakworks:\/\/ticket\/acme\/widgets\/101"/);
  assert.match(html, /<span class="acName">acme\/empty<\/span><\/div><div class="rfRuns"><div class="fmmEmpty">no runs yet</);
  assert.match(html, /acme\/off<\/span><\/div><div class="fmmEmpty">refactoring off</);
  assert.match(html, /Runs unavailable: engine said 500/);
  assert.match(draw(React.createElement(RefactorRunsView, { sections: { error: "boom" } })), /Runs unavailable: boom/);
});

test("loadSection: the repo's refactor job by GET /jobs?repo=, its runs at the limit, none when the repo has no refactor job", async () => {
  const calls = [];
  const fleet = {
    jobs: async (repo) => { calls.push(["jobs", repo]); return repo === "acme/off" ? { jobs: [{ id: "p", kind: "prompt" }] } : { jobs: [{ id: "p", kind: "prompt" }, { id: "r1", kind: "refactor" }] }; },
    jobRuns: async (id, limit) => { calls.push(["runs", id, limit]); return { runs: [{ instance: "i" }] }; },
  };
  assert.deepEqual(await loadSection(fleet, "acme/widgets"), { repo: "acme/widgets", jobId: "r1", runs: { runs: [{ instance: "i" }] } });
  assert.deepEqual(await loadSection(fleet, "acme/off"), { repo: "acme/off", jobId: null });
  assert.deepEqual(calls, [["jobs", "acme/widgets"], ["runs", "r1", RUNS_LIMIT], ["jobs", "acme/off"]]);
  assert.ok(RUNS_LIMIT >= 20);
  assert.deepEqual(await loadSection({ jobs: async () => ({ error: "nope" }) }, "x/y"), { repo: "x/y", error: "nope" });
});
