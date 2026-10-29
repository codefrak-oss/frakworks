// Config > AI Accounts: src/engine-data.js fetchAccounts against a stand-in
// fetch (the /accounts + /accounts/usage join, and every failure an `error`),
// and accounts.jsx drawing the result. Shapes are the engine's AiAccount and
// UsageView (engine/src/main/kotlin/com/codefrak/engine/accounts).
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { fetchAccounts } = require("../src/engine-data");
const { Accounts, runningText, shortModel, accountState, fmtPct, meterLevel, fableText, hasFableReading, fmtCountdown, Countdown, STALE_RESET_MS } = require("../dist/accounts.cjs");

const render = (props) => renderToStaticMarkup(React.createElement(Accounts, props));

const ACCOUNTS = [
  { name: "max-1", provider: "anthropic", enabled: true, hasCredential: true, credentialSetAt: "2026-09-22T10:00:00Z", addedAt: "2026-09-22T09:00:00Z" },
  { name: "codex", provider: "openai", enabled: false, hasCredential: false, credentialSetAt: null, addedAt: "2026-09-23T09:00:00Z" },
];
const meter = (account, extra = {}) => ({
  account, fiveHourUtilization: 0.02, fiveHourStatus: "allowed", fiveHourReset: "2026-09-24T05:50:00Z",
  sevenDayUtilization: 0.48, sevenDayStatus: "allowed", sevenDayReset: "2026-09-26T09:00:00Z",
  status: "allowed", rejected: false, httpStatus: 200, requests: 42, observedAt: "2026-09-24T03:35:53Z", ...extra,
});

// A stand-in fetch answering by path: { "/accounts": [status, body] | Error }.
const routes = (table, calls = []) => async (url) => {
  calls.push(url);
  const r = table[new URL(url).pathname];
  if (r instanceof Error) throw r;
  const [status, body] = r;
  return { ok: status >= 200 && status < 300, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)) };
};

test("fetchAccounts joins each account's meter on by name", async () => {
  const calls = [];
  const r = await fetchAccounts({ env: {}, fetch: routes({
    "/accounts": [200, ACCOUNTS], "/accounts/usage": [200, [meter("max-1"), meter("engine", { requests: 7 })]],
  }, calls) });
  assert.equal(r.error, null);
  assert.equal(r.usageError, null);
  assert.deepEqual(calls.sort(), ["http://localhost:8090/accounts", "http://localhost:8090/accounts/usage"]);
  assert.equal(r.accounts[0].usage.sevenDayUtilization, 0.48);
  assert.equal(r.accounts[1].usage, null);
});

test("fetchAccounts passes running through, and reads a missing one (an older engine) as []", async () => {
  const live = [{ model: "claude-opus-5-5", count: 2 }];
  const r = await fetchAccounts({ env: {}, fetch: routes({
    "/accounts": [200, [{ ...ACCOUNTS[0], running: live }, ACCOUNTS[1]]], "/accounts/usage": [200, []],
  }) });
  assert.deepEqual(r.accounts[0].running, live);
  assert.deepEqual(r.accounts[1].running, []);
});

