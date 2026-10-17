// One ticket detail whichever list opened it (#802): Engine > Issues (the
// board has the row), Tickets > Needs you (an off-board issue, read through
// the off-board read) and a Tickets tree row (the ticket read's doc) all draw
// detail.jsx's TicketDetail, with the same inline HITLP task for a `question`
// /needs row (hitlp-task.jsx TicketTask, #100070), and the same authorize notice.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { toRows } = require("../src/needs-you");
const { needsOf } = require("../dist/flow.cjs");
const { TicketDetail, TicketDetailView } = require("../dist/index.cjs");

const REPO = "codefrak-oss/frakworks";
const N = 802;
const ACTION = "5b1e0c2a-bde8-11f1-9362-46e996d04630";
const TITLE = "Desktop app: one ticket detail";
const NEED = { repo: REPO, number: N, title: TITLE, kind: "question", wanted: "answer: which list?",
  since: "2026-10-01T09:00:00Z", actionId: ACTION, question: "Which list opens the detail?" };
const needs = needsOf(toRows([NEED]), REPO, N);
const AUTH = needsOf(toRows([{ repo: REPO, number: N, title: TITLE, kind: "authorize",
  wanted: "authorize: add the label", since: "2026-10-01T09:00:00Z", actionId: null }]), REPO, N);

// What each entry point hands the detail. The board's row is there only when
// the board has the issue (Engine > Issues); a Needs you row of a closed issue
// is off the board; a tree row opens with the ticket read's doc.
const BOARD_ROW = { number: N, repo: REPO, title: TITLE, labels: [], author: "a", url: `https://github.com/${REPO}/issues/${N}`, flow: null, pendingQuestion: null };
const DOC = { repo: REPO, issue: { title: TITLE, state: "OPEN", labels: [], body: "",
  comments: [{ body: "Which list opens the detail?\n\n— engine intake `i`. HITLP task t-802 on the engine's HITLP server." }] }, prs: [] };
const ENTRIES = {
  "Engine > Issues": { issue: BOARD_ROW, offBoard: null, doc: DOC },
  "Tickets > Needs you": { issue: null, offBoard: { ticket: { title: TITLE, state: "CLOSED", labels: [] } }, doc: null },
  "Tickets tree": { issue: null, offBoard: null, doc: DOC },
};
const base = { repo: REPO, number: N, now: Date.parse("2026-10-01T10:00:00Z") };
const draw = (props) => renderToStaticMarkup(React.createElement(TicketDetail, { ...base, ...props }));

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
test("a question /needs row mounts the same inline task from every entry point", () => {
  for (const [name, props] of Object.entries(ENTRIES)) {
    const html = draw({ ...props, needs });
    assert.match(html, /reading the HITLP task/, name);
    assert.doesNotMatch(html, /askCard decisionCard|decideLink/, name);
  }
});

test("every entry point's task is the ticket's, with the hint's id when the ticket read has it, and the screen posts no answer", () => {
  for (const [name, props] of Object.entries(ENTRIES)) {
    const view = TicketDetailView({ ...base, ...props, needs });
    const task = find(view, (e) => e.type && e.type.name === "TicketTask");
    assert.ok(task, name);
    assert.deepEqual([task.props.repo, task.props.number], [REPO, N], name);
    assert.equal(task.props.taskId, props.doc ? "t-802" : null, name);
  }
  const src = fs.readFileSync(path.join(__dirname, "../src/renderer/index.jsx"), "utf8");
  assert.doesNotMatch(src, /answerIntake|answerWithFiles/);
});

test("an authorize row shows the same waiting-to-be-authorized notice from every entry point", () => {
  const cards = Object.entries(ENTRIES).map(([name, props]) => {
    const html = draw({ ...props, needs: AUTH });
    const at = html.indexOf('<div class="askCard authorizeCard"');
    assert.ok(at >= 0, name);
    assert.doesNotMatch(html, /class="askSend/, `${name}: nothing to send`);
    const card = html.slice(at, html.indexOf("</div></div>", at));
    assert.match(card, /Approve: waiting to be authorized/, name); // Needs you's label, in HITLP's words since #2173
    assert.match(card, /no authorize action/, name);
    return card;
  });
  for (const c of cards) assert.equal(c, cards[0]);
});

test("the head puts the GitHub link next to the ticket number, ahead of the meta span", () => {
  const html = draw(ENTRIES["Engine > Issues"]);
  const num = html.indexOf(`#${N}`), gh = html.indexOf('class="ghLink"'), meta = html.indexOf('class="meta"');
  assert.ok(num > 0 && gh > num && gh < meta, "GitHub link sits between the number and the meta span");
});

test("index.jsx opens every ticket through one TicketScreen and one detail component", () => {
  const src = fs.readFileSync(path.join(__dirname, "../src/renderer/index.jsx"), "utf8");
  assert.doesNotMatch(src, /IssueScreen|IssueDetail/);
  assert.equal((src.match(/<TicketScreen\b/g) || []).length, 1);
  assert.equal((src.match(/<TicketDetail\b/g) || []).length, 1);
  assert.match(src, /detail\(ticket, /);
  assert.match(src, /detail\(issueSel, /);
});

test("the ticket's story points show beside its labels, from the doc or the off-board read", () => {
  const fromDoc = draw({ issue: null, offBoard: null, doc: { issue: { title: TITLE, state: "OPEN", labels: ["feature"], points: 5 } } });
  assert.match(fromDoc, /<span class="chip">feature<\/span><span class="chip tdPoints"[^>]*>5 pts<\/span>/);
  const offBoard = draw({ issue: null, offBoard: { ticket: { title: TITLE, state: "CLOSED", labels: [], points: 8 } }, doc: null });
  assert.match(offBoard, />8 pts</);
  assert.doesNotMatch(draw(ENTRIES["Tickets tree"]), /pts</, "no points, no chip");
});
