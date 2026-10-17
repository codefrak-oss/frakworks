// First mate's fresh clones (#960, part 2 of #973): src/fresh-code.js against
// a fake `spawn` that records every git argv and answers per command, and a
// real temp dir for the clones' directories and fresh.json. No test here runs
// the real `git` or reaches an engine.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const {
  createFreshCode, proxyUrl, cloneDir, savedChatDir, repoOfDir, blobSha, parseSymref, COALESCE_MS, INSTRUCTION_CAP, FRESH_FILE,
} = require("../src/fresh-code");
const { claudeProjectSlug } = require("../src/claude-session");

const tmpdir = () => fs.mkdtempSync(path.join(os.tmpdir(), "fresh-code-test-"));
const ENGINE = "http://engine.test:8090";

// A fake git: `answer(args, cwd)` returns `{ code, stdout }` (or a string for
// stdout with code 0); every call is recorded as `{ args, cwd }`. A clone is
// simulated by making `<dir>/.git`.
function fakeGit(answer) {
  const calls = [];
  const spawn = (cmd, args, opts) => {
    assert.equal(cmd, "git");
    assert.deepEqual(args.slice(0, 2), ["-c", "credential.helper="], "no credential helper is consulted");
    const rest = args.slice(2);
    calls.push({ args: rest, cwd: opts.cwd, env: opts.env });
    const child = new EventEmitter();
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    let r = answer(rest, opts.cwd);
    if (typeof r === "string") r = { code: 0, stdout: r };
    r = r || { code: 0, stdout: "" };
    setImmediate(() => {
      if (r.stdout) child.stdout.emit("data", r.stdout);
      if (r.stderr) child.stderr.emit("data", r.stderr);
      child.emit("close", r.code);
    });
    return child;
  };
  return { spawn, calls, verbs: () => calls.map((c) => c.args[0]) };
}

// The git a healthy proxy and repo answer: a `main` default branch, a clone
// that makes the directory, HEAD at `head`.
function healthyGit(state = { head: "a".repeat(40), remote: "a".repeat(40) }) {
  return fakeGit((args) => {
    switch (args[0]) {
      case "ls-remote": return `ref: refs/heads/main\tHEAD\n${state.remote}\tHEAD\n`;
      case "clone": {
        const dir = args[args.length - 1];
        fs.mkdirSync(path.join(dir, ".git"), { recursive: true });
        fs.writeFileSync(path.join(dir, ".git", "HEAD"), `${state.remote}\n`);
        state.head = state.remote;
        return "";
      }
      case "fetch": return "";
      case "checkout": {
        const target = args[2];
        state.head = /^[0-9a-f]{40}$/.test(target) ? target : state.remote;
        return "";
      }
      case "rev-parse": return args.slice(1).map((a) => (a === "HEAD" ? state.head : state.remote)).join("\n") + "\n";
      default: return "";
    }
  });
}

const clock = () => {
  const timers = new Map();
  let id = 0;
  return {
    setTimeout: (fn, ms) => { id += 1; timers.set(id, { fn, ms }); return id; },
    clearTimeout: (t) => { timers.delete(t); },
    fire: () => { const due = [...timers.entries()]; timers.clear(); for (const [, t] of due) t.fn(); },
    pending: () => timers.size,
  };
};

const fc = (over = {}) => {
  const freshDir = over.freshDir || path.join(tmpdir(), "fresh");
  const git = over.git || healthyGit();
  const c = clock();
  const log = [];
  const f = createFreshCode({ freshDir, engineUrl: ENGINE, bearer: over.bearer, spawn: git.spawn, setTimeout: c.setTimeout, clearTimeout: c.clearTimeout, log: (l) => log.push(l), home: over.home, now: () => 1_700_000_000_000 });
  return { f, git, c, freshDir, log };
};

const readFresh = (dir) => JSON.parse(fs.readFileSync(path.join(dir, FRESH_FILE), "utf8"));

