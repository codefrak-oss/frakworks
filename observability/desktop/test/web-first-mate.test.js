// The web version's First mate (#100104): src/web/first-mate-web.js over the
// unchanged RemoteSession, TurnLedger and store helpers, with
// src/web/first-mate-web-store.js in a memory storage. A start reaches
// POST /first-mate/sessions with the page's bearer; only the session's own
// frames reach history; local hosting answers the one notice; conversations
// persist across a fresh bridge; Save for later, cancel, startNew and resume
// use the engine's routes.
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");

const { createWebFirstMate, chatTitle, OMITTED, LOCAL_HOSTING_NOTICE, REMOTE_CANCEL_ROW } = require("../src/web/first-mate-web");
const savedChats = require("../src/saved-chats");
const { createWebFirstMateStore, storeKey } = require("../src/web/first-mate-web-store");
const { createWebFleet, memoryStorage, TOKEN_KEY, KEEP_KEY } = require("../src/web/fleet-web");
const { FIRST_MATE_HISTORY_LIMIT } = require("../src/first-mate-store");

const BEARER = "Bearer frk_page";
const SID = "sess-1";

/** A stream stand-in: `frame(f)` emits one engine frame; subscribe/unsubscribe are counted. */
function fakeEvents() {
  const ev = new EventEmitter();
  ev.subscribed = [];
  ev.subscribe = (r) => { ev.subscribed.push(r); return { ok: true }; };
  ev.unsubscribe = (r) => { ev.subscribed = ev.subscribed.filter((x) => x !== r); return { ok: true }; };
  ev.setSelected = () => {};
  ev.snapshotted = () => ({ ok: true });
  ev.streamState = () => ({ state: "up" });
  ev.frame = (f) => ev.emit("frame", f);
  return ev;
}

// globalThis.fetch for one test: `answer(url, init)` returns { status, body }; every call is recorded.
function withFetch(answer, fn) {
  const calls = [];
  const was = globalThis.fetch;
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), method: init.method || "GET", headers: init.headers || {}, body: init.body ? JSON.parse(init.body) : undefined });
    const { status = 200, body = {} } = answer(String(url), init) || {};
    return { ok: status >= 200 && status < 300, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)) };
  };
  return Promise.resolve(fn(calls)).finally(() => { globalThis.fetch = was; });
}

const tick = () => new Promise((r) => setTimeout(r, 5));

/** The engine as the session API: a start answers SID, every other route 200. */
const engineAnswers = (url, init) => {
  if (url === "/settings/first-mate-hosting") return { body: { hosting: "kubernetes" } };
  if (url === "/first-mate/sessions" && init.method === "POST") return { status: 201, body: { id: SID, repo: "acme/app", state: "open", firstLine: "Pod up: dropped no servers." } };
  if (url.startsWith("/saved-chats/")) return { status: 201, body: { id: url.split("/").pop() } };
  if (url.includes("/repos")) return { body: [{ fullName: "acme/app" }] };
  return { body: {} };
};

const build = (over = {}) => {
  const events = over.events || fakeEvents();
  const storage = over.storage || memoryStorage();
  const fm = createWebFirstMate({
    storage, login: "octo", bearer: () => BEARER, engineEvents: events,
    repo: () => over.repo === undefined ? "acme/app" : over.repo,
    resolveTier: (n) => (n === 8 ? { model: "fable", effort: "low" } : n === 3 ? { model: "sonnet", effort: "medium" } : null),
    submitIntake: over.submitIntake,
    now: () => 1000,
    ...over.opts,
  });
  return { fm, events, storage };
};

/** One frame of the engine's stream for session `sid`. */
const frame = (sid, line) => ({ type: "first-mate", repo: "acme/app", data: { sessionId: sid, line } });

