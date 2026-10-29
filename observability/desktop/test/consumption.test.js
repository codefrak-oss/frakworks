// Analytics > AI Accounts > Account consumption (#1934): src/renderer/consumption.jsx
// over src/engine-data.js fetchConsumption, GET /accounts/consumption?window=.
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { ConsumptionView, TIMEFRAMES, fmtDuration, fmtUsd, rowName, UNKNOWN } = require("../dist/consumption.cjs");
const { fetchConsumption, CONSUMPTION_WINDOWS } = require("../src/engine-data");

const fake = (status, body) => {
  const urls = [];
  return { urls, opts: { env: { engineUrl: "http://engine.test" }, fetch: async (url) => {
    urls.push(url);
    return { ok: status >= 200 && status < 300, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)) };
  } } };
};

const row = (key, caller, o = {}) => ({ key, caller, calls: 1, costUsd: null, durationMs: null,
  inputTokens: null, outputTokens: null, cacheReadTokens: null, cacheWriteTokens: null, unknown: {}, ...o });

test("the timeframes include 1 hour, 4 hours, 1 day and 1 week, and each is a window the engine takes", () => {
  const keys = TIMEFRAMES.map(([k]) => k);
  for (const k of ["1h", "4h", "1d", "1w"]) assert.ok(keys.includes(k), k);
  assert.deepEqual(keys, CONSUMPTION_WINDOWS);
});

test("fetchConsumption asks the engine for the chosen window, each change a new read", async () => {
  const f = fake(200, { since: "2026-10-09T00:00:00Z", until: "2026-10-09T01:00:00Z", rows: [row("total", "total")] });
  for (const w of ["1h", "4h", "1d", "1w"]) {
    const r = await fetchConsumption(w, f.opts);
    assert.equal(r.error, null); assert.equal(r.rows.length, 1);
  }
  assert.deepEqual(f.urls, ["1h", "4h", "1d", "1w"].map((w) => `http://engine.test/accounts/consumption?window=${w}`));
  assert.match((await fetchConsumption("2y", f.opts)).error, /not a timeframe/);
  assert.equal(f.urls.length, 4, "a bad timeframe sends nothing");
  assert.equal((await fetchConsumption("1h", fake(404, "").opts)).error, "this engine has no /accounts/consumption (older than #1934)");
});

test("the page reloads when the timeframe changes: its loader depends on the timeframe", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "consumption.jsx"), "utf8");
  assert.match(src, /try \{ r = await fetchRows\(timeframe\); \}/);
  assert.match(src, /\}, \[timeframe, loadRows\]\);/);
  assert.match(src, /useEffect\(\(\) => \{\n    setResult\(\{ loading: true \}\);\n    load\(\);/);
});

test("rows by role, then by caller, then the total, with USD, wall clock and the four token kinds; unknown is not 0", () => {
  const rows = [
    row("coding", "smith-run", { calls: 3, costUsd: 4.5, durationMs: 3_725_000, inputTokens: 1200, outputTokens: 300, cacheReadTokens: 90000, cacheWriteTokens: 5000 }),
    row("correctness", "smith-run", { costUsd: 0.25, durationMs: 42_000, inputTokens: 10, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 }),
    row("summarizer", "summarizer", { durationMs: 850, inputTokens: 900, outputTokens: 30, cacheReadTokens: 0, cacheWriteTokens: 0, unknown: { costUsd: 1 } }),
    row("total", "total", { calls: 5, costUsd: 4.75, unknown: { costUsd: 1 } }),
  ];
  const html = renderToStaticMarkup(React.createElement(ConsumptionView, { result: { rows }, timeframe: "4h" }));
  for (const h of ["USD", "wall clock", "input", "output", "cache read", "cache write"]) assert.ok(html.includes(`>${h}</th>`), h);
  const order = ["coding", "correctness", "session summarizer", "total"].map((n) => html.indexOf(`<td>${n}</td>`));
  assert.ok(order.every((i, n) => i > 0 && (n === 0 || i > order[n - 1])), `order ${order}`);
  assert.ok(html.includes("$4.50") && html.includes("1 h 02 m") && html.includes("90,000"));
  assert.ok(html.includes(`<td class="num muted">${UNKNOWN}</td>`), "the summarizer's cost is unknown, not $0.00");
  assert.ok(!html.includes("$0.00"));
  assert.match(html, /<button class="on" title="4 hours">4h<\/button>/);
});

test("formats: wall clock in a human unit, USD, and a caller's own name", () => {
  assert.equal(fmtDuration(850), "850 ms"); assert.equal(fmtDuration(42_000), "42 s");
  assert.equal(fmtDuration(185_000), "3 m 05 s"); assert.equal(fmtDuration(null), UNKNOWN); assert.equal(fmtDuration(0), "0 ms");
  assert.equal(fmtUsd(null), UNKNOWN); assert.equal(fmtUsd(0.0123), "$0.0123"); assert.equal(fmtUsd(12), "$12.00");
  assert.equal(rowName(row("doctor", "doctor")), "doctor runs");
  assert.equal(rowName(row("usage-probe", "usage-probe")), "usage refresh probe");
  assert.equal(rowName(row("refine", "smith-run")), "refine");
});

test("an engine error is one line and no table; an empty window says so", () => {
  const err = renderToStaticMarkup(React.createElement(ConsumptionView, { result: { error: "engine unreachable" } }));
  assert.ok(err.includes("engine unreachable") && !err.includes("<table"));
  const empty = renderToStaticMarkup(React.createElement(ConsumptionView, { result: { rows: [row("total", "total", { calls: 0 })] } }));
  assert.ok(empty.includes("no Claude calls recorded in the window"));
});
