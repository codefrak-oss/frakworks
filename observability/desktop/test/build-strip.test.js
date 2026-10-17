// The builds-in-progress strip above Analytics > Build gate (#1389):
// src/renderer/build-strip.jsx and its engine read, fetchActiveBuilds.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const { BuildStrip, buildRow, durationSecs, elapsedLabel } = require("../dist/build-strip.cjs");
const { fetchActiveBuilds } = require("../src/engine-data");

const draw = (props) => renderToStaticMarkup(React.createElement(BuildStrip, props));
const NOW = Date.parse("2026-10-06T12:00:00Z");

test("elapsed time reads m:ss, then h:mm:ss", () => {
  assert.equal(elapsedLabel(0), "0:00");
  assert.equal(elapsedLabel(221), "3:41");
  assert.equal(elapsedLabel(3725), "1:02:05");
  assert.equal(elapsedLabel(-5), "0:00");
});

test("the check timeout is read from ISO-8601", () => {
  assert.equal(durationSecs("PT3H"), 10800);
  assert.equal(durationSecs("PT1H30M20S"), 5420);
  assert.equal(durationSecs("3h"), null);
  assert.equal(durationSecs(null), null);
});

test("one bar per build, oldest first, labelled repo#n with a ticket link and its elapsed time", () => {
  const html = draw({ now: NOW, data: { timeout: "PT3H", builds: [
    { repo: "codefrak-oss/frakworks", issue: 1376, since: "2026-10-06T11:00:00Z", processInstanceId: "a" },
    { repo: "codefrak-oss/frakworks", issue: 1389, since: "2026-10-06T11:57:00Z", processInstanceId: "b" },
  ] } });
  assert.match(html, /Builds in progress \(2\)/);
  assert.equal((html.match(/class="build-row"/g) || []).length, 2);
  assert.match(html, /href="frakworks:\/\/ticket\/codefrak-oss\/frakworks\/1376"[^>]*>frakworks#1376<\/a>.*1:00:00/s);
  assert.match(html, /frakworks#1389<\/a>.*3:00/s);
  assert.ok(html.indexOf("#1376") < html.indexOf("#1389"));
});

test("the bar's width is the elapsed share of the timeout, capped at the track", () => {
  assert.equal(buildRow({ since: "2026-10-06T10:30:00Z" }, NOW, 10800).width, 50);
  assert.equal(buildRow({ since: "2026-10-06T06:00:00Z" }, NOW, 10800).width, 100);
  assert.equal(buildRow({ since: "2026-10-06T11:00:00Z" }, NOW, null).width, 100);
});

test("a build with no ticket is drawn with no link", () => {
  const r = buildRow({ repo: "o/r", since: "2026-10-06T11:59:00Z" }, NOW, 10800);
  assert.equal(r.href, null);
  assert.equal(r.label, "o/r");
  assert.doesNotMatch(draw({ now: NOW, data: { builds: [{ repo: "o/r", since: "2026-10-06T11:59:00Z" }] } }), /<a /);
});

test("no builds says so; a failed read is an error line; before the first read it waits", () => {
  assert.match(draw({ now: NOW, data: { builds: [], timeout: "PT3H" } }), /no builds running/);
  assert.match(draw({ now: NOW, data: { error: "engine unreachable" } }), /class="err".*engine unreachable/);
  assert.match(draw({ now: NOW, data: null }), /reading builds in progress/);
});

test("fetchActiveBuilds reads GET /builds/active and never rejects", async () => {
  const env = { ENGINE_URL: "http://engine.test" };
  const answer = (status, body) => async (url) => {
    assert.equal(url, "http://engine.test/builds/active");
    return { ok: status === 200, status, text: async () => body };
  };
  const ok = await fetchActiveBuilds({ env, fetch: answer(200, JSON.stringify({ builds: [{ repo: "o/r", issue: 1 }], timeout: "PT3H" })) });
  assert.deepEqual(ok, { builds: [{ repo: "o/r", issue: 1 }], timeout: "PT3H", error: null });
  assert.match((await fetchActiveBuilds({ env, fetch: answer(500, "boom") })).error, /500/);
  assert.match((await fetchActiveBuilds({ env, fetch: answer(200, "[]") })).error, /no builds list/);
});
