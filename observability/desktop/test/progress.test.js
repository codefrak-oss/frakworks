// The Issues view's live progress bar (issues.jsx, progressOf/ProgressBar):
// the stage puts the bar in its segment, the fill inside it is elapsed time
// over the engine's estimate, capped at the segment's end. Against
// fixture-issues.json and hand-built flows.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { Issues, stageSegment, progressOf, partsProgress, fmtDuration, STAGE_SEGMENTS, STAGE_PHRASES, PROGRESS_TICK_MS } = require("../dist/issues.cjs");

const board = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-issues.json"), "utf8"));
const byNumber = Object.fromEntries(board.issues.map((i) => [i.number, i]));
const MIN = 60000;
const t0 = Date.parse("2026-09-25T11:02:00Z");

// A running flow of `kind` at `stage`, entered at t0, with a 30-minute estimate.
const at = (kind, stage, flow = {}, issue = {}) => ({
  kind, labels: [kind], children: [], pendingQuestion: null, ...issue,
  flow: { flow: kind, outcome: "started", ended: false, stage, incident: null,
    stageStartedAt: new Date(t0).toISOString(), stageEstimateMs: 30 * MIN, stageRound: 1, ...flow },
});

test("every agreed stage has its segment, per kind", () => {
  const chore = {
    "start-chore-filed": [0, 5], "call-do-work": [5, 50], "task-open-pr": [50, 55], "call-ci-check": [50, 55],
    "call-security-review": [55, 70], "call-review-pr": [70, 90], "task-merge-pr": [90, 96], "task-close-ticket": [90, 96], "call-post-deploy-check": [96, 100],
  };
  for (const [stage, [start, end]] of Object.entries(chore)) assert.deepEqual(stageSegment("chore", stage), { start, end }, stage);
  // bug adds reproduce up front; feature adds design before the work and acceptance before the close
  assert.deepEqual(stageSegment("bug", "call-reproduce"), { start: 5, end: 15 });
  assert.deepEqual(stageSegment("bug", "call-do-work"), { start: 15, end: 50 });
  assert.ok(stageSegment("bug", "call-verify").start >= 90);
  assert.deepEqual(stageSegment("feature", "call-design"), { start: 5, end: 15 });
  assert.deepEqual(stageSegment("feature", "call-do-work"), { start: 15, end: 50 });
  const accept = stageSegment("feature", "call-accept");
  assert.ok(accept.start > stageSegment("feature", "task-merge-pr").start && accept.end <= stageSegment("feature", "task-close-ticket").start);
  // a question sits in the segment of the step that asked it
  assert.deepEqual(stageSegment("chore", "user-task-answer-question"), stageSegment("chore", "call-do-work"));
  assert.deepEqual(stageSegment("feature", "user-task-answer-question"), stageSegment("feature", "call-design"));
  assert.deepEqual(stageSegment("bug", "user-task-answer-reproduce"), stageSegment("bug", "call-reproduce"));
  // intake: refining is the largest segment, and a question or a duplicate check holds in its asker's segment
  const sizes = (kind) => STAGE_SEGMENTS[kind].map(([, a, b]) => b - a);
  const refine = stageSegment("intake", "call-refine-ask");
  assert.equal(refine.end - refine.start, Math.max(...sizes("intake")));
  assert.deepEqual(stageSegment("intake", "user-task-answer-question"), refine);
  assert.deepEqual(stageSegment("intake", "task-post-question"), refine);
  assert.deepEqual(stageSegment("intake", "user-task-answer-duplicate"), stageSegment("intake", "task-check-duplicate"));
  assert.deepEqual(stageSegment("intake", "task-post-duplicate"), stageSegment("intake", "task-check-duplicate"));
  // proposal: drafting is the largest, review follows it, the decision after the PR
  const draft = stageSegment("proposal", "call-draft");
  assert.equal(draft.end - draft.start, Math.max(...sizes("proposal")));
  assert.equal(stageSegment("proposal", "call-review").start, draft.end);
  const decision = stageSegment("proposal", "user-task-answer-decision");
  assert.deepEqual(stageSegment("proposal", "task-post-decision"), decision);
  assert.deepEqual(stageSegment("proposal", "task-read-decision"), decision);
  assert.ok(decision.start >= stageSegment("proposal", "task-open-pr").end);
  assert.deepEqual(stageSegment("proposal", "user-task-answer-question"), draft);
});

