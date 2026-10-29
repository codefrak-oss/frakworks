// The engine fetchers ride out a few seconds with no ready engine endpoint
// (client ride-out of a lease handover): getJson and
// sendJson retry once on 502/503 or a refused connection, and nothing else.
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { getJson, sendJson } = require("../src/engine-data");

const env = { engineUrl: "http://engine.test" };
const ok = (body) => ({ ok: true, status: 200, text: async () => JSON.stringify(body) });
const fail = (status) => ({ ok: false, status, text: async () => "" });
const refusedError = () => { const e = new TypeError("fetch failed"); e.cause = { code: "ECONNREFUSED" }; return e; };

// A fake fetch answering from `answers` in order (an Error is thrown), counting calls.
function fakeFetch(answers) {
  const f = async () => { const a = answers[f.calls++]; if (a instanceof Error) throw a; return a; };
  f.calls = 0;
  return f;
}

test("a 503 then a 200 succeeds on the retry", async () => {
  const fetch = fakeFetch([fail(503), ok({ a: 1 })]);
  assert.deepEqual(await getJson("/x", { env, fetch, retryDelayMs: 1 }), { value: { a: 1 } });
  assert.equal(fetch.calls, 2);
});

test("a 502 is retried once too", async () => {
  const fetch = fakeFetch([fail(502), ok({})]);
  assert.equal((await sendJson("POST", "/x", {}, { env, fetch, retryDelayMs: 1 })).ok, true);
  assert.equal(fetch.calls, 2);
});

test("503 twice fails after exactly two calls", async () => {
  const fetch = fakeFetch([fail(503), fail(503), ok({})]);
  assert.match((await getJson("/x", { env, fetch, retryDelayMs: 1 })).error, /503/);
  assert.equal(fetch.calls, 2);
});

test("a refused connection is retried once", async () => {
  const fetch = fakeFetch([refusedError(), ok({ b: 2 })]);
  assert.deepEqual(await getJson("/x", { env, fetch, retryDelayMs: 1 }), { value: { b: 2 } });
  assert.equal(fetch.calls, 2);
});

test("a 500 and other network errors are not retried", async () => {
  const f500 = fakeFetch([fail(500), ok({})]);
  assert.match((await sendJson("PUT", "/x", {}, { env, fetch: f500, retryDelayMs: 1 })).error, /500/);
  assert.equal(f500.calls, 1);
  const fOther = fakeFetch([new Error("ENOTFOUND"), ok({})]);
  assert.match((await getJson("/x", { env, fetch: fOther, retryDelayMs: 1 })).error, /unreachable/);
  assert.equal(fOther.calls, 1);
});
