// The judge-escalation teach buttons (#2436): only a card whose request carries
// `context.data.judge` draws them, in the one task view (the urgent popup, HITLP Requests and a ticket's detail); with
// no first-mate token they are disabled with the tooltip; a click decides the
// task, then teaches; and engine-data's teachJudge sends the first-mate token.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const J = require("../src/judge-teach");
const { HitlpTask } = require("../dist/hitlp-task.cjs");
const { teachJudge } = require("../src/engine-data");

const judge = { callId: "j-7", verdict: "escalate", confidence: 0.5, rubricPath: "ops/rubric/ssh.md", repo: "example-org/example-repo" };
const row = (data) => ({ kind: "approve", priority: "urgent", actionId: "t1", action: "ssh.exec", payload: { command: "uptime" },
  context: data === undefined ? { summary: "s" } : { summary: "s", data } });
const escape = (s) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/>/g, "&gt;");

test("judgeOf finds context.data.judge on an approve, and nothing else", () => {
  assert.deepEqual(J.judgeOf(row({ judge })), judge);
  assert.equal(J.judgeOf(row()), null);
  assert.equal(J.judgeOf(row({ other: 1 })), null);
  assert.equal(J.judgeOf({ ...row({ judge }), kind: "ask" }), null);
});

test("judgeOf reads the recommendation's by.judge first (HITLP 0.2, #2487), else context.data.judge", () => {
  const other = { ...judge, rubricPath: "ops/rubric/other.md" };
  const rec = (by) => ({ verdict: "deny", confidence: 0.6, by });
  assert.deepEqual(J.judgeOf({ ...row({ judge: other }), recommendation: rec({ type: "model", judge }) }), judge);
  assert.deepEqual(J.judgeOf({ ...row(), asserted: { recommendation: rec({ judge }) } }), judge);
  assert.deepEqual(J.judgeOf({ ...row({ judge: other }), recommendation: rec({ type: "model" }) }), other, "no judge in by: context.data.judge");
  assert.equal(J.judgeOf({ ...row(), kind: "ask", recommendation: rec({ judge }) }), null);
});

test("only a judge escalation's card draws the teach buttons, in the one task view (popup, HITLP Requests, a ticket's detail)", () => {
  for (const C of [HitlpTask]) {
    const plain = renderToStaticMarkup(React.createElement(C, { row: row(), now: 0, teachReady: true }));
    assert.ok(!plain.includes("and teach"), C.name);
    const html = renderToStaticMarkup(React.createElement(C, { row: row({ judge }), now: 0, teachReady: true }));
    assert.ok(html.includes("Allow and teach") && html.includes("Deny and teach"), C.name);
    assert.ok(html.includes("Approve"), C.name);
    assert.ok(html.includes(`teaches ${judge.rubricPath} in ${judge.repo}`), C.name);
    assert.ok(!plain.includes("data-teach-target"), C.name);
  }
});

test("without a first-mate token the teach buttons are disabled with the tooltip; approve and reject are not", () => {
  const html = renderToStaticMarkup(React.createElement(HitlpTask, { row: row({ judge }), now: 0, teachReady: false }));
  const teach = html.match(/<button[^>]*data-teach="allow"[^>]*>/)[0];
  assert.ok(/disabled=""/.test(teach));
  assert.ok(teach.includes(escape(J.NO_TOKEN_TIP)));
  const approve = html.match(/<button[^>]*data-outcome="approved"[^>]*>/)[0];
  assert.ok(!/disabled=""/.test(approve));
});

test("a teach click decides the task first, then teaches with the note", async () => {
  const calls = [];
  const decide = async (...a) => { calls.push(["decide", ...a]); return { ok: true }; };
  const teach = async (...a) => { calls.push(["teach", ...a]); return { ok: true, line: "- line" }; };
  const r = await J.teachClick(row({ judge }), J.TEACH_BUTTONS[1], " why ", decide, teach);
  assert.deepEqual(calls, [["decide", "t1", "rejected", null, "why"], ["teach", "t1", "deny", "why"]]);
  assert.deepEqual(r, { ok: true, line: "- line" });
  const refused = await J.teachClick(row({ judge }), J.TEACH_BUTTONS[0], "", async () => ({ error: "409" }), teach);
  assert.deepEqual(refused, { error: "409" });
});

test("teachJudge posts to /judge/teach with the first-mate token, and refuses without one", async () => {
  assert.match((await teachJudge("t1", "allow", null, null)).error, /first-mate token/);
  let seen;
  const fetch = async (url, init) => { seen = { url, init }; return new Response(JSON.stringify({ line: "- l", commit: "c" }), { status: 200 }); };
  const r = await teachJudge("t1", "allow", "ok", "Bearer frk_fm", { fetch, env: { engineUrl: "http://e" } });
  assert.deepEqual(r, { ok: true, line: "- l", commit: "c" });
  assert.equal(seen.url, "http://e/judge/teach");
  assert.equal(seen.init.headers.authorization, "Bearer frk_fm");
  assert.deepEqual(JSON.parse(seen.init.body), { taskId: "t1", decision: "allow", note: "ok" });
});