test("every activity of the chore, bug, feature, intake and proposal flows is in exactly one segment, and the segments run in order", () => {
  const bpmn = path.join(__dirname, "..", "..", "..", "engine", "bpmn");
  for (const kind of ["chore", "bug", "feature", "intake", "proposal"]) {
    const xml = fs.readFileSync(path.join(bpmn, `${kind}.bpmn`), "utf8");
    const ids = [...xml.matchAll(/<bpmn:(?:startEvent|intermediateCatchEvent|boundaryEvent|callActivity|serviceTask|sendTask|userTask)\b[^>]*\bid="([^"]+)"/g)].map((m) => m[1]);
    const mapped = STAGE_SEGMENTS[kind].flatMap(([stages]) => stages);
    for (const id of ids) assert.ok(mapped.includes(id), `${kind}.bpmn's ${id} has a segment`);
    for (const id of mapped) assert.ok(STAGE_PHRASES[id], `${id} is a known stage`);
    for (const [, start, end] of STAGE_SEGMENTS[kind]) assert.ok(start >= 0 && start < end && end <= 100, `${kind} ${start}-${end}`);
    if (kind === "intake" || kind === "proposal") {
      for (const id of ids) assert.equal(mapped.filter((m) => m === id).length, 1, `${kind}'s ${id} in exactly one segment`);
      const rows = STAGE_SEGMENTS[kind];
      assert.equal(rows[0][1], 0);
      assert.equal(rows[rows.length - 1][2], 100);
      rows.slice(1).forEach(([, start], i) => assert.equal(start, rows[i][2], `${kind} segments are contiguous`));
    }
  }
});

test("a chore coding fills 5% to 50% with elapsed time, and holds at 50% past its estimate", () => {
  const issue = at("chore", "call-do-work");
  assert.equal(progressOf(issue, t0).pct, 5);
  assert.equal(progressOf(issue, t0 + 15 * MIN).pct, 27.5);
  assert.equal(progressOf(issue, t0 + 30 * MIN).pct, 50);
  assert.equal(progressOf(issue, t0 + 300 * MIN).pct, 50, "never a phase it has not reached");
  const widths = [0, 1, 5, 10, 20, 29].map((m) => progressOf(issue, t0 + m * MIN).pct);
  assert.deepEqual(widths, [...widths].sort((a, b) => a - b));
  assert.ok(widths.every((w) => w >= 5 && w <= 50));
  assert.equal(progressOf(issue, t0 - MIN).pct, 5, "a clock behind the engine's is no negative fill");
});

test("a new stage moves the bar to its segment", () => {
  assert.equal(progressOf(at("chore", "call-security-review"), t0).pct, 55);
  assert.equal(progressOf(at("chore", "call-review-pr"), t0 + 30 * MIN).pct, 90);
  assert.equal(progressOf(at("feature", "call-design"), t0 + 15 * MIN).pct, 10);
  assert.equal(progressOf(at("bug", "call-reproduce"), t0 + 30 * MIN).pct, 15);
});

test("a stage waiting on the user, an incident, a merge conflict or a rework round holds at its segment start", () => {
  const later = t0 + 20 * MIN;
  const cases = [
    at("chore", "user-task-answer-question", { stageEstimateMs: null }),
    at("feature", "user-task-answer-question", { stageEstimateMs: null }),
    at("chore", "call-do-work", { incident: { activityId: "task-start-smith", type: "failedJob" } }),
    at("chore", "event-merge-conflict"),
    at("chore", "call-do-work", { stageRound: 2 }),
    at("chore", "call-do-work", { stageEstimateMs: null }),
    at("chore", "call-do-work", { stageStartedAt: null }),
  ];
  for (const issue of cases) {
    const p = progressOf(issue, later);
    assert.equal(p.hold, true, `${issue.flow.stage} holds`);
    assert.equal(p.pct, p.start, `${issue.flow.stage} at its start`);
  }
  assert.equal(progressOf(at("feature", "call-accept", { stageEstimateMs: null }), later).pct, 88);
  assert.equal(progressOf(at("chore", "call-do-work"), later).hold, false);
});

