// The urgent HITLP popup (#2196): which Needs row pops (src/hitlp-popup.js),
// the snooze and silence bookkeeping on a fake storage and a fixed clock, the
// keys, and the task view (src/renderer/hitlp-task.jsx) the popup draws for an
// ask with options, an ask with a text schema and an approve.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { toRows, surfaceRows } = require("../src/needs-you");
const P = require("../src/hitlp-popup");
const { HitlpPopup } = require("../dist/hitlp-popup.cjs");
const { HitlpTask } = require("../dist/hitlp-task.cjs");

const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-needs-you.json"), "utf8"));
// The HITLP server's surface tasks (#2487), merged first as the Needs read does.
const tasks = JSON.parse(fs.readFileSync(path.join(__dirname, "fixture-hitlp-tasks.json"), "utf8"));
const NOW_S = Math.floor(Date.parse("2026-09-25T00:00:00Z") / 1000);
const NOW = NOW_S * 1000;
const rows = [...surfaceRows(tasks, NOW_S), ...toRows(fixture, NOW_S)];
const fakeStorage = () => { const m = new Map(); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), map: m }; };
const urgent = (id, since, extra = {}) => ({ kind: "ask", priority: "urgent", actionId: id, since, for: null, options: [{ value: "a", label: "A" }], question: "q", ...extra });
const POPUP = { onSnooze: () => {}, onSilence: () => {} };
const card = (row, props = {}) => renderToStaticMarkup(React.createElement(HitlpTask, { row, now: NOW_S, popup: POPUP, teachReady: false, ...props }));

test("surfaceRows carries an ask's or approve's priority (normal by default) and context, and no other kind's", () => {
  const ask = rows.find((r) => r.actionId === "task-ask-1");
  assert.equal(ask.priority, "urgent");
  assert.equal(ask.context.summary, "The cache layer lands this week and needs a backing store.");
  assert.equal(rows.find((r) => r.actionId === "task-approve-1").priority, "high");
  assert.equal(rows.find((r) => r.actionId === "task-ask-2").priority, "normal");
  assert.ok(!("priority" in rows.find((r) => r.kind === "question")));
});

test("only the urgent row pops: high, normal, low and absent never do", () => {
  const picked = P.popupQueue(rows, "lundmatt1", fakeStorage(), NOW);
  assert.deepEqual(picked.map((r) => r.actionId), ["task-ask-1"]);
  for (const priority of ["high", "normal", "low", undefined]) {
    assert.equal(P.pickPopup([urgent("x", 1, { priority })], "me", fakeStorage(), NOW), null, String(priority));
  }
});

test("an urgent task for another login does not pop; for me (any case) or for everyone does", () => {
  assert.equal(P.isUrgentForMe(urgent("x", 1, { for: "someone-else" }), "lundmatt1"), false);
  assert.equal(P.isUrgentForMe(urgent("x", 1, { for: "LundMatt1" }), "lundmatt1"), true);
  assert.equal(P.isUrgentForMe(urgent("x", 1), "lundmatt1"), true);
  assert.equal(P.isUrgentForMe(urgent("x", 1, { kind: "question" }), "lundmatt1"), false);
});

test("several urgent tasks: one at a time, the oldest first, the next once it is decided or snoozed", () => {
  const three = [urgent("b", 200), urgent("a", 100), urgent("c", 300)];
  const s = fakeStorage();
  assert.equal(P.pickPopup(three, "me", s, NOW).actionId, "a");
  // Decided: the engine drops its row.
  assert.equal(P.pickPopup(three.filter((r) => r.actionId !== "a"), "me", s, NOW).actionId, "b");
  P.snooze(s, "me", "b", 5 * 60000, NOW);
  assert.equal(P.pickPopup(three.filter((r) => r.actionId !== "a"), "me", s, NOW).actionId, "c");
});

test("a snooze hides one task until it elapses, kept per login under hitlp.snooze.<login>.<taskId>", () => {
  const s = fakeStorage();
  const list = [urgent("t1", 100)];
  const until = P.snooze(s, "me", "t1", 15 * 60000, NOW);
  assert.equal(s.map.get("hitlp.snooze.me.t1"), String(NOW + 15 * 60000));
  assert.equal(P.pickPopup(list, "me", s, NOW + 60000), null);
  assert.equal(P.nextWake(list, "me", s, NOW), until);
  assert.equal(P.pickPopup(list, "me", s, until + 1).actionId, "t1", "back once it elapses");
  assert.equal(P.pickPopup(list, "other", s, NOW).actionId, "t1", "another login's snooze is its own");
  // A reload reads the same storage.
  const reloaded = { getItem: s.getItem };
  assert.equal(P.pickPopup(list, "me", reloaded, NOW + 60000), null);
});

test("Stop all for 1 hour hides every urgent popup for an hour under hitlp.silence.<login>", () => {
  const s = fakeStorage();
  const list = [urgent("t1", 100), urgent("t2", 200)];
  P.silence(s, "me", P.SILENCE_MS, NOW);
  assert.equal(s.map.get("hitlp.silence.me"), String(NOW + 3600000));
  assert.equal(P.pickPopup(list, "me", s, NOW + 59 * 60000), null);
  assert.equal(P.nextWake(list, "me", s, NOW), NOW + 3600000);
  assert.equal(P.pickPopup(list, "me", s, NOW + 3600001).actionId, "t1");
});

