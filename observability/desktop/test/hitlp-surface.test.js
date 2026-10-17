// The HITLP server's surface API client (#2487, src/hitlp-surface.js): the URL
// and credential kept with a stand-in safeStorage in a 0600 file only after
// GET /tasks accepts them (a 401 stores nothing), the list, get and decision
// calls (the decision carries `channel`, an approve the shown payloadDigest,
// an ask's answer checked against responseSchema), and GET /events, whose
// `task` event (and each connect) calls back so main refetches the Needs rows.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { createHitlpSurface, storageKeeper, schemaProblem, normalizeUrl, HITLP_SURFACE_KEY } = require("../src/hitlp-surface");
// Copied verbatim from the HITLP spec (codefrak-oss/hitlp spec/examples/surface/decide.request.json).
const SPEC_DECIDE = { outcome: "approve", reason: "Checked the image tag.", payloadDigest: "sha256:9f2c1e0a", teach: { option: "always-allow", text: "deploy release * to production" } };

const BASE = "https://hitlp.test/surface/v1";
const CRED = "srf_secret_credential";
const safeStorage = {
  isEncryptionAvailable: () => true,
  encryptString: (s) => Buffer.from(`enc:${s}`),
  decryptString: (b) => { const t = b.toString(); if (!t.startsWith("enc:")) throw new Error("bad"); return t.slice(4); },
};
const tmpFile = () => path.join(fs.mkdtempSync(path.join(os.tmpdir(), "hitlp-surface-")), "hitlp-surface.bin");
const reply = (status, body) => ({ ok: status < 300, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)) });
// A fetch stand-in that records each request and answers from `route(method, url, body)`.
const stub = (route) => {
  const seen = [];
  const fetch = async (url, init = {}) => {
    const body = init.body ? JSON.parse(init.body) : undefined;
    seen.push({ url, method: init.method, auth: init.headers && init.headers.authorization, body });
    return route(init.method, url, body);
  };
  return { fetch, seen };
};
const make = (fetch, file = tmpFile(), extra = {}) => createHitlpSurface({ safeStorage, file, fetch, platform: "darwin", ...extra });

test("set verifies with GET /tasks, then keeps URL and credential encrypted in a 0600 file; status never names the credential", async () => {
  const { fetch, seen } = stub(() => reply(200, { tasks: [] }));
  const file = tmpFile();
  const s = make(fetch, file);
  assert.deepEqual(s.status(), { signedIn: false, url: null, error: null });
  assert.deepEqual(await s.set(`${BASE}/`, ` ${CRED} `), { url: BASE });
  assert.deepEqual(seen[0], { url: `${BASE}/tasks`, method: "GET", auth: `Bearer ${CRED}`, body: undefined });
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  assert.ok(!fs.readFileSync(file, "utf8").startsWith("{"), "encrypted, not plain JSON");
  assert.deepEqual(s.status(), { signedIn: true, url: BASE, error: null });
  assert.ok(!JSON.stringify(s.status()).includes(CRED));
  // read back by a fresh keeper, as after a restart
  const again = make(fetch, file);
  assert.deepEqual(again.load(), { signedIn: true, url: BASE });
  assert.equal(again.configured(), true);
  // clear removes the file
  assert.deepEqual(again.clear(), { ok: true });
  assert.equal(fs.existsSync(file), false);
  assert.equal(again.status().signedIn, false);
});

test("a 401 (or any refusal) stores nothing; a bad URL or no credential never calls out", async () => {
  const { fetch, seen } = stub(() => reply(401, { message: "unknown credential" }));
  const file = tmpFile();
  const s = make(fetch, file);
  const r = await s.set(BASE, CRED);
  assert.match(r.error, /refused the credential for GET \/tasks: unknown credential/);
  assert.equal(fs.existsSync(file), false);
  assert.equal(s.status().signedIn, false);
  assert.match((await s.set("ftp://x", CRED)).error, /surface URL/);
  assert.match((await s.set(BASE, "  ")).error, /credential/);
  assert.equal(seen.length, 1);
  // no keychain: refused before any call
  const plain = createHitlpSurface({ safeStorage: { ...safeStorage, isEncryptionAvailable: () => false }, file, fetch });
  assert.match((await plain.set(BASE, CRED)).error, /cannot encrypt/);
  assert.equal(seen.length, 1);
  assert.equal(normalizeUrl(" https://a/b// "), "https://a/b");
});