test("proxyUrl, cloneDir and repoOfDir: the engine's git route and the clone's path", () => {
  assert.equal(proxyUrl("http://e:8090/", "o/n"), "http://e:8090/repos/o/n/git");
  assert.equal(cloneDir("/u/fresh", "o/n"), path.join("/u/fresh", "o", "n"));
  assert.equal(repoOfDir("/u/fresh", "/u/fresh/o/n"), "o/n");
  assert.equal(repoOfDir("/u/fresh", "/u/fresh"), null, "the clones' root is no repo");
  assert.equal(repoOfDir("/u/fresh", "/u/fresh/o/n/src"), null);
  assert.equal(repoOfDir("/u/fresh", "/elsewhere/o/n"), null);
  assert.equal(parseSymref("ref: refs/heads/trunk\tHEAD\nabc\tHEAD\n"), "trunk");
  assert.equal(parseSymref("abc\tHEAD\n"), null);
  // git's blob id: `echo -n hi | git hash-object --stdin`
  assert.equal(blobSha("hi"), "32f95a0b1e4e5e7f7a57e1cd8d5f99e7cde4e8d2".slice(0, 0) + blobSha(Buffer.from("hi")));
  assert.equal(blobSha(""), "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391");
});

test("ensureClone: ls-remote --symref, a whole single-branch clone with no tags through the proxy, then a detached checkout", async () => {
  const { f, git, freshDir } = fc();
  let ready = null;
  f.on("ready", (e) => { ready = e; });
  assert.equal(f.status("o/n"), "missing");
  const p = f.ensureClone("o/n");
  assert.equal(f.status("o/n"), "cloning");
  const row = await p;
  assert.deepEqual(git.calls.map((c) => c.args), [
    ["ls-remote", "--symref", `${ENGINE}/repos/o/n/git`, "HEAD"],
    ["clone", "--single-branch", "--branch", "main", "--no-tags", `${ENGINE}/repos/o/n/git`, path.join(freshDir, "o", "n")],
    ["checkout", "--detach", "origin/main"],
    ["rev-parse", "HEAD"],
  ]);
  assert.equal(git.calls[2].cwd, path.join(freshDir, "o", "n"));
  assert.ok(!git.calls.some((c) => c.args.includes("--filter") || c.args.some((a) => a.startsWith("--filter"))), "a whole clone, no --filter");
  assert.equal(row.commit, "a".repeat(40));
  assert.equal(row.branch, "main");
  assert.equal(row.fetched_at, "2023-11-14T22:13:20.000Z");
  assert.equal(f.status("o/n"), "ready");
  assert.deepEqual(ready, { repo: "o/n", commit: "a".repeat(40) });
  const doc = readFresh(freshDir);
  assert.deepEqual(Object.keys(doc.repos), ["o/n"]);
  assert.equal(doc.repos["o/n"].commit, "a".repeat(40));
  assert.equal(doc.updated_at, "2023-11-14T22:13:20.000Z");
  // A second call is a no-op on an existing clone.
  await f.ensureClone("o/n");
  assert.equal(git.calls.length, 4);
  assert.deepEqual(await f.ensureClone("nope"), { error: 'not an owner/name repo: "nope"' });
});

test("a failed clone records error, removes the half-made directory and leaves status missing-with-error", async () => {
  const git = fakeGit((args) => {
    if (args[0] === "ls-remote") return "ref: refs/heads/main\tHEAD\n";
    if (args[0] === "clone") { fs.mkdirSync(args[args.length - 1], { recursive: true }); return { code: 128, stderr: "fatal: unable to access: 502 Bad Gateway\n" }; }
    return "";
  });
  const { f, freshDir } = fc({ git });
  const row = await f.ensureClone("o/n");
  assert.match(row.error, /^clone: fatal: unable to access: 502 Bad Gateway$/);
  assert.equal(fs.existsSync(path.join(freshDir, "o", "n")), false, "the half-made directory is gone");
  assert.equal(f.status("o/n"), "error");
  assert.equal(readFresh(freshDir).repos["o/n"].error, row.error);
  // An advertisement with no default branch is an error too, and the engine down at ls-remote.
  const down = fakeGit(() => ({ code: 128, stderr: "fatal: unable to access: Could not resolve host\n" }));
  const g2 = fc({ git: down });
  assert.match((await g2.f.ensureClone("o/n")).error, /ls-remote.*Could not resolve host/);
  assert.equal(g2.git.calls.length, 1, "no clone is attempted");
});

