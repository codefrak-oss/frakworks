// The status bar: src/app-version.js against a real throwaway repo (git is
// what it wraps, so a fake would only test the fake) with a bare repo on disk
// as its `origin`, `describe` / `versionText` for every state the bar can be
// in, and the bar lighting its button only for a desktop change. The stand-in
// `exec` passes git through to the real one and fails or records on cue: a
// fetch that cannot reach origin, npm builds that are not run.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { repoRoot, readVersion, fetchOrigin, compareRunning, describe, rebuild, APP_PATH, CHECK_MS, FETCH_MS } = require("../src/app-version");
const { StatusBar, versionText, versionTitle } = require("../dist/status-bar.cjs");
const { VERSION_CHECK_MS } = require("../dist/index.cjs");

const git = (cwd, ...args) => execFileSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
const commit = (cwd, file, msg) => {
  fs.mkdirSync(path.dirname(path.join(cwd, file)), { recursive: true });
  fs.appendFileSync(path.join(cwd, file), `${msg}\n`);
  git(cwd, "add", "-A"); git(cwd, "commit", "-q", "-m", msg);
  return git(cwd, "rev-parse", "HEAD");
};

const fixture = (t) => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "app-version-"));
  t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));
  const repo = path.join(tmp, "repo");
  git(tmp, "init", "-q", "-b", "main", repo);
  git(repo, "config", "user.email", "t@example.com"); git(repo, "config", "user.name", "t");
  const base = commit(repo, "README.md", "first");
  return { repo, base };
};

test("readVersion reports HEAD, the branch and whether tracked files are dirty", async (t) => {
  const { repo, base } = fixture(t);
  assert.equal(await repoRoot({ from: repo }), fs.realpathSync(repo));
  assert.deepEqual(await readVersion({ repoDir: repo }), { sha: base, short: base.slice(0, 7), branch: "main", dirty: false });
  fs.writeFileSync(path.join(repo, "untracked.txt"), "x");
  assert.equal((await readVersion({ repoDir: repo })).dirty, false, "an untracked file is not dirty");
  fs.appendFileSync(path.join(repo, "README.md"), "edit\n");
  assert.equal((await readVersion({ repoDir: repo })).dirty, true);
  assert.match((await readVersion({ repoDir: null })).error, /not running from a git checkout/);
  assert.match((await readVersion({ repoDir: path.join(repo, "..") })).error, /git/);
});

test("compareRunning: an engine-only commit moves HEAD but does not make the app stale; a desktop one does", async (t) => {
  const { repo, base } = fixture(t);
  assert.deepEqual(await compareRunning({ repoDir: repo, running: base }), { ahead: 0, desktop: false, stale: false, changed: [], lockMoved: false });

  commit(repo, "engine/README.md", "engine change");
  assert.deepEqual(await compareRunning({ repoDir: repo, running: base }), { ahead: 1, desktop: false, stale: false, changed: ["engine"], lockMoved: false });

  commit(repo, `${APP_PATH}src/x.js`, "desktop change");
  assert.deepEqual(await compareRunning({ repoDir: repo, running: base }), { ahead: 2, desktop: true, stale: true, changed: ["engine", "observability"], lockMoved: false });

  commit(repo, `${APP_PATH}package-lock.json`, "lock moved");
  assert.equal((await compareRunning({ repoDir: repo, running: base })).lockMoved, true);

  // a branch switch: HEAD is not a descendant, the diff still says what differs
  git(repo, "checkout", "-q", "-b", "other", base);
  commit(repo, "bots/README.md", "elsewhere");
  const c = await compareRunning({ repoDir: repo, running: git(repo, "rev-parse", "main") });
  assert.equal(c.ahead, 1); assert.equal(c.stale, true, "the desktop change on main is not in HEAD: the running app differs from disk");
  assert.match((await compareRunning({ repoDir: repo, running: "0000000" })).error, /git/);
});

// A bare repo as `origin`, the app's checkout cloned from it, and a second
// clone standing in for GitHub merges.
const remoteFixture = (t) => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "app-origin-"));
  t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));
  const bare = path.join(tmp, "origin.git");
  git(tmp, "init", "-q", "--bare", "-b", "main", bare);
  const clone = (name) => {
    const dir = path.join(tmp, name);
    git(tmp, "clone", "-q", bare, dir);
    git(dir, "config", "user.email", "t@example.com"); git(dir, "config", "user.name", "t");
    return dir;
  };
  const gh = clone("github");
  commit(gh, "README.md", "first"); git(gh, "push", "-q", "origin", "main");
  const repo = clone("repo");
  const base = git(repo, "rev-parse", "HEAD");
  const merge = (file, msg) => { const sha = commit(gh, file, msg); git(gh, "push", "-q", "origin", "main"); return sha; };
  return { repo, base, merge };
};

