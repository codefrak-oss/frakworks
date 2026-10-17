// One card for every Smith question (#689): a `/needs` row's full `question`
// (the engine's engine.intake_question text) waits the same as the board's
// `pendingQuestion`, on the board or off it: the detail mounts the live HITLP
// task in place (hitlp-task.jsx TicketTask, #100070). The
// one-line `ask` is only the fallback for a flow with no recorded question.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { toRows } = require("../src/needs-you");
const { needsOf, needQuestion } = require("../dist/flow.cjs");
const { TicketDetail } = require("../dist/index.cjs");

const REPO = "codefrak-oss/frakworks";
const ACTION = "3d814633-ba9d-11f1-a28c-d243e3abce9f";
const FULL = [
  "Before I draft the proposal, two questions:",
  "",
  "1. Should the needs row carry the whole question, or should the app fetch GET /intake per row?",
  "2. Does the off-board card keep the one-line summary as its heading?",
  "",
  "```ask",
  "kind: choice",
  "question: Where should the full question come from?",
  "options:",
  "  - id: row",
  "    label: Row — carry it on GET /needs",
  "  - id: fetch",
  "    label: Fetch — read GET /intake per row",
  "```",
].join("\n");
const NEED = { repo: REPO, number: 681, title: "Desktop ticket view", kind: "question",
  wanted: "answer: Before I draft the proposal, two questions:", since: "2026-09-27T10:00:00Z",
  actionId: ACTION, question: FULL };
const rows = needsOf(toRows([NEED]), REPO, 681);
const detail = (props) => renderToStaticMarkup(React.createElement(TicketDetail, { repo: REPO, number: 681, now: Date.now(), ...props }));
const onBoard = { number: 681, repo: REPO, title: "Desktop ticket view", labels: ["proposal"], author: "a", url: "u", flow: null, pendingQuestion: null };
const offBoard = { ticket: { title: "Desktop ticket view", state: "OPEN", labels: [] } };

// The waiting row mounts the live HITLP task in place (#100070): the question
// is the task's, read from the HITLP server, and no read-only card draws.
const assertWhole = (page) => {
  assert.match(page, /reading the HITLP task/);
  assert.doesNotMatch(page, /askCard decisionCard|class="askOption"/);
};

test("a /needs row's card is built from its full question, else its one-line ask", () => {
  assert.deepEqual(needQuestion(rows[0]), { question: FULL });
  assert.deepEqual(needQuestion({ ...rows[0], question: null }), { question: rows[0].ask });
  assert.equal(needQuestion(null), null);
});

test("off the board, the waiting /needs row mounts the inline task", () => {
  assertWhole(detail({ issue: null, needs: rows, offBoard }));
});

test("on the board with no pendingQuestion, the waiting /needs row mounts the same task", () => {
  assertWhole(detail({ issue: onBoard, needs: rows }));
  // With no /needs row either, nothing waits and no card draws.
  assert.doesNotMatch(detail({ issue: onBoard, needs: [] }), /reading the HITLP task|htView/);
});

test("the board's and the /needs row's card draw the same", () => {
  const board = detail({ issue: { ...onBoard, pendingQuestion: { question: FULL } }, needs: rows });
  const fromNeeds = detail({ issue: onBoard, needs: rows });
  const card = (html) => html.slice(html.indexOf("reading the HITLP task"), html.indexOf("asking the engine for the ticket"));
  assert.equal(card(fromNeeds), card(board));
});

test("a flow with no recorded question falls back to the one-line ask", () => {
  const bare = needsOf(toRows([{ ...NEED, question: null, wanted: "answer the question on the issue" }]), REPO, 681);
  assert.match(detail({ issue: null, needs: bare, offBoard }), /answer the question on the issue/);
});
