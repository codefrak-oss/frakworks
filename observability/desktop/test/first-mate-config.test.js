// Config > Repos' Configure First Mate modal (#1801):
// src/renderer/first-mate-config.jsx's form mapping, its save-then-test,
// remove and test against a stand-in IPC, its rendering, the button on each
// repo tile, and src/engine-data.js's MCP server calls against a stand-in fetch.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const {
  CONFIG_TABS, MCP_AUDIENCES, MCP_ROLES, audienceLabel, roleLabel, emptyForm, formOf, serverBody, saveServer, removeServer, testServer,
  TestResult, ServerForm, McpServersPage, ConfigureFirstMateDialog,
} = require("../dist/first-mate-config.cjs");
const { Repos } = require("../dist/repos.cjs");
const { fetchRepoMcpServers, saveRepoMcpServer, deleteRepoMcpServer, testRepoMcpServer, fetchRepoMcpConfig } = require("../src/engine-data");

const REPO = "acme/widgets";
const HTTP = { repo: REPO, name: "docs", kind: "http", audience: "first-mate", url: "https://mcp.example.com/mcp",
  headers: { "X-Team": { secret: false, value: "core" }, Authorization: { secret: true, set: true, since: "2026-10-08T10:00:00Z" } },
  updatedAt: "2026-10-08T10:00:00Z", updatedBy: "alice" };
const STDIO = { repo: REPO, name: "local", kind: "stdio", audience: "both", command: "npx", args: ["-y", "docs-mcp"], env: {}, updatedAt: "x", updatedBy: null };
const CLUSTER = { repo: REPO, name: "ops-server", kind: "http", audience: "smith", url: "http://ops-server.ops.svc.cluster.local:8737/mcp",
  headers: { Authorization: { secret: true, set: true, since: "2026-10-11T10:00:00Z" } }, updatedAt: "x", updatedBy: "alice" };

/** A stand-in for window.fleet's four MCP calls, recording each. */
const fakeApi = ({ save = { ok: true }, del = { ok: true }, testAnswer = { reached: true, tools: ["search", "read"], error: null } } = {}) => {
  const calls = [];
  return {
    calls,
    mcpServers: async (repo) => { calls.push(["list", repo]); return { servers: [HTTP, STDIO] }; },
    saveMcpServer: async (repo, name, body) => { calls.push(["save", repo, name, body]); return save; },
    deleteMcpServer: async (repo, name) => { calls.push(["delete", repo, name]); return del; },
    testMcpServer: async (repo, name) => { calls.push(["test", repo, name]); return testAnswer; },
  };
};

test("the tab row has the MCP servers page, and is a list further pages join", () => {
  assert.deepEqual(CONFIG_TABS, [{ key: "mcp", label: "MCP servers" }]);
  const html = renderToStaticMarkup(React.createElement(ConfigureFirstMateDialog, { repo: REPO, tab: "mcp", onTab: () => {}, onClose: () => {} },
    React.createElement("p", null, "page")));
  assert.match(html, /Configure First Mate: acme\/widgets/);
  assert.match(html, /role="tab" aria-selected="true" class="erTab on">MCP servers</);
  assert.match(html, /class="uoPanel erPanel"/);
});

test("each repo tile has Configure First Mate next to Edit…, opening the modal", () => {
  const html = renderToStaticMarkup(React.createElement(Repos, {
    result: { repos: [{ fullName: REPO, hasToken: true, addedAt: "2026-10-01" }] }, onAction: async () => ({ ok: true }) }));
  assert.match(html, />Edit…<\/button><button class="acBtn">Configure First Mate<\/button>/);
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "repos.jsx"), "utf8");
  assert.match(src, /is\("firstMate", r\.fullName\) && <ConfigureFirstMateModal repo=\{r\.fullName\} onClose=\{close\} \/>/);
});

const page = (over = {}) => renderToStaticMarkup(React.createElement(McpServersPage, {
  repo: REPO, servers: [HTTP, STDIO], loadError: null, results: {}, form: null, set: () => {}, busy: false, error: null,
  onAdd() {}, onEdit() {}, onRemove() {}, onTest() {}, onSave() {}, onCancelEdit() {}, ...over }));

