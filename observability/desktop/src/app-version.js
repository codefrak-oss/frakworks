// app-version.js — what code the app is running, and whether the checkout, or
// GitHub's main, has moved on from it. The app runs straight out of the
// checkout, so its version is the HEAD it was started on; no build stamp
// needed. Main process only: every call here is `git` in the repo the app was
// started from.
//
// Local facts, kept apart (the status bar shows both):
//   - `ahead`: commits the checkout's HEAD has that the running one does not;
//   - `desktop`: whether any of them touched this app (APP_PATH). A pull that
//     only brought engine/ changes moves HEAD but does not make the running
//     app old.
// And the remote fact, `origin`: commits on origin/main the checkout lacks
// (`behind`), whether they touch this app, and why a fast-forward would be
// refused if it would. `stale` (the flag that lights Restart) is true when
// either the checkout or origin/main has desktop changes the running app
// lacks.
//
// The app fetches origin/main (decided with the user 2026-09-26, replacing the
// 2026-09-24 "all local git, nothing fetched" rule): `fetchOrigin()` on its
// own slow interval (FETCH_MS), with the user's own git credentials. That
// fetch and the fast-forward in `pull()` are the app's only direct git contact
// with GitHub; reads of GitHub data still go only through the engine. A pull
// changes the checkout under anything else working in it, so an external
// Claude Code session belongs in a worktree, not in this checkout.
//
// `rebuild()` is the app's one write: the fast-forward if asked (refused, never
// forced, when the checkout is off main, dirty or diverged), `npm ci` if the
// lockfile moved, then the renderer build. The relaunch itself is the caller's
// (src/main.js).
"use strict";
const path = require("node:path");
const child_process = require("node:child_process");

// This app's directory relative to the repo root: the paths a commit must touch
// to make the running app stale.
const APP_PATH = "observability/desktop/";
// Local git only, so the bar can look often.
const CHECK_MS = 3000;
// The fetch reaches GitHub, so it runs far less often than the local check,
// and gives up after FETCH_TIMEOUT_MS.
const FETCH_MS = 60000;
const FETCH_TIMEOUT_MS = 30000;
// The branch the app follows, and its remote-tracking ref.
const REMOTE = "origin";
const BRANCH = "main";
const REMOTE_REF = `${REMOTE}/${BRANCH}`;

const run = (cmd, args, { cwd, exec = child_process.execFile, timeoutMs = 60000, env } = {}) =>
  new Promise((resolve, reject) => {
    const opts = { cwd, timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024 };
    if (env) opts.env = env;
    exec(cmd, args, opts, (err, stdout, stderr) => {
      if (err) reject(Object.assign(new Error(`${cmd} ${args.join(" ")}: ${(stderr || err.message || "").toString().trim()}`), { code: err.code }));
      else resolve(String(stdout || ""));
    });
  });

/** The repo root the app runs from, or null when it does not run from a checkout (a packaged .app). */
async function repoRoot({ from = __dirname, exec } = {}) {
  try { return (await run("git", ["rev-parse", "--show-toplevel"], { cwd: from, exec })).trim(); }
  catch (e) { return null; }
}

/**
 * The checkout now: { sha, short, branch, dirty }, or { error }. `dirty`
 * counts tracked changes only.
 */
async function readVersion({ repoDir, exec } = {}) {
  if (!repoDir) return { error: "not running from a git checkout" };
  try {
    const sha = (await run("git", ["rev-parse", "HEAD"], { cwd: repoDir, exec })).trim();
    const branch = (await run("git", ["rev-parse", "--abbrev-ref", "HEAD"], { cwd: repoDir, exec })).trim();
    const status = await run("git", ["status", "--porcelain", "--untracked-files=no"], { cwd: repoDir, exec });
    return { sha, short: sha.slice(0, 7), branch, dirty: status.trim().length > 0 };
  } catch (e) { return { error: e.message }; }
}

/**
 * `git fetch origin main` with the user's own git configuration: { at } or
 * { error }. Never throws, never prompts (no terminal: GIT_TERMINAL_PROMPT=0),
 * and gives up after FETCH_TIMEOUT_MS, so offline or an expired credential
 * costs the remote fact only.
 */
