// A need the engine drops leaves the Needs you list with no ⟳ (#1172): the
// list is re-read on the `needs` frame the engine publishes for the drop, as
// index.jsx wires it (useRefetchOn(["needs", "ticket"]) over a coalesced
// window.fleet.needsYou()). The two drops #1172 found unannounced are here: a
// flow that ended with its answer task (the `question` `removed` FlowEvents
// now publishes after the `ended` frame) and a propagated incident's resolve.
// React's hooks are hand-stubbed as in test/board-close-frames.test.js.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const ev = require("../dist/engine-events.cjs");
const { toRows, fetchHitlpTasks } = require("../src/needs-you");
const { NeedsYou } = require("../dist/needs-you.cjs");
const { HitlpRequests } = require("../dist/hitlp-requests.cjs");

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
    try { fn(); } finally { Object.assign(React, real); }
    for (const f of pendingEffects) { const c = f(); if (typeof c === "function") cleanups.push(c); }
  };
  const unmount = () => { for (const c of cleanups) c(); cleanups = []; };
  return { run, unmount };
};

const REPO = "codefrak-oss/frakworks";
const NOW = Math.floor(Date.parse("2026-10-06T00:00:00Z") / 1000);
const need = (number, kind, actionId) => ({ repo: REPO, number, title: `Issue ${number}`, kind,
  wanted: kind === "incident" ? "retry or cancel: run-smith" : "answer the question on the issue",
  since: "2026-10-05T12:00:00Z", actionId, question: null });
const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

// The engine's GET /needs as the main process hands it over: `served` is what the engine lists now.
const fakeFleet = (served, tasks = []) => {
  const frames = new Set();
  const reads = { needs: 0, hitlp: 0 };
  global.window = { fleet: {
    onEngineEvent: (cb) => { frames.add(cb); return () => frames.delete(cb); },
    onEngineStream: () => () => {},
    engineSnapshotted: async () => {},
    engineStreamState: async () => null,
    needsYou: async () => { reads.needs++; return { error: null, repo: REPO, rows: toRows(served.slice(), NOW) }; },
    hitlpTasks: async () => { reads.hitlp++; return fetchHitlpTasks({ surface: async () => ({ tasks: tasks.slice() }), now: NOW }); },
  } };
  return { frame: (f) => { for (const cb of [...frames]) cb(f); }, reads };
};

// The App's needs state and the tab's count as index.jsx keeps them.
const mountNeeds = async () => {
  const state = { needs: null };
  const refresh = ev.coalesce(async () => { state.needs = await window.fleet.needsYou(); });
  await refresh();
  const h = harness();
  h.run(() => ev.useRefetchOn(["needs", "ticket"], refresh));
  const view = () => renderToStaticMarkup(React.createElement(NeedsYou, { result: state.needs, onRefresh: () => {}, refreshing: false }));
  const count = () => ((state.needs && state.needs.rows) || []).length;
  return { state, h, view, count };
};
const removed = (kind, number, actionId) => ({ type: "needs", subscription: REPO,
  data: { repo: REPO, kind, state: "removed", number, actionId, at: "2026-10-06T00:00:00Z" } });

for (const [what, dropped, frame] of [
  ["a flow that ended at its answer task, with no recorded question", need(971, "question", "inst-971"), removed("question", 971, "inst-971")],
  ["a propagated incident resolved after its root cause", need(971, "incident", "inc-propagated"), removed("incident", 971, "inc-propagated")],
]) {
  test(`${what}: the dropped need leaves the list and the count on the engine's frame`, async () => {
    ev.refetchRegistry.clear();
    const served = [dropped, need(1001, "question", "inst-1001")];
    const fleet = fakeFleet(served);
    const { h, view, count } = await mountNeeds();
    assert.match(view(), /#971/);
    assert.equal(count(), 2);

    served.splice(0, 1);                  // the engine drops the need ...
    fleet.frame(frame);                   // ... and publishes its frame
    await tick(ev.REFETCH_DEBOUNCE_MS + 50);
    assert.equal(fleet.reads.needs, 2, "one re-read on the frame, no ⟳");
    assert.doesNotMatch(view(), /#971/, "gone from the rendered list");
    assert.match(view(), /#1001/);
    assert.equal(count(), 1, "and from the tab's count");
    h.unmount();
    ev.refetchRegistry.clear();
  });
}

// The HITLP server's tasks (#2487, #100064): main turns each `event: task` on
// the surface API's GET /events into a `needs` frame, which refetches the one
// shared task list (window.fleet.hitlpTasks) that HITLP Requests, the explorer
// badge and the urgent popup read; a task decided on a ticket, on the server
// or here leaves HITLP Requests on that frame, and the Needs rows never had it.
test("a HITLP task decided elsewhere leaves HITLP Requests on the frame main pushes for the surface's task event (#2487)", async () => {
  ev.refetchRegistry.clear();
  const tasks = [{ id: "task-9", primitive: "approve", status: "input_required", action: "Deploy engine 1.43", createdAt: "2026-10-05T12:00:00Z" }];
  const fleet = fakeFleet([need(1001, "question", "inst-1001")], tasks);
  const { h, count, view: needsView } = await mountNeeds();
  const shared = { tasks: null };
  const refreshHitlp = ev.coalesce(async () => { shared.tasks = await window.fleet.hitlpTasks(); });
  await refreshHitlp();
  const hh = harness();
  hh.run(() => ev.useRefetchOn(["needs"], refreshHitlp));
  const view = () => renderToStaticMarkup(React.createElement(HitlpRequests, { result: shared.tasks, now: NOW }));
  assert.match(view(), /Deploy engine 1\.43/);
  assert.doesNotMatch(needsView(), /Deploy engine 1\.43/, "Needs you never draws a HITLP task");
  assert.equal(count(), 1);
  tasks[0] = { ...tasks[0], status: "completed", result: { outcome: "approved", decidedBy: { type: "human", id: "lundmatt1" }, decidedAt: "2026-10-05T13:00:00Z", channel: "desktop" } };
  fleet.frame({ type: "needs", data: { source: "hitlp" } });
  await tick(ev.REFETCH_DEBOUNCE_MS + 50);
  assert.equal(fleet.reads.hitlp, 2, "one re-read of the shared list on the frame");
  assert.doesNotMatch(view(), /Deploy engine 1\.43/);
  assert.equal(shared.tasks.closed.length, 1, "and it is Closed's now");
  h.unmount(); hh.unmount();
  ev.refetchRegistry.clear();
});