test("the page lists the servers, each with Test, Edit and Remove, and never a secret value", () => {
  const html = page();
  assert.match(html, /docs<\/span> <span class="acDim">http<\/span> <span class="fmcAudience acDim">First mate on this Mac<\/span> <span class="fmcWhere">https:\/\/mcp.example.com\/mcp/);
  assert.match(html, /local<\/span> <span class="acDim">stdio<\/span> <span class="fmcAudience acDim">Both<\/span> <span class="fmcWhere">npx -y docs-mcp/);
  assert.match(page({ servers: [CLUSTER] }), /ops-server<\/span> <span class="acDim">http<\/span> <span class="fmcAudience acDim">Smith runs on the cluster<\/span>/);
  assert.equal([...html.matchAll(/>Test</g)].length, 2);
  assert.equal([...html.matchAll(/>Edit</g)].length, 2);
  assert.equal([...html.matchAll(/>Remove</g)].length, 2);
  assert.match(html, />\+ Add server</);
  assert.match(page({ servers: [] }), /No MCP servers for acme\/widgets yet/);
  assert.match(page({ servers: null, loadError: "engine unreachable" }), /engine unreachable/);
});

test("a test result renders: reached with the tool names, the error, or testing", () => {
  const r = (result) => renderToStaticMarkup(React.createElement(TestResult, { result }));
  assert.match(r({ reached: true, tools: ["search", "read"], error: null }), /reached · 2 tools: search, read/);
  assert.match(r({ reached: false, tools: [], error: "HTTP 401: unauthorized" }), /not reached: HTTP 401: unauthorized/);
  assert.match(r({ testing: true }), /testing…/);
  assert.match(r(undefined), /not tested/);
  assert.match(page({ results: { docs: { reached: true, tools: ["search"], error: null } } }), /reached · 1 tool: search/);
});

test("edit: the form starts at the server, a set secret blank; the body keeps it with {secret: true}", () => {
  const form = formOf(HTTP);
  assert.deepEqual(form.values, [
    { key: "X-Team", value: "core", secret: false, set: false, since: null },
    { key: "Authorization", value: "", secret: true, set: true, since: "2026-10-08T10:00:00Z" },
  ]);
  assert.deepEqual(serverBody(form), { kind: "http", audience: "first-mate", url: "https://mcp.example.com/mcp", headers: { "X-Team": "core", Authorization: { secret: true } } });
  const html = page({ form });
  assert.match(html, /set 2026-10-08: blank keeps it/);
  assert.match(html, /<input class="ecInput" disabled="" spellCheck="false" placeholder="docs" value="docs"\/>/, "a saved server's name is fixed");
  assert.deepEqual(serverBody({ ...formOf(STDIO), args: "-y\n docs-mcp \n", values: [{ key: "TOKEN", value: "t", secret: true }] }),
    { kind: "stdio", audience: "both", command: "npx", args: ["-y", "docs-mcp"], env: { TOKEN: { value: "t", secret: true } } });
});

