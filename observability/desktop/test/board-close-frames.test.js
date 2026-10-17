// A closed issue leaves the Issues board and the Tickets tree on its `ticket`
// `closed` frame (#1065), not on a refetch: `GET /issues` is GitHub's open
// listing, which lags a close by seconds, so the refetch the close used to
// run still listed the issue and nothing asked again. useBoardRows
// (src/renderer/engine-events.js, as index.jsx wires it) hands the close to
// its `close` callback (dropBoardRow), remembers the number, and a later read
// is filtered with withoutRemoved. React's hooks are hand-stubbed as in
// test/board-row-frames.test.js.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const ev = require("../dist/engine-events.cjs");
const { treeOf, withClosed, bucketDone } = require("../src/ticket-tree");

const harness = () => {
  const real = { useRef: React.useRef, useState: React.useState, useEffect: React.useEffect, useCallback: React.useCallback };
  const cells = [];
  let cleanups = [];
  let idx = 0;
  const run = (fn) => {
    idx = 0;
    const pendingEffects = [];
    React.useRef = (v) => { const c = (cells[idx] ||= { current: v }); idx++; return c; };
    React.useState = (v) => { const c = (cells[idx] ||= { s: typeof v === "function" ? v() : v }); idx++; return [c.s, (n) => { c.s = typeof n === "function" ? n(c.s) : n; }]; };
    React.useCallback = (f) => f;
    React.useEffect = (f) => { pendingEffects.push(f); };
    let out;
    try { out = fn(); } finally { Object.assign(React, real); }
    for (const c of cleanups) c();
    cleanups = [];
    for (const f of pendingEffects) { const c = f(); if (typeof c === "function") cleanups.push(c); }
    return out;
  };
  const unmount = () => { for (const c of cleanups) c(); cleanups = []; };
  return { run, unmount };
};

const REPO = "codefrak-oss/frakworks";
const OTHER = "codefrak-oss/other";
const row = (number, repo = REPO) => ({ repo, number, title: `Issue ${number}`, author: "lundmatt1", labels: ["bug"],
  url: `https://github.com/${repo}/issues/${number}`, createdAt: "2026-10-05T12:50:57Z", updatedAt: "2026-10-05T12:50:57Z",
  kind: "bug", authorized: false, flow: null, pendingQuestion: null, blockedBy: [], deferred: false, children: [] });

// window.fleet: the frame push, and a `GET /issues` and ticket tree that lag a close: they list what `listed` holds.
const fakeFleet = () => {
  const frames = new Set();
  const streams = new Set();
  const reads = { issues: 0, tree: 0 };
  const listed = [row(1027), row(1033)];
  global.window = { fleet: {
    onEngineEvent: (cb) => { frames.add(cb); return () => frames.delete(cb); },
    onEngineStream: (cb) => { streams.add(cb); return () => streams.delete(cb); },
    engineSnapshotted: async () => {},
    engineStreamState: async () => null,
    issues: async () => { reads.issues++; return { issues: listed.slice(), failures: [] }; },
    ticketTree: async () => { reads.tree++; return { ...treeOf(listed.slice(), REPO), issues: listed.slice() }; },
  } };
  return { frame: (f) => { for (const cb of [...frames]) cb(f); }, reads, listed };
};
const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

// The App's board and tree as index.jsx keeps them: each read filtered with withoutRemoved, the frame's row
// applied with applyBoardRow, a close dropped with dropBoardRow, both guarded by the selection (`sel`).
const mountBoard = async (sel = REPO) => {
  const state = {};
  const readBoard = async () => {
    const r = await window.fleet.issues();
    state.issues = { ...r, issues: ev.withoutRemoved(r.issues) };
    const t = await window.fleet.ticketTree();
    const issues = ev.withoutRemoved(t.issues);
    state.tree = { ...t, ...treeOf(issues, t.repo), issues };
  };
  await readBoard();
  const h = harness();
  h.run(() => {
    ev.useBoardRows((r) => {
      const issues = ev.applyBoardRow(state.issues.issues, r, sel);
      if (issues !== state.issues.issues) state.issues = { ...state.issues, issues };
      const t = ev.applyBoardRow(state.tree.issues, r, state.tree.repo);
      if (t !== state.tree.issues) state.tree = { ...treeOf(t, state.tree.repo), issues: t };
    }, readBoard, (repo, number) => {
      const issues = ev.dropBoardRow(state.issues.issues, repo, number, sel);
      if (issues !== state.issues.issues) state.issues = { ...state.issues, issues };
      const t = ev.dropBoardRow(state.tree.issues, repo, number, state.tree.repo);
      if (t !== state.tree.issues) state.tree = { ...treeOf(t, state.tree.repo), issues: t };
    });
  });
  return { state, h, readBoard };
};
const numbers = (list) => list.map((i) => i.number).sort();
const inTree = (tree, n) => tree.groups.some((g) => JSON.stringify(g).includes(`"number":${n}`));
const closed = (number, repo = REPO, extra = {}) => ({ type: "ticket", subscription: repo,
  data: { repo, number, action: "closed", label: null, prNumber: null, issue: null, ...extra } });