// Real git for everything but what the test says: `fail` git subcommands
// fail as if offline, npm is recorded and succeeds.
const standIn = ({ fail = [] } = {}) => {
  const calls = [];
  const exec = (cmd, args, opts, cb) => {
    calls.push([cmd, ...args]);
    if (cmd === "git" && fail.includes(args[0])) return cb(new Error("exit 128"), "", "fatal: unable to access 'https://github.com/x/y/': Could not resolve host: github.com");
    if (cmd === "npm") return cb(null, "ok", "");
    return child.execFile(cmd, args, opts, cb);
  };
  return { exec, calls };
};
const child = require("node:child_process");

test("fetchOrigin: a failed fetch is { error } for the remote part only; the local facts stand", async (t) => {
  const { repo, base, merge } = remoteFixture(t);
  merge(`${APP_PATH}src/x.js`, "desktop on github");
  const { exec, calls } = standIn({ fail: ["fetch"] });
  const f = await fetchOrigin({ repoDir: repo, exec });
  assert.match(f.error, /Could not resolve host/);
  assert.deepEqual(calls[0], ["git", "fetch", "--quiet", "origin", "main"]);
  assert.deepEqual(await fetchOrigin({ repoDir: null }), { error: "not running from a git checkout" });
  commit(repo, "engine/README.md", "local engine change");
  const c = await compareRunning({ repoDir: repo, running: base, remote: true, fetchError: f.error, exec });
  assert.equal(c.ahead, 1); assert.deepEqual(c.changed, ["engine"]); assert.equal(c.stale, false);
  assert.match(c.origin.error, /^fetch: .*Could not resolve host/);
  assert.equal(versionTitle({ sha: "abc", running: base, cmp: c }).split("\n")[2], `origin/main unknown: ${c.origin.error}`);
  const ok = await fetchOrigin({ repoDir: repo, now: () => 42 });
  assert.deepEqual(ok, { at: 42 });
});

test("compareRunning with remote: origin/main ahead with a desktop change is stale; engine-only is not", async (t) => {
  const { repo, base, merge } = remoteFixture(t);
  let c = await compareRunning({ repoDir: repo, running: base, remote: true });
  assert.deepEqual(c.origin, { sha: base, behind: 0, stale: false, changed: [], blocked: null });
  assert.equal(c.stale, false);

  const e = merge("engine/README.md", "engine on github");
  assert.equal((await fetchOrigin({ repoDir: repo })).error, undefined);
  c = await compareRunning({ repoDir: repo, running: base, remote: true });
  assert.deepEqual(c.origin, { sha: e, behind: 1, stale: false, changed: ["engine"], blocked: null });
  assert.equal(c.stale, false, "engine only: not lit");
  assert.equal(c.ahead, 0, "the checkout itself has not moved");

  const d = merge(`${APP_PATH}src/x.js`, "desktop on github");
  await fetchOrigin({ repoDir: repo });
  c = await compareRunning({ repoDir: repo, running: base, remote: true });
  assert.deepEqual(c.origin, { sha: d, behind: 2, stale: true, changed: ["engine", "observability"], blocked: null });
  assert.equal(c.stale, true); assert.equal(c.desktop, false);
  assert.equal(describe(await readVersion({ repoDir: repo }), base, c).text, "main · " + base.slice(0, 7) + " · running · behind origin 2 (desktop changed)");

  // pulled by hand: nothing behind, the local fact carries it
  git(repo, "merge", "-q", "--ff-only", "origin/main");
  c = await compareRunning({ repoDir: repo, running: base, remote: true });
  assert.equal(c.origin.behind, 0); assert.equal(c.desktop, true); assert.equal(c.stale, true);
});

