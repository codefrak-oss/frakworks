"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { parseDeployment, loadDeployment } = require("../src/deployment-config");

test("parseDeployment: both keys, trimmed, the feed with a trailing slash", () => {
  const d = parseDeployment({ registry: " registry.example:5000 ", release_feed: "http://releases.example" });
  assert.equal(d.registry, "registry.example:5000");
  assert.equal(d.releaseFeed, "http://releases.example/");
  assert.deepEqual(d.errors, []);
});

test("parseDeployment: unset keys give nulls, so the callers keep their defaults", () => {
  assert.deepEqual(parseDeployment({}), { registry: null, releaseFeed: null, logsUrl: null, metricsUrl: null, errors: [] });
  assert.deepEqual(parseDeployment(null), { registry: null, releaseFeed: null, logsUrl: null, metricsUrl: null, errors: [] });
});

test("parseDeployment: a bad value is an error and ignored, never a guess", () => {
  const d = parseDeployment({ registry: "http://registry.example:5000/", release_feed: "ftp://feed" });
  assert.equal(d.registry, null);
  assert.equal(d.releaseFeed, null);
  assert.equal(d.errors.length, 2);
  assert.match(d.errors[0], /registry must be host\[:port\]/);
  assert.match(d.errors[1], /release_feed must be an http\(s\) URL/);
  assert.equal(parseDeployment({ release_feed: "http://user:pw@releases.example/" }).releaseFeed, null);
});

test("loadDeployment: a missing file is the defaults; a broken one is one error", () => {
  const enoent = () => { const e = new Error("no"); e.code = "ENOENT"; throw e; };
  assert.deepEqual(loadDeployment({ file: "/nowhere/agent-army.config.json", readFileSync: enoent }),
    { registry: null, releaseFeed: null, logsUrl: null, metricsUrl: null, errors: [] });
  const d = loadDeployment({ file: "/x/agent-army.config.json", readFileSync: () => "{ not json" });
  assert.equal(d.registry, null);
  assert.equal(d.errors.length, 1);
  const ok = loadDeployment({ file: "/x/agent-army.config.json", readFileSync: () => JSON.stringify({ registry: "r.example:5000" }) });
  assert.equal(ok.registry, "r.example:5000");
});

test("parseDeployment: logs_url and metrics_url, valid, malformed and absent", () => {
  const ok = parseDeployment({ logs_url: " http://localhost:9428 ", metrics_url: "https://vm.example/" });
  assert.equal(ok.logsUrl, "http://localhost:9428/");
  assert.equal(ok.metricsUrl, "https://vm.example/");
  assert.deepEqual(ok.errors, []);
  const bad = parseDeployment({ logs_url: "localhost:9428", metrics_url: "http://u:p@vm.example/" });
  assert.equal(bad.logsUrl, null);
  assert.equal(bad.metricsUrl, null);
  assert.match(bad.errors[0], /logs_url must be an http\(s\) URL/);
  assert.match(bad.errors[1], /metrics_url must be an http\(s\) URL/);
  const none = parseDeployment({});
  assert.equal(none.logsUrl, null);
  assert.equal(none.metricsUrl, null);
  assert.deepEqual(none.errors, []);
});