test("role (#100008): the select offers none and ops, the body carries ops only when chosen, the list labels it, and the test shows the contract", () => {
  assert.deepEqual(MCP_ROLES.map((r) => r.value), ["", "ops"]);
  assert.equal(emptyForm().role, "", "a new server has no role");
  assert.equal(formOf(CLUSTER).role, "", "a row without a role (older shape or null) reads as none");
  assert.equal(formOf({ ...CLUSTER, role: "ops" }).role, "ops");
  assert.deepEqual(serverBody(formOf(CLUSTER)), { kind: "http", audience: "smith", url: CLUSTER.url, headers: { Authorization: { secret: true } } }, "no role: no field in the body");
  assert.deepEqual(serverBody({ ...emptyForm(), name: "ops", audience: "smith", role: "ops", url: "https://ops" }),
    { kind: "http", audience: "smith", role: "ops", url: "https://ops", headers: {} });
  assert.deepEqual(serverBody({ ...emptyForm(), kind: "stdio", role: "ops", command: "x" }).role, "ops", "the engine refuses a stdio ops row; the body still says what was asked");
  assert.equal(roleLabel("ops"), "ops"); assert.equal(roleLabel(null), "");
  const html = page({ servers: [{ ...CLUSTER, role: "ops" }, HTTP] });
  assert.match(html, /Smith runs on the cluster<\/span> <span class="fmcRole acDim">role ops<\/span> <span class="fmcWhere">/);
  assert.doesNotMatch(html, /docs<\/span> <span class="acDim">http<\/span> <span class="fmcAudience acDim">First mate on this Mac<\/span> <span class="fmcRole/, "a plain row shows no role");
  const form = renderToStaticMarkup(React.createElement(ServerForm, { form: { ...emptyForm(), role: "ops" }, set: () => {}, busy: false }));
  assert.match(form, /Role<select[^>]*><option value="">None<\/option><option value="ops" selected="">Ops: answers whether a commit is deployed \(ops\/1\)<\/option>/);
  const r = (result) => renderToStaticMarkup(React.createElement(TestResult, { result }));
  assert.match(r({ reached: true, tools: ["is_deployed"], error: null, contract: { version: "ops/1", conforms: true, missing: [] } }), /reached · 1 tool: is_deployed<span class="fmcContract fmcOk"> · conforms to ops\/1<\/span>/);
  assert.match(r({ reached: true, tools: ["is_deployed"], error: null, contract: { version: "ops/1", conforms: false, missing: ["is_deployed takes sha, a required string"] } }),
    /does not conform to ops\/1: is_deployed takes sha, a required string/);
  assert.doesNotMatch(r({ reached: true, tools: ["search"], error: null }), /fmcContract/, "a plain row's result has no contract");
});

test("audience (#2449): the form carries it, the body sends it, the select offers the three, and the list labels it", () => {
  assert.deepEqual(MCP_AUDIENCES.map((a) => a.value), ["first-mate", "smith", "both"]);
  assert.equal(emptyForm().audience, "first-mate", "the select starts at the First mate; the body always carries the choice");
  assert.equal(formOf(CLUSTER).audience, "smith");
  assert.equal(formOf({ ...HTTP, audience: undefined }).audience, "first-mate", "an older row shape reads as the First mate's");
  assert.deepEqual(serverBody(formOf(CLUSTER)), { kind: "http", audience: "smith", url: CLUSTER.url, headers: { Authorization: { secret: true } } });
  assert.deepEqual(serverBody({ ...emptyForm(), name: "x", audience: "both", url: "https://x" }), { kind: "http", audience: "both", url: "https://x", headers: {} });
  const html = page({ form: formOf(CLUSTER) });
  assert.match(html, /Who gets it<select class="ecInput">/);
  assert.match(html, /<option value="first-mate">First mate on this Mac<\/option><option value="smith" selected="">Smith runs on the cluster<\/option><option value="both">Both<\/option>/);
  assert.equal(audienceLabel("smith"), "Smith runs on the cluster");
  assert.equal(audienceLabel("odd"), "odd");
  assert.match(page(), /X-Frakworks-Run header naming the run/);
});

test("add: save sends the body, then tests the server and answers its result", async () => {
  const api = fakeApi();
  const form = { ...emptyForm(), name: "docs", url: "https://mcp.example.com/mcp", values: [{ key: "A", value: "v", secret: false }] };
  const r = await saveServer(api, REPO, form);
  assert.deepEqual(r, { ok: true, name: "docs", test: { reached: true, tools: ["search", "read"], error: null } });
  assert.deepEqual(api.calls, [
    ["save", REPO, "docs", { kind: "http", audience: "first-mate", url: "https://mcp.example.com/mcp", headers: { A: "v" } }],
    ["test", REPO, "docs"],
  ]);
});

test("a refused save shows the engine's reason and runs no test; a nameless form is refused unsent", async () => {
  const api = fakeApi({ save: { error: "engine answered 400", status: 400, body: "url is required for an http server: an http or https URL" } });
  assert.deepEqual(await saveServer(api, REPO, { ...emptyForm(), name: "docs" }), { error: "url is required for an http server: an http or https URL" });
  assert.deepEqual(api.calls.map((c) => c[0]), ["save"]);
  assert.deepEqual(await saveServer(api, REPO, emptyForm()), { error: "a server needs a name" });
  assert.match(page({ form: emptyForm(), error: "url is required" }), /class="acProblem">url is required</);
});

test("remove and test go through the IPC, and a failed call is an error, not a throw", async () => {
  const api = fakeApi();
  assert.deepEqual(await removeServer(api, REPO, "docs"), { ok: true });
  assert.deepEqual(await testServer(api, REPO, "docs"), { reached: true, tools: ["search", "read"], error: null });
  assert.deepEqual(api.calls, [["delete", REPO, "docs"], ["test", REPO, "docs"]]);
  const broken = { deleteMcpServer: async () => { throw new Error("gone"); }, testMcpServer: async () => { throw new Error("gone"); } };
  assert.deepEqual(await removeServer(broken, REPO, "docs"), { error: "IPC failed: gone" });
  assert.deepEqual(await testServer(broken, REPO, "docs"), { reached: false, tools: [], error: "IPC failed: gone" });
});

// ---- src/engine-data.js ----

const env = { ENGINE_URL: "http://engine.test" };
const stub = (status, body) => {
  const calls = [];
  const fetch = async (url, init = {}) => { calls.push({ url, init }); return { ok: status < 300, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)) }; };
  return { calls, fetch };
};