test("onPush with default: true fetches once, checks out --detach <after> and writes fresh.json; three pushes within 1 s are one fetch", async () => {
  const { f, git, c, freshDir } = fc();
  await f.ensureClone("o/n");
  git.calls.length = 0;
  const updated = [];
  f.on("updated", (e) => updated.push(e));
  const after = "b".repeat(40);
  assert.equal(f.onPush({ repo: "o/n", ref: "refs/heads/main", before: "a".repeat(40), after: "1".repeat(40), default: true }), true);
  assert.equal(f.onPush({ repo: "o/n", ref: "refs/heads/main", before: "1".repeat(40), after: "2".repeat(40), default: true }), true);
  assert.equal(f.onPush({ repo: "o/n", ref: "refs/heads/main", before: "2".repeat(40), after, default: true }), true);
  assert.equal(c.pending(), 1, "one coalescing timer, re-armed");
  assert.equal(git.calls.length, 0, "nothing runs before the window closes");
  c.fire();
  await new Promise((r) => setTimeout(r, 30));
  assert.deepEqual(git.calls.map((x) => x.args), [
    ["remote", "set-url", "origin", `${ENGINE}/repos/o/n/git`],
    ["fetch", "origin", "main"],
    ["checkout", "--detach", after],
    ["rev-parse", "HEAD"],
  ]);
  for (const call of git.calls) assert.equal(call.cwd, path.join(freshDir, "o", "n"));
  const doc = readFresh(freshDir);
  assert.equal(doc.repos["o/n"].commit, after);
  assert.equal(doc.repos["o/n"].error, undefined);
  assert.deepEqual(updated, [{ repo: "o/n", commit: after, instructionsChanged: false }]);
  assert.equal(COALESCE_MS, 1000);
});

test("onPush ignores default: false, a repo with no clone, and a malformed frame", async () => {
  const { f, git, c } = fc();
  await f.ensureClone("o/n");
  git.calls.length = 0;
  assert.equal(f.onPush({ repo: "o/n", ref: "refs/heads/feature", before: "a", after: "b", default: false }), false);
  assert.equal(f.onPush({ repo: "x/y", ref: "refs/heads/main", before: "a", after: "b", default: true }), false, "no clone yet: the next hello clones it");
  assert.equal(f.onPush(null), false);
  assert.equal(f.onPush({ repo: "o/n", default: "true" }), false);
  assert.equal(c.pending(), 0);
  c.fire();
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(git.calls.length, 0, "no git command ran");
});

