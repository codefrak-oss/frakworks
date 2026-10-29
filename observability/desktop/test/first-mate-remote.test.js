// First mate in a session pod (#2521): FirstMateManager with the engine's
// First mate hosting `kubernetes` runs a conversation through
// src/first-mate-remote.js's RemoteSession over the session API, against a
// recording fetch and a fake event stream, never a local `claude` child.
"use strict";
// Config > frakworks > Engine URL, as main sets it at launch (src/engine-url-setting.js).
const TEST_ENGINE_URL = "http://engine.test";
require("../src/engine-data").setEngineUrl(TEST_ENGINE_URL);
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { EventEmitter } = require("node:events");
const { PassThrough } = require("node:stream");

const {
  FirstMateManager, REMOTE_CANCEL_ROW, REMOTE_EXPIRED_ROW,
} = require("../src/first-mate");
const { RemoteSession, splitContent } = require("../src/first-mate-remote");
const {
  fetchFirstMateHosting, startFirstMateSession, sendFirstMateMessage, endFirstMateSession,
} = require("../src/engine-data");
require("../src/engine-tiers").setTiers(require("./seeded-tiers").SEEDED_TIERS);

const REPO = "acme/widgets";
const BEARER = "Bearer frk_firstmate";

/** A fake engine: the hosting setting, the session API, and what was asked of it. */
function fakeEngine({ hosting = "kubernetes", messageStatus = 200 } = {}) {
  const eng = { hosting, messageStatus, calls: [], sessions: 0 };
  eng.fetch = async (url, init = {}) => {
    const u = new URL(url);
    const method = init.method || "GET";
    const body = init.body ? JSON.parse(init.body) : undefined;
    eng.calls.push({ method, path: u.pathname, body, auth: init.headers && init.headers.authorization });
    const answer = (status, v) => ({ ok: status >= 200 && status < 300, status, text: async () => (v === undefined ? "" : JSON.stringify(v)) });
    if (u.pathname === "/settings/first-mate-hosting") {
      return eng.hosting instanceof Error ? Promise.reject(eng.hosting) : answer(200, { hosting: eng.hosting });
    }
    if (u.pathname === "/first-mate/sessions" && method === "POST") {
      eng.sessions += 1;
      return answer(200, { id: `s${eng.sessions}`, repo: body.repo, state: "open", tier: body.tier, firstLine: eng.sessions === 1 ? "dropped: local-db" : null });
    }
    if (/\/messages$/.test(u.pathname)) return answer(eng.messageStatus, eng.messageStatus === 200 ? { seq: 1 } : { message: "gone" });
    if (method === "DELETE") return answer(204);
    return answer(404, { message: "no" });
  };
  const opts = { env: {}, fetch: eng.fetch };
  eng.hostingFn = () => fetchFirstMateHosting(opts);
  eng.api = {
    start: (repo, ask, tier, bearer) => startFirstMateSession(repo, ask, tier, bearer, opts),
    send: (id, text, images, clientId, bearer) => sendFirstMateMessage(id, text, images, clientId, bearer, opts),
    end: (id, bearer) => endFirstMateSession(id, bearer, opts),
  };
  eng.of = (p, method) => eng.calls.filter((c) => c.path === p && (!method || c.method === method));
  return eng;
}

function fakeEvents() {
  const ev = new EventEmitter();
  ev.subscribed = [];
  ev.subscribe = (r) => ev.subscribed.push(r);
  ev.unsubscribe = (r) => { ev.subscribed.splice(ev.subscribed.indexOf(r), 1); };
  ev.frame = (sessionId, line) => ev.emit("frame", { type: "first-mate", data: { repo: REPO, sessionId, seq: 1, line } });
  return ev;
}

