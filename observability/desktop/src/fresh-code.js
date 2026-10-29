// fresh-code.js — First mate's fresh clones (#960, built in part 2 of #973). Main keeps one detached whole
// clone per target repo under `<userData>/first-mate/fresh/<owner>/<name>/`,
// made and moved with plain `git` against the engine's read-only git proxy
// (`<engine URL>/repos/<owner>/<name>/git`, engine/README.md: `info/refs` and
// `git-upload-pack` forwarded with the repo's token, `receive-pack` 403), so
// the desktop carries no GitHub credential and names no GitHub host. First
// mate runs its `claude` child in the clone of the conversation's repo
// (src/first-mate.js), and main appends the clone's `CLAUDE.md` and `AGENTS.md`
// to its system prompt (`readRepoInstructions`, src/claude-session.js).
//
// When the clones move: on the stream, never on a timer. Two triggers, both
// from the `EngineEventStream` main holds (src/engine-events.js):
//   * a `push` frame with `default: true` (`onPush`): frames for one repo are
//     coalesced over COALESCE_MS, then one `git fetch` and
//     `git checkout --detach <after>`;
//   * every connect's `hello` (`onHello`): one fetch per repo `GET /repos`
//     lists, a checkout where the head moved, a clone where the directory is
//     missing. This fills the directory at first launch and catches a push
//     delivered while the app was closed or the stream was down.
// A failure (engine down, GitHub 502) leaves the clone where it was and
// records `error` on that repo in `fresh.json`; nothing retries until the next
// frame or hello. There is no interval timer here and no retry timer
// (test/engine-events.test.js asserts it for every module that names the proxy).
//
// `fresh.json`, beside the clones:
//   { updated_at, repos: { "<owner>/<name>": { commit, branch, fetched_at, error? } },
//     sweep: [ "<owner>/<name>" ], migrated_from }
// `sweep` names clones of removed repos still in use when they were removed
// (a conversation's cwd, or a child running in them), swept at a later hello;
// `migrated_from` records the one-time copy of `~/.claude/projects/<slug of
// REPO_DIR>/` to the frakworks clone's slug (`migrateProjectDir`).
//
// Pure where it can be: every side effect is injected (`spawn`, `fs`, the
// clock), nothing here imports Electron, and main.js is the one caller.
"use strict";

const { EventEmitter } = require("node:events");
const crypto = require("node:crypto");
const nodeFs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn: nodeSpawn } = require("node:child_process");

const { claudeProjectSlug } = require("./claude-session");
const { REPO_FULL } = require("./engine-data");

const FRESH_FILE = "fresh.json";
// Pushes for one repo within this window cost one fetch.
const COALESCE_MS = 1000;
// The instruction files appended to First mate's prompt, and the most
// characters of each that are.
const INSTRUCTION_FILES = Object.freeze(["CLAUDE.md", "AGENTS.md"]);
const INSTRUCTION_CAP = 32000;
// `git` never asks the user's credential helper for the proxy: the engine
// authenticates to GitHub, the desktop to the engine with its bearer token
// (`gitEnv`), like every other engine call.
const GIT_BASE = Object.freeze(["-c", "credential.helper="]);

/**
 * git's environment: no terminal prompt and, while signed in, the engine's
 * `Authorization` header as `http.extraHeader`, passed through
 * `GIT_CONFIG_COUNT` so the token is in no argv and in no clone's config.
 */
function gitEnv(base, bearer) {
  const env = { ...base, GIT_TERMINAL_PROMPT: "0" };
  if (bearer) Object.assign(env, { GIT_CONFIG_COUNT: "1", GIT_CONFIG_KEY_0: "http.extraHeader", GIT_CONFIG_VALUE_0: `Authorization: ${bearer}` });
  return env;
}

/** The git proxy URL of `repo` on `engineUrl`: `<engineUrl>/repos/<owner>/<name>/git`. */
function proxyUrl(engineUrl, repo) {
  const [owner, name] = String(repo).split("/");
  return `${String(engineUrl).replace(/\/+$/, "")}/repos/${encodeURIComponent(owner)}/${encodeURIComponent(name)}/git`;
}