test("a split parent is its parts merged over its parts, dropped ones left out", () => {
  const children = [{ state: "merged" }, { state: "open" }, { state: "pending" }, { state: "merged" }, { state: "dropped" }];
  const p = progressOf(at("feature", "event-child-closed", {}, { children, deferred: true }), t0);
  assert.equal(p.pct, 50);
  assert.match(p.title, /2 of 4 parts merged/);
  const parent = progressOf(byNumber[60], t0);
  assert.equal(parent.pct, 100 / 3, "the recorded parent: one of three parts merged");
});

test("a split parent's bar weighs its parts by story points", () => {
  const split = (children) => progressOf(at("feature", "event-child-closed", {}, { children }), t0);
  const p = split([{ state: "merged", points: 3 }, { state: "open", points: 5 }, { state: "dropped", points: 13 }]);
  assert.equal(p.pct, 37.5);
  assert.match(p.title, /3 of 8 pts merged$/);
  assert.equal(p.estimated, false);
  const big = split([{ state: "merged", points: 8 }, { state: "open", points: 1 }]);
  assert.ok(big.pct > 50, "a large merged part outweighs a small open one");
  assert.equal(split([{ state: "satisfied", points: 2 }, { state: "open", points: 2 }]).pct, 50);
});

test("a part with no points weighs its pointed siblings' mean, and the bar says it is estimated", () => {
  const unpointed = progressOf(at("feature", "event-child-closed", {}, { children: [
    { state: "merged", points: 2 }, { state: "open", points: 3 }, { state: "open", number: 9 }] }), t0);
  assert.match(unpointed.title, /2 of 7.5 pts merged \(1 of 3 parts estimated\)/);
  assert.equal(unpointed.estimated, true);
  assert.deepEqual(partsProgress([{ state: "merged", points: 1 }, { state: "pending" }, { state: "open", points: 2 }]),
    { done: 1, total: 4.5, unit: "pts", parts: 3, estimated: 1 }, "a pending draft is estimated the same way");
});

test("with no points at all a split parent counts its parts", () => {
  const p = progressOf(at("feature", "event-child-closed", {}, { children: [{ state: "merged" }, { state: "open" }, { state: "open" }] }), t0);
  assert.match(p.title, /1 of 3 parts merged$/);
  assert.equal(p.estimated, false);
});

test("a stage waiting on a person in an intake or a proposal holds, whatever the clock and estimate say", () => {
  const cases = [
    ["intake", "event-intake-authorized"], ["intake", "user-task-answer-duplicate"], ["intake", "user-task-answer-question"],
    ["proposal", "user-task-answer-question"], ["proposal", "user-task-answer-decision"],
  ];
  for (const [kind, stage] of cases) {
    const p = progressOf(at(kind, stage), t0 + 20 * MIN);
    assert.equal(p.hold, true, `${kind} ${stage} holds`);
    assert.equal(p.pct, stageSegment(kind, stage).start, `${kind} ${stage} at its start`);
  }
});

test("an intake started by POST /intake (no outcome, an instance) refining gets a bar that fills its segment", () => {
  const issue = at("intake", "call-refine-ask", { outcome: null, instance: "i-1" });
  const seg = stageSegment("intake", "call-refine-ask");
  const mid = progressOf(issue, t0 + 10 * MIN).pct;
  assert.ok(mid > seg.start && mid < seg.end, `${mid}`);
  assert.equal(progressOf(issue, t0 + 30 * MIN).pct, seg.end);
  assert.equal(progressOf(issue, t0 + 90 * MIN).pct, seg.end);
  assert.equal(progressOf(at("intake", "call-refine-ask", { outcome: null, instance: null }), t0), null, "no instance, no flow");
});