function manager(eng, events, extra = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "first-mate-remote-test-"));
  const spawned = [];
  const m = new FirstMateManager({
    dir, cwd: dir, mcpConfigPath: path.join(dir, "mcp.json"),
    // A local child that never answers: a test lets go of its turn with stop().
    spawnFn: (...a) => {
      spawned.push(a);
      const proc = new EventEmitter();
      proc.stdout = new PassThrough();
      proc.stdin = new PassThrough();
      proc.kill = () => {};
      return proc;
    },
    repoFn: () => REPO,
    hostingFn: eng.hostingFn,
    bearer: async () => BEARER,
    sessionApi: eng.api,
    engineEvents: events,
    ...extra,
  }).init();
  return { m, spawned };
}

const until = async (pred) => {
  for (let i = 0; i < 200 && !pred(); i += 1) await new Promise((r) => setImmediate(r));
  assert.ok(pred(), "condition never held");
};

// Answers the turn in flight on session `id`: the echo, one assistant line and its result.
async function reply(eng, events, id, text, n) {
  await until(() => eng.of(`/first-mate/sessions/${id}/messages`).length >= n);
  events.frame(id, { type: "user", text: "echo" });
  events.frame(id, { type: "assistant", session_id: "claude-1", message: { content: [{ type: "text", text }] } });
  events.frame(id, { type: "result", session_id: "claude-1", user_message_uuid: "pod-side", result: text });
}

test("with hosting kubernetes a new conversation POSTs /first-mate/sessions on the chat's repo, never spawns, and each send POSTs /messages", async () => {
  const eng = fakeEngine();
  const events = fakeEvents();
  const { m, spawned } = manager(eng, events);
  const first = m.send("hello there");
  await reply(eng, events, "s1", "Sounds like a real bug.\nFile: 4 | the retry button double-submits", 1);
  const r = await first;
  assert.equal(r.ok, true);
  assert.deepEqual(spawned, []);
  const [start] = eng.of("/first-mate/sessions", "POST");
  assert.equal(start.body.repo, REPO);
  assert.equal(start.body.tier, 8);
  assert.equal(start.auth, BEARER);
  const [msg] = eng.of("/first-mate/sessions/s1/messages");
  assert.match(msg.body.text, /hello there/);
  assert.match(msg.body.clientId, /^[0-9a-f-]{36}$/);
  assert.equal(msg.auth, BEARER);
  assert.deepEqual(events.subscribed, [REPO]);
  const snap = m.snapshot();
  assert.equal(snap.runtime, "kubernetes");
  assert.deepEqual(snap.history.map((h) => [h.role, h.text]), [
    ["user", "hello there"],
    ["system", "dropped: local-db"],
    ["assistant", "Sounds like a real bug.\nthe retry button double-submits"],
  ]);
  assert.equal(snap.file.ask, "the retry button double-submits");
  assert.equal(m.session.remote_session_id, "s1");

  const second = m.send("and again");
  await reply(eng, events, "s1", "Second answer.", 2);
  assert.equal((await second).text, "Second answer.");
  assert.equal(eng.of("/first-mate/sessions", "POST").length, 1);
  const msgs = eng.of("/first-mate/sessions/s1/messages");
  assert.equal(msgs.length, 2);
  assert.notEqual(msgs[0].body.clientId, msgs[1].body.clientId);
  assert.equal(eng.of("/settings/first-mate-hosting").length, 1);
  assert.equal(m.snapshot().notice, null);
  m.stop();
  assert.deepEqual(events.subscribed, []);
  assert.equal(eng.of("/first-mate/sessions/s1", "DELETE").length, 0);
});

test("another session's frames on the repo's stream are ignored", async () => {
  const eng = fakeEngine();
  const events = fakeEvents();
  const { m } = manager(eng, events);
  const turn = m.send("hi");
  await until(() => eng.of("/first-mate/sessions/s1/messages").length === 1);
  events.frame("phone-session", { type: "user", text: "phone" });
  events.frame("phone-session", { type: "assistant", message: { content: [{ type: "text", text: "not yours" }] } });
  events.frame("phone-session", { type: "result", result: "not yours" });
  assert.equal(m.rt.busy, true);
  await reply(eng, events, "s1", "yours", 1);
  assert.equal((await turn).text, "yours");
  assert.ok(!m.snapshot().history.some((h) => h.text === "not yours"));
  m.stop();
});