/** `<freshDir>/<owner>/<name>`. */
const cloneDir = (freshDir, repo) => path.join(freshDir, ...String(repo).split("/"));

/**
 * The cwd of a saved chat on `repo` (its engine-supplied `repo`, which anyone
 * with a GitHub login may have shaped): the clone `<freshDir>/<owner>/<name>`
 * when `repo` is a plain `owner/name`, else `freshDir` itself (All repos). A
 * repo with `..` segments, more than one `/` or any other character never
 * becomes a path, so a saved chat cannot move First mate's child or the
 * hand-off's `cd` outside `freshDir`.
 */
function savedChatDir(freshDir, repo) {
  return repo && REPO_FULL.test(String(repo)) ? cloneDir(freshDir, repo) : freshDir;
}

/** The `owner/name` whose clone `dir` is under `freshDir`, or null (`freshDir` itself, or anything else). */
function repoOfDir(freshDir, dir) {
  if (!freshDir || !dir) return null;
  const rel = path.relative(path.resolve(freshDir), path.resolve(dir));
  const parts = rel.split(path.sep);
  if (parts.length !== 2 || parts[0].startsWith("..") || !REPO_FULL.test(parts.join("/"))) return null;
  return parts.join("/");
}

/** git's blob id of `text`: sha1 of `blob <bytes>\0<bytes>`. */
function blobSha(buf) {
  const bytes = Buffer.isBuffer(buf) ? buf : Buffer.from(String(buf), "utf8");
  return crypto.createHash("sha1").update(`blob ${bytes.length}\0`).update(bytes).digest("hex");
}

/** The default branch in `git ls-remote --symref <url> HEAD`'s output, or null. */
function parseSymref(out) {
  const m = /^ref: refs\/heads\/(\S+)\tHEAD$/m.exec(String(out || ""));
  return m ? m[1] : null;
}

/**
 * The clone keeper. Options: `freshDir` (required), `engineUrl` (the base the
 * proxy is on), and for tests `spawn`, `fs`, `now`, `setTimeout`, `clearTimeout`,
 * `home` (for `migrateProjectDir`), `log`. Events: `"ready"` `{ repo, commit }`
 * after a first clone, `"updated"` `{ repo, commit, instructionsChanged }` after
 * a checkout moved a clone. Errors are recorded in `fresh.json`, never thrown.
 */
class FreshCode extends EventEmitter {
  constructor({ freshDir, engineUrl, bearer, spawn, fs, now, setTimeout: st, clearTimeout: ct, home, log } = {}) {
    super();
    if (!freshDir) throw new Error("fresh-code needs a freshDir");
    this.freshDir = freshDir;
    this.engineUrl = engineUrl || null;
    // `() => "Bearer <token>" | null`, read per git run so a sign-in applies at once.
    this.bearerFn = typeof bearer === "function" ? bearer : () => null;
    this.spawnFn = spawn || nodeSpawn;
    this.fs = fs || nodeFs;
    this.now = now || (() => Date.now());
    this.setTimeoutFn = st || ((fn, ms) => { const t = setTimeout(fn, ms); if (t.unref) t.unref(); return t; });
    this.clearTimeoutFn = ct || clearTimeout;
    this.home = home || os.homedir();
    this.log = log || ((line) => console.log(`[fresh-code] ${line}`));
    // repo -> the promise of the git work in flight for it, so a push during a
    // hello fetch waits instead of racing a second fetch in the same clone.
    this.chains = new Map();
    // repo -> the coalescing timer of its pending push, and the `after` it carries.
    this.pending = new Map();
    this.cloning = new Set();
    this.doc = this._load();
  }

  // ---------------------------------------------------------------------------
  // fresh.json
  // ---------------------------------------------------------------------------

  _file() { return path.join(this.freshDir, FRESH_FILE); }

  _load() {
    let doc = null;
    try { doc = JSON.parse(this.fs.readFileSync(this._file(), "utf8")); } catch { doc = null; }
    if (!doc || typeof doc !== "object") doc = {};
    return {
      updated_at: doc.updated_at || null,
      repos: doc.repos && typeof doc.repos === "object" ? doc.repos : {},
      sweep: Array.isArray(doc.sweep) ? doc.sweep.filter((r) => REPO_FULL.test(String(r))) : [],
      migrated_from: "migrated_from" in doc ? doc.migrated_from : undefined,
      migration_skipped: doc.migration_skipped === true || undefined,
    };
  }

