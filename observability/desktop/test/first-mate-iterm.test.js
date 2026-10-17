// Open in Claude Code ⇗ (#820): src/first-mate-iterm.js. Pure pieces with
// the platform, fs and the osascript runner injected; nothing here opens a
// terminal or runs osascript.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const {
  NOT_MAC, NOT_INSTALLED, AUTOMATION_REFUSED,
  detectIterm, shellQuote, launcherScript, itermScript, writeHandoff, openInIterm, sweepHandoffDir,
} = require("../src/first-mate-iterm");

const tmpdir = () => fs.mkdtempSync(path.join(os.tmpdir(), "first-mate-iterm-test-"));
const fakeFs = (present) => ({ existsSync: (p) => present.includes(p) });

// ---------------------------------------------------------------------------
// detectIterm
// ---------------------------------------------------------------------------

test("detectIterm: not macOS is the Mac-only reason, whatever is on disk", () => {
  const r = detectIterm({ platform: "linux", home: "/home/u", env: {}, fs: fakeFs(["/Applications/iTerm.app"]) });
  assert.deepEqual(r, { available: false, why: NOT_MAC, app: null });
  assert.equal(NOT_MAC, "Only on macOS: iTerm2 is a Mac app.");
});

test("detectIterm: macOS without the app in either Applications folder says where it looked", () => {
  const r = detectIterm({ platform: "darwin", home: "/Users/u", env: {}, fs: fakeFs([]) });
  assert.deepEqual(r, { available: false, why: NOT_INSTALLED, app: null });
  assert.equal(NOT_INSTALLED, "iTerm2 is not installed (looked in /Applications and ~/Applications).");
});

test("detectIterm: /Applications first, then ~/Applications", () => {
  assert.deepEqual(detectIterm({ platform: "darwin", home: "/Users/u", env: {}, fs: fakeFs(["/Applications/iTerm.app"]) }),
    { available: true, why: null, app: "/Applications/iTerm.app" });
  assert.deepEqual(detectIterm({ platform: "darwin", home: "/Users/u", env: {}, fs: fakeFs(["/Users/u/Applications/iTerm.app"]) }),
    { available: true, why: null, app: "/Users/u/Applications/iTerm.app" });
});

test("detectIterm: AGENT_ARMY_ITERM_APP names the bundle, and is the only place looked when set", () => {
  const env = { AGENT_ARMY_ITERM_APP: "/opt/iTerm.app" };
  assert.deepEqual(detectIterm({ platform: "darwin", home: "/Users/u", env, fs: fakeFs(["/opt/iTerm.app"]) }),
    { available: true, why: null, app: "/opt/iTerm.app" });
  const missing = detectIterm({ platform: "darwin", home: "/Users/u", env, fs: fakeFs(["/Applications/iTerm.app"]) });
  assert.equal(missing.available, false);
  assert.match(missing.why, /\/opt\/iTerm\.app.*AGENT_ARMY_ITERM_APP/);
});

test("detectIterm: an fs that throws is 'not installed', never an exception", () => {
  const r = detectIterm({ platform: "darwin", home: "/Users/u", env: {}, fs: { existsSync: () => { throw new Error("EACCES"); } } });
  assert.equal(r.available, false);
  assert.equal(r.why, NOT_INSTALLED);
});

// ---------------------------------------------------------------------------
// The launcher
// ---------------------------------------------------------------------------

test("shellQuote single-quotes, escaping a quote as '\\''", () => {
  assert.equal(shellQuote("plain"), "'plain'");
  assert.equal(shellQuote("it's here"), "'it'\\''s here'");
  assert.equal(shellQuote("$HOME `x` \"y\""), "'$HOME `x` \"y\"'");
});