test("engine-data: list, save, delete, test and config hit /repos/{o}/{n}/mcp-servers", async () => {
  let s = stub(200, [HTTP]);
  assert.deepEqual(await fetchRepoMcpServers(REPO, { env, fetch: s.fetch }), { servers: [HTTP] });
  assert.equal(s.calls[0].url, "http://engine.test/repos/acme/widgets/mcp-servers");
  s = stub(201, HTTP);
  assert.deepEqual(await saveRepoMcpServer(REPO, "docs", { kind: "http", url: "u" }, "alice", { env, fetch: s.fetch }), { ok: true, server: HTTP });
  assert.equal(s.calls[0].init.method, "PUT");
  assert.equal(s.calls[0].url, "http://engine.test/repos/acme/widgets/mcp-servers/docs");
  assert.equal(s.calls[0].init.headers["X-Frakworks-Login"], undefined);
  s = stub(204, "");
  assert.equal((await deleteRepoMcpServer(REPO, "docs", "alice", { env, fetch: s.fetch })).ok, true);
  assert.equal(s.calls[0].init.method, "DELETE");
  s = stub(200, { reached: false, tools: [], error: "HTTP 404" });
  assert.deepEqual(await testRepoMcpServer(REPO, "docs", { env, fetch: s.fetch }), { reached: false, tools: [], error: "HTTP 404" });
  assert.equal(s.calls[0].url, "http://engine.test/repos/acme/widgets/mcp-servers/docs/test");
  // The config is what the engine answers, the First mate's rows: a smith row is not in it, and the desktop writes only what it got.
  s = stub(200, { mcpServers: { docs: { type: "http", url: "u", headers: {} } } });
  assert.deepEqual(await fetchRepoMcpConfig(REPO, { env, fetch: s.fetch }), { servers: { docs: { type: "http", url: "u", headers: {} } } });
  assert.equal(s.calls[0].url, "http://engine.test/repos/acme/widgets/mcp-servers/config");
  s = stub(200, []);
  assert.match((await fetchRepoMcpServers(null, { env, fetch: s.fetch })).error, /need a repo/);
  assert.match((await saveRepoMcpServer(REPO, "docs", {}, null, { env, fetch: s.fetch })).error, /no GitHub login/);
  assert.equal(s.calls.length, 0);
});
