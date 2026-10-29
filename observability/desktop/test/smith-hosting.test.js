// Config > frakworks > Smith hosting (#1348): src/engine-data.js reading and
// saving `GET`/`PUT /settings/smith-hosting` against a stand-in fetch, main's
// and preload's IPC, and smith-hosting.jsx drawing and saving it.
"use strict";
// Config > frakworks > Engine URL, as main sets it at launch (src/engine-url-setting.js).
const TEST_ENGINE_URL = "http://engine.test";
require("../src/engine-data").setEngineUrl(TEST_ENGINE_URL);
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { fetchSmithHosting, setSmithHosting, accountAction } = require("../src/engine-data");
const { SmithHosting, SmithHostingPanel } = require("../dist/smith-hosting.cjs");

const SETTINGS = { hosting: "docker", namespace: "", image: "", engineUrl: "" };
const recorder = (status, body) => {
  const calls = [];
  const fetch = async (url, init = {}) => {
    calls.push({ url, method: init.method || "GET", body: init.body && JSON.parse(init.body) });
    if (body instanceof Error) throw body;
    return { ok: status >= 200 && status < 300, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)) };
  };
  return { calls, fetch };
};
const walk = (el, out = []) => {
  if (!el || typeof el !== "object") return out;
  if (Array.isArray(el)) { el.forEach((c) => walk(c, out)); return out; }
  out.push(el);
  if (el.props) walk(el.props.children, out);
  return out;
};

test("fetchSmithHosting reads GET /settings/smith-hosting; a failed or odd answer is the error", async () => {
  const { calls, fetch } = recorder(200, { ...SETTINGS, hosting: "kubernetes" });
  assert.deepEqual(await fetchSmithHosting({ env: {}, fetch }), { smithHosting: { hosting: "kubernetes" }, error: null });
  assert.equal(calls[0].url, "http://engine.test/settings/smith-hosting");
  assert.match((await fetchSmithHosting({ env: {}, fetch: recorder(500, "boom").fetch })).error, /500/);
  assert.match((await fetchSmithHosting({ env: {}, fetch: recorder(200, { hosting: "vm" }).fetch })).error, /docker or kubernetes/);
  assert.ok((await fetchSmithHosting({ env: {}, fetch: recorder(0, new Error("ECONNREFUSED")).fetch })).error);
});

test("the smith-hosting action is PUT /settings/smith-hosting with the hosting alone, and answers the engine's view", async () => {
  const { calls, fetch } = recorder(200, { ...SETTINGS, hosting: "kubernetes", namespace: "smiths" });
  const r = await accountAction({ op: "smith-hosting", hosting: "kubernetes" }, { env: {}, fetch });
  assert.deepEqual(r, { ok: true, status: 200, smithHosting: { hosting: "kubernetes" } });
  assert.deepEqual(calls, [{ url: "http://engine.test/settings/smith-hosting", method: "PUT", body: { hosting: "kubernetes" } }]);
});

test("a hosting that is neither docker nor kubernetes is refused before any request; the engine's 400 is said back", async () => {
  const { calls, fetch } = recorder(200, SETTINGS);
  for (const h of ["vm", "", null, undefined, "Docker"]) assert.match((await setSmithHosting(h, { env: {}, fetch })).error, /docker or kubernetes/);
  assert.equal(calls.length, 0);
  const refused = recorder(400, JSON.stringify({ status: 400, message: "kubernetes needs namespace, image set first" }));
  const r = await accountAction({ op: "smith-hosting", hosting: "kubernetes" }, { env: {}, fetch: refused.fetch });
  assert.match(r.error, /400 for PUT \/settings\/smith-hosting: kubernetes needs namespace, image set first/);
  const down = await setSmithHosting("docker", { env: {}, fetch: recorder(0, new Error("ECONNREFUSED")).fetch });
  assert.ok(down.error);
});

