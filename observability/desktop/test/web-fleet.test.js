// The web version's bridge (src/web/fleet-web.js): every member preload gives
// the renderer exists in the browser too, the desktop-only ones answer the
// web error, sign-in keeps the token only after the engine accepted it and
// sends it as the bearer, the HITLP server's surface API is set, read,
// decided and subscribed from the page with its own credential (#2489), and
// a ticket link routes in the page. Plus the sha256 shim against node:crypto,
// the grep for the engine's removed HITLP paths, and the build itself.
"use strict";
// Config > frakworks > Engine URL, as main sets it at launch (src/engine-url-setting.js).
const TEST_ENGINE_URL = "http://engine.test";
require("../src/engine-data").setEngineUrl(TEST_ENGINE_URL);

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");

const { createWebFleet, OMITTED, TOKEN_KEY, KEEP_KEY, parseTicketHash, hashOfAppLink, memoryStorage } = require("../src/web/fleet-web");
const { HITLP_SURFACE_KEY } = require("../src/hitlp-surface");
const HITLP_TASKS = JSON.parse(fs.readFileSync(path.join(__dirname, "fixture-hitlp-tasks.json"), "utf8"));

const ROOT = path.join(__dirname, "..");
const preload = fs.readFileSync(path.join(ROOT, "src", "preload.js"), "utf8");
const block = (name, next) => preload.slice(preload.indexOf(`exposeInMainWorld("${name}"`), next ? preload.indexOf(`exposeInMainWorld("${next}"`) : undefined);
const keysOf = (text) => [...text.matchAll(/^ {2}(\w+):/gm)].map((m) => m[1]);

// A stream that opens nothing: the bridge's own logic only.
const quietStream = () => ({ setSelected() {}, snapshotted: () => ({ ok: true }), streamState: () => ({ state: "down" }), subscribe: () => ({ ok: true }), unsubscribe: () => ({ ok: true }) });

// globalThis.fetch for one test: `answer(url, init)` returns { status, body }; every call is recorded.
function withFetch(answer, fn) {
  const calls = [];
  const was = globalThis.fetch;
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), headers: init.headers || {}, body: init.body });
    const { status = 200, body = {} } = answer(String(url), init) || {};
    return { ok: status >= 200 && status < 300, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)) };
  };
  return Promise.resolve(fn(calls)).finally(() => { globalThis.fetch = was; });
}

test("every window.fleet member preload exposes exists in the web bridge, and the platform says web", () => {
  const { fleet: f } = createWebFleet({ stream: quietStream() });
  assert.equal("decideHitlp" in f, false, "the engine's decision path is gone (#2485)");
  const { fleet } = createWebFleet({ stream: quietStream() });
  const keys = keysOf(block("fleet", "repo"));
  assert.ok(keys.length > 50, `preload's fleet members were read (${keys.length})`);
  assert.deepEqual(keys.filter((k) => !(k in fleet)), []);
  assert.equal(fleet.platform, "web");
});

test("window.repo and window.firstMate have every member preload's has (#100104)", () => {
  const bridge = createWebFleet({ stream: quietStream() });
  assert.deepEqual(keysOf(block("repo", "firstMate")).filter((k) => !(k in bridge.repo)), []);
  const fmKeys = keysOf(block("firstMate", "app"));
  assert.ok(fmKeys.includes("send") && fmKeys.includes("onStream"), `preload's firstMate members were read (${fmKeys.length})`);
  assert.deepEqual(fmKeys.filter((k) => !(k in bridge.firstMate)), []);
  assert.match(fs.readFileSync(path.join(ROOT, "src", "web", "install.js"), "utf8"), /window\.firstMate = bridge\.firstMate/);
});

test("window.firstMate before sign-in: list answers null, the desktop-only members the web error, font the fallback", async () => {
  const { firstMate } = createWebFleet({ stream: quietStream() });
  assert.equal(await firstMate.list(), null);
  for (const k of ["handoff", "openInIterm", "restoreSaved"]) assert.deepEqual(await firstMate[k](), { error: OMITTED }, k);
  assert.equal((await firstMate.font()).family, null);
  assert.equal(typeof firstMate.onUpdate(() => {}), "function");
});