const ready = async (route) => {
  const st = stub((m, u, b) => (u === `${BASE}/tasks` && m === "GET" && !st.armed ? reply(200, []) : route(m, u, b)));
  const s = make(st.fetch);
  await s.set(BASE, CRED);
  st.armed = true;
  st.seen.length = 0;
  return { s, seen: st.seen };
};

test("list reads GET /tasks (a list or { tasks }), get one task; unset lists nothing without a call", async () => {
  const unset = stub(() => { throw new Error("must not call"); });
  assert.deepEqual(await make(unset.fetch).list(), { tasks: [] });
  const { s, seen } = await ready((m, u) => (u.endsWith("/tasks") ? reply(200, { tasks: [{ id: "t1", primitive: "ask" }, null] }) : reply(200, { id: "t/1", primitive: "approve" })));
  assert.deepEqual(await s.list(), { tasks: [{ id: "t1", primitive: "ask" }] });
  assert.deepEqual(await s.get("t/1"), { task: { id: "t/1", primitive: "approve" } });
  assert.deepEqual(seen.map((x) => [x.method, x.url, x.auth]), [["GET", `${BASE}/tasks`, `Bearer ${CRED}`], ["GET", `${BASE}/tasks/t%2F1`, `Bearer ${CRED}`]]);
  const down = await ready(() => reply(503, "busy"));
  assert.match((await down.s.list()).error, /answered 503 for GET \/tasks: busy/);
});

test("decide posts the spec's body: an approve's outcome approve or reject with the shown payloadDigest and reason, nothing else", async () => {
  const { s, seen } = await ready(() => reply(200, ""));
  assert.deepEqual(await s.decide("t1", { outcome: "rejected", reason: " too risky ", payloadDigest: "sha256:9f3c" }), { ok: true });
  assert.deepEqual(seen[0], { url: `${BASE}/tasks/t1/decision`, method: "POST", auth: `Bearer ${CRED}`,
    body: { outcome: "reject", payloadDigest: "sha256:9f3c", reason: "too risky" } });
  await s.decide("t1", { outcome: "approved", reason: "", payloadDigest: null });
  assert.deepEqual(seen[1].body, { outcome: "approve" });
  // The spec's own example decision is a body this client can send (HITLP spec/examples/surface/decide.request.json).
  assert.deepEqual(Object.keys(SPEC_DECIDE).filter((k) => k !== "teach").sort(), ["outcome", "payloadDigest", "reason"]);
  assert.ok(["approve", "reject"].includes(SPEC_DECIDE.outcome));
  assert.match((await s.decide("", { outcome: "approved" })).error, /no HITLP task id/);
  assert.match((await s.decide("t1", { outcome: "maybe" })).error, /not a HITLP outcome/);
  assert.equal(seen.length, 2);
  const gone = await ready(() => reply(409, ""));
  assert.equal((await gone.s.decide("t1", { outcome: "approved" })).error, "the task is already decided (or timed out)");
  const missing = await ready(() => reply(404, ""));
  assert.equal((await missing.s.decide("t1", { outcome: "approved" })).error, "the HITLP server has no such task");
});