test("the keys: 1–9 press that tile, Escape snoozes 5 min, a box keeps its own keys", () => {
  const buttons = [{ label: "A" }, { label: "B" }];
  assert.deepEqual(P.keyAction("1", buttons), { press: 0 });
  assert.deepEqual(P.keyAction("2", buttons), { press: 1 });
  assert.equal(P.keyAction("3", buttons), null);
  assert.equal(P.keyAction("0", buttons), null);
  assert.deepEqual(P.keyAction("Escape", buttons), { snooze: 5 * 60000 });
  assert.equal(P.keyAction("1", buttons, true), null);
  assert.equal(P.keyAction("Escape", buttons, true), null);
});

test("the header names the attested requester and whom for, or the ticket, missing parts left out", () => {
  assert.equal(P.headerText({ attested: { requester: "smith-run-7", onBehalfOf: { kind: "person", id: "lundmatt1" } } }), "smith-run-7 · lundmatt1 NEEDS YOU");
  assert.equal(P.headerText({ attested: { requester: "deploy-bot" }, repo: "o/n", ticket: 5 }), "deploy-bot · o/n#5 NEEDS YOU");
  assert.equal(P.headerText({ attested: { requester: "b-bot" }, for: "b" }), "b-bot · b NEEDS YOU", "`for` when no attested person");
  assert.equal(P.headerText({ attested: { agent: "old-0.1-field" } }), "NEEDS YOU", "the spec's attested names the requester, not an agent");
  assert.equal(P.headerText({ asserted: { agent: "liar" } }), "NEEDS YOU", "the requester's own claim is never the header");
  assert.equal(P.headerText({}), "NEEDS YOU");
});

test("as the popup, an urgent ask draws who needs you, the question as the headline, numbered buttons and the Later row", () => {
  const html = card(rows.find((r) => r.actionId === "task-ask-1"));
  assert.match(html, /class="htView htPopup"/);
  assert.match(html, /class="htNeeds">.*smith-run-7 · lundmatt1 NEEDS YOU/);
  assert.doesNotMatch(html, /← Needs you/, "no back link in the popup");
  assert.match(html, /<h1 class="htHeadline">Which store should the cache use\?<\/h1><p class="htQuestionMore">Postgres is already in the stack; Redis would be new\.<\/p>/);
  assert.match(html, /The cache layer lands this week/);
  assert.match(html, /data-outcome="answered" class="htBtn">Postgres<kbd>1<\/kbd>/);
  assert.match(html, /data-outcome="answered" class="htBtn">Redis<kbd>2<\/kbd>/);
  assert.match(html, /Comment \(optional, sent with your answer\)/);
  assert.match(html, /class="htLater">.*5 min.*15 min.*1 hour.*Stop all for 1 hour/);
  assert.match(html, /1–9 picks an option · Esc snoozes 5 min/);
  assert.match(html, /class="nmDeadline"/, "the countdown");
});

test("as the popup, an ask with a text schema draws the answer box and one Answer button", () => {
  const html = card(rows.find((r) => r.actionId === "task-ask-2"));
  assert.match(html, /class="htAnswer"[^>]*type="text"/);
  assert.match(html, />Answer<kbd>1<\/kbd>/);
  assert.doesNotMatch(html, /<kbd>2<\/kbd>/);
});

test("as the popup, an approve draws its headline, the judge, Approve 1 and Reject 2, the comment and folded details; an error draws", () => {
  const row = rows.find((r) => r.actionId === "task-approve-1");
  const html = card(row);
  assert.match(html, /APPROVAL NEEDED/);
  assert.match(html, /<h1 class="htHeadline">Deploy engine 1.42 to the cluster<\/h1>/);
  assert.match(html, /data-outcome="approved" class="htBtn approve">Approve<kbd>1<\/kbd>/);
  assert.match(html, /data-outcome="rejected" class="htBtn reject">Reject<kbd>2<\/kbd>/);
  assert.match(html, /Technical details: digest, scope, payload/);
  assert.match(html, /digest sha256:9f3c0d1e/);
  assert.match(card(row, { initialError: "the task is already decided (or timed out)" }), /class="err htErr">the task is already decided/);
  const view = renderToStaticMarkup(React.createElement(HitlpTask, { row, now: NOW_S, teachReady: false }));
  assert.match(view, /<kbd>A<\/kbd>/, "the work-area view keeps A / R");
  assert.doesNotMatch(view, /htLater/);
});

test("the popup draws nothing without a login or an urgent row, and the urgent one over a scrim", () => {
  const draw = (props) => renderToStaticMarkup(React.createElement(HitlpPopup, { storage: fakeStorage(), ...props }));
  assert.equal(draw({ rows, login: null }), "");
  assert.equal(draw({ rows: rows.filter((r) => r.priority !== "urgent"), login: "lundmatt1" }), "");
  const html = draw({ rows, login: "lundmatt1" });
  assert.match(html, /class="uoBackdrop" role="dialog" aria-modal="true"/);
  assert.equal((html.match(/class="htView htPopup"/g) || []).length, 1);
  assert.match(html, /class="uoPanel hpPanel"/);
  assert.match(html, /data-task="task-ask-1"/);
});

test("as the popup, a choice ask with no schema enum draws Something else as one more numbered tile; its key reveals the box", () => {
  const row = urgent("u-other", NOW_S, { responseSchema: { type: "string" }, options: [{ value: "a", label: "A" }, { value: "b", label: "B" }] });
  const html = card(row);
  assert.match(html, />Something else<kbd>3<\/kbd>/);
  assert.doesNotMatch(html, /class="htAnswer"/);
  const { buttonsFor, pressOf } = require("../dist/needs-you.cjs");
  const spec = buttonsFor(row);
  const a = P.keyAction("3", spec.buttons);
  assert.deepEqual(a, { press: 2 });
  assert.deepEqual(pressOf(spec, spec.buttons[a.press], false, ""), { reveal: true }, "the key reveals the box as a click does");
  assert.match(card(row, { initialOther: true }), /class="htAnswer"/);
});
