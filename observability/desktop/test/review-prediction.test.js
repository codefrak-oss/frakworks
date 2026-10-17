// The review tile's prediction bar (#1746): `PredictionBar` draws Jev's
// -100..100 call, and `predictionChain` asks again only once the previous
// answer is in, stopping when the review no longer runs or on an error.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { PredictionBar, predictionLabel, predictionChain, StepCard, runningReview, cardPrediction, stepKey } = require("../dist/timeline.cjs");

const render = (C, props) => renderToStaticMarkup(React.createElement(C, props));
const settle = () => new Promise((r) => setImmediate(r));

test("the bar fills right for +72 approve, left for -35 deny, and is empty for null", () => {
  const plus = render(PredictionBar, { prediction: { value: 72, verdict: "approve" } });
  assert.match(plus, /tlPredict approve/);
  assert.match(plus, /left:50%;width:36%/);
  assert.match(plus, />\+72 approve</);

  const minus = render(PredictionBar, { prediction: { value: -35, verdict: "deny" } });
  assert.match(minus, /tlPredict deny/);
  assert.match(minus, /right:50%;width:17.5%/);
  assert.match(minus, />-35 deny</);

  for (const prediction of [null, { value: null, verdict: null }]) {
    const empty = render(PredictionBar, { prediction });
    assert.doesNotMatch(empty, /tlPredictFill/);
    assert.match(empty, />no prediction yet</);
  }
  assert.equal(predictionLabel({ value: 0 }), "0 approve");
});

test("only a review card given a prediction draws the bar", () => {
  const review = { role: "correctness", round: 1, running: true, model: "m", effort: "e" };
  assert.match(render(StepCard, { step: review, prediction: null }), /no prediction yet/);
  assert.doesNotMatch(render(StepCard, { step: review }), /tlPredict/);
  assert.doesNotMatch(render(StepCard, { step: { ...review, role: "coding" }, prediction: { value: 50 } }), /tlPredict/);
  assert.equal(runningReview({ steps: [{ role: "coding" }, review] }), review);
  assert.equal(runningReview({ steps: [{ role: "coding", running: true }] }), null);
});

test("a review card loses its bar once the step stops running", () => {
  const running = { role: "correctness", round: 1, running: true, model: "m", effort: "e", startedAt: "t" };
  const review = runningReview({ steps: [running] });
  const predicted = { key: stepKey(running), prediction: { value: 72, verdict: "approve" } };
  assert.match(render(StepCard, { step: running, prediction: cardPrediction(running, review, predicted) }), />\+72 approve</);
  const finished = { ...running, running: false, endedAt: "u" };
  const after = runningReview({ steps: [finished] });
  const p = cardPrediction(finished, after, predicted);
  assert.equal(p, undefined);
  assert.doesNotMatch(render(StepCard, { step: finished, prediction: p }), /tlPredict|no prediction yet/);
  const old = { ...finished, round: 0 };
  assert.equal(cardPrediction(old, review, predicted), undefined);
  assert.equal(cardPrediction(running, review, { key: null, prediction: null }), null);
});

test("the chain issues the next call only after the previous resolves, and stops when the step stops running", async () => {
  const pending = [];
  let running = true;
  const heard = [];
  let ended = "not yet";
  predictionChain({
    call: () => new Promise((resolve) => pending.push(resolve)),
    isRunning: () => running,
    onPrediction: (p) => heard.push(p.value),
    onEnd: (why) => { ended = why; },
  });
  await settle();
  assert.equal(pending.length, 1, "one call in flight");
  await settle();
  assert.equal(pending.length, 1, "no second call before the first answers");
  pending[0]({ prediction: { value: 10 } });
  await settle();
  assert.equal(pending.length, 2);
  assert.deepEqual(heard, [10]);
  running = false;
  pending[1]({ prediction: { value: -20 } });
  await settle();
  assert.equal(pending.length, 2, "no call once the review stopped");
  assert.deepEqual(heard, [10, -20]);
  assert.equal(ended, null);
});

test("an error answer ends the chain, and stop() drops a late answer", async () => {
  const pending = [];
  let ended = "not yet";
  predictionChain({ call: () => new Promise((r) => pending.push(r)), isRunning: () => true, onPrediction: () => {}, onEnd: (w) => { ended = w; } });
  await settle();
  pending[0]({ error: "engine answered 409" });
  await settle();
  assert.equal(pending.length, 1);
  assert.equal(ended, "engine answered 409");

  const later = [];
  const heard = [];
  const stop = predictionChain({ call: () => new Promise((r) => later.push(r)), isRunning: () => true, onPrediction: (p) => heard.push(p) });
  await settle();
  stop();
  later[0]({ prediction: { value: 5 } });
  await settle();
  assert.equal(later.length, 1);
  assert.deepEqual(heard, []);
});