  // Atomic: tmp + rename, as first-mate-store.js writes its document.
  _save() {
    this.doc.updated_at = new Date(this.now()).toISOString();
    const out = { updated_at: this.doc.updated_at, repos: this.doc.repos, sweep: this.doc.sweep };
    if (this.doc.migrated_from !== undefined) out.migrated_from = this.doc.migrated_from;
    if (this.doc.migration_skipped) out.migration_skipped = true;
    const target = this._file();
    try {
      this.fs.mkdirSync(this.freshDir, { recursive: true });
      const tmp = `${target}.${process.pid}.${this.now()}.tmp`;
      this.fs.writeFileSync(tmp, `${JSON.stringify(out, null, 2)}\n`, { mode: 0o600 });
      this.fs.renameSync(tmp, target);
    } catch (e) {
      this.log(`fresh.json unwritable: ${e.message}`);
    }
  }

  _record(repo, fields) {
    const row = { ...(this.doc.repos[repo] || {}), ...fields };
    if (!("error" in fields)) delete row.error;
    this.doc.repos[repo] = row;
    this._save();
    return row;
  }

  /** Every repo's row, the sweep list and the migration record, as `fresh.json` has them. */
  state() {
    return { ...this.doc, repos: { ...this.doc.repos }, sweep: [...this.doc.sweep], cloning: [...this.cloning] };
  }

  /** The clone of `repo`: `ready`, `cloning`, `missing`, or `error` (no clone and a recorded failure). */
  status(repo) {
    if (this.cloning.has(repo)) return "cloning";
    if (this.hasClone(repo)) return "ready";
    const row = this.doc.repos[repo];
    return row && row.error ? "error" : "missing";
  }

  cloneDir(repo) { return cloneDir(this.freshDir, repo); }

  hasClone(repo) {
    try { return this.fs.existsSync(path.join(this.cloneDir(repo), ".git")); } catch { return false; }
  }

  // ---------------------------------------------------------------------------
  // git
  // ---------------------------------------------------------------------------

  // One git command: `{ code, stdout, stderr }`, never a rejection.
  _git(args, cwd) {
    return new Promise((resolve) => {
      let child;
      try {
        child = this.spawnFn("git", [...GIT_BASE, ...args], { cwd, stdio: ["ignore", "pipe", "pipe"], env: gitEnv(process.env, this.bearerFn()) });
      } catch (e) {
        resolve({ code: -1, stdout: "", stderr: e.message });
        return;
      }
      let stdout = "";
      let stderr = "";
      if (child.stdout) child.stdout.on("data", (d) => { stdout += d; });
      if (child.stderr) child.stderr.on("data", (d) => { stderr += d; });
      child.on("error", (e) => resolve({ code: -1, stdout, stderr: `${stderr}${e.message}` }));
      child.on("close", (code) => resolve({ code: code == null ? -1 : code, stdout, stderr }));
    });
  }

  // Serialises every git run on one repo.
  _queue(repo, work) {
    const prev = this.chains.get(repo) || Promise.resolve();
    const next = prev.catch(() => {}).then(work);
    this.chains.set(repo, next);
    next.finally(() => { if (this.chains.get(repo) === next) this.chains.delete(repo); }).catch(() => {});
    return next;
  }

  _fail(repo, what, r) {
    const msg = `${what}: ${(r.stderr || r.stdout || `exit ${r.code}`).trim().split("\n").pop()}`;
    this.log(`${repo}: ${msg}`);
    return this._record(repo, { error: msg, fetched_at: this.doc.repos[repo] ? this.doc.repos[repo].fetched_at : null });
  }

  // ---------------------------------------------------------------------------
  // ensureClone
  // ---------------------------------------------------------------------------