test("no bar for an issue with no running flow, or an ended one", () => {
  assert.equal(progressOf(byNumber[65], t0), null, "ended");
  assert.equal(progressOf(byNumber[19], t0), null, "no flow");
  assert.ok(stageSegment("intake", "call-refine-ask"));
  assert.ok(progressOf(at("intake", "call-refine-ask"), t0));
  assert.ok(progressOf(at("proposal", "call-draft"), t0));
  assert.equal(progressOf(at("intake", "call-refine-ask", { ended: true }), t0), null, "ended intake");
  assert.equal(progressOf(at("proposal", "call-draft", { ended: true, outcome: null, instance: "p" }), t0), null, "ended proposal");
  assert.equal(progressOf({ ...at("chore", "call-do-work"), flow: { flow: "chore", outcome: "deferred", detail: "waiting on #3" } }, t0), null);
});

test("the tooltip says the stage phrase, the elapsed time and the estimate", () => {
  assert.equal(progressOf(at("chore", "call-do-work"), t0 + 12 * MIN).title, "coding · 12m elapsed · est. 30m");
  assert.equal(progressOf(at("chore", "user-task-answer-question", { stageEstimateMs: null }), t0 + 65 * MIN).title,
    "waiting on your answer · 1h 05m elapsed · no estimate");
  assert.equal(fmtDuration(45000), "45s");
  assert.equal(fmtDuration(2 * 86400000 + 3 * 3600000), "2d 3h");
});

test("the recorded board draws a bar on each running row, with its width and title, and none on the rest", () => {
  const now = Date.parse("2026-09-25T11:17:00Z");
  const html = renderToStaticMarkup(React.createElement(Issues, { result: board, now }));
  const chore = byNumber[64];
  assert.equal(chore.flow.stage, "call-do-work");
  assert.equal(typeof chore.flow.stageEstimateMs, "number");
  assert.ok(html.includes('title="coding · 15m elapsed · est. 30m"'), "the running chore's tooltip");
  assert.ok(html.includes('style="width:27.5%"'), "15 of 30 minutes: halfway through 5-50%");
  assert.equal([...html.matchAll(/class="isProgress( hold)?"/g)].length, 5, "#64, #62, the split parent #60, the intake #90 and the proposal #91");
  assert.equal([...html.matchAll(/class="isProgressFill"/g)].length, 5);
  assert.ok(html.includes('title="refining the ask · 22m elapsed · est. 10m (role default)"'), "the intake's tooltip");
  assert.ok(html.includes('title="drafting the proposal · 25m elapsed · est. 10m (role default)"'), "the proposal's tooltip");
  for (const n of [65, 19]) assert.equal(progressOf(byNumber[n], now), null);
  assert.equal(PROGRESS_TICK_MS, 1000);
});

test("the recorded board carries the new flow fields: set on running flows, null on ended ones", () => {
  for (const i of board.issues.filter((x) => x.flow && x.flow.outcome === "started")) {
    for (const k of ["stageStartedAt", "stageEstimateMs", "stageRound"]) assert.ok(k in i.flow, `#${i.number} has ${k}`);
    if (i.flow.ended) for (const k of ["stageStartedAt", "stageEstimateMs", "stageRound"]) assert.equal(i.flow[k], null);
    else assert.ok(i.flow.stageStartedAt && i.flow.stageRound >= 1);
  }
});

test("the bar's clock is a local timer: issues.jsx makes no request of its own", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "issues.jsx"), "utf8");
  const bar = src.slice(src.indexOf("export const ProgressBar"), src.indexOf("const Row = "));
  assert.match(bar, /setInterval\(\(\) => setTick\(Date\.now\(\)\), PROGRESS_TICK_MS\)/);
  assert.match(bar, /clearInterval/);
  assert.doesNotMatch(bar, /fetch|window\.fleet|onRefresh/);
});