test("the hosting is read again at the next new conversation, and an open conversation keeps its runtime after it changes", async () => {
  const eng = fakeEngine();
  const events = fakeEvents();
  const { m, spawned } = manager(eng, events);
  const t1 = m.send("one");
  await reply(eng, events, "s1", "first", 1);
  await t1;
  eng.hosting = "local";
  const t2 = m.send("two");
  await reply(eng, events, "s1", "second", 2);
  await t2;
  assert.equal(eng.of("/settings/first-mate-hosting").length, 1);
  assert.equal(m.session.runtime, "kubernetes");
  assert.deepEqual(spawned, []);

  m.startNewConversation();
  assert.equal(m.session.conversations[0].runtime, "kubernetes");
  // Let go of, not ended (#2523): History and Back go on in it.
  assert.equal(m.session.conversations[0].remote_session_id, "s1");
  assert.equal(eng.of("/first-mate/sessions/s1", "DELETE").length, 0);
  const t3 = m.send("three");
  await until(() => eng.of("/settings/first-mate-hosting").length === 2);
  await until(() => spawned.length === 1);
  m.cancel();
  await t3;
  assert.equal(m.session.runtime, "local");
  assert.equal(eng.of("/first-mate/sessions", "POST").length, 1);
  // The archived kubernetes conversation comes back to its own runtime.
  m.popConversation();
  assert.equal(m.session.runtime, "kubernetes");
  m.stop();
});

test("a 404 on send is a system row, and the next turn starts a new session", async () => {
  const eng = fakeEngine();
  const events = fakeEvents();
  const { m } = manager(eng, events);
  const t1 = m.send("one");
  await reply(eng, events, "s1", "first", 1);
  await t1;
  eng.messageStatus = 404;
  const t2 = await m.send("two");
  assert.equal(t2.ok, false);
  assert.equal(m.snapshot().history.at(-1).role, "system");
  assert.equal(m.session.remote_session_id, null);
  eng.messageStatus = 200;
  const t3 = m.send("three");
  await reply(eng, events, "s2", "fresh", 1);
  assert.equal((await t3).text, "fresh");
  assert.equal(eng.of("/first-mate/sessions", "POST").length, 2);
  m.stop();
});

// The session API is driven with the sign-in bearer (#100103): a refusal
// drops nothing in main (no First mate token exists to drop) and the strip
// says to sign in again.
for (const status of [401, 403]) {
  test(`a ${status} from the session API is a strip notice saying to sign in again under Config > frakworks > Sign in, with nothing dropped`, async () => {
    const eng = fakeEngine();
    const events = fakeEvents();
    const { m } = manager(eng, events);
    eng.messageStatus = status;
    const r = await m.send("one");
    assert.equal(r.ok, false);
    assert.match(m.rt.notice, /sign in again under Config > frakworks > Sign in/);
    assert.match(m.rt.notice, new RegExp(`\\(${status}\\)`));
    assert.equal(m.session.remote_session_id, null);
    // the next turn still asks main for the bearer: nothing was dropped
    eng.messageStatus = 200;
    const next = m.send("two");
    await reply(eng, events, "s2", "ok", 1);
    assert.equal((await next).ok, true);
    assert.equal(eng.of("/first-mate/sessions/s2/messages")[0].auth, BEARER);
    m.stop();
  });
}

test("a bearer provider answering { error } (not signed in) is the strip notice and no session is started", async () => {
  const eng = fakeEngine();
  const { m } = manager(eng, fakeEvents(), { bearer: async () => ({ error: "not signed in to the engine: sign in under Config > frakworks > Sign in" }) });
  const r = await m.send("one");
  assert.equal(r.ok, false);
  assert.match(m.rt.notice, /Config > frakworks > Sign in/);
  assert.equal(eng.of("/first-mate/sessions", "POST").length, 0);
  m.stop();
});