test("the pull is refused, touching nothing, when dirty, off main or diverged", async (t) => {
  const { repo, base, merge } = remoteFixture(t);
  merge(`${APP_PATH}src/x.js`, "desktop on github");
  await fetchOrigin({ repoDir: repo });
  const appDir = repo;
  const refused = async (why) => {
    const head = git(repo, "rev-parse", "HEAD");
    const status = git(repo, "status", "--porcelain");
    const { exec, calls } = standIn();
    const r = await rebuild({ appDir, exec, pull: { repoDir: repo, running: base } });
    assert.equal(r.ok, false);
    assert.deepEqual(r.steps.map((s) => [s.step, s.ok]), [["pull", false]]);
    assert.match(r.steps[0].detail, /^not pulled: /); assert.match(r.steps[0].detail, why);
    assert.equal(git(repo, "rev-parse", "HEAD"), head, "HEAD not moved");
    assert.equal(git(repo, "status", "--porcelain"), status, "working tree not touched");
    assert.equal(calls.filter((c) => c[0] === "npm").length, 0, "no build after a refused pull");
    assert.ok(!calls.some((c) => ["merge", "stash", "reset", "rebase", "pull", "checkout"].includes(c[1])), "never forces");
    const cmp = await compareRunning({ repoDir: repo, running: base, remote: true });
    assert.match(cmp.origin.blocked, why, "the bar says why before the click");
  };

  fs.appendFileSync(path.join(repo, "README.md"), "edit\n");
  await refused(/the checkout has uncommitted changes/);
  git(repo, "checkout", "-q", "--", "README.md");

  git(repo, "checkout", "-q", "-b", "feature");
  await refused(/the checkout is on feature, not main/);
  git(repo, "checkout", "-q", "main");

  commit(repo, "local.txt", "local commit not on github");
  await refused(/local commits on main are not on origin\/main: no fast-forward/);
});

test("behind origin/main: the pull runs, then npm ci if the lockfile moved, then the build", async (t) => {
  const { repo, base, merge } = remoteFixture(t);
  merge(`${APP_PATH}src/x.js`, "desktop on github");
  const tip = merge(`${APP_PATH}package-lock.json`, "lock moved on github");
  await fetchOrigin({ repoDir: repo });
  const { exec, calls } = standIn();
  const r = await rebuild({ appDir: repo, exec, pull: { repoDir: repo, running: base } });
  assert.equal(r.ok, true);
  assert.deepEqual(r.steps.map((s) => [s.step, s.ok]), [["pull", true], ["npm ci", true], ["repair electron", true], ["build renderer", true]]);
  assert.equal(git(repo, "rev-parse", "HEAD"), tip);
  assert.ok(calls.some((c) => c.join(" ") === "git merge --ff-only origin/main"));
  assert.ok(!calls.some((c) => c[1] === "fetch"), "the pull uses the fetched ref");

  // no lockfile change: no npm ci
  const { repo: repo2, base: base2, merge: merge2 } = remoteFixture(t);
  merge2(`${APP_PATH}src/y.js`, "desktop only");
  await fetchOrigin({ repoDir: repo2 });
  const r2 = await rebuild({ appDir: repo2, exec: standIn().exec, pull: { repoDir: repo2, running: base2 } });
  assert.deepEqual(r2.steps.map((s) => s.step), ["pull", "build renderer"]);
});

