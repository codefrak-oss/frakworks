const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { resolveClaudeBin, describeSpawnError, mergePath } = require("../src/claude-bin");

const HOME = "/Users/someone";
const exists = (set) => (file) => set.has(file);
const shellSaying = (p) => () => `${p}\n`;
const shellFails = () => { throw new Error("no shell"); };

test("AGENT_ARMY_CLAUDE_BIN wins verbatim, nothing is checked", () => {
  const r = resolveClaudeBin({ env: { AGENT_ARMY_CLAUDE_BIN: "/x/claude", PATH: "/usr/bin" }, home: HOME, existsFn: () => { throw new Error("must not be called"); }, execFn: shellFails });
  assert.deepEqual(r, { command: "/x/claude", source: "env", searched: "/usr/bin" });
});

test("a claude on the process PATH is used without asking the shell", () => {
  const env = { PATH: "/usr/bin:/opt/bin", SHELL: "/bin/zsh" };
  const r = resolveClaudeBin({ env, home: HOME, existsFn: exists(new Set(["/opt/bin/claude"])), execFn: () => { throw new Error("must not ask"); } });
  assert.equal(r.command, "/opt/bin/claude");
  assert.equal(r.source, "path");
  assert.equal(env.PATH, "/usr/bin:/opt/bin");
});

test("launchd PATH: the login shell's PATH finds ~/.local/bin/claude and widens PATH", () => {
  const env = { PATH: "/usr/bin:/bin:/usr/sbin:/sbin", SHELL: "/bin/zsh" };
  const bin = path.join(HOME, ".local", "bin", "claude");
  const r = resolveClaudeBin({ env, home: HOME, existsFn: exists(new Set([bin])), execFn: shellSaying(`${HOME}/.local/bin:/opt/homebrew/bin:/usr/bin:/bin`) });
  assert.equal(r.command, bin);
  assert.equal(r.source, "login-shell");
  assert.equal(env.PATH, `${HOME}/.local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin`);
});

test("a shell that fails or hangs falls through to the known install dirs", () => {
  const env = { PATH: "/usr/bin", SHELL: "/bin/zsh" };
  const bin = path.join(HOME, ".claude", "local", "claude");
  const r = resolveClaudeBin({ env, home: HOME, existsFn: exists(new Set([bin])), execFn: shellFails });
  assert.equal(r.command, bin);
  assert.equal(r.source, "fallback");
  assert.equal(env.PATH, `${HOME}/.claude/local:/usr/bin`);
});

test("nothing found: spawn 'claude' anyway and report the PATH searched", () => {
  const env = { PATH: "/usr/bin", SHELL: "/bin/zsh" };
  const r = resolveClaudeBin({ env, home: HOME, existsFn: () => false, execFn: shellSaying("/usr/bin:/bin") });
  assert.equal(r.command, "claude");
  assert.equal(r.source, "unresolved");
  assert.equal(r.searched, "/usr/bin:/bin");
});

test("shell rc noise before the PATH line is skipped; a non-path answer is ignored", () => {
  const env = { PATH: "/usr/bin", SHELL: "/bin/zsh" };
  const r1 = resolveClaudeBin({ env: { ...env }, home: HOME, existsFn: exists(new Set(["/opt/bin/claude"])), execFn: () => "Welcome!\n/opt/bin:/usr/bin\n" });
  assert.equal(r1.command, "/opt/bin/claude");
  const r2 = resolveClaudeBin({ env: { ...env }, home: HOME, existsFn: () => false, execFn: () => "oops" });
  assert.equal(r2.source, "unresolved");
});

test("mergePath keeps order and drops duplicates", () => {
  assert.equal(mergePath("/usr/bin:/bin", "/a:/usr/bin:/b"), "/a:/usr/bin:/b:/bin");
});

test("describeSpawnError names the PATH on ENOENT and passes other errors through", () => {
  const e = Object.assign(new Error("spawn claude ENOENT"), { code: "ENOENT" });
  assert.match(describeSpawnError(e, "claude", { PATH: "/usr/bin" }), /no claude found on PATH \(\/usr\/bin\); install Claude Code or set AGENT_ARMY_CLAUDE_BIN/);
  assert.equal(describeSpawnError(new Error("boom"), "claude"), "boom");
});
