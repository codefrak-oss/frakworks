// first-mate-store.js (issue #257): the conversations archive, the total cap
// across history + conversations, and the version-1-to-2 migration.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const {
  FIRST_MATE_VERSION, FIRST_MATE_HISTORY_LIMIT,
  freshSession, serializeSession, saveFirstMate, loadFirstMate,
  archiveCurrentConversation, capTotal,
} = require("../src/first-mate-store");

const tmpdir = () => fs.mkdtempSync(path.join(os.tmpdir(), "first-mate-store-test-"));
const msg = (role, text, at) => ({ role, text, at });

test("FIRST_MATE_VERSION is 2, and freshSession carries conversations and next_conversation_id", () => {
  assert.equal(FIRST_MATE_VERSION, 2);
  const s = freshSession();
  assert.deepEqual(s.conversations, []);
  assert.equal(s.next_conversation_id, 1);
  const ser = serializeSession(s);
  assert.deepEqual(ser.conversations, []);
  assert.equal(ser.next_conversation_id, 1);
});

// ---------------------------------------------------------------------------
// archiveCurrentConversation
// ---------------------------------------------------------------------------

test("archiveCurrentConversation moves a non-empty history into conversations[0] and resets history", () => {
  const s = freshSession();
  s.history = [msg("user", "hi", 1), msg("assistant", "hello", 2)];
  s.next_conversation_id = 5;
  archiveCurrentConversation(s);
  assert.deepEqual(s.history, []);
  assert.equal(s.conversations.length, 1);
  assert.equal(s.conversations[0].id, 5);
  assert.equal(s.conversations[0].started_at, 1);
  assert.deepEqual(s.conversations[0].messages, [msg("user", "hi", 1), msg("assistant", "hello", 2)]);
  assert.equal(s.next_conversation_id, 6);
});

test("archiveCurrentConversation is a no-op on an empty history", () => {
  const s = freshSession();
  archiveCurrentConversation(s);
  assert.deepEqual(s.conversations, []);
  assert.equal(s.next_conversation_id, 1);
  assert.deepEqual(s.history, []);
});

test("archiveCurrentConversation unshifts, so the newest archived conversation is conversations[0]", () => {
  const s = freshSession();
  s.history = [msg("user", "first", 1)];
  archiveCurrentConversation(s);
  s.history = [msg("user", "second", 2)];
  archiveCurrentConversation(s);
  assert.equal(s.conversations.length, 2);
  assert.equal(s.conversations[0].messages[0].text, "second");
  assert.equal(s.conversations[1].messages[0].text, "first");
});

// ---------------------------------------------------------------------------
// capTotal
// ---------------------------------------------------------------------------

test("capTotal leaves an under-limit total untouched", () => {
  const s = freshSession();
  s.history = [msg("user", "a", 1), msg("assistant", "b", 2)];
  s.conversations = [{ id: 1, started_at: 0, messages: [msg("user", "c", 0)] }];
  capTotal(s);
  assert.equal(s.history.length, 2);
  assert.equal(s.conversations[0].messages.length, 1);
});

test("capTotal trims the oldest conversation's oldest messages first, dropping an emptied conversation", () => {
  const big = freshSession();
  big.history = new Array(3).fill(0).map((_, i) => msg("user", `h${i}`, i));
  big.conversations = [
    { id: 2, started_at: 100, messages: new Array(2).fill(0).map((_, i) => msg("user", `new-${i}`, 100 + i)) },
    { id: 1, started_at: 0, messages: new Array(FIRST_MATE_HISTORY_LIMIT).fill(0).map((_, i) => msg("user", `old-${i}`, i)) },
  ];
  const totalBefore = big.history.length + big.conversations.reduce((n, c) => n + c.messages.length, 0);
  assert.ok(totalBefore > FIRST_MATE_HISTORY_LIMIT);
  capTotal(big);
  const totalAfter = big.history.length + big.conversations.reduce((n, c) => n + c.messages.length, 0);
  assert.ok(totalAfter <= FIRST_MATE_HISTORY_LIMIT);
  assert.equal(big.history.length, 3, "current history is never trimmed");
  assert.equal(big.conversations.length, 2, "the newer conversation is untouched while the oldest still has messages");
  assert.equal(big.conversations[1].messages[0].text, `old-${totalBefore - FIRST_MATE_HISTORY_LIMIT}`,
    "the oldest conversation's oldest messages are trimmed first");
});

