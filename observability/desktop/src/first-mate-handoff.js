// first-mate-handoff.js — "Continue in terminal" (issue #819):
// the pure side of handing a
// First mate conversation, live or saved, to a terminal Claude Code session.
// Main (src/main.js) decides the path with `resumability`, writes the hand-off
// file on the seeded path and puts the command on the clipboard; this file
// only builds the command and the file's text.
//
// Two shapes, as Saved chats (#598) decides between resume and seed:
//   * the session file is on this machine: `cd '<cwd>' && claude --resume <id>
//     --fork-session`, a fork, so First mate's own `.jsonl` is never appended to;
//   * it is not: `cd '<cwd>' && claude "$(cat '<file>')"`, the file holding a
//     header and `seedPreamble`'s transcript. The transcript never rides in the
//     command, so nothing in a message can break its quoting.
// `cwd` is the conversation's stored cwd (#960, fresh code): the fresh clone of
// its repo, where its session file and memory live; main passes it.
"use strict";

const path = require("node:path");

const { SEED_BUDGET, chatTitle, seedPreamble } = require("./saved-chats");
const { resolveTier: defaultResolveTier } = require("./engine-tiers");

const SAFE_ID = /^[A-Za-z0-9-]+$/;

const OPENING = "[Continued from a First mate conversation in the frakworks desktop app. " +
  "This is a new Claude Code session; the conversation so far follows, for context:]";
const CLOSING = "[End of the First mate conversation. Reply in one line that you have the context, " +
  "then wait for the user's next message.]";

// The hand-off header line for a conversation that ran in a session pod (#2523).
const POD_LINE = "a pod on the cluster (its session file is in the pod, so this is a new session seeded with the transcript)";

/** `s` single-quoted for sh, bash and zsh: each `'` becomes `'\''`. */
function shellQuote(s) {
  return `'${String(s).replace(/'/g, "'\\''")}'`;
}

const when = (v) => {
  if (v == null || v === "") return null;
  const d = new Date(typeof v === "number" ? v : String(v));
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
};

/** The tier as `<n> · <model> <effort>`, or `<n>` when the tier map cannot resolve it; null with none. */
function tierLine(tier, resolveTierFn = defaultResolveTier) {
  if (!Number.isInteger(tier)) return null;
  const pair = resolveTierFn(tier);
  return pair ? `${tier} · ${pair.model} ${pair.effort}` : String(tier);
}

/**
 * The hand-off file's text: a header saying where it came from (title,
 * saved-chat id, repo, tier, savedFrom, started), then `seedPreamble`'s
 * transcript, newest first within SEED_BUDGET, with the hand-off's own
 * opening line and a one-line-reply instruction at the end.
 */
function handoffText(chat, { budget = SEED_BUDGET, resolveTierFn = defaultResolveTier } = {}) {
  const c = chat || {};
  const header = [
    ["Title", c.title || chatTitle(c.messages)],
    ["Saved-chat id", c.id || null],
    ["Repo", c.repo || "All repos"],
    ["Tier", tierLine(c.tier, resolveTierFn)],
    ["Saved from", c.savedFrom || null],
    // A pod's conversation (#2523): only the seeded form exists.
    ["Ran in", c.runtime === "kubernetes" ? POD_LINE : null],
    ["Started", when(c.startedAt)],
  ].filter(([, v]) => v != null).map(([k, v]) => `${k}: ${v}`);
  return `${header.join("\n")}\n\n${seedPreamble(c, budget, { opening: OPENING, closing: CLOSING })}\n`;
}

/**
 * The command to paste: `cd '<cwd>' && claude --resume <id> --fork-session`
 * when `resumable`, otherwise `cd '<cwd>' && claude "$(cat '<file>')"`.
 * Throws on a session id that is not plain letters, digits and dashes, or a
 * seeded call without a file: main answers that as a refusal.
 */
function handoffCommand({ cwd, sessionId, resumable, file }) {
  const cd = `cd ${shellQuote(cwd)} && `;
  if (resumable) {
    if (!SAFE_ID.test(String(sessionId || ""))) throw new Error("the Claude session id is not one the command can carry");
    return `${cd}claude --resume ${sessionId} --fork-session`;
  }
  if (!file) throw new Error("no hand-off file to seed the session with");
  return `${cd}claude "$(cat ${shellQuote(file)})"`;
}

/**
 * Everything main needs for one press, without touching the disk: `{ mode,
 * command, file, text }`, `file` and `text` null on the resume path. `dir` is
 * `<userData>/first-mate/handoff`; the file is `<dir>/<chat id>.md`.
 */
function handoffPlan(chat, { cwd, dir, resumable, resolveTierFn } = {}) {
  if (chat && chat.runtime === "kubernetes") resumable = false;
  if (!chat || !Array.isArray(chat.messages) || !chat.messages.length) throw new Error("there is nothing to hand off yet");
  if (resumable) {
    return { mode: "resume", command: handoffCommand({ cwd, sessionId: chat.claudeSessionId, resumable: true }), file: null, text: null };
  }
  if (!SAFE_ID.test(String(chat.id || ""))) throw new Error("the chat has no id to name its hand-off file");
  const file = path.join(dir, `${chat.id}.md`);
  return { mode: "seed", command: handoffCommand({ cwd, resumable: false, file }), file, text: handoffText(chat, { resolveTierFn }) };
}

module.exports = { POD_LINE, OPENING, CLOSING, shellQuote, tierLine, handoffText, handoffCommand, handoffPlan };
