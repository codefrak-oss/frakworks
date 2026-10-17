// The renderer's side of the event stream (src/renderer/engine-events.js, #976):
// the frame filter, the two hooks with React's hooks hand-stubbed (as
// test/chat-about.test.js stubs them), the debounce and the catch-up's order,
// the board row applied in place, the stale line and the status bar's badge.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const ev = require("../dist/engine-events.cjs");
const { StatusBar } = require("../dist/status-bar.cjs");

// A minimal hook runtime: `render(fn)` runs a component function with
// useRef/useState/useEffect stubbed; effects run after the render and their
// cleanups on the next render or on `unmount()`.
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

// window.fleet as the hooks see it: subscribers to the two pushes, and the invokes recorded.
const fakeFleet = () => {
  const frames = new Set(), streams = new Set();
  const calls = [];
  global.window = { fleet: {
    onEngineEvent: (cb) => { frames.add(cb); return () => frames.delete(cb); },
    onEngineStream: (cb) => { streams.add(cb); return () => streams.delete(cb); },
    engineSnapshotted: async (repo) => { calls.push(`snapshotted ${repo}`); },
    engineStreamState: async () => ({ repo: "*", state: "live", since: 1, lastFrameAt: 2 }),
  } };
  return { frame: (f) => { for (const cb of [...frames]) cb(f); }, stream: (p) => { for (const cb of [...streams]) cb(p); },
           calls, get listeners() { return frames.size; } };
};
const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

test("matchesFrame: by type, number, repo (case-insensitive, `*` passes), instance (instance, root or intakeId), issueUrl and where", () => {
  const f = (type, data) => ({ type, data, subscription: "*" });
  assert.equal(ev.matchesFrame(f("flow", { number: 1 }), ["flow"]), true);
  assert.equal(ev.matchesFrame(f("ticket", { number: 1 }), ["flow"]), false);
  assert.equal(ev.matchesFrame(f("flow", { number: 1 }), [], {}), true, "no types: every frame");
  assert.equal(ev.matchesFrame(f("flow", { number: 2 }), ["flow"], { number: 1 }), false);
  assert.equal(ev.matchesFrame(f("flow", { number: 1, repo: "Acme/Widgets" }), ["flow"], { repo: "acme/widgets", number: 1 }), true);
  assert.equal(ev.matchesFrame(f("setting", { repo: "*", name: "accounts" }), ["setting"], { repo: "acme/widgets" }), true);
  assert.equal(ev.matchesFrame(f("flow", { repo: "a/b" }), ["flow"], { repo: "a/c" }), false);
  assert.equal(ev.matchesFrame(f("flow", { instance: "x", root: "r" }), ["flow"], { instance: "r" }), true);
  assert.equal(ev.matchesFrame(f("intake", { intakeId: "i" }), ["intake"], { instance: "i" }), true);
  assert.equal(ev.matchesFrame(f("flow", { instance: "x", root: "r" }), ["flow"], { instance: "q" }), false);
  assert.equal(ev.matchesFrame(f("incident", { repo: "a/b", number: 7 }), ["incident"], { issueUrl: "https://github.com/a/b/issues/7" }), true);
  assert.equal(ev.matchesFrame(f("incident", { repo: "a/b", number: 8 }), ["incident"], { issueUrl: "https://github.com/a/b/issues/7" }), false);
  assert.equal(ev.matchesFrame(f("setting", { name: "doctor" }), ["setting"], { where: (d) => d.name === "accounts" }), false);
});

test("useEngineEvents hands the handler the matching frames only, and unsubscribes on unmount", () => {
  const fleet = fakeFleet();
  const h = harness();
  const got = [];
  h.run(() => ev.useEngineEvents(["flow", "intake"], (f) => got.push(f.type), { number: 5 }));
  assert.equal(fleet.listeners, 1);
  fleet.frame({ type: "flow", data: { number: 5 } });
  fleet.frame({ type: "flow", data: { number: 6 } });
  fleet.frame({ type: "ticket", data: { number: 5 } });
  fleet.frame({ type: "intake", data: { number: 5 } });
  assert.deepEqual(got, ["flow", "intake"]);
  h.unmount();
  assert.equal(fleet.listeners, 0);
  fleet.frame({ type: "flow", data: { number: 5 } });
  assert.deepEqual(got, ["flow", "intake"], "an unmounted view costs nothing");
});

