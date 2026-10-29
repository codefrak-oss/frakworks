// Config > frakworks > Tiers (#1099): the engine's tier map in its database,
// read and written through src/engine-data.js (GET/PUT /tiers, GET/PUT
// /security/tier), main's copy in src/engine-tiers.js, and tiers.jsx's section.
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
const { fetchTiers, setTier, fetchSecurityTier, setSecurityTier, fetchCheckTier, setCheckTier, accountAction } = require("../src/engine-data");
const engineTiers = require("../src/engine-tiers");
const { Tiers, TiersPanel, effortFor, TIER_EFFORTS } = require("../dist/tiers.cjs");
const { SEEDED_TIERS } = require("./seeded-tiers");

// The engine's GET /tiers as V42 seeds it: full model ids.
const FULL = { haiku: "claude-haiku-4-5-20251001", sonnet: "claude-sonnet-5", opus: "claude-opus-5-5", fable: "claude-fable-5-1" };
const ENGINE_ROWS = SEEDED_TIERS.map((r) => ({ ...r, model: FULL[r.model] }));

const recorder = (status = 200, body = "{}") => {
  const calls = [];
  const fetch = async (url, init = {}) => {
    calls.push({ url, method: init.method || "GET", body: init.body ? JSON.parse(init.body) : undefined });
    return { ok: status >= 200 && status < 300, status, text: async () => (typeof body === "function" ? body(url, init) : body) };
  };
  return { calls, fetch };
};

test("fetchTiers reads GET /tiers and gives the rows sorted, each model as its short name", async () => {
  const { calls, fetch } = recorder(200, JSON.stringify([...ENGINE_ROWS].reverse()));
  const r = await fetchTiers({ env: {}, fetch });
  assert.deepEqual(r.tiers, SEEDED_TIERS);
  assert.equal(calls[0].url, "http://engine.test/tiers");
  assert.match((await fetchTiers({ env: {}, fetch: recorder(200, "{}").fetch })).error, /not a list/);
  assert.match((await fetchTiers({ env: {}, fetch: recorder(503, "down").fetch })).error, /503/);
});

test("setTier sends PUT /tiers/<n> with the model and effort; a bad tier or an empty field is refused before any request", async () => {
  const { calls, fetch } = recorder(200, JSON.stringify({ tier: 6, model: "claude-fable-5-1", effort: "max" }));
  const r = await accountAction({ op: "tier", tier: 6, model: "fable", effort: "max" }, { env: {}, fetch });
  assert.deepEqual(r, { ok: true, status: 200, row: { tier: 6, model: "fable", effort: "max" } });
  assert.deepEqual(calls, [{ url: "http://engine.test/tiers/6", method: "PUT", body: { model: "fable", effort: "max" } }]);
  const none = recorder();
  for (const tier of [0, 11, 5.5, "5", null]) assert.match((await setTier(tier, "opus", "low", { env: {}, fetch: none.fetch })).error, /integer from 1 to 10/);
  assert.match((await setTier(3, "", "low", { env: {}, fetch: none.fetch })).error, /model is empty/);
  assert.match((await setTier(3, "opus", " ", { env: {}, fetch: none.fetch })).error, /effort is empty/);
  assert.equal(none.calls.length, 0);
  const refused = recorder(400, JSON.stringify({ status: 400, message: "haiku does not take effort 'max': low" }));
  assert.match((await setTier(1, "haiku", "max", { env: {}, fetch: refused.fetch })).error, /400 for PUT \/tiers\/1: haiku does not take effort 'max'/);
});

test("the security tier is read from and written to /security/tier", async () => {
  const read = recorder(200, JSON.stringify({ tier: 5, model: "claude-opus-5-5", effort: "medium" }));
  assert.deepEqual((await fetchSecurityTier({ env: {}, fetch: read.fetch })).securityTier, { tier: 5, model: "opus", effort: "medium" });
  const { calls, fetch } = recorder(200, JSON.stringify({ tier: 9, model: "claude-fable-5-1", effort: "medium" }));
  const r = await accountAction({ op: "security-tier", tier: 9 }, { env: {}, fetch });
  assert.equal(r.securityTier.tier, 9);
  assert.deepEqual(calls, [{ url: "http://engine.test/security/tier", method: "PUT", body: { tier: 9 } }]);
  assert.match((await setSecurityTier(11, { env: {}, fetch })).error, /integer from 1 to 10/);
});