test("the desktop-only members answer the web error, and their listeners unsubscribe", async () => {
  const { fleet } = createWebFleet({ stream: quietStream() });
  for (const k of ["fetchTranscript", "openTranscriptTail", "doctorTranscript", "hitlpCall", "envCheck", "envRemove",
    "savedChats", "savedChat", "claudeSessions", "grafanaDashboards", "grafanaProbe", "teachTokenSet", "teachTokenClear", "teachJudge"]) {
    assert.deepEqual(await fleet[k](), { error: OMITTED }, k);
  }
  assert.deepEqual(await fleet.teachTokenStatus(), { signedIn: false, login: null, name: null, error: OMITTED }, "teach buttons stay disabled");
  for (const k of ["onTranscriptTail", "onSavedChatsChanged", "onClaudeSessionsChanged"]) {
    assert.equal(typeof fleet[k](() => {}), "function", k);
  }
});

test("sign-in keeps the token only after GET /whoami accepts it, then every read carries it as the bearer", async () => {
  const storage = memoryStorage();
  const { fleet, signedIn } = createWebFleet({ storage, stream: quietStream() });
  await withFetch((url, init) => {
    if (url.endsWith("/whoami")) {
      return init.headers.authorization === "Bearer frk_good" ? { body: { login: "octo", tokenId: "t1", name: "laptop" } } : { status: 401, body: { error: "no such token" } };
    }
    return { body: [] };
  }, async (calls) => {
    const bad = await fleet.signIn("frk_bad");
    assert.match(bad.error, /401/);
    assert.equal(storage.getItem(TOKEN_KEY), null);
    assert.equal(signedIn(), false);

    assert.deepEqual(await fleet.signIn("  frk_good \n"), { login: "octo", name: "laptop" });
    assert.equal(storage.getItem(TOKEN_KEY), "frk_good");
    assert.deepEqual(await fleet.signInStatus(), { signedIn: true, login: "octo", name: "laptop", error: null });
    assert.equal(await fleet.login(), "octo");

    calls.length = 0;
    await fleet.activeBuilds();
    assert.ok(calls.length >= 1);
    assert.ok(calls.every((c) => c.headers.authorization === "Bearer frk_good"), JSON.stringify(calls));

    assert.deepEqual(await fleet.signOut(), { ok: true });
    assert.equal(storage.getItem(TOKEN_KEY), null);
    assert.deepEqual(await fleet.signInStatus(), { signedIn: false, login: null, name: null, error: null });
    calls.length = 0;
    await fleet.activeBuilds();
    assert.ok(calls.every((c) => !c.headers.authorization));
  });
});

test("Keep me signed in (README, \"iPad\"): a kept token survives a relaunch with a fresh sessionStorage; an unkept one does not; Sign out clears both", async () => {
  const prefs = memoryStorage();
  const persist = memoryStorage();
  const whoami = (url, init) => (url.endsWith("/whoami") ? { body: { login: "octo", tokenId: "t1", name: "ipad" } } : { body: [] });
  await withFetch(whoami, async () => {
    const first = createWebFleet({ storage: memoryStorage(), prefs, persist, stream: quietStream() });
    await first.fleet.signIn("frk_kept", { keep: true });
    assert.equal(persist.getItem(TOKEN_KEY), "frk_kept");
    assert.equal(prefs.getItem(KEEP_KEY), "1");
    const relaunch = createWebFleet({ storage: memoryStorage(), prefs, persist, stream: quietStream() });
    assert.equal(relaunch.signedIn(), true, "a home-screen launch comes back signed in");
    assert.equal((await relaunch.fleet.signInStatus()).login, "octo");

    await relaunch.fleet.signOut();
    assert.equal(persist.getItem(TOKEN_KEY), null);
    assert.equal(prefs.getItem(KEEP_KEY), null);
    assert.equal(createWebFleet({ storage: memoryStorage(), prefs, persist, stream: quietStream() }).signedIn(), false);

    const session = memoryStorage();
    const tab = createWebFleet({ storage: session, prefs, persist, stream: quietStream() });
    await tab.fleet.signIn("frk_tab");
    assert.equal(session.getItem(TOKEN_KEY), "frk_tab");
    assert.equal(persist.getItem(TOKEN_KEY), null, "without Keep me signed in nothing reaches localStorage");
    assert.equal(createWebFleet({ storage: memoryStorage(), prefs, persist, stream: quietStream() }).signedIn(), false);
  });
});

const SURFACE = "https://hitlp.example/surface/v1";
const CRED = "srf_web_credential";
// `calls` as withFetch records them, split by host.
const toSurface = (calls) => calls.filter((c) => c.url.startsWith(SURFACE));
const toEngine = (calls) => calls.filter((c) => !c.url.startsWith(SURFACE));