test("a failed fetch keeps the clone and the last commit and records error; a failed checkout of <after> falls back to origin/<branch>", async () => {
  const state = { head: "a".repeat(40), remote: "a".repeat(40) };
  let fetchFails = true;
  let afterUnknown = false;
  const inner = healthyGit(state);
  const git = fakeGit((args, cwd) => {
    if (args[0] === "fetch" && fetchFails) return { code: 128, stderr: "fatal: unable to access: Connection refused\n" };
    if (args[0] === "checkout" && afterUnknown && /^[0-9a-f]{40}$/.test(args[2])) return { code: 128, stderr: "fatal: reference is not a tree\n" };
    return inner.spawn === undefined ? "" : null;
  });
  // Delegate the healthy answers: wrap the inner answer.
  const { f, c, freshDir } = fc({ git: { spawn: (cmd, args, opts) => {
    const rest = args.slice(2);
    if ((rest[0] === "fetch" && fetchFails) || (rest[0] === "checkout" && afterUnknown && /^[0-9a-f]{40}$/.test(rest[2]))) return git.spawn(cmd, args, opts);
    return inner.spawn(cmd, args, opts);
  }, calls: inner.calls } });
  await f.ensureClone("o/n");
  const before = readFresh(freshDir).repos["o/n"];
  f.onPush({ repo: "o/n", after: "b".repeat(40), default: true });
  c.fire();
  await new Promise((r) => setTimeout(r, 30));
  let doc = readFresh(freshDir);
  assert.equal(fs.existsSync(path.join(freshDir, "o", "n", ".git")), true, "the clone stays");
  assert.equal(doc.repos["o/n"].commit, before.commit, "the last commit stays");
  assert.match(doc.repos["o/n"].error, /^fetch: fatal: unable to access: Connection refused$/);
  assert.equal(f.status("o/n"), "ready", "a clone with a failed fetch is still a clone");
  assert.equal(c.pending(), 0, "no retry timer");
  // The next push after the engine is back: fetched, and `after` not in the fetch falls back to origin/main.
  fetchFails = false;
  afterUnknown = true;
  state.remote = "c".repeat(40);
  inner.calls.length = 0;
  f.onPush({ repo: "o/n", after: "b".repeat(40), default: true });
  c.fire();
  await new Promise((r) => setTimeout(r, 30));
  assert.deepEqual(inner.calls.map((x) => x.args), [["remote", "set-url", "origin", `${ENGINE}/repos/o/n/git`], ["fetch", "origin", "main"], ["checkout", "--detach", "origin/main"], ["rev-parse", "HEAD"]]);
  doc = readFresh(freshDir);
  assert.equal(doc.repos["o/n"].commit, "c".repeat(40));
  assert.equal(doc.repos["o/n"].error, undefined, "the error is cleared");
});

test("onHello fetches every listed repo, checks out only the moved one and clones the missing one", async () => {
  const states = { "o/n": { head: "a".repeat(40), remote: "a".repeat(40) }, "o/m": { head: "b".repeat(40), remote: "b".repeat(40) }, "x/y": { head: "c".repeat(40), remote: "c".repeat(40) } };
  const inners = Object.fromEntries(Object.entries(states).map(([k, s]) => [k, healthyGit(s)]));
  const calls = [];
  const repoOf = (args, cwd, freshDir) => {
    const dir = cwd || args[args.length - 1];
    return path.relative(freshDir, dir).split(path.sep).slice(0, 2).join("/");
  };
  const freshDir = path.join(tmpdir(), "fresh");
  const git = { calls, spawn: (cmd, args, opts) => {
    const rest = args.slice(2);
    const repo = rest[0] === "ls-remote" ? /repos\/(.+?)\/git/.exec(rest[2])[1].replace(/%2F/g, "/") : repoOf(rest, opts.cwd, freshDir);
    calls.push({ repo, args: rest });
    return inners[repo].spawn(cmd, args, opts);
  } };
  const { f, log } = fc({ git, freshDir });
  await f.ensureClone("o/n");
  await f.ensureClone("o/m");
  calls.length = 0;
  states["o/m"].remote = "d".repeat(40);   // moved while the app was closed
  await f.onHello(["o/n", "o/m", "x/y"]);
  const by = (repo) => calls.filter((c) => c.repo === repo).map((c) => c.args[0]);
  assert.deepEqual(by("o/n"), ["remote", "fetch", "rev-parse"], "fetched, not moved: no checkout");
  assert.deepEqual(by("o/m"), ["remote", "fetch", "rev-parse", "checkout", "rev-parse"], "fetched and checked out");
  assert.deepEqual(by("x/y"), ["ls-remote", "clone", "checkout", "rev-parse"], "cloned");
  const doc = readFresh(freshDir);
  assert.equal(doc.repos["o/m"].commit, "d".repeat(40));
  assert.equal(doc.repos["o/n"].commit, "a".repeat(40));
  assert.equal(doc.repos["x/y"].commit, "c".repeat(40));
  assert.ok(log.some((l) => /x\/y: cloned/.test(l)));
});

