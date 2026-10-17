// An acceptance or a decision at the top of an issue's detail (#100070): the
// detail mounts the live HITLP task inline (hitlp-task.jsx TicketTask), with
// the task id from the engine's comment hint (ask-card.jsx hintTaskIdOf) or,
// with none, looked up by the ticket in the open-task list.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { pendingNeed, pendingKind, DECIDED_KINDS } = require("../dist/flow.cjs");
const { TicketDetail, TicketDetailView } = require("../dist/index.cjs");

const REPO = "codefrak-oss/frakworks";
const kids = (el) => {
  const c = el && el.props && el.props.children;
  return c == null ? [] : Array.isArray(c) ? c.flat(Infinity) : [c];
};
const find = (el, pred) => {
  if (!el || typeof el !== "object") return null;
  if (pred(el)) return el;
  for (const k of kids(el)) { const hit = find(k, pred); if (hit) return hit; }
  return null;
};
const taskOf = (props) => find(TicketDetailView({ repo: REPO, number: 904, now: 0, needs: [], ...props }), (e) => e.type && e.type.name === "TicketTask");

test("which rows mount the task", () => {
  assert.deepEqual(DECIDED_KINDS, ["question", "acceptance", "decision"]);
  assert.equal(pendingNeed([{ kind: "decision", actionId: "d-1" }]).actionId, "d-1");
  const rows = [{ kind: "acceptance", actionId: "i-1" }];
  assert.equal(pendingKind({ intake: "i-1" }, rows), "acceptance");
  assert.equal(pendingKind({ intake: "i-2" }, rows), "question");
});

const issue = (pendingQuestion) => ({ number: 904, repo: REPO, title: "t", labels: [], author: "a", url: "u", pendingQuestion });
const doc = (body) => ({ repo: REPO, issue: { title: "t", body: "", labels: [], comments: [{ body }] } });

test("on the board, an acceptance need mounts the task named by the issue's comment hint", () => {
  const needs = [{ repo: REPO, ticket: 904, kind: "acceptance", actionId: "i-1", ask: "Accept?" }];
  const t = taskOf({ issue: issue({ question: "Accept or reject PR #950", intake: "i-1" }), needs,
    doc: doc("Accept?\n\n— engine feature `f`. HITLP task t-9 on the engine's HITLP server.") });
  assert.ok(t);
  assert.deepEqual([t.props.repo, t.props.number, t.props.taskId], [REPO, 904, "t-9"]);
  const byUrl = taskOf({ issue: issue({ question: "Accept?", intake: "i-1" }), needs, doc: doc("Decide it on https://h/d/1.") });
  assert.equal(byUrl.props.taskId, null, "no task id in the hint: looked up by the ticket");
});

test("off the board, a decision need mounts the task too, and nothing posts to the engine", () => {
  const needs = [{ repo: REPO, ticket: 904, kind: "decision", actionId: "d-1", ask: "Decide the proposal" }];
  assert.ok(taskOf({ issue: null, needs, offBoard: { ticket: { title: "t", state: "CLOSED", labels: [] } } }));
  const html = renderToStaticMarkup(React.createElement(TicketDetail, { repo: REPO, number: 904, now: 0, issue: null, needs,
    offBoard: { ticket: { title: "t", state: "CLOSED", labels: [] } } }));
  assert.match(html, /reading the HITLP task/);
  assert.doesNotMatch(html, /decisionCard|decideLink|askOption/);
});

