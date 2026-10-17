// HITLP Requests and Closed (#100064): the HITLP server's tasks
// (test/fixture-hitlp-tasks.json, open and decided) through src/needs-you.js
// fetchHitlpTasks, drawn by hitlp-requests.jsx; and the explorer's HITLP
// Requests branch, Needs you first under Tickets, the Tickets heading as the
// Tickets pane and the leaf rows' chevron gap.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { fetchHitlpTasks } = require("../src/needs-you");
const { HitlpRequests, HitlpClosed, Age, fmtAge, filterOpen, filterClosed, requesterOf, fmtClock, closedRowClass } = require("../dist/hitlp-requests.cjs");
const { Explorer, Branch, Row } = require("../dist/explorer.cjs");

const tasks = JSON.parse(fs.readFileSync(path.join(__dirname, "fixture-hitlp-tasks.json"), "utf8"));
const NOW = Math.floor(Date.parse("2026-09-25T00:00:00Z") / 1000);
const read = () => fetchHitlpTasks({ surface: async () => ({ tasks }), url: "https://hitlp.example/surface/v1", now: NOW });
const draw = (C, props) => renderToStaticMarkup(React.createElement(C, { now: NOW, ...props }));

test("the age reads as the mockup does: 3h 07m, 17m 04s, 42s, and days past a day", () => {
  assert.equal(fmtAge(3 * 3600 + 7 * 60 + 9), "3h 07m");
  assert.equal(fmtAge(17 * 60 + 4), "17m 04s");
  assert.equal(fmtAge(42), "42s");
  assert.equal(fmtAge(2 * 86400 + 4 * 3600), "2d 4h");
  assert.equal(fmtAge(-5), "0s");
  assert.match(draw(Age, { since: NOW - 17 * 60 - 4 }), />17m 04s</);
});

test("the age cell ticks every second and clears its interval on unmount", () => {
  const real = { useState: React.useState, useEffect: React.useEffect };
  const realSet = global.setInterval, realClear = global.clearInterval;
  const intervals = [], cleared = [], effects = [];
  React.useState = (v) => [v, () => {}];
  React.useEffect = (f) => { effects.push(f); };
  global.setInterval = (fn, ms) => { intervals.push(ms); return 77; };
  global.clearInterval = (id) => { cleared.push(id); };
  try {
    Age({ since: NOW - 5 });
    const cleanup = effects[0]();
    assert.deepEqual(intervals, [1000]);
    cleanup();
    assert.deepEqual(cleared, [77]);
  } finally {
    Object.assign(React, real);
    global.setInterval = realSet; global.clearInterval = realClear;
  }
});