const fresh = () => { ev.refetchRegistry.clear(); return fakeFleet(); };

test("a `ticket` `closed` frame drops the row from the board and the tree at once, with no GET /issues", async () => {
  const fleet = fresh();
  const { state, h } = await mountBoard();
  assert.ok(inTree(state.tree, 1027));
  const before = { ...fleet.reads };

  fleet.frame(closed(1027));
  assert.deepEqual(numbers(state.issues.issues), [1033], "gone from the board on the frame");
  assert.equal(inTree(state.tree, 1027), false, "and from the tree, regrouped");
  await tick(ev.REFETCH_DEBOUNCE_MS + 50);
  assert.deepEqual(fleet.reads, before, "no GET /issues and no tree read for the close");
  assert.deepEqual(numbers(state.issues.issues), [1033]);
  h.unmount();
  ev.refetchRegistry.clear();
});

test("a GET /issues answer that still lists the closed issue, read after the frame, does not bring it back", async () => {
  const fleet = fresh();
  const { state, h, readBoard } = await mountBoard();
  fleet.frame(closed(1027));
  assert.ok(fleet.listed.some((i) => i.number === 1027), "GitHub's listing still has #1027");

  await readBoard();
  assert.deepEqual(numbers(state.issues.issues), [1033], "the lagging read is filtered");
  assert.equal(inTree(state.tree, 1027), false, "in the tree too");
  // Another frame's refetch (a comment on #1033) reads the same lagging list.
  fleet.frame({ type: "ticket", subscription: REPO, data: { repo: REPO, number: 1033, action: "commented", issue: null } });
  await tick(ev.REFETCH_DEBOUNCE_MS + 50);
  assert.deepEqual(numbers(state.issues.issues), [1033], "nor does a later frame's refetch");
  h.unmount();
  ev.refetchRegistry.clear();
});

test("`reopened` puts the row back: from the frame's row, or by the refetch when it carries none", async () => {
  let fleet = fresh();
  let { state, h } = await mountBoard();
  fleet.frame(closed(1027));
  fleet.frame({ type: "ticket", subscription: REPO, data: { repo: REPO, number: 1027, action: "reopened", prNumber: null, issue: row(1027) } });
  assert.deepEqual(numbers(state.issues.issues), [1027, 1033], "from the frame's row, at once");
  assert.ok(inTree(state.tree, 1027));
  assert.equal(ev.isRemoved(REPO, 1027), false, "and the number leaves the removed set");
  h.unmount();

  fleet = fresh();
  ({ state, h } = await mountBoard());
  fleet.frame(closed(1027));
  const reads = fleet.reads.issues;
  fleet.frame({ type: "ticket", subscription: REPO, data: { repo: REPO, number: 1027, action: "reopened", prNumber: null, issue: null } });
  assert.equal(ev.isRemoved(REPO, 1027), false, "a rowless reopen forgets the number before its refetch");
  await tick(ev.REFETCH_DEBOUNCE_MS + 50);
  assert.equal(fleet.reads.issues, reads + 1, "and refetches");
  assert.deepEqual(numbers(state.issues.issues), [1027, 1033], "which lists it again");
  h.unmount();
  ev.refetchRegistry.clear();
});

test("any later frame carrying the row takes the number out of the removed set", async () => {
  const fleet = fresh();
  const { state, h } = await mountBoard();
  fleet.frame(closed(1027));
  fleet.frame({ type: "flow", subscription: REPO, data: { repo: REPO, number: 1027, state: "started", issue: row(1027) } });
  assert.equal(ev.isRemoved(REPO, 1027), false);
  assert.deepEqual(numbers(state.issues.issues), [1027, 1033]);
  h.unmount();
  ev.refetchRegistry.clear();
});