test("send on kubernetes hosting starts a session with POST /first-mate/sessions carrying the page's bearer, then posts the message", async () => {
  const { fm, events } = build();
  await withFetch(engineAnswers, async (calls) => {
    const snap = await fm.list();
    assert.equal(snap.notice, null);
    assert.equal(snap.can_handoff, false);
    assert.equal(snap.iterm.available, false);
    const sending = fm.send("hello pod", []);
    await tick();
    const start = calls.find((c) => c.url === "/first-mate/sessions");
    assert.ok(start, "POST /first-mate/sessions");
    assert.equal(start.method, "POST");
    assert.equal(start.headers.authorization, BEARER);
    assert.deepEqual(start.body, { repo: "acme/app", ask: "hello pod", tier: 8 });
    const msg = calls.find((c) => c.url === `/first-mate/sessions/${SID}/messages`);
    assert.ok(msg, "POST …/messages");
    assert.equal(msg.headers.authorization, BEARER);
    assert.equal(msg.body.text, "hello pod");
    assert.match(msg.body.clientId, /^[0-9a-f-]{36}$/);
    assert.deepEqual(events.subscribed, ["acme/app"], "the repo is subscribed on the page's stream");
    const mid = fm.snapshot();
    assert.equal(mid.busy, true);
    assert.equal(mid.runtime, "kubernetes");
    assert.equal(mid.mode_line, undefined);
    assert.deepEqual(mid.history.map((m) => [m.role, m.text]), [["user", "hello pod"], ["system", "Pod up: dropped no servers."]]);
    // a frame of another session is ignored; the session's own echo then result answers the turn
    events.frame(frame("other-session", { type: "result", result: "not yours" }));
    events.frame(frame("other-session", { type: "user", text: "x" }));
    await tick();
    assert.equal(fm.snapshot().busy, true);
    events.frame(frame(SID, { type: "user", text: "hello pod" }));
    events.frame(frame(SID, { type: "assistant", message: { content: [{ type: "text", text: "Hi " }] } }));
    events.frame(frame(SID, { type: "result", result: "Hi there" }));
    const r = await sending;
    assert.equal(r.ok, true);
    assert.equal(r.text, "Hi there");
    const done = fm.snapshot();
    assert.equal(done.busy, false);
    assert.deepEqual(done.history.map((m) => m.text), ["hello pod", "Pod up: dropped no servers.", "Hi there"]);
    assert.ok(!done.history.some((m) => m.text === "not yours"));
  });
});

