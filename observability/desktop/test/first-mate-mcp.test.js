// First mate's per-repo MCP servers at spawn (#1801): FirstMateManager asks
// `mcpServersFn(repo)` for the repo's servers (the engine's
// GET /repos/{o}/{n}/mcp-servers/config) and writes them into
// frakworks-mcp.json beside the engine's two (frakworks and the diagnostics
// server; hitlp is gone since #2484), allowing `mcp__<server>__*`; an engine
// that cannot hand them over leaves the two and a one-line notice.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const {
  CHAT_READ_TOOLS, FIRST_MATE_FILING_TOOLS, FRAKWORKS_MCP_SERVER, DIAGNOSTICS_MCP_SERVER, firstMateMemoryRule, frakworksMcpConfigObject,
} = require("../src/claude-session");
const { FirstMateManager } = require("../src/first-mate");
const { makeFakeChild } = require("./fake-claude-child");

const REPO = "acme/widgets";
const ENGINE = (name) => ({ type: "http", url: `http://127.0.0.1:9/mcp/${name}`, headers: {} });
const TWO = { [FRAKWORKS_MCP_SERVER]: ENGINE(FRAKWORKS_MCP_SERVER), [DIAGNOSTICS_MCP_SERVER]: ENGINE(DIAGNOSTICS_MCP_SERVER) };
const HTTP = { type: "http", url: "https://mcp.example.com/mcp", headers: { Authorization: "Bearer s3cret" } };
const STDIO = { type: "stdio", command: "npx", args: ["-y", "docs-mcp"], env: { TOKEN: "t0k" } };

const start = async (mcpServersFn) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "first-mate-mcp-"));
  const freshDir = path.join(dir, "fresh");
  const cwd = path.join(freshDir, "acme", "widgets");
  fs.mkdirSync(cwd, { recursive: true });
  const dump = path.join(dir, "argv.json");
  const asked = [];
  const { command, launchArgs } = makeFakeChild(path.join(dir, "bin"), { argvDump: dump });
  const m = new FirstMateManager({
    dir, command, launchArgs, cwd, freshDir,
    submitIntakeFn: async () => ({ ok: true, intakeId: "intake-1" }),
    resolveTierFn: () => ({ model: "sonnet", effort: "medium" }),
    fetchFlowFn: async () => ({ error: "no engine in tests" }),
    engineEnvFn: async () => ({ engineUrl: "http://127.0.0.1:9" }),
    repoFn: () => REPO,
    mcpServersFn: async (repo) => { asked.push(repo); return mcpServersFn(repo); },
  }).init();
  await m.send("hello");
  const argv = JSON.parse(fs.readFileSync(dump, "utf8"));
  m.stop();
  const cfg = JSON.parse(fs.readFileSync(m.mcpConfigPath, "utf8"));
  const settings = JSON.parse(fs.readFileSync(m.settingsPath, "utf8"));
  return { m, cwd, argv, cfg, settings, asked, mode: fs.statSync(m.mcpConfigPath).mode & 0o777 };
};

const base = (cwd) => [...CHAT_READ_TOOLS, ...FIRST_MATE_FILING_TOOLS, firstMateMemoryRule(cwd)];

test("a repo with one http and one stdio server: both beside the engine's three, each allowed whole", async () => {
  const { m, cwd, argv, cfg, settings, asked, mode } = await start(async () => ({ servers: { docs: HTTP, local: STDIO } }));
  assert.deepEqual(asked, [REPO]);
  assert.deepEqual(cfg, { mcpServers: { ...TWO, docs: HTTP, local: STDIO } });
  assert.equal(mode, 0o600, "the secrets are on disk only in a 0600 file");
  const allow = [...base(cwd), "mcp__docs__*", "mcp__local__*"];
  assert.deepEqual(settings.permissions.allow, allow);
  assert.equal(argv[argv.indexOf("--allowedTools") + 1], allow.join(","));
  assert.ok(argv.includes("--strict-mcp-config"));
  // #2484: no hitlp server and no mcp__hitlp__* tool.
  assert.equal(cfg.mcpServers.hitlp, undefined);
  assert.ok(allow.every((t) => !t.startsWith("mcp__hitlp__")), allow.join(","));
  assert.equal(argv[argv.indexOf("--mcp-config") + 1], m.mcpConfigPath);
  assert.equal(m.snapshot().mcp_notice, null);
});

test("a repo with none: the document and the allow list are today's", async () => {
  const { cwd, cfg, settings } = await start(async () => ({ servers: {} }));
  assert.deepEqual(cfg, { mcpServers: TWO });
  assert.deepEqual(cfg, frakworksMcpConfigObject({ engineUrl: "http://127.0.0.1:9" }));
  assert.deepEqual(settings.permissions.allow, base(cwd));
});

