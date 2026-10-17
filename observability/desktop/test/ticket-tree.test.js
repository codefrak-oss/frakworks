// The explorer's "Tickets" branch grouping (src/ticket-tree.js groupOf): a
// flow stuck on an open Operaton incident lands in "Needs you", where its
// ticket view has Fix and Propose a fix.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { groupOf, groupTickets, buildForest, fetchTicketTree } = require("../src/ticket-tree");

const incident = { id: "inc-1", activityId: "task-start-smith", type: "failedJob", message: "boom",
  processInstanceId: "p-2", createdAt: "2026-09-26T04:00:00Z" };
const running = { flow: "chore", outcome: "started", startedAt: "2026-09-26T03:00:00Z", ended: false };

test("a running flow with an open incident needs the user, not in progress", () => {
  assert.equal(groupOf({ number: 1, kind: "chore", authorized: true, flow: { ...running, incident } }), "needs-you");
  assert.equal(groupOf({ number: 2, kind: "chore", authorized: true, flow: { ...running, incident: null } }), "in-progress");
  assert.equal(groupOf({ number: 3, kind: "chore", authorized: true, flow: running }), "in-progress", "a board without the field");
});

test("an ended flow's incident does not count, and a pending question still does", () => {
  assert.equal(groupOf({ number: 4, kind: "chore", authorized: true, flow: { ...running, ended: true, incident } }), "other");
  assert.equal(groupOf({ number: 5, pendingQuestion: { question: "?" }, flow: running }), "needs-you");
});

test("groupTickets puts incident tickets in Needs you, newest first", () => {
  const groups = Object.fromEntries(groupTickets([
    { number: 7, title: "a", url: "u7", kind: "chore", authorized: true, flow: { ...running, incident } },
    { number: 9, title: "b", url: "u9", kind: "chore", authorized: true, flow: { ...running, incident } },
    { number: 8, title: "c", url: "u8", kind: "chore", authorized: true, flow: running },
  ]).map((g) => [g.id, g.tickets.map((t) => t.number)]));
  assert.deepEqual(groups["needs-you"], [9, 7]);
  assert.deepEqual(groups["in-progress"], [8]);
});

test("a proposal whose flow has a pitch carries it into the tree, and the Tickets view marks it (#563)", () => {
  const React = require("react");
  const { renderToStaticMarkup } = require("react-dom/server");
  const { Explorer } = require("../dist/explorer.cjs");
  const groups = groupTickets([
    { number: 10, title: "pitched", url: "u10", kind: "proposal", authorized: true, flow: { ...running, flow: "proposal", pitch: "docs/proposals/x.html" } },
    { number: 11, title: "plain", url: "u11", kind: "proposal", authorized: true, flow: { ...running, flow: "proposal" } },
  ]);
  const tickets = groups.find((g) => g.id === "in-progress").tickets;
  assert.deepEqual(tickets.map((t) => [t.number, t.pitch]), [[11, null], [10, "docs/proposals/x.html"]]);
  const html = renderToStaticMarkup(React.createElement(Explorer, { sel: null, onSelect: () => {}, tree: { groups } }));
  assert.equal([...html.matchAll(/class="exPitch"/g)].length, 1);
  assert.ok(html.includes('title="docs/proposals/x.html">pitch</span>'));
});

test("the tree follows the repo switcher: one repo's /issues?repo=, or in All repos a branch per repo (#577)", async () => {
  const React = require("react");
  const { renderToStaticMarkup } = require("react-dom/server");
  const { Explorer } = require("../dist/explorer.cjs");
  const issues = [
    { repo: "o/b", number: 3, title: "b3", url: "u", kind: "work", authorized: true, flow: running },
    { repo: "o/a", number: 1, title: "a1", url: "u", kind: "work", authorized: true, flow: running },
  ];
  const seen = [];
  const fetchIssues = async (opts) => { seen.push(opts); return { issues: opts.repo ? issues.filter((i) => i.repo === opts.repo) : issues }; };
  const one = await fetchTicketTree({ repo: "o/a", fetchIssues });
  assert.equal(one.repo, "o/a");
  assert.equal(one.repos, undefined);
  const all = await fetchTicketTree({ repo: null, fetchIssues });
  assert.deepEqual(seen, [{ repo: "o/a" }, {}]);
  assert.deepEqual(all.repos.map((r) => r.repo), ["o/a", "o/b"]);
  const html = renderToStaticMarkup(React.createElement(Explorer, { sel: null, onSelect: () => {}, tree: all }));
  assert.ok(html.indexOf(">o/a<") > 0 && html.indexOf(">o/a<") < html.indexOf(">o/b<"), "a branch per repo, in name order");
  assert.match(html, /b3/);
});