test("local hosting: one notice on the snapshot, send and startNew answer it, the desktop-only members OMITTED, font null", async () => {
  const { fm } = build();
  await withFetch((url) => (url === "/settings/first-mate-hosting" ? { body: { hosting: "local" } } : { body: {} }), async (calls) => {
    const snap = await fm.list();
    assert.equal(snap.notice, LOCAL_HOSTING_NOTICE);
    assert.match(snap.notice, /claude.*process on the desktop's machine, which a browser cannot start/);
    const r = await fm.send("hi", []);
    assert.deepEqual(r, { ok: false, refused: true, error: LOCAL_HOSTING_NOTICE });
    const n = await fm.startNew();
    assert.equal(n.ok, false);
    assert.equal(n.error, LOCAL_HOSTING_NOTICE);
    assert.ok(!calls.some((c) => c.url.startsWith("/first-mate/sessions")), "no session is started");
    assert.equal(fm.snapshot().history.length, 0);
    for (const k of ["handoff", "openInIterm", "restoreSaved"]) assert.deepEqual(await fm[k](), { error: OMITTED }, k);
    assert.equal((await fm.font()).family, null);
  });
});

test("conversations persist in the browser store across a fresh bridge over the same storage, keyed by login, capped with capTotal", async () => {
  const storage = memoryStorage();
  const { fm, events } = build({ storage });
  await withFetch(engineAnswers, async () => {
    const sending = fm.send("first question", []);
    await tick();
    events.frame(frame(SID, { type: "user" }));
    events.frame(frame(SID, { type: "result", result: "first answer" }));
    await sending;
    assert.deepEqual(await fm.startNew(), { ok: true });
  });
  const raw = storage.getItem(storeKey("octo"));
  assert.ok(raw, "the document is under frakworks.firstMate.<login>");
  assert.equal(JSON.parse(raw).version, 2);
  assert.equal(storage.getItem(storeKey("someone-else")), null);

  const again = build({ storage }).fm;
  const list = await again.conversations();
  assert.equal(list.length, 1);
  assert.equal(list[0].first_message, "first question");
  const c = await again.conversation(list[0].id);
  assert.equal(c.resumable, true, "a pod conversation with a session id resumes");
  assert.deepEqual(c.messages.map((m) => m.text), ["first question", "Pod up: dropped no servers.", "first answer"]);

  // the cap: a document past the limit is trimmed on save, oldest conversation first
  const store = createWebFirstMateStore({ storage, login: "cap" });
  const big = store.load().session;
  big.conversations = [{ id: 1, started_at: 1, messages: Array.from({ length: 150 }, (_, i) => ({ role: "user", text: `m${i}`, at: i })) }];
  big.history = Array.from({ length: 100 }, (_, i) => ({ role: "user", text: `h${i}`, at: i }));
  big.next_conversation_id = 2;
  assert.deepEqual(store.save(big), { ok: true });
  const back = store.load().session;
  assert.equal(back.history.length, 100, "the live history is never trimmed");
  assert.equal(back.conversations[0].messages.length, FIRST_MATE_HISTORY_LIMIT - 100);
  // a malformed value is a fresh session with the error said
  storage.setItem(storeKey("bad"), "{not json");
  const bad = createWebFirstMateStore({ storage, login: "bad" }).load();
  assert.equal(bad.session.history.length, 0);
  assert.match(bad.error, /not JSON/);
});

test("Save for later PUTs /saved-chats/<uuid> with the page bearer, a second save reuses the id, History shows saved_chat_id", async () => {
  const { fm, events } = build();
  await withFetch(engineAnswers, async (calls) => {
    assert.equal((await fm.save()).ok, false, "nothing to save yet");
    const sending = fm.send("keep this", []);
    await tick();
    events.frame(frame(SID, { type: "user" }));
    events.frame(frame(SID, { type: "result", result: "kept" }));
    await sending;
    const first = await fm.save();
    assert.equal(first.ok, true);
    assert.match(first.id, /^[0-9a-f-]{36}$/);
    const put = calls.find((c) => c.url === `/saved-chats/${first.id}`);
    assert.ok(put, "PUT /saved-chats/<id>");
    assert.equal(put.method, "PUT");
    assert.equal(put.headers.authorization, BEARER);
    assert.equal(put.body.title, "keep this");
    assert.equal(put.body.runtime, "kubernetes");
    assert.deepEqual(put.body.messages.map((m) => m.text), ["keep this", "Pod up: dropped no servers.", "kept"]);
    assert.equal(fm.snapshot().saved_chat_id, first.id);
    const second = await fm.save();
    assert.equal(second.id, first.id, "the same conversation updates its save");
    await fm.startNew();
    const list = await fm.conversations();
    assert.equal(list[0].saved_chat_id, first.id);
    assert.equal((await fm.conversation(list[0].id)).saved_chat_id, first.id);
  });
});

test("cancel and startNew DELETE the live session; a cancelled turn leaves the interrupted and pod rows", async () => {
  const { fm, events } = build();
  await withFetch(engineAnswers, async (calls) => {
    const sending = fm.send("slow one", []);
    await tick();
    assert.deepEqual(await fm.cancel(), { ok: true });
    const r = await sending;
    assert.equal(r.interrupted, true);
    await tick();
    const del = calls.filter((c) => c.method === "DELETE");
    assert.deepEqual(del.map((c) => c.url), [`/first-mate/sessions/${SID}`]);
    assert.equal(del[0].headers.authorization, BEARER);
    const texts = fm.snapshot().history.map((m) => m.text);
    assert.ok(texts.some((t) => /^Interrupted\./.test(t)));
    assert.ok(texts.includes(REMOTE_CANCEL_ROW));
    assert.equal(fm.snapshot().busy, false);
    assert.deepEqual(events.subscribed, [], "the stream subscription is let go of");

    // a new session for the next message, then startNew ends it
    const sending2 = fm.send("again", []);
    await tick();
    events.frame(frame(SID, { type: "user" }));
    events.frame(frame(SID, { type: "result", result: "ok" }));
    await sending2;
    calls.length = 0;
    assert.deepEqual(await fm.startNew(), { ok: true });
    await tick();
    assert.deepEqual(calls.filter((c) => c.method === "DELETE").map((c) => c.url), [`/first-mate/sessions/${SID}`]);
    assert.equal(fm.snapshot().history.length, 0);
    assert.equal(fm.snapshot().notice, "New conversation started.");
  });
});

test("resume of a pod conversation GETs the transcript route and appends the rows that came while the page was away; Back pops it", async () => {
  const { fm, events } = build();
  const transcript = [
    { seq: 1, role: "user", body: { text: "q1" }, at: "2026-10-16T10:00:00Z" },
    { seq: 2, role: "assistant", body: { text: "a1" }, at: "2026-10-16T10:00:05Z" },
    { seq: 3, role: "assistant", body: { text: "a1 postscript" }, at: "2026-10-16T10:00:09Z" },
  ];
  await withFetch((url, init) => (url === `/first-mate/sessions/${SID}/transcript` ? { body: { session: { id: SID }, messages: transcript } } : engineAnswers(url, init)), async (calls) => {
    const sending = fm.send("q1", []);
    await tick();
    events.frame(frame(SID, { type: "user" }));
    events.frame(frame(SID, { type: "result", result: "a1" }));
    await sending;
    await fm.startNew();
    await tick();
    const [past] = await fm.conversations();
    calls.length = 0;
    assert.deepEqual(await fm.resume(past.id), { ok: true });
    const get = calls.find((c) => c.url === `/first-mate/sessions/${SID}/transcript`);
    assert.ok(get, "GET …/transcript");
    assert.equal(get.method, "GET");
    assert.equal(get.headers.authorization, BEARER);
    const snap = fm.snapshot();
    assert.deepEqual(snap.history.map((m) => m.text), ["q1", "Pod up: dropped no servers.", "a1", "a1 postscript"]);
    assert.equal(snap.notice, "Resumed a past conversation.");
    assert.equal(snap.runtime, "kubernetes");
    assert.equal(snap.can_pop, false, "the new, empty conversation was not archived");
    assert.equal((await fm.resume(999)).ok, false);
    // the next turn goes on in the same session: no new POST /first-mate/sessions
    calls.length = 0;
    const sending2 = fm.send("q2", []);
    await tick();
    assert.ok(!calls.some((c) => c.url === "/first-mate/sessions"), "no new session");
    assert.ok(calls.some((c) => c.url === `/first-mate/sessions/${SID}/messages`));
    events.frame(frame(SID, { type: "user" }));
    events.frame(frame(SID, { type: "result", result: "a2" }));
    await sending2;
    assert.equal(fm.snapshot().history.at(-1).text, "a2");
  });
});

test("the File card files through the page as the login with the Tier line, checking the repo against GET /repos", async () => {
  const filed = [];
  const { fm, events } = build({ submitIntake: async (ask, repo) => { filed.push({ ask, repo }); return { ok: true, intakeId: "in-1" }; } });
  await withFetch(engineAnswers, async () => {
    const sending = fm.send("make a ticket", []);
    await tick();
    events.frame(frame(SID, { type: "user" }));
    events.frame(frame(SID, { type: "result", result: "Sure.\nFile: 3 | Add a thing" }));
    const r = await sending;
    assert.deepEqual(r.file, { tier: 3, ask: "Add a thing", turn_id: r.file.turn_id });
    assert.equal((await fm.file(3, "acme/nope")).error, "acme/nope is not a target repo");
    assert.deepEqual(await fm.file(3, null), { ok: true, intake_id: "in-1" });
    assert.deepEqual(filed, [{ ask: "Tier: 3 (sonnet medium) — prescribed by the user; do not re-judge\n\nAdd a thing", repo: "acme/app" }]);
    const snap = fm.snapshot();
    assert.equal(snap.file, null);
    assert.equal(snap.filed[0].intake_id, "in-1");
    assert.equal(snap.turns_since_filed, 0);
  });
});

test("the web runtime's chatTitle is saved-chats.js's, and the bundle reaches no module that reads process.env as it loads", () => {
  for (const msgs of [[], [{ role: "system", text: "x" }], [{ role: "user", text: "  Hello\nmore" }], [{ role: "user", text: "w".repeat(100) }]]) {
    assert.equal(chatTitle(msgs), savedChats.chatTitle(msgs));
  }
  const fs = require("node:fs");
  const path = require("node:path");
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "web", "first-mate-web.js"), "utf8");
  assert.doesNotMatch(src, /require\("\.\.\/(saved-chats|claude-session|first-mate|first-mate-tier)"\)/);
});