async function fetchOrigin({ repoDir, exec, now = Date.now } = {}) {
  if (!repoDir) return { error: "not running from a git checkout" };
  try {
    await run("git", ["fetch", "--quiet", REMOTE, BRANCH],
      { cwd: repoDir, exec, timeoutMs: FETCH_TIMEOUT_MS, env: { ...process.env, GIT_TERMINAL_PROMPT: "0" } });
    return { at: now() };
  } catch (e) { return { error: e.message }; }
}

/**
 * Why a fast-forward of the checkout to origin/main would be refused, or null
 * when it would not: off main, tracked uncommitted changes, or local commits
 * origin/main does not have. Reads only.
 */
async function pullBlocker({ repoDir, exec } = {}) {
  const v = await readVersion({ repoDir, exec });
  if (v.error) return v.error;
  if (v.branch !== BRANCH) return `the checkout is on ${v.branch}, not ${BRANCH}`;
  if (v.dirty) return "the checkout has uncommitted changes";
  try { await run("git", ["merge-base", "--is-ancestor", "HEAD", REMOTE_REF], { cwd: repoDir, exec }); }
  catch (e) {
    return e.code === 1 ? `local commits on ${BRANCH} are not on ${REMOTE_REF}: no fast-forward` : e.message;
  }
  return null;
}

const touched = (out) => out.split("\n").map((s) => s.trim()).filter(Boolean);
const tops = (files) => [...new Set(files.map((f) => f.split("/")[0]))].sort();

/**
 * origin/main against the checkout and the running commit: { sha, behind,
 * stale, changed, blocked } — `behind` the commits origin/main has that HEAD
 * lacks; `stale`/`changed` what origin/main changed since it and the running
 * commit parted (a three-dot diff, so a local commit is not counted as
 * origin's); `blocked` why a pull would be refused, or null.
 */
async function compareOrigin({ repoDir, running, head, exec }) {
  const sha = (await run("git", ["rev-parse", "--verify", `${REMOTE_REF}^{commit}`], { cwd: repoDir, exec })).trim();
  const behind = Number((await run("git", ["rev-list", "--count", `${head}..${sha}`], { cwd: repoDir, exec })).trim());
  if (!behind) return { sha, behind: 0, stale: false, changed: [], blocked: null };
  const files = touched(await run("git", ["diff", "--name-only", `${running}...${sha}`], { cwd: repoDir, exec }));
  return {
    sha, behind, stale: files.some((f) => f.startsWith(APP_PATH)), changed: tops(files),
    blocked: await pullBlocker({ repoDir, exec }),
  };
}

/**
 * The checkout's HEAD against the commit the app started on: { ahead,
 * desktop, stale, changed, lockMoved } — `changed` the top-level paths the
 * commits touch, so the bar can say what moved ("engine"); `desktop` whether
 * APP_PATH is among them; `lockMoved` whether the app's lockfile is, so a
 * rebuild knows to `npm ci`. A HEAD that is not a descendant of `running` (a
 * branch switch) still diffs; `ahead` is then the commits HEAD has that
 * `running` lacks.
 *
 * With `remote` (main passes it), also `origin`: compareOrigin's facts, or
 * { error } when the last fetch failed (`fetchError`) or the ref cannot be
 * read — the remote part only; the local facts stand. `stale` is then
 * `desktop` or origin/main being behind-with-desktop-changes.
 */
async function compareRunning({ repoDir, running, head, remote = false, fetchError = null, exec } = {}) {
  if (!repoDir) return { error: "not running from a git checkout" };
  let cmp;
  try {
    if (!head) head = (await run("git", ["rev-parse", "HEAD"], { cwd: repoDir, exec })).trim();
    if (head === running) cmp = { ahead: 0, desktop: false, stale: false, changed: [], lockMoved: false };
    else {
      const ahead = Number((await run("git", ["rev-list", "--count", `${running}..${head}`], { cwd: repoDir, exec })).trim());
      const files = touched(await run("git", ["diff", "--name-only", running, head], { cwd: repoDir, exec }));
      const desktop = files.some((f) => f.startsWith(APP_PATH));
      cmp = { ahead, desktop, stale: desktop, changed: tops(files), lockMoved: files.includes(`${APP_PATH}package-lock.json`) };
    }
  } catch (e) { return { error: e.message }; }
  if (!remote) return cmp;
  if (fetchError) cmp.origin = { error: `fetch: ${fetchError}` };
  else {
    try { cmp.origin = await compareOrigin({ repoDir, running, head, exec }); }
    catch (e) { cmp.origin = { error: e.message }; }
  }
  if (cmp.origin.behind && cmp.origin.stale) cmp.stale = true;
  return cmp;
}