test("onRepoRemoved sweeps an unused clone now, defers one in use to a later hello, and a push for it is dropped", async () => {
  const { f, c, freshDir } = fc();
  await f.ensureClone("o/n");
  await f.ensureClone("o/m");
  const dirN = path.join(freshDir, "o", "n");
  const dirM = path.join(freshDir, "o", "m");
  assert.deepEqual(f.onRepoRemoved("o/n", { inUse: () => false }), { swept: true, deferred: false });
  assert.equal(fs.existsSync(dirN), false);
  assert.equal(readFresh(freshDir).repos["o/n"], undefined);
  f.onPush({ repo: "o/m", after: "b".repeat(40), default: true });
  assert.deepEqual(f.onRepoRemoved("o/m", { inUse: (d) => d === dirM }), { swept: false, deferred: true });
  assert.equal(c.pending(), 0, "the pending push is dropped");
  assert.equal(fs.existsSync(dirM), true, "a clone in use stays");
  assert.deepEqual(readFresh(freshDir).sweep, ["o/m"]);
  // Still in use at the next hello: kept; free at the one after: swept.
  await f.onHello([], { inUse: () => true });
  assert.equal(fs.existsSync(dirM), true);
  await f.onHello([], { inUse: () => false });
  assert.equal(fs.existsSync(dirM), false);
  assert.deepEqual(readFresh(freshDir).sweep, []);
  // A repo listed again by /repos leaves the sweep list.
  f.doc.sweep.push("o/n");
  await f.onHello(["o/n"]);
  assert.deepEqual(readFresh(freshDir).sweep, []);
});

test("readRepoInstructions: CLAUDE.md and AGENTS.md at the clone root, capped at 32,000 characters, with blob and commit; [] for a non-repo dir", () => {
  const { f, freshDir } = fc();
  const dir = path.join(freshDir, "o", "n");
  fs.mkdirSync(path.join(dir, ".git"), { recursive: true });
  fs.writeFileSync(path.join(dir, ".git", "HEAD"), `${"a".repeat(40)}\n`);
  assert.deepEqual(f.readRepoInstructions(dir), [], "no instruction file yet");
  assert.deepEqual(f.readRepoInstructions(freshDir), [], "the clones' root is no repo");
  assert.deepEqual(f.readRepoInstructions(null), []);
  fs.writeFileSync(path.join(dir, "CLAUDE.md"), "# Rules\n\nAlways say ahoy.\n");
  fs.writeFileSync(path.join(dir, "AGENTS.md"), "x".repeat(INSTRUCTION_CAP + 5));
  const got = f.readRepoInstructions(dir);
  assert.deepEqual(got.map((i) => [i.repo, i.file, i.path, i.commit, i.truncated]), [
    ["o/n", "CLAUDE.md", path.join(dir, "CLAUDE.md"), "a".repeat(40), false],
    ["o/n", "AGENTS.md", path.join(dir, "AGENTS.md"), "a".repeat(40), true],
  ]);
  assert.equal(got[0].text, "# Rules\n\nAlways say ahoy.\n");
  assert.equal(got[0].blob, blobSha("# Rules\n\nAlways say ahoy.\n"));
  assert.equal(INSTRUCTION_CAP, 32000);
  assert.ok(got[1].text.startsWith("x".repeat(INSTRUCTION_CAP)) && got[1].text.endsWith("[cut at 32000 characters; the file is longer]"));
  assert.equal(got[1].text.length, INSTRUCTION_CAP + "\n[cut at 32000 characters; the file is longer]".length);
  // A changed file changes its blob and nothing else's.
  fs.writeFileSync(path.join(dir, "CLAUDE.md"), "# Rules\n\nNever say ahoy.\n");
  const again = f.readRepoInstructions(dir);
  assert.notEqual(again[0].blob, got[0].blob);
  assert.equal(again[1].blob, got[1].blob);
  // A symbolic HEAD (a clone not yet detached) is followed one step.
  fs.writeFileSync(path.join(dir, ".git", "HEAD"), "ref: refs/heads/main\n");
  fs.mkdirSync(path.join(dir, ".git", "refs", "heads"), { recursive: true });
  fs.writeFileSync(path.join(dir, ".git", "refs", "heads", "main"), `${"b".repeat(40)}\n`);
  assert.equal(f.readRepoInstructions(dir)[0].commit, "b".repeat(40));
});