test("the HITLP server (#2489): set verifies with GET /tasks and keeps the pair in sessionStorage; status answers the URL alone; a 401 stores nothing", async () => {
  const storage = memoryStorage();
  const { fleet } = createWebFleet({ storage, stream: quietStream() });
  storage.setItem(TOKEN_KEY, "frk_good");
  await withFetch((url, init) => {
    if (url === `${SURFACE}/tasks`) return init.headers.authorization === `Bearer ${CRED}` ? { body: { tasks: [] } } : { status: 401, body: { message: "unknown credential" } };
    return { body: [] };
  }, async (calls) => {
    assert.deepEqual(await fleet.hitlpSurfaceStatus(), { signedIn: false, url: null, error: null });
    assert.match((await fleet.hitlpSurfaceSet(SURFACE, "srf_wrong")).error, /refused the credential/);
    assert.equal(storage.getItem(HITLP_SURFACE_KEY), null, "a 401 stores nothing");
    const pushes = [];
    fleet.onEngineEvent((ev) => pushes.push(ev));
    assert.deepEqual(await fleet.hitlpSurfaceSet(`${SURFACE}/`, ` ${CRED} `), { url: SURFACE });
    assert.deepEqual(toSurface(calls).map((c) => [c.url, c.headers.authorization]).slice(0, 2),
      [[`${SURFACE}/tasks`, "Bearer srf_wrong"], [`${SURFACE}/tasks`, `Bearer ${CRED}`]]);
    assert.ok(toSurface(calls).every((c) => c.headers.authorization !== "Bearer frk_good"), "the engine token never goes to the HITLP server");
    assert.deepEqual(JSON.parse(storage.getItem(HITLP_SURFACE_KEY)), { url: SURFACE, credential: CRED });
    const st = await fleet.hitlpSurfaceStatus();
    assert.deepEqual(st, { signedIn: true, url: SURFACE, error: null });
    assert.ok(!JSON.stringify(st).includes(CRED));
    assert.deepEqual(pushes, [{ type: "needs", data: { source: "hitlp" } }], "a set refetches the Needs rows");
    assert.deepEqual(await fleet.hitlpSurfaceClear(), { ok: true });
    assert.equal(storage.getItem(HITLP_SURFACE_KEY), null);
    assert.equal((await fleet.hitlpSurfaceStatus()).signedIn, false);
    assert.equal(pushes.length, 2);
  });
});

