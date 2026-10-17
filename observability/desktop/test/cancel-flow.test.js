// The issue detail's Cancel (src/renderer/cancel-flow.jsx): when it shows,
// that nothing is sent before the confirm or with a blank reason, that the
// reason is what is sent, and that a refusal is said with the reason kept.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { cancelTarget, cancelStep, submitCancel, CancelFlowView, IDLE } = require("../dist/cancel-flow.cjs");

const running = { id: "0a1b2c3d-1", processDefinitionKey: "chore", state: "running", ended: false };
const render = (props) => renderToStaticMarkup(React.createElement(CancelFlowView, props));
const recorder = (answer) => {
  const calls = [];
  const fn = async (id, reason) => { calls.push([id, reason]); return answer; };
  return { calls, fn };
};

test("it shows for a root instance running or on an incident, and not for ended ones or none", () => {
  assert.equal(cancelTarget([]), null);
  assert.equal(cancelTarget(null), null);
  assert.equal(cancelTarget([{ id: "e", state: "ended", ended: true }]), null);
  assert.equal(cancelTarget([{ id: "e", state: "ended", ended: true }, running]).id, running.id);
  assert.equal(cancelTarget([{ id: "i", state: "incident", ended: false }]).id, "i");
  assert.equal(render({ target: null, state: IDLE }), "");
  assert.match(render({ target: running, state: IDLE }), /<button[^>]*>Cancel…<\/button>/);
});

test("Cancel… only opens the confirm; nothing is sent before it", async () => {
  const { calls, fn } = recorder({ ok: true });
  assert.equal((await submitCancel(IDLE, running.id, fn)).state.phase, "idle");
  const confirm = cancelStep(IDLE, { type: "open" });
  assert.equal(confirm.phase, "confirm");
  assert.equal(calls.length, 0);
  const html = render({ target: running, state: confirm });
  assert.match(html, /<textarea[^>]*placeholder="Why \(required/);
  assert.match(html, />Cancel this flow<\/button>/);
  assert.match(html, />Keep it<\/button>/);
  assert.match(html, /Stops its Smith, closes the ticket as not planned and any open PR unmerged\. The branch and its commits stay/);
  assert.match(html, /<button class="cfButton cfDanger" disabled=""/, "no reason yet");
  assert.equal(cancelStep(confirm, { type: "keep" }).phase, "idle");
});

test("a blank reason blocks the send", async () => {
  const { calls, fn } = recorder({ ok: true });
  const blank = cancelStep(cancelStep(IDLE, { type: "open" }), { type: "reason", reason: "   " });
  const { state } = await submitCancel(blank, running.id, fn);
  assert.equal(state.phase, "confirm");
  assert.match(state.error, /say why/);
  assert.equal(calls.length, 0);
});

test("the reason typed is what is sent, and a cancel that went through closes the control", async () => {
  const { calls, fn } = recorder({ ok: true, status: 200, failedWrites: [] });
  const typed = cancelStep(cancelStep(IDLE, { type: "open" }), { type: "reason", reason: "  wrong approach  " });
  const { state, result } = await submitCancel(typed, running.id, fn);
  assert.deepEqual(calls, [[running.id, "wrong approach"]]);
  assert.equal(result.ok, true);
  assert.deepEqual(state, IDLE);
});

test("a refusal is said, and the confirm stays with its reason", async () => {
  const { fn } = recorder({ error: "engine answered 403 for DELETE /intake/x: someone may not cancel a chore on o/r" });
  const typed = cancelStep(cancelStep(IDLE, { type: "open" }), { type: "reason", reason: "not needed" });
  const { state } = await submitCancel(typed, running.id, fn);
  assert.equal(state.phase, "confirm");
  assert.equal(state.reason, "not needed");
  assert.match(render({ target: running, state }), /<div class="err">engine answered 403[^<]*may not cancel a chore/);
  const thrown = await submitCancel(typed, running.id, async () => { throw new Error("gone"); });
  assert.equal(thrown.state.error, "IPC failed: gone");
  assert.equal(thrown.state.reason, "not needed");
});

test("the detail puts Cancel in its header, next to the GitHub link", () => {
  const src = fs.readFileSync(path.join(__dirname, "../src/renderer/detail.jsx"), "utf8");
  const num = src.indexOf("#{number}"), gh = src.indexOf('className="ghLink"'),
        meta = src.indexOf('className="meta"'), cancel = src.indexOf("<CancelFlow issueUrl={url}");
  // GitHub sits right after the ticket number, still inside the h1; Cancel
  // sits in the meta span that follows.
  assert.ok(num > 0 && gh > num && gh < meta && meta < cancel);
});
