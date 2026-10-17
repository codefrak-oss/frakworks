// The issue detail for an issue the engine's board has no row for (#548): a
// closed confirm-mode ticket that GET /needs still lists as an acceptance is
// read from GET /ticket and drawn with its ask, and only a failed read of that
// draws "not on the engine's board".
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { readOffBoard, needsOf, pendingNeed } = require("../dist/flow.cjs");
const { TicketDetail } = require("../dist/index.cjs");
const { toRows } = require("../src/needs-you");

const REPO = "codefrak-oss/frakworks";
const render = (props) => renderToStaticMarkup(React.createElement(TicketDetail, props));

const TICKET_508 = {
  number: 508, title: "Desktop: a confirm-mode feature", state: "CLOSED",
  url: `https://github.com/${REPO}/issues/508`, author: { login: "lundmatt1" },
  labels: [{ name: "feature" }, "tier:5"], comments: [], prs: [],
};
const NEEDS = toRows([
  { repo: REPO, number: 508, title: TICKET_508.title, kind: "acceptance",
    wanted: "Does the feature do what you asked? Accept it, or say what is missing.",
    since: "2026-09-25T10:00:00Z", actionId: "0541c973-ba0b-11f1-a5bc-4ac7a8a66890" },
  { repo: REPO, number: 12, title: "another", kind: "question", wanted: "?", since: null, actionId: "aaaaaaaa-1" },
]);

const spyFleet = (reply) => {
  const calls = [];
  return { calls, ticketDetail: async (...a) => { calls.push(a); return typeof reply === "function" ? reply(...a) : reply; } };
};

test("a closed issue off the board with an acceptance row draws its title, state, labels, link and ask", async () => {
  const fleet = spyFleet({ ticket: TICKET_508 });
  const offBoard = await readOffBoard({ board: { issues: [] }, issue: null, repo: REPO, number: 508, fleet });
  assert.deepEqual(fleet.calls, [["codefrak-oss", "frakworks", 508]]);
  const needs = needsOf(NEEDS, REPO, 508);
  assert.equal(needs.length, 1);
  assert.equal(pendingNeed(needs).actionId, "0541c973-ba0b-11f1-a5bc-4ac7a8a66890");
  const html = render({ repo: REPO, number: 508, issue: null, flowResult: null, offBoard, needs, now: Date.now() });
  assert.doesNotMatch(html, /is not on the engine&#x27;s board now/);
  assert.match(html, /Desktop: a confirm-mode feature/);
  assert.match(html, /class="chip tdState closed">closed</);
  assert.match(html, /class="chip">feature</);
  assert.match(html, /class="chip">tier:5</);
  assert.match(html, /href="https:\/\/github.com\/codefrak-oss\/frakworks\/issues\/508"/);
  assert.match(html, /reading the HITLP task/, "the acceptance's HITLP task, inline (#100070)");
  assert.doesNotMatch(html, /askCard decisionCard/);
  assert.match(html, /Does the feature do what you asked\?/);
});

test("an issue missing from both the board and /ticket draws the error with the read's reason", async () => {
  const fleet = spyFleet({ error: "no such ticket in a target repo: codefrak-oss/frakworks#9999" });
  const offBoard = await readOffBoard({ board: { issues: [] }, issue: null, repo: REPO, number: 9999, fleet });
  const html = render({ repo: REPO, number: 9999, issue: null, flowResult: null, offBoard, needs: [] });
  assert.match(html, /class="err">codefrak-oss\/frakworks#9999 is not on the engine&#x27;s board now/);
  assert.match(html, /no such ticket in a target repo/);
  const thrown = await readOffBoard({ board: { issues: [] }, issue: null, repo: REPO, number: 1,
    fleet: { ticketDetail: async () => { throw new Error("gone"); } } });
  assert.deepEqual(thrown, { error: "IPC failed: gone" });
});

test("an issue on the board, or a board still loading, makes no /ticket read", () => {
  const fleet = spyFleet({ ticket: TICKET_508 });
  assert.equal(readOffBoard({ board: { issues: [{}] }, issue: { number: 508 }, repo: REPO, number: 508, fleet }), null);
  assert.equal(readOffBoard({ board: null, issue: null, repo: REPO, number: 508, fleet }), null);
  assert.equal(fleet.calls.length, 0);
});

test("the screen reads an off-board ticket over a bridge verb that takes no repo, and posts no answer", () => {
  const src = (f) => fs.readFileSync(path.join(__dirname, "..", "src", f), "utf8");
  const index = src(path.join("renderer", "index.jsx"));
  assert.doesNotMatch(index, /answerIntake|answerWithFiles|onAnswer/);
  assert.match(index, /onRefreshNeeds=\{refreshNeeds\}/);
  assert.match(src("preload.js"), /ticketDetail: \(owner, repoName, n\) => ipcRenderer\.invoke\("engine:ticket"/);
  assert.match(src("main.js"), /ipcMain\.handle\("engine:ticket", async \(_e, owner, repo, n\) => \{\n  const r = await fetchTicketDetail\(owner, repo, n\);/);
});