  /**
   * The clone of `repo`, made if missing: `ls-remote --symref` for the default
   * branch, a whole single-branch clone with no tags through the proxy, then a
   * detached checkout of `origin/<default>` so no local branch exists to commit
   * on. Resolves to the repo's row (`{ commit, branch, fetched_at }`, or
   * `{ error }` with a half-made directory removed). One clone at a time per repo.
   */
  ensureClone(repo) {
    if (!REPO_FULL.test(String(repo))) return Promise.resolve({ error: `not an owner/name repo: ${JSON.stringify(repo)}` });
    if (this.hasClone(repo)) return Promise.resolve(this.doc.repos[repo] || { commit: null });
    if (!this.engineUrl) return Promise.resolve({ error: "no engine URL set: enter one under Config > frakworks > Engine URL" });
    this.cloning.add(repo);
    return this._queue(repo, () => this._clone(repo));
  }

  async _clone(repo) {
    if (this.hasClone(repo)) { this.cloning.delete(repo); return this.doc.repos[repo] || { commit: null }; }
    const dir = this.cloneDir(repo);
    const url = proxyUrl(this.engineUrl, repo);
    try {
      const sym = await this._git(["ls-remote", "--symref", url, "HEAD"]);
      const branch = sym.code === 0 ? parseSymref(sym.stdout) : null;
      if (!branch) return this._fail(repo, "clone (ls-remote)", sym.code === 0 ? { stderr: "no default branch in the advertisement" } : sym);
      this.fs.mkdirSync(path.dirname(dir), { recursive: true });
      const c = await this._git(["clone", "--single-branch", "--branch", branch, "--no-tags", url, dir]);
      if (c.code !== 0) {
        try { this.fs.rmSync(dir, { recursive: true, force: true }); } catch { /* best effort */ }
        return this._fail(repo, "clone", c);
      }
      const d = await this._git(["checkout", "--detach", `origin/${branch}`], dir);
      if (d.code !== 0) return this._fail(repo, "checkout", d);
      const head = await this._git(["rev-parse", "HEAD"], dir);
      const commit = head.code === 0 ? head.stdout.trim() : null;
      const row = this._record(repo, { commit, branch, fetched_at: new Date(this.now()).toISOString() });
      this.log(`${repo}: cloned at ${commit}`);
      this.emit("ready", { repo, commit });
      return row;
    } finally {
      this.cloning.delete(repo);
    }
  }

  // ---------------------------------------------------------------------------
  // onPush, onHello, onRepoRemoved
  // ---------------------------------------------------------------------------

  /**
   * A `push` frame's data. `default: false` is ignored, so is a repo with no
   * clone yet (the next hello clones it). Frames for one repo within COALESCE_MS
   * run one fetch and one `checkout --detach <after>` of the last one's `after`.
   */
  onPush(data) {
    if (!data || data.default !== true || !REPO_FULL.test(String(data.repo))) return false;
    const repo = data.repo;
    if (!this.hasClone(repo)) return false;
    const p = this.pending.get(repo);
    if (p) this.clearTimeoutFn(p.timer);
    const after = typeof data.after === "string" && /^[0-9a-f]{7,64}$/.test(data.after) ? data.after : null;
    const timer = this.setTimeoutFn(() => {
      this.pending.delete(repo);
      this._queue(repo, () => this._refresh(repo, after));
    }, COALESCE_MS);
    this.pending.set(repo, { timer, after });
    return true;
  }