test("capTotal drops an emptied conversation entirely and never trims the current history, even when history alone exceeds the limit", () => {
  const s = freshSession();
  s.history = new Array(FIRST_MATE_HISTORY_LIMIT + 10).fill(0).map((_, i) => msg("user", `h${i}`, i));
  s.conversations = [{ id: 1, started_at: 0, messages: [msg("user", "only", 0), msg("user", "second", 1)] }];
  capTotal(s);
  assert.equal(s.history.length, FIRST_MATE_HISTORY_LIMIT + 10, "history itself is never trimmed by capTotal");
  assert.deepEqual(s.conversations, [], "the only conversation is trimmed down to empty and dropped");
});

// ---------------------------------------------------------------------------
// loadFirstMate migration
// ---------------------------------------------------------------------------

const writeDoc = (dir, doc) => fs.writeFileSync(path.join(dir, "first-mate.json"), JSON.stringify(doc));

test("loadFirstMate migrates a v1 document with one marker into one past conversation plus a current tail", () => {
  const dir = tmpdir();
  writeDoc(dir, {
    version: 1,
    session: {
      session_generation: 1, claude_session_id: null, turns_since_filed: 0,
      history: [
        msg("user", "first thing", 1),
        msg("assistant", "ok", 2),
        msg("system", "New conversation started. The earlier messages above are history only.", 3),
        msg("user", "second thing", 4),
      ],
      filed: [],
    },
  });
  const { session, error } = loadFirstMate(dir);
  assert.equal(error, null);
  assert.equal(session.conversations.length, 1);
  assert.equal(session.conversations[0].id, 1);
  assert.equal(session.conversations[0].started_at, 1);
  assert.deepEqual(session.conversations[0].messages.map((m) => m.text), ["first thing", "ok"]);
  assert.deepEqual(session.history.map((m) => m.text), ["second thing"]);
  assert.equal(session.next_conversation_id, 2);
});

test("loadFirstMate migrates a v1 document with multiple/adjacent markers producing no empty conversations", () => {
  const dir = tmpdir();
  const marker = (at) => msg("system", "New conversation started. more text", at);
  writeDoc(dir, {
    version: 1,
    session: {
      session_generation: 1, claude_session_id: null, turns_since_filed: 0,
      history: [
        msg("user", "a", 1),
        marker(2),
        marker(3), // adjacent: an empty group between two markers
        msg("user", "b", 4),
        marker(5),
        msg("user", "c", 6),
      ],
      filed: [],
    },
  });
  const { session } = loadFirstMate(dir);
  assert.equal(session.conversations.length, 2, "the empty group between adjacent markers is dropped");
  // stored newest-first: the "b" conversation is more recent than "a".
  assert.deepEqual(session.conversations[0].messages.map((m) => m.text), ["b"]);
  assert.deepEqual(session.conversations[1].messages.map((m) => m.text), ["a"]);
  assert.deepEqual(session.history.map((m) => m.text), ["c"]);
  assert.equal(session.next_conversation_id, 3);
});

test("loadFirstMate keeps a v1 document with no marker as current history", () => {
  const dir = tmpdir();
  writeDoc(dir, {
    version: 1,
    session: {
      session_generation: 1, claude_session_id: null, turns_since_filed: 0,
      history: [msg("user", "a", 1), msg("assistant", "b", 2)],
      filed: [],
    },
  });
  const { session } = loadFirstMate(dir);
  assert.deepEqual(session.conversations, []);
  assert.equal(session.next_conversation_id, 1);
  assert.deepEqual(session.history.map((m) => m.text), ["a", "b"]);
});

