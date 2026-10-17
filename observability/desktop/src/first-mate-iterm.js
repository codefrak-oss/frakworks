// first-mate-iterm.js — "Open in Claude Code ⇗" (#820): the pure pieces of handing the live
// First mate conversation to a Claude Code session in a new iTerm2 window.
// (Continue in terminal, #819, which copies a command to paste, is
// src/first-mate-handoff.js; the two share nothing but shellQuote's shape.)
//
// The mechanism: main writes a tiny launcher script under the app's userData
// (`first-mate/iterm/<uuid>.sh`, mode 0700) and asks iTerm2, through its own
// scripting dictionary via `osascript`, to open a window on the default
// profile running it. The launcher deletes itself (and any seed file) as its
// first act, `cd`s to First mate's cwd (REPO_DIR: Claude Code finds a session
// by the launch cwd's project slug) and `exec`s `claude`:
//
//   * resume: `claude --resume <claude_session_id> --fork-session`, when the
//     session file is on this machine. `--fork-session` gives the terminal its
//     own session id, so First mate's conversation is untouched by what the
//     terminal does next. No `--worktree`: the user decided (#820) the session
//     starts in REPO_DIR and the repo's CLAUDE.md ("Interactive sessions work
//     in a worktree") moves it to a worktree before its first edit.
//     A Claude Code session from Saved Chats > Claude Code (#1937) resumes
//     as itself instead (`fork: false`): the session is the user's own and
//     continuing it is the point.
//   * seed: `claude "$seed"`, the transcript preamble read from a `<uuid>.md`
//     (mode 0600) into a variable before both files are removed, when there is
//     no session id or its file is not here.
//
// Every value in the script is a literal with single-quote shell escaping;
// nothing is interpolated by the shell. No file is ever shown to the user.
//
// Pure functions with `fs`, `execFile`, `platform`, `home` and `env`
// injectable, like src/iterm-font.js. FirstMateManager (src/first-mate.js)
// decides resume-or-seed and calls these in order: writeHandoff, openInIterm.
"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { execFile } = require("node:child_process");

const NOT_MAC = "Only on macOS: iTerm2 is a Mac app.";
const NOT_INSTALLED = "iTerm2 is not installed (looked in /Applications and ~/Applications).";
const AUTOMATION_REFUSED = "macOS refused automation of iTerm2. Allow it in System Settings > Privacy & Security > Automation.";
const OSASCRIPT_TIMEOUT_MS = 10000;
// A launcher that never ran (iTerm2 quit before the window opened) is swept
// from the launcher directory at the next startup once it is this old. The
// directory is `first-mate/iterm`, not `first-mate/handoff`: that one holds
// Continue in terminal's seed files, which the user's pasted command reads
// later and the sweep must not touch.
const HANDOFF_SWEEP_MS = 3600000;
const HANDOFF_DIR_NAME = "iterm";

/**
 * Whether iTerm2 can be driven here: `{ available, why, app }`. macOS only;
 * `AGENT_ARMY_ITERM_APP` names the app bundle, else `/Applications/iTerm.app`
 * or `~/Applications/iTerm.app`. `why` is the button's disabled title.
 */
function detectIterm({ platform = process.platform, home = os.homedir(), env = process.env, fs: fsImpl = fs } = {}) {
  if (platform !== "darwin") return { available: false, why: NOT_MAC, app: null };
  const exists = (p) => { try { return fsImpl.existsSync(p); } catch { return false; } };
  const override = env && env.AGENT_ARMY_ITERM_APP;
  if (override) {
    return exists(override)
      ? { available: true, why: null, app: override }
      : { available: false, why: `iTerm2 is not at ${override} (AGENT_ARMY_ITERM_APP).`, app: null };
  }
  for (const app of ["/Applications/iTerm.app", path.join(home, "Applications", "iTerm.app")]) {
    if (exists(app)) return { available: true, why: null, app };
  }
  return { available: false, why: NOT_INSTALLED, app: null };
}

/** `s` as one single-quoted shell word: `'` becomes `'\''`. */
function shellQuote(s) {
  return `'${String(s).replace(/'/g, "'\\''")}'`;
}

/**
 * The launcher's text. `resumeId` set: the resume script, with
 * `--fork-session` unless `fork` is false; else `seedPath` must be set: the
 * seed script. `rm -f` comes before anything can fail.
 */
function launcherScript({ claude, cwd, resumeId, seedPath, fork = true }) {
  if (!claude || !cwd) throw new Error("launcherScript needs claude and cwd");
  if (resumeId) {
    return [
      "#!/bin/sh",
      'rm -f -- "$0"',
      `cd ${shellQuote(cwd)} || exit 1`,
      `exec ${shellQuote(claude)} --resume ${shellQuote(resumeId)}${fork ? " --fork-session" : ""}`,
      "",
    ].join("\n");
  }
  if (!seedPath) throw new Error("launcherScript needs a resumeId or a seedPath");
  return [
    "#!/bin/sh",
    `seed=$(cat ${shellQuote(seedPath)})`,
    `rm -f -- "$0" ${shellQuote(seedPath)}`,
    `cd ${shellQuote(cwd)} || exit 1`,
    `exec ${shellQuote(claude)} "$seed"`,
    "",
  ].join("\n");
}

