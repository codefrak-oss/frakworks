// Config > frakworks > Engine URL (#100144): the setting under userData, and
// an app with none set makes no engine call and says where to enter one.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const setting = require("../src/engine-url-setting");
const { getJson, sendJson, fetchIssues, fetchWhoAmI, engineUrl, setEngineUrl, NO_ENGINE_PROMPT } = require("../src/engine-data");
const { EngineEventStream } = require("../src/engine-events");
const { frakworksMcpConfigObject } = require("../src/claude-session");
const { createFreshCode } = require("../src/fresh-code");

const tmp = () => path.join(fs.mkdtempSync(path.join(os.tmpdir(), "engine-url-")), setting.FILE_NAME);

test("the setting: missing is null; a saved URL loads back without its trailing slash; a bad one is refused; empty clears", () => {
  const f = tmp();
  assert.equal(setting.load(f), null);
  assert.deepEqual(setting.save(f, " https://engine.example/ "), { url: "https://engine.example" });
  assert.equal(setting.load(f), "https://engine.example");
  assert.match(setting.save(f, "ftp://x").error, /not an http/);
  assert.match(setting.save(f, "https://u:p@engine.example").error, /no credentials/);
  assert.match(setting.save(f, "nope").error, /not a URL/);
  assert.equal(setting.load(f), "https://engine.example", "a refused save keeps the old value");
  assert.deepEqual(setting.save(f, ""), { url: null });
  assert.equal(setting.load(f), null);
  fs.writeFileSync(f, "{ not json");
  assert.equal(setting.load(f), null);
});

test("unset: no default, every read and write answers the Config prompt and fetches nothing", async () => {
  setEngineUrl(null);
  assert.equal(engineUrl(), null);
  assert.match(NO_ENGINE_PROMPT, /Config > frakworks > Engine URL/);
  const calls = [];
  const fetch = async (u) => { calls.push(u); throw new Error("no call expected"); };
  assert.deepEqual(await getJson("/tiers", { fetch }), { error: NO_ENGINE_PROMPT });
  assert.deepEqual(await sendJson("PUT", "/intake/tier", { tier: 1 }, { fetch }), { error: NO_ENGINE_PROMPT });
  assert.equal((await fetchIssues({ fetch })).error, NO_ENGINE_PROMPT);
  assert.equal((await fetchWhoAmI("t", { fetch })).error, NO_ENGINE_PROMPT);
  assert.deepEqual(calls, []);
});

test("set: calls go to the setting's URL", async () => {
  setEngineUrl("https://engine.example/");
  const calls = [];
  const fetch = async (u) => { calls.push(u); return { ok: true, status: 200, text: async () => "{}" }; };
  assert.deepEqual(await getJson("/tiers", { fetch }), { value: {} });
  assert.deepEqual(calls, ["https://engine.example/tiers"]);
  setEngineUrl(null);
});

test("unset: the event stream opens no connection", async () => {
  setEngineUrl(null);
  const calls = [];
  const ev = new EngineEventStream({ fetch: async (u) => { calls.push(u); throw new Error("no"); }, send: () => {}, log: () => {} });
  ev.subscribe("*");
  await new Promise((r) => setImmediate(r));
  ev.close();
  assert.deepEqual(calls, []);
});

test("unset: no MCP config with a guessed engine, and no clone", async () => {
  assert.throws(() => frakworksMcpConfigObject({}), /no engine URL set/);
  assert.equal(frakworksMcpConfigObject({ engineUrl: "https://engine.example/" }).mcpServers.frakworks.url, "https://engine.example/mcp/frakworks");
  const fc = createFreshCode({ freshDir: fs.mkdtempSync(path.join(os.tmpdir(), "fresh-")), spawn: () => { throw new Error("no git expected"); }, log: () => {} });
  assert.match((await fc.ensureClone("o/r")).error, /no engine URL set/);
});