// ---- the tree of related work (#571) -----

const u = (n) => `https://github.com/o/r/issues/${n}`;
const open = (number, over = {}) => ({ repo: "o/r", number, title: `Issue ${number}`, url: u(number), labels: [], kind: "chore",
  authorized: true, flow: running, pendingQuestion: null, blockedBy: [], deferred: false, children: [], parent: null, intake: null, ...over });
// Intake #500 filed feature #501, split into #502 (merged), #503 (open), a pending draft and a dropped one.
const feature = (over = {}) => open(501, { kind: "feature", intake: { id: "i-500", number: 500, url: u(500) },
  blockedBy: [{ number: 503, state: "OPEN" }, { number: 490, state: "OPEN" }, { number: 502, state: "CLOSED" }],
  children: [
    { index: 2, title: "Part 3: docs", kind: "chore", state: "pending" },
    { index: 0, title: "Part 1: engine", kind: "chore", state: "merged", number: 502, url: u(502), prNumber: 510 },
    { index: 3, title: "Part 4: gone", kind: "", state: "dropped" },
    { index: 1, title: "Part 2: explorer", kind: "chore", state: "open", number: 503, url: u(503) },
  ], ...over });
const part = (over = {}) => open(503, { parent: { number: 501, url: u(501) }, ...over });
const numbers = (forest) => {
  const out = [];
  const walk = (n) => { if (!n.draft) { out.push(n.number); n.children.forEach(walk); } };
  forest.forEach(walk);
  return out.sort((a, b) => a - b);
};

test("buildForest: intake -> feature -> parts nests the parts under the feature, in the split's order, the intake as provenance", () => {
  const forest = buildForest([part(), feature()]);
  assert.equal(forest.length, 1);
  const f = forest[0];
  assert.equal(f.number, 501);
  assert.deepEqual(f.intake, { number: 500, url: u(500) }, "the intake is on the feature, not a tree level");
  assert.equal(f.family, "in-progress");
  assert.equal(f.size, 2);
  assert.deepEqual(f.children.map((c) => [c.index, c.draft || "ticket", c.number ?? null]),
    [[0, "closed", 502], [1, "ticket", 503], [2, "pending", null], [3, "dropped", null]]);
  assert.equal(f.children[0].prNumber, 510);
  assert.deepEqual([f.partsDone, f.partsTotal], [1, 3], "the dropped draft does not count");
  assert.deepEqual(f.blockers, [490], "the nested part is not repeated as a blocker, a closed one is not one");
  assert.equal(f.urgency, null);
  assert.equal(f.partOf, null);
});

test("buildForest: a split of a split nests two levels deep", () => {
  const forest = buildForest([
    feature(),
    part({ kind: "feature", children: [{ index: 0, title: "Part 2a", kind: "chore", state: "open", number: 504, url: u(504) }] }),
    open(504, { parent: { number: 503, url: u(503) } }),
  ]);
  assert.equal(forest.length, 1);
  const p = forest[0].children.find((c) => c.number === 503);
  assert.deepEqual(p.children.map((c) => c.number), [504]);
  assert.deepEqual([p.partsDone, p.partsTotal], [0, 1]);
  assert.equal(forest[0].size, 3);
});

test("buildForest: a part that needs the user moves its whole family to Needs you; counts stay per ticket", () => {
  const groups = groupTickets([feature(), part({ pendingQuestion: { question: "Which?" } }), open(9)]);
  const by = Object.fromEntries(groups.map((g) => [g.id, g]));
  assert.deepEqual(by["needs-you"].tickets.map((t) => t.number), [501]);
  assert.equal(by["needs-you"].count, 2, "#501 and #503: tickets, not families");
  assert.deepEqual(by["in-progress"].tickets.map((t) => t.number), [9]);
  const f = by["needs-you"].tickets[0];
  assert.equal(f.urgency, "warn");
  assert.equal(f.needsYou, null, "the feature's own row keeps its own state");
  assert.equal(f.children.find((c) => c.number === 503).needsYou, "question");
  const crit = buildForest([feature(), part({ flow: { ...running, incident: { id: "inc" } } })]);
  assert.equal(crit[0].urgency, "crit");
});

