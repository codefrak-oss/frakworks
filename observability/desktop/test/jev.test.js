// jev.jsx — the Jev decisions page (#553), drawn from
// fixture-jev-summary.json, fixture-jev-calls.json and
// fixture-jev-call.json (the shapes GET /jev/summary, /jev/calls and
// /jev/calls/{id} answer, engine/README.md "Jev decisions"). The page's parts
// are reached through dist/ticket.cjs, which re-exports them.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const {
  JevDecisionsView, JevCallDrawer, JevCallsTable, JevStrip, JevUseCase, ConfidenceHistogram, PickBars,
  topPicks, daysOf, jevCallIdOf, mergeCalls, sinceOf, fmtPct, fmtAt, USE_CASES, DEFAULT_WINDOW, TOP_PICKS,
} = require("../dist/ticket.cjs");

const load = (f) => JSON.parse(fs.readFileSync(path.join(__dirname, "..", f), "utf8"));
const summary = load("fixture-jev-summary.json");
const calls = load("fixture-jev-calls.json");
const call = load("fixture-jev-call.json");
const render = (C, props) => renderToStaticMarkup(React.createElement(C, props));

test("the page draws the top strip, then the six use cases in the engine's order", () => {
  const html = render(JevDecisionsView, { result: { summary }, win: DEFAULT_WINDOW });
  assert.equal(DEFAULT_WINDOW, "7d");
  assert.match(html, /<h1>Jev decisions<\/h1>/);
  for (const w of ["24h", "7d", "30d", "90d"]) assert.ok(html.includes(`>${w}</button>`), w);
  assert.match(html, /<button class="on">7d<\/button>/, "the window in force is marked");
  assert.match(html, /<b>19<\/b><span>calls<\/span>/);
  assert.match(html, /<b>11%<\/b><span>no answer<\/span>/, "2 of 19");
  assert.match(html, /<b>840 ms<\/b><span>median latency<\/span>/);
  assert.match(html, /<b>78%<\/b><span>agreement<\/span>/);
  const order = USE_CASES.map(([k]) => html.indexOf(`id="jev-${k}"`));
  assert.ok(order.every((i) => i > 0), "every use case has a section, even one with no calls");
  assert.deepEqual([...order].sort((a, b) => a - b), order);
  assert.deepEqual(USE_CASES.map(([k]) => k), summary.useCases.map((u) => u.useCase));
});

test("agreement is a dash when nothing was judged, overall and per use case", () => {
  const html = render(JevStrip, { totals: { ...summary.totals, agreement: null, judged: 0 }, win: "7d" });
  assert.match(html, /<b>–<\/b><span>agreement<\/span>/);
  assert.equal(fmtPct(null), "–");
  const empty = render(JevUseCase, { uc: summary.useCases[1], since: summary.since, until: summary.until });
  assert.match(empty, /agreement –/);
  assert.match(empty, /no calls in the window/);
});

test("runs saved shows for the two triage use cases only", () => {
  const byName = Object.fromEntries(summary.useCases.map((u) => [u.useCase, u]));
  const ci = render(JevUseCase, { uc: byName["ci-triage"], since: summary.since, until: summary.until });
  assert.match(ci, /1 run saved/);
  assert.match(ci, /a re-run that passed instead of a Smith/);
  const inc = render(JevUseCase, { uc: byName["incident-triage"], since: summary.since, until: summary.until });
  assert.match(inc, /an incident retried without a Smith/);
  for (const name of ["acceptance", "refine-pick", "intake-duplicate"]) {
    assert.doesNotMatch(render(JevUseCase, { uc: byName[name], since: summary.since, until: summary.until }), /saved/, name);
  }
});

