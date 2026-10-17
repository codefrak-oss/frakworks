// timeline.jsx — the ticket detail's Timeline widget (#101), built ahead of
// its engine endpoint (#91) against fixture-ticket-timeline.json. Pure parts
// (TimelineHeader, StepCard, WaitLine, cardsOf, trackItems, toggleWait) are
// exercised directly, the same way process.test.js exercises process.jsx's
// pure parts; `Timeline` itself is exercised through a stub `window.fleet`,
// since it is the one thing here that does IPC.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const {
  Timeline, TimelineHeader, StepCard, WaitLine, cardsOf, trackItems, toggleWait,
  waitLabel, stateChip, ciRunning, liveDurationMs, BlockedBanner, fmtTokens, tokensLine,
} = require("../dist/timeline.cjs");
const { fmtDur } = require("../dist/timeline.cjs");

const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-ticket-timeline.json"), "utf8"));
const render = (C, props) => renderToStaticMarkup(React.createElement(C, props));

test("cardsOf gives every step a card, whatever its role, and a higher round for the same role is an extra rework card", () => {
  const steps = [
    { role: "coding", round: 1 },
    { role: "refine", round: 1 },
    { role: "security", round: 1 },
    { role: "correctness", round: 1 },
    { role: "coding", round: 2 },
  ];
  const cards = cardsOf(steps);
  assert.equal(cards.length, 5, "every role gets a card, including one the engine has no special label for");
  assert.deepEqual(cards.map((c) => c.rework), [false, false, false, false, true]);
  assert.equal(cards[4].step.role, "coding");
  assert.equal(cards[0].rework, false, "round 1 is never itself a rework card");
});

