// The desktop's test of a stdio MCP server (#1801): src/mcp-stdio-probe.js
// spawns it and does the MCP handshake over newline-delimited JSON-RPC.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { probeStdio } = require("../src/mcp-stdio-probe");

const SERVER = `
const rl = require("node:readline").createInterface({ input: process.stdin });
rl.on("line", (l) => {
  const m = JSON.parse(l);
  if (m.method === "initialize") console.log(JSON.stringify({ jsonrpc: "2.0", id: m.id, result: { protocolVersion: "2025-03-26", capabilities: {}, serverInfo: { name: "s", version: "1" } } }));
  if (m.method === "tools/list") console.log(JSON.stringify({ jsonrpc: "2.0", id: m.id, result: { tools: [{ name: process.env.TOOL }, { name: "two" }] } }));
});`;

test("a stdio server that answers: reached, with its tools; env reaches it", async () => {
  const r = await probeStdio({ command: process.execPath, args: ["-e", SERVER], env: { TOOL: "one" } }, { timeoutMs: 10000 });
  assert.deepEqual(r, { reached: true, tools: ["one", "two"], error: null });
});

test("a command that does not exist, or exits, is not reached with the error", async () => {
  const missing = await probeStdio({ command: "/nonexistent/mcp-server" }, { timeoutMs: 5000 });
  assert.equal(missing.reached, false);
  assert.match(missing.error, /ENOENT/);
  const exits = await probeStdio({ command: process.execPath, args: ["-e", "process.exit(3)"] }, { timeoutMs: 5000 });
  assert.equal(exits.reached, false);
  assert.match(exits.error, /exited with 3/);
});