test("main reads the Smith hosting on engine:smith-hosting, and preload bridges it", () => {
  const main = fs.readFileSync(path.join(__dirname, "..", "src", "main.js"), "utf8");
  const handler = main.slice(main.indexOf('"engine:smith-hosting"'), main.indexOf('"engine:account-action"'));
  assert.ok(handler.includes("fetchSmithHosting()"));
  assert.match(fs.readFileSync(path.join(__dirname, "..", "src", "preload.js"), "utf8"), /smithHosting: \(\) => ipcRenderer\.invoke\("engine:smith-hosting"\)/);
  const index = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "index.jsx"), "utf8");
  assert.match(index, /<SmithHostingPanel /);
  assert.match(index, /useRefetchOn\(\["setting"\], refreshSmithHostingShown, \{ where: \(d\) => d\.name === "smith\.hosting" \}\)/);
});

test("the section shows the current hosting, an error or the loading line", () => {
  const html = renderToStaticMarkup(React.createElement(SmithHostingPanel, { result: { smithHosting: { hosting: "docker" }, error: null }, onAction: async () => ({}) }));
  assert.match(html, /Smith hosting/);
  assert.match(html, /<option value="docker" selected="">docker<\/option><option value="kubernetes">kubernetes<\/option>/);
  assert.match(renderToStaticMarkup(React.createElement(SmithHostingPanel, { result: { smithHosting: null, error: "engine answered 500" } })),
    /class="err">Smith hosting unavailable: engine answered 500/);
  assert.match(renderToStaticMarkup(React.createElement(SmithHostingPanel, { result: { loading: true } })), /asking the engine/);
  assert.match(renderToStaticMarkup(React.createElement(SmithHostingPanel, { result: { smithHosting: { hosting: "docker" } } })), /disabled=""/);
});

test("the section saves on change: a pick sends { op: smith-hosting, hosting }; the current one sends nothing", async () => {
  const sent = [];
  let tree;
  const Probe = () => (tree = SmithHosting({ smithHosting: { hosting: "docker" }, busy: false,
    run: async (a, done) => { sent.push([a, done]); return true; } }));
  renderToStaticMarkup(React.createElement(Probe));
  const select = walk(tree).find((e) => e.type === "select");
  await select.props.onChange({ target: { value: "docker" } });
  assert.deepEqual(sent, []);
  await select.props.onChange({ target: { value: "kubernetes" } });
  assert.deepEqual(sent, [[{ op: "smith-hosting", hosting: "kubernetes" }, "Smith hosting set to kubernetes"]]);
});

test("the page's run sends over onAction; a refusal is a failed run and the refusal is drawn", async () => {
  const result = { smithHosting: { hosting: "docker" }, error: null };
  const drive = async (answer) => {
    const sent = [];
    let tree;
    const Probe = () => (tree = SmithHostingPanel({ result, onRefresh: () => {}, onAction: async (a) => { sent.push(a); return answer; } }));
    renderToStaticMarkup(React.createElement(Probe));
    const child = walk(tree).find((e) => e.type === SmithHosting);
    return { sent, ok: await child.props.run({ op: "smith-hosting", hosting: "kubernetes" }, "done") };
  };
  const good = await drive({ ok: true, status: 200, smithHosting: { hosting: "kubernetes" } });
  assert.deepEqual(good.sent, [{ op: "smith-hosting", hosting: "kubernetes" }]);
  assert.equal(good.ok, true);
  assert.equal((await drive({ error: "engine answered 400" })).ok, false);
  assert.equal((await drive(undefined)).ok, false);
  const html = renderToStaticMarkup(React.createElement(SmithHosting, { ...result, notice: { ok: false, text: "engine answered 400: kubernetes needs image set first" }, run: async () => false }));
  assert.match(html, /class="err">engine answered 400: kubernetes needs image set first/);
  assert.match(html, /<option value="docker" selected="">/);
});