test("useRefetchOn: a burst of frames is one call after the debounce, two hooks on one function coalesce, and unmounting cancels", async () => {
  const fleet = fakeFleet();
  ev.refetchRegistry.clear();
  const h = harness();
  let calls = 0;
  const refetch = () => { calls++; };
  h.run(() => { ev.useRefetchOn(["ticket"], refetch); ev.useRefetchOn(["incident"], refetch); });
  for (let i = 0; i < 5; i++) fleet.frame({ type: "ticket", data: { number: i } });
  fleet.frame({ type: "incident", data: { number: 1 } });
  assert.equal(calls, 0, "debounced");
  await tick(ev.REFETCH_DEBOUNCE_MS + 30);
  assert.ok(calls >= 1 && calls <= 2, `one call per hook at most, got ${calls}`);
  const before = calls;
  fleet.frame({ type: "ticket", data: { number: 9 } });
  h.unmount();
  await tick(ev.REFETCH_DEBOUNCE_MS + 30);
  assert.equal(calls, before, "a pending refetch of an unmounted view never runs");
});

test("useRefetchOn: the tree's subscription runs on an `incident` frame alone (no board row, no `flow` frame follows an incident's open)", async () => {
  const fleet = fakeFleet();
  ev.refetchRegistry.clear();
  const h = harness();
  let treeReads = 0;
  const refreshTree = () => { treeReads++; };
  h.run(() => { ev.useRefetchOn(["ticket", "incident"], refreshTree); });
  fleet.frame({ type: "incident", data: { repo: "a/b", number: 4, state: "open" } });
  await tick(ev.REFETCH_DEBOUNCE_MS + 30);
  assert.equal(treeReads, 1, "the tree re-reads on the incident frame");
  fleet.frame({ type: "setting", data: { name: "doctor" } });
  await tick(ev.REFETCH_DEBOUNCE_MS + 30);
  assert.equal(treeReads, 1, "a frame of another type does not");
  h.unmount();
  assert.equal(ev.refetchRegistry.size, 0);
  ev.refetchRegistry.clear();
});

test("the catch-up: every mounted refetch runs once on `catching-up`, and engineSnapshotted(repo) is called only after the last has returned", async () => {
  const fleet = fakeFleet();
  ev.refetchRegistry.clear();
  const h = harness();
  const order = [];
  let release;
  const slow = () => new Promise((r) => { release = () => { order.push("slow done"); r(); }; });
  const fast = async () => { order.push("fast done"); };
  const other = async () => { order.push("other repo"); };
  h.run(() => { ev.useRefetchOn(["ticket"], slow); ev.useRefetchOn([], fast); ev.useRefetchOn(["flow"], other, { repo: "x/y" }); });
  fleet.stream({ repo: "a/b", state: "catching-up", since: 1, lastFrameAt: null });
  await tick(5);
  assert.deepEqual(order, ["fast done"]);
  assert.deepEqual(fleet.calls, [], "not yet: one read is still out");
  release();
  await tick(5);
  assert.deepEqual(order, ["fast done", "slow done"]);
  assert.deepEqual(fleet.calls, ["snapshotted a/b"]);
  // A fleet-wide connection snapshots every view, the other repo's too; `live` and `down` run nothing.
  fleet.stream({ repo: "*", state: "catching-up", since: 2, lastFrameAt: null });
  await tick(5); release(); await tick(5);
  assert.ok(order.includes("other repo"));
  assert.deepEqual(fleet.calls, ["snapshotted a/b", "snapshotted *"]);
  fleet.stream({ repo: "a/b", state: "live", since: 3, lastFrameAt: 3 });
  fleet.stream({ repo: "a/b", state: "down", since: 4, lastFrameAt: 3 });
  await tick(5);
  assert.deepEqual(fleet.calls, ["snapshotted a/b", "snapshotted *"]);
  h.unmount();
  ev.refetchRegistry.clear();
});