test("describe says the one thing that matters, and the renderer's copy agrees", () => {
  const v = { sha: "9f1e2ab0123", short: "9f1e2ab", branch: "main", dirty: false };
  const running = "c4d787c9999";
  const same = { ...v, sha: running, short: "c4d787c" };
  assert.deepEqual(describe({ error: "not running from a git checkout" }, running, null), { text: "not running from a git checkout", level: "muted" });
  assert.deepEqual(describe(same, running, null), { text: "main · c4d787c · checking…", level: "muted" });
  assert.deepEqual(describe(same, running, { error: "git rev-list: bad" }), { text: "main · c4d787c · git rev-list: bad", level: "muted" });
  assert.deepEqual(describe(same, running, { ahead: 0, stale: false, changed: [] }), { text: "main · c4d787c · running", level: "ok" });
  assert.deepEqual(describe(v, running, { ahead: 1, stale: false, changed: ["engine"] }), { text: "main · 9f1e2ab · running c4d787c +1 (engine)", level: "muted" });
  assert.deepEqual(describe(v, running, { ahead: 2, desktop: true, stale: true, changed: ["engine", "observability"] }), { text: "main · 9f1e2ab · running c4d787c +2 (desktop changed)", level: "stale" });
  // origin/main ahead of the checkout
  const upToDate = { sha: "a", behind: 0, stale: false, changed: [], blocked: null };
  const behindDesktop = { sha: "b", behind: 3, stale: true, changed: ["observability"], blocked: null };
  const behindEngine = { sha: "c", behind: 1, stale: false, changed: ["engine"], blocked: null };
  const clean = { ahead: 0, desktop: false, stale: false, changed: [] };
  assert.deepEqual(describe(same, running, { ...clean, origin: upToDate }), { text: "main · c4d787c · running", level: "ok" });
  assert.deepEqual(describe(same, running, { ...clean, stale: true, origin: behindDesktop }), { text: "main · c4d787c · running · behind origin 3 (desktop changed)", level: "stale" });
  assert.deepEqual(describe(same, running, { ...clean, origin: behindEngine }), { text: "main · c4d787c · running · behind origin 1 (engine)", level: "muted" });
  assert.deepEqual(describe(same, running, { ...clean, stale: true, origin: { ...behindDesktop, blocked: "the checkout has uncommitted changes" } }),
    { text: "main · c4d787c · running · behind origin 3 (desktop changed; won't pull: the checkout has uncommitted changes)", level: "stale" });
  assert.deepEqual(describe(v, running, { ahead: 1, desktop: false, stale: true, changed: ["engine"], origin: behindDesktop }),
    { text: "main · 9f1e2ab · running c4d787c +1 (engine) · behind origin 3 (desktop changed)", level: "stale" });
  assert.deepEqual(describe(same, running, { ...clean, origin: { error: "fetch: git fetch: Could not resolve host" } }), { text: "main · c4d787c · running", level: "ok" },
    "a failed fetch keeps the local facts; the hover names it");
  assert.deepEqual(describe({ ...same, dirty: true }, running, { ahead: 0, stale: false, changed: [] }), { text: "main · c4d787c · dirty · running", level: "ok" });
  for (const [ver, cmp] of [[same, { ahead: 0, stale: false, changed: [] }], [v, { ahead: 2, desktop: true, stale: true, changed: ["observability"] }], [v, { ahead: 1, stale: false, changed: ["engine"] }],
    [same, { ...clean, stale: true, origin: behindDesktop }], [same, { ...clean, origin: behindEngine }], [v, { ahead: 1, stale: true, changed: ["engine"], origin: { ...behindDesktop, blocked: "x" } }],
    [same, { ...clean, origin: { error: "fetch: offline" } }]]) {
    assert.deepEqual(versionText({ ...ver, running, cmp }), describe(ver, running, cmp));
  }
  assert.deepEqual(versionText(null), { text: "version…", level: "muted" });
});

test("rebuild runs npm ci only when the lockfile moved, and stops at the first failure", async (t) => {
  const appDir = fs.mkdtempSync(path.join(os.tmpdir(), "app-rebuild-"));
  t.after(() => fs.rmSync(appDir, { recursive: true, force: true }));
  const calls = [];
  const exec = (cmd, args, _opts, cb) => {
    calls.push([cmd, ...args]);
    if (args[1] === "build:renderer" && exec.failRenderer) return cb(new Error("esbuild: boom"), "", "esbuild: boom");
    if (args[1] === "repair:electron" && exec.failRepair) return cb(new Error("no cached zip"), "", "no cached zip");
    cb(null, "ok", "");
  };
  let r = await rebuild({ appDir, exec });
  assert.equal(r.ok, true);
  assert.deepEqual(calls, [["npm", "run", "build:renderer"]]);

  calls.length = 0;
  r = await rebuild({ appDir, exec, lockMoved: true });
  assert.deepEqual(r.steps.map((s) => s.step), ["npm ci", "repair electron", "build renderer"]);
  assert.deepEqual(calls[0], ["npm", "ci", "--ignore-scripts"]);
  // --ignore-scripts skips Electron's postinstall (its binary download), so
  // the repair always follows an npm ci, and a failed repair stops there.
  assert.deepEqual(calls[1], ["npm", "run", "repair:electron"]);
  calls.length = 0;
  exec.failRepair = true;
  r = await rebuild({ appDir, exec, lockMoved: true });
  assert.equal(r.ok, false);
  assert.deepEqual(r.steps.map((s) => [s.step, s.ok]), [["npm ci", true], ["repair electron", false]]);
  exec.failRepair = false;

  exec.failRenderer = true;
  r = await rebuild({ appDir, exec });
  assert.equal(r.ok, false);
  assert.deepEqual(r.steps.map((s) => [s.step, s.ok]), [["build renderer", false]]);
  assert.match(r.steps[0].detail, /esbuild: boom/);
});

