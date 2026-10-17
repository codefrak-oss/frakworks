// docker-bin.test.js — which `docker` the Update engine button runs
// (src/docker-bin.js), by claude-bin's rules: env, PATH, login shell, fallbacks.
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { resolveDockerBin } = require("../src/docker-bin");

const HOME = "/Users/someone";
const exists = (set) => (file) => set.has(file);
const shellSaying = (p) => () => `${p}\n`;
const shellFails = () => { throw new Error("no shell"); };

test("AGENT_ARMY_DOCKER_BIN wins verbatim, nothing is checked", () => {
  const r = resolveDockerBin({ env: { AGENT_ARMY_DOCKER_BIN: "/x/docker", PATH: "/usr/bin" }, home: HOME,
    existsFn: () => { throw new Error("must not be called"); }, execFn: shellFails });
  assert.deepEqual(r, { command: "/x/docker", source: "env", searched: "/usr/bin" });
});

test("a docker on the process PATH is used without asking the shell", () => {
  const env = { PATH: "/usr/bin:/opt/bin", SHELL: "/bin/zsh" };
  const r = resolveDockerBin({ env, home: HOME, existsFn: exists(new Set(["/opt/bin/docker"])), execFn: () => { throw new Error("must not ask"); } });
  assert.equal(r.command, "/opt/bin/docker");
  assert.equal(r.source, "path");
  assert.equal(env.PATH, "/usr/bin:/opt/bin");
});

test("launchd PATH: the login shell's PATH finds Homebrew's docker and widens PATH", () => {
  const env = { PATH: "/usr/bin:/bin:/usr/sbin:/sbin", SHELL: "/bin/zsh" };
  const r = resolveDockerBin({ env, home: HOME, existsFn: exists(new Set(["/opt/homebrew/bin/docker"])),
    execFn: shellSaying("/opt/homebrew/bin:/usr/bin:/bin") });
  assert.equal(r.command, "/opt/homebrew/bin/docker");
  assert.equal(r.source, "login-shell");
  assert.equal(env.PATH, "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin");
});

test("a failing shell falls through to Docker Desktop's ~/.docker/bin", () => {
  const env = { PATH: "/usr/bin", SHELL: "/bin/zsh" };
  const bin = path.join(HOME, ".docker", "bin", "docker");
  const r = resolveDockerBin({ env, home: HOME, existsFn: exists(new Set([bin])), execFn: shellFails });
  assert.equal(r.command, bin);
  assert.equal(r.source, "fallback");
  assert.equal(env.PATH, `${HOME}/.docker/bin:/usr/bin`);
});

test("nothing found: run 'docker' anyway and report the PATH searched", () => {
  const env = { PATH: "/usr/bin", SHELL: "/bin/zsh" };
  const r = resolveDockerBin({ env, home: HOME, existsFn: () => false, execFn: shellSaying("/usr/bin:/bin") });
  assert.equal(r.command, "docker");
  assert.equal(r.source, "unresolved");
  assert.equal(r.searched, "/usr/bin:/bin");
});