test("the tier is kept per login in the browser store; setRepo sets the chat's repo; no repo refuses a pod start", async () => {
  const storage = memoryStorage();
  const { fm } = build({ storage, repo: null });
  assert.deepEqual(await fm.tierState(), { standing: 8, effective: { tier: 8, model: "fable", effort: "low", source: "standing" }, error: null });
  assert.equal((await fm.setTier(11)).ok, false);
  assert.equal((await fm.setTier(3)).ok, true);
  assert.equal(build({ storage, repo: null }).fm.snapshot().tier.tier, 3, "a fresh bridge reads the kept tier");
  await withFetch(engineAnswers, async (calls) => {
    const r = await fm.send("hi", []);
    assert.equal(r.refused, true);
    assert.match(r.error, /Pick a repo/);
    assert.ok(!calls.some((c) => c.url === "/first-mate/sessions"));
    assert.deepEqual(await fm.setRepo("acme/app"), { ok: true, repo: "acme/app" });
    assert.equal(fm.snapshot().chat_repo, "acme/app");
    assert.equal((await fm.setRepo("nope")).ok, false);
  });
});

test("the bridge's window.firstMate waits for the login, keys the store by it, and Sign out lets the session go", async () => {
  const persist = memoryStorage();
  const prefs = memoryStorage();
  prefs.setItem(KEEP_KEY, "1");
  persist.setItem(TOKEN_KEY, "frk_page");
  const events = fakeEvents();
  const bridge = createWebFleet({ storage: memoryStorage(), prefs, persist, stream: events });
  const whoami = (url, init) => (url.endsWith("/whoami") ? { body: { login: "octo", tokenId: "t1", name: "ipad" } } : engineAnswers(url, init));
  await withFetch(whoami, async (calls) => {
    const snap = await bridge.firstMate.list();
    assert.equal(snap.notice, null);
    assert.equal(snap.chat_repo, null, "All repos: no chat repo yet");
    await bridge.repo.set("acme/app");
    const updates = [];
    bridge.firstMate.onUpdate((s) => updates.push(s));
    const sending = bridge.firstMate.send("from the page", []);
    await tick();
    const start = calls.find((c) => c.url === "/first-mate/sessions");
    assert.ok(start);
    assert.equal(start.headers.authorization, BEARER, "the page's own sign-in bearer");
    events.frame(frame(SID, { type: "user" }));
    events.frame(frame(SID, { type: "result", result: "hello" }));
    await sending;
    assert.ok(updates.length > 0);
    assert.ok(persist.getItem(storeKey("octo")), "kept in localStorage under the login");
    assert.match((await bridge.fleet.savedChats()).error, /not in the web version/, "Saved chats > Claude Code stays desktop-only");
    calls.length = 0;
    await bridge.fleet.signOut();
    await tick();
    assert.ok(!calls.some((c) => c.method === "DELETE"), "sign out detaches, it does not end the session");
    assert.equal(await bridge.firstMate.list(), null);
    assert.ok(persist.getItem(storeKey("octo")), "the conversation stays for the next sign-in");
  });
});