/** The one line the bar shows. Pure, so it is tested without git; status-bar.jsx mirrors it. */
function describe(version, running, cmp) {
  if (!version || version.error) return { text: version && version.error ? version.error : "version unknown", level: "muted" };
  let text = `${version.branch} · ${version.short}`;
  if (version.dirty) text += " · dirty";
  if (!cmp || cmp.error) return { text: `${text} · ${cmp && cmp.error ? cmp.error : "checking…"}`, level: "muted" };
  const o = cmp.origin && !cmp.origin.error && cmp.origin.behind ? cmp.origin : null;
  const behind = o
    ? ` · behind origin ${o.behind} (${o.stale ? "desktop changed" : o.changed.join(", ") || "no files"}${o.blocked ? `; won't pull: ${o.blocked}` : ""})`
    : "";
  const level = cmp.stale ? "stale" : o ? "muted" : "ok";
  if (!cmp.ahead && version.sha === running) return { text: `${text} · running${behind}`, level };
  const what = cmp.desktop ? "desktop changed" : cmp.changed.join(", ") || "no files";
  const n = cmp.ahead ? ` +${cmp.ahead}` : "";
  return { text: `${text} · running ${(running || "").slice(0, 7)}${n} (${what})${behind}`, level: cmp.stale ? "stale" : "muted" };
}

/**
 * Fast-forward the checkout to origin/main: `git merge --ff-only origin/main`,
 * the fetched ref, so no second trip to GitHub. Refuses, touching nothing,
 * when pullBlocker says so; never resets, stashes, rebases or merges.
 */
async function pull({ repoDir, exec } = {}) {
  const blocked = await pullBlocker({ repoDir, exec });
  if (blocked) throw new Error(`not pulled: ${blocked}`);
  return run("git", ["merge", "--ff-only", REMOTE_REF], { cwd: repoDir, exec });
}

/**
 * Rebuild what is on disk: { ok, steps: [{ step, ok, detail }] }. Stops at the
 * first failing step, leaving the built app as it was, so a relaunch after a
 * failure runs the same code as before. With `pull` ({ repoDir, running }),
 * first a "pull" step (pull() above), and `npm ci` then runs when the lockfile
 * moved between `running` and the new HEAD; without it, only when `lockMoved`.
 * After an `npm ci`, "repair electron" (`npm run repair:electron`) puts the
 * Electron binary back that `--ignore-scripts` left out.
 */
async function rebuild({ appDir, exec, lockMoved = false, npm = "npm", pull: pullFrom = null } = {}) {
  const steps = [];
  const step = async (name, fn) => {
    try { const detail = await fn(); steps.push({ step: name, ok: true, detail: (detail || "").trim().slice(-400) }); return true; }
    catch (e) { steps.push({ step: name, ok: false, detail: e.message.slice(-800) }); return false; }
  };
  if (pullFrom) {
    if (!await step("pull", () => pull({ repoDir: pullFrom.repoDir, exec }))) return { ok: false, steps };
    const cmp = await compareRunning({ repoDir: pullFrom.repoDir, running: pullFrom.running, exec });
    lockMoved = cmp.error ? true : cmp.lockMoved;
  }
  if (lockMoved) {
    if (!await step("npm ci", () => run(npm, ["ci", "--ignore-scripts"], { cwd: appDir, exec, timeoutMs: 300000 })))
      return { ok: false, steps };
    // `npm ci` empties node_modules and, with scripts skipped, never runs
    // Electron's postinstall, which is what downloads its binary: the next
    // launch would throw "Electron failed to install correctly" (that is how
    // the app crashed on 2026-09-26 after a lockfile change). The repair
    // script restores the binary from the local Electron cache (README, "The
    // Electron binary is missing after npm ci").
    if (!await step("repair electron", () => run(npm, ["run", "repair:electron"], { cwd: appDir, exec, timeoutMs: 120000 })))
      return { ok: false, steps };
  }
  if (!await step("build renderer", () => run(npm, ["run", "build:renderer"], { cwd: appDir, exec, timeoutMs: 120000 }))) return { ok: false, steps };
  return { ok: true, steps };
}

module.exports = {
  repoRoot, readVersion, fetchOrigin, pullBlocker, compareRunning, describe, pull, rebuild,
  APP_PATH, CHECK_MS, FETCH_MS, FETCH_TIMEOUT_MS, REMOTE_REF,
};