test("readRepoInstructions never follows a symlink: a CLAUDE.md linking outside the clone is skipped, its target's bytes never reach the text", () => {
  const { f, freshDir } = fc();
  const dir = path.join(freshDir, "o", "n");
  fs.mkdirSync(path.join(dir, ".git"), { recursive: true });
  fs.writeFileSync(path.join(dir, ".git", "HEAD"), `${"a".repeat(40)}\n`);
  const outside = path.join(tmpdir(), "id_rsa");
  fs.writeFileSync(outside, "-----BEGIN PRIVATE KEY----- SECRET\n");
  fs.symlinkSync(outside, path.join(dir, "CLAUDE.md"));
  fs.writeFileSync(path.join(dir, "AGENTS.md"), "# Agents\n");
  const got = f.readRepoInstructions(dir);
  assert.deepEqual(got.map((i) => i.file), ["AGENTS.md"], "the symlinked CLAUDE.md is skipped");
  assert.ok(!JSON.stringify(got).includes("SECRET"), "nothing outside the clone is read");
  // A symlink to a file inside the clone is skipped too: only a regular file at the root is an instruction file.
  fs.unlinkSync(path.join(dir, "CLAUDE.md"));
  fs.writeFileSync(path.join(dir, "real.md"), "# Real\n");
  fs.symlinkSync(path.join(dir, "real.md"), path.join(dir, "CLAUDE.md"));
  assert.deepEqual(f.readRepoInstructions(dir).map((i) => i.file), ["AGENTS.md"]);
  // A directory named CLAUDE.md is skipped as well.
  fs.unlinkSync(path.join(dir, "CLAUDE.md"));
  fs.mkdirSync(path.join(dir, "CLAUDE.md"));
  assert.deepEqual(f.readRepoInstructions(dir).map((i) => i.file), ["AGENTS.md"]);
});

test("savedChatDir: a saved chat's repo becomes a path under freshDir only as a plain owner/name; anything else is freshDir itself", () => {
  const freshDir = "/Users/you/Library/Application Support/frakworks/first-mate/fresh";
  assert.equal(savedChatDir(freshDir, "codefrak-oss/frakworks"), path.join(freshDir, "codefrak-oss", "frakworks"));
  assert.equal(savedChatDir(freshDir, null), freshDir, "All repos");
  assert.equal(savedChatDir(freshDir, ""), freshDir);
  for (const bad of ["../../x", "o/../../x", "../x/n", "o/n/../..", "/etc/passwd", "o/n/extra", "o", "..", "o/..", "o/ n", "o\\n"]) {
    const got = savedChatDir(freshDir, bad);
    assert.equal(got, freshDir, `${JSON.stringify(bad)} is no repo`);
    assert.ok(!path.relative(freshDir, got).startsWith(".."), `${JSON.stringify(bad)} stays under freshDir`);
  }
});

