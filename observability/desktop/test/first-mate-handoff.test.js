// Continue in terminal (#819):
// src/first-mate-handoff.js, the command and the hand-off file's text. Main's
// wiring is checked in test/first-mate-ipc.test.js; the buttons in
// test/first-mate-view.test.js and test/saved-chat-view.test.js.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");

const { OPENING, CLOSING, shellQuote, tierLine, handoffText, handoffCommand, handoffPlan } = require("../src/first-mate-handoff");
const { SEED_BUDGET } = require("../src/saved-chats");

const msg = (role, text, at) => ({ role, text, at });
const resolveTierFn = (n) => (n === 5 ? { model: "opus", effort: "medium" } : null);
const chat = (over = {}) => ({
  id: "0b7c9a1e-3f2d-4c5b-9a8e-1d2c3b4a5f60", title: "Plan the week", repo: "o/r", tier: 5,
  savedFrom: "laptop", startedAt: "2026-09-27T09:00:00.000Z", claudeSessionId: "6f1c-e2",
  messages: [msg("user", "what is on", 1), msg("assistant", "two tickets", 2)], ...over,
});
const sh = (script) => execFileSync("sh", ["-c", script], { encoding: "utf8" });

test("shellQuote single-quotes, turns ' into '\\'' and round-trips through sh", () => {
  assert.equal(shellQuote("/a b/c"), "'/a b/c'");
  assert.equal(shellQuote("it's"), "'it'\\''s'");
  for (const s of ["/Users/you/Library/Application Support/x", "/tmp/it's here", "$(echo no) `no` \"q\" \\ *"]) {
    assert.equal(sh(`printf %s ${shellQuote(s)}`), s);
  }
});

test("handoffCommand: the resume shape is a fork of the session, in the quoted cwd", () => {
  assert.equal(handoffCommand({ cwd: "/Users/you/dev/frakworks", sessionId: "6f1c-e2", resumable: true }),
    "cd '/Users/you/dev/frakworks' && claude --resume 6f1c-e2 --fork-session");
  assert.equal(handoffCommand({ cwd: "/a b/it's", sessionId: "s1", resumable: true }),
    "cd '/a b/it'\\''s' && claude --resume s1 --fork-session");
  assert.throws(() => handoffCommand({ cwd: "/x", sessionId: "s; rm -rf ~", resumable: true }));
  assert.throws(() => handoffCommand({ cwd: "/x", sessionId: null, resumable: true }));
});

test("handoffCommand: the seeded shape cats the quoted file, and needs one", () => {
  assert.equal(handoffCommand({ cwd: "/r", resumable: false, file: "/u/App Support/h/1.md" }),
    "cd '/r' && claude \"$(cat '/u/App Support/h/1.md')\"");
  assert.throws(() => handoffCommand({ cwd: "/r", resumable: false }));
});

test("handoffPlan: no message text ever appears in the command, on either path", () => {
  const nasty = "it's $(rm -rf ~) and `whoami` and \"quotes\"";
  const c = chat({ messages: [msg("user", nasty, 1), msg("assistant", "ok", 2)] });
  for (const resumable of [true, false]) {
    const p = handoffPlan(c, { cwd: "/r", dir: "/u/handoff", resumable, resolveTierFn });
    for (const bit of ["rm -rf", "whoami", "quotes", "two tickets"]) assert.ok(!p.command.includes(bit), `${bit} (${p.mode})`);
  }
  const seed = handoffPlan(c, { cwd: "/r", dir: "/u/handoff", resumable: false, resolveTierFn });
  assert.equal(seed.mode, "seed");
  assert.equal(seed.file, "/u/handoff/0b7c9a1e-3f2d-4c5b-9a8e-1d2c3b4a5f60.md");
  assert.ok(seed.text.includes(nasty), "the file holds the message as written");
  const resume = handoffPlan(c, { cwd: "/r", dir: "/u/handoff", resumable: true });
  assert.deepEqual([resume.mode, resume.file, resume.text], ["resume", null, null]);
  assert.throws(() => handoffPlan(chat({ messages: [] }), { cwd: "/r", dir: "/d", resumable: false }), /nothing to hand off/);
  assert.throws(() => handoffPlan(chat({ id: "../x" }), { cwd: "/r", dir: "/d", resumable: false }));
});