test("a catch-up step (a round opened by merging main) is labelled 'merged main', never rework; a later work round still is rework", () => {
  const steps = [
    { role: "coding", round: 1 },
    { role: "security", round: 1 },
    { role: "correctness", round: 1 },
    { role: "security", round: 2, catchUp: true },
    { role: "correctness", round: 2, catchUp: true },
    { role: "coding", round: 3 },
    { role: "security", round: 3, catchUp: false },
  ];
  const cards = cardsOf(steps);
  assert.deepEqual(cards.map((c) => c.rework), [false, false, false, false, false, true, true]);
  const catchUp = render(StepCard, { step: cards[3].step, rework: cards[3].rework });
  assert.match(catchUp, /round 2 · merged main/);
  assert.doesNotMatch(catchUp, /rework/);
  // even if a caller passes rework, a catch-up step never says it
  assert.doesNotMatch(render(StepCard, { step: cards[4].step, rework: true }), /rework/);
  const work = render(StepCard, { step: cards[5].step, rework: cards[5].rework });
  assert.match(work, /tlRework">round 3 · rework/);
  assert.doesNotMatch(work, /merged main/);
  assert.match(render(StepCard, { step: cards[6].step, rework: cards[6].rework }), /round 3 · rework/);
});

test("a step whose role is not coding/security/correctness still gets a card showing its duration", () => {
  const steps = [
    { role: "refine", round: 1, durationMs: 70000 },
    { role: "reproduce", round: 1, durationMs: 5000 },
    { role: "verify", round: 1, durationMs: 3000 },
  ];
  const cards = cardsOf(steps);
  assert.equal(cards.length, 3, "none of these roles is dropped");
  assert.deepEqual(cards.map((c) => c.step.role), ["refine", "reproduce", "verify"]);
  for (const c of cards) {
    const html = render(StepCard, { step: c.step, rework: c.rework });
    assert.ok(html.includes(fmtDur(c.step.durationMs / 1000)), `${c.step.role} card shows its duration`);
  }
});

test("the fixture's four steps and five waits merge into track order by start time", () => {
  const items = trackItems(fixture.steps, fixture.waits);
  assert.equal(items.length, fixture.steps.length + fixture.waits.length);
  for (let i = 1; i < items.length; i++) assert.ok(items[i].start >= items[i - 1].start, "in start order");
  // the CI wait is its own line, right after the coding card it followed and before the security card
  const kinds = items.slice(0, 4).map((it) => (it.kind === "card" ? it.card.step.role : it.wait.cause));
  assert.deepEqual(kinds, ["coding", "ci", "person-authorization", "security"]);
});

test("waitLabel is `<duration> · <cause text> · <who acts>`, and stateChip is `<state> · round <n>`", () => {
  assert.match(waitLabel({ durationMs: 600000, cause: "person-authorization" }), /^10m · waiting on your authorization · you$/);
  assert.match(waitLabel({ durationMs: 1100000, cause: "stuck", payload: { retrier: "example-smith" } }),
    /stuck · example-smith$/);
  assert.match(waitLabel({ durationMs: 300000, cause: "stuck" }), /stuck · you$/, "no retrier falls back to you");
  assert.match(waitLabel({ durationMs: 300000, cause: "engine" }), /engine working · engine$/);
  assert.equal(waitLabel({ durationMs: 400000, cause: "ci" }), `${fmtDur(400)} · CI check · github`);
  assert.equal(stateChip({ state: "OPEN", round: 2 }), "open · round 2");
});

test("toggleWait flips membership without mutating the set it was given", () => {
  const open = new Set([1]);
  const opened = toggleWait(open, 2);
  assert.deepEqual([...opened].sort(), [1, 2]);
  assert.deepEqual([...open], [1], "the input set is untouched");
  const closed = toggleWait(opened, 1);
  assert.deepEqual([...closed].sort(), [2]);
});

test("TimelineHeader shows the ticket, the state chip, the totals (CI after Waiting) and a five-segment split bar", () => {
  const html = render(TimelineHeader, { ticket: fixture.ticket, totals: fixture.totals });
  assert.ok(html.includes(`#${fixture.ticket.number}`));
  assert.ok(html.includes(fixture.ticket.title));
  assert.match(html, /class="chip tdState">open · round 2</);
  assert.match(html, /class="tlSplitBar"/);
  const t = fixture.totals;
  assert.ok(html.includes(`Elapsed <b>${fmtDur(t.elapsedMs / 1000)}`));
  assert.ok(html.includes(`Working <b>${fmtDur(t.workingMs / 1000)}`));
  assert.ok(html.includes(`Waiting <b>${fmtDur(t.waitingMs / 1000)}`));
  assert.ok(html.includes(`CI <b>${fmtDur(t.ciMs / 1000)}`));
  assert.ok(html.indexOf("Waiting <b>") < html.indexOf("CI <b>"), "CI comes after Waiting");
  assert.ok(html.includes(`Cost <b>$${t.costUsd.toFixed(2)}`));
  // the split bar's five segments, one per totals field it slices elapsed into
  for (const cls of ["tl-working", "tl-waiting-person", "tl-stuck", "tl-ci", "tl-engine"]) {
    assert.match(html, new RegExp(`class="${cls}"`), `${cls} segment drawn`);
  }
});

test("a done card is fixed-size regardless of duration and shows its role, model, cost and outcome", () => {
  const step = fixture.steps[0];
  const html = render(StepCard, { step, rework: false });
  assert.match(html, /class="tlCard coding"/);
  assert.doesNotMatch(html, /tlPulse/, "no pulse on a finished step");
  assert.doesNotMatch(html, /tlActive|tlSweep/, "no animation on a finished step");
  assert.doesNotMatch(html, /tlRework/, "round 1 is not a rework card");
  assert.ok(html.includes(step.model));
  assert.ok(html.includes(step.outcome));
  assert.ok(html.includes(`$${step.cost.toFixed(2)}`));
});

test("a running card is animated across the whole card, pulses and labels its numbers 'so far'", () => {
  const step = fixture.steps[3];
  assert.equal(step.running, true);
  const html = render(StepCard, { step, rework: true });
  assert.match(html, /class="tlCard coding running tlActive"/, "the whole card is marked animated");
  assert.match(html, /<span class="tlSweep" aria-hidden="true"><\/span>/, "the sweeping highlight across the card");
  assert.match(html, /tlPulse/);
  assert.match(html, /tlRework">round 2 · rework/);
  assert.match(html, /so far/);
});

test("a running step with no cost or turns yet (collect result hasn't run) hides the cost line and says turns 'not yet known', never $0.00 or a blank count", () => {
  // Before the live tally's first assistant event the endpoint has null turns and tokens too.
  const step = { ...fixture.steps[3], turns: null, tokens: null };
  assert.equal(step.cost, null);
  const html = render(StepCard, { step, rework: true });
  assert.doesNotMatch(html, /tlCardCost/, "no cost line until the cost is known");
  assert.doesNotMatch(html, /cost not yet known/);
  assert.match(html, /tlCardTurns">turns not yet known</);
  assert.doesNotMatch(html, /tlCardTokens/, "no token line without tokens");
  assert.doesNotMatch(html, /\$0\.00/);
  assert.doesNotMatch(html, />\s+turns/);
});

test("a running step with live figures shows its turns and tokens 'so far', and hides the cost line until collect result", () => {
  const step = fixture.steps[3];
  assert.equal(step.running, true);
  assert.equal(step.cost, null, "no live cost: the user chose cost at the end");
  assert.equal(step.turns, 12);
  const html = render(StepCard, { step, rework: true });
  assert.doesNotMatch(html, /tlCardCost/, "no cost line until the cost is known");
  assert.doesNotMatch(html, /cost not yet known/);
  assert.doesNotMatch(html, /\$0\.00/);
  assert.match(html, /tlCardTurns">12 turns so far</);
  assert.match(html, /tlCardTokens"[^>]*>in 41k · out 9.3k · cache read 1.2M · cache write 88k so far</);
  assert.match(html, /title="input 41200 · output 9300 · cache read 1200000 · cache write 88000 tokens so far"/, "exact counts on hover");
});

test("a finished step shows its final cost and turns, never a token line", () => {
  const step = { ...fixture.steps[0], tokens: { input: 1, output: 2, cacheRead: 3, cacheWrite: 4 } };
  const html = render(StepCard, { step, rework: false });
  assert.match(html, /tlCardTurns">42 turns</);
  assert.doesNotMatch(html, /so far/);
  assert.doesNotMatch(html, /tlCardTokens/);
});

test("fmtTokens is compact and tokensLine is null without tokens", () => {
  assert.deepEqual([0, 950, 1000, 9300, 41200, 999_499, 1_200_000, 25_000_000].map(fmtTokens),
    ["0", "950", "1k", "9.3k", "41k", "999k", "1.2M", "25M"]);
  assert.equal(tokensLine(null), null);
});

test("a wait line is one fixed element labelled with cause and actor, and toggling shows its minor detail", () => {
  const w = fixture.waits[3]; // person-question, with a question/answer payload
  const collapsed = render(WaitLine, { wait: w, expanded: false, onToggle: () => {} });
  assert.match(collapsed, /class="tlWait amber"/);
  assert.doesNotMatch(collapsed, /tlMinor/);
  const expanded = render(WaitLine, { wait: w, expanded: true, onToggle: () => {} });
  assert.match(expanded, /class="tlWait amber expanded"/);
  assert.match(expanded, /tlMinor/);
  assert.ok(expanded.includes(w.payload.question));
  assert.ok(expanded.includes(w.payload.answer));
});

test("the stuck wait is red and names its retrier; the engine wait is grey and says engine", () => {
  const stuck = render(WaitLine, { wait: fixture.waits[4], expanded: false, onToggle: () => {} });
  assert.match(stuck, /class="tlWait red"/);
  assert.ok(stuck.includes(fixture.waits[4].payload.retrier));
  const engine = render(WaitLine, { wait: fixture.waits[2], expanded: false, onToggle: () => {} });
  assert.match(engine, /class="tlWait grey"/);
  assert.ok(engine.includes("engine"));
});

test("the CI wait is its own ci-coloured line, labelled CI check · github, and expanding it shows the check, conclusion and steps", () => {
  const w = fixture.waits[0];
  assert.equal(w.cause, "ci");
  const collapsed = render(WaitLine, { wait: w, expanded: false, onToggle: () => {} });
  assert.match(collapsed, /class="tlWait ci"/);
  assert.ok(collapsed.includes(`${fmtDur(w.durationMs / 1000)} · CI check · github`));
  assert.doesNotMatch(collapsed, /tlMinor/);
  const expanded = render(WaitLine, { wait: w, expanded: true, onToggle: () => {} });
  assert.ok(expanded.includes(`<b>Check:</b> ${w.payload.check}`));
  assert.ok(expanded.includes(`<b>Conclusion:</b> ${w.payload.conclusion}`));
  assert.ok(expanded.includes("receive-ci-check"), "the steps, as any wait shows them");
  // a wait still running, or one whose flow recorded neither, shows no check or conclusion line
  const bare = render(WaitLine, { wait: { ...w, payload: { steps: [], check: null, conclusion: null } }, expanded: true, onToggle: () => {} });
  assert.doesNotMatch(bare, /Check:|Conclusion:/);
});

// ---- Timeline (the IPC-backed widget) --------------------------------------

// renderToStaticMarkup never runs effects, so Timeline's IPC call is never
// made here — its initial state is what a test can assert without a stub,
// the same reasoning process.test.js uses for ProcessSection's first paint.
test("Timeline's initial render is the loading state, before any IPC call is made", () => {
  const html = render(Timeline, { owner: "codefrak-oss", repo: "frakworks", ticket: 101 });
  assert.match(html, /<h2>Timeline<\/h2>/);
  assert.match(html, /asking the engine for the timeline/);
});

// ---- BlockedBanner (#443) ----------------------------------------------------

test("an intake's `blocked` renders the can't-do-with-current-permissions banner: heading, summary, and the full reason behind a disclosure", () => {
  const blocked = fixture._blockedIntake.ticket.blocked;
  assert.equal(blocked.kind, "insufficient-permissions");
  assert.ok(blocked.summary.split(/\s+/).length <= 20, "the contract's summary is at most 20 words");
  const html = render(BlockedBanner, { blocked });
  assert.match(html, /class="tdBlocked"/);
  assert.match(html, /<h3 class="tdBlockedHead">Blocked: can&#x27;t do with current permissions<\/h3>/);
  assert.match(html, /class="tdBlockedSummary">/);
  assert.ok(html.includes(render(() => blocked.summary)), "the summary under the heading");
  // The full reason is inside a closed <details>: shown only when clicked open.
  assert.match(html, /<details class="tdBlockedReason"><summary>Full reason<\/summary>/);
  assert.doesNotMatch(html, /<details[^>]* open/, "collapsed until clicked");
  assert.ok(html.includes(render(() => blocked.reason)), "the full reason on expand");
});

test("no `blocked` (every ticket but an insufficient-permissions intake) renders nothing", () => {
  assert.equal(render(BlockedBanner, { blocked: undefined }), "");
  assert.equal(render(BlockedBanner, { blocked: fixture.ticket.blocked }), "", "the fixture's main ticket has no blocked key");
  assert.equal(fixture.ticket.blocked, undefined);
});

// #1014: the open step's bar (flow.jsx, stepProgress) is on the running card.
test("a running card draws the open step's progress bar; with no estimate, or ended, none", () => {
  const MIN = 60000;
  const t0 = Date.parse("2026-10-05T11:00:00Z");
  const open = { process: "chore", activity: "call-do-work", name: "do the work\n(smith run)",
    startedAt: new Date(t0).toISOString(), endedAt: null, estimateMs: 30 * MIN, estimateBasis: "scope", estimateRuns: 7 };
  const flow = { ended: false, steps: [open], questions: [], variables: {} };
  const step = { role: "coding", round: 1, running: true, durationMs: 600000, model: "opus", effort: "low" };
  const html = render(StepCard, { step, flow, now: t0 + 10 * MIN });
  assert.match(html, /^<div class="tlCard coding running tlActive">.*class="isProgress isProgressStep"[^>]*role="progressbar"[^>]*aria-valuenow="33"/);
  assert.match(html, /title="do the work \(smith run\) · 10m elapsed · est. 30m \(from 7 similar runs\)"/);
  assert.match(render(StepCard, { step, flow, now: t0 + 45 * MIN }), /isProgressStep hold"[^>]*aria-valuenow="92"/);
  const none = { ...flow, steps: [{ ...open, estimateMs: null }] };
  assert.doesNotMatch(render(StepCard, { step, flow: none, now: t0 + 10 * MIN }), /progressbar/, "no estimate");
  assert.doesNotMatch(render(StepCard, { step: { ...step, running: false }, flow, now: t0 + 10 * MIN }), /progressbar/, "ended card");
});

test("a CI wait animates only while its check runs: unconcluded and last on the track (#1678)", () => {
  const ci = { start: 10, end: 20, durationMs: 10, cause: "ci", payload: { check: "engine" } };
  const items = trackItems([{ role: "work", round: 1, start: 0 }], [ci]);
  assert.equal(ciRunning(items, items[1]), true);
  const done = trackItems([], [{ ...ci, payload: { conclusion: "success" } }]);
  assert.equal(ciRunning(done, done[0]), false);
  const later = trackItems([{ role: "work", round: 2, start: 30 }], [ci]);
  assert.equal(ciRunning(later, later[0]), false);
  const eng = trackItems([], [{ ...ci, cause: "engine" }]);
  assert.equal(ciRunning(eng, eng[0]), false);
  const on = render(WaitLine, { wait: ci, expanded: false, onToggle: () => {}, running: true });
  assert.match(on, /class="tlWait ci running"/);
  assert.match(on, /tlWaitDot/);
  assert.doesNotMatch(render(WaitLine, { wait: ci, expanded: false, onToggle: () => {} }), /running|tlWaitDot/);
});

test("a running CI check's elapsed time counts up live from the loaded duration (#1690)", (t) => {
  t.mock.timers.enable({ apis: ["Date", "setInterval"], now: 1000000 });
  const since = Date.now();
  assert.equal(liveDurationMs(8000, since, Date.now()), 8000);
  t.mock.timers.tick(5000);
  assert.equal(liveDurationMs(8000, since, Date.now()), 13000);
  assert.equal(waitLabel({ cause: "ci", durationMs: liveDurationMs(8000, since, Date.now()) }), `${fmtDur(13)} · CI check · github`);
  assert.equal(liveDurationMs(8000, since, since - 500), 8000, "never below the engine's figure");
  // a finished check renders the engine's final duration as it is
  const done = render(WaitLine, { wait: { cause: "ci", durationMs: 42000, payload: { conclusion: "success" } }, expanded: false, onToggle: () => {} });
  assert.ok(done.includes(`${fmtDur(42)} · CI check`));
});

test("the running card names its run's AI account, and omits the label when unknown (#2600)", () => {
  const step = { role: "coding", round: 1, durationMs: 1000, model: "opus", effort: "high", turns: 2, outcome: "in progress", running: true };
  assert.match(render(StepCard, { step: { ...step, account: "acct-a" } }), /<div class="tlCardAccount">account: acct-a<\/div>/);
  for (const account of [undefined, null, ""]) assert.doesNotMatch(render(StepCard, { step: { ...step, account } }), /tlCardAccount|account:/);
  assert.doesNotMatch(render(StepCard, { step: { ...step, running: false, account: "acct-a" } }), /tlCardAccount/);
});

test("the post-deploy check on the track: a deploy line, and check runs read as their outcome", () => {
  const { StepCard, DeployLine, checkOutcome, deployLabel, deployItems } = require("../dist/timeline.cjs");
  const d = { repo: "o/r", sha: "0123456789abcdef", source: "argocd", at: "2026-10-13T10:00:00Z" };
  assert.equal(deployLabel(d), "deployed to production · 0123456 · argocd");
  assert.equal(deployLabel({ sha: "abcdef0123" }), "deployed to production · abcdef0");
  assert.deepEqual(deployItems([d]).map((i) => [i.kind, i.start]), [["deploy", Date.parse(d.at)]]);
  assert.deepEqual(deployItems(undefined), []);
  assert.match(renderToStaticMarkup(React.createElement(DeployLine, { deploy: d })), /tlDeploy[^>]*>deployed to production · 0123456/);
  assert.equal(checkOutcome({ role: "check", outcome: "passed" }), "passed");
  assert.equal(checkOutcome({ role: "check", outcome: "not_yet" }), "not yet: next check scheduled");
  assert.equal(checkOutcome({ role: "coding", outcome: "done" }), null);
  const html = renderToStaticMarkup(React.createElement(StepCard, { step: { role: "check", round: 1, outcome: "failed", durationMs: 1000 } }));
  assert.match(html, /tlCardOutcome">failed: bug filed</);
});