test("a section draws calls per day, picks per question, the histogram with its floor, and the action counts", () => {
  const uc = summary.useCases.find((u) => u.useCase === "refine-pick");
  const html = render(JevUseCase, { uc, since: summary.since, until: summary.until, calls: { calls: calls.calls, total: 3 } });
  assert.match(html, /<title>2026-09-26: 3 calls<\/title>/);
  assert.match(html, /<title>2026-09-22: 0 calls<\/title>/, "a quiet day is a zero bar, not a gap");
  for (const q of ["kind", "featureMode", "tier"]) assert.ok(html.includes(`<div class="jevQ">${q}</div>`), q);
  assert.match(html, /floor 0\.60/);
  assert.match(html, /filed at jev&#x27;s pick <b>1<\/b>/);
  assert.match(html, /<table class="jevCalls">/);
  assert.equal(daysOf(uc.perDay, summary.since, summary.until).length, 8, "the 20th to the 27th");
});

test("the histogram has ten buckets and draws the floor as a line; no floor, no line", () => {
  const html = render(ConfidenceHistogram, { confidence: [0, 0, 0, 1, 0, 0, 1, 2, 2, 2], floor: 0.9 });
  assert.equal((html.match(/<rect /g) || []).length, 10);
  assert.match(html, /class="jevFloor"><line x1="468"/);
  assert.doesNotMatch(render(ConfidenceHistogram, { confidence: [1, 0, 0, 0, 0, 0, 0, 0, 0, 0], floor: null }), /jevFloor/);
});

test("a question with many options shows its top ones and 'other'", () => {
  const dup = summary.useCases.find((u) => u.useCase === "intake-duplicate").picks.duplicate;
  const top = topPicks(dup);
  assert.equal(top.length, TOP_PICKS);
  assert.deepEqual(top[0], ["none", 3]);
  assert.deepEqual(top[top.length - 1], ["other", 2]);
  assert.equal(top.reduce((s, [, v]) => s + v, 0), 9, "nothing is dropped");
  assert.match(render(PickBars, { question: "duplicate", counts: dup }), />other</);
  assert.deepEqual(topPicks({ yes: 2, no: 1 }), [["yes", 2], ["no", 1]]);
});

test("the calls table: when, a ticket link that opens the ticket detail, pick, confidence, action, outcome", () => {
  const html = render(JevCallsTable, { calls: calls.calls, onOpenTicket: () => {} });
  assert.match(html, /2026-09-27 09:14 UTC/);
  assert.match(html, /href="https:\/\/github.com\/codefrak-oss\/frakworks\/issues\/553"[^>]*>#553<\/a>/);
  assert.match(html, />infrastructure<\/td><td class="num">0\.82<\/td>/);
  assert.match(html, /the re-run passed/);
  assert.match(html, /class="jevRow noAnswer"[^>]*>.*no answer/);
  // the link's click opens the ticket in-app and does not also open the drawer
  const opened = [], drawn = [];
  const table = JevCallsTable({ calls: calls.calls.slice(0, 1), onOpenTicket: (r, n) => opened.push(`${r}#${n}`), onOpenCall: (id) => drawn.push(id) });
  const row = table.props.children[1].props.children[0];
  const link = row.props.children[1].props.children;
  let prevented = false, stopped = false;
  link.props.onClick({ preventDefault: () => { prevented = true; }, stopPropagation: () => { stopped = true; } });
  assert.deepEqual(opened, ["codefrak-oss/frakworks#553"]);
  assert.ok(prevented && stopped);
  row.props.onClick();
  assert.deepEqual(drawn, [calls.calls[0].id]);
});

test("the drawer shows the state and questions as sent, the probabilities per question, and the outcome", () => {
  const html = render(JevCallDrawer, { state: { call } });
  assert.match(html, /Refine pick · 2026-09-26 07:59 UTC/);
  assert.match(html, /State as sent/);
  assert.match(html, /Jev decisions dashboard in the desktop app/);
  assert.match(html, /Questions as sent/);
  assert.match(html, /What kind of work is this\?/);
  assert.match(html, /<span class="jevProbOpt">feature<\/span><span class="jevProbBar"><i style="width:91%"><\/i><\/span>/);
  assert.match(html, /relabelled from feature direct tier 7/);
  assert.match(html, /floor 0\.60/);
  const failed = render(JevCallDrawer, { state: { call: { ...call, error: "jev answered 503", picks: [], outcome: null } } });
  assert.match(failed, /<div class="err">jev answered 503<\/div>/);
  assert.match(failed, /not yet known/);
  assert.equal(render(JevCallDrawer, { state: null }), "");
  assert.match(render(JevCallDrawer, { state: { error: "no jev call x" } }), /no jev call x/);
});

test("the drawer shows the raw request and response verbatim as highlighted JSON (#607)", () => {
  const html = render(JevCallDrawer, { state: { call } });
  const section = (cls) => html.split(`class="${cls}"`)[1].split("</div>")[0];
  const req = section("jevRawRequest");
  const res = section("jevRawResponse");
  assert.match(html, /Raw request/);
  assert.match(html, /Raw response/);
  assert.match(req, /<span class="jsonKey">&quot;prompt_version&quot;<\/span>/);
  assert.doesNotMatch(res, /prompt_version/);
  assert.match(res, /<span class="jsonKey">&quot;usage_tokens&quot;<\/span>/);
  assert.doesNotMatch(req, /usage_tokens/);
  for (const k of Object.keys(call.request)) assert.match(req, new RegExp(`&quot;${k}&quot;`));
  for (const k of Object.keys(call.response)) assert.match(res, new RegExp(`&quot;${k}&quot;`));
  assert.match(req, /<span class="jsonStr">&quot;refine-pick-v3&quot;<\/span>/);
  assert.match(req, /<span class="jsonNum">0<\/span>/);
  assert.match(req, /<span class="jsonLit">false<\/span>/);
  assert.match(res, /<span class="jsonLit">null<\/span>/);
  assert.match(req, /\{\n  <span class="jsonKey">/);
  const text = render(JevCallDrawer, { state: { call: { ...call, response: "not json <b>at all</b>" } } });
  const textRes = text.split('class="jevRawResponse"')[1].split("</div>")[0];
  assert.match(textRes, /jsonText">not json &lt;b&gt;at all&lt;\/b&gt;<\/pre>/);
  assert.doesNotMatch(textRes, /jsonStr/);
  const failed = render(JevCallDrawer, { state: { call: { ...call, response: null, error: "jev answered 503" } } });
  assert.match(failed.split('class="jevRawResponse"')[1].split("</div>")[0], /jev answered 503/);
});

test("merged calls sort oldest first", () => {
  const merged = mergeCalls(calls.calls, [call], [calls.calls[0]]);
  assert.deepEqual(merged.map((c) => c.called_at),
    ["2026-09-26T07:59:30Z", "2026-09-26T08:02:11Z", "2026-09-26T16:40:00Z", "2026-09-27T09:14:02Z"]);
});

test("the refine-pick call is read from the body's intake trailer", () => {
  const body = "Ask…\n\n<!-- engine: intake 8b60ef4c-ba0e-11f1-8651-726c937bef24 · jev 9A8B7C6D-1111-4222-8333-444455556666 -->\n";
  assert.equal(jevCallIdOf(body), "9a8b7c6d-1111-4222-8333-444455556666");
  assert.equal(jevCallIdOf("<!-- engine: intake abc -->"), null);
  assert.equal(jevCallIdOf(null), null);
});

test("the window switch and times", () => {
  const now = Date.parse("2026-09-27T12:00:00Z");
  assert.equal(sinceOf("24h", now), "2026-09-26T12:00:00.000Z");
  assert.equal(sinceOf("90d", now), "2026-06-29T12:00:00.000Z");
  assert.equal(sinceOf("nonsense", now), "2026-09-20T12:00:00.000Z", "falls back to 7 days");
  assert.equal(fmtAt("2026-09-27T09:14:02Z"), "2026-09-27 09:14 UTC");
});

test("a failed summary is the widget's error block, never a blank page", () => {
  const html = render(JevDecisionsView, { result: { error: "engine answered 404 for /jev/summary" }, win: "7d" });
  assert.match(html, /<div class="err">engine answered 404 for \/jev\/summary<\/div>/);
  assert.match(render(JevDecisionsView, { result: { loading: true }, win: "7d" }), /asking the engine for the summary/);
});

test("a use case folds behind its heading and remembers the choice per viewer", () => {
  const uc = summary.useCases.find((u) => u.calls);
  const calls = { calls: [], total: 0 };
  const store = {};
  global.localStorage = { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = v; } };
  try {
    const open = render(JevUseCase, { uc, since: summary.since, until: summary.until, calls });
    assert.match(open, /class="tdFold"/);
    assert.match(open, /▾/);
    assert.match(open, /Calls per day/);
    assert.match(open, new RegExp(`id="jev-${uc.useCase}"`));
    store[`jev.open.${uc.useCase}`] = "false";
    const folded = render(JevUseCase, { uc, since: summary.since, until: summary.until, calls });
    assert.match(folded, /▸/);
    assert.match(folded, new RegExp(`id="jev-${uc.useCase}"`));
    assert.match(folded, /jevUcMeta/);
    assert.match(folded, /agreement/);
    assert.doesNotMatch(folded, /Calls per day|jevLabel/);
  } finally {
    delete global.localStorage;
  }
});