test("coalesce: a refetch called while its read is in flight runs exactly one more read after it settles, and the catch-up answers engineSnapshotted only after that re-read (#976 review)", async () => {
  const order = [];
  const releases = [];
  let reads = 0;
  const read = (tag = "") => new Promise((r) => { reads++; releases.push(() => { order.push(`read ${reads}${tag} done`); r(); }); });
  const refetch = ev.coalesce(read);
  // Three calls while the first read is out: one re-read after it, not three and not none.
  const first = refetch();
  const second = refetch(); const third = refetch();
  await tick(2);
  assert.equal(reads, 1, "one in flight");
  releases.shift()();
  await tick(2);
  assert.equal(reads, 2, "the burst is one more read, run after the first settled");
  releases.shift()();
  await tick(2);
  assert.equal(reads, 2, "and no third");
  assert.deepEqual(order, ["read 1 done", "read 2 done"]);
  // Every caller's promise settled with the re-read, not before it.
  await Promise.all([first, second, third]);
  // Idle again: a new call is a new read; the last call's arguments win for the re-read.
  const seen = [];
  const withArgs = ev.coalesce(async (x) => { seen.push(x); await tick(2); });
  withArgs("a"); withArgs("b"); withArgs("c");
  await tick(10);
  assert.deepEqual(seen, ["a", "c"]);
  // A rejecting read does not end the chain or reject the caller.
  let n = 0;
  const failing = ev.coalesce(async () => { n++; if (n === 1) throw new Error("boom"); });
  await failing(); await failing();
  assert.equal(n, 2);
  // The catch-up: a coalesced refetch called by the catch-up while a frame's read is out
  // waits for the re-read, so `engineSnapshotted` comes after it.
  const calls = [];
  const slowReads = [];
  const view = ev.coalesce(() => new Promise((r) => { slowReads.push(() => { calls.push(`read ${slowReads.length} done`); r(); }); }));
  view();                                          // a frame's refetch, still out
  const entries = new Map([[view, { repo: null }]]);
  const done = ev.runCatchUp({ repo: "a/b", state: "catching-up", since: 1 }, entries, async (repo) => { calls.push(`snapshotted ${repo}`); });
  await tick(2);
  assert.equal(slowReads.length, 1, "the catch-up's call joined the read in flight");
  slowReads[0]();
  await tick(2);
  assert.equal(slowReads.length, 2, "and asked for the re-read");
  assert.deepEqual(calls, ["read 1 done"], "not answered yet: the snapshot is the re-read's");
  slowReads[1]();
  await done;
  assert.deepEqual(calls, ["read 1 done", "read 2 done", "snapshotted a/b"]);
});

test("applyBoardRow replaces the row with that number in that repo, inserts a new one, and never drops a row", () => {
  const rows = [{ repo: "a/b", number: 1, title: "one" }, { repo: "a/c", number: 1, title: "other repo's one" }];
  const replaced = ev.applyBoardRow(rows, { repo: "A/B", number: 1, title: "one, moved" });
  assert.deepEqual(replaced.map((r) => r.title), ["one, moved", "other repo's one"]);
  const inserted = ev.applyBoardRow(rows, { repo: "a/b", number: 2, title: "two" });
  assert.equal(inserted.length, 3);
  assert.equal(inserted[0].number, 2);
  assert.deepEqual(ev.applyBoardRow(rows, null), rows, "a null row changes nothing: the view refetches instead");
  assert.deepEqual(rows.map((r) => r.title), ["one", "other repo's one"], "pure");
  // The selection guards the apply: a detail's second connection carries another
  // repo's rows, which are not the selected repo's board (nor its tree).
  const foreign = { repo: "a/c", number: 9, title: "the other repo's nine" };
  assert.equal(ev.applyBoardRow(rows, foreign, "A/B"), rows, "another repo's row leaves the selected repo's rows unchanged, by identity");
  assert.equal(ev.applyBoardRow(rows, foreign, null).length, 3, "in All repos it is inserted");
  assert.equal(ev.applyBoardRow(rows, foreign, "*").length, 3, "`*` is All repos too");
  assert.equal(ev.applyBoardRow(rows, { repo: "a/b", number: 1, title: "moved" }, "a/b")[0].title, "moved");
});