test("an engine that cannot answer the hosting runs the conversation locally, with a notice", async () => {
  const eng = fakeEngine({ hosting: new Error("ECONNREFUSED") });
  const { m, spawned } = manager(eng, fakeEvents());
  const turn = m.send("one");
  await until(() => spawned.length === 1);
  assert.equal(m.session.runtime, "local");
  assert.match(m.snapshot().notice || "", /First mate hosting unavailable/);
  assert.equal(eng.of("/first-mate/sessions").length, 0);
  m.stop();
  await turn;
});

test("with hosting local /first-mate/sessions is never fetched", async () => {
  const eng = fakeEngine({ hosting: "local" });
  const { m, spawned } = manager(eng, fakeEvents());
  const turn = m.send("one");
  await until(() => spawned.length === 1);
  assert.equal(eng.calls.filter((c) => c.path.startsWith("/first-mate/sessions")).length, 0);
  m.stop();
  await turn;
});

test("RemoteSession: kill DELETEs the session; images and text are split from the content", async () => {
  const eng = fakeEngine();
  const events = fakeEvents();
  const s = new RemoteSession({ repo: REPO, tier: 8, api: eng.api, bearer: async () => BEARER, engineEvents: events });
  s.start();
  await s.ready;
  assert.equal(s.alive, true);
  s.kill();
  assert.equal(s.alive, false);
  await until(() => eng.of("/first-mate/sessions/s1", "DELETE").length === 1);
  assert.deepEqual(splitContent([{ type: "image", source: { type: "base64", media_type: "image/png", data: "AA==" } }, { type: "text", text: "look" }]),
    { text: "look", images: [{ media_type: "image/png", data: "AA==" }] });
});

test("resuming frames show resuming… until the first assistant frame; a pod-status line is the tooltip only (#2523)", async () => {
  const eng = fakeEngine();
  const events = fakeEvents();
  const { m } = manager(eng, events);
  const turn = m.send("hi");
  await until(() => eng.of("/first-mate/sessions/s1/messages").length === 1);
  events.frame("s1", { type: "resuming" });
  assert.equal(m.snapshot().remoteState, "resuming…");
  events.frame("s1", { type: "pod-status", text: "ContainerCreating" });
  events.frame("s1", { type: "resuming" });
  const snap = m.snapshot();
  assert.equal(snap.remoteState, "resuming…");
  assert.equal(snap.remote_detail, "ContainerCreating");
  assert.ok(!snap.history.some((h) => /ContainerCreating/.test(h.text)));
  assert.equal(snap.mode_line, undefined);
  await reply(eng, events, "s1", "back", 1);
  assert.equal(m.snapshot().remoteState, null);
  assert.equal((await turn).text, "back");
  m.stop();
});

test("pasted images go in the message body (#2523)", async () => {
  const eng = fakeEngine();
  const events = fakeEvents();
  const { m } = manager(eng, events);
  const png = { media_type: "image/png", data: Buffer.from([0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 0, 0, 0, 0]).toString("base64") };
  const turn = m.send("look", [png]);
  await reply(eng, events, "s1", "seen", 1);
  await turn;
  const [msg] = eng.of("/first-mate/sessions/s1/messages");
  assert.deepEqual(msg.body.images, [png]);
  assert.equal(m.snapshot().history[0].images, 1);
  m.stop();
});

test("a kubernetes conversation never calls the lineage or the account pick; its first ask is the session's ask (#2523)", async () => {
  const eng = fakeEngine();
  const events = fakeEvents();
  const asked = [];
  const { m } = manager(eng, events, {
    lineageFn: async (a) => { asked.push(["lineage", a]); return { ok: true, lineage: "{}" }; },
    pickAccountFn: async () => { asked.push(["account"]); return null; },
  });
  const turn = m.send("the retry button");
  await reply(eng, events, "s1", "ok", 1);
  await turn;
  assert.deepEqual(asked, []);
  assert.equal(eng.of("/first-mate/sessions", "POST")[0].body.ask, "the retry button");
  assert.ok(!fs.existsSync(path.join(m.dir, "first-mate", "lineage.json")));
  m.stop();
});