test("HITLP Requests: every open task, the five columns in order, the attested requester and the ticket link", async () => {
  const r = await read();
  const html = draw(HitlpRequests, { result: r });
  assert.match(html, /<h1>HITLP Requests · <span class="hrCountOpen">3<\/span><\/h1>/);
  assert.match(html, /hitlp\.example/);
  const heads = [...html.matchAll(/<th>([^<]*)<\/th>/g)].map((m) => m[1]);
  assert.deepEqual(heads, ["Age", "Kind", "Priority", "Requester", "Question"]);
  assert.equal((html.match(/<tr data-task=/g) || []).length, 3, "the open tasks only, from any requester");
  assert.doesNotMatch(html, /Ship the cache behind a flag|Restart the engine pod/, "no closed task");
  assert.match(html, /<span class="hrPrio urgent">urgent<\/span>/);
  assert.match(html, /Deploy engine 1\.42 to the cluster/, "an approve shows its action");
  const ask = r.open.find((x) => x.actionId === "task-ask-1");
  assert.equal(requesterOf(ask), "smith-run-7");
  assert.match(html, />smith-run-7</);
  const linked = { ...ask, ticket: 2201, repo: "codefrak-oss/frakworks", url: "frakworks://ticket/codefrak-oss/frakworks/2201" };
  const opened = [];
  const withLink = draw(HitlpRequests, { result: { ...r, open: [linked] }, onOpenTicket: (repo, n) => opened.push([repo, n]) });
  assert.match(withLink, /<a class="hrTicket" href="frakworks:\/\/ticket\/codefrak-oss\/frakworks\/2201"[^>]*>#2201<\/a>/);
  assert.match(html, /no ticket|The cache layer lands this week/);
});

test("the filters: requester, priority and a case-insensitive search over the question or action", async () => {
  const { open } = await read();
  assert.deepEqual(filterOpen(open, { priority: "urgent" }).map((x) => x.actionId), ["task-ask-1"]);
  assert.deepEqual(filterOpen(open, { q: "DEPLOY ENGINE" }).map((x) => x.actionId), ["task-approve-1"]);
  assert.deepEqual(filterOpen(open, { requester: "smith-run-7" }).map((x) => x.actionId).includes("task-ask-1"), true);
  assert.equal(filterOpen(open, { requester: "nobody" }).length, 0);
  assert.equal(filterOpen(open, {}).length, 3);
  const html = draw(HitlpRequests, { result: { open, closed: [], error: null } });
  assert.match(html, /<option value="" selected="">Any<\/option><option value="urgent">urgent<\/option><option value="high">high<\/option><option value="normal">normal<\/option><option value="low">low<\/option>/);
});

test("an opened task draws below the table with its id and the one task view (HitlpTask); a sent one is muted, a refusal drawn", async () => {
  const r = await read();
  const html = draw(HitlpRequests, { result: r, initialOpen: "task-ask-1", failed: { "task-ask-1": "the HITLP server said no" },
    sent: new Set(["task-approve-1"]) });
  assert.match(html, /<div class="hrDetail" data-open-task="task-ask-1">/);
  assert.match(html, /task task-ask-1/);
  assert.match(html, /<div class="htView" data-task="task-ask-1">/);
  assert.match(html, /data-outcome="answered"[^>]*>Postgres/);
  assert.match(html, /class="htComment"/);
  assert.match(html, /VERIFIED/);
  assert.match(html, /Why, as smith-run-7 states it:<\/span> The cache layer/);
  assert.match(html, /<div class="err htErr">the HITLP server said no<\/div>/);
  assert.doesNotMatch(html, /htBack|nmHitlp/, "no back link, no old card");
  assert.match(html, /class="hrRow muted"[^>]*>|data-task="task-approve-1" class="hrRow muted"/);
});

test("a surface that is not set says where to set it; a failed GET /tasks draws its error", async () => {
  const unset = await fetchHitlpTasks({ configured: false, surface: async () => ({ tasks }) });
  const html = draw(HitlpRequests, { result: unset });
  assert.match(html, /Config &gt; frakworks &gt; HITLP server/);
  assert.doesNotMatch(html, /<table/);
  const failed = await fetchHitlpTasks({ surface: async () => ({ error: "HITLP server answered 503 for GET /tasks", tasks: [] }) });
  assert.match(draw(HitlpRequests, { result: failed }), /<div class="err">HITLP server: HITLP server answered 503/);
  assert.match(draw(HitlpClosed, { result: failed }), /class="err"/);
  const { SURFACE_HINT, SURFACE_HINT_WEB } = require("../src/hitlp-surface-hint");
  for (const h of [SURFACE_HINT, SURFACE_HINT_WEB]) { assert.match(h, /HITLP Requests/); assert.doesNotMatch(h, /Needs view/); }
});

test("Closed: every decided task, newest first, with outcome, who, channel and when, and a plain count", async () => {
  const r = await read();
  assert.deepEqual(r.closed.map((x) => [x.actionId, x.outcome, x.decidedBy, x.channel]), [
    ["task-ask-closed", "answered", "lundmatt1", "desktop"],
    ["task-approve-closed", "rejected", "lundmatt1", "slack"],
  ]);
  const html = draw(HitlpClosed, { result: r });
  assert.match(html, /<h1>Closed · <span class="hrCount">2<\/span><\/h1>/);
  const heads = [...html.matchAll(/<th>([^<]*)<\/th>/g)].map((m) => m[1]);
  assert.deepEqual(heads, ["Decided", "Requester", "Question", "Outcome", "Decided by", "Channel", "When"]);
  assert.match(html, />approve - Restart the engine pod/, "the kind before the action");
  assert.match(html, />ask - Ship the cache behind a flag\?/, "the kind before the question");
  assert.match(html, /Ship the cache behind a flag\?[\s\S]*Restart the engine pod/);
  assert.match(html, />#2201<\/a>/, "the ticket link in the question cell");
  assert.match(html, /hrOutcome-rejected">rejected</);
  assert.doesNotMatch(html, /Which store should the cache use/, "no open task");
});

test("Closed's filters: requester, outcome and the time window over the decided time", async () => {
  const { closed } = await read();
  assert.deepEqual(filterClosed(closed, { outcome: "rejected" }, NOW).map((x) => x.actionId), ["task-approve-closed"]);
  assert.deepEqual(filterClosed(closed, { requester: "first-mate" }, NOW).map((x) => x.actionId), ["task-approve-closed"]);
  assert.equal(filterClosed(closed, { window: "7d" }, NOW).length, 2);
  assert.equal(filterClosed(closed, { window: "30d" }, NOW + 40 * 86400).length, 0);
  assert.equal(filterClosed(closed, { window: "all" }, NOW + 400 * 86400).length, 2);
  assert.equal(filterClosed(closed, { window: "today" }, NOW + 3 * 86400).length, 0);
});

// ---- the explorer -----------------------------------------------------------

const explorer = (props = {}) => renderToStaticMarkup(React.createElement(Explorer, { sel: null, onSelect: () => {}, ...props }));
const labels = (html) => [...html.matchAll(/<span class="exLabel">([^<]*)</g)].map((m) => m[1]);

test("HITLP Requests sits above Tickets with a yellow badge, Closed under it with a plain count; Needs you is Tickets' first row", () => {
  const tree = { repos: [{ repo: "codefrak-oss/frakworks", groups: [] }] };
  const html = explorer({ hitlpOpen: 3, hitlpClosed: 7, needsCount: 2, tree, onLoadDone: () => {} });
  const l = labels(html);
  assert.deepEqual(l.slice(l.indexOf("Utils"), l.indexOf("Utils") + 5), ["Utils", "HITLP Requests", "Closed", "Tickets", "Needs you"]);
  assert.ok(l.indexOf("Needs you") < l.indexOf("Done"), "before the repo branches and Done");
  assert.match(html, /<span class="exLabel">HITLP Requests<\/span><span class="exBadge">3<\/span>/);
  assert.match(html, /<span class="exLabel">Closed<\/span><span class="exCount">7<\/span>/);
  assert.match(html, /<span class="exLabel">Needs you<\/span><span class="exBadge">2<\/span>/);
  assert.equal(l.filter((x) => x === "Tickets").length, 1, "no Tickets leaf repeating the heading");
  const none = explorer({ hitlpOpen: 0, hitlpClosed: 0 });
  assert.doesNotMatch(none, /HITLP Requests<\/span><span class="exBadge">/);
  assert.doesNotMatch(none, /Closed<\/span><span class="exBadge">/);
});

test("a leaf's label starts where a sibling branch's does: Needs you and the repo branch at depth 1", () => {
  const tree = { repos: [{ repo: "codefrak-oss/frakworks", groups: [] }] };
  const html = explorer({ tree });
  const offset = (label) => {
    const m = html.match(new RegExp(`<div class="exRow[^"]*" style="padding-left:(\\d+)px"[^>]*>(<span class="exChev">[^<]*</span>)<span class="exLabel">${label}<`));
    assert.ok(m, `${label} has a chevron span before its label`);
    return Number(m[1]);
  };
  assert.equal(offset("Needs you"), offset("codefrak-oss/frakworks"));
  assert.equal(offset("Needs you"), 22);
});

test("the Tickets heading's label selects the Tickets pane, its chevron alone toggles; HITLP Requests' the same", () => {
  for (const [label, kind] of [["Tickets", "tickets"], ["HITLP Requests", "hitlp-requests"]]) {
    const picked = [], toggled = [];
    const frag = Branch({ depth: 0, label, open: true, item: { kind }, onSelect: (i) => picked.push(i), onToggle: () => toggled.push(1) });
    const row = frag.props.children[0];
    assert.equal(row.props.onClick, undefined, "the row itself neither selects nor toggles");
    const [chev, lab] = row.props.children;
    lab.props.onClick();
    assert.deepEqual(picked, [{ kind }]);
    assert.equal(toggled.length, 0);
    let stopped = false;
    chev.props.onClick({ stopPropagation: () => { stopped = true; } });
    assert.deepEqual([toggled.length, stopped, picked.length], [1, true, 1]);
  }
  // A plain branch still toggles on a click anywhere.
  const toggled = [];
  const plain = Branch({ depth: 0, label: "Config", open: false, onToggle: () => toggled.push(1) }).props.children[0];
  plain.props.onClick();
  assert.equal(toggled.length, 1);
  assert.match(explorer({ sel: { kind: "tickets" } }), /<div class="exRow exBranch on"[^>]*><span class="exChev">▾<\/span><span class="exLabel">Tickets</);
  assert.match(explorer({ sel: { kind: "hitlp-closed" } }), /<div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">Closed</);
  assert.equal(typeof Row, "function");
});

test("Closed's Decided age is H:MM:SS since the decision, past 24 hours unpadded; — when null", () => {
  const html = (since, now) => renderToStaticMarkup(React.createElement(Age, { since, now, fmt: fmtClock }));
  assert.match(html(1000, 1000 + 8 * 3600 + 12 * 60 + 29), />8:12:29</);
  assert.match(html(1000, 1000 + 31 * 3600 + 5 * 60), />31:05:00</);
  assert.match(html(1000, 2000), new RegExp(`title="${new Date(1000000).toISOString()}"`));
  assert.match(html(null, 1000), />—</);
});

test("Closed's row class per decision: approved, rejected, answered colored, the rest plain", async () => {
  assert.equal(closedRowClass("approved"), "hrRow hrRow-approved");
  assert.equal(closedRowClass("rejected"), "hrRow hrRow-rejected");
  assert.equal(closedRowClass("answered"), "hrRow hrRow-answered");
  for (const o of ["timed_out", "cancelled", "failed"]) assert.equal(closedRowClass(o), "hrRow");
  const html = draw(HitlpClosed, { result: await read() });
  assert.match(html, /data-task="task-ask-closed" class="hrRow hrRow-answered"/);
  assert.match(html, /data-task="task-approve-closed" class="hrRow hrRow-rejected"/);
});