test("buildForest: an orphan part (its parent closed or off the board) is a root with part of #n, never dropped", () => {
  const forest = buildForest([part(), open(7)]);
  assert.deepEqual(forest.map((r) => r.number), [503, 7]);
  assert.deepEqual(forest[0].partOf, { number: 501, closed: true });
  assert.equal(forest[0].family, "in-progress");
  const g = Object.fromEntries(groupTickets([part({ deferred: true, flow: null })]).map((x) => [x.id, x.tickets.map((t) => t.number)]));
  assert.deepEqual(g.deferred, [503], "at the top level of its own group");
});

test("buildForest: a ticket with no relations is a root with no children, grouped as groupOf says", () => {
  const [r] = buildForest([open(12, { flow: null, kind: "chore", authorized: false })]);
  assert.deepEqual([r.number, r.children, r.partsTotal, r.intake, r.partOf, r.family], [12, [], 0, null, null, "not-authorized"]);
  const self = buildForest([open(13, { intake: { id: "x", number: 13, url: u(13) } })]);
  assert.equal(self[0].intake, null, "an intake that is the ticket itself (one issue per ask) is no provenance");
});

test("buildForest: a cycle or a self parent does not loop, and every open issue appears exactly once", () => {
  const issues = [
    open(1, { parent: { number: 1 } }),
    open(2, { parent: { number: 3 } }), open(3, { parent: { number: 4 } }), open(4, { parent: { number: 2 } }),
    open(5, { parent: { number: 2 }, children: [{ index: 0, title: "x", state: "open", number: 2 }] }),
    feature(), part(), part(),
  ];
  const forest = buildForest(issues);
  assert.deepEqual(numbers(forest), [1, 2, 3, 4, 5, 501, 503]);
  assert.equal(forest.reduce((n, r) => n + r.size, 0), 7);
});

