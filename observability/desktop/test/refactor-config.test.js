// Config > Repos, Edit… > Refactoring (#1399, #1943): src/engine-data.js's
// fetchRefactorConfig and saveRefactorConfig against a stand-in fetch, and
// src/renderer/refactor-config.jsx's preset mapping, patch and view.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { fetchRefactorConfig, saveRefactorConfig, fetchJobRuns } = require("../src/engine-data");
const { schedulePreset, presetSeconds, configForm, refactorPatch, saveRefactor, saveRefactorPage, RefactorView, SCHEDULE_PRESETS } = require("../dist/refactor-config.cjs");

const env = { engineUrl: "http://engine.test" };
const CONFIG = { repo: "acme/widgets", enabled: false, schedule: 604800, values: "shipped", valuesDefault: "shipped",
  maxTickets: 2, maxLines: 300, approval: "human", jobId: null, updatedAt: null, updatedBy: null, tier: null };
const stub = (status, body) => {
  const calls = [];
  const fetch = async (url, init = {}) => { calls.push({ url, init }); return { ok: status < 300, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)) }; };
  return { calls, fetch };
};

test("fetchRefactorConfig: GET /repos/{owner}/{name}/refactor answers { config }", async () => {
  const s = stub(200, CONFIG);
  const r = await fetchRefactorConfig("acme/widgets", { env, fetch: s.fetch });
  assert.deepEqual(r, { config: CONFIG });
  assert.equal(s.calls[0].url, "http://engine.test/repos/acme/widgets/refactor");
});

test("fetchRefactorConfig and saveRefactorConfig refuse a missing or malformed repo without sending", async () => {
  const s = stub(200, CONFIG);
  for (const repo of [null, "", "acme", "a/b/c"]) {
    assert.match((await fetchRefactorConfig(repo, { env, fetch: s.fetch })).error, /needs a repo/);
    assert.match((await saveRefactorConfig(repo, { enabled: true }, "lundmatt1", { env, fetch: s.fetch })).error, /needs a repo/);
  }
  assert.equal(s.calls.length, 0);
});

test("saveRefactorConfig: PUT with the patch and no X-Frakworks-Login, answers the config", async () => {
  const s = stub(200, { ...CONFIG, enabled: true });
  const r = await saveRefactorConfig("acme/widgets", { enabled: true, values: null }, "lundmatt1", { env, fetch: s.fetch });
  assert.equal(r.ok, true);
  assert.equal(r.config.enabled, true);
  const { url, init } = s.calls[0];
  assert.equal(url, "http://engine.test/repos/acme/widgets/refactor");
  assert.equal(init.method, "PUT");
  assert.equal(init.headers["X-Frakworks-Login"], undefined);
  assert.deepEqual(JSON.parse(init.body), { enabled: true, values: null });
});

test("saveRefactorConfig: a 400 carries the engine's field text; no login is refused unsent", async () => {
  const s = stub(400, { message: "maxTickets: 1 to 20" });
  const r = await saveRefactorConfig("acme/widgets", { maxTickets: 99 }, "lundmatt1", { env, fetch: s.fetch });
  assert.equal(r.status, 400);
  assert.equal(r.body, "maxTickets: 1 to 20");
  const n = await saveRefactorConfig("acme/widgets", { maxTickets: 2 }, null, { env, fetch: s.fetch });
  assert.match(n.error, /no GitHub login/);
  assert.equal(s.calls.length, 1);
});

test("presets: each named period round-trips, any other period is custom", () => {
  for (const p of SCHEDULE_PRESETS.filter((x) => x.seconds)) assert.equal(schedulePreset(presetSeconds(p.id)), p.id);
  assert.equal(schedulePreset(86400), "daily");
  assert.equal(schedulePreset(604800), "weekly");
  assert.equal(schedulePreset(3600), "custom");
  assert.equal(presetSeconds("custom"), null);
});

test("refactorPatch: only changed keys; Reset sends values: null; bad numbers are refused", () => {
  const f = configForm(CONFIG);
  assert.deepEqual(refactorPatch(f, CONFIG), { patch: {} });
  assert.deepEqual(refactorPatch({ ...f, enabled: true, preset: "daily", maxLines: "500" }, CONFIG).patch, { enabled: true, schedule: 86400, maxLines: 500 });
  assert.deepEqual(refactorPatch({ ...f, preset: "custom", seconds: "3600", approval: "none" }, CONFIG).patch, { schedule: 3600, approval: "none" });
  assert.deepEqual(refactorPatch({ ...f, values: "mine" }, CONFIG).patch, { values: "mine" });
  const custom = { ...CONFIG, values: "mine" };
  assert.deepEqual(refactorPatch({ ...configForm(custom), values: "shipped", reset: true }, custom).patch, { values: null });
  assert.match(refactorPatch({ ...f, preset: "custom", seconds: "59" }, CONFIG).error, /schedule/);
  assert.match(refactorPatch({ ...f, maxTickets: "21" }, CONFIG).error, /max tickets/);
  assert.match(refactorPatch({ ...f, maxLines: "9" }, CONFIG).error, /max size/);
  assert.match(refactorPatch({ ...f, values: "  " }, CONFIG).error, /values/);
});