test("migrateProjectDir copies ~/.claude/projects/<slug of from>/ to <slug of to>/ once, leaving the source, and never over an existing target", () => {
  const home = tmpdir();
  const from = "/Users/you/dev/frakworks";
  const to = "/Users/you/Library/Application Support/frakworks/first-mate/fresh/codefrak-oss/frakworks";
  const src = path.join(home, ".claude", "projects", claudeProjectSlug(from));
  const dst = path.join(home, ".claude", "projects", claudeProjectSlug(to));
  fs.mkdirSync(path.join(src, "memory"), { recursive: true });
  fs.writeFileSync(path.join(src, "memory", "MEMORY.md"), "- remember\n");
  fs.writeFileSync(path.join(src, "sess-1.jsonl"), "{}\n");
  const { f, freshDir } = fc({ home });
  const r = f.migrateProjectDir(from, to);
  assert.deepEqual(r, { done: true, source: src, target: dst });
  assert.equal(fs.readFileSync(path.join(dst, "memory", "MEMORY.md"), "utf8"), "- remember\n");
  assert.equal(fs.existsSync(path.join(dst, "sess-1.jsonl")), true);
  assert.equal(fs.existsSync(path.join(src, "memory", "MEMORY.md")), true, "the source is left in place");
  assert.equal(readFresh(freshDir).migrated_from, src);
  // Once: a second call, even with a changed source, does nothing.
  fs.writeFileSync(path.join(src, "memory", "MEMORY.md"), "- changed\n");
  assert.equal(f.migrateProjectDir(from, to).already, true);
  assert.equal(fs.readFileSync(path.join(dst, "memory", "MEMORY.md"), "utf8"), "- remember\n");
  // A fresh keeper reads the record from fresh.json and does not run again.
  const again = createFreshCode({ freshDir, home, spawn: () => { throw new Error("no git here"); } });
  assert.equal(again.migrateProjectDir(from, to).already, true);
  // An existing target is never overwritten: recorded as skipped.
  const other = fc({ home });
  fs.mkdirSync(path.join(home, ".claude", "projects", claudeProjectSlug("/t2")), { recursive: true });
  fs.writeFileSync(path.join(home, ".claude", "projects", claudeProjectSlug("/t2"), "keep.jsonl"), "keep\n");
  const s = other.f.migrateProjectDir(from, "/t2");
  assert.equal(s.skipped, true);
  assert.equal(fs.readFileSync(path.join(home, ".claude", "projects", claudeProjectSlug("/t2"), "keep.jsonl"), "utf8"), "keep\n");
  assert.equal(fs.existsSync(path.join(home, ".claude", "projects", claudeProjectSlug("/t2"), "sess-1.jsonl")), false);
  const doc = readFresh(other.freshDir);
  assert.equal(doc.migrated_from, null);
  assert.equal(doc.migration_skipped, true);
  // Nothing to copy: recorded as null, so it does not run again either.
  const none = fc({ home });
  assert.deepEqual(none.f.migrateProjectDir("/nowhere", "/t3"), { done: false, migrated_from: null });
  assert.equal(readFresh(none.freshDir).migrated_from, null);
});

test("the source arms no timer but the 1 s coalescer, names no GitHub host and imports no Electron", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "fresh-code.js"), "utf8");
  assert.doesNotMatch(src, /setInterval/);
  assert.doesNotMatch(src, /github\.com|api\.github/i);
  assert.doesNotMatch(src, /require\(["']electron["']\)/);
  assert.match(src, /credential\.helper=/);
  assert.doesNotMatch(src, /--filter/, "whole clones, as the user decided for this build");
});

test("git carries the engine's bearer as http.extraHeader in its environment, never in argv, and none while signed out", async () => {
  let bearer = "Bearer tok";
  const { f, git } = fc({ bearer: () => bearer });
  await f.ensureClone("o/n");
  const first = git.calls[0];
  assert.equal(first.env.GIT_TERMINAL_PROMPT, "0");
  assert.equal(first.env.GIT_CONFIG_COUNT, "1");
  assert.equal(first.env.GIT_CONFIG_KEY_0, "http.extraHeader");
  assert.equal(first.env.GIT_CONFIG_VALUE_0, "Authorization: Bearer tok");
  assert.ok(git.calls.every((c) => !c.args.join(" ").includes("tok")), "the token is in no argv");
  bearer = null;
  const out = fc({ bearer: () => bearer, freshDir: path.join(tmpdir(), "fresh-out") });
  await out.f.ensureClone("o/n");
  assert.equal(out.git.calls[0].env.GIT_CONFIG_COUNT, undefined);
});