test("the tree draws a family: collapsed with its chevron and count; in Needs you open down to the part, indented, drafts not selectable", () => {
  const React = require("react");
  const { renderToStaticMarkup } = require("react-dom/server");
  const { Explorer } = require("../dist/explorer.cjs");
  const draw = (issues) => renderToStaticMarkup(React.createElement(Explorer,
    { sel: null, onSelect: () => {}, tree: { repo: "o/r", groups: groupTickets(issues) } }));

  const quiet = draw([feature(), part()]);
  assert.match(quiet, /class="exChev exTwisty"[^>]*>▸</, "a collapsed family has a chevron");
  assert.match(quiet, /class="exCount">1\/3</);
  assert.doesNotMatch(quiet, /#503/, "collapsed: its parts are not drawn");
  assert.match(quiet, /title="Issue 501 \(via #500\)"/, "the intake is the row's provenance");
  assert.match(quiet, /⧗ #490/);
  assert.match(quiet, /In-Progress<\/span><span class="exCount">2</, "the group counts tickets");

  const needs = draw([feature(), part({ pendingQuestion: { question: "?" } })]);
  assert.match(needs, /class="exChev exTwisty"[^>]*>▾</, "a Needs-you family starts open");
  assert.doesNotMatch(needs, /class="exDot/, "no dot while open");
  const padOf = (n) => Number(new RegExp(`padding-left:(\\d+)px"[^>]*>(?:(?!<div)[^])*?>#${n}<`).exec(needs)[1]);
  assert.equal(padOf(503) - padOf(501), 14, "one level deeper, 14px");
  assert.ok((needs.match(/class="exGuide"/g) || []).length >= 4, "a guide line on each nested row");
  assert.match(needs, /<span class="exBadge">\?<\/span>/, "the part that needs the user carries the badge");
  assert.match(needs, /✓ PR #510/);
  const pending = needs.match(/<div class="exRow exNode exDraft"[^>]*>(?:(?!<\/div>)[^])*Part 3: docs(?:(?!<\/div>)[^])*<\/div>/);
  assert.ok(pending, "the pending draft has its row");
  assert.match(pending[0], /not filed/);
  assert.match(needs, /exDropped[^]*?exUnfinished">Part 4: gone</);
  assert.match(needs, /Needs you<\/span><span class="exCount">2</);
});

test("a collapsed family shows its most urgent part as a dot before the count; the chevron toggles without selecting", () => {
  const React = require("react");
  const { renderToStaticMarkup } = require("react-dom/server");
  const { TicketNode } = require("../dist/explorer.cjs");
  const [f] = buildForest([feature(), part({ flow: { ...running, incident: { id: "inc" } } })]);
  const props = { t: f, repo: "o/r", base: 2, level: 0, expand: true, toggle: () => {}, sel: null, onSelect: () => {} };
  const closed = renderToStaticMarkup(React.createElement(TicketNode, { ...props, open: { "t:o/r#501": false } }));
  assert.match(closed, /class="exDot crit"[^>]*><\/span><span class="exCount">1\/3</);
  const opened = renderToStaticMarkup(React.createElement(TicketNode, { ...props, open: {} }));
  assert.doesNotMatch(opened, /exDot/);
  assert.match(opened, /<span class="exBadge crit">!<\/span>/);

  // The chevron's click stops before the row's: it toggles, and selects nothing.
  const el = TicketNode({ ...props, open: {} });
  const row = React.Children.toArray(el.props.children)[0];
  const chev = React.Children.toArray(row.props.children).find((c) => c && c.props && /exTwisty/.test(c.props.className || ""));
  const seen = [];
  let stopped = false;
  chev.props.onClick({ stopPropagation: () => { stopped = true; } });
  assert.ok(stopped);
  const toggled = [];
  const el2 = TicketNode({ ...props, open: {}, toggle: (k, d) => toggled.push([k, d]), onSelect: (i) => seen.push(i) });
  const row2 = React.Children.toArray(el2.props.children)[0];
  React.Children.toArray(row2.props.children).find((c) => c && c.props && /exTwisty/.test(c.props.className || ""))
    .props.onClick({ stopPropagation() {} });
  assert.deepEqual(toggled, [["t:o/r#501", true]], "open by default in Needs you, so the first click closes it");
  assert.deepEqual(seen, []);
  row2.props.onClick();
  assert.deepEqual(seen, [{ kind: "ticket", id: "o/r#501" }]);
});

test("the ticket view says which intake filed the ticket, found from its tree entry", () => {
  const React = require("react");
  const { renderToStaticMarkup } = require("react-dom/server");
  const { treeEntryOf } = require("../dist/explorer.cjs");
  const { TicketDetail } = require("../dist/index.cjs");
  const tree = { repo: "o/r", groups: groupTickets([feature(), part()]) };
  assert.deepEqual(treeEntryOf(tree, { repo: "o/r", number: 501 }).intake, { number: 500, url: u(500) });
  assert.equal(treeEntryOf(tree, { repo: "o/r", number: 503 }).number, 503, "a nested part is found too");
  assert.equal(treeEntryOf(tree, { repo: "o/other", number: 501 }), null);
  assert.equal(treeEntryOf({ repos: [{ repo: "o/r", groups: tree.groups }] }, { repo: "O/R", number: 501 }).number, 501);
  const draw = (intake) => renderToStaticMarkup(React.createElement(TicketDetail,
    { number: 501, repo: "o/r", doc: null, error: "x", now: 0, intake }));
  // The header's error path still draws; the intake line sits under the title, with a document.
  const doc = { repo: "o/r", issue: { title: "Tree", url: u(501), state: "OPEN", labels: [], body: "", comments: [] }, prs: [] };
  const html = renderToStaticMarkup(React.createElement(TicketDetail, { number: 501, repo: "o/r", doc, now: 0, intake: { number: 500, url: u(500) } }));
  assert.match(html, /class="tdIntake">Intake <a href="https:\/\/github.com\/o\/r\/issues\/500"[^>]*>#500<\/a>/);
  assert.doesNotMatch(draw(null), /tdIntake/);
});