test("loadFirstMate loads a v2 document's conversations directly", () => {
  const dir = tmpdir();
  const doc = {
    version: 2,
    session: {
      session_generation: 3, claude_session_id: "sess-1", turns_since_filed: 1,
      history: [msg("user", "live", 9)],
      conversations: [{ id: 1, started_at: 1, messages: [msg("user", "past", 1)] }],
      next_conversation_id: 2,
      filed: [],
    },
  };
  writeDoc(dir, doc);
  const { session, error } = loadFirstMate(dir);
  assert.equal(error, null);
  assert.equal(session.conversations.length, 1);
  assert.equal(session.conversations[0].id, 1);
  assert.deepEqual(session.conversations[0].messages.map((m) => m.text), ["past"]);
  assert.equal(session.next_conversation_id, 2);
  assert.deepEqual(session.history.map((m) => m.text), ["live"]);
});

test("loadFirstMate still yields a fresh session for unreadable, malformed or unknown-version documents", () => {
  const dir1 = tmpdir();
  const r1 = loadFirstMate(dir1); // ENOENT
  assert.equal(r1.error, null);
  assert.deepEqual(r1.session, freshSession());

  const dir2 = tmpdir();
  fs.writeFileSync(path.join(dir2, "first-mate.json"), "not json{{");
  const r2 = loadFirstMate(dir2);
  assert.match(r2.error, /not JSON/);
  assert.deepEqual(r2.session, freshSession());

  const dir3 = tmpdir();
  writeDoc(dir3, { version: 99, session: { history: [] } });
  const r3 = loadFirstMate(dir3);
  assert.match(r3.error, /version 99 is not 2/);
  assert.deepEqual(r3.session, freshSession());
});

// ---------------------------------------------------------------------------
// Issue #486: an archived conversation keeps its Claude session id.
// ---------------------------------------------------------------------------

test("archiveCurrentConversation stores the live claude_session_id, and it survives a save and load", () => {
  const dir = tmpdir();
  const s = freshSession();
  s.claude_session_id = "sess-live";
  s.history = [msg("user", "hi", 1), msg("assistant", "hello", 2)];
  archiveCurrentConversation(s);
  assert.equal(s.conversations[0].claude_session_id, "sess-live");
  saveFirstMate(dir, s);
  const { session, error } = loadFirstMate(dir);
  assert.equal(error, null);
  assert.equal(session.conversations[0].claude_session_id, "sess-live");
});

test("archiveCurrentConversation stores null when there was no live session", () => {
  const s = freshSession();
  s.history = [msg("user", "hi", 1)];
  archiveCurrentConversation(s);
  assert.equal(s.conversations[0].claude_session_id, null);
});

test("a first-mate.json from before #486, with no claude_session_id on its conversations, loads with null ids", () => {
  const dir = tmpdir();
  writeDoc(dir, {
    version: 2,
    session: {
      session_generation: 1, claude_session_id: "sess-live", turns_since_filed: 0,
      history: [],
      conversations: [
        { id: 2, started_at: 5, messages: [msg("user", "newer", 5)] },
        { id: 1, started_at: 1, messages: [msg("user", "older", 1)] },
      ],
      next_conversation_id: 3,
      filed: [],
    },
  });
  const { session, error } = loadFirstMate(dir);
  assert.equal(error, null);
  assert.equal(FIRST_MATE_VERSION, 2, "unchanged: the new field is optional");
  assert.deepEqual(session.conversations.map((c) => c.claude_session_id), [null, null]);
  assert.deepEqual(session.conversations.map((c) => c.messages[0].text), ["newer", "older"]);
});

// ---------------------------------------------------------------------------
// Saved chats (#598): the saved-chat id and the seed
// ---------------------------------------------------------------------------

