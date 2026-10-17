// The explorer's "Tickets" branch: src/ticket-tree.js's open-issue grouping
// from the engine's GET /issues, its closed-issue read and the bucketing by
// close date, against stand-in engine reads; and explorer.jsx's DoneBranch
// drawn closed (it asks for nothing) and open.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { bucketDone, fetchDoneTickets, doneTicket, DONE_PAGE, DONE_MAX,
  GROUPS, groupTickets, fetchTicketTree } = require("../src/ticket-tree");
const { DoneBranch, Explorer } = require("../dist/explorer.cjs");

// Local noon, so today/yesterday hold in any time zone the tests run in.
const NOW = new Date(2026, 8, 24, 12, 0, 0).getTime();
const at = (daysAgo, hour = 10) => new Date(2026, 8, 24 - daysAgo, hour, 0, 0).toISOString();
const issues = [
  { number: 68, title: "marker", url: "u68", closedAt: at(0), stateReason: "completed",
    closedBy: [{ number: 69, url: "p69", state: "MERGED" }] },
  { number: 64, title: "dupe", url: "u64", closedAt: at(0, 8), stateReason: "not_planned", closedBy: [] },
  { number: 50, title: "yday", url: "u50", closedAt: at(1), stateReason: "completed", closedBy: [] },
  { number: 40, title: "week", url: "u40", closedAt: at(5), stateReason: "completed", closedBy: [] },
  { number: 10, title: "old", url: "u10", closedAt: at(30), stateReason: null, closedBy: [] },
];

test("closed tickets land in today, yesterday, this week and earlier, newest first", () => {
  const b = Object.fromEntries(bucketDone(issues, NOW).map((x) => [x.id, x.tickets.map((t) => t.number)]));
  assert.deepEqual(b, { today: [68, 64], yesterday: [50], week: [40], earlier: [10] });
});

test("a ticket says what closed it: the PR, or that it was not planned", () => {
  assert.deepEqual(doneTicket(issues[0]), { repo: null, number: 68, title: "marker", url: "u68", closedAt: at(0), unfinished: false, pr: 69, parent: null, body: "" });
  // #577: a ticket is owner/name#n, from the engine's row or its url
  assert.equal(doneTicket({ ...issues[0], repo: "o/r" }).repo, "o/r");
  assert.equal(doneTicket({ ...issues[0], url: "https://github.com/o/r2/issues/68" }).repo, "o/r2");
  assert.equal(doneTicket(issues[1]).unfinished, true);
  assert.equal(doneTicket(issues[1]).pr, null);
  assert.equal(doneTicket({ ...issues[1], stateReason: "NOT_PLANNED" }).unfinished, true, "GitHub's GraphQL spelling too");
  assert.equal(doneTicket(issues[4]).unfinished, false);
});

test("fetchDoneTickets asks the engine for one clamped page of this repo's closed issues and says when there may be more", async () => {
  const calls = [];
  const fetchClosedIssues = async (q) => { calls.push(q); return { issues, failures: [], error: null }; };
  const r = await fetchDoneTickets({ repo: "o/r", limit: 5, fetchClosedIssues, now: NOW });
  assert.deepEqual(calls[0], { limit: DONE_PAGE, repo: "o/r" });
  assert.equal(r.limit, DONE_PAGE);
  assert.equal(r.more, false, "5 back for 50 asked: that is all of them");
  const full = await fetchDoneTickets({ repo: "o/r", limit: 100, now: NOW,
    fetchClosedIssues: async () => ({ issues: Array(100).fill(issues[0]), failures: [], error: null }) });
  assert.equal(full.more, true);
  assert.equal(DONE_MAX, 500, "the engine's cap");
  const max = await fetchDoneTickets({ repo: "o/r", limit: 1e9, now: NOW,
    fetchClosedIssues: async () => ({ issues: Array(DONE_MAX).fill(issues[0]), failures: [], error: null }) });
  assert.equal(max.limit, DONE_MAX);
  assert.equal(max.more, false, "never past the cap");
  const failed = await fetchDoneTickets({ repo: "o/r", fetchClosedIssues: async () => ({ error: "engine: HTTP 400" }) });
  assert.match(failed.error, /HTTP 400/);
  assert.deepEqual(failed.buckets, []);
  const threw = await fetchDoneTickets({ repo: "o/r", fetchClosedIssues: async () => { throw new Error("boom"); } });
  assert.match(threw.error, /boom/);
});

// ---- the open tree: GET /issues grouped ----------------------------------

const board = (over) => ({ repo: "codefrak-oss/frakworks", title: "t", url: "u", labels: [], kind: null,
  authorized: false, flow: null, pendingQuestion: null, blockedBy: [], deferred: false, ...over });
const running = { startedAt: "2026-09-24T10:00:00Z", ended: false };
const byGroup = (groups) => Object.fromEntries(groups.map((g) => [g.id, g.tickets.map((t) => t.number)]));

test("the open tree has the five groups, in order", () => {
  assert.deepEqual(GROUPS.map((g) => g.label), ["Needs you", "In-Progress", "Waiting on a blocker", "Not authorized", "Other open"]);
  assert.deepEqual(groupTickets([]).map((g) => [g.id, g.tickets.length]), GROUPS.map((g) => [g.id, 0]));
});