test("the resume launcher deletes itself first, cds to the cwd, execs claude --resume --fork-session, and passes no --worktree", () => {
  const text = launcherScript({ claude: "/Users/u/.local/bin/claude", cwd: "/Users/u/dev/frakworks", resumeId: "abc-123" });
  assert.equal(text, [
    "#!/bin/sh",
    'rm -f -- "$0"',
    "cd '/Users/u/dev/frakworks' || exit 1",
    "exec '/Users/u/.local/bin/claude' --resume 'abc-123' --fork-session",
    "",
  ].join("\n"));
  assert.doesNotMatch(text, /--worktree/, "the user's decision: CLAUDE.md's rule moves the session to a worktree");
  assert.doesNotMatch(text, /seed/);
});

test("a resume launcher with fork: false resumes the session as itself (#1937)", () => {
  const text = launcherScript({ claude: "claude", cwd: "/Users/u/dev/x", resumeId: "abc-123", fork: false });
  assert.equal(text, [
    "#!/bin/sh",
    'rm -f -- "$0"',
    "cd '/Users/u/dev/x' || exit 1",
    "exec 'claude' --resume 'abc-123'",
    "",
  ].join("\n"));
});

test("the seed launcher reads the seed into a variable, removes both files, cds, and execs claude with the seed", () => {
  const text = launcherScript({ claude: "/opt/claude", cwd: "/Users/u/dev/frakworks", seedPath: "/Users/u/Library/x/handoff/id.md" });
  assert.equal(text, [
    "#!/bin/sh",
    "seed=$(cat '/Users/u/Library/x/handoff/id.md')",
    "rm -f -- \"$0\" '/Users/u/Library/x/handoff/id.md'",
    "cd '/Users/u/dev/frakworks' || exit 1",
    "exec '/opt/claude' \"$seed\"",
    "",
  ].join("\n"));
  assert.doesNotMatch(text, /--resume|--fork-session|--worktree/);
});