test("handoffText: the header, the new opening line, the transcript and the one-line-reply instruction", () => {
  const text = handoffText(chat(), { resolveTierFn });
  assert.match(text, /^Title: Plan the week\nSaved-chat id: 0b7c9a1e-3f2d-4c5b-9a8e-1d2c3b4a5f60\nRepo: o\/r\nTier: 5 · opus medium\nSaved from: laptop\nStarted: 2026-09-27T09:00:00\.000Z\n\n/);
  assert.match(OPENING, /^\[Continued from a First mate conversation in the frakworks desktop app/);
  assert.ok(text.includes(`${OPENING}\n\nThe user: what is on\n\nFirst mate: two tickets\n\n${CLOSING}\n`));
  assert.match(CLOSING, /Reply in one line that you have the context/);
  assert.doesNotMatch(text, /Restored saved chat/);
  assert.match(handoffText(chat({ repo: null, tier: 9, savedFrom: null }), { resolveTierFn }), /Repo: All repos\nTier: 9\nStarted/);
  assert.equal(tierLine(null, resolveTierFn), null);
});

test("handoffText keeps the newest messages within SEED_BUDGET and counts the older ones", () => {
  assert.equal(SEED_BUDGET, 40000);
  const messages = Array.from({ length: 10 }, (_, i) => msg("user", `${i}`.repeat(10000), i));
  const text = handoffText(chat({ messages }), { resolveTierFn });
  assert.match(text, /\(7 older messages left out\)/);
  assert.ok(text.includes("9".repeat(10000)) && !text.includes("6".repeat(10000)));
  assert.ok(text.indexOf("7".repeat(10000)) < text.indexOf("9".repeat(10000)), "oldest kept first, as seedPreamble lays it out");
});

// #960, fresh code: the `cd` is the conversation's stored cwd, the clone, on both paths.
test("handoffPlan's command cds to the stored cwd, the fresh clone, on the resume and the seed path", () => {
  const cwd = "/Users/you/Library/Application Support/frakworks/first-mate/fresh/o/r";
  const c = chat({ cwd });
  const resume = handoffPlan(c, { cwd: c.cwd, dir: "/u/handoff", resumable: true });
  assert.equal(resume.command, `cd '${cwd}' && claude --resume 6f1c-e2 --fork-session`);
  const seed = handoffPlan(c, { cwd: c.cwd, dir: "/u/handoff", resumable: false, resolveTierFn });
  assert.ok(seed.command.startsWith(`cd '${cwd}' && claude "$(cat `));
  assert.doesNotMatch(seed.text, /fresh\/o\/r/, "the cwd is not in the file: it is main's, not the chat's body");
});

test("a kubernetes conversation hands off in the seeded form only, saying its session file is in the pod (#2523)", () => {
  const { POD_LINE } = require("../src/first-mate-handoff");
  const { resumability } = require("../src/saved-chats");
  const chat = { id: "c-1", runtime: "kubernetes", claudeSessionId: "abc-123", messages: [{ role: "user", text: "hi", at: 1 }] };
  assert.deepEqual(resumability(chat, "/tmp"), { resumable: false, mode: "seed", why: "its session file is in the pod" });
  const plan = handoffPlan(chat, { cwd: "/w", dir: "/d", resumable: true });
  assert.equal(plan.mode, "seed");
  assert.doesNotMatch(plan.command, /--resume/);
  assert.match(plan.text, new RegExp(`Ran in: ${POD_LINE.replace(/[()]/g, "\\$&")}`));
  assert.doesNotMatch(handoffText({ ...chat, runtime: "local" }), /Ran in:/);
});