test("the post-deployment check tier is read from and written to /check/tier", async () => {
  const read = recorder(200, JSON.stringify({ tier: 4, model: "claude-opus-5-5", effort: "low" }));
  assert.deepEqual((await fetchCheckTier({ env: {}, fetch: read.fetch })).checkTier, { tier: 4, model: "opus", effort: "low" });
  assert.equal(read.calls[0].url, "http://engine.test/check/tier");
  const { calls, fetch } = recorder(200, JSON.stringify({ tier: 2, model: "claude-sonnet-5", effort: "low" }));
  const r = await accountAction({ op: "check-tier", tier: 2 }, { env: {}, fetch });
  assert.equal(r.checkTier.tier, 2);
  assert.deepEqual(calls, [{ url: "http://engine.test/check/tier", method: "PUT", body: { tier: 2 } }]);
  assert.match((await setCheckTier(0, { env: {}, fetch })).error, /integer from 1 to 10/);
  assert.match((await fetchCheckTier({ env: {}, fetch: recorder(503, "down").fetch })).error, /503/);
});

test("main's copy resolves nothing before a load, the engine's rows after it, and keeps the last copy when a load fails", async () => {
  assert.equal(engineTiers.resolveTier(8), null);
  assert.equal(engineTiers.cachedTiers(), null);
  await engineTiers.loadTiers({}, async () => ({ tiers: SEEDED_TIERS }));
  assert.deepEqual(engineTiers.resolveTier(8), { model: "fable", effort: "low" });
  assert.deepEqual(engineTiers.cachedTiers(), SEEDED_TIERS);
  for (const bad of [0, 11, "x", null]) assert.equal(engineTiers.resolveTier(bad), null);
  const r = await engineTiers.loadTiers({}, async () => ({ error: "engine unreachable" }));
  assert.equal(r.error, "engine unreachable");
  assert.deepEqual(engineTiers.resolveTier(8), { model: "fable", effort: "low" });
});

test("main re-reads the map on a `tiers` setting frame and after a tier write; nothing reads bots/tiers.toml", () => {
  const src = (f) => fs.readFileSync(path.join(__dirname, "..", "src", f), "utf8");
  const main = src("main.js");
  assert.match(main, /f\.type === "setting" && f\.data\.name === "tiers"\) loadTiers\(\)/);
  assert.match(main, /if \(op === "tier" && r\.ok\) await loadTiers\(\)/);
  assert.ok(!fs.existsSync(path.join(__dirname, "..", "src", "tiers.js")));
  assert.ok(!fs.readFileSync(path.join(__dirname, "..", "package.json"), "utf8").includes("tiers.toml"));
  for (const f of fs.readdirSync(path.join(__dirname, "..", "src")).filter((f) => f.endsWith(".js"))) {
    assert.ok(!/readFileSync\([^)]*tiers/.test(src(f)), `${f} reads a tiers file`);
  }
});

test("a model switch keeps the effort when the new model takes it, else the model's first", () => {
  assert.equal(effortFor("fable", "medium"), "medium");
  assert.equal(effortFor("haiku", "medium"), "low");
  assert.equal(effortFor("opus", "low"), "low");
  assert.deepEqual(Object.keys(TIER_EFFORTS), ["haiku", "sonnet", "opus", "fable"]);
});

const draw = (props) => renderToStaticMarkup(React.createElement(Tiers, props));