test("an ask's answer is checked against the task's responseSchema before it is posted", async () => {
  const schema = { type: "string", enum: ["pg", "redis"] };
  const { s, seen } = await ready((m) => (m === "GET" ? reply(200, { id: "a1", primitive: "ask", responseSchema: schema }) : reply(200, "")));
  assert.match((await s.decide("a1", { outcome: "answered", answer: "mysql" })).error, /must be one of "pg", "redis"/);
  assert.deepEqual(seen.map((x) => x.method), ["GET"], "refused before any post");
  assert.deepEqual(await s.decide("a1", { outcome: "answered", answer: "pg" }), { ok: true });
  assert.deepEqual(seen[2].body, { answer: "pg" });
  assert.equal(schemaProblem({ type: "object", required: ["a"], properties: { a: { type: "integer" } } }, { a: 1 }), null);
  assert.match(schemaProblem({ type: "object", required: ["a"] }, {}), /needs "a"/);
  assert.match(schemaProblem({ type: "object", properties: { a: { type: "integer" } } }, { a: 1.5 }), /answer\.a must be integer/);
  assert.match(schemaProblem({ type: "boolean" }, "yes"), /must be boolean/);
  assert.equal(schemaProblem(null, "anything"), null);
});

test("subscribe holds GET /events open: each connect and each `event: task` calls back; other events do not", async () => {
  const enc = new TextEncoder();
  const chunks = [": ping\n\n", "event: task\ndata: {\"id\":\"t1\",\"state\":\"decided\"}\n\n", "event: other\ndata: x\n\nevent: ta", "sk\ndata: plain\n\n"];
  const st = stub((m, u) => {
    if (u.endsWith("/events")) {
      let i = 0;
      return { ok: true, status: 200, body: { getReader: () => ({ read: async () => (i < chunks.length ? { value: enc.encode(chunks[i++]), done: false } : new Promise(() => {})) }) } };
    }
    return reply(200, []);
  });
  const s = make(st.fetch);
  await s.set(BASE, CRED);
  const got = [];
  const stop = s.subscribe((d) => got.push(d));
  await new Promise((r) => setTimeout(r, 20));
  assert.deepEqual(got, [null, { id: "t1", state: "decided" }, "plain"]);
  const ev = st.seen.find((x) => x.url === `${BASE}/events`);
  assert.equal(ev.auth, `Bearer ${CRED}`);
  stop();
  // not set: nothing to subscribe to
  assert.equal(typeof make(st.fetch).subscribe(() => {}), "function");
});

test("a dropped stream reconnects after the backoff and calls back again", async () => {
  let connects = 0;
  const timers = [];
  const st = stub((m, u) => {
    if (u.endsWith("/events")) { connects++; return { ok: true, status: 200, body: { getReader: () => ({ read: async () => ({ done: true }) }) } }; }
    return reply(200, []);
  });
  const s = make(st.fetch, tmpFile(), { setTimer: (f, ms) => { timers.push({ f, ms }); return timers.length; }, clearTimer: () => {} });
  await s.set(BASE, CRED);
  const got = [];
  s.subscribe((d) => got.push(d));
  await new Promise((r) => setTimeout(r, 10));
  assert.equal(connects, 1);
  const retry = timers.filter((t) => t.ms === 1000).pop();
  assert.ok(retry, "a 1 s backoff is set after the drop");
  retry.f();
  await new Promise((r) => setTimeout(r, 10));
  assert.equal(connects, 2);
  assert.deepEqual(got, [null, null], "each connect refetches");
  s.unsubscribe();
});

// A Web Storage stand-in, as the web version's sessionStorage.
const memStorage = () => { const m = new Map(); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), size: () => m.size }; };

