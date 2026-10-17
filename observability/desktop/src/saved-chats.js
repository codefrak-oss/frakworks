// saved-chats.js — the desktop side of saved First mate chats (issue #598):
// the pure helpers main.js and FirstMateManager share for "Save for later",
// the Saved Chats view and restore. The engine stores the chats
// (`/saved-chats`, engine/README.md "Saved First mate chats"); this file only
// shapes what is sent and what the view shows, derived from the transcript
// with no model call.
//
// Restore across machines: a Claude session id only resumes on the machine
// whose `~/.claude/projects/<slug of cwd>/` holds its `<id>.jsonl`
// (`sessionFileExists`). Without it the restored chat starts a fresh session
// whose first turn is prefixed with `seedPreamble`, the saved transcript's most
// recent part, so First mate has the context the user restored it for.
// `cwd` is the conversation's (#960, fresh code): the fresh clone of the chat's
// repo, under whose slug its session file is; main decides which (`savedChatCwd`).
"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { claudeProjectSlug } = require("./claude-session");

// How many messages the Saved Chats view draws: the most recent ones, with a
// line saying how many earlier ones are not shown.
const DISPLAY_CAP = 60;
// The most characters of transcript a fresh session is given on its first turn.
const SEED_BUDGET = 40000;
const TITLE_MAX = 80;
const UNTITLED = "Untitled chat";

const WHO = { user: "The user", assistant: "First mate", system: "System" };

/** The first line of the first user message, whitespace collapsed, at most 80 characters. */
function chatTitle(messages) {
  const first = (messages || []).find((m) => m && m.role === "user" && String(m.text || "").trim());
  if (!first) return UNTITLED;
  const line = String(first.text).trim().split("\n")[0].replace(/\s+/g, " ").trim();
  if (!line) return UNTITLED;
  return line.length > TITLE_MAX ? `${line.slice(0, TITLE_MAX - 1)}…` : line;
}

/** Turns (user messages), message count, and the first and last message times. */
function summarize(chat) {
  const messages = (chat && chat.messages) || [];
  const times = messages.map((m) => Number(m.at)).filter((t) => Number.isFinite(t) && t > 0);
  return {
    turns: messages.filter((m) => m.role === "user").length,
    messages: messages.length,
    first_at: times.length ? Math.min(...times) : null,
    last_at: times.length ? Math.max(...times) : null,
  };
}

/** The most recent `cap` messages to draw, and how many earlier ones are left out. */
function displayWindow(messages, cap = DISPLAY_CAP) {
  const all = messages || [];
  const shown = all.slice(-cap);
  return { shown, hidden: all.length - shown.length };
}

/**
 * The preamble a fresh session's first turn starts with when the saved chat's
 * Claude session is not on this machine: the transcript, newest messages kept
 * first within `budget` characters, saying how many older ones it leaves out.
 * `wording` replaces the opening and closing lines (`{ opening, closing }`, each
 * optional); the hand-off file (src/first-mate-handoff.js) is its other caller.
 */
function seedPreamble(chat, budget = SEED_BUDGET, wording = {}) {
  const messages = (chat && chat.messages) || [];
  const blocks = [];
  let used = 0;
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const m = messages[i];
    const block = `${WHO[m.role] || m.role}: ${m.text}`;
    if (used + block.length > budget && blocks.length) break;
    blocks.unshift(block.length > budget ? `${block.slice(0, budget)}…` : block);
    used += block.length + 2;
  }
  const left = messages.length - blocks.length;
  const title = chat && chat.title ? ` "${chat.title}"` : "";
  const opening = wording.opening || `[Restored saved chat${title}. Its Claude session was on another machine, so this is a new session. ` +
    "The conversation so far, for context; continue from it:]";
  const closing = wording.closing || "[End of the restored conversation. The user's new message follows.]";
  return [
    opening,
    left > 0 ? `(${left} older message${left === 1 ? "" : "s"} left out)` : null,
    ...blocks,
    closing,
  ].filter(Boolean).join("\n\n");
}

/** Whether Claude session `id` can be resumed here: `~/.claude/projects/<slug of cwd>/<id>.jsonl` exists. */
function sessionFileExists(cwd, id, { home = os.homedir(), fs: fsImpl = fs } = {}) {
  if (!id || !/^[A-Za-z0-9-]+$/.test(String(id))) return false;
  try {
    return fsImpl.existsSync(path.join(home, ".claude", "projects", claudeProjectSlug(cwd), `${id}.jsonl`));
  } catch {
    return false;
  }
}

/**
 * Whether a saved chat resumes on this machine, and why not: `{ resumable,
 * why }`, `why` "no session id" or "saved on another machine" when it does not.
 */
function resumability(chat, cwd, opts) {
  if (!chat || !chat.claudeSessionId) return { resumable: false, why: "no session id" };
  // A conversation that ran in a session pod (#2523): its session file is in the pod, so it seeds.
  if (chat.runtime === "kubernetes") return { resumable: false, mode: "seed", why: "its session file is in the pod" };
  if (!sessionFileExists(cwd, chat.claudeSessionId, opts)) return { resumable: false, why: "saved on another machine" };
  return { resumable: true, why: null };
}

/** What the Saved Chats view draws: the chat, its summary, the display window and resumability. */
function savedChatView(chat, cwd, opts) {
  return { ...chat, summary: summarize(chat), window: displayWindow(chat.messages), ...resumability(chat, cwd, opts) };
}

module.exports = {
  DISPLAY_CAP, SEED_BUDGET, TITLE_MAX, UNTITLED,
  chatTitle, summarize, displayWindow, seedPreamble, sessionFileExists, resumability, savedChatView,
};