test("hitlpTasks carries the HITLP server's tasks, needsYou the engine's rows only; hitlpSurfaceDecide posts the decision with the surface credential, never the engine token", async () => {
  const storage = memoryStorage();
  storage.setItem(TOKEN_KEY, "frk_good");
  storage.setItem(HITLP_SURFACE_KEY, JSON.stringify({ url: SURFACE, credential: CRED }));
  const { fleet } = createWebFleet({ storage, stream: quietStream() });
  await withFetch((url, init) => {
    if (url === `${SURFACE}/tasks`) return { body: HITLP_TASKS };
    if (url === `${SURFACE}/tasks/task-ask-1`) return { body: HITLP_TASKS.find((t) => t.id === "task-ask-1") };
    if (url.endsWith("/decision")) return { body: "" };
    if (url.includes("/needs")) return { body: [{ kind: "question", intake: "i1", repo: "acme/app", issue: 5, title: "Which?", detail: "", since: 1, deadline: null, position: "" }] };
    return { body: [] };
  }, async (calls) => {
    assert.deepEqual(await fleet.hitlpSurfaceStatus(), { signedIn: true, url: SURFACE, error: null }, "read back from sessionStorage at creation");
    const r = await fleet.needsYou();
    assert.equal(r.error, null);
    assert.ok(r.rows.length > 0 && r.rows.every((row) => row.source !== "hitlp"), "Needs you is the engine's rows only");
    const h = await fleet.hitlpTasks();
    assert.equal(h.error, null);
    assert.equal(h.host, new URL(SURFACE).host);
    const open = HITLP_TASKS.filter((t) => ["working", "input_required"].includes(t.status));
    assert.deepEqual(h.open.map((row) => row.actionId).sort(), open.map((t) => t.id).sort(), "the open tasks");
    assert.equal(h.closed.length, HITLP_TASKS.length - open.length, "the decided ones are Closed's");
    assert.equal(toSurface(calls).find((c) => c.url === `${SURFACE}/tasks`).headers.authorization, `Bearer ${CRED}`);
    assert.ok(toEngine(calls).every((c) => c.headers.authorization === "Bearer frk_good"), "the engine's reads carry the engine token");
    // one task by id (#100070): its row, read with the surface credential, never the credential back
    calls.length = 0;
    const one = await fleet.hitlpSurfaceGet("task-ask-1");
    assert.equal(one.error, null);
    assert.equal(one.row.actionId, "task-ask-1");
    assert.equal(toSurface(calls).find((c) => c.url === `${SURFACE}/tasks/task-ask-1`).headers.authorization, `Bearer ${CRED}`);
    assert.doesNotMatch(JSON.stringify(one), new RegExp(CRED));
    calls.length = 0;
    assert.deepEqual(await fleet.hitlpSurfaceDecide("task-ask-1", "answered", "pg", null, null), { ok: true });
    const post = toSurface(calls).find((c) => c.url === `${SURFACE}/tasks/task-ask-1/decision`);
    assert.ok(post, "the decision is posted to the HITLP server");
    assert.equal(post.headers.authorization, `Bearer ${CRED}`);
    assert.deepEqual(JSON.parse(post.body), { answer: "pg" });
    assert.equal(toEngine(calls).length, 0, "no engine request for a decision");
    assert.ok(calls.every((c) => c.headers.authorization !== "Bearer frk_good"));
    assert.deepEqual(await fleet.hitlpSurfaceDecide("task-ask-1", "rejected", undefined, " nope ", "sha256:ab"), { ok: true });
    assert.deepEqual(JSON.parse(toSurface(calls).pop().body), { outcome: "reject", payloadDigest: "sha256:ab", reason: "nope" });
    // sign-out takes the HITLP server's credential with the tab's identity
    assert.deepEqual(await fleet.signOut(), { ok: true });
    assert.equal(storage.getItem(HITLP_SURFACE_KEY), null);
    assert.deepEqual(await fleet.hitlpSurfaceStatus(), { signedIn: false, url: null, error: null });
    assert.match((await fleet.hitlpSurfaceDecide("task-ask-1", "approved")).error, /no HITLP server set/);
  });
});

test("a `task` frame on the HITLP server's GET /events fires the needs engine event in the page", async () => {
  const storage = memoryStorage();
  const enc = new TextEncoder();
  const chunks = ["event: task\ndata: {\"id\":\"t1\",\"state\":\"decided\"}\n\n"];
  let i = 0;
  const was = globalThis.fetch;
  const seen = [];
  globalThis.fetch = async (url, init = {}) => {
    seen.push({ url: String(url), auth: init.headers && init.headers.authorization });
    if (String(url) === `${SURFACE}/events`) {
      return { ok: true, status: 200, body: { getReader: () => ({ read: async () => (i < chunks.length ? { value: enc.encode(chunks[i++]), done: false } : new Promise(() => {})) }) } };
    }
    return { ok: true, status: 200, text: async () => "[]" };
  };
  try {
    const { fleet } = createWebFleet({ storage, stream: quietStream() });
    const pushes = [];
    fleet.onEngineEvent((ev) => pushes.push(ev));
    assert.deepEqual(await fleet.hitlpSurfaceSet(SURFACE, CRED), { url: SURFACE });
    await new Promise((r) => setTimeout(r, 30));
    const ev = seen.find((c) => c.url === `${SURFACE}/events`);
    assert.ok(ev, "GET /events is held open once set");
    assert.equal(ev.auth, `Bearer ${CRED}`);
    // the set, the connect and the task frame: each one refetch
    assert.ok(pushes.length >= 3, JSON.stringify(pushes));
    assert.ok(pushes.every((p) => p.type === "needs" && p.data.source === "hitlp"));
    await fleet.hitlpSurfaceClear();
  } finally { globalThis.fetch = was; }
});

test("no file under src/ names the engine's removed HITLP paths", () => {
  const files = [];
  const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, e.name); if (e.isDirectory()) walk(p); else files.push(p); } };
  walk(path.join(ROOT, "src"));
  const bad = /settings\/hitlp|hitlp\/rules|hitlp\/tasks\/.*\/decision|decideHitlp|mcp\/hitlp/;
  const hits = files.filter((f) => bad.test(fs.readFileSync(f, "utf8"))).map((f) => path.relative(ROOT, f));
  assert.deepEqual(hits, []);
});

