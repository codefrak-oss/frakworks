// Saved First mate chats (#598), the desktop's pure side: src/saved-chats.js
// (title, summary, display window, seed preamble, resumability). The view's
// drawing is test/saved-chat-view.test.js.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const {
  DISPLAY_CAP, SEED_BUDGET, UNTITLED, chatTitle, summarize, displayWindow, seedPreamble,
  sessionFileExists, resumability, savedChatView,
} = require("../src/saved-chats");
const { claudeProjectSlug } = require("../src/claude-session");

const msg = (role, text, at) => ({ role, text, at });
const convo = (n) => Array.from({ length: n }, (_, i) => msg(i % 2 ? "assistant" : "user", `message ${i}`, 1000 + i));

test("chatTitle is the first user message's first line, collapsed and capped at 80", () => {
  assert.equal(chatTitle([msg("system", "note", 1), msg("user", "  what   is\nthe plan", 2)]), "what is");
  assert.equal(chatTitle([msg("assistant", "hi", 1)]), UNTITLED);
  assert.equal(chatTitle([msg("user", "   ", 1)]), UNTITLED);
  assert.equal(chatTitle([]), UNTITLED);
  const long = chatTitle([msg("user", "x".repeat(200), 1)]);
  assert.equal(long.length, 80);
  assert.ok(long.endsWith("…"));
});

test("summarize counts turns and messages and spans the message times", () => {
  assert.deepEqual(summarize({ messages: convo(5) }), { turns: 3, messages: 5, first_at: 1000, last_at: 1004 });
  assert.deepEqual(summarize({ messages: [] }), { turns: 0, messages: 0, first_at: null, last_at: null });
});

test("displayWindow keeps the newest 60 messages and says how many are hidden", () => {
  assert.equal(DISPLAY_CAP, 60);
  const w = displayWindow(convo(75));
  assert.equal(w.shown.length, 60);
  assert.equal(w.hidden, 15);
  assert.equal(w.shown[0].text, "message 15");
  assert.deepEqual(displayWindow(convo(3)), { shown: convo(3), hidden: 0 });
});

test("seedPreamble frames the transcript and keeps the newest messages within the budget", () => {
  assert.equal(SEED_BUDGET, 40000);
  const all = seedPreamble({ title: "Plan", messages: [msg("user", "q", 1), msg("assistant", "a", 2)] });
  assert.match(all, /^\[Restored saved chat "Plan"\. Its Claude session was on another machine/);
  assert.match(all, /The user: q\n\nFirst mate: a\n\n\[End of the restored conversation\. The user's new message follows\.\]$/);
  assert.doesNotMatch(all, /left out/);
  const messages = Array.from({ length: 10 }, (_, i) => msg("user", `${i}`.repeat(100), i));
  const cut = seedPreamble({ title: "Big", messages }, 350);
  assert.match(cut, /\(7 older messages left out\)/);
  assert.ok(cut.includes("9".repeat(100)) && !cut.includes("6".repeat(100)));
  // one message over the whole budget is still given, cut
  const huge = seedPreamble({ messages: [msg("user", "z".repeat(500), 1)] }, 100);
  assert.ok(huge.includes(`The user: ${"z".repeat(90)}…`));
});

test("sessionFileExists and resumability look in ~/.claude/projects/<slug of cwd>/<id>.jsonl", () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "saved-chats-"));
  const cwd = "/Users/someone/dev/frakworks";
  const dir = path.join(home, ".claude", "projects", claudeProjectSlug(cwd));
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, "sess-here.jsonl"), "{}\n");
  assert.equal(sessionFileExists(cwd, "sess-here", { home }), true);
  assert.equal(sessionFileExists(cwd, "sess-elsewhere", { home }), false);
  assert.equal(sessionFileExists(cwd, "../sess-here", { home }), false, "an id that is no plain name is never a path");
  assert.deepEqual(resumability({ claudeSessionId: "sess-here" }, cwd, { home }), { resumable: true, why: null });
  assert.deepEqual(resumability({ claudeSessionId: "sess-elsewhere" }, cwd, { home }), { resumable: false, why: "saved on another machine" });
  assert.deepEqual(resumability({ claudeSessionId: null }, cwd, { home }), { resumable: false, why: "no session id" });
  const v = savedChatView({ id: "x", claudeSessionId: "sess-here", messages: convo(3) }, cwd, { home });
  assert.equal(v.resumable, true);
  assert.equal(v.summary.turns, 2);
  assert.equal(v.window.hidden, 0);
});

// #819: the hand-off passes its own wording; restore's stays as it was.
test("seedPreamble's default wording is unchanged, and a caller may replace the opening and closing lines", () => {
  const c = { title: "Plan", messages: [msg("user", "q", 1)] };
  const def = seedPreamble(c);
  assert.equal(def, seedPreamble(c, SEED_BUDGET, {}));
  assert.match(def, /^\[Restored saved chat "Plan"\. Its Claude session was on another machine, so this is a new session\. The conversation so far, for context; continue from it:\]\n\n/);
  assert.match(def, /\[End of the restored conversation\. The user's new message follows\.\]$/);
  assert.equal(seedPreamble(c, SEED_BUDGET, { opening: "OPEN", closing: "CLOSE" }), "OPEN\n\nThe user: q\n\nCLOSE");
});

// #960, fresh code: a conversation's session file is under the slug of its cwd, the clone.
test("resumability with the clone's cwd looks under the clone's slug, not the checkout's", () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "saved-chats-fresh-"));
  const checkout = "/Users/someone/dev/frakworks";
  const clone = "/Users/someone/Library/Application Support/frakworks/first-mate/fresh/codefrak-oss/frakworks";
  const dir = path.join(home, ".claude", "projects", claudeProjectSlug(clone));
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, "sess-clone.jsonl"), "{}\n");
  assert.deepEqual(resumability({ claudeSessionId: "sess-clone" }, clone, { home }), { resumable: true, why: null });
  assert.deepEqual(resumability({ claudeSessionId: "sess-clone" }, checkout, { home }), { resumable: false, why: "saved on another machine" });
  assert.equal(claudeProjectSlug(clone), "-Users-someone-Library-Application-Support-frakworks-first-mate-fresh-codefrak-oss-frakworks");
});
