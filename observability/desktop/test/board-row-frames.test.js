// A new issue reaches the Issues board and the Tickets tree from its frame
// (#1048): the engine's `ticket` `opened` frame and the `created` frame of the
// engine's own create carry the issue's board row, read by number, and
// useBoardRows (src/renderer/engine-events.js, as index.jsx wires it) applies
// it with applyBoardRow instead of re-reading `GET /issues`, whose list lags a
// just-created issue by seconds. React's hooks are hand-stubbed as in
// test/engine-events-renderer.test.js.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const ev = require("../dist/engine-events.cjs");
const { treeOf } = require("../src/ticket-tree");

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
const row = (number, title = `Issue ${number}`) => ({ repo: REPO, number, title, author: "lundmatt1", labels: ["chore"],
  url: `https://github.com/${REPO}/issues/${number}`, createdAt: "2026-10-05T12:50:57Z", updatedAt: "2026-10-05T12:50:57Z",
  kind: "chore", authorized: false, flow: null, pendingQuestion: null, blockedBy: [], deferred: false, children: [] });

// window.fleet: the frame push, and a `GET /issues` (issues) and ticket tree that lag: they never list the new issue.
const fakeFleet = () => {
  const frames = new Set();
  const reads = { issues: 0, tree: 0 };
  const listed = [row(1033, "Intake: the test intake")];
  global.window = { fleet: {
    onEngineEvent: (cb) => { frames.add(cb); return () => frames.delete(cb); },
    onEngineStream: () => () => {},
    engineSnapshotted: async () => {},
    engineStreamState: async () => null,
    issues: async () => { reads.issues++; return { issues: listed.slice(), failures: [] }; },
    ticketTree: async () => { reads.tree++; return { ...treeOf(listed.slice(), REPO), issues: listed.slice() }; },
  } };
  return { frame: (f) => { for (const cb of [...frames]) cb(f); }, reads };
};
const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

// The App's board and tree as index.jsx keeps them: read once, then useBoardRows with the same applies and refetch.
const mountBoard = async (fleet) => {
  const state = { issues: await window.fleet.issues(), tree: await window.fleet.ticketTree() };
  const refreshBoard = async () => {
    state.issues = await window.fleet.issues();
    state.tree = await window.fleet.ticketTree();
  };
  const h = harness();
  h.run(() => {
    ev.useBoardRows((r) => {
      const issues = ev.applyBoardRow(state.issues.issues, r, REPO);
      if (issues !== state.issues.issues) state.issues = { ...state.issues, issues };
      const t = ev.applyBoardRow(state.tree.issues, r, state.tree.repo);
      if (t !== state.tree.issues) state.tree = { ...treeOf(t, state.tree.repo), issues: t };
    }, refreshBoard);
  });
  return { state, h };
};
const numbers = (list) => list.map((i) => i.number).sort();
const inTree = (tree, n) => tree.groups.some((g) => JSON.stringify(g).includes(`"number":${n}`));

for (const [what, frame] of [
  ["a `ticket` `opened` frame", { type: "ticket", data: { repo: REPO, number: 1034, action: "opened", label: null, prNumber: null, issue: row(1034) } }],
  ["the engine's `created` frame", { type: "created", data: { repo: REPO, number: 1034, url: `https://github.com/${REPO}/issues/1034`, issue: row(1034) } }],
]) {
  test(`${what} carrying the new issue's row puts it on the board and in the tree with no further GET /issues`, async () => {
    const fleet = fakeFleet();
    ev.refetchRegistry.clear();
    const { state, h } = await mountBoard(fleet);
    assert.deepEqual(numbers(state.issues.issues), [1033], "GET /issues does not list #1034 yet");
    assert.equal(inTree(state.tree, 1034), false);
    const before = { ...fleet.reads };

    fleet.frame({ ...frame, subscription: REPO });
    assert.deepEqual(numbers(state.issues.issues), [1033, 1034], "inserted from the frame, at once");
    assert.equal(state.issues.issues[0].number, 1034, "a new row goes first");
    assert.ok(inTree(state.tree, 1034), "and in the Tickets tree, regrouped");

    await tick(ev.REFETCH_DEBOUNCE_MS + 50);
    assert.deepEqual(fleet.reads, before, "no GET /issues and no tree read was made for it");
    assert.deepEqual(numbers(state.issues.issues), [1033, 1034], "nor did a stale read take it away");
    h.unmount();
    ev.refetchRegistry.clear();
  });
}

test("a frame with no row keeps today's refetch: a `ticket` one debounced, a `flow` or `created` one at once, `incident` as before", async () => {
  const fleet = fakeFleet();
  ev.refetchRegistry.clear();
  const { h } = await mountBoard(fleet);
  const reads = () => fleet.reads.issues;
  const start = reads();

  fleet.frame({ type: "ticket", data: { repo: REPO, number: 1033, action: "commented", issue: null }, subscription: REPO });
  fleet.frame({ type: "ticket", data: { repo: REPO, number: 9, action: "pr", prNumber: 9 }, subscription: REPO });
  assert.equal(reads(), start, "a rowless ticket frame waits for the debounce");
  await tick(ev.REFETCH_DEBOUNCE_MS + 50);
  assert.equal(reads(), start + 1, "then one refetch for the burst");

  fleet.frame({ type: "incident", data: { repo: REPO, number: 1033, state: "open" }, subscription: REPO });
  await tick(ev.REFETCH_DEBOUNCE_MS + 50);
  assert.equal(reads(), start + 2, "an incident refetches as before");

  fleet.frame({ type: "created", data: { repo: REPO, number: 1035, issue: null }, subscription: REPO });
  await tick();
  assert.equal(reads(), start + 3, "a created frame whose row the engine could not read refetches");
  fleet.frame({ type: "flow", data: { repo: REPO, number: 1033, state: "started", issue: null }, subscription: REPO });
  await tick();
  assert.equal(reads(), start + 4, "and a rowless flow frame, as it always has");

  fleet.frame({ type: "flow", data: { repo: REPO, number: 1033, state: "started", issue: row(1033, "moved") }, subscription: REPO });
  await tick(ev.REFETCH_DEBOUNCE_MS + 50);
  assert.equal(reads(), start + 4, "a flow frame with its row refetches nothing");
  h.unmount();
  ev.refetchRegistry.clear();
});

test("carriedRow: a row object with a number and a repo, else null", () => {
  assert.equal(ev.carriedRow({ issue: row(5) }).number, 5);
  for (const d of [null, {}, { issue: null }, { issue: {} }, { issue: { number: 5 } }, { issue: "x" }]) assert.equal(ev.carriedRow(d), null, JSON.stringify(d));
  assert.deepEqual(ev.BOARD_ROW_TYPES, ["flow", "ticket", "created"]);
});
