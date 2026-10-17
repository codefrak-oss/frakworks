// #2363: a repo picked before a new conversation's first turn moves its cwd
// cwd stays at the clones root and the MCP config lacks the repo's servers.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const D = path.join(__dirname, "..");
const { FirstMateManager } = require(path.join(D, "src/first-mate"));
const { makeFakeChild } = require(path.join(D, "test/fake-claude-child"));

test("new conversation + picker repo before first message -> clone cwd and repo MCP servers", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fm2363-"));
  const freshDir = path.join(dir, "fresh");
  const clone = path.join(freshDir, "acme", "widgets");
  fs.mkdirSync(clone, { recursive: true });
  const { command, launchArgs } = makeFakeChild(path.join(dir, "bin"), { argvDump: path.join(dir, "argv.json") });
  const asked = [];
  const m = new FirstMateManager({
    dir, command, launchArgs, cwd: freshDir, freshDir,
    submitIntakeFn: async () => ({ ok: true }), resolveTierFn: () => ({ model: "sonnet", effort: "medium" }),
    fetchFlowFn: async () => ({ error: "x" }), engineEnvFn: async () => ({ ENGINE_URL: "http://127.0.0.1:9" }),
    repoFn: () => null, // switcher on All repos
    mcpServersFn: async (r) => { asked.push(r); return { servers: { ops: { type: "http", url: "https://x/mcp", headers: {} } } }; },
  }).init();
  m.startNewConversation();          // picker: "No repo yet"
  m.setChatRepo("acme/widgets");     // picker: REPO acme/widgets
  await m.send("hello");
  m.stop();
  const cfg = JSON.parse(fs.readFileSync(m.mcpConfigPath, "utf8"));
  assert.equal(m.chatRepo(), "acme/widgets");
  assert.equal(path.resolve(m.conversationCwd()), path.resolve(clone), "cwd should be the picked repo's clone");
  assert.ok(cfg.mcpServers.ops, `repo's MCP server missing; asked=${JSON.stringify(asked)}`);
});

test("a started session whose repo and cwd disagree is flagged on the strip", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fm2363-"));
  const freshDir = path.join(dir, "fresh");
  fs.mkdirSync(freshDir, { recursive: true });
  const m = new FirstMateManager({ dir, command: "true", cwd: freshDir, freshDir, repoFn: () => null }).init();
  m.session.history.push({ role: "user", text: "hi", at: 1 });
  m.session.cwd = freshDir;
  m.session.repo = "example-org/example-repo";
  assert.match(m.repoMismatch(), /example-org\/example-repo/);
  assert.match(m.snapshot().notice, /not loaded/);
  m.setChatRepo("example-org/example-repo");
  assert.equal(path.resolve(m.conversationCwd()), path.resolve(freshDir), "a started conversation's cwd stays");
  m.session.repo = null;
  assert.equal(m.repoMismatch(), null);
});

test("an unstarted session with repo set and cwd at the root is repaired on load", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fm2363-"));
  const freshDir = path.join(dir, "fresh");
  const a = new FirstMateManager({ dir, command: "true", cwd: freshDir, freshDir, repoFn: () => null }).init();
  a.session.repo = "acme/widgets";
  a.session.cwd = freshDir;
  a._save();
  const b = new FirstMateManager({ dir, command: "true", cwd: freshDir, freshDir, repoFn: () => null }).init();
  assert.equal(path.resolve(b.conversationCwd()), path.join(freshDir, "acme", "widgets"));
});

test("switcher repo picked before Start new conversation -> the new chat's cwd and MCP servers (#2551)", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fm2551-"));
  const freshDir = path.join(dir, "fresh");
  const clone = path.join(freshDir, "acme", "widgets");
  fs.mkdirSync(clone, { recursive: true });
  const { command, launchArgs } = makeFakeChild(path.join(dir, "bin"), { argvDump: path.join(dir, "argv.json") });
  const m = new FirstMateManager({
    dir, command, launchArgs, cwd: freshDir, freshDir,
    submitIntakeFn: async () => ({ ok: true }), resolveTierFn: () => ({ model: "sonnet", effort: "medium" }),
    fetchFlowFn: async () => ({ error: "x" }), engineEnvFn: async () => ({ ENGINE_URL: "http://127.0.0.1:9" }),
    repoFn: () => "acme/widgets", // switcher set first
    mcpServersFn: async () => ({ servers: { ops: { type: "http", url: "https://x/mcp", headers: {} } } }),
  }).init();
  m.startNewConversation();
  await m.send("hello");
  m.stop();
  const cfg = JSON.parse(fs.readFileSync(m.mcpConfigPath, "utf8"));
  assert.equal(m.chatRepo(), "acme/widgets");
  assert.equal(path.resolve(m.conversationCwd()), path.resolve(clone));
  assert.ok(cfg.mcpServers.ops);
});

// #100116: a new chat in All repos starts with the last-used repo.
const stickyManager = (dir, over = {}) => new FirstMateManager({
  dir, command: "true", cwd: dir, repoFn: () => null, reposFn: () => ["acme/widgets", "acme/other"], ...over,
}).init();

test("sticky: after a chat in o/n, a new chat in All repos starts in o/n, tagged last used (#100116)", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fm100116-"));
  const m = stickyManager(dir);
  m.startNewConversation();
  assert.equal(m.chatRepo(), null, "no last repo yet");
  m.setChatRepo("acme/widgets");
  assert.equal(m.snapshot().chat_repo_sticky, false);
  m.session.history.push({ role: "user", text: "hi", at: 1 });
  m.startNewConversation();
  assert.equal(m.chatRepo(), "acme/widgets");
  assert.equal(m.snapshot().chat_repo_sticky, true);
  // kept across a restart
  const b = stickyManager(dir);
  b.startNewConversation();
  assert.equal(b.chatRepo(), "acme/widgets");
  // the picker still changes it, and the tag goes
  b.setChatRepo("acme/other");
  assert.equal(b.chatRepo(), "acme/other");
  assert.equal(b.snapshot().chat_repo_sticky, false);
});

test("sticky: the switcher's selection wins; a removed repo is not reused (#100116)", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fm100116-"));
  let sel = "acme/other";
  let repos = ["acme/widgets", "acme/other"];
  const m = stickyManager(dir, { repoFn: () => sel, reposFn: () => repos });
  m.session.last_repo = "acme/widgets";
  m.startNewConversation();
  assert.equal(m.chatRepo(), "acme/other");
  assert.equal(m.snapshot().chat_repo_sticky, false);
  sel = null;
  repos = ["acme/other"];
  m.startNewConversation();
  assert.equal(m.chatRepo(), null, "never runs in a repo that is no longer a target");
  assert.equal(m.session.last_repo, null);
});
