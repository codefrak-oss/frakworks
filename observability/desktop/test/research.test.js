// The research ticket's Answer section (#1262, src/renderer/pitch.jsx): the
// engine-kept page under "Answer, round N", the ack controls while the ticket
// waits for the ack (its decision need), and no controls on a closed ticket
// whose page still shows. On test/fixture-research.json: #21 at round 1 awaiting
// its ack, #22 at round 3 with a thread, #23 closed and accepted.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { PitchView, isResearch, isProposal, pitchHeading } = require("../dist/pitch.cjs");
const { TicketPitchView, pitchRoundOf, pitchHtmlOf } = require("../dist/ticket.cjs");

const FIX = JSON.parse(fs.readFileSync(path.join(__dirname, "fixture-research.json"), "utf8"));
const draw = (el) => renderToStaticMarkup(el);
const page = (n) => FIX.pages[`o/r:${n}`];

test("a research board row is a research, not a proposal, and the heading carries the round", () => {
  assert.equal(isResearch(FIX.board[0]), true);
  assert.equal(isProposal(FIX.board[0]), false, "no PR: the Pitch view is reached through isResearch");
  assert.equal(isResearch({ flow: { flow: "proposal", prUrl: "u" } }), false);
  assert.equal(pitchHeading(1), "Answer, round 1");
  assert.equal(pitchHeading(3), "Answer, round 3");
  assert.equal(pitchHeading(null), "Pitch");
  assert.equal(pitchRoundOf(page(22)), 3);
  assert.equal(pitchRoundOf({ pitch: { kind: "html", content: "x" } }), null, "a proposal's pitch has no round");
});

test("the fixtures have the three states: round 1 awaiting its ack, round 3 with a thread, closed with its page", () => {
  assert.equal(page(21).pitch.round, 1);
  assert.equal(page(22).pitch.round, 3);
  assert.equal(FIX.thread["22"].length, 3);
  assert.equal(page(23).pitch.round, 2);
  assert.equal(FIX.board.some((r) => r.number === 23), false, "closed: no board row");
  assert.equal(FIX.needs.some((n) => n.ticket === 23), false, "closed: no need");
  for (const n of FIX.needs) assert.equal(n.wanted, "answer ready: accept, save, drop, or ask a follow-up", "the Needs row reads as the research decision");
});

test("the research Pitch view mounts its ack's HITLP task inline while it waits, and draws nothing when it does not", () => {
  const fleet = { ticketPitch: async () => page(21) };
  const waiting = draw(React.createElement(PitchView, { repo: "o/r", number: 21, research: true, decision: true, taskId: "t-21", fleet }));
  assert.match(waiting, /Your ack/);
  assert.match(waiting, /reading the HITLP task/);
  assert.doesNotMatch(waiting, /<textarea|pitchChat/, "no box, no proposal controls on a research ticket");
  const notWaiting = draw(React.createElement(PitchView, { repo: "o/r", number: 22, research: true, decision: false, fleet }));
  assert.doesNotMatch(notWaiting, /Your ack|reading the HITLP task|htView/);
});

test("a closed research ticket's page shows under Answer with its round and no controls", () => {
  const r = page(23);
  const html = draw(React.createElement(TicketPitchView, { html: pitchHtmlOf(r), round: pitchRoundOf(r) }));
  assert.match(html, /Answer, round 2/);
  assert.match(html, /<iframe/);
  assert.doesNotMatch(html, /answerControls|Ask a follow-up/);
  assert.match(draw(React.createElement(TicketPitchView, { html: "<p>x</p>" })), />Pitch</, "a proposal's page keeps its heading");
});

test("the heading is Answer, round N for a research page in the ticket detail section's overlay too", () => {
  const html = draw(React.createElement(TicketPitchView, { html: "<p>x</p>", round: 3, full: true }));
  assert.equal((html.match(/Answer, round 3/g) || []).length, 2, "the section and the overlay bar");
});