// A string inside an AppleScript string literal: backslash and double quote escaped.
const appleScriptString = (s) => String(s).replace(/\\/g, "\\\\").replace(/"/g, '\\"');

/** The AppleScript that opens one new iTerm2 window on the default profile running the launcher. */
function itermScript(launcherPath) {
  const command = `/bin/sh ${shellQuote(launcherPath)}`;
  return [
    'tell application "iTerm2"',
    "  activate",
    `  create window with default profile command "${appleScriptString(command)}"`,
    "end tell",
    "",
  ].join("\n");
}

/**
 * Write the launcher (and the seed, in seed mode) under `dir`, created 0700
 * if missing: `{ launcherPath, seedPath }`. `plan` is `{ mode, resumeId,
 * seed, claude, cwd, fork }`; `fork: false` resumes without `--fork-session`.
 */
function writeHandoff(dir, plan, { fs: fsImpl = fs, uuid = () => crypto.randomUUID() } = {}) {
  fsImpl.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const id = uuid();
  const launcherPath = path.join(dir, `${id}.sh`);
  const seedPath = plan.mode === "seed" ? path.join(dir, `${id}.md`) : null;
  if (seedPath) fsImpl.writeFileSync(seedPath, String(plan.seed || ""), { mode: 0o600 });
  const script = launcherScript({ claude: plan.claude, cwd: plan.cwd,
    resumeId: plan.mode === "resume" ? plan.resumeId : null, seedPath, fork: plan.fork !== false });
  fsImpl.writeFileSync(launcherPath, script, { mode: 0o700 });
  return { launcherPath, seedPath };
}

/** Remove the handoff files; a missing one is fine. */
function removeHandoff({ launcherPath, seedPath }, fsImpl = fs) {
  for (const p of [launcherPath, seedPath]) {
    if (!p) continue;
    try { fsImpl.rmSync(p, { force: true }); } catch { /* already gone */ }
  }
}

const runOsascript = (script, timeoutMs) => new Promise((resolve) => {
  execFile("osascript", ["-e", script], { timeout: timeoutMs, maxBuffer: 1024 * 1024 },
    (err, stdout, stderr) => resolve({ err, stdout: String(stdout || ""), stderr: String(stderr || "") }));
});

/** The reason an osascript failure is shown as. */
function osascriptError({ err, stderr }) {
  const text = String(stderr || "");
  if (/-1743\b/.test(text) || /not allowed to send keystrokes|not permitted/i.test(text)) return AUTOMATION_REFUSED;
  if (err && err.killed) return `iTerm2 did not answer within ${Math.round(OSASCRIPT_TIMEOUT_MS / 1000)} s.`;
  const line = text.split("\n").map((l) => l.trim()).find(Boolean);
  if (line) return line;
  if (err && err.code === "ENOENT") return "osascript is not on this machine.";
  return (err && err.message) || "osascript failed";
}

/**
 * Ask iTerm2 to open a window running `launcherPath`: `{ ok: true }` or
 * `{ ok: false, error }`. On failure the launcher never ran, so it could not
 * delete itself: the launcher and `seedPath` are removed here.
 */
async function openInIterm(launcherPath, { seedPath = null, execFile: run = runOsascript, timeoutMs = OSASCRIPT_TIMEOUT_MS, fs: fsImpl = fs } = {}) {
  let r;
  try {
    r = await run(itermScript(launcherPath), timeoutMs);
  } catch (e) {
    r = { err: e, stderr: "" };
  }
  if (!r || !r.err) return { ok: true };
  removeHandoff({ launcherPath, seedPath }, fsImpl);
  return { ok: false, error: osascriptError(r) };
}

/**
 * Remove every file under `dir` whose mtime is older than `olderThanMs`
 * before `now`. A missing directory, or a file that goes meanwhile, is fine.
 */
function sweepHandoffDir(dir, olderThanMs = HANDOFF_SWEEP_MS, { fs: fsImpl = fs, now = () => Date.now() } = {}) {
  let names;
  try { names = fsImpl.readdirSync(dir); } catch { return []; }
  const cutoff = now() - olderThanMs;
  const removed = [];
  for (const name of names) {
    const p = path.join(dir, name);
    try {
      const st = fsImpl.statSync(p);
      if (!st.isFile() || st.mtimeMs > cutoff) continue;
      fsImpl.rmSync(p, { force: true });
      removed.push(p);
    } catch { /* gone, or unreadable: leave it */ }
  }
  return removed;
}

module.exports = {
  NOT_MAC, NOT_INSTALLED, AUTOMATION_REFUSED, OSASCRIPT_TIMEOUT_MS, HANDOFF_SWEEP_MS, HANDOFF_DIR_NAME,
  detectIterm, shellQuote, launcherScript, itermScript, writeHandoff, removeHandoff, osascriptError,
  openInIterm, sweepHandoffDir,
};
