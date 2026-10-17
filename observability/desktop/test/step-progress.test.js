// The ticket detail's open step bar (flow.jsx, stepProgress), drawn on the
// Timeline's running card (timeline.jsx, StepBar, #1014): a live bar, elapsed time over the estimate the engine
// sends with the step (`GET /intake/{id}`'s `estimateMs`, engine/README.md,
// "Stage estimates"), held at 92 % once it overruns. Ended steps, an ended
// flow and a step that waits on someone carry none.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { stepProgress, STEP_HOLD_PCT } = require("../dist/flow.cjs");
const { StepBar } = require("../dist/timeline.cjs");
const { estimateSource, progressOf } = require("../dist/issues.cjs");

const MIN = 60000;
const t0 = Date.parse("2026-10-05T11:00:00Z");
const at = (m) => new Date(t0 + m * MIN).toISOString();
const ended = { process: "chore", activity: "start-chore-filed", name: "chore filed", startedAt: at(0), endedAt: at(0) };
const coding = { process: "chore", activity: "call-do-work", name: "do the work\n(smith run)", startedAt: at(0), endedAt: null,
  estimateMs: 30 * MIN, estimateBasis: "scope", estimateRuns: 7 };
const flowOf = (steps, more = {}) => ({ ended: false, steps, questions: [], variables: {}, ...more });
const render = (flow, now) => renderToStaticMarkup(React.createElement(StepBar, { flow, now }));
const bars = (html) => html.match(/role="progressbar"/g) || [];

test("the open step's fill is elapsed over its estimate", () => {
  const p = stepProgress(coding, flowOf([coding]), t0 + 10 * MIN);
  assert.equal(Math.round(p.pct), 33);
  assert.equal(p.overdue, false);
  assert.equal(p.title, "do the work (smith run) · 10m elapsed · est. 30m (from 7 similar runs)");
});

test("a step past its estimate holds at exactly 92 until it ends", () => {
  assert.equal(STEP_HOLD_PCT, 92);
  for (const m of [40, 90, 600]) {
    const p = stepProgress(coding, flowOf([coding]), t0 + m * MIN);
    assert.equal(p.pct, 92, `${m} minutes in`);
    assert.equal(p.overdue, true);
  }
  assert.equal(stepProgress({ ...coding, endedAt: at(40) }, flowOf([coding]), t0 + 40 * MIN), null, "ended: no bar");
});

test("the bar is the open step's, and it moves with the clock alone", () => {
  const flow = flowOf([ended, coding]);
  const html = render(flow, t0 + 10 * MIN);
  assert.equal(bars(html).length, 1);
  assert.match(html, /role="progressbar"[^>]*aria-valuenow="33"/);
  assert.match(html, /title="do the work \(smith run\) · 10m elapsed · est. 30m \(from 7 similar runs\)"/);
  assert.match(render(flow, t0 + 20 * MIN), /aria-valuenow="67"/, "same data, a later clock");
  assert.match(render(flow, t0 + 45 * MIN), /class="isProgress isProgressStep hold"[^>]*aria-valuenow="92"/);
});

test("no bar for an ended flow, a waiting step, a step with no start or no estimate", () => {
  const now = t0 + 10 * MIN;
  assert.equal(bars(render(flowOf([ended, coding], { ended: true }), now)).length, 0, "ended flow");
  const waiting = { process: "chore", activity: "user-task-answer-question", name: "answer the smith's question",
    startedAt: at(0), endedAt: null, estimateMs: 30 * MIN, estimateBasis: "default", estimateRuns: 0 };
  assert.equal(bars(render(flowOf([ended, waiting]), now)).length, 0, "user task");
  assert.equal(stepProgress({ ...waiting, activity: "event-merge-conflict" }, flowOf([]), now), null, "event");
  assert.equal(bars(render(flowOf([ended, { ...coding, startedAt: null }]), now)).length, 0, "no startedAt");
  assert.equal(bars(render(flowOf([ended, { ...coding, estimateMs: null }]), now)).length, 0, "no estimate");
  assert.equal(stepProgress(ended, flowOf([ended]), now), null, "ended step");
});

test("the tooltip names each estimate source", () => {
  assert.equal(estimateSource("scope", 7), "from 7 similar runs");
  assert.equal(estimateSource("tier", 12), "from 12 runs");
  assert.equal(estimateSource("role", 1), "from 1 run");
  assert.equal(estimateSource("default", 0), "role default");
  assert.equal(estimateSource("prior-round", 0), "this ticket's first round");
  assert.equal(estimateSource(undefined, undefined), null);
  const p = stepProgress({ ...coding, estimateBasis: "default", estimateRuns: 0 }, flowOf([coding]), t0 + 12 * MIN);
  assert.equal(p.title, "do the work (smith run) · 12m elapsed · est. 30m (role default)");
});

test("the board's bar tooltip notes its estimate's source when the engine sends one", () => {
  const issue = { kind: "chore", labels: ["chore"], children: [], pendingQuestion: null,
    flow: { flow: "chore", outcome: "started", ended: false, stage: "call-do-work", incident: null,
      stageStartedAt: at(0), stageEstimateMs: 30 * MIN, stageRound: 1, stageEstimateBasis: "tier", stageEstimateRuns: 4 } };
  assert.match(progressOf(issue, t0 + 10 * MIN).title, /est\. 30m \(from 4 runs\)$/);
  const { stageEstimateBasis, stageEstimateRuns, ...older } = issue.flow;
  assert.match(progressOf({ ...issue, flow: older }, t0 + 10 * MIN).title, /est\. 30m$/, "an older engine: no note");
});
