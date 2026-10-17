// The issue detail view (flow.jsx, IssueDetail) shows the Issues rows' live
// progress bar, reused from issues.jsx, in its larger variant.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { TicketDetail } = require("../dist/index.cjs");

const MIN = 60000;
const t0 = Date.parse("2026-09-25T11:02:00Z");
const base = { number: 7, repo: "o/r", title: "t", author: "a", url: "https://github.com/o/r/issues/7",
  kind: "chore", labels: ["chore"], children: [], pendingQuestion: null };
const running = { ...base, flow: { flow: "chore", outcome: "started", ended: false, stage: "call-do-work", incident: null,
  stageStartedAt: new Date(t0).toISOString(), stageEstimateMs: 30 * MIN, stageRound: 1 } };
const render = (issue) => renderToStaticMarkup(React.createElement(TicketDetail,
  { repo: "o/r", number: 7, issue, flowResult: null, now: t0 + 10 * MIN }));

test("a running issue's detail view carries the large progress bar before the ticket read below it", () => {
  const html = render(running);
  assert.match(html, /class="isProgress isProgressLg"[^>]*role="progressbar"[^>]*aria-valuenow="\d+"/);
  assert.ok(html.indexOf("isProgressLg") < html.indexOf("asking the engine for the ticket"));
});

test("an issue with no running flow has no progress bar", () => {
  assert.doesNotMatch(render({ ...base, flow: null }), /progressbar|isProgress/);
});

test("a running intake refining and a running proposal drafting carry the large bar within their stage's segment", () => {
  const cases = [
    [{ kind: "intake", labels: ["intake"] }, { flow: "intake", outcome: null, instance: "i-1", stage: "call-refine-ask" }, [20, 90]],
    [{ kind: "proposal", labels: ["proposal"] }, { flow: "proposal", stage: "call-draft" }, [5, 50]],
  ];
  for (const [issue, flow, [start, end]] of cases) {
    const html = render({ ...running, ...issue, flow: { ...running.flow, ...flow } });
    const m = html.match(/class="isProgress isProgressLg"[^>]*role="progressbar"[^>]*aria-valuenow="(\d+)"/);
    assert.ok(m, flow.flow);
    assert.ok(+m[1] >= start && +m[1] <= end, `${flow.flow} ${m[1]}`);
  }
});

test("a split parent's large bar in the detail says its points, and is striped when a part is estimated", () => {
  const children = [{ state: "merged", points: 8 }, { state: "open", points: 5 }];
  const html = render({ ...running, kind: "feature", labels: ["feature"], children,
    flow: { ...running.flow, flow: "feature", stage: "event-child-closed" } });
  assert.match(html, /class="isProgress isProgressLg" title="[^"]*8 of 13 pts merged"/);
  const est = render({ ...running, kind: "feature", labels: ["feature"], children: [...children, { state: "pending" }],
    flow: { ...running.flow, flow: "feature", stage: "event-child-closed" } });
  assert.match(est, /class="isProgress isProgressLg estimated" title="[^"]*8 of 19.5 pts merged \(1 of 3 parts estimated\)"/);
});

test("the post-deploy check splits its segment: awaiting the deploy in the first half, checking in the second, and only the check's run moves", () => {
  const { progressOf } = require("../dist/issues.cjs");
  const at = (flow, step) => ({ ...running, kind: flow, flow: { ...running.flow, flow, stage: "call-post-deploy-check", step } });
  const now = t0 + 10 * MIN;
  for (const [flow, start, end] of [["chore", 96, 100], ["bug", 99, 100], ["feature", 98, 100]]) {
    const mid = (start + end) / 2;
    const awaiting = progressOf(at(flow, "wait for the deploy"), now);
    assert.deepEqual([awaiting.start, awaiting.end, awaiting.hold, awaiting.pct], [start, mid, true, start], flow);
    const checking = progressOf(at(flow, "check it\n(smith run)"), now);
    assert.deepEqual([checking.start, checking.end, checking.hold], [mid, end, false], flow);
    assert.ok(checking.pct > mid && checking.pct < end, flow);
    for (const step of ["wake at", "decide at the deadline"]) {
      const p = progressOf(at(flow, step), now);
      assert.deepEqual([p.start, p.hold, p.pct], [mid, true, mid], `${flow} ${step}`);
    }
  }
  assert.match(render(at("chore", "decide at the deadline")), /isProgress isProgressLg hold/);
});
