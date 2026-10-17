// ssh-verdicts.test.js — the SSH verdicts page (#2439): its row mapping, the
// human decision's word, and the table drawn from an engine answer.
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { verdictCells, decisionText, SshVerdictsView } = require("../dist/ssh-verdicts.cjs");

const ROW = {
  id: "c1", called_at: "2026-10-11T09:30:12Z", repo: "example-org/example-repo", host: "nas", command: "systemctl restart nginx",
  verdict: "escalate", confidence: 0.912, cited_passage: "Restarts.", reasoning: "Verdict escalate at confidence 0.912",
  decision: { task_id: "t1", status: "completed", outcome: "approved", decided_at: "2026-10-11T09:40:00Z" },
};

test("a row maps every field, the decision as its outcome", () => {
  assert.deepEqual(verdictCells(ROW), {
    id: "c1", when: "2026-10-11 09:30 UTC", host: "nas", command: "systemctl restart nginx", verdict: "escalate",
    confidence: "0.91", passage: "Restarts.", reasoning: "Verdict escalate at confidence 0.912", decision: "approved",
  });
});

test("a call with no answer and no task shows dashes and no decision", () => {
  const c = verdictCells({ id: "c2", called_at: "2026-10-11T09:00:00Z", verdict: "no-answer", decision: null });
  assert.equal(c.verdict, "no answer");
  assert.equal(c.passage, "–");
  assert.equal(c.decision, "–");
  assert.equal(decisionText({ status: "working" }), "pending");
  assert.equal(decisionText(null), null);
});

test("the view draws the rows in the engine's order, newest first", () => {
  const older = { ...ROW, id: "c0", called_at: "2026-10-10T08:00:00Z", command: "ls /etc", verdict: "allow", decision: null };
  const html = renderToStaticMarkup(React.createElement(SshVerdictsView, { result: { verdicts: [ROW, older] } }));
  assert.ok(html.indexOf("systemctl restart nginx") < html.indexOf("ls /etc"));
  assert.match(html, /<h1>SSH verdicts<\/h1>/);
  assert.match(html, />approved</);
  const none = renderToStaticMarkup(React.createElement(SshVerdictsView, { result: { verdicts: [] } }));
  assert.match(none, /no call yet/);
});