test("cancel marks the pod's turn interrupted, and that turn's later frames add no row (#2523)", async () => {
  const eng = fakeEngine();
  const events = fakeEvents();
  const { m } = manager(eng, events);
  const t1 = m.send("long one");
  await until(() => eng.of("/first-mate/sessions/s1/messages").length === 1);
  events.frame("s1", { type: "user", text: "echo" });
  m.cancel();
  assert.equal((await t1).interrupted, true);
  assert.equal(m.snapshot().history.at(-1).text, REMOTE_CANCEL_ROW);
  assert.equal(eng.of("/first-mate/sessions/s1", "DELETE").length, 0);
  const rows = m.snapshot().history.length;
  events.frame("s1", { type: "assistant", message: { content: [{ type: "text", text: "late" }] } });
  events.frame("s1", { type: "result", result: "late" });
  assert.equal(m.snapshot().history.length, rows);
  // The next turn goes on in the same session; the cancelled turn's lines are still dropped.
  const t2 = m.send("next");
  await until(() => eng.of("/first-mate/sessions/s1/messages").length === 2);
  events.frame("s1", { type: "result", result: "late" });
  assert.equal(m.rt.busy, true);
  await reply(eng, events, "s1", "fresh answer", 2);
  assert.equal((await t2).text, "fresh answer");
  assert.ok(!m.snapshot().history.some((h) => h.text === "late"));
  assert.equal(eng.of("/first-mate/sessions", "POST").length, 1);
  m.stop();
});

test("an expired session (404) is a system row saying so, and the next message starts a new one in the same strip (#2523)", async () => {
  const eng = fakeEngine();
  const events = fakeEvents();
  const { m } = manager(eng, events);
  const t1 = m.send("one");
  await reply(eng, events, "s1", "first", 1);
  await t1;
  eng.messageStatus = 404;
  await m.send("two");
  assert.ok(m.snapshot().history.some((h) => h.role === "system" && h.text === REMOTE_EXPIRED_ROW));
  m.stop();
});

test("a saved kubernetes chat records its runtime, and restoring it seeds a new conversation, never --resume (#2523)", async () => {
  const eng = fakeEngine();
  const events = fakeEvents();
  const { m } = manager(eng, events);
  const t1 = m.send("one");
  await reply(eng, events, "s1", "first", 1);
  await t1;
  const p = m.savePayload();
  assert.equal(p.body.runtime, "kubernetes");
  m.restoreSavedChat({ id: p.id, ...p.body }, { resumable: true });
  assert.equal(m.session.claude_session_id, null);
  assert.ok(m.session.seed);
  m.stop();
});
// #100109: a turn that used a tool. claude's stdout carries a `user` tool_result line,
// which the relay posts and RemoteSession forwards like the engine's echo.
test("#100109: a tool_result line is not an echo: the turn after a tool call still ends and the pane returns to idle", async () => {
  const eng = fakeEngine();
  const events = fakeEvents();
  const { m } = manager(eng, events);
  const first = m.send("look something up");
  await until(() => eng.of("/first-mate/sessions/s1/messages").length >= 1);
  events.frame("s1", { type: "user", text: "echo" });
  events.frame("s1", { type: "assistant", message: { content: [{ type: "tool_use", id: "t1", name: "Read", input: {} }] } });
  events.frame("s1", { type: "user", message: { content: [{ type: "tool_result", tool_use_id: "t1", content: "ok" }] } });
  events.frame("s1", { type: "assistant", message: { content: [{ type: "text", text: "Found it." }] } });
  events.frame("s1", { type: "result", result: "Found it." });
  assert.equal((await first).ok, true);
  assert.equal(m.snapshot().busy, false);

  m.send("thanks");
  await reply(eng, events, "s1", "You're welcome.", 2);
  await new Promise((r) => setTimeout(r, 50));
  const snap = m.snapshot();
  assert.equal(snap.busy, false, "the reply finished but the pane is still busy (Stop shown, input locked)");
  m.stop();
});