test("a pull request's `ticket` frame removes no issue row, even one with the same number", async () => {
  const fleet = fresh();
  const { state, h } = await mountBoard();
  fleet.frame({ type: "ticket", subscription: REPO, data: { repo: REPO, number: 1027, action: "pr", prNumber: 1027, issue: null } });
  fleet.frame(closed(1027, REPO, { prNumber: 1027 }));
  assert.deepEqual(numbers(state.issues.issues), [1027, 1033]);
  assert.ok(inTree(state.tree, 1027));
  assert.equal(ev.isRemoved(REPO, 1027), false, "nothing is remembered for a pull request");
  await tick(ev.REFETCH_DEBOUNCE_MS + 50);
  assert.deepEqual(numbers(state.issues.issues), [1027, 1033], "its refetch still lists the issue");
  h.unmount();
  ev.refetchRegistry.clear();
});

test("a close in another repo than the selected one changes neither the board nor the tree", async () => {
  const fleet = fresh();
  const { state, h } = await mountBoard();
  const issues = state.issues, tree = state.tree;
  fleet.frame(closed(1027, OTHER));
  assert.equal(state.issues, issues, "the board is untouched");
  assert.equal(state.tree, tree, "and so is the tree");
  assert.equal(ev.dropBoardRow([row(1027, OTHER)], OTHER, 1027, "*").length, 0, "All repos takes every repo's close");
  h.unmount();
  ev.refetchRegistry.clear();
});

test("the removed set: bounded by REMOVED_KEEP_MS, emptied by a connect's catch-up", async () => {
  fresh();
  const t0 = Date.now();
  ev.rememberRemoved(REPO, 1027, t0);
  assert.equal(ev.isRemoved(REPO.toUpperCase(), 1027, t0 + 1000), true, "case-insensitive by repo");
  assert.equal(ev.isRemoved(OTHER, 1027, t0 + 1000), false, "per repo");
  assert.equal(ev.isRemoved(REPO, 1027, t0 + ev.REMOVED_KEEP_MS), false, "forgotten once REMOVED_KEEP_MS has passed");
  assert.ok(ev.REMOVED_KEEP_MS >= 10_000 && ev.REMOVED_KEEP_MS <= 10 * 60_000, "sized to GitHub's listing lag, not forever");

  ev.rememberRemoved(REPO, 1027);
  ev.rememberRemoved(OTHER, 5);
  await ev.runCatchUp({ repo: REPO, state: "catching-up", since: 1 }, new Map(), async () => {});
  assert.equal(ev.isRemoved(REPO, 1027), false, "the catch-up's read is authoritative: a reopen missed offline shows");
  assert.equal(ev.isRemoved(OTHER, 5), true, "another connection's set stays");
  await ev.runCatchUp({ repo: "*", state: "catching-up", since: 2 }, new Map(), async () => {});
  assert.equal(ev.isRemoved(OTHER, 5), false, "the `*` connection's catch-up empties every repo's");
  ev.refetchRegistry.clear();
});

test("Needs rows (numbered `ticket`) are filtered too; the Done group gains the closed issue from its remembered row", () => {
  fresh();
  const at = "2026-10-05T13:27:56Z";
  const closedIssue = { repo: REPO, number: 1027, title: "Issue 1027", url: row(1027).url, closedAt: at, stateReason: null, closedBy: [], parent: null };
  ev.rememberRemoved(REPO, 1027, Date.now(), closedIssue);
  const rows = [{ ticket: 1027, repo: REPO, kind: "authorize" }, { ticket: 1033, repo: REPO, kind: "authorize" }];
  assert.deepEqual(ev.withoutRemoved(rows, "ticket").map((r) => r.ticket), [1033]);

  const now = Date.parse(at) + 60_000;
  const done = { repo: REPO, limit: 50, more: false, buckets: bucketDone([], now) };
  const got = withClosed(done, ev.removedClosed(REPO), now);
  assert.deepEqual(got.buckets.find((b) => b.id === "today").tickets.map((t) => t.number), [1027]);
  assert.equal(withClosed(got, ev.removedClosed(REPO), now), got, "not added twice");
  assert.equal(withClosed(done, ev.removedClosed(OTHER), now), done, "another repo's close adds nothing");
  assert.ok(ev.closedIssue(closed(1027).data) && !ev.closedIssue({ ...closed(1027).data, prNumber: 3 }));
  ev.refetchRegistry.clear();
});