test("an empty token is refused before any request", async () => {
  const { fleet } = createWebFleet({ stream: quietStream() });
  await withFetch(() => ({ body: {} }), async (calls) => {
    assert.deepEqual(await fleet.signIn("   "), { error: "no token to sign in with" });
    assert.equal(calls.length, 0);
  });
});

test("the repo selection is kept in prefs and refused for a repo the engine does not list", async () => {
  const prefs = memoryStorage();
  const bridge = createWebFleet({ prefs, stream: quietStream() });
  const heard = [];
  bridge.repo.onChanged((c) => heard.push(c));
  await withFetch((url) => (url.includes("/repos") ? { body: [{ fullName: "acme/app" }] } : { body: [] }), async () => {
    assert.deepEqual(await bridge.repo.set("acme/nope"), { ok: false, error: "acme/nope is not a target repo" });
    assert.deepEqual(await bridge.repo.set("acme/app"), { ok: true, repo: "acme/app", title: "acme/app" });
    assert.equal(prefs.getItem("frakworks.repo"), "acme/app");
    assert.deepEqual(heard, [{ repo: "acme/app", title: "acme/app" }]);
    const got = await bridge.repo.get();
    assert.equal(got.repo, "acme/app");
    assert.deepEqual(got.repos, ["acme/app"]);
    assert.deepEqual(await bridge.repo.set(null), { ok: true, repo: null, title: "All repos" });
    assert.equal(prefs.getItem("frakworks.repo"), null);
  });
});

test("a frakworks:// ticket link becomes the page's hash, and the hash is the link the renderer takes", async () => {
  assert.equal(hashOfAppLink("frakworks://ticket/acme/app/12"), "#/ticket/acme/app/12");
  assert.equal(hashOfAppLink("https://example.com"), null);
  assert.deepEqual(parseTicketHash("#/ticket/acme/app/12"), { repo: "acme/app", number: 12 });
  assert.equal(parseTicketHash("#/ticket/acme/app/x"), null);
  const replaced = [];
  const win = { location: { hash: "#/ticket/acme/app/7", pathname: "/web/", search: "" }, history: { replaceState: (...a) => replaced.push(a) } };
  const { app } = createWebFleet({ win, stream: quietStream() });
  assert.deepEqual(await app.takeLink(), { repo: "acme/app", number: 7 });
  assert.deepEqual(replaced, [[null, "", "/web/"]]);
});

test("the node:crypto shim's sha256 matches Node's", () => {
  const shim = require("../src/web/node-shims/crypto");
  for (const s of ["", "abc", "x".repeat(1000), "2026-10-10T00:00:00Z\nhéllo ✓"]) {
    assert.equal(shim.createHash("sha256").update(s).digest("hex"), crypto.createHash("sha256").update(s).digest("hex"));
  }
});

test("npm run build:web bundles the shared renderer with no require left in it", () => {
  execFileSync(process.execPath, [path.join(ROOT, "src", "web", "build.js")], { cwd: ROOT, stdio: "pipe" });
  const out = path.join(ROOT, "dist-web");
  for (const f of ["index.html", "web.js", "web.css", "styles.css", "web-overrides.css"]) assert.ok(fs.existsSync(path.join(out, f)), f);
  const js = fs.readFileSync(path.join(out, "web.js"), "utf8");
  assert.doesNotMatch(js, /\brequire\(/);
  assert.match(fs.readFileSync(path.join(out, "index.html"), "utf8"), /src="\.\/web\.js"/);
});

test("ciRuns reaches GET /repos/{owner}/{name}/ci/runs in the page as in the desktop: the ticket detail's main-build strip (#100090)", async () => {
  const { fleet } = createWebFleet({ storage: memoryStorage(), stream: quietStream() });
  const run = { id: 1, area: "images", trigger: "main", headSha: "abc", status: "in_progress", url: "u" };
  await withFetch((url) => (url.includes("/ci/runs") ? { body: { repo: "o/r", runs: [run], summary: null } } : { body: {} }), async (calls) => {
    const r = await fleet.ciRuns("o/r", 50, null, null);
    assert.deepEqual(r.runs, [run]);
    assert.ok(calls.some((c) => /\/repos\/o\/r\/ci\/runs\?limit=50$/.test(c.url)), JSON.stringify(calls.map((c) => c.url)));
  });
  assert.match(preload, /ciRuns: \(repo, limit, workflow, branch\) => ipcRenderer\.invoke\("engine:ci-runs"/);
  assert.match(fs.readFileSync(path.join(ROOT, "src", "main.js"), "utf8"), /ipcMain\.handle\("engine:ci-runs"/);
});