  // One fetch of the clone's branch, a detached checkout of `after` (or of
  // `origin/<branch>` when `after` is null or not fetched), fresh.json after.
  // A failure keeps the clone and records `error`. `onlyIfMoved`: hello's
  // rule, a checkout only when `origin/<branch>` is not HEAD already.
  async _refresh(repo, after, { onlyIfMoved = false } = {}) {
    const dir = this.cloneDir(repo);
    if (!this.hasClone(repo) || !this.engineUrl) return null;
    const before = this._instructionKey(dir);
    const row = this.doc.repos[repo] || {};
    const branch = row.branch || (await this._defaultBranch(dir));
    // The clone's origin follows the engine: one made under an earlier
    // engine URL (the local Compose stack) would otherwise fetch from a port
    // nothing listens on, forever.
    await this._git(["remote", "set-url", "origin", proxyUrl(this.engineUrl, repo)], dir);
    const f = await this._git(["fetch", "origin", ...(branch ? [branch] : [])], dir);
    if (f.code !== 0) return this._fail(repo, "fetch", f);
    const target = branch ? `origin/${branch}` : "FETCH_HEAD";
    if (onlyIfMoved) {
      const head = await this._git(["rev-parse", "HEAD", target], dir);
      const [h, t] = head.stdout.trim().split("\n");
      if (head.code === 0 && h && h === t) {
        return this._record(repo, { commit: h, branch: branch || row.branch || null, fetched_at: new Date(this.now()).toISOString() });
      }
    }
    let d = after ? await this._git(["checkout", "--detach", after], dir) : { code: 1 };
    if (d.code !== 0) d = await this._git(["checkout", "--detach", target], dir);
    if (d.code !== 0) return this._fail(repo, "checkout", d);
    const head = await this._git(["rev-parse", "HEAD"], dir);
    const commit = head.code === 0 ? head.stdout.trim() : null;
    const out = this._record(repo, { commit, branch: branch || row.branch || null, fetched_at: new Date(this.now()).toISOString() });
    const instructionsChanged = this._instructionKey(dir) !== before;
    this.log(`${repo}: at ${commit}${instructionsChanged ? " (instructions changed)" : ""}`);
    this.emit("updated", { repo, commit, instructionsChanged });
    return out;
  }

