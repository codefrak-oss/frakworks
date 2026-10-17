// The merged PR's main build, the strip after the ticket detail's progress
// bar (#100090): src/renderer/main-build.jsx.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const { MainBuildView, mergedSha, matchMainBuild, mainBuildLabel } = require("../dist/main-build.cjs");

const NOW = Date.parse("2026-10-16T04:00:00Z");
const SHA = "3ba86cf0123456789";
const run = (o) => ({ area: "images", trigger: "main", headSha: SHA, status: "in_progress", url: "https://github.com/o/r/actions/runs/1",
  startedAt: "2026-10-16T03:55:48Z", ...o });
const draw = (props) => renderToStaticMarkup(React.createElement(MainBuildView, { now: NOW, ...props }));

test("the merge sha is the merged PR's; no PR, an open PR or a merged one without a sha is not merged", () => {
  assert.equal(mergedSha([{ state: "OPEN", mergeSha: null }, { state: "MERGED", mergeSha: SHA }]), SHA);
  assert.equal(mergedSha([]), null);
  assert.equal(mergedSha(null), null);
  assert.equal(mergedSha([{ state: "OPEN", mergeSha: null }]), null);
  assert.equal(mergedSha([{ state: "MERGED", mergeSha: null }]), null);
});

test("the run is the images run on main whose headSha is the merge commit", () => {
  const right = run({ id: 3 });
  const runs = [run({ id: 1, area: "engine" }), run({ id: 2, trigger: "pr" }), run({ id: 4, headSha: "other" }), right];
  assert.equal(matchMainBuild(runs, SHA), right);
  assert.equal(matchMainBuild(runs.slice(0, 3), SHA), null);
  assert.equal(matchMainBuild(runs, null), null);
});

test("labels: not started, queued and running tick from startedAt, completed shows the outcome and its duration", () => {
  assert.deepEqual(mainBuildLabel(null, NOW), { state: "none", text: "Main build (images) not started" });
  assert.deepEqual(mainBuildLabel(run({}), NOW), { state: "running", text: "Main build (images) running 4:12" });
  assert.deepEqual(mainBuildLabel(run({}), NOW + 1000), { state: "running", text: "Main build (images) running 4:13" });
  assert.equal(mainBuildLabel(run({ status: "queued", startedAt: null, createdAt: "2026-10-16T03:59:30Z" }), NOW).text, "Main build (images) queued 0:30");
  assert.deepEqual(mainBuildLabel(run({ status: "completed", conclusion: "success", durationSeconds: 731 }), NOW),
    { state: "success", text: "Main build (images) success in 12:11" });
  assert.equal(mainBuildLabel(run({ status: "completed", conclusion: "failure", durationSeconds: 61 }), NOW).state, "failure");
  assert.equal(mainBuildLabel(run({ status: "completed", conclusion: "cancelled", durationSeconds: null,
    completedAt: "2026-10-16T03:57:48Z" }), NOW).text, "Main build (images) cancelled in 2:00");
});

test("the strip: nothing without a merged sha, a link to the run, not started, and an inline error", () => {
  assert.equal(draw({ sha: null, result: { runs: [run({})] } }), "");
  const html = draw({ sha: SHA, result: { runs: [run({})] } });
  assert.match(html, /href="https:\/\/github.com\/o\/r\/actions\/runs\/1"/);
  assert.match(html, /running 4:12/);
  assert.match(html, /merge 3ba86cf/);
  const none = draw({ sha: SHA, result: { runs: [run({ headSha: "x" })] } });
  assert.match(none, /not started/);
  assert.doesNotMatch(none, /href=/);
  const err = draw({ sha: SHA, result: { error: "engine answered 502" } });
  assert.match(err, /class="mainBuild mbError err"/);
  assert.match(err, /engine answered 502/);
});
