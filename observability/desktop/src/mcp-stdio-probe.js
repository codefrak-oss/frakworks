"use strict";

// Tests a stdio MCP server on this machine (#1801; engine/README.md, "First
// mate's MCP servers"): the engine cannot reach a program that runs here, so
// the Configure First Mate modal's Test spawns it the way Claude Code would
// (`command` with `args`, `env` over ours), writes an MCP `initialize`,
// `notifications/initialized` and `tools/list` as newline-delimited JSON-RPC
// on its stdin, reads the answers from its stdout, and kills it. Resolves to
// { reached, tools, error }; never rejects.

const { spawn: nodeSpawn } = require("node:child_process");

const PROTOCOL = "2025-03-26";
const STDIO_PROBE_TIMEOUT_MS = 15000;

function probeStdio({ command, args = [], env = {} } = {}, { spawn = nodeSpawn, timeoutMs = STDIO_PROBE_TIMEOUT_MS } = {}) {
  return new Promise((resolve) => {
    if (typeof command !== "string" || !command.trim()) { resolve({ reached: false, tools: [], error: "no command to run" }); return; }
    let child;
    let done = false;
    let buf = "";
    let stderr = "";
    const finish = (result) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      try { child && child.kill(); } catch { /* gone */ }
      resolve(result);
    };
    const timer = setTimeout(() => finish({ reached: false, tools: [], error: `no answer within ${timeoutMs / 1000}s${stderr ? `: ${stderr.trim().slice(-300)}` : ""}` }), timeoutMs);
    const send = (msg) => { try { child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", ...msg })}\n`); } catch (e) { finish({ reached: false, tools: [], error: e.message }); } };
    try {
      child = spawn(command, Array.isArray(args) ? args : [], { env: { ...process.env, ...(env || {}) }, stdio: ["pipe", "pipe", "pipe"] });
    } catch (e) { finish({ reached: false, tools: [], error: (e && e.message) || String(e) }); return; }
    child.on("error", (e) => finish({ reached: false, tools: [], error: (e && e.message) || String(e) }));
    child.on("exit", (code) => finish({ reached: false, tools: [], error: `exited with ${code}${stderr ? `: ${stderr.trim().slice(-300)}` : ""}` }));
    if (child.stderr) child.stderr.on("data", (d) => { stderr = (stderr + d).slice(-2000); });
    child.stdout.on("data", (d) => {
      buf += d;
      let nl;
      while ((nl = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line) continue;
        let msg;
        try { msg = JSON.parse(line); } catch { continue; }
        if (msg.error && (msg.id === 1 || msg.id === 2)) { finish({ reached: false, tools: [], error: msg.error.message || JSON.stringify(msg.error) }); return; }
        if (msg.id === 1) {
          send({ method: "notifications/initialized", params: {} });
          send({ id: 2, method: "tools/list", params: {} });
        } else if (msg.id === 2) {
          const tools = ((msg.result && msg.result.tools) || []).map((t) => t && t.name).filter((n) => typeof n === "string");
          finish({ reached: true, tools, error: null });
          return;
        }
      }
    });
    send({ id: 1, method: "initialize", params: { protocolVersion: PROTOCOL, capabilities: {}, clientInfo: { name: "frakworks-desktop", version: "1" } } });
  });
}

module.exports = { probeStdio, STDIO_PROBE_TIMEOUT_MS };