test("the catch-up runs for a connection already `catching-up` when the first view listens, with no push heard (the launch), and once per connect", async () => {
  const fleet = fakeFleet();
  ev.refetchRegistry.clear();
  // Main connected before the renderer mounted: the `catching-up` push is gone;
  // the state read says it, for the selected connection and a detail's.
  window.fleet.engineStreamState = async () => ({ repo: "a/b", state: "catching-up", since: 10, lastFrameAt: null,
    connections: { "a/b": { repo: "a/b", state: "catching-up", since: 10, lastFrameAt: null },
                   "x/y": { repo: "x/y", state: "catching-up", since: 11, lastFrameAt: null },
                   "p/q": { repo: "p/q", state: "live", since: 12, lastFrameAt: 12 } } });
  const h = harness();
  const ran = [];
  h.run(() => { ev.useRefetchOn(["ticket"], async () => { ran.push("board"); }); ev.useRefetchOn(["flow"], async () => { ran.push("detail"); }, { repo: "x/y" }); });
  await tick(10);
  // The board's hook has no repo filter: both connections snapshot it; the detail's view is its connection's only.
  assert.deepEqual(ran.sort(), ["board", "board", "detail"]);
  assert.deepEqual(fleet.calls.sort(), ["snapshotted a/b", "snapshotted x/y"], "each catching-up connection is answered; the live one is not");
  // The same connect's push, heard after all: not a second catch-up.
  const n = ran.length;
  fleet.stream({ repo: "a/b", state: "catching-up", since: 10, lastFrameAt: null });
  await tick(10);
  assert.equal(ran.length, n);
  assert.equal(fleet.calls.length, 2);
  // A new connect (a new `since`) is.
  fleet.stream({ repo: "a/b", state: "catching-up", since: 20, lastFrameAt: null });
  await tick(10);
  assert.equal(fleet.calls.length, 3);
  h.unmount();
  ev.refetchRegistry.clear();
});

test("StaleLine draws one line while the stream is down, with the time of the last frame, and nothing otherwise", () => {
  const at = new Date(2026, 9, 5, 14, 2).getTime();
  const down = renderToStaticMarkup(React.createElement(ev.StaleLine, { state: { repo: "*", state: "down", since: at + 60000, lastFrameAt: at } }));
  assert.match(down, /class="staleLine"/);
  assert.ok(down.includes("stale since 14:02, reconnecting"), down);
  assert.equal(renderToStaticMarkup(React.createElement(ev.StaleLine, { state: { state: "live" } })), "");
  assert.equal(renderToStaticMarkup(React.createElement(ev.StaleLine, { state: { state: "catching-up" } })), "");
  assert.equal(ev.staleText({ state: "down", since: at, lastFrameAt: null }), "stale since 14:02, reconnecting");
});

test("the status bar shows the stream beside the engine commit: ● live, ● catching up, ○ down · age", () => {
  const now = 1_000_000;
  assert.deepEqual(ev.streamText({ state: "live" }, now), { text: "stream ● live", level: "ok" });
  assert.deepEqual(ev.streamText({ state: "catching-up" }, now), { text: "stream ● catching up", level: "muted" });
  assert.deepEqual(ev.streamText({ state: "down", since: now - 5000, lastFrameAt: now - 125000 }, now), { text: "stream ○ down · 2m", level: "bad" });
  assert.equal(ev.streamText(null).level, "muted");
  const html = renderToStaticMarkup(React.createElement(StatusBar, { version: null, engine: null, stream: { state: "down", since: now, lastFrameAt: now - 3600000 }, now }));
  assert.match(html, /class="sbStream sb-bad"[^>]*>stream ○ down · 1h</);
  assert.match(renderToStaticMarkup(React.createElement(StatusBar, { version: null, engine: null, stream: { state: "live" } })), /sbStream sb-ok[^>]*>stream ● live</);
});