test("an engine that cannot hand them over: the engine's two only, and a one-line notice naming the repo", async () => {
  const { m, cwd, cfg, settings } = await start(async () => ({ error: "engine unreachable at http://127.0.0.1:9/repos/acme/widgets/mcp-servers/config: ECONNREFUSED" }));
  assert.deepEqual(cfg, { mcpServers: TWO });
  assert.deepEqual(settings.permissions.allow, base(cwd));
  const notice = m.snapshot().mcp_notice;
  assert.match(notice, /acme\/widgets/);
  assert.match(notice, /engine's two only/);
  assert.doesNotMatch(notice, /\n/);
  assert.equal(m.mcpConfigRepo, undefined, "the next child start asks again");
});

test("a repo server named as one of the engine's cannot replace it", () => {
  const cfg = frakworksMcpConfigObject({ engineUrl: "http://e", repoServers: { frakworks: HTTP, other: HTTP } });
  assert.equal(cfg.mcpServers.frakworks.url, "http://e/mcp/frakworks");
  assert.deepEqual(cfg.mcpServers.other, HTTP);
});

// Sign in to the engine (#2206): the engine token
// in the two engine servers' headers, when main's
// engineEnvFn carries one; a sign-in or sign-out makes the next start rewrite.
test("frakworksMcpConfigObject with a token writes Authorization on the two engine servers alone (no X-Frakworks-Login), not on a repo server; without one, no header", () => {
  const withToken = frakworksMcpConfigObject({ engineUrl: "http://e", token: "frk_test_x", repoServers: { docs: HTTP } });
  for (const name of [FRAKWORKS_MCP_SERVER, DIAGNOSTICS_MCP_SERVER]) {
    assert.deepEqual(withToken.mcpServers[name].headers, { Authorization: "Bearer frk_test_x" }, name);
  }
  assert.deepEqual(withToken.mcpServers.docs, HTTP, "a repo server's headers are its own");
  const tokenOnly = frakworksMcpConfigObject({ engineUrl: "http://e", token: " frk_test_x " });
  assert.deepEqual(tokenOnly.mcpServers[DIAGNOSTICS_MCP_SERVER].headers, { Authorization: "Bearer frk_test_x" });
  const none = frakworksMcpConfigObject({ engineUrl: "http://e", token: "" });
  assert.deepEqual(none.mcpServers[DIAGNOSTICS_MCP_SERVER].headers, {});
  assert.deepEqual(frakworksMcpConfigObject({ engineUrl: "http://e" }).mcpServers[FRAKWORKS_MCP_SERVER].headers, {});
});

test("the written config carries the token from engineEnvFn's ENGINE_TOKEN, mode 0600, and invalidateMcpConfig makes the next start rewrite it", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "first-mate-mcp-config-"));
  const freshDir = path.join(dir, "fresh");
  fs.mkdirSync(path.join(freshDir, "acme", "widgets"), { recursive: true });
  const dump = path.join(dir, "argv.json");
  const { command, launchArgs } = makeFakeChild(path.join(dir, "bin"), { argvDump: dump });
  let token = "frk_test_one";
  const m = new FirstMateManager({
    dir, command, launchArgs, cwd: path.join(freshDir, "acme", "widgets"), freshDir,
    submitIntakeFn: async () => ({ ok: true, intakeId: "intake-1" }),
    resolveTierFn: () => ({ model: "sonnet", effort: "medium" }),
    fetchFlowFn: async () => ({ error: "no engine in tests" }),
    engineEnvFn: async () => ({ engineUrl: "http://127.0.0.1:9", ENGINE_TOKEN: token }),
    repoFn: () => REPO,
    mcpServersFn: async () => ({ servers: {} }),
  }).init();
  await m.send("hello");
  m.stop();
  const read = () => JSON.parse(fs.readFileSync(m.mcpConfigPath, "utf8"));
  assert.equal(fs.statSync(m.mcpConfigPath).mode & 0o777, 0o600);
  assert.deepEqual(read().mcpServers[FRAKWORKS_MCP_SERVER].headers, { Authorization: "Bearer frk_test_one" });
  // the same repo, nothing changed: the file is kept as it is
  assert.equal(m._mcpStale(REPO), false);
  // a sign-out: the next start rewrites, without the header
  token = undefined;
  m.invalidateMcpConfig();
  assert.equal(m._mcpStale(REPO), true);
  await m._ensureMcpConfig(REPO);
  assert.deepEqual(read().mcpServers[FRAKWORKS_MCP_SERVER].headers, {});
  assert.equal(m._mcpStale(REPO), false);
  // the token never reaches the child's environment
  const argv = JSON.parse(fs.readFileSync(dump, "utf8"));
  assert.ok(!JSON.stringify(argv).includes("frk_test_one"));
});

test("a server added, then removed, for the same repo reaches the next child start (#2361)", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fm2361-"));
  let servers = {};
  let asks = 0;
  const m = new FirstMateManager({ dir, command: "true", launchArgs: [], cwd: dir, freshDir: dir,
    engineEnvFn: async () => ({ engineUrl: "http://127.0.0.1:9" }), repoFn: () => "acme/w",
    mcpServersFn: async () => { asks++; return { servers }; } }).init();
  await m._ensureMcpConfig("acme/w");
  assert.deepEqual(m.repoServerNames, []);
  servers = { "ops-server": { type: "http", url: "https://x/mcp", headers: {} } };
  let cfg = JSON.parse(fs.readFileSync(await m._ensureMcpConfig("acme/w"), "utf8"));
  assert.ok(cfg.mcpServers["ops-server"], "the added server is in the config");
  assert.deepEqual(m.repoServerNames, ["ops-server"], "the allow-list follows");
  servers = {};
  cfg = JSON.parse(fs.readFileSync(await m._ensureMcpConfig("acme/w"), "utf8"));
  assert.ok(!cfg.mcpServers["ops-server"], "the removed server is gone");
  assert.deepEqual(m.repoServerNames, []);
  assert.equal(asks, 3, "one GET per child start, no more");
});
