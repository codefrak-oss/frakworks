// Analytics > AI Accounts' "Which account would be used?" tile (#1260):
// fetchTierAccounts against a stand-in fetch, the engine:tier-accounts channel
// in main, and the tile asking once on open and once per Refresh, never on an event.
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
const { fetchTierAccounts } = require("../src/engine-data");
const { Accounts, TierAccountsTile } = require("../dist/accounts.cjs");

const ROWS = [
  { tier: 2, model: "claude-sonnet-5-5", effort: "low", account: "max-1", blockedUntil: null },
  { tier: 1, model: "claude-haiku-4-5-20251001", effort: "low", account: "max-1", blockedUntil: null },
  { tier: 10, model: "claude-fable-5-1", effort: "high", account: null, blockedUntil: "2026-10-06T14:00:00Z" },
];

test("fetchTierAccounts reads GET /tiers/accounts once, sorted by tier; a failure or odd answer is the error", async () => {
  const calls = [];
  const ok = async (url) => { calls.push(url); return { ok: true, status: 200, text: async () => JSON.stringify(ROWS) }; };
  const r = await fetchTierAccounts({ env: {}, fetch: ok });
  assert.deepEqual(calls, ["http://engine.test/tiers/accounts"]);
  assert.equal(r.error, null);
  assert.deepEqual(r.rows.map((x) => x.tier), [1, 2, 10]);
  const odd = await fetchTierAccounts({ env: {}, fetch: async () => ({ ok: true, status: 200, text: async () => "{}" }) });
  assert.match(odd.error, /not a list/);
  const down = await fetchTierAccounts({ env: {}, fetch: async () => { throw new Error("ECONNREFUSED"); } });
  assert.ok(down.error);
  assert.deepEqual(down.rows, []);
});

test("main's engine:tier-accounts makes one fetchTierAccounts call and subscribes to nothing", () => {
  const main = fs.readFileSync(path.join(__dirname, "../src/main.js"), "utf8");
  const at = main.indexOf('ipcMain.handle("engine:tier-accounts"');
  assert.ok(at > 0);
  const body = main.slice(at, main.indexOf("});", at));
  assert.equal((body.match(/fetchTierAccounts\(/g) || []).length, 1);
  assert.doesNotMatch(body, /setInterval|subscribe/);
  const preload = fs.readFileSync(path.join(__dirname, "../src/preload.js"), "utf8");
  assert.match(preload, /tierAccounts: \(\) => ipcRenderer\.invoke\("engine:tier-accounts"\)/);
});

// Draws the tile with hand-kept hook slots: an effect runs once, on the first draw (its mount).
const mountTile = (props) => {
  const slots = [];
  const effects = [];
  let mounted = false;
  const real = { useState: React.useState, useEffect: React.useEffect };
  const draw = () => {
    let i = 0;
    React.useState = (init) => {
      const k = i++;
      if (!(k in slots)) slots[k] = typeof init === "function" ? init() : init;
      return [slots[k], (v) => { slots[k] = typeof v === "function" ? v(slots[k]) : v; }];
    };
    React.useEffect = (fn) => { if (!mounted) effects.push(fn); };
    try { return TierAccountsTile(props); } finally { Object.assign(React, real); mounted = true; }
  };
  const open = () => { const t = draw(); effects.splice(0).forEach((f) => f()); return t; };
  return { draw, open };
};
const walk = (el, out = []) => {
  if (Array.isArray(el)) { el.forEach((e) => walk(e, out)); return out; }
  if (el && typeof el === "object" && el.props) { out.push(el); walk(el.props.children, out); }
  return out;
};
const settle = () => new Promise((r) => setImmediate(r));

test("the tile asks once when the view opens and once more per Refresh press", async () => {
  let asked = 0;
  const fleet = { tierAccounts: async () => { asked++; return { rows: ROWS, error: null }; } };
  const was = global.window;
  global.window = { fleet };
  try {
    const c = mountTile({});
    c.open();
    await settle();
    assert.equal(asked, 1, "asked once on open");
    c.draw(); c.draw();
    assert.equal(asked, 1, "a redraw does not ask again");
    for (const n of [2, 3]) {
      walk(c.draw()).find((e) => e.type === "button" && e.props.className.startsWith("refreshBtn")).props.onClick();
      await settle();
      assert.equal(asked, n, `asked again on Refresh press ${n - 1}`);
    }
    const html = renderToStaticMarkup(c.draw());
    assert.equal((html.match(/<tr>/g) || []).length, ROWS.length + 1, "one row per tier, plus the header");
    assert.match(html, /<td>1<\/td><td>haiku<\/td><td>low<\/td><td><span class="taAccount">max-1<\/span>/);
    assert.ok(html.includes(`none<span title="2026-10-06T14:00:00Z"> until ${new Date("2026-10-06T14:00:00Z").toLocaleString()}</span>`));
  } finally { global.window = was; }
});

test("the tile is drawn on Analytics > AI Accounts only, apart from the account tiles and with no account controls", () => {
  const ACCOUNTS = [{ name: "max-1", provider: "anthropic", enabled: true, hasCredential: true }];
  const analytics = renderToStaticMarkup(React.createElement(Accounts, { result: { accounts: ACCOUNTS }, onRefreshUsage: async () => ({ ok: true }) }));
  assert.match(analytics, /<section class="tier-accounts-tile"[^>]*><header class="taHead"><h2[^>]*aria-expanded="true"[^>]*>.*Which account would be used\?<\/h2>/);
  const config = renderToStaticMarkup(React.createElement(Accounts, { result: { accounts: ACCOUNTS }, showUsage: false, onAction: async () => ({ ok: true }) }));
  assert.ok(!config.includes("Which account would be used?"));
  assert.ok(!config.includes("tier-accounts-tile"));
});

test("the heading folds the table behind it and remembers the viewer's choice in localStorage", async () => {
  const fleet = { tierAccounts: async () => ({ rows: ROWS, error: null }) };
  const was = global.window;

  // A stub localStorage holding "false" draws the tile collapsed on mount.
  const store = { "accounts.tierAccountsOpen": "false" };
  global.window = { fleet };
  global.localStorage = { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = v; } };
  try {
    const c = mountTile({});
    c.open();
    await settle();
    const heading = () => walk(c.draw()).find((e) => e.type === "h2");
    let html = renderToStaticMarkup(c.draw());
    assert.equal(heading().props["aria-expanded"], false);
    assert.ok(!html.includes("<table"));
  } finally { global.window = was; delete global.localStorage; }

  // An empty stub draws it open.
  const emptyStore = {};
  global.window = { fleet };
  global.localStorage = { getItem: (k) => (k in emptyStore ? emptyStore[k] : null), setItem: (k, v) => { emptyStore[k] = v; } };
  try {
    const c = mountTile({});
    c.open();
    await settle();
    const heading = () => walk(c.draw()).find((e) => e.type === "h2");
    let html = renderToStaticMarkup(c.draw());
    assert.equal(heading().props["aria-expanded"], true);
    assert.ok(html.includes("<table"));

    // Clicking the heading flips it and writes the new value under the key.
    heading().props.onClick();
    html = renderToStaticMarkup(c.draw());
    assert.equal(heading().props["aria-expanded"], false);
    assert.ok(!html.includes("<table"));
    assert.equal(emptyStore["accounts.tierAccountsOpen"], "false");
  } finally { global.window = was; delete global.localStorage; }
});
