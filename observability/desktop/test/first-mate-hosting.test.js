// Config > frakworks > First mate hosting (#2521): src/engine-data.js reading
// and saving `GET`/`PUT /settings/first-mate-hosting` against a stand-in fetch,
// main's and preload's IPC, and first-mate-hosting.jsx drawing and saving it.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { fetchFirstMateHosting, setFirstMateHosting, accountAction } = require("../src/engine-data");
const { FirstMateHosting, FirstMateHostingPanel } = require("../dist/smith-hosting.cjs");

const SETTINGS = { hosting: "local" };
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

test("fetchFirstMateHosting reads GET /settings/first-mate-hosting; a failed or odd answer is the error", async () => {
  const { calls, fetch } = recorder(200, { ...SETTINGS, hosting: "kubernetes" });
  assert.deepEqual(await fetchFirstMateHosting({ env: {}, fetch }), { firstMateHosting: { hosting: "kubernetes" }, error: null });
  assert.equal(calls[0].url, "http://localhost:8090/settings/first-mate-hosting");
  assert.match((await fetchFirstMateHosting({ env: {}, fetch: recorder(500, "boom").fetch })).error, /500/);
  assert.match((await fetchFirstMateHosting({ env: {}, fetch: recorder(200, { hosting: "vm" }).fetch })).error, /local or kubernetes/);
  assert.ok((await fetchFirstMateHosting({ env: {}, fetch: recorder(0, new Error("ECONNREFUSED")).fetch })).error);
});

test("the first-mate-hosting action is PUT /settings/first-mate-hosting with the hosting alone, and answers the engine's view", async () => {
  const { calls, fetch } = recorder(200, { ...SETTINGS, hosting: "kubernetes", extra: 1 });
  const r = await accountAction({ op: "first-mate-hosting", hosting: "kubernetes" }, { env: {}, fetch });
  assert.deepEqual(r, { ok: true, status: 200, firstMateHosting: { hosting: "kubernetes" } });
  assert.deepEqual(calls, [{ url: "http://localhost:8090/settings/first-mate-hosting", method: "PUT", body: { hosting: "kubernetes" } }]);
});

test("a hosting that is neither local nor kubernetes is refused before any request; the engine's 400 is said back", async () => {
  const { calls, fetch } = recorder(200, SETTINGS);
  for (const h of ["vm", "", null, undefined, "Local"]) assert.match((await setFirstMateHosting(h, { env: {}, fetch })).error, /local or kubernetes/);
  assert.equal(calls.length, 0);
  const refused = recorder(400, JSON.stringify({ status: 400, message: "kubernetes needs smith.hosting kubernetes first" }));
  const r = await accountAction({ op: "first-mate-hosting", hosting: "kubernetes" }, { env: {}, fetch: refused.fetch });
  assert.match(r.error, /400 for PUT \/settings\/first-mate-hosting: kubernetes needs smith.hosting kubernetes first/);
  const down = await setFirstMateHosting("local", { env: {}, fetch: recorder(0, new Error("ECONNREFUSED")).fetch });
  assert.ok(down.error);
});

test("main reads the First mate hosting on engine:first-mate-hosting, and preload bridges it", () => {
  const main = fs.readFileSync(path.join(__dirname, "..", "src", "main.js"), "utf8");
  const handler = main.slice(main.indexOf('"engine:first-mate-hosting"'), main.indexOf('"engine:account-action"'));
  assert.ok(handler.includes("fetchFirstMateHosting()"));
  assert.match(fs.readFileSync(path.join(__dirname, "..", "src", "preload.js"), "utf8"), /firstMateHosting: \(\) => ipcRenderer\.invoke\("engine:first-mate-hosting"\)/);
  const index = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "index.jsx"), "utf8");
  assert.match(index, /<FirstMateHostingPanel /);
  assert.match(index, /useRefetchOn\(\["setting"\], refreshFirstMateHostingShown, \{ where: \(d\) => d\.name === "firstmate\.hosting" \}\)/);
  assert.ok(index.indexOf("<FirstMateHostingPanel ") > index.indexOf("<SmithHostingPanel "), "under Smith hosting");
});

test("the section shows the current hosting, an error or the loading line", () => {
  const html = renderToStaticMarkup(React.createElement(FirstMateHostingPanel, { result: { firstMateHosting: { hosting: "local" }, error: null }, onAction: async () => ({}) }));
  assert.match(html, /First mate hosting/);
  assert.match(html, /<option value="local" selected="">local<\/option><option value="kubernetes">kubernetes<\/option>/);
  assert.match(renderToStaticMarkup(React.createElement(FirstMateHostingPanel, { result: { firstMateHosting: null, error: "engine answered 500" } })),
    /class="err">First mate hosting unavailable: engine answered 500/);
  assert.match(renderToStaticMarkup(React.createElement(FirstMateHostingPanel, { result: { loading: true } })), /asking the engine/);
  assert.match(renderToStaticMarkup(React.createElement(FirstMateHostingPanel, { result: { firstMateHosting: { hosting: "local" } } })), /disabled=""/);
});

test("the section saves on change: a pick sends { op: first-mate-hosting, hosting }; the current one sends nothing", async () => {
  const sent = [];
  let tree;
  const Probe = () => (tree = FirstMateHosting({ firstMateHosting: { hosting: "local" }, busy: false,
    run: async (a, done) => { sent.push([a, done]); return true; } }));
  renderToStaticMarkup(React.createElement(Probe));
  const select = walk(tree).find((e) => e.type === "select");
  await select.props.onChange({ target: { value: "local" } });
  assert.deepEqual(sent, []);
  await select.props.onChange({ target: { value: "kubernetes" } });
  assert.deepEqual(sent, [[{ op: "first-mate-hosting", hosting: "kubernetes" }, "First mate hosting set to kubernetes"]]);
});

test("the page's run sends over onAction; a refusal is a failed run and the refusal is drawn", async () => {
  const result = { firstMateHosting: { hosting: "local" }, error: null };
  const drive = async (answer) => {
    const sent = [];
    let tree;
    const Probe = () => (tree = FirstMateHostingPanel({ result, onRefresh: () => {}, onAction: async (a) => { sent.push(a); return answer; } }));
    renderToStaticMarkup(React.createElement(Probe));
    const child = walk(tree).find((e) => e.type === FirstMateHosting);
    return { sent, ok: await child.props.run({ op: "first-mate-hosting", hosting: "kubernetes" }, "done") };
  };
  const good = await drive({ ok: true, status: 200, firstMateHosting: { hosting: "kubernetes" } });
  assert.deepEqual(good.sent, [{ op: "first-mate-hosting", hosting: "kubernetes" }]);
  assert.equal(good.ok, true);
  assert.equal((await drive({ error: "engine answered 400" })).ok, false);
  assert.equal((await drive(undefined)).ok, false);
  const html = renderToStaticMarkup(React.createElement(FirstMateHosting, { ...result, notice: { ok: false, text: "engine answered 400: kubernetes needs smith.hosting kubernetes first" }, run: async () => false }));
  assert.match(html, /class="err">engine answered 400: kubernetes needs smith.hosting kubernetes first/);
  assert.match(html, /<option value="local" selected="">/);
});