test("the section draws ten rows with a model and an effort select each, and the security tier's select", () => {
  const html = draw({ tiers: SEEDED_TIERS, security: { securityTier: { tier: 5, model: "opus", effort: "medium" } }, run: () => {} });
  assert.ok(html.includes(">Tiers<"));
  for (const r of SEEDED_TIERS) {
    assert.ok(html.includes(`aria-label="tier ${r.tier} model"`));
    assert.ok(html.includes(`aria-label="tier ${r.tier} effort"`));
  }
  assert.equal((html.match(/<option value="haiku"/g) || []).length, 10);
  assert.ok(html.includes('aria-label="security tier"'));
  assert.ok(html.includes(">5 · opus medium<"));
  assert.ok(!html.includes('disabled=""'));
  assert.equal((draw({ tiers: SEEDED_TIERS, security: null }).match(/disabled=""/g) || []).length, 20, "read-only without run");
  assert.ok(draw({ tiers: null }).includes("asking the engine"));
  assert.ok(draw({ tiers: { error: "engine answered 503" } }).includes("tiers unavailable: engine answered 503"));
  assert.ok(draw({ tiers: SEEDED_TIERS, security: { securityTier: null, error: "down" } }).includes("security tier unavailable: down"));
});

test("the section draws the post-deployment check tier's select beside the security tier's, and its change is one check-tier action", () => {
  const sent = [];
  const props = { tiers: SEEDED_TIERS, security: { securityTier: { tier: 5, model: "opus", effort: "medium" } }, check: { checkTier: { tier: 4, model: "opus", effort: "low" } }, busy: false, run: (a) => sent.push(a) };
  const html = draw(props);
  assert.ok(html.includes('aria-label="check tier"'));
  assert.ok(html.includes("Post-deployment checks run at"));
  assert.ok(html.indexOf('aria-label="security tier"') < html.indexOf('aria-label="check tier"'), "after the security row");
  assert.ok(html.includes(">4 · opus low<"));
  assert.ok(draw({ tiers: SEEDED_TIERS, check: { checkTier: null, error: "down" } }).includes("check tier unavailable: down"));
  assert.ok(!draw({ tiers: SEEDED_TIERS, security: null, check: null }).includes("check tier"), "no row before the read answers");
  const tree = Tiers(props);
  const walk = (e) => (!e || typeof e !== "object" ? [] : [e, ...[].concat(e.props && e.props.children || []).flat(Infinity).flatMap(walk)]);
  // The setting rows are components: render each to reach its select.
  const selects = walk(tree).flatMap((e) => (typeof e.type === "function" ? walk(e.type(e.props)) : [e])).filter((e) => e.type === "select");
  selects.find((s) => s.props["aria-label"] === "check tier").props.onChange({ target: { value: "2" } });
  selects.find((s) => s.props["aria-label"] === "check tier").props.onChange({ target: { value: "4" } });
  assert.deepEqual(sent, [{ op: "check-tier", tier: 2 }], "the current tier picked again sends nothing");
});

test("a change is one action: the model select sends the row's tier with a matching effort", async () => {
  const sent = [];
  const el = React.createElement(TiersPanel, { tiers: SEEDED_TIERS, security: null, onAction: async (a) => { sent.push(a); return { ok: true }; } });
  // Drawing only: find the tier 6 model select's onChange in the rendered tree.
  const tree = Tiers({ tiers: SEEDED_TIERS, security: null, busy: false, run: (a) => sent.push(a) });
  const walk = (e) => (!e || typeof e !== "object" ? [] : [e, ...[].concat(e.props && e.props.children || []).flat(Infinity).flatMap(walk)]);
  const selects = walk(tree).filter((e) => e.type === "select");
  selects.find((s) => s.props["aria-label"] === "tier 6 model").props.onChange({ target: { value: "haiku" } });
  selects.find((s) => s.props["aria-label"] === "tier 9 effort").props.onChange({ target: { value: "max" } });
  assert.deepEqual(sent, [{ op: "tier", tier: 6, model: "haiku", effort: "low" }, { op: "tier", tier: 9, model: "fable", effort: "max" }]);
  assert.ok(renderToStaticMarkup(el).includes("Tiers"));
});
