// builds.jsx — Analytics > Builds (#1935), drawn from fixture-ci-runs.json (the
// shape GET /repos/{owner}/{name}/ci/runs answers, engine/README.md "CI runs of
// a repo"): the Running and History sections, the kind chips, the 15 s re-read
// and the error lines.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { BuildsView, runningRuns, historyRuns, kindsOf, shownRuns, pollEvery, POLL_MS, LIMITS, DEFAULT_LIMIT } = require("../dist/builds.cjs");

const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, "fixture-ci-runs.json"), "utf8"));
const NOW = Date.parse("2026-10-09T12:00:00Z");
const render = (props) => renderToStaticMarkup(React.createElement(BuildsView, { repo: "acme/widgets", now: NOW, ...props }));
const section = (html, name) => {
  const start = html.indexOf(`<h2>${name}</h2>`);
  assert.ok(start >= 0, `a ${name} section`);
  const next = html.indexOf("<h2>", start + 1);
  return html.slice(start, next < 0 ? undefined : next);
};
const ids = (html) => [...new Set([...html.matchAll(/actions\/runs\/(\d+)/g)].map((m) => Number(m[1])))];

test("Running lists the runs not completed, oldest first, with their elapsed time; History the completed ones, newest first", () => {
  assert.deepEqual(runningRuns(fixture.runs).map((r) => r.id), [4, 5]);
  assert.deepEqual(historyRuns(fixture.runs).map((r) => r.id), [3, 2, 1]);
  const html = render({ result: { runs: fixture.runs } });
  assert.ok(html.indexOf("<h2>Running</h2>") < html.indexOf("<h2>History</h2>"), "Running above History");
  const running = section(html, "Running");
  assert.deepEqual(ids(running), [4, 5]);
  assert.match(running, /10:00/, "run 4 created 10 minutes ago");
  assert.match(running, /2:00/, "run 5 created 2 minutes ago");
  const history = section(html, "History");
  assert.deepEqual(ids(history), [3, 2, 1]);
  for (const h of ["started", "area", "trigger", "workflow", "branch", "conclusion", "duration"]) assert.ok(history.includes(`>${h}</th>`), h);
  assert.match(history, /class="cibRow ci-success"/);
  assert.match(history, /class="cibRow ci-failure"/);
  assert.match(history, />10:00<\/td>/, "run 3's duration");
  assert.match(history, /title="2026-10-09 11:00 UTC"/, "the UTC instant in the tooltip");
  assert.match(history, /href="https:\/\/github.com\/acme\/widgets\/actions\/runs\/3"/);
});

test("no running run says so", () => {
  const html = render({ result: { runs: historyRuns(fixture.runs) } });
  assert.match(section(html, "Running"), /no builds running/);
});

test("a chip per distinct area and trigger, all on by default; one off hides its rows in both sections, and toggles back", () => {
  assert.deepEqual(kindsOf(fixture.runs), { area: ["engine", "images", "desktop-release"], trigger: ["pr", "main", "release"] });
  const all = render({ result: { runs: fixture.runs } });
  for (const k of ["area:engine", "area:images", "area:desktop-release", "trigger:pr", "trigger:main", "trigger:release"]) {
    assert.match(all, new RegExp(`class="cibChip" data-kind="${k}"`), k);
  }
  // area engine off: run 5 leaves Running, runs 3 and 2 leave History
  const noEngine = render({ result: { runs: fixture.runs }, hidden: { "area:engine": true } });
  assert.match(noEngine, /class="cibChip off" data-kind="area:engine"/);
  assert.deepEqual(ids(section(noEngine, "Running")), [4]);
  assert.deepEqual(ids(section(noEngine, "History")), [1]);
  // trigger pr off: runs 5 and 2 go
  const noPr = render({ result: { runs: fixture.runs }, hidden: { "trigger:pr": true } });
  assert.deepEqual(ids(section(noPr, "Running")), [4]);
  assert.deepEqual(ids(section(noPr, "History")), [3, 1]);
  // clicked again it is on, and the rows are back
  assert.deepEqual(shownRuns(fixture.runs, { "trigger:pr": false }).map((r) => r.id), [5, 4, 3, 2, 1]);
});

test("the limit selector offers 25, 50 and 100, 50 by default", () => {
  assert.deepEqual(LIMITS, [25, 50, 100]);
  assert.equal(DEFAULT_LIMIT, 50);
  const html = render({ result: { runs: [] }, onRefresh: () => {} });
  assert.match(html, /<option value="50" selected="">50<\/option>/);
  assert.match(html, /title="Refresh now"[^>]*>⟳</);
});

test("the page re-reads every 15 s: the interval is set on mount and cleared on unmount", () => {
  assert.equal(POLL_MS, 15000);
  const set = [], cleared = [];
  const timers = { setInterval: (fn, ms) => { set.push(ms); return 7; }, clearInterval: (id) => cleared.push(id) };
  const stop = pollEvery(() => {}, POLL_MS, timers);
  assert.deepEqual(set, [15000]);
  assert.deepEqual(cleared, []);
  stop();
  assert.deepEqual(cleared, [7]);
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "builds.jsx"), "utf8");
  assert.match(src, /const stop = pollEvery\(load, POLL_MS\);\n\s*return \(\) => \{ seq\.current\+\+; stop\(\); \};/);
});

test("an older engine's 404 line, a 500 and a body without runs are each one error line; nothing throws", () => {
  const old = "this engine has no /repos/{owner}/{name}/ci/runs (older than this ticket)";
  for (const error of [old, "engine answered 500 for /repos/acme/widgets/ci/runs: boom", "engine's /ci/runs has no runs list"]) {
    const html = render({ result: { error } });
    assert.equal(html.match(/class="err"/g).length, 1);
    assert.ok(html.includes(error.replace(/'/g, "&#x27;")), error);
    assert.doesNotMatch(html, /<h2>Running/);
  }
  assert.doesNotThrow(() => render({ result: null }));
  assert.doesNotThrow(() => render({ result: { runs: [null, {}] } }));
  assert.match(render({ repo: null, result: { loading: true } }), /Pick a repo in the switcher/);
});
