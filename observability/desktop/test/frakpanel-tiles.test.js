// frakpanel tiles (#1338): engine-data's read and write of the engine's switch, and Config > frakworks' section.
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { fetchFrakpanelTiles, setFrakpanelTiles, accountAction } = require("../src/engine-data");
const { FrakpanelTiles, FrakpanelTilesPanel } = require("../dist/frakpanel-tiles.cjs");
const { STAGE_PHRASES } = require("../dist/issues.cjs");
const fs = require("node:fs");
const path = require("node:path");

const routes = (table, calls = []) => async (url, init) => {
  calls.push([url, init && init.method, init && init.body]);
  const [status, body] = table[new URL(url).pathname];
  return { ok: status >= 200 && status < 300, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)) };
};

test("fetchFrakpanelTiles reads GET /frakpanel/tiles/enabled with the tile URL; an odd answer is the error", async () => {
  const r = await fetchFrakpanelTiles({ env: { ENGINE_URL: "http://eng:9000/" }, fetch: routes({ "/frakpanel/tiles/enabled": [200, { enabled: false }] }) });
  assert.deepEqual(r, { frakpanelTiles: { enabled: false, url: "http://eng:9000/frakpanel/tiles/ai-accounts" }, error: null });
  assert.match((await fetchFrakpanelTiles({ env: {}, fetch: routes({ "/frakpanel/tiles/enabled": [200, { enabled: "yes" }] }) })).error, /boolean/);
  assert.ok((await fetchFrakpanelTiles({ env: {}, fetch: routes({ "/frakpanel/tiles/enabled": [500, "boom"] }) })).error);
});

test("setFrakpanelTiles PUTs { enabled }, through accountAction too; a non-boolean is refused before the engine", async () => {
  const calls = [];
  const fetch = routes({ "/frakpanel/tiles/enabled": [200, { enabled: true }] }, calls);
  const r = await accountAction({ op: "frakpanel-tiles", enabled: true }, { env: {}, fetch });
  assert.equal(r.ok, true);
  assert.equal(r.frakpanelTiles.enabled, true);
  assert.equal(calls[0][1], "PUT");
  assert.deepEqual(JSON.parse(calls[0][2]), { enabled: true });
  assert.match((await setFrakpanelTiles("true", { env: {}, fetch })).error, /true or false/);
  assert.equal(calls.length, 1);
});

test("the section shows the switch, and the tile URL only while on", () => {
  const draw = (frakpanelTiles) => renderToStaticMarkup(React.createElement(FrakpanelTiles, { frakpanelTiles, run: () => {} }));
  const off = draw({ enabled: false, url: "http://e/frakpanel/tiles/ai-accounts" });
  assert.match(off, /type="checkbox"/);
  assert.ok(!off.includes("http://e/frakpanel"));
  assert.ok(!off.includes("frakworks issues tile"));
  const on = draw({ enabled: true, url: "http://e/frakpanel/tiles/ai-accounts" });
  assert.ok(on.includes("AI accounts tile: <code>http://e/frakpanel/tiles/ai-accounts</code>"));
  assert.ok(on.includes("frakworks issues tile: <code>http://e/frakpanel/tiles/frakworks-issues</code>"));
  const err = renderToStaticMarkup(React.createElement(FrakpanelTilesPanel, { initial: { frakpanelTiles: null, error: "engine answered 500" } }));
  assert.ok(err.includes("frakpanel tiles unavailable: engine answered 500"));
});

test("the frakworks issues tile's stage phrases are the desktop's STAGE_PHRASES", () => {
  const html = fs.readFileSync(path.join(__dirname, "../../../engine/modules/app/src/main/resources/frakpanel/frakworks-issues.html"), "utf8");
  const m = html.match(/\/\*stage-phrases\*\/([\s\S]*?)\/\*end\*\//);
  assert.ok(m, "the tile marks its STAGE_PHRASES copy");
  assert.deepEqual(JSON.parse(m[1]), STAGE_PHRASES);
});