  async _defaultBranch(dir) {
    const r = await this._git(["symbolic-ref", "--short", "refs/remotes/origin/HEAD"], dir);
    return r.code === 0 ? r.stdout.trim().replace(/^origin\//, "") : null;
  }

  /**
   * The snapshot a connect's `hello` asks of every consumer: `repos` is what
   * `GET /repos` lists. One fetch per repo with a clone (a checkout only where
   * the head moved), a clone where missing; then the sweep list, for clones
   * `onRepoRemoved` could not remove at the time (`inUse(dir)` says whether one
   * still is). Resolves when every repo is done.
   */
  async onHello(repos, { inUse } = {}) {
    const list = (Array.isArray(repos) ? repos : []).filter((r) => REPO_FULL.test(String(r)));
    await Promise.all(list.map((repo) => (this.hasClone(repo)
      ? this._queue(repo, () => this._refresh(repo, null, { onlyIfMoved: true }))
      : this.ensureClone(repo))));
    for (const repo of [...this.doc.sweep]) {
      if (list.includes(repo)) { this.doc.sweep = this.doc.sweep.filter((r) => r !== repo); this._save(); continue; }
      this._sweep(repo, inUse);
    }
    return this.state();
  }

  /**
   * A `repo` frame with state `removed`: the clone goes now when nothing uses
   * it (`inUse(dir)`: a conversation's cwd is it, or a child runs in it), else
   * it is listed in `fresh.json`'s `sweep` for a later hello.
   */
  onRepoRemoved(repo, { inUse } = {}) {
    if (!REPO_FULL.test(String(repo))) return { error: `not an owner/name repo: ${JSON.stringify(repo)}` };
    const p = this.pending.get(repo);
    if (p) { this.clearTimeoutFn(p.timer); this.pending.delete(repo); }
    return this._sweep(repo, inUse);
  }

  _sweep(repo, inUse) {
    const dir = this.cloneDir(repo);
    let exists = false;
    try { exists = this.fs.existsSync(dir); } catch { exists = false; }
    const used = exists && typeof inUse === "function" && Boolean(inUse(dir));
    if (used) {
      if (!this.doc.sweep.includes(repo)) { this.doc.sweep.push(repo); this._save(); }
      return { swept: false, deferred: true };
    }
    if (exists) {
      try { this.fs.rmSync(dir, { recursive: true, force: true }); } catch (e) { this.log(`${repo}: could not remove the clone: ${e.message}`); return { swept: false, error: e.message }; }
    }
    delete this.doc.repos[repo];
    this.doc.sweep = this.doc.sweep.filter((r) => r !== repo);
    this._save();
    this.log(`${repo}: clone removed`);
    return { swept: true, deferred: false };
  }

  // ---------------------------------------------------------------------------
  // readRepoInstructions, migrateProjectDir
  // ---------------------------------------------------------------------------

  /**
   * `CLAUDE.md` and `AGENTS.md` at the root of clone `dir`, for the system
   * prompt: `[{ repo, file, path, text, truncated, blob, commit }]`, each text
   * cut at INSTRUCTION_CAP characters with a marker line. `blob` is git's blob
   * id of the file's bytes, `commit` the clone's detached HEAD. `[]` for a dir
   * that is no git repository (`<freshDir>/` in All repos) or has neither file.
   */
  readRepoInstructions(dir) {
    if (!dir) return [];
    const commit = this._headOf(dir);
    if (!commit) return [];
    const repo = repoOfDir(this.freshDir, dir);
    const out = [];
    for (const file of INSTRUCTION_FILES) {
      const p = path.join(dir, file);
      let buf;
      // Only a regular file inside the clone is read: a symlink committed to
      // the repo (to `~/.ssh/id_rsa`, say) is skipped, never followed, so no
      // byte outside the clone reaches First mate's prompt.
      try {
        if (!this.fs.lstatSync(p).isFile()) continue;
        buf = this.fs.readFileSync(p);
      } catch { continue; }
      const full = buf.toString("utf8");
      const truncated = full.length > INSTRUCTION_CAP;
      const text = truncated ? `${full.slice(0, INSTRUCTION_CAP)}\n[cut at ${INSTRUCTION_CAP} characters; the file is longer]` : full;
      out.push({ repo, file, path: p, text, truncated, blob: blobSha(buf), commit });
    }
    return out;
  }

  // `blob@blob@...`: what the manager compares between turns.
  _instructionKey(dir) {
    return this.readRepoInstructions(dir).map((i) => `${i.file}=${i.blob}`).join("@");
  }

  // The detached HEAD of `dir` from `.git/HEAD` (a ref is followed one step), or null for a non-repo.
  _headOf(dir) {
    try {
      const head = this.fs.readFileSync(path.join(dir, ".git", "HEAD"), "utf8").trim();
      if (/^[0-9a-f]{40,64}$/.test(head)) return head;
      const m = /^ref: (\S+)$/.exec(head);
      if (!m) return null;
      try { return this.fs.readFileSync(path.join(dir, ".git", m[1]), "utf8").trim(); } catch { return "HEAD"; }
    } catch {
      return null;
    }
  }

  /**
   * The one-time copy of `~/.claude/projects/<slug of from>/` (memory and
   * session files) to `<slug of to>/`, when the target does not exist yet; the
   * source is left in place. Recorded in `fresh.json` as `migrated_from` (the
   * source path, or null when there was nothing to copy), so it runs once; an
   * existing target is never overwritten and is recorded as `migration_skipped`.
   */
  migrateProjectDir(from, to) {
    if (this.doc.migrated_from !== undefined) return { done: false, already: true, migrated_from: this.doc.migrated_from };
    const projects = path.join(this.home, ".claude", "projects");
    const source = path.join(projects, claudeProjectSlug(from));
    const target = path.join(projects, claudeProjectSlug(to));
    const exists = (p) => { try { return this.fs.existsSync(p); } catch { return false; } };
    if (!exists(source) || source === target) {
      this.doc.migrated_from = null;
      this._save();
      return { done: false, migrated_from: null };
    }
    if (exists(target)) {
      this.doc.migrated_from = null;
      this.doc.migration_skipped = true;
      this._save();
      this.log(`project dir not copied: ${target} exists`);
      return { done: false, skipped: true, source, target };
    }
    try {
      this.fs.cpSync(source, target, { recursive: true });
    } catch (e) {
      this.log(`project dir copy failed: ${e.message}`);
      return { done: false, error: e.message, source, target };
    }
    this.doc.migrated_from = source;
    this._save();
    this.log(`project dir copied: ${source} -> ${target}`);
    return { done: true, source, target };
  }

  /** Cancels pending coalescing timers (app closing). */
  close() {
    for (const p of this.pending.values()) this.clearTimeoutFn(p.timer);
    this.pending.clear();
  }
}

const createFreshCode = (opts) => new FreshCode(opts);

module.exports = {
  FreshCode, createFreshCode, proxyUrl, cloneDir, savedChatDir, repoOfDir, blobSha, parseSymref,
  FRESH_FILE, COALESCE_MS, INSTRUCTION_FILES, INSTRUCTION_CAP,
};