test("storageKeeper (the web version): set keeps { url, credential } under the key only after GET /tasks accepts; clear removes it; status never names the credential", async () => {
  const storage = memStorage();
  const refused = stub(() => reply(401, { message: "unknown credential" }));
  const bad = createHitlpSurface({ keeper: storageKeeper(storage), fetch: refused.fetch });
  assert.match((await bad.set(BASE, CRED)).error, /refused the credential/);
  assert.equal(storage.getItem(HITLP_SURFACE_KEY), null, "a 401 stores nothing");
  assert.equal(bad.status().signedIn, false);

  const { fetch, seen } = stub(() => reply(200, { tasks: [] }));
  const s = createHitlpSurface({ keeper: storageKeeper(storage), fetch });
  assert.deepEqual(s.load(), { signedIn: false });
  assert.deepEqual(await s.set(`${BASE}/`, CRED), { url: BASE });
  assert.deepEqual(seen[0], { url: `${BASE}/tasks`, method: "GET", auth: `Bearer ${CRED}`, body: undefined });
  assert.deepEqual(JSON.parse(storage.getItem(HITLP_SURFACE_KEY)), { url: BASE, credential: CRED });
  assert.equal(HITLP_SURFACE_KEY, "frakworks.hitlpSurface");
  assert.deepEqual(s.status(), { signedIn: true, url: BASE, error: null });
  assert.ok(!JSON.stringify(s.status()).includes(CRED));
  // a fresh instance over the same storage reads it back, as a page reload does
  const again = createHitlpSurface({ keeper: storageKeeper(storage), fetch });
  assert.deepEqual(again.load(), { signedIn: true, url: BASE });
  assert.deepEqual(await again.list(), { tasks: [] });
  assert.equal(seen[1].auth, `Bearer ${CRED}`);
  assert.deepEqual(again.clear(), { ok: true });
  assert.equal(storage.getItem(HITLP_SURFACE_KEY), null);
  assert.equal(again.status().signedIn, false);
  // a store that holds junk is said and not set
  storage.setItem(HITLP_SURFACE_KEY, "{not json");
  const junk = createHitlpSurface({ keeper: storageKeeper(storage), fetch }).load();
  assert.equal(junk.signedIn, false);
  assert.match(junk.error, /could not be read/);
});

test("a thrown TypeError in a browser is said as unreachable or CORS, naming the page's origin; main's message stays plain", async () => {
  const throwing = stub(() => { throw new TypeError("Failed to fetch"); });
  const web = createHitlpSurface({ keeper: storageKeeper(memStorage()), fetch: throwing.fetch, origin: "https://frak.example" });
  const r = await web.set(BASE, CRED);
  assert.match(r.error, /the browser could not reach https:\/\/hitlp\.test\/surface\/v1 \(Failed to fetch\)/);
  assert.match(r.error, /Access-Control-Allow-Origin for https:\/\/frak\.example/);
  assert.match(r.error, /Access-Control-Allow-Headers: authorization, content-type/);
  const main = make(throwing.fetch);
  assert.match((await main.set(BASE, CRED)).error, /^HITLP server unreachable at .*: Failed to fetch$/);
});

test("main wires the four channels, keeps the file in userData, and turns a task event into the needs frame", () => {
  const main = fs.readFileSync(path.join(__dirname, "..", "src", "main.js"), "utf8");
  for (const ch of ["hitlp:surface-set", "hitlp:surface-clear", "hitlp:surface-status", "hitlp:surface-decide"]) assert.match(main, new RegExp(`ipcMain\\.handle\\("${ch}"`));
  assert.match(main, /file: path\.join\(app\.getPath\("userData"\), HITLP_SURFACE_FILE\)/);
  assert.match(main, /webContents\.send\("engine:event", \{ type: "needs", data: \{ source: "hitlp" \} \}\)/);
  assert.match(main, /fetchNeeds\(\{ repo: await currentRepo\(\) \}\)/);
  assert.match(main, /ipcMain\.handle\("hitlp:tasks", async \(\) => \{\n\s*const r = await fetchHitlpTasks\(\{ surface: \(\) => hitlpSurface\.list\(\)/);
  const preload = fs.readFileSync(path.join(__dirname, "..", "src", "preload.js"), "utf8");
  assert.match(preload, /hitlpSurfaceStatus: \(\) => ipcRenderer\.invoke\("hitlp:surface-status"\)/);
});

test("a choice ask's plain string schema (no enum) accepts a free answer that is none of its options", () => {
  assert.equal(schemaProblem({ type: "string" }, "neither: use SQLite"), null);
  assert.match(schemaProblem({ type: "string", enum: ["pg", "redis"] }, "neither: use SQLite"), /must be one of/);
});