test("the bar lights the button only for a desktop change; the local check is often, the fetch a minute or slower", () => {
  assert.equal(VERSION_CHECK_MS, 3000); assert.equal(CHECK_MS, 3000);
  assert.ok(FETCH_MS >= 60000 && FETCH_MS > CHECK_MS);
  const render = (props) => renderToStaticMarkup(React.createElement(StatusBar, props));
  const running = "c4d787c9999";
  const v = { sha: "9f1e2ab0123", branch: "main", dirty: false, running, engineUrl: "http://localhost:8090" };
  const engine = { ok: true, url: "http://localhost:8090" };
  let html = render({ version: { ...v, sha: running, cmp: { ahead: 0, stale: false, changed: [] } }, engine });
  assert.match(html, /main · c4d787c · running/);
  assert.match(html, /⟳ Restart/); assert.doesNotMatch(html, /sbButton lit/);
  assert.match(html, /engine ● localhost:8090/);

  html = render({ version: { ...v, cmp: { ahead: 1, stale: false, changed: ["engine"] } }, engine });
  assert.match(html, /running c4d787c \+1 \(engine\)/); assert.doesNotMatch(html, /sbButton lit/, "engine only: not lit");

  html = render({ version: { ...v, cmp: { ahead: 2, desktop: true, stale: true, changed: ["observability"] } }, engine });
  assert.match(html, /sb-stale/); assert.match(html, /sbButton lit/);

  const current = { ...v, sha: running };
  html = render({ version: { ...current, cmp: { ahead: 0, stale: true, changed: [], origin: { sha: "d", behind: 2, stale: true, changed: ["observability"], blocked: null } } }, engine });
  assert.match(html, /running · behind origin 2 \(desktop changed\)/); assert.match(html, /sbButton lit/);
  assert.match(html, /Pull origin\/main \(fast-forward only\), rebuild and relaunch/);
  html = render({ version: { ...current, cmp: { ahead: 0, stale: false, changed: [], origin: { sha: "e", behind: 1, stale: false, changed: ["engine"], blocked: null } } }, engine });
  assert.match(html, /behind origin 1 \(engine\)/); assert.match(html, /sb-muted/); assert.doesNotMatch(html, /sbButton lit/, "engine only on origin: not lit");
  html = render({ version: { ...current, cmp: { ahead: 0, stale: false, changed: [], origin: { error: "fetch: offline" } } }, engine });
  assert.match(html, /main · c4d787c · running</); assert.match(html, /origin\/main unknown: fetch: offline/);
  html = render({ version: v, engine, updating: { ok: false, steps: [{ step: "pull", ok: false, detail: "not pulled: the checkout has uncommitted changes" }] } });
  assert.match(html, /pull failed/); assert.match(html, /not pulled: the checkout has uncommitted changes/);

  html = render({ version: { ...v, dirty: true, cmp: { ahead: 2, stale: true, changed: ["observability"] } }, engine: { ok: false, error: "ECONNREFUSED", url: "http://localhost:8090" } });
  assert.match(html, /dirty/); assert.match(html, /sbButton lit/, "dirty does not stop a restart");
  assert.match(html, /engine ○ localhost:8090/); assert.match(html, /sb-bad/);

  html = render({ version: v, engine, updating: { steps: [{ step: "npm ci", ok: true }] } });
  assert.match(html, /npm ci…/); assert.match(html, /disabled=""/);
  html = render({ version: v, engine, updating: { ok: false, steps: [{ step: "npm ci", ok: false, detail: "no" }] } });
  assert.match(html, /npm ci failed/);
});

test("the bar is drawing only, the app's git reaches GitHub only to fetch and fast-forward, and the renderer sends no argument", () => {
  const src = (f) => fs.readFileSync(path.join(__dirname, "..", "src", f), "utf8");
  assert.doesNotMatch(src(path.join("renderer", "status-bar.jsx")), /require\(|node:|fetch\(|git /);
  const code = src("app-version.js").replace(/^\s*\/\/.*$/gm, "");
  assert.doesNotMatch(code, /\["(push|pull|reset|stash|rebase|checkout)"|"--force"|"-f"/, "never pushes, resets, stashes or forces");
  assert.match(code, /\["merge", "--ff-only", REMOTE_REF\]/, "the one move is a fast-forward to the fetched ref");
  assert.equal((code.match(/"fetch"/g) || []).length, 1, "one fetch");
  const preload = src("preload.js");
  assert.match(preload, /version: \(\) => ipcRenderer\.invoke\("app:version"\)/);
  assert.match(preload, /restart: \(\) => ipcRenderer\.invoke\("app:restart"\)/);
});