test("a cwd and a path with a quote and a space are shell-escaped, never interpolated", () => {
  const cwd = "/Users/u/Matt's repos/frak works";
  const claude = "/Applications/Claude Code's/bin/claude";
  const seedPath = "/tmp/it's a seed.md";
  const resume = launcherScript({ claude, cwd, resumeId: "s-1" });
  assert.match(resume, /cd '\/Users\/u\/Matt'\\''s repos\/frak works' \|\| exit 1/);
  assert.match(resume, /exec '\/Applications\/Claude Code'\\''s\/bin\/claude' --resume 's-1' --fork-session/);
  const seed = launcherScript({ claude, cwd, seedPath });
  assert.match(seed, /seed=\$\(cat '\/tmp\/it'\\''s a seed\.md'\)/);
  assert.match(seed, /rm -f -- "\$0" '\/tmp\/it'\\''s a seed\.md'/);
  // A session id with shell metacharacters is quoted too, so it cannot run.
  const odd = launcherScript({ claude: "claude", cwd: "/x", resumeId: "a; rm -rf /" });
  assert.match(odd, /--resume 'a; rm -rf \/' --fork-session/);
});

test("launcherScript refuses to build without claude, cwd, or a resumeId/seedPath", () => {
  assert.throws(() => launcherScript({ cwd: "/x", resumeId: "s" }));
  assert.throws(() => launcherScript({ claude: "claude", resumeId: "s" }));
  assert.throws(() => launcherScript({ claude: "claude", cwd: "/x" }));
});

// ---------------------------------------------------------------------------
// The AppleScript
// ---------------------------------------------------------------------------

test("itermScript tells iTerm2 to activate and open a default-profile window running /bin/sh on the launcher", () => {
  const s = itermScript("/Users/u/Library/Application Support/frakworks/first-mate/handoff/id.sh");
  assert.equal(s, [
    'tell application "iTerm2"',
    "  activate",
    '  create window with default profile command "/bin/sh \'/Users/u/Library/Application Support/frakworks/first-mate/handoff/id.sh\'"',
    "end tell",
    "",
  ].join("\n"));
});

test("itermScript escapes a quote and a backslash in the launcher path for both the shell and AppleScript", () => {
  const s = itermScript('/tmp/it\'s "odd"\\here.sh');
  assert.match(s, /command "\/bin\/sh '\/tmp\/it'\\\\''s \\"odd\\"\\\\here\.sh'"/);
});

// ---------------------------------------------------------------------------
// writeHandoff
// ---------------------------------------------------------------------------

test("writeHandoff (resume) writes one 0700 launcher under the dir, created 0700, and no seed", () => {
  const dir = path.join(tmpdir(), "handoff");
  const r = writeHandoff(dir, { mode: "resume", resumeId: "s-9", claude: "/opt/claude", cwd: "/repo" }, { uuid: () => "fixed-id" });
  assert.deepEqual(r, { launcherPath: path.join(dir, "fixed-id.sh"), seedPath: null });
  assert.deepEqual(fs.readdirSync(dir), ["fixed-id.sh"]);
  assert.equal(fs.statSync(dir).mode & 0o777, 0o700);
  assert.equal(fs.statSync(r.launcherPath).mode & 0o777, 0o700);
  assert.match(fs.readFileSync(r.launcherPath, "utf8"), /--resume 's-9' --fork-session/);
});

test("writeHandoff (resume, fork: false) writes a launcher without --fork-session", () => {
  const dir = path.join(tmpdir(), "handoff");
  const r = writeHandoff(dir, { mode: "resume", resumeId: "s-9", fork: false, claude: "/opt/claude", cwd: "/repo" }, { uuid: () => "id" });
  const text = fs.readFileSync(r.launcherPath, "utf8");
  assert.match(text, /--resume 's-9'$/m);
  assert.doesNotMatch(text, /--fork-session/);
});

test("writeHandoff (seed) writes the seed 0600 beside the launcher, and the launcher's rm -f names it", () => {
  const dir = path.join(tmpdir(), "handoff");
  const r = writeHandoff(dir, { mode: "seed", seed: "The user: hi\n\nFirst mate: hello", claude: "/opt/claude", cwd: "/repo" },
    { uuid: () => "fixed-id" });
  assert.equal(r.seedPath, path.join(dir, "fixed-id.md"));
  assert.equal(fs.statSync(r.seedPath).mode & 0o777, 0o600);
  assert.equal(fs.readFileSync(r.seedPath, "utf8"), "The user: hi\n\nFirst mate: hello");
  const script = fs.readFileSync(r.launcherPath, "utf8");
  assert.ok(script.includes(`rm -f -- "$0" ${shellQuote(r.seedPath)}`));
  assert.ok(script.includes(`seed=$(cat ${shellQuote(r.seedPath)})`));
});

// ---------------------------------------------------------------------------
// openInIterm
// ---------------------------------------------------------------------------

const writeBoth = () => {
  const dir = path.join(tmpdir(), "handoff");
  return { dir, ...writeHandoff(dir, { mode: "seed", seed: "x", claude: "c", cwd: "/r" }) };
};

test("openInIterm runs osascript -e with the iTerm2 script, once, and leaves the files for the launcher to delete", async () => {
  const { launcherPath, seedPath } = writeBoth();
  const calls = [];
  const r = await openInIterm(launcherPath, { seedPath, execFile: async (script, timeoutMs) => { calls.push([script, timeoutMs]); return { err: null, stdout: "", stderr: "" }; } });
  assert.deepEqual(r, { ok: true });
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], itermScript(launcherPath));
  assert.equal(calls[0][1], 10000);
  assert.ok(fs.existsSync(launcherPath) && fs.existsSync(seedPath), "the launcher deletes them, not main");
});

test("a -1743 from osascript is the Automation text, and the launcher and seed are deleted", async () => {
  const { launcherPath, seedPath } = writeBoth();
  const r = await openInIterm(launcherPath, { seedPath, execFile: async () => ({
    err: Object.assign(new Error("Command failed"), { code: 1 }),
    stdout: "", stderr: "execution error: Not authorized to send Apple events to iTerm2. (-1743)\n" }) });
  assert.deepEqual(r, { ok: false, error: AUTOMATION_REFUSED });
  assert.equal(AUTOMATION_REFUSED, "macOS refused automation of iTerm2. Allow it in System Settings > Privacy & Security > Automation.");
  assert.ok(!fs.existsSync(launcherPath), "launcher removed");
  assert.ok(!fs.existsSync(seedPath), "seed removed");
});

test("another osascript failure is shown as its first stderr line; a timeout and a missing osascript say so", async () => {
  const { launcherPath, seedPath } = writeBoth();
  const r = await openInIterm(launcherPath, { seedPath, execFile: async () => ({
    err: Object.assign(new Error("Command failed"), { code: 1 }), stdout: "",
    stderr: "\nexecution error: iTerm2 got an error: Connection is invalid. (-609)\n" }) });
  assert.deepEqual(r, { ok: false, error: "execution error: iTerm2 got an error: Connection is invalid. (-609)" });
  assert.ok(!fs.existsSync(launcherPath));
  const t = await openInIterm("/nonexistent/x.sh", { execFile: async () => ({ err: Object.assign(new Error("killed"), { killed: true }), stderr: "" }) });
  assert.match(t.error, /did not answer within 10 s/);
  const e = await openInIterm("/nonexistent/x.sh", { execFile: async () => ({ err: Object.assign(new Error("spawn osascript ENOENT"), { code: "ENOENT" }), stderr: "" }) });
  assert.match(e.error, /osascript is not on this machine/);
  const thrown = await openInIterm("/nonexistent/x.sh", { execFile: async () => { throw new Error("boom"); } });
  assert.deepEqual(thrown, { ok: false, error: "boom" });
});

// ---------------------------------------------------------------------------
// sweepHandoffDir
// ---------------------------------------------------------------------------

test("sweepHandoffDir removes only files older than the cutoff, and a missing dir is fine", () => {
  const dir = path.join(tmpdir(), "handoff");
  fs.mkdirSync(dir);
  const old = path.join(dir, "old.sh"), oldSeed = path.join(dir, "old.md"), fresh = path.join(dir, "fresh.sh");
  for (const p of [old, oldSeed, fresh]) fs.writeFileSync(p, "x");
  const now = Date.now();
  const twoHoursAgo = new Date(now - 2 * 3600000);
  fs.utimesSync(old, twoHoursAgo, twoHoursAgo);
  fs.utimesSync(oldSeed, twoHoursAgo, twoHoursAgo);
  const removed = sweepHandoffDir(dir, 3600000, { now: () => now });
  assert.deepEqual(removed.sort(), [oldSeed, old].sort());
  assert.deepEqual(fs.readdirSync(dir), ["fresh.sh"]);
  assert.deepEqual(sweepHandoffDir(path.join(dir, "nope"), 3600000), []);
});

test("a kubernetes conversation opens in iTerm2 seeded, never --resume, its seed saying it ran in a pod (#2523)", () => {
  const { FirstMateManager, ITERM_POD_SEED_HEAD } = require("../src/first-mate");
  const dir = tmpdir();
  const m = new FirstMateManager({ dir, cwd: dir, sessionFileExistsFn: () => true });
  m.session.runtime = "kubernetes";
  m.session.claude_session_id = "pod-claude-1";
  m.session.history = [{ role: "user", text: "hello", at: 1 }];
  const plan = m.itermPlan();
  assert.equal(plan.mode, "seed");
  assert.equal(plan.seed.split("\n")[0], ITERM_POD_SEED_HEAD);
  const script = launcherScript({ claude: "/bin/claude", cwd: dir, seedPath: path.join(dir, "s.md") });
  assert.doesNotMatch(script, /--resume/);
});