test("the running badge reads '2 opus · 1 sonnet', by count then name, and is absent at zero", () => {
  assert.equal(shortModel("claude-opus-5-5"), "opus");
  assert.equal(shortModel("claude-haiku-4-5-20251001"), "haiku");
  assert.equal(shortModel("gpt-5"), "gpt-5");
  assert.equal(runningText([{ model: "claude-sonnet-5", count: 1 }, { model: "claude-opus-5-5", count: 2 }]), "2 opus · 1 sonnet");
  assert.equal(runningText([{ model: "claude-sonnet-5", count: 1 }, { model: "claude-fable-5-1", count: 1 }]), "1 fable · 1 sonnet");
  assert.equal(runningText([]), null);
  assert.equal(runningText(undefined), null);
  assert.equal(runningText([{ model: "claude-opus-5-5", count: 0 }]), null);

  const accounts = [
    { ...ACCOUNTS[0], running: [{ model: "claude-sonnet-5", count: 1 }, { model: "claude-opus-5-5", count: 2 }] },
    { ...ACCOUNTS[0], name: "max-2", running: [] },
    { ...ACCOUNTS[0], name: "max-3" }, // an older engine: no running at all
  ];
  const html = render({ result: { accounts, usageError: null }, now: Date.parse("2026-09-24T04:00:00Z") });
  assert.equal((html.match(/class="acRunning"/g) || []).length, 1, html);
  assert.match(html, /<span class="acRunning"[^>]*><span class="acRunningLabel">running<\/span> 2 opus · 1 sonnet<\/span>/);
  assert.doesNotMatch(html, /0 running|acRunning"[^>]*><\/span>/);
  const tiles = html.split("<article").slice(1);
  assert.match(tiles[0], /acRunning/);
  assert.doesNotMatch(tiles[1], /acRunning/);
  assert.doesNotMatch(tiles[2], /acRunning/);
});

test("a failed meter still draws the accounts; a failed /accounts is the error", async () => {
  const half = await fetchAccounts({ env: {}, fetch: routes({ "/accounts": [200, ACCOUNTS], "/accounts/usage": [500, "boom"] }) });
  assert.equal(half.error, null);
  assert.equal(half.accounts.length, 2);
  assert.match(half.usageError, /500/);
  for (const [a, expect] of [
    [[503, "down"], /engine answered 503 for \/accounts/],
    [[200, "<html>"], /produced no JSON/],
    [[200, { nope: 1 }], /not a list/],
    [new Error("ECONNREFUSED"), /unreachable.*ECONNREFUSED/],
  ]) {
    const r = await fetchAccounts({ env: {}, fetch: routes({ "/accounts": a, "/accounts/usage": [200, []] }) });
    assert.match(r.error, expect);
    assert.deepEqual(r.accounts, []);
  }
});

test("accountState says whether a Smith can run on it now", () => {
  const ok = { enabled: true, hasCredential: true, usage: null };
  assert.equal(accountState(ok).key, "ready");
  assert.equal(accountState({ ...ok, enabled: false }).key, "off");
  assert.equal(accountState({ ...ok, hasCredential: false }).key, "nocred");
  assert.equal(accountState({ ...ok, usage: { rejected: true } }).key, "rejected");
  assert.equal(fmtPct(0.475), "48%");
  assert.equal(fmtPct(null), "–");
});

test("the view draws one tile per account, its state and meters, and never a token", () => {
  const result = { accounts: [{ ...ACCOUNTS[0], usage: meter("max-1") }, { ...ACCOUNTS[1], usage: null }], usageError: null };
  const html = render({ result, now: Date.parse("2026-09-24T12:00:00Z") });
  assert.equal([...html.matchAll(/class="acTile ac-/g)].length, 2);
  assert.doesNotMatch(html, /<table/);
  assert.ok(html.includes("AI Accounts · 2"));
  assert.match(html, /ac-ready/);
  assert.match(html, /ac-off/);
  assert.ok(html.includes("48%"));
  assert.ok(html.includes("never used"));
});

test("errors, a failed meter, loading and empty each draw something", () => {
  assert.match(render({ result: { error: "engine unreachable", accounts: [] } }), /class="err"/);
  assert.ok(render({ result: { accounts: [{ ...ACCOUNTS[0], usage: null }], usageError: "HTTP 500" } }).includes("usage meter unavailable: HTTP 500"));
  assert.ok(render({ result: { accounts: [], loading: true } }).includes("asking the engine…"));
  assert.ok(render({ result: { accounts: [] } }).includes("no AI accounts yet"));
});

test("the view is drawing only: no fs, net or host name of its own", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "accounts.jsx"), "utf8");
  assert.doesNotMatch(src, /require\(|node:|fetch\(|localhost|8090/);
});

// ---- the writes: engine:account-action -> accountAction -----------------
const { accountAction } = require("../src/engine-data");

// Records each request; answers every one with `status`.
const recorder = (status = 204, body = "") => {
  const calls = [];
  const fetch = async (url, init) => {
    calls.push({ url, method: init.method, body: init.body && JSON.parse(init.body) });
    return { ok: status >= 200 && status < 300, status, text: async () => body };
  };
  return { calls, fetch };
};

test("each tab's control sends its existing op", async () => {
  const { EditAccountDialog } = require("../dist/accounts.cjs");
  const sent = [];
  const run = async (a) => { sent.push(a.op); return true; };
  const find = (el, pred) => {
    if (!el || typeof el !== "object") return null;
    if (Array.isArray(el)) { for (const c of el) { const f = find(c, pred); if (f) return f; } return null; }
    if (pred(el)) return el;
    const kids = find(el.props && el.props.children, pred);
    if (kids || typeof el.type !== "function") return kids;
    try { return find(el.type(el.props), pred); } catch { return null; } // a component with hooks: not this one
  };
  const text = (el) => [].concat(el.props.children).join("");
  const tree = (tab) => EditAccountDialog({ account: { ...ACCOUNTS[0] }, tab, onTab: () => {}, busy: false, run, verify: async () => {}, onClose: () => {} });
  const btn = (tab, label) => find(tree(tab), (e) => e.type === "button" && e.props && e.props.role !== "tab" && e.props.children && text(e) === label);
  const sw = find(tree("details"), (e) => e.props && e.props.label === "Enabled");
  const args = [];
  const run2 = async (a) => { args.push(a); return true; };
  find(EditAccountDialog({ account: { ...ACCOUNTS[0] }, tab: "details", onTab: () => {}, busy: false, run: run2, verify: async () => {}, onClose: () => {} }),
    (e) => e.props && e.props.label === "Enabled").props.onChange(false);
  find(EditAccountDialog({ account: { ...ACCOUNTS[1] }, tab: "details", onTab: () => {}, busy: false, run: run2, verify: async () => {}, onClose: () => {} }),
    (e) => e.props && e.props.label === "Enabled").props.onChange(true);
  assert.deepEqual(args, [{ op: "enabled", name: "max-1", enabled: false }, { op: "enabled", name: "codex", enabled: true }]);
  await sw.props.onChange(false);
  await btn("remove", "Remove").props.onClick();
  assert.deepEqual(sent, ["enabled", "remove"]);
});

test("each account action is the engine's request for it", async () => {
  const cases = [
    [{ op: "add", name: "anthropic-1", provider: "anthropic" }, "POST", "/accounts", { name: "anthropic-1", provider: "anthropic" }],
    [{ op: "credential", name: "anthropic-1", token: " sk-ant-oat01-x " }, "PUT", "/accounts/anthropic-1/credential", { token: "sk-ant-oat01-x" }],
    [{ op: "enabled", name: "anthropic-1", enabled: false }, "PUT", "/accounts/anthropic-1/enabled", { enabled: false }],
    [{ op: "remove", name: "anthropic-1" }, "DELETE", "/accounts/anthropic-1", undefined],
  ];
  for (const [action, method, p, body] of cases) {
    const { calls, fetch } = recorder();
    const r = await accountAction(action, { env: {}, fetch });
    assert.deepEqual(r, { ok: true, status: 204 });
    assert.deepEqual(calls, [{ url: `http://localhost:8090${p}`, method, body }]);
  }
});

test("a bad action is refused before any request, and nothing odd reaches a URL", async () => {
  const { calls, fetch } = recorder();
  for (const [action, expect] of [
    [{ op: "remove", name: "../repos" }, /name is lowercase/],
    [{ op: "remove", name: "Max" }, /name is lowercase/],
    [{ op: "add", name: "x", provider: "gemini" }, /provider is one of anthropic, openai, jev, codex/],
    [{ op: "credential", name: "x", token: "  " }, /credential is empty/],
    // A paste across a terminal's line wrap: the indent of the second line lands inside the token.
    [{ op: "credential", name: "x", provider: "anthropic", token: "sk-ant-oat01-abcd   hkxwh" }, /whitespace at character 18 of 25/],
    [{ op: "credential", name: "x", token: "sk-ant-oat01-ab\ncd" }, /whitespace at character 16/],
    [{ op: "credential", name: "x", provider: "anthropic", token: "sk-ant-api03-abc" }, /starts with sk-ant-oat01-.*not an API key/],
    [{ op: "enabled", name: "x", enabled: "no" }, /true or false/],
    [{ op: "drop", name: "x" }, /unknown account action/],
    [null, /name is lowercase/],
  ]) assert.match((await accountAction(action, { env: {}, fetch })).error, expect);
  assert.equal(calls.length, 0);
});

test("the engine's refusal comes back said, and never with the credential in it", async () => {
  const { fetch } = recorder(503, JSON.stringify({ status: 503, message: "ENGINE_SECRETS_KEY is not set" }));
  const r = await accountAction({ op: "credential", name: "a", token: "sk-secret-123" }, { env: {}, fetch });
  assert.match(r.error, /503 for PUT \/accounts\/a\/credential: ENGINE_SECRETS_KEY is not set/);
  assert.match(r.error, /cannot seal a credential/, "Spring's bare 503 is explained");
  assert.doesNotMatch(r.error, /sk-secret/);
  const down = await accountAction({ op: "credential", name: "a", token: "sk-secret-123" },
    { env: {}, fetch: async () => { throw new Error("ECONNREFUSED"); } });
  assert.match(down.error, /unreachable/);
  assert.doesNotMatch(down.error, /sk-secret/);
});

const editDialog = (account, tab, extra = {}) => renderToStaticMarkup(React.createElement(require("../dist/accounts.cjs").EditAccountDialog,
  { account, tab, onTab: () => {}, busy: false, run: async () => true, verify: async () => {}, error: null, verdict: null, onClose: () => {}, ...extra }));

test("with onAction each tile offers exactly one Edit…, whose tabs offer every action; without, none", () => {
  const result = { accounts: [{ ...ACCOUNTS[0], usage: null }, { ...ACCOUNTS[1], usage: null }] };
  const html = render({ result, onAction: async () => ({ ok: true }) });
  assert.ok(html.includes("+ Add account"));
  for (const tile of html.split(/<article /).slice(1)) {
    assert.equal([...tile.matchAll(/<button/g)].length, 1, "one button per tile");
    assert.match(tile, />Edit…</);
    assert.doesNotMatch(tile, />Disable<|>Enable<|credential<\/button>|>Rename<|>Verify<|>Remove<|email<\/button>|type="checkbox"/);
  }
  const { ACCOUNT_TABS } = require("../dist/accounts.cjs");
  const [on, off] = [{ ...ACCOUNTS[0], email: "me@example.com" }, ACCOUNTS[1]];
  const d = editDialog(on, "details");
  assert.match(d, /class="uoBackdrop/);
  assert.equal([...d.matchAll(/class="erTab( on)?"/g)].length, ACCOUNT_TABS.length);
  assert.match(d, /class="erTabs"/);
  assert.match(d, /erPage/);
  assert.ok(d.includes("max-1") && d.includes("anthropic"));
  assert.match(d, /type="submit"[^>]*>Rename</);
  assert.match(d, /value="me@example.com"/);
  assert.match(d, /type="checkbox" aria-label="Enabled" checked=""/);
  assert.ok(d.indexOf('aria-label="Enabled"') < d.indexOf(">Name<"), "Enabled is the first row, above Name");
  assert.match(d, /Enabled: Smith runs may bill to it\./);
  assert.doesNotMatch(d, /<button[^>]*>(Disable|Enable)</);
  const dOff = editDialog(off, "details");
  assert.match(dOff, /type="checkbox" aria-label="Enabled"/);
  assert.doesNotMatch(dOff, /aria-label="Enabled" checked/);
  assert.match(dOff, /Disabled: the picker skips it\./);
  const cred = editDialog(on, "credential");
  assert.match(cred, /type="password"/);
  assert.match(cred, />Verify</);
  assert.ok(cred.includes("Replace credential"));
  assert.match(cred, /type="password"[^>]*value=""/, "no stored credential is drawn");
  assert.ok(editDialog(off, "credential").includes("Set credential"));
  assert.doesNotMatch(editDialog(off, "credential"), />Verify</, "no stored credential, no Verify");
  assert.match(editDialog(on, "remove"), /Remove <b>max-1<\/b>\?/);
  assert.match(editDialog(on, "remove", { error: "a run is live" }), /class="uoFail">a run is live</, "the refusal stays in the tab");
  assert.match(editDialog(on, "credential", { verdict: { ok: false, text: "max-1: refused" } }), /acVerdict bad">max-1: refused</);
  const ro = render({ result });
  assert.doesNotMatch(ro, /acBtn/);
  assert.ok(render({ result: { accounts: [] }, onAction: async () => ({}) }).includes("Add one above"));
});

test("Config > AI Accounts draws controls but no window rows; Analytics > AI Accounts the reverse (#640)", () => {
  const now = Date.parse("2026-09-24T12:00:00Z");
  const result = { accounts: [{ ...ACCOUNTS[0], usage: meter("max-1") }, { ...JEV, usage: null }] };
  const config = render({ result, onAction: async () => ({ ok: true }), showUsage: false, now });
  assert.ok(config.includes("+ Add account") && config.includes("acActions") && config.includes(">Edit…<"));
  assert.doesNotMatch(config, /acPill/);
  assert.doesNotMatch(config, /acWindow|acMeter|acBar|acFable|no usage data|>5h<|>7d</);
  assert.ok(config.includes("credential set") && config.includes("requests") && config.includes("seen "));
  const analytics = render({ result, now });
  assert.doesNotMatch(analytics, /acPill/);
  assert.ok(analytics.includes("acWindow") && analytics.includes("acBar") && analytics.includes("no usage data"));
  assert.ok(!analytics.includes("Fable: available") && !analytics.includes("acFable"));
  assert.doesNotMatch(analytics, /acActions|acToggle|acBtn|Add account|Edit…/);
  const failed = render({ result: { ...result, usageError: "boom" }, onAction: async () => ({}), showUsage: false });
  assert.ok(failed.includes("usage meter unavailable: boom") && failed.includes("acTile"));
  assert.ok(render({ result: { error: "down" } }).includes(">down<"));
});

test("main never logs what an action carries beyond its op and name", () => {
  const main = fs.readFileSync(path.join(__dirname, "..", "src", "main.js"), "utf8");
  const handler = main.slice(main.indexOf('"engine:account-action"'), main.indexOf('"app:initial-view"'));
  assert.ok(handler.includes("accountAction(action)"));
  assert.doesNotMatch(handler.replace(/^\s*\/\/.*$/gm, ""), /token|JSON\.stringify\(action\)|\$\{action\}/);
});

test("each meter is a bar filled to its utilization, coloured by how full it is, beside the number", () => {
  assert.equal(meterLevel(0.2), "ok");
  assert.equal(meterLevel(0.5), "warm");
  assert.equal(meterLevel(0.8), "hot");
  assert.equal(meterLevel(0.1, "rejected"), "ok");
  const result = { accounts: [{ ...ACCOUNTS[0], usage: meter("max-1") }], usageError: null };
  const html = render({ result, now: Date.parse("2026-09-24T12:00:00Z") });
  assert.equal([...html.matchAll(/class="acBar"/g)].length, 2);
  assert.match(html, /class="acFill [a-z]+" style="width:\d+(\.\d+)?%"/);
  assert.ok(html.includes("48%"));
});

test("each tile says the account's Fable limit when reached; nothing when not reached or unknown", () => {
  const now = Date.parse("2026-09-25T09:00:00Z");
  const reached = meter("max-1", { fableLimit: "reached", fableLimitSince: "2026-09-25T04:39:00Z", fableLimitReset: "2026-09-26T09:00:00Z" });
  assert.equal(fableText(reached, now), "Fable: reached, resets in 1d:00h:00m:00s");
  assert.equal(fableText(meter("max-1", { fableLimit: "unknown", fableLimitSince: null, fableLimitReset: null }), now), "");
  assert.equal(fableText(null, now), "");
  const result = { accounts: [{ ...ACCOUNTS[0], usage: reached }, { ...ACCOUNTS[1], usage: meter("codex", { fableLimit: "unknown" }) }] };
  const html = render({ result, now });
  assert.ok(html.includes("Fable: reached, resets in 1d:00h:00m:00s"));
  assert.ok(!html.includes("Fable: available"));
  assert.match(html, /class="acFable warn" title="[^"]+"/);
});

test("fmtCountdown: hours under a day, days and padded hours beyond, resetting at or past zero", () => {
  const ms = (d, h, m, s) => (((d * 24 + h) * 60 + m) * 60 + s) * 1000;
  assert.equal(fmtCountdown(ms(0, 5, 22, 12)), "5h:22m:12s");
  assert.equal(fmtCountdown(ms(0, 23, 59, 59) + 999), "23h:59m:59s");
  assert.equal(fmtCountdown(ms(3, 4, 22, 12)), "3d:04h:22m:12s");
  assert.equal(fmtCountdown(ms(1, 0, 0, 0)), "1d:00h:00m:00s");
  assert.equal(fmtCountdown(0), "resetting…");
  assert.equal(fmtCountdown(-5000), "resetting…");
  assert.equal(fmtCountdown(ms(0, 0, 4, 9)), "0h:04m:09s");
});

test("Countdown: future counts down, just past resets, well past reads as stale with observedAt", () => {
  const now = Date.parse("2026-09-26T12:00:00Z");
  const at = (ms) => new Date(now + ms).toISOString();
  const r = (iso) => renderToStaticMarkup(React.createElement(Countdown, { iso, now, observedAt: "2026-09-25T17:03:00Z" }));
  assert.match(r(at(3600 * 1000)), />1h:00m:00s</);
  assert.match(r(at(-60 * 1000)), />resetting…</);
  assert.match(r(at(-STALE_RESET_MS)), />resetting…</);
  const stale = r(at(-STALE_RESET_MS - 1000));
  assert.match(stale, />awaiting new reading</);
  assert.match(stale, /title="[^"]*2026-09-25T17:03:00Z/);
});

test("the tile shows accountState's own text: ready, or rate-limited once usage.rejected is true", () => {
  const ok = render({ result: { accounts: [{ ...ACCOUNTS[0], usage: meter("a") }] } });
  assert.match(ok, />ready</);
  assert.doesNotMatch(ok, /healthy|waiting for window reset/);
  const rejectedAccounts = { accounts: [{ ...ACCOUNTS[0], usage: meter("a", { fiveHourStatus: "rejected", rejected: true }) }] };
  const analytics = render({ result: rejectedAccounts });
  assert.doesNotMatch(analytics, /rate-limited/);
  assert.doesNotMatch(analytics, />ready</);
  assert.doesNotMatch(analytics, /healthy|waiting for window reset/);
  const config = render({ result: rejectedAccounts, showUsage: false });
  assert.match(config, />rate-limited</);
  assert.doesNotMatch(config, /healthy|waiting for window reset/);
});

test("each of several accounts gets exactly one tile with both countdowns and no Fable row", () => {
  const now = Date.parse("2026-09-24T00:00:00Z");
  const names = ["max-1", "max-2", "max-3"];
  const result = { accounts: names.map((n) => ({ ...ACCOUNTS[0], name: n, usage: meter(n) })) };
  const html = render({ result, now });
  const tiles = html.split(/<article /).slice(1);
  assert.equal(tiles.length, 3);
  for (const [i, n] of names.entries()) {
    assert.equal(tiles.filter((t) => t.includes(`>${n}<`)).length, 1);
    const t = tiles[i];
    assert.equal([...t.matchAll(/class="acCountdown" title="[^"]+"/g)].length, 2);
    assert.ok(t.includes("5h:50m:00s"), "5h reset countdown");
    assert.ok(t.includes("2d:09h:00m:00s"), "7d reset countdown");
    assert.ok(!t.includes("Fable: available") && !t.includes("acFable"));
  }
});

test("the Fable meter shows as a percentage with its reset only when the reading has one", () => {
  const now = Date.parse("2026-09-24T00:00:00Z");
  const fable = meter("max-1", { fableUtilization: 0.78, fableReset: "2026-09-29T17:00:00Z" });
  const none = meter("max-2", { fableUtilization: null, fableReset: null });
  assert.equal(hasFableReading(fable), true);
  assert.equal(hasFableReading(none), false);
  assert.equal(hasFableReading(meter("max-3")), false, "an engine without the fields");
  assert.equal(hasFableReading(null), false);

  const tiles = render({ result: { accounts: [{ ...ACCOUNTS[0], usage: fable }, { ...ACCOUNTS[0], name: "max-2", usage: none }] }, now })
    .split(/<article /).slice(1);
  assert.match(tiles[0], /title="Fable limit">F</);
  assert.ok(tiles[0].includes("78%"));
  assert.ok(tiles[0].includes("5d:17h:00m:00s"), "Fable reset countdown");
  assert.equal([...tiles[0].matchAll(/class="acCountdown" title="[^"]+"/g)].length, 3);
  assert.doesNotMatch(tiles[1], /Fable limit/);
  assert.doesNotMatch(tiles[1], /0%/, "no Fable value, not 0%");
  assert.equal([...tiles[1].matchAll(/class="acCountdown" title="[^"]+"/g)].length, 2);
  assert.ok(!tiles[1].includes("Fable: available") && !tiles[1].includes("acFable"), "no Fable row when the limit is not reached");
});

test("a Fable pair the engine kept across later non-Fable readings draws the F meter, even at 0% (#465)", () => {
  const now = Date.parse("2026-09-26T21:00:00Z");
  // The /accounts/usage row as served live, with the pair an earlier Fable run left.
  const kept = meter("anthropic-primary", {
    fiveHourUtilization: 0.07, sevenDayUtilization: 0.66, requests: 1695, observedAt: "2026-09-26T20:45:58Z",
    fableLimit: "unknown", fableUtilization: 0, fableReset: "2026-09-29T22:00:00Z",
  });
  assert.equal(hasFableReading(kept), true);
  const html = render({ result: { accounts: [{ ...ACCOUNTS[0], name: "anthropic-primary", usage: kept }] }, now });
  assert.match(html, /title="Fable limit">F</);
  assert.ok(html.includes("3d:01h:00m:00s"), "Fable reset countdown");
});

// ---- jev (TypeSafe's Jev): stored, verified and toggled, with no usage meter ----
const { credentialProblem } = require("../src/credential-rules");
const JEV = { name: "jev-1", provider: "jev", enabled: true, hasCredential: true, credentialSetAt: "2026-09-26T10:00:00Z", addedAt: "2026-09-26T09:00:00Z" };

test("a jev tile shows its provider, state and credential, and says no usage data in place of the meters", () => {
  const on = render({ result: { accounts: [{ ...JEV, usage: null }] }, onAction: async () => ({ ok: true }), now: Date.parse("2026-09-26T12:00:00Z") });
  assert.match(on, /class="acProvider">jev</);
  assert.ok(on.includes("credential set"));
  assert.ok(on.includes("no usage data"));
  assert.doesNotMatch(on, /acMeter|acCountdown|Fable|requests/, "no 5h/7d meter, Fable row or request count");
  // the "balance" label must not share the metered rows' fixed-width class alone: at 2ch it
  // overflows into the adjacent "no usage data" text (the "balanmoeusage data" overlap bug).
  assert.match(on, /<span class="acWinLabel acWinLabelWide">balance<\/span>/);
  const off = render({ result: { accounts: [{ ...JEV, enabled: false, hasCredential: false, usage: null }] }, onAction: async () => ({ ok: true }) });
  assert.match(off, /disabled/);
  assert.ok(off.includes("no credential") && off.includes("no usage data"));
});

test("acWinLabelWide gives the balance label room for its text instead of the metered rows' fixed 2ch box", () => {
  const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
  const winLabel = css.match(/\.acWinLabel\s*{([^}]*)}/)[1];
  assert.match(winLabel, /width:\s*2ch/, "metered rows (5h/7d/F) keep their current fixed label width");
  const wide = css.match(/\.acWinLabelWide\s*{([^}]*)}/);
  assert.ok(wide, "expected an .acWinLabelWide rule sizing the no-meter balance label separately");
  assert.doesNotMatch(wide[1], /(?<!min-)width:\s*2ch/, "the wide label must not be pinned back to the 2ch metered-row width");
});

test("the add form offers Codex beside the existing providers", async () => {
  const { AddForm, PROVIDERS } = require("../dist/accounts.cjs");
  assert.deepEqual(PROVIDERS, ["anthropic", "openai", "jev", "codex"]);
  const form = renderToStaticMarkup(React.createElement(AddForm, { busy: false, run: async () => true, onClose: () => {} }));
  const options = [...form.matchAll(/<option value="([^"]+)"/g)].map((m) => m[1]);
  assert.deepEqual(options, ["anthropic", "openai", "jev", "codex"]);
  const r = await accountAction({ op: "add", name: "jev-1", provider: "jev" }, { env: {}, fetch: recorder(201).fetch });
  assert.deepEqual(r, { ok: true, status: 201 });
  const codex = recorder(201);
  assert.deepEqual(await accountAction({ op: "add", name: "codex-1", provider: "codex" }, { env: {}, fetch: codex.fetch }), { ok: true, status: 201 });
  assert.deepEqual(codex.calls, [{ url: "http://localhost:8090/accounts", method: "POST", body: { name: "codex-1", provider: "codex" } }]);
});

test("Codex accounts import a bounded auth.json blob but remain unavailable to Smith runs", async () => {
  const { AddForm } = require("../dist/accounts.cjs");
  const html = render({ result: { accounts: [{ name: "codex-1", provider: "codex", enabled: true, hasCredential: false, email: null, usage: null }] },
    onAction: async () => ({ ok: true }) });
  assert.match(html, /sign-in not connected/);
  const codexDialog = (hasCredential) => editDialog({ name: "codex-1", provider: "codex", enabled: true, hasCredential, email: null }, "credential");
  assert.match(codexDialog(false), /Import auth\.json/);
  assert.match(codexDialog(false), /type="file"/);
  assert.doesNotMatch(codexDialog(true), /Set credential|Replace credential|>Verify</);
  const stored = render({ result: { accounts: [{ name: "codex-1", provider: "codex", enabled: true, hasCredential: true, email: null, usage: null }] },
    onAction: async () => ({ ok: true }) });
  assert.match(stored, /login stored · Smith runs not connected/);
  assert.match(codexDialog(true), /Replace auth\.json/);

  const { calls, fetch } = recorder();
  const auth = "{\n\"auth_mode\":\"chatgpt\",\"tokens\":{\"refresh_token\":\"secret\"}}\n";
  assert.deepEqual(await accountAction({ op: "credential", name: "codex-1", provider: "codex", token: auth }, { env: {}, fetch }), { ok: true, status: 204 });
  assert.deepEqual(calls, [{ url: "http://localhost:8090/accounts/codex-1/credential", method: "PUT", body: { token: auth } }]);
  const tooLarge = await accountAction({ op: "credential", name: "codex-1", provider: "codex", token: "x".repeat(65537) }, { env: {}, fetch });
  assert.match(tooLarge.error, /over 65536 bytes/);
  assert.equal(calls.length, 1);

  const codexForm = renderToStaticMarkup(React.createElement(AddForm, { busy: false, run: async () => true, onClose: () => {} }));
  assert.match(codexForm, /codex \(ChatGPT sign-in\)/);
});

test("a jev key gets the whitespace rule and no prefix rule, here and before a request", async () => {
  assert.match(credentialProblem("jev", "abc def"), /whitespace at character 4 of 7/);
  assert.equal(credentialProblem("jev", "anykey123"), null);
  const { calls, fetch } = recorder();
  assert.match((await accountAction({ op: "credential", name: "jev-1", provider: "jev", token: "ab\ncd" }, { env: {}, fetch })).error, /whitespace/);
  assert.equal(calls.length, 0);
  assert.deepEqual(await accountAction({ op: "credential", name: "jev-1", provider: "jev", token: "anykey123" }, { env: {}, fetch }), { ok: true, status: 204 });
  assert.deepEqual(calls, [{ url: "http://localhost:8090/accounts/jev-1/credential", method: "PUT", body: { token: "anykey123" } }]);
});

test("verify asks the engine to try the stored credential and brings back its verdict", async () => {
  const good = recorder(200, JSON.stringify({ valid: true, reachable: true, detail: "jev accepted it" }));
  assert.deepEqual(await accountAction({ op: "verify", name: "jev-1" }, { env: {}, fetch: good.fetch }),
    { ok: true, status: 200, verdict: { valid: true, reachable: true, detail: "jev accepted it" } });
  assert.deepEqual(good.calls, [{ url: "http://localhost:8090/accounts/jev-1/verify", method: "POST", body: undefined }]);
  const bad = recorder(200, JSON.stringify({ valid: false, reachable: true, detail: "jev refused it (401): Invalid API key" }));
  const refused = await accountAction({ op: "verify", name: "jev-1" }, { env: {}, fetch: bad.fetch });
  assert.equal(refused.verdict.valid, false);
  assert.equal(refused.verdict.detail, "jev refused it (401): Invalid API key");
  assert.match((await accountAction({ op: "verify", name: "jev-1" }, { env: {}, fetch: recorder(404).fetch })).error, /no credential to verify/);
  assert.match((await accountAction({ op: "verify", name: "jev-1" }, { env: {}, fetch: recorder(200, "<html>").fetch })).error, /no JSON/);
});

test("an account with a credential offers Verify in its credential tab, one without does not", () => {
  assert.equal([...editDialog(JEV, "credential").matchAll(/<button[^>]*>Verify</g)].length, 1);
  assert.doesNotMatch(editDialog({ ...JEV, hasCredential: false }, "credential"), />Verify</);
  assert.doesNotMatch(render({ result: { accounts: [{ ...JEV, usage: null }] } }), />Verify</, "read-only: no controls");
});

test("refreshUsage POSTs /accounts/<name>/usage/refresh; the engine's refusal is the error", async () => {
  const good = recorder(200, JSON.stringify(meter("max-1")));
  assert.deepEqual(await accountAction({ op: "refreshUsage", name: "max-1" }, { env: {}, fetch: good.fetch }), { ok: true, status: 200 });
  assert.deepEqual(good.calls, [{ url: "http://localhost:8090/accounts/max-1/usage/refresh", method: "POST", body: undefined }]);
  const conflict = recorder(409, JSON.stringify({ status: 409, message: "a jev account has no usage meter to refresh" }));
  assert.match((await accountAction({ op: "refreshUsage", name: "jev-1" }, { env: {}, fetch: conflict.fetch })).error,
    /409 for POST \/accounts\/jev-1\/usage\/refresh: a jev account has no usage meter to refresh/);
  const down = recorder(502, JSON.stringify({ status: 502, message: "could not reach anthropic: timed out" }));
  assert.match((await accountAction({ op: "refreshUsage", name: "max-1" }, { env: {}, fetch: down.fetch })).error, /502 .*could not reach anthropic/);
  const none = recorder();
  assert.match((await accountAction({ op: "refreshUsage", name: "../x" }, { env: {}, fetch: none.fetch })).error, /name is lowercase/);
  assert.equal(none.calls.length, 0);
});

test("an Analytics anthropic tile offers Refresh usage, disabled without a credential; jev and openai tiles do not; Config has none", () => {
  const accounts = [{ ...ACCOUNTS[0], usage: meter("max-1") }, { ...JEV, usage: null },
    { ...ACCOUNTS[1], hasCredential: true, usage: null }, { ...ACCOUNTS[0], name: "max-2", hasCredential: false, usage: null }];
  const html = render({ result: { accounts }, onRefreshUsage: async () => ({ ok: true }) });
  const tiles = html.split(/<article /).slice(1);
  assert.equal([...tiles[0].matchAll(/>Refresh usage</g)].length, 1);
  assert.doesNotMatch(tiles[0], /disabled=""[^>]*>Refresh usage</);
  assert.doesNotMatch(tiles[1], />Refresh usage</, "jev has no meter");
  assert.doesNotMatch(tiles[2], />Refresh usage</, "openai has no meter to ask for");
  assert.match(tiles[3], /disabled=""[^>]*>Refresh usage</, "no credential to probe with");
  assert.doesNotMatch(render({ result: { accounts }, onAction: async () => ({ ok: true }), showUsage: false }), />Refresh usage</,
    "Config > AI Accounts has no Refresh usage");
  assert.doesNotMatch(render({ result: { accounts: [{ ...ACCOUNTS[0], usage: null }] } }), />Refresh usage</, "no callback, no button");
});

test("Analytics > AI Accounts has Refresh usage as its only control", () => {
  const html = render({ result: { accounts: [{ ...ACCOUNTS[0], usage: meter("max-1") }, { ...JEV, usage: null }] },
    onRefreshUsage: async () => ({ ok: true }) });
  for (const c of [/type="checkbox"/, /Add account/, />Enable</, />Disable</, /credential<\/button>/, />Verify</, />Remove</])
    assert.doesNotMatch(html, c);
  assert.equal([...html.matchAll(/<button class="acBtn"/g)].length, 1);
});

// ---- the refiner tier: GET/PUT /intake/tier, the section above the tiles --
const { fetchRefinerTier, setRefinerTier } = require("../src/engine-data");
const { tierLabel } = require("../dist/accounts.cjs");
const { RefinerTier, RefinerTierPanel } = require("../dist/refiner-tier.cjs");

test("fetchRefinerTier reads GET /intake/tier; a failed or odd answer is the error", async () => {
  const calls = [];
  const r = await fetchRefinerTier({ env: {}, fetch: routes({ "/intake/tier": [200, { tier: 5, model: "claude-opus-5-5", effort: "medium" }] }, calls) });
  assert.deepEqual(r, { refinerTier: { tier: 5, model: "claude-opus-5-5", effort: "medium" }, error: null });
  assert.deepEqual(calls, ["http://localhost:8090/intake/tier"]);
  for (const [a, expect] of [
    [[500, "boom"], /engine answered 500 for \/intake\/tier/],
    [[200, { tier: "5" }], /no integer `tier`/],
    [new Error("ECONNREFUSED"), /unreachable/],
  ]) assert.match((await fetchRefinerTier({ env: {}, fetch: routes({ "/intake/tier": a }) })).error, expect);
});

test("the refiner-tier action is PUT /intake/tier with the tier, and answers the engine's view", async () => {
  const { calls, fetch } = recorder(200, JSON.stringify({ tier: 8, model: "claude-fable-5-1", effort: "low" }));
  const r = await accountAction({ op: "refiner-tier", tier: 8 }, { env: {}, fetch });
  assert.deepEqual(r, { ok: true, status: 200, refinerTier: { tier: 8, model: "claude-fable-5-1", effort: "low" } });
  assert.deepEqual(calls, [{ url: "http://localhost:8090/intake/tier", method: "PUT", body: { tier: 8 } }]);
});

test("a refiner tier that is no integer 1-10 is refused before any request; the engine's 400 is said back", async () => {
  const { calls, fetch } = recorder();
  for (const tier of [0, 11, 5.5, "5", null, undefined]) {
    assert.match((await setRefinerTier(tier, { env: {}, fetch })).error, /integer from 1 to 10/);
  }
  assert.equal(calls.length, 0);
  const refused = recorder(400, JSON.stringify({ status: 400, message: "tier is an integer" }));
  const r = await accountAction({ op: "refiner-tier", tier: 7 }, { env: {}, fetch: refused.fetch });
  assert.match(r.error, /400 for PUT \/intake\/tier: tier is an integer/);
});

test("main reads the refiner tier on its own IPC, engine:refiner-tier, not with the accounts", () => {
  const main = fs.readFileSync(path.join(__dirname, "..", "src", "main.js"), "utf8");
  const accounts = main.slice(main.indexOf('"engine:accounts"'), main.indexOf('"engine:refiner-tier"'));
  assert.ok(!accounts.includes("fetchRefinerTier"));
  const handler = main.slice(main.indexOf('"engine:refiner-tier"'), main.indexOf('"engine:account-action"'));
  assert.ok(handler.includes("fetchRefinerTier()"));
  assert.match(handler, /refinerTier: t\.refinerTier/);
  assert.match(fs.readFileSync(path.join(__dirname, "..", "src", "preload.js"), "utf8"), /refinerTier: \(\) => ipcRenderer\.invoke\("engine:refiner-tier"\)/);
});

const TIERS = [{ tier: 3, model: "sonnet", effort: "medium" }, { tier: 5, model: "opus", effort: "medium" }, { tier: 8, model: "fable", effort: "low" }];
const page = (props) => renderToStaticMarkup(React.createElement(RefinerTierPanel, props));
const walk = (e) => (!e || typeof e !== "object" ? [] : [e, ...[].concat(e.props && e.props.children || []).flatMap(walk)]);

test("AI Accounts no longer draws the refiner tier", () => {
  const html = render({ result: { accounts: [{ ...ACCOUNTS[0], usage: null }], refinerTier: { tier: 5, model: "m", effort: "e" } }, onAction: async () => ({ ok: true }) });
  assert.ok(!html.includes("Intake refiner tier"));
  assert.ok(!html.includes("acSetting"));
});

test("the frakworks page's refiner tier panel draws the tier with its model and effort, and a picker when writable", () => {
  const result = { refinerTier: { tier: 5, model: "claude-opus-5-5", effort: "medium" }, error: null };
  const html = page({ result, tiers: TIERS, onAction: async () => ({ ok: true }) });
  assert.ok(html.includes("Intake refiner tier"));
  assert.ok(html.includes("5 · claude-opus-5-5 medium"));
  assert.ok(html.includes('aria-label="refiner tier"'));
  assert.ok(html.includes(">8 · fable low<"), "the choices are the tier map's rows");
  const ro = page({ result, tiers: TIERS });
  assert.ok(ro.includes("5 · claude-opus-5-5 medium"));
  assert.match(ro, /<select class="acInput" aria-label="refiner tier" disabled=""/);
  assert.ok(page({ result: { refinerTier: null, error: "engine answered 500" } }).includes("refiner tier unavailable: engine answered 500"));
  assert.ok(page({ result: { loading: true } }).includes("asking the engine"));
  assert.equal(tierLabel(4), "4");
});

test("the section saves on change: a pick sends { op: refiner-tier, tier } through its run", async () => {
  const sent = [];
  // RefinerTier called inside a render; its tree is kept to drive the select afterwards.
  let tree;
  const Probe = () => (tree = RefinerTier({ refinerTier: { tier: 5, model: "m", effort: "e" }, tiers: TIERS, busy: false,
    run: async (a, done) => { sent.push([a, done]); return false; } }));
  renderToStaticMarkup(React.createElement(Probe));
  assert.ok(!walk(tree).some((e) => e.type === "button" || e.type === "form"), "no Set button: it saves on change");
  const select = walk(tree).find((e) => e.type === "select");
  await select.props.onChange({ target: { value: "5" } });
  assert.deepEqual(sent, [], "picking the current tier sends nothing");
  await select.props.onChange({ target: { value: "8" } });
  assert.deepEqual(sent, [[{ op: "refiner-tier", tier: 8 }, "refiner tier set to 8"]]);
});

test("a refused save shows the refusal and keeps the shown tier", async () => {
  const result = { refinerTier: { tier: 5, model: "m", effort: "e" }, error: null };
  const html = renderToStaticMarkup(React.createElement(RefinerTier, { ...result,
    notice: { ok: false, text: "engine answered 400: tier 7 has no [claude] row" }, tiers: TIERS, run: async () => false }));
  assert.match(html, /class="err">engine answered 400: tier 7 has no \[claude\] row/);
  assert.match(html, /<option value="5" selected="">5 · m e/);
});

test("the page sends a Set over onAction; a 400 is a failed run, which keeps the tier", async () => {
  // Drive the page's run directly: its RefinerTier child gets it as `run`.
  const result = { refinerTier: { tier: 5, model: "m", effort: "e" }, error: null };
  const drive = async (answer) => {
    const sent = [];
    let tree;
    const Probe = () => (tree = RefinerTierPanel({ result, tiers: TIERS, onRefresh: () => {},
      onAction: async (a) => { sent.push(a); return answer; } }));
    renderToStaticMarkup(React.createElement(Probe));
    const child = walk(tree).find((e) => e.type === RefinerTier);
    const ok = await child.props.run({ op: "refiner-tier", tier: 8 }, "refiner tier set to 8");
    return { sent, ok };
  };
  const good = await drive({ ok: true, status: 200, refinerTier: { tier: 8, model: "fable", effort: "low" } });
  assert.deepEqual(good.sent, [{ op: "refiner-tier", tier: 8 }]);
  assert.equal(good.ok, true);
  const bad = await drive({ error: "engine answered 400 for PUT /intake/tier: tier 7 has no [claude] row" });
  assert.equal(bad.ok, false);
});

test("the frakworks page draws First mate model, then Intake refiner tier; a failed tier read leaves First mate model drawn (#617)", () => {
  const { FirstMateModel } = require("../dist/first-mate-model.cjs");
  const draw = (result) => renderToStaticMarkup(React.createElement(FirstMateModel, { tiers: TIERS, state: { standing: 8 }, onSetTier: () => {} },
    React.createElement(RefinerTierPanel, { result, tiers: TIERS, onAction: async () => ({ ok: true }) })));
  const html = draw({ refinerTier: { tier: 5, model: "claude-opus-5-5", effort: "medium" }, error: null });
  assert.match(html, /<h1>frakworks<\/h1>/);
  const fm = html.indexOf("First mate model"), rt = html.indexOf("Intake refiner tier");
  assert.ok(fm > 0 && rt > fm, "Intake refiner tier is below First mate model");
  assert.ok(html.includes("5 · claude-opus-5-5 medium"));
  // One form: two acSetting sections of the same structure, no ⟳ between them (#636).
  assert.equal([...html.matchAll(/class="acSetting"/g)].length, 2);
  assert.doesNotMatch(html, /refreshBtn|class="meta"/);
  const shape = (sec) => [...sec.matchAll(/<(\w+) class="([^"]+)"/g)].map((m) => `${m[1]}.${m[2]}`).filter((c) => !/option/.test(c));
  const [, s1, s2] = html.split('<section class="acSetting">');
  assert.deepEqual(shape(s1), shape(s2), "both settings have one class structure");
  assert.deepEqual(shape(s1), ["div.acSettingHead", "span.acName", "p.fmmHint", "select.acInput"]);
  assert.ok(!/<button/.test(html), "both save on change: neither has a Set button");
  const failed = draw({ refinerTier: null, error: "engine answered 500" });
  assert.ok(failed.includes("First mate model") && failed.includes('aria-label="first mate tier"'));
  assert.ok(failed.includes("refiner tier unavailable: engine answered 500"));
});

// ---- the FAQ (#738) ----------------------------------------------------------

// The pitch.test.js harness: Accounts called as a function with its hooks stood in.
const mountAccounts = (props) => {
  const slots = [];
  const real = { useState: React.useState, useEffect: React.useEffect };
  const draw = () => {
    let i = 0;
    React.useState = (init) => {
      const k = i++;
      if (!(k in slots)) slots[k] = typeof init === "function" ? init() : init;
      return [slots[k], (v) => { slots[k] = typeof v === "function" ? v(slots[k]) : v; }];
    };
    React.useEffect = () => {};
    try { return Accounts(props); } finally { Object.assign(React, real); }
  };
  return { draw };
};
const walkEl = (el, out = []) => {
  if (!el || typeof el !== "object") return out;
  if (Array.isArray(el)) { for (const c of el) walkEl(c, out); return out; }
  out.push(el);
  if (el.props) walkEl(el.props.children, out);
  return out;
};
const byText = (tree, text) => walkEl(tree).find((e) => e.type === "button" && e.props.children === text);
const dialog = (tree) => walkEl(tree).find((e) => e.props && e.props.role === "dialog");

test("the FAQ button is in the head on both pages and in the error state", () => {
  for (const props of [{ result: { accounts: ACCOUNTS }, onAction: async () => ({ ok: true }) },
    { result: { accounts: ACCOUNTS }, showUsage: false }, { result: { error: "engine down" } }]) {
    assert.match(render(props), /<div class="head">.*<button[^>]*>FAQ<\/button>/s);
  }
});

test("FAQ opens the picker dialog with its seven questions and closes, with no IPC or refresh", () => {
  const { PICKER_FAQ } = require("../dist/accounts.cjs");
  const calls = [];
  const c = mountAccounts({ result: { accounts: ACCOUNTS }, now: Date.parse("2026-09-24T04:00:00Z"),
    onAction: async (a) => { calls.push(["action", a]); return { ok: true }; }, onRefresh: () => calls.push(["refresh"]) });
  let tree = c.draw();
  assert.equal(dialog(tree), undefined);
  byText(tree, "FAQ").props.onClick();
  tree = c.draw();
  const html = renderToStaticMarkup(dialog(tree));
  assert.match(html, /How the picker chooses an account/);
  assert.equal(PICKER_FAQ.length, 7);
  let at = -1;
  for (const [q] of PICKER_FAQ) { const j = html.indexOf(`<b>${q}</b>`); assert.ok(j > at, q); at = j; }
  assert.match(html, /twice an even week&#x27;s pace/);
  byText(tree, "FAQ").props.onClick();
  tree = c.draw();
  assert.equal(dialog(tree), undefined, "a second click closes it");
  byText(tree, "FAQ").props.onClick();
  tree = c.draw();
  byText(tree, "Close ✕").props.onClick();
  assert.equal(dialog(c.draw()), undefined, "Close closes it");
  assert.deepEqual(calls, []);
});

test("a window whose reset is stale reads as unknown, not the last reading", () => {
  const now = Date.parse("2026-09-24T06:00:00Z") + STALE_RESET_MS;
  const usage = meter("max-1", { fiveHourUtilization: 0.26, fiveHourReset: "2026-09-24T05:50:00Z",
    fableUtilization: 0.99, fableReset: "2026-09-24T05:00:00Z" });
  const html = render({ result: { accounts: [{ ...ACCOUNTS[0], usage }] }, now });
  const rows = html.split(/class="acWindow"/).slice(1);
  const row = (label) => rows.find((r) => r.includes(`>${label}<`));
  for (const label of ["5h", "F"]) {
    const r = row(label);
    assert.match(r, /awaiting new reading/);
    assert.match(r, /class="acPct">–</);
    assert.doesNotMatch(r, /acFill/);
  }
  assert.doesNotMatch(html, /26%|99%/);
  assert.match(row("7d"), /class="acPct">48%</);
  assert.match(row("7d"), /acFill/);
});

// ---- First mate's account: POST /accounts/pick -> pickAccount -------------
const { pickAccount } = require("../src/engine-data");

test("pickAccount asks the engine for the model's account and returns its token", async () => {
  const { calls, fetch } = recorder(200, JSON.stringify(
    { account: "anthropic-primary", provider: "anthropic", env: "CLAUDE_CODE_OAUTH_TOKEN", token: "sk-ant-oat01-x" }));
  const r = await pickAccount("fable", { env: {}, fetch });
  assert.deepEqual(r, { ok: true, account: "anthropic-primary", envVar: "CLAUDE_CODE_OAUTH_TOKEN", token: "sk-ant-oat01-x" });
  assert.deepEqual(calls, [{ url: "http://localhost:8090/accounts/pick", method: "POST", body: { model: "fable" } }]);
});

test("pickAccount is an error, never a throw, on a refusal, a tokenless answer or no model", async () => {
  const refused = recorder(503, JSON.stringify({ message: "no usable anthropic account for claude-fable-5-1" }));
  assert.match((await pickAccount("fable", { env: {}, fetch: refused.fetch })).error, /503.*no usable anthropic account/);
  const empty = recorder(200, JSON.stringify({ account: "a", env: "CLAUDE_CODE_OAUTH_TOKEN" }));
  assert.match((await pickAccount("fable", { env: {}, fetch: empty.fetch })).error, /no account, env or token/);
  const none = recorder();
  assert.match((await pickAccount("  ", { env: {}, fetch: none.fetch })).error, /no model/);
  assert.equal(none.calls.length, 0);
});

test("Refresh usage sends refreshUsage, is disabled in flight, says it refreshed and re-fetches; a refusal is said and nothing is re-fetched", async () => {
  for (const [answer, notice, refreshes] of [
    [{ ok: true, status: 200 }, '<div class="acNotice">max-1: usage refreshed</div>', 1],
    [{ error: "engine answered 502 for POST /accounts/max-1/usage/refresh: could not reach anthropic" },
      '<div class="err">engine answered 502 for POST /accounts/max-1/usage/refresh: could not reach anthropic</div>', 0],
  ]) {
    const sent = [];
    let refreshed = 0, inFlight = null;
    const c = mountAccounts({ result: { accounts: [{ ...ACCOUNTS[0], usage: meter("max-1") }] }, now: Date.parse("2026-09-24T04:00:00Z"),
      onRefresh: () => { refreshed++; },
      onRefreshUsage: async (a) => { sent.push(a); inFlight = byText(c.draw(), "Refresh usage").props.disabled; return answer; } });
    const click = byText(c.draw(), "Refresh usage").props.onClick();
    await click;
    assert.deepEqual(sent, [{ op: "refreshUsage", name: "max-1" }]);
    assert.equal(inFlight, true, "disabled while the action is in flight");
    const tree = c.draw();
    assert.equal(byText(tree, "Refresh usage").props.disabled, false);
    assert.ok(renderToStaticMarkup(tree).includes(notice), notice);
    assert.equal(refreshed, refreshes);
  }
});

test("the add form and the set-email form ask for the email and say it is for the user's reference only", () => {
  const { AddForm, EmailForm, EMAIL_NOTE } = require("../dist/accounts.cjs");
  assert.equal(EMAIL_NOTE, "For your reference only: the engine never uses this address.");
  const add = renderToStaticMarkup(React.createElement(AddForm, { busy: false, run: async () => true, onClose: () => {} }));
  assert.match(add, /placeholder="email \(optional\), for your reference"/);
  assert.ok(add.includes(EMAIL_NOTE));
  const set = renderToStaticMarkup(React.createElement(EmailForm, { account: { name: "max-1", email: "me@example.com" }, busy: false, run: async () => true, onClose: () => {} }));
  assert.ok(set.includes(EMAIL_NOTE));
  assert.match(set, /value="me@example.com"/);
});

test("a tile shows its email when set and nothing when not; the details tab edits it", () => {
  const html = render({ result: { accounts: [{ ...ACCOUNTS[0], email: "me@example.com" }, { ...ACCOUNTS[1], email: null }] }, onAction: async () => ({ ok: true }) });
  assert.match(html, /class="acEmail"[^>]*>me@example.com</);
  assert.equal((html.match(/class="acEmail"/g) || []).length, 1);
  assert.ok(!/>null</.test(html));
  const d = editDialog({ ...ACCOUNTS[0], email: "me@example.com" }, "details");
  assert.match(d, /value="me@example.com"/);
  assert.ok(d.includes("For your reference only"));
});

test("each account's details tab offers Rename, and the rename form is prefilled with the account's name", async () => {
  for (const a of ACCOUNTS) assert.equal((editDialog(a, "details").match(/>Rename</g) || []).length, 1);
  const { RenameForm } = require("../dist/accounts.cjs");
  const form = renderToStaticMarkup(React.createElement(RenameForm, { account: { name: "anthropic-3" }, busy: false, run: async () => true, onClose: () => {} }));
  assert.match(form, /value="anthropic-3"/);
  assert.match(form, /type="submit"[^>]*>Rename</);
});

test("the Which account would be used? tile is on Analytics > AI Accounts only, and holds no account control (#1260)", () => {
  const result = { accounts: [{ ...ACCOUNTS[0], usage: meter("max-1") }] };
  const analytics = render({ result, onRefreshUsage: async () => ({ ok: true }) });
  assert.match(analytics, /<h2[^>]*>.*Which account would be used\?<\/h2>/);
  const tile = analytics.slice(analytics.indexOf('<section class="tier-accounts-tile"'), analytics.indexOf("</section>"));
  assert.ok(tile.length > 0);
  assert.doesNotMatch(tile, /acTile|acBtn|acToggle|acActions|acBar|acMeter|type="checkbox"|>Rename<|credential|>Remove<|>Enable<|>Disable</);
  const config = render({ result, showUsage: false, onAction: async () => ({ ok: true }) });
  assert.doesNotMatch(config, /Which account would be used\?|tier-accounts-tile/);
});

// ---- API keys, billing cycle and credit (engine/docs/ai-accounts.md, "API keys and credit") ----

test("the key, billing and credit actions are the engine's requests for them", async () => {
  const cases = [
    [{ op: "api-key", name: "max-1", key: " sk-ant-usr-a " }, "PUT", "/accounts/max-1/api-key", { key: "sk-ant-usr-a" }],
    [{ op: "admin-key", name: "max-1", key: "sk-ant-admin01-b" }, "PUT", "/accounts/max-1/admin-key", { key: "sk-ant-admin01-b" }],
    [{ op: "clear-api-key", name: "max-1" }, "DELETE", "/accounts/max-1/api-key", undefined],
    [{ op: "clear-admin-key", name: "max-1" }, "DELETE", "/accounts/max-1/admin-key", undefined],
    [{ op: "billing", name: "max-1", cycleDate: "2026-01-14", grantCents: 10000 }, "PUT", "/accounts/max-1/billing", { cycleDate: "2026-01-14", grantCents: 10000 }],
    [{ op: "refreshCredit", name: "max-1" }, "POST", "/accounts/max-1/credit/refresh", undefined],
  ];
  for (const [action, method, p, body] of cases) {
    const { calls, fetch } = recorder();
    assert.deepEqual(await accountAction(action, { env: {}, fetch }), { ok: true, status: 204 });
    assert.deepEqual(calls, [{ url: `http://localhost:8090${p}`, method, body }]);
  }
});

test("a key of the wrong kind or a bad billing cycle is refused before any request", async () => {
  const { calls, fetch } = recorder();
  for (const [action, expect] of [
    [{ op: "api-key", name: "x", key: "sk-ant-admin01-k" }, /API key starts with sk-ant-api03- or sk-ant-usr-/],
    [{ op: "admin-key", name: "x", key: "sk-ant-api03-k" }, /admin key starts with sk-ant-admin01- or sk-ant-usr-/],
    [{ op: "api-key", name: "x", key: "sk-ant-usr-a b" }, /whitespace/],
    [{ op: "billing", name: "x", cycleDate: "14th", grantCents: 1 }, /YYYY-MM-DD/],
    [{ op: "billing", name: "x", cycleDate: "2026-01-14", grantCents: -1 }, /whole number of cents/],
  ]) assert.match((await accountAction(action, { env: {}, fetch })).error, expect);
  assert.deepEqual(calls, []);
});

test("a tracked account's tile shows the credit used, left and the cycle, and a failed refresh says so", () => {
  const { fmtCents } = require("../dist/accounts.cjs");
  assert.equal(fmtCents("3712.4567"), "$37.12");
  assert.equal(fmtCents(null), "–");
  const a = { ...ACCOUNTS[0], adminKeySet: true, apiKeySet: true, billingCycleDate: "2026-01-14", grantCents: 10000,
    usedCents: 3712, remainingCents: 6288, cycleStart: "2026-10-14", cycleEnd: "2026-11-14",
    creditRefreshedAt: new Date(Date.now() - 120000).toISOString(), creditError: "cost_report answered 500: overloaded", usage: null };
  const html = render({ result: { accounts: [a] } });
  assert.ok(html.includes("$37.12 used / $100.00 · $62.88 left"), html);
  assert.ok(html.includes("cycle 2026-10-14 – 2026-11-14"));
  assert.ok(html.includes("credit refresh failed: cost_report answered 500: overloaded"));
  assert.doesNotMatch(render({ result: { accounts: [{ ...ACCOUNTS[0], usage: null }] } }), /acCredit/, "untracked: no credit row");
});

test("the API keys tab shows whether each key is set, never a key, and asks for the cycle before the admin key", () => {
  const a = { ...ACCOUNTS[0], apiKeySet: true, adminKeySet: false, billingCycleDate: null, grantCents: null };
  const html = editDialog(a, "keys");
  assert.match(html, /● set/);
  assert.match(html, /type="password"[^>]*value=""/);
  assert.match(html, />Clear</);
  assert.match(html, /Set the billing cycle first/);
  const dated = editDialog({ ...a, billingCycleDate: "2026-01-14", grantCents: 10000 }, "keys");
  assert.match(dated, /value="2026-01-14"/);
  assert.match(dated, /value="100"/);
  assert.match(dated, /set admin key: sk-ant-admin01-/);
  assert.match(editDialog(ACCOUNTS[1], "keys"), /Only an Anthropic account/);
});