test("each issue lands in its group, newest first, in the shape the tree draws", () => {
  const g = groupTickets([
    board({ number: 1, pendingQuestion: { id: "q1", question: "?" } }),
    board({ number: 2, flow: running }),
    board({ number: 3, deferred: true, blockedBy: [{ number: 9, state: "OPEN" }] }),
    board({ number: 4, kind: "bot-task", authorized: false }),
    board({ number: 5 }),
    board({ number: 6, kind: "bot-task", authorized: true }),
    board({ number: 7, flow: { startedAt: "2026-09-24T10:00:00Z", ended: true } }),
    board({ number: 8, flow: { startedAt: null, ended: false } }),
    board({ number: 12, flow: running }),
  ]);
  assert.deepEqual(byGroup(g), { "needs-you": [1], "in-progress": [12, 2], deferred: [3], "not-authorized": [4], other: [8, 7, 6, 5] });
  const { number, title, url, labels, pitch, children, family } = g[0].tickets[0];
  assert.deepEqual({ number, title, url, labels, pitch, children, family },
    { number: 1, title: "t", url: "u", labels: [], pitch: null, children: [], family: "needs-you" });
  assert.deepEqual(groupTickets([board({ number: 1, labels: ["bot-task", { name: "x" }] })])[4].tickets[0].labels, ["bot-task", "x"]);
});

test("the first matching group wins", () => {
  const all = { pendingQuestion: { id: "q" }, flow: running, deferred: true, kind: "bot-task", authorized: false };
  const g = byGroup(groupTickets([
    board({ number: 1, ...all }),
    board({ number: 2, ...all, pendingQuestion: null }),
    board({ number: 3, ...all, pendingQuestion: null, flow: null }),
    board({ number: 4, ...all, pendingQuestion: null, flow: null, deferred: false }),
  ]));
  assert.deepEqual(g, { "needs-you": [1], "in-progress": [2], deferred: [3], "not-authorized": [4], other: [] });
});

test("fetchTicketTree keeps only the app's repo, case-insensitively, and reports a failed read", async () => {
  let asked = 0;
  const fetchIssues = async () => { asked++; return { issues: [
    board({ number: 1 }), board({ number: 2, repo: "CodeFrak-OSS/Frakworks" }), board({ number: 3, repo: "codefrak-oss/other" }),
  ], failures: [], error: null }; };
  const r = await fetchTicketTree({ repo: "codefrak-oss/frakworks", fetchIssues });
  assert.equal(asked, 1);
  assert.equal(r.error, undefined);
  assert.deepEqual(byGroup(r.groups).other, [2, 1]);
  const other = await fetchTicketTree({ repo: "codefrak-oss/other", fetchIssues });
  assert.deepEqual(byGroup(other.groups).other, [3]);

  const failed = await fetchTicketTree({ repo: "o/r", fetchIssues: async () => ({ error: "engine unreachable", issues: [], failures: [] }) });
  assert.match(failed.error, /engine unreachable/);
  assert.deepEqual(failed.groups, []);
  const threw = await fetchTicketTree({ repo: "o/r", fetchIssues: async () => { throw new Error("boom"); } });
  assert.match(threw.error, /boom/);
  assert.deepEqual(threw.groups, []);
});

test("the Done branch draws nothing and asks for nothing while closed; opened, it shows today's rows with their outcome", () => {
  const draw = (props) => renderToStaticMarkup(React.createElement(DoneBranch,
    { toggle: () => {}, onLoad: () => { throw new Error("no load on render"); }, sel: null, onSelect: () => {}, ...props }));
  const closed = draw({ done: null, open: {} });
  assert.match(closed, /Done/);
  assert.doesNotMatch(closed, /#68/);

  const done = { buckets: bucketDone(issues, NOW), limit: DONE_PAGE, more: true };
  const html = draw({ done, open: { done: true } });
  assert.match(html, /Today/);
  assert.match(html, /#68/);
  assert.match(html, /✓ PR #69/);
  assert.match(html, /exUnfinished.*#64/);
  assert.match(html, /not planned/);
  assert.doesNotMatch(html, /#50/, "yesterday starts collapsed");
  assert.match(html, /load 50 more/);
  assert.match(html, />5\+</, "the count says there may be more");
});

test("a closed part of a split says which feature it was part of, and Done stays chronological (explorer-work-tree.md)", () => {
  const part = { number: 502, title: "Part 1", url: "u502", closedAt: at(0, 9), stateReason: "completed",
    closedBy: [{ number: 510, url: "p510", state: "MERGED" }], parent: { number: 501, url: "u501" } };
  assert.equal(doneTicket(part).parent, 501);
  assert.equal(doneTicket(issues[0]).parent, null);
  const buckets = bucketDone([...issues, part], NOW);
  assert.deepEqual(buckets[0].tickets.map((t) => t.number), [68, 502, 64], "by when it closed, not under its feature");
  const html = renderToStaticMarkup(React.createElement(DoneBranch, { done: { buckets, limit: DONE_PAGE }, open: { done: true },
    toggle: () => {}, onLoad: () => {}, sel: null, onSelect: () => {} }));
  assert.match(html, /#502<\/span> Part 1<\/span><span class="exSuffix"> part of #501<\/span>/);
  assert.equal([...html.matchAll(/class="exSuffix"/g)].length, 1, "only the part carries it");
});

test("Done shows what the engine found: its count, its rows, and `none` for nothing", () => {
  const draw = (done) => renderToStaticMarkup(React.createElement(DoneBranch, { done, open: { done: true },
    toggle: () => {}, onLoad: () => {}, sel: null, onSelect: () => {} }));
  const found = draw({ buckets: bucketDone([issues[2]], NOW), limit: DONE_PAGE });
  assert.match(found, /class="exCount">1</);
  assert.match(found, /Yesterday/);
  assert.match(draw({ buckets: bucketDone([], NOW), limit: DONE_PAGE }), />none</);
});