test("refactorPatch: the tier picker sends a tier 1-10, and none as null", () => {
  const f = configForm(CONFIG);
  assert.equal(f.tier, "");
  assert.deepEqual(refactorPatch({ ...f, tier: "7" }, CONFIG).patch, { tier: 7 });
  const tiered = { ...CONFIG, tier: 7 };
  assert.equal(configForm(tiered).tier, "7");
  assert.deepEqual(refactorPatch(configForm(tiered), tiered).patch, {});
  assert.deepEqual(refactorPatch({ ...configForm(tiered), tier: "" }, tiered).patch, { tier: null });
  assert.match(refactorPatch({ ...f, tier: "11" }, CONFIG).error, /tier/);
});

test("saveRefactor: sends the patch through fleet, unchanged sends nothing, shows the engine's body", async () => {
  const sent = [];
  const fleet = { saveRefactorConfig: async (repo, patch) => { sent.push([repo, patch]); return patch.maxTickets === 3 ? { ok: true, config: { ...CONFIG, maxTickets: 3 } } : { error: "e", body: "maxTickets: 1 to 20" }; } };
  assert.deepEqual(await saveRefactor(fleet, "acme/widgets", configForm(CONFIG), CONFIG), { unchanged: true });
  assert.equal((await saveRefactor(fleet, "acme/widgets", { ...configForm(CONFIG), maxTickets: "3" }, CONFIG)).config.maxTickets, 3);
  assert.deepEqual(sent, [["acme/widgets", { maxTickets: 3 }]]);
  assert.deepEqual(await saveRefactor(fleet, "acme/widgets", { ...configForm(CONFIG), maxTickets: "4" }, CONFIG), { error: "maxTickets: 1 to 20" });
});

test("RefactorView: the Edit… page's form for the tile's repo", () => {
  const html = renderToStaticMarkup(React.createElement(RefactorView, { repo: "acme/widgets", result: { config: CONFIG } }));
  for (const label of ["enabled", "schedule", "max tickets (1–20)", "max size, lines (10–5000)", "approval", "values"]) assert.ok(html.includes(`aria-label="${label}"`), label);
  assert.match(html, /Reset to default/);
  const tiered = renderToStaticMarkup(React.createElement(RefactorView, { repo: "acme/widgets", result: { config: CONFIG }, tiers: [{ tier: 5, model: "opus", effort: "medium" }] }));
  assert.ok(tiered.includes('aria-label="tier"')); assert.match(tiered, />none: no scan<\/option>/); assert.match(tiered, />5 · opus medium<\/option>/);
  assert.match(html, />shipped<\/textarea>/);
  // The run history moved to Analytics > Refactoring (#1781): one pointer line, no runs.
  assert.doesNotMatch(html, /rfRuns|recent runs|no runs yet/);
  assert.match(html, /Run history is on\s+Analytics &gt; Refactoring\./);
  assert.match(html, /<div class="erTitle">Refactoring<\/div>/);
  assert.doesNotMatch(html, /repo switcher|type="submit"/, "no repo prompt; Save is the modal's footer");
});

test("saveRefactorPage: one changed key PUTs only that key and closes; nothing changed sends nothing and closes; a refusal stays", async () => {
  const sent = [];
  let closed = 0, error = "old";
  const fleet = { saveRefactorConfig: async (repo, patch) => { sent.push([repo, patch]); return { ok: true, config: { ...CONFIG, ...patch } }; } };
  await saveRefactorPage(fleet, "acme/widgets", { ...configForm(CONFIG), approval: "none" }, CONFIG, (e) => { error = e; }, () => { closed++; });
  assert.deepEqual([sent, closed, error], [[["acme/widgets", { approval: "none" }]], 1, null]);
  await saveRefactorPage(fleet, "acme/widgets", configForm(CONFIG), CONFIG, (e) => { error = e; }, () => { closed++; });
  assert.deepEqual([sent.length, closed], [1, 2]);
  const refusing = { saveRefactorConfig: async () => ({ ok: false, body: "engine answered 400: maxLines: 10 to 5000" }) };
  await saveRefactorPage(refusing, "acme/widgets", { ...configForm(CONFIG), maxLines: "400" }, CONFIG, (e) => { error = e; }, () => { closed++; });
  assert.deepEqual([error, closed], ["engine answered 400: maxLines: 10 to 5000", 2]);
});

// Recent runs (#1653): GET /jobs/{id}/runs and how each run reads.
test("fetchJobRuns: GET /jobs/{id}/runs?limit= answers { runs }, and refuses a bad id without sending", async () => {
  const s = stub(200, [{ instance: "i1" }]);
  assert.deepEqual(await fetchJobRuns("job-1", 5, { env, fetch: s.fetch }), { runs: [{ instance: "i1" }] });
  assert.equal(s.calls[0].url, "http://engine.test/jobs/job-1/runs?limit=5");
  assert.ok((await fetchJobRuns("../x", 5, { env, fetch: s.fetch })).error);
  assert.equal(s.calls.length, 1);
});

test("RefactorView: enabled with no scan tier warns in red and marks the picker, and only then", () => {
  const WARN = "Enabled with no scan tier: every run fails and no scan is filed. Pick a scan tier.";
  for (const [enabled, tier, warns] of [[true, null, true], [true, 5, false], [false, null, false], [false, 5, false]]) {
    const html = renderToStaticMarkup(React.createElement(RefactorView, { repo: "acme/widgets", result: { config: { ...CONFIG, enabled, tier } } }));
    assert.equal(html.includes(WARN), warns, `warning for enabled=${enabled} tier=${tier}`);
    assert.equal(/<select class="acInput err" aria-label="tier"/.test(html), warns, `red picker for enabled=${enabled} tier=${tier}`);
  }
});
