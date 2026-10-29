// The explorer's Saved Chats refetch on the event stream's catch-up (#1055):
// an "engine unreachable" from a read made while the engine was down clears
// on the next connect, through the real useSavedChats registration.
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { useSavedChats } = require("../dist/index.cjs");

// The hook runtime of test/engine-events-renderer.test.js, with effects and
// callbacks keyed on their deps, as in React.
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
    React.useCallback = (f, deps) => {
      const c = (cells[idx] ||= { f, deps }); idx++;
      if (!(deps && deps.length === c.deps.length && deps.every((d, i) => Object.is(d, c.deps[i])))) { c.f = f; c.deps = deps; }
      return c.f;
    };
    React.useEffect = (f, deps) => {
      const c = (cells[idx] ||= { deps: null, cleanup: null }); idx++;
      if (c.deps && deps && deps.length === c.deps.length && deps.every((d, i) => Object.is(d, c.deps[i]))) return;
      c.deps = deps; pendingEffects.push(c, f);
    };
    let out;
    try { out = fn(); } finally { Object.assign(React, real); }
    for (let i = 0; i < pendingEffects.length; i += 2) {
      const c = pendingEffects[i];
      if (c.cleanup) c.cleanup();
      const r = pendingEffects[i + 1]();
      c.cleanup = typeof r === "function" ? r : null;
      if (c.cleanup) cleanups.push(c);
    }
    return out;
  };
  const unmount = () => { for (const c of cleanups) if (c.cleanup) { c.cleanup(); c.cleanup = null; } cleanups = []; };
  return { run, unmount };
};
const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));
const DOWN = "engine unreachable at http://engine.test/saved-chats: fetch failed";

// window.fleet with the stream push, a savedChats() that fails until `up`, and savedChat().
const fakeFleet = () => {
  const streams = new Set();
  const f = { up: false, list: 0, one: 0, changed: null };
  global.window = { fleet: {
    onEngineEvent: () => () => {},
    onEngineStream: (cb) => { streams.add(cb); return () => streams.delete(cb); },
    engineSnapshotted: async () => {},
    engineStreamState: async () => ({ repo: "*", state: "live", since: 1, lastFrameAt: 2 }),
    onSavedChatsChanged: (cb) => { f.changed = cb; return () => { f.changed = null; }; },
    savedChats: async () => { f.list++; return f.up ? { chats: [{ id: "c1" }] } : { error: DOWN, chats: [] }; },
    savedChat: async (id) => { f.one++; return f.up ? { id, summary: {} } : { error: DOWN }; },
  } };
  f.stream = (p) => { for (const cb of [...streams]) cb(p); };
  return f;
};

test("saved chats: one read with no stream push; a catch-up replaces the error with the list and reloads an errored open chat; a loaded one stays", async () => {
  const f = fakeFleet();
  const h = harness();
  let out = h.run(() => useSavedChats("saved-chat", "c1"));
  await tick(5);
  out = h.run(() => useSavedChats("saved-chat", "c1"));
  assert.equal(f.list, 1, "no stream push: savedChats() exactly once");
  assert.equal(out.savedChats.error, DOWN);
  assert.equal(out.savedChat.error, DOWN);

  f.up = true;
  f.stream({ repo: "*", state: "catching-up", since: 5, lastFrameAt: null });
  await tick(20);
  out = h.run(() => useSavedChats("saved-chat", "c1"));
  assert.equal(f.list, 2, "the catch-up reads the list again");
  assert.deepEqual(out.savedChats, { chats: [{ id: "c1" }] });
  assert.equal(f.one, 2, "the errored open chat is read again");
  assert.equal(out.savedChat.id, "c1");

  f.stream({ repo: "*", state: "catching-up", since: 6, lastFrameAt: null });
  await tick(20);
  out = h.run(() => useSavedChats("saved-chat", "c1"));
  assert.equal(f.list, 3);
  assert.equal(f.one, 2, "a loaded chat is not re-read");

  f.changed();
  await tick(5);
  assert.equal(f.list, 4, "savedChats:changed still refetches");
  h.unmount();
});