test("saved_chat_id and seed survive a save and load; a document from before #598 loads them as null", () => {
  const dir = tmpdir();
  const s = freshSession();
  assert.equal(s.saved_chat_id, null);
  assert.equal(s.seed, null);
  s.saved_chat_id = "0b7c9a1e-3f2d-4c5b-9a8e-1d2c3b4a5f60";
  s.seed = "[Restored saved chat]";
  s.history = [msg("user", "hi", 1)];
  saveFirstMate(dir, s);
  const { session } = loadFirstMate(dir);
  assert.equal(session.saved_chat_id, "0b7c9a1e-3f2d-4c5b-9a8e-1d2c3b4a5f60");
  assert.equal(session.seed, "[Restored saved chat]");
  writeDoc(dir, { version: 2, session: { session_generation: 1, history: [], conversations: [{ id: 1, started_at: 1, messages: [msg("user", "old", 1)] }], next_conversation_id: 2, filed: [] } });
  const old = loadFirstMate(dir).session;
  assert.equal(old.saved_chat_id, null);
  assert.equal(old.seed, null);
  assert.equal(old.conversations[0].saved_chat_id, null);
});

test("archiveCurrentConversation carries the live saved_chat_id into the archive", () => {
  const s = freshSession();
  s.saved_chat_id = "id-1";
  s.history = [msg("user", "hi", 1)];
  archiveCurrentConversation(s);
  assert.equal(s.conversations[0].saved_chat_id, "id-1");
  const t = freshSession();
  t.history = [msg("user", "hi", 1)];
  archiveCurrentConversation(t);
  assert.equal(t.conversations[0].saved_chat_id, null);
});

// ---------------------------------------------------------------------------
// #960, fresh code: the conversation's cwd on the session and the archive.
// ---------------------------------------------------------------------------

test("cwd round-trips on the session and on archived conversations, and a pre-#960 document loads with cwd null", () => {
  const dir = tmpdir();
  const s = freshSession();
  assert.equal(s.cwd, null);
  s.cwd = "/u/fresh/o/n";
  s.history = [msg("user", "q", 1), msg("assistant", "a", 2)];
  archiveCurrentConversation(s);
  assert.equal(s.conversations[0].cwd, "/u/fresh/o/n", "the archive carries the cwd");
  s.cwd = "/u/fresh";
  s.history = [msg("user", "q2", 3)];
  saveFirstMate(dir, s);
  const { session, error } = loadFirstMate(dir);
  assert.equal(error, null);
  assert.equal(session.cwd, "/u/fresh");
  assert.equal(session.conversations[0].cwd, "/u/fresh/o/n");
  assert.equal(FIRST_MATE_VERSION, 2, "an added nullable field, no version bump");
  // A document written before #960: no cwd anywhere, read as null (the legacy cwd).
  const old = { version: 2, session: { session_generation: 3, claude_session_id: "s", history: [msg("user", "q", 1)],
    conversations: [{ id: 1, started_at: 1, claude_session_id: "s0", messages: [msg("user", "old", 1)] }], next_conversation_id: 2, filed: [] } };
  fs.writeFileSync(path.join(dir, "first-mate.json"), JSON.stringify(old));
  const loaded = loadFirstMate(dir).session;
  assert.equal(loaded.cwd, null);
  assert.equal(loaded.conversations[0].cwd, null);
  assert.equal(serializeSession({ cwd: "" }).cwd, null, "an empty string is null, never written back as a path");
});

test("the chat's repo round-trips, and an entry without one loads without it (#2343)", () => {
  const { serializeSession } = require("../src/first-mate-store");
  const base = { history: [], conversations: [{ id: 1, messages: [], repo: "o/c" }, { id: 2, messages: [] }] };
  const s = serializeSession({ ...base, repo: null });
  assert.equal(s.repo, null);
  assert.equal(s.conversations[0].repo, "o/c");
  assert.equal("repo" in s.conversations[1], false);
  assert.equal("repo" in serializeSession(base), false);
  assert.equal(serializeSession({ ...base, repo: "o/n" }).repo, "o/n");
});
