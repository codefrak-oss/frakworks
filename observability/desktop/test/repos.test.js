// Config > Repos (#567): src/engine-data.js
// fetchRepos, fetchRepoSetup, repoAction and the trusted logins against a
// stand-in fetch, and repos.jsx drawing the result. Shapes are the engine's
// TargetRepo, SetupReport and AccessView (engine/src/main/kotlin/com/codefrak/engine/repos).
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { fetchRepos, fetchRepoSetup, fetchDoctorBugs, repoAction, fetchEngineAccess, engineAccessAction } = require("../src/engine-data");
const { HealthCheckOverlay, healthSteps, healthTitle, STREAM_DOWN } = require("../dist/update-overlay.cjs");
const { Repos, HealthCheckButton, SetupChecklist, EngineAccessPanel, setupChecklist, parsePaths, webhookWarning, scanEstimate,
  applySetupFrame,
  doctorNumbers, loadDoctorNumbers, engineDefectsHolder, doctorSince, DOCTOR_DAYS, checkAction, saveCheck,
  EditRepoDialog, EDIT_TABS, editValues, pageAction, savePage, scanCost,
  GITHUB_HOURLY_LIMIT, WARN_PCT, DANGER_PCT } = require("../dist/repos.cjs");
const { WebhookInstructions } = require("../dist/repos.cjs");

const env = { ENGINE_URL: "http://engine.test:8090/" };

const REPOS = [
  { fullName: "codefrak-oss/frakworks", addedAt: "2026-09-22T10:00:00Z", ciCheck: "engine", ciPaths: ["engine/"], hasToken: true, tokenSetAt: "2026-09-27T09:00:00Z" },
  { fullName: "acme/widgets", addedAt: "2026-09-26T10:00:00Z", ciCheck: null, ciPaths: [], hasToken: false, tokenSetAt: null },
];
const SETUP_OK = {
  repo: "codefrak-oss/frakworks", hasToken: true, readable: true, push: true, missingLabels: [],
  defaultBranch: { name: "main", protected: true, requiresReviews: false, forbidsSquash: false },
  ciCheck: { name: "engine", ranOnDefaultBranch: true, commitsLooked: 30 },
  webhook: { status: "present", hooks: [{ events: ["*"], active: true }] },
};
const SETUP_TODO = {
  repo: "acme/widgets", hasToken: true, readable: true, push: true, missingLabels: ["authorized", "intake"],
  defaultBranch: { name: "main", protected: true, requiresReviews: true, forbidsSquash: null },
  ciCheck: null, webhook: { status: "unknown", hooks: null },
};
const flow = (outcome, ended, instance = "i") => ({ outcome, ended, instance });
const BOARD = { issues: [
  { repo: "codefrak-oss/frakworks", number: 1, flow: flow("started", false) },
  { repo: "Codefrak-OSS/frakworks", number: 2, flow: flow("started", false) },
  { repo: "codefrak-oss/frakworks", number: 3, flow: flow("started", true) },
  { repo: "codefrak-oss/frakworks", number: 4, flow: flow("refused", false, null) },
  { repo: "codefrak-oss/frakworks", number: 6, flow: flow(null, false) },
  { repo: "codefrak-oss/frakworks", number: 7, flow: flow("deferred", false, null) },
  { repo: "codefrak-oss/frakworks", number: 5, flow: null },
], failures: [] };

/**
 * A stand-in fetch answering by method and path: { "GET /repos": [status, body] | Error }.
 * Records every call as { method, url, body }.
 */
const routes = (table, calls = []) => async (url, init = {}) => {
  const u = new URL(url);
  const method = init.method || "GET";
  calls.push({ method, url, body: init.body ? JSON.parse(init.body) : undefined });
  const r = table[`${method} ${u.pathname}`];
  if (!r) return { ok: false, status: 404, text: async () => "" };
  if (r instanceof Error) throw r;
  const [status, body] = r;
  return { ok: status >= 200 && status < 300, status, text: async () => (body === undefined ? "" : typeof body === "string" ? body : JSON.stringify(body)) };
};

// ---- fetchRepos ---------------------------------------------------------------

test("fetchRepos lists GET /repos with each row's running count from the board, and no setup check", async () => {
  const calls = [];
  const r = await fetchRepos({ env, fetch: routes({
    "GET /repos": [200, REPOS], "GET /issues": [200, BOARD],
    "GET /repos/codefrak-oss/frakworks/setup": [200, SETUP_OK], "GET /repos/acme/widgets/setup": [502, { message: "GitHub: boom" }],
  }, calls) });
  assert.equal(r.error, null);
  assert.equal(r.runningError, null);
  assert.deepEqual(r.repos.map((x) => x.fullName), ["codefrak-oss/frakworks", "acme/widgets"]);
  assert.equal(r.repos[0].running, 3, "two started flows and an intake (outcome null), unended, the repo's case ignored; deferred and refused have no instance");
  assert.equal(r.repos[1].running, 0);
  assert.deepEqual(r.repos.map((x) => [x.setup, x.setupError]), [[null, null], [null, null]], "the setup arrives on the stream");
  assert.deepEqual(r.repos[0].ciPaths, ["engine/"]);
  assert.ok(calls.every((c) => c.method === "GET" && c.url.startsWith("http://engine.test:8090/")));
  assert.ok(!calls.some((c) => c.url.endsWith("/setup")), "no GET …/setup");
});

test("fetchRepos reads each row's securityReview, a row without one (an older engine) as on", async () => {
  const rows = [{ ...REPOS[0], securityReview: false }, REPOS[1]];
  const r = await fetchRepos({ env, fetch: routes({ "GET /repos": [200, rows], "GET /issues": [200, BOARD] }) });
  assert.equal(r.error, null);
  assert.deepEqual(r.repos.map((x) => x.securityReview), [false, true]);
});

test("fetchRepos: a failed board still lists the repos, with no counts and runningError said", async () => {
  const r = await fetchRepos({ env, fetch: routes({
    "GET /repos": [200, [REPOS[1]]], "GET /issues": [500, "down"], "GET /repos/acme/widgets/setup": [200, SETUP_TODO],
  }) });
  assert.equal(r.error, null);
  assert.equal(r.repos[0].running, null);
  assert.match(r.runningError, /500/);
});

test("fetchRepos: a failed or malformed /repos is the error; it never rejects", async () => {
  for (const [a, expect] of [
    [[503, "down"], /engine answered 503 for \/repos/],
    [[200, { nope: 1 }], /not a list/],
    [new Error("ECONNREFUSED"), /unreachable.*ECONNREFUSED/],
  ]) {
    const r = await fetchRepos({ env, fetch: routes({ "GET /repos": a, "GET /issues": [200, BOARD] }) });
    assert.match(r.error, expect);
    assert.deepEqual(r.repos, []);
  }
});

test("fetchRepoSetup refuses a malformed repo before any request, and says a 404 in words", async () => {
  const calls = [];
  const bad = await fetchRepoSetup("../etc", { env, fetch: routes({}, calls) });
  assert.match(bad.error, /not an owner\/name repo/);
  assert.equal(calls.length, 0);
  const gone = await fetchRepoSetup("acme/gone", { env, fetch: routes({}) });
  assert.equal(gone.error, "acme/gone is not a target repo");
});

test("fetchEngineAccess reads GET /access, the trusted logins", async () => {
  const calls = [];
  const r = await fetchEngineAccess({ env, fetch: routes({
    "GET /access": [200, { logins: [{ login: "alice" }], history: [] }],
  }, calls) });
  assert.deepEqual(r.access.logins, [{ login: "alice" }]);
  assert.equal(calls[0].url, "http://engine.test:8090/access");
  const bad = await fetchEngineAccess({ env, fetch: routes({ "GET /access": [200, {}] }) });
  assert.match(bad.error, /no `logins` list/);
});

test("add and remove a trusted login as the signed-in login, with no kind; the login is checked first", async () => {
  const calls = [];
  const fetch = routes({ "POST /access": [201], "DELETE /access/bob": [204] }, calls);
  assert.equal((await engineAccessAction({ op: "add", login: "bob" }, "alice", { env, fetch })).ok, true);
  assert.deepEqual(calls[0].body, { login: "bob" });
  assert.equal((await engineAccessAction({ op: "remove", login: "bob" }, "alice", { env, fetch })).ok, true);
  assert.equal(calls[1].url, "http://engine.test:8090/access/bob");
  calls.length = 0;
  assert.match((await engineAccessAction({ op: "grant", login: "bob" }, "alice", { env, fetch })).error, /unknown engine access op/);
  assert.match((await engineAccessAction({ op: "add", login: "a/b" }, "alice", { env, fetch })).error, /GitHub login/);
  assert.match((await engineAccessAction({ op: "remove", login: "bob" }, "", { env, fetch })).error, /no GitHub login to remove as/);
  assert.equal(calls.length, 0);
});

// ---- repoAction -------------------------------------------------------------

test("add: POST /repos, then the setup check, and no access request", async () => {
  const calls = [];
  const r = await repoAction({ op: "add", repo: "acme/widgets" }, "alice", { env, fetch: routes({
    "POST /repos": [201], "GET /repos/acme/widgets/setup": [200, SETUP_TODO],
  }, calls) });
  assert.equal(r.ok, true);
  assert.deepEqual(r.setup, SETUP_TODO);
  assert.deepEqual(calls[0], { method: "POST", url: "http://engine.test:8090/repos", body: { repo: "acme/widgets" } });
  assert.equal(calls.filter((c) => c.url.includes("/access")).length, 0);
  assert.equal(calls.at(-1).url, "http://engine.test:8090/repos/acme/widgets/setup");
});

test("add: the engine's refusal is said", async () => {
  const refused = await repoAction({ op: "add", repo: "acme/widgets" }, "alice", { env, fetch: routes({ "POST /repos": [400, { message: "repo is owner/name" }] }) });
  assert.match(refused.error, /400 for POST \/repos: repo is owner\/name/);
});

test("add with a token: PUT …/token right after POST /repos; a refused token stops there", async () => {
  const calls = [];
  const r = await repoAction({ op: "add", repo: "acme/widgets", token: " github_pat_x " }, "alice", { env, fetch: routes({
    "POST /repos": [201], "PUT /repos/acme/widgets/token": [204], "GET /repos/acme/widgets/setup": [200, SETUP_OK],
  }, calls) });
  assert.equal(r.ok, true);
  assert.deepEqual(calls.slice(0, 2).map((c) => [c.method, c.url, c.body]), [
    ["POST", "http://engine.test:8090/repos", { repo: "acme/widgets" }],
    ["PUT", "http://engine.test:8090/repos/acme/widgets/token", { token: "github_pat_x" }],
  ]);
  const bad = [];
  const refused = await repoAction({ op: "add", repo: "acme/widgets", token: "github_pat_x" }, "alice", { env, fetch: routes({
    "POST /repos": [201], "PUT /repos/acme/widgets/token": [400, { message: "the token cannot read acme/widgets; nothing was saved" }],
  }, bad) });
  assert.match(refused.error, /added acme\/widgets, but its token was refused: .*cannot read acme\/widgets/);
  assert.equal(bad.length, 2, "no setup check after a refused token");
  const spaced = [];
  assert.match((await repoAction({ op: "add", repo: "acme/widgets", token: "two words" }, "alice", { env, fetch: routes({}, spaced) })).error, /whitespace/);
  assert.equal(spaced.length, 0, "refused before any request");
});

test("token: PUT …/token with the trimmed token; clear-token: DELETE …/token; bad tokens are refused first", async () => {
  const calls = [];
  const r = await repoAction({ op: "token", repo: "acme/widgets", token: " github_pat_x\n" }, "alice",
    { env, fetch: routes({ "PUT /repos/acme/widgets/token": [204] }, calls) });
  assert.equal(r.ok, true);
  assert.deepEqual(calls[0], { method: "PUT", url: "http://engine.test:8090/repos/acme/widgets/token", body: { token: "github_pat_x" } });
  const sealing = await repoAction({ op: "token", repo: "acme/widgets", token: "github_pat_x" }, "alice",
    { env, fetch: routes({ "PUT /repos/acme/widgets/token": [503, { message: "ENGINE_SECRETS_KEY is not set" }] }) });
  assert.match(sealing.error, /503.*cannot seal a token/);
  const gh = await repoAction({ op: "token", repo: "acme/widgets", token: "github_pat_x" }, "alice",
    { env, fetch: routes({ "PUT /repos/acme/widgets/token": [400, { message: "the token can read acme/widgets but not push to it; nothing was saved" }] }) });
  assert.match(gh.error, /not push to it/);
  const none = [];
  for (const t of ["", "   ", "a b", "x".repeat(401), 7]) {
    assert.ok((await repoAction({ op: "token", repo: "acme/widgets", token: t }, "alice", { env, fetch: routes({}, none) })).error, JSON.stringify(t));
  }
  assert.equal(none.length, 0);
  const cleared = [];
  assert.equal((await repoAction({ op: "clear-token", repo: "acme/widgets" }, "alice", { env, fetch: routes({ "DELETE /repos/acme/widgets/token": [204] }, cleared) })).ok, true);
  assert.deepEqual([cleared[0].method, cleared[0].url], ["DELETE", "http://engine.test:8090/repos/acme/widgets/token"]);
});

test("remove: DELETE /repos/<o>/<n>; a 409 is the engine's message naming the running flows, in full", async () => {
  const calls = [];
  const ok = await repoAction({ op: "remove", repo: "acme/widgets" }, "alice", { env, fetch: routes({ "DELETE /repos/acme/widgets": [204] }, calls) });
  assert.equal(ok.ok, true);
  assert.equal(calls[0].method, "DELETE");
  const many = Array.from({ length: 12 }, (_, i) => `chore #${100 + i}`).join(", ");
  const message = `acme/widgets has running flows: ${many}; cancel or finish them first`;
  const flows = [{ processKey: "chore", issue: 100, instanceId: "x" }];
  const r = await repoAction({ op: "remove", repo: "acme/widgets" }, "alice", { env, fetch: routes({ "DELETE /repos/acme/widgets": [409, { error: message, flows }] }) });
  assert.equal(r.error, message, "not cut at 200 characters");
  assert.deepEqual(r.flows, flows);
  const gone = await repoAction({ op: "remove", repo: "acme/widgets" }, "alice", { env, fetch: routes({}) });
  assert.match(gone.error, /404/);
});

test("check: PATCH with ciCheck and ciPaths; empty check is null; bad prefixes are refused first", async () => {
  const calls = [];
  const row = { ...REPOS[1], ciCheck: "build", ciPaths: ["src/"] };
  const r = await repoAction({ op: "check", repo: "acme/widgets", ciCheck: " build ", ciPaths: ["src/"] }, "alice",
    { env, fetch: routes({ "PATCH /repos/acme/widgets": [200, row] }, calls) });
  assert.deepEqual(r.repo, row);
  assert.deepEqual(calls[0], { method: "PATCH", url: "http://engine.test:8090/repos/acme/widgets", body: { ciCheck: "build", ciPaths: ["src/"] } });
  calls.length = 0;
  await repoAction({ op: "check", repo: "acme/widgets", ciCheck: "", ciPaths: [] }, "alice", { env, fetch: routes({ "PATCH /repos/acme/widgets": [200, REPOS[1]] }, calls) });
  assert.deepEqual(calls[0].body, { ciCheck: null, ciPaths: [] });
  for (const bad of [{ ciPaths: ["a,b"] }, { ciPaths: [" "] }, { ciPaths: "src/" }, { ciCheck: 3 }, {}]) {
    const c = [];
    const x = await repoAction({ op: "check", repo: "acme/widgets", ...bad }, "alice", { env, fetch: routes({}, c) });
    assert.ok(x.error, JSON.stringify(bad));
    assert.equal(c.length, 0);
  }
});

test("security-review: PATCH with only securityReview; anything but a boolean is refused first", async () => {
  const calls = [];
  const row = { ...REPOS[1], securityReview: false };
  const r = await repoAction({ op: "security-review", repo: "acme/widgets", securityReview: false }, "alice",
    { env, fetch: routes({ "PATCH /repos/acme/widgets": [200, row] }, calls) });
  assert.equal(r.ok, true);
  assert.deepEqual(r.repo, row);
  assert.deepEqual(calls, [{ method: "PATCH", url: "http://engine.test:8090/repos/acme/widgets", body: { securityReview: false } }]);
  calls.length = 0;
  await repoAction({ op: "security-review", repo: "acme/widgets", securityReview: true }, "alice",
    { env, fetch: routes({ "PATCH /repos/acme/widgets": [200, REPOS[1]] }, calls) });
  assert.deepEqual(calls[0].body, { securityReview: true });
  for (const bad of [{}, { securityReview: "false" }, { securityReview: null }, { securityReview: 0 }]) {
    const c = [];
    const x = await repoAction({ op: "security-review", repo: "acme/widgets", ...bad }, "alice", { env, fetch: routes({}, c) });
    assert.match(x.error, /securityReview/, JSON.stringify(bad));
    assert.equal(c.length, 0);
  }
  const failed = await repoAction({ op: "security-review", repo: "acme/widgets", securityReview: false }, "alice",
    { env, fetch: routes({ "PATCH /repos/acme/widgets": [400, { message: "securityReview is true or false" }] }) });
  assert.ok(failed.error);
});

test("scan-interval: PATCH with only scanIntervalMinutes; anything but a whole number from 1 to 1440 is refused first", async () => {
  const calls = [];
  const row = { ...REPOS[1], scanIntervalMinutes: 15 };
  const r = await repoAction({ op: "scan-interval", repo: "acme/widgets", scanIntervalMinutes: 15 }, "alice",
    { env, fetch: routes({ "PATCH /repos/acme/widgets": [200, row] }, calls) });
  assert.equal(r.ok, true);
  assert.deepEqual(r.repo, row);
  assert.deepEqual(calls, [{ method: "PATCH", url: "http://engine.test:8090/repos/acme/widgets", body: { scanIntervalMinutes: 15 } }]);
  for (const m of [1, 1440]) {
    const c = [];
    await repoAction({ op: "scan-interval", repo: "acme/widgets", scanIntervalMinutes: m }, "alice", { env, fetch: routes({ "PATCH /repos/acme/widgets": [200, row] }, c) });
    assert.deepEqual(c[0].body, { scanIntervalMinutes: m });
  }
  for (const bad of [0, -5, 1441, 2.5, "10", null, undefined, NaN, true]) {
    const c = [];
    const x = await repoAction({ op: "scan-interval", repo: "acme/widgets", scanIntervalMinutes: bad }, "alice", { env, fetch: routes({}, c) });
    assert.match(x.error, /from 1 to 1440/, String(bad));
    assert.equal(c.length, 0, String(bad));
  }
  const failed = await repoAction({ op: "scan-interval", repo: "acme/widgets", scanIntervalMinutes: 15 }, "alice",
    { env, fetch: routes({ "PATCH /repos/acme/widgets": [403, { message: "a Smith may not set a repo's scan interval" }] }) });
  assert.ok(failed.error);
});

test("webhook-secret: PUT …/webhook-secret with the trimmed secret; clear-webhook-secret: DELETE; bad secrets are refused first", async () => {
  const calls = [];
  const secret = "0123456789abcdef0123456789abcdef";
  const r = await repoAction({ op: "webhook-secret", repo: "acme/widgets", secret: ` ${secret}\n` }, "alice",
    { env, fetch: routes({ "PUT /repos/acme/widgets/webhook-secret": [204] }, calls) });
  assert.equal(r.ok, true);
  assert.deepEqual(calls[0], { method: "PUT", url: "http://engine.test:8090/repos/acme/widgets/webhook-secret", body: { secret } });
  const sealing = await repoAction({ op: "webhook-secret", repo: "acme/widgets", secret }, "alice",
    { env, fetch: routes({ "PUT /repos/acme/widgets/webhook-secret": [503, { message: "ENGINE_SECRETS_KEY is not set" }] }) });
  assert.match(sealing.error, /503.*cannot seal a secret/);
  for (const bad of [undefined, "", "short", "0123456789 abcdef 0123456789", "x".repeat(401)]) {
    const none = [];
    assert.ok((await repoAction({ op: "webhook-secret", repo: "acme/widgets", secret: bad }, "alice", { env, fetch: routes({}, none) })).error, JSON.stringify(bad));
    assert.equal(none.length, 0);
  }
  const cleared = [];
  assert.equal((await repoAction({ op: "clear-webhook-secret", repo: "acme/widgets" }, "alice",
    { env, fetch: routes({ "DELETE /repos/acme/widgets/webhook-secret": [204] }, cleared) })).ok, true);
  assert.deepEqual([cleared[0].method, cleared[0].url], ["DELETE", "http://engine.test:8090/repos/acme/widgets/webhook-secret"]);
});

test("setup-check: POST /repos/setup/check for that one repo with force, never GET …/setup", async () => {
  const calls = [];
  const r = await repoAction({ op: "setup-check", repo: "acme/widgets" }, "alice",
    { env, fetch: routes({ "POST /repos/setup/check": [202, { queued: ["acme/widgets"], fresh: [] }] }, calls) });
  assert.deepEqual(r, { ok: true, queued: ["acme/widgets"], fresh: [] });
  assert.deepEqual(calls, [{ method: "POST", url: "http://engine.test:8090/repos/setup/check", body: { repo: "acme/widgets", force: true } }]);
});

test("labels: POST …/setup/labels answers what it created; unknown ops and bad repos are refused", async () => {
  const r = await repoAction({ op: "labels", repo: "acme/widgets" }, "alice",
    { env, fetch: routes({ "POST /repos/acme/widgets/setup/labels": [200, { created: ["authorized", "intake"] }] }) });
  assert.deepEqual(r.created, ["authorized", "intake"]);
  assert.match((await repoAction({ op: "nuke", repo: "acme/widgets" }, "alice", { env, fetch: routes({}) })).error, /unknown repo action/);
  assert.match((await repoAction({ op: "remove", repo: "acme" }, "alice", { env, fetch: routes({}) })).error, /not an owner\/name repo/);
  assert.match((await repoAction(null, "alice", { env, fetch: routes({}) })).error, /not an owner\/name repo/);
});

// ---- repos.jsx --------------------------------------------------------------

const render = (props) => renderToStaticMarkup(React.createElement(Repos, props));
const ROWS = [
  { ...REPOS[0], running: 2, setup: SETUP_OK, setupError: null },
  { ...REPOS[1], running: 0, setup: SETUP_TODO, setupError: null },
];

test("the checklist answers each setup question, unknowns as unknown", () => {
  const ok = setupChecklist(SETUP_OK);
  assert.ok(ok.every((i) => i.ok === true), JSON.stringify(ok));
  const todo = Object.fromEntries(setupChecklist(SETUP_TODO).map((i) => [i.key, i]));
  assert.equal(todo.labels.ok, false);
  assert.match(todo.labels.text, /missing labels: authorized, intake/);
  assert.equal(todo.branch.ok, false);
  assert.match(todo.branch.text, /requires approving reviews/);
  assert.equal(todo.check.ok, null);
  assert.equal(todo.webhook.ok, null);
  assert.match(todo.webhook.text, /check it by hand/);
  assert.deepEqual(setupChecklist({ repo: "x/y", readable: false }).map((i) => i.ok), [false]);
  assert.match(ok[0].text, /the repo's token can read it/);
  assert.match(setupChecklist({ repo: "x/y", hasToken: true, readable: false })[0].text, /the repo's token cannot read/);
  const noToken = setupChecklist({ repo: "x/y", hasToken: false, readable: false });
  assert.deepEqual(noToken, [{ key: "readable", ok: false, text: "no token: set one on the repo" }]);
  const empty = { ...SETUP_OK, defaultBranch: { name: "main", protected: false, requiresReviews: null, forbidsSquash: null, exists: false }, ciCheck: null };
  const emptyItems = Object.fromEntries(setupChecklist(empty).map((i) => [i.key, i]));
  assert.deepEqual(emptyItems.branch, { key: "branch", ok: false, text: "default branch main: no commits yet, push one" });
  assert.equal(emptyItems.check.text, "no engine check configured");
  assert.deepEqual(parsePaths(" src/, engine/ docs,, "), ["src/", "engine/", "docs"]);
});

test("each repo row shows its running count, no check or paths, and no setup verdict", () => {
  const html = render({ result: { repos: ROWS, runningError: null }, onAction: () => {} });
  assert.match(html, /<h1>Repos · 2<\/h1>/);
  assert.match(html, /codefrak-oss\/frakworks/);
  assert.match(html, />2 flows</);
  assert.match(html, />0 flows</);
  assert.doesNotMatch(html, />engine<|>engine\/<|no check|every path|>check<|>paths</, "the check and its paths are in the Edit check dialog only");
  assert.equal((html.match(/>Edit…<\/button>/g) || []).length, 2, "an Edit… button on each tile");
  assert.match(html, />set 2026-09-27</, "the tokened repo says so, never the token");
  assert.match(html, /none: nothing can run here until one is set/);
  assert.doesNotMatch(html, /fleet token|FLEET_TOKEN/);
  for (const b of ["+ Add repo", "Edit…", "Set token", "Replace token", "Remove"]) assert.ok(html.includes(`>${b}</button>`), b);
  assert.ok(!html.includes(">Access</button>"), "no per-repo access editor: the trusted logins are engine-wide");
  assert.match(html, /no webhook secret on the engine: it runs on its scan interval/);
});

test("each tile links to its repo's full GitHub URL, shown in full", () => {
  const html = render({ result: { repos: ROWS, error: null } });
  assert.match(html, /href="https:\/\/github\.com\/codefrak-oss\/frakworks"/);
  assert.match(html, />https:\/\/github\.com\/codefrak-oss\/frakworks</);
  assert.match(html, /href="https:\/\/github\.com\/acme\/widgets"/);
});

test("read-only rows show the token state but no token controls", () => {
  const ro = render({ result: { repos: ROWS } });
  assert.match(ro, />set 2026-09-27</);
  assert.doesNotMatch(ro, /Set token|Replace token|Remove token|webhook secret</);
});

test("read-only without onAction; errors, the loading state and a failed count are said", () => {
  const ro = render({ result: { repos: ROWS } });
  assert.doesNotMatch(ro, /Add repo|Edit…|>Remove</);
  assert.match(render({ result: { error: "engine unreachable" } }), /class="err">engine unreachable/);
  assert.match(render({ result: { repos: [], loading: true } }), /asking the engine/);
  assert.match(render({ result: { repos: [], runningError: "boom" }, onAction: () => {} }), /running-flow counts unavailable: boom[\s\S]*Add one above/);
});

test("the checklist offers Create labels only when labels are missing and it can write", () => {
  const withRun = renderToStaticMarkup(React.createElement(SetupChecklist, { repo: "acme/widgets", setup: SETUP_TODO, run: () => {} }));
  assert.match(withRun, />Create labels</);
  assert.doesNotMatch(renderToStaticMarkup(React.createElement(SetupChecklist, { repo: "acme/widgets", setup: SETUP_TODO })), /Create labels/);
  assert.doesNotMatch(renderToStaticMarkup(React.createElement(SetupChecklist, { repo: "x/y", setup: SETUP_OK, run: () => {} })), /Create labels/);
});

test("the trusted logins panel is one engine-wide list, with no kind picker", () => {
  // Drawn statically: what shows before the load answers.
  const html = renderToStaticMarkup(React.createElement(EngineAccessPanel, { onLoad: async () => ({ access: { logins: [] } }), onAction: async () => ({ ok: true }) }));
  assert.match(html, /Trusted logins/);
  assert.match(html, /reading trusted logins/);
  assert.doesNotMatch(html, /<select/);
});

// ---- the webhook warning and the scan interval (#958) ------------------------

const hook = (status) => ({ ...SETUP_OK, webhook: { status, hooks: status === "present" ? [{ events: ["*"], active: true }] : null } });

test("webhookWarning: a missing webhook or no secret is warned, a present one with a secret is not, unknown keeps its by-hand wording", () => {
  const withSecret = { fullName: "acme/widgets", hasWebhookSecret: true, scanIntervalMinutes: 60 };
  const noSecret = { ...withSecret, hasWebhookSecret: false };
  assert.equal(webhookWarning(withSecret, hook("present")), null);
  assert.equal(webhookWarning(noSecret, hook("present")), "no webhook secret on the engine: it runs on its scan interval (every 60 min) instead");
  assert.equal(webhookWarning(withSecret, hook("missing")), "no webhook on GitHub: it runs on its scan interval (every 60 min) instead");
  assert.equal(webhookWarning(noSecret, hook("missing")), "no webhook on GitHub and no webhook secret on the engine: it runs on its scan interval (every 60 min) instead");
  assert.equal(webhookWarning({ hasWebhookSecret: false }, null), "no webhook secret on the engine: it runs on its scan interval instead");
  assert.equal(webhookWarning(withSecret, hook("unknown")), null, "the checklist says to check it by hand");
  assert.match(setupChecklist(hook("unknown")).find((i) => i.key === "webhook").text, /check it by hand/);
  assert.equal(webhookWarning(withSecret, null), null, "no setup check: only the secret is known");
});

test("scanEstimate: calls per scan × 60 / interval, against 5,000 an hour; exactly 10% or 25% is the lower band", () => {
  assert.equal(GITHUB_HOURLY_LIMIT, 5000);
  assert.deepEqual([WARN_PCT, DANGER_PCT], [10, 25]);
  const e = scanEstimate(5, 15);
  assert.equal(e.perHour, 20);
  assert.equal(e.pct, 0.4);
  assert.equal(e.band, "ok");
  // 500/h is 10%, 1,250/h is 25%.
  assert.equal(scanEstimate(500, 60).band, "ok");
  assert.equal(scanEstimate(501, 60).band, "warn");
  assert.equal(scanEstimate(1250, 60).band, "warn");
  assert.equal(scanEstimate(1251, 60).band, "danger");
  assert.deepEqual(scanEstimate(0, 1), { perHour: 0, pct: 0, band: "ok" });
});

// ---- the setup check over the stream (#965) ----------------------------------

const BARE = [{ ...REPOS[0], running: 2, setup: null }, { ...REPOS[1], running: 0, setup: null }];
const NAMES = BARE.map((r) => r.fullName);
const tile = (html, name) => html.split('<article').find((t) => t.includes(`>${name}<`)) || "";
const overlay = (check) => renderToStaticMarkup(React.createElement(HealthCheckOverlay, { check, onAction: () => {}, onClose: () => {} }));
const step = (html, name) => (html.match(new RegExp(`<li[^>]*data-step="${name}"[^>]*>`)) || [""])[0];
const frames = (...fs) => fs.reduce((m, f) => applySetupFrame(m, f), {});

test("the tiles show no setup verdict, checking marker, Setup toggle or Re-check", () => {
  const html = render({ result: { repos: BARE }, onAction: () => {}, onHealthCheck: () => {} });
  assert.doesNotMatch(html, /setup not checked|setup check failed|checking…|>Setup<|>Re-check<|setup check not requested/);
});

test("Health check: in the head, disabled while loading, with no repos or while open", () => {
  const head = (props) => (render(props).match(/<button[^>]*aria-label="Health check"[^>]*>/) || [""])[0];
  assert.match(head({ result: { repos: BARE }, onHealthCheck: () => {} }), /^<button(?![^>]*disabled)/);
  assert.match(head({ result: { repos: [], loading: true }, onHealthCheck: () => {} }), /disabled/);
  assert.match(head({ result: { repos: [] }, onHealthCheck: () => {} }), /disabled/);
  assert.match(head({ result: { repos: BARE }, onHealthCheck: () => {}, checking: true }), /disabled/);
});

test("Health check: one click calls onHealthCheck once with the repos in page order; rendering asks nothing", () => {
  const calls = [];
  render({ result: { repos: BARE }, onHealthCheck: (n) => calls.push(n) });
  assert.equal(calls.length, 0, "no request on open");
  HealthCheckButton({ repos: BARE, loading: false, checking: false, onHealthCheck: (n) => calls.push(n) }).props.onClick();
  assert.deepEqual(calls, [NAMES]);
});

test("index.jsx asks for the setup check only from the button, forced through engine:setup-check", () => {
  const fs = require("node:fs"), path = require("node:path");
  const index = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "index.jsx"), "utf8");
  assert.equal((index.match(/await window\.fleet\.setupCheck\(/g) || []).length, 1);
  assert.match(index, /startHealthCheck = useCallback\(async \(names\) => \{\n\s*setHealth\(/, "the overlay opens before the answer");
  assert.doesNotMatch(index, /catching-up/);
  const main = fs.readFileSync(path.join(__dirname, "..", "src", "main.js"), "utf8");
  assert.match(main, /requestSetupCheck\([^)]*"\*", \{ force: true \}\)/);
});

test("the overlay's steps: pending, then running on started, done with its checklist, failed with the error", () => {
  let check = { repos: NAMES, setups: {}, down: false, error: null };
  let html = overlay(check);
  assert.match(html, /role="dialog" aria-modal="true" aria-label="Health check"/);
  assert.match(html, /class="uoBackdrop"/);
  assert.match(step(html, NAMES[0]), /data-state="pending"/);
  assert.match(html, /Checking 2 repos…/);
  assert.doesNotMatch(html, />Close</);
  check = { ...check, setups: frames({ repo: NAMES[0], state: "started" }) };
  assert.match(step(overlay(check), NAMES[0]), /data-state="running"/);
  check = { ...check, setups: frames({ repo: NAMES[0], state: "started" }, { repo: NAMES[0], state: "done", report: SETUP_OK },
    { repo: NAMES[1], state: "done", report: SETUP_TODO }) };
  html = overlay(check);
  assert.match(step(html, NAMES[0]), /data-state="done"/);
  assert.match(html, /every kind label exists/);
  assert.match(html, />Create labels</);
  assert.match(html, /1 of 2 repos need attention/);
  assert.match(html, />Close</);
  check = { ...check, setups: frames({ repo: NAMES[0], state: "done", report: SETUP_OK }, { repo: NAMES[1], state: "failed", error: "GitHub: 502" }) };
  html = overlay(check);
  assert.match(step(html, NAMES[1]), /data-state="failed"/);
  assert.match(html, /GitHub: 502/);
  assert.equal(healthTitle(check).text, "1 of 2 repos need attention");
});

test("the title rules and Close gating", () => {
  const ok = { repos: NAMES, setups: frames(...NAMES.map((n) => ({ repo: n, state: "done", report: SETUP_OK }))), down: false, error: null };
  assert.deepEqual(healthTitle(ok), { text: "All 2 repos set up", ended: true, ok: true });
  const half = { ...ok, setups: frames({ repo: NAMES[0], state: "done", report: SETUP_OK }) };
  assert.equal(healthTitle(half).ended, false);
  const down = { ...half, down: true };
  assert.deepEqual(healthSteps(down).map((s) => s.state), ["done", "failed"]);
  assert.equal(healthSteps(down)[1].error, STREAM_DOWN);
  assert.match(overlay(down), />Close</);
  const refused = { repos: NAMES, setups: {}, down: false, error: "engine unreachable" };
  const html = overlay(refused);
  assert.match(html, /class="uoTitle uo-bad">Health check failed: engine unreachable/);
  assert.match(html, />Close</);
  assert.equal(overlay(null), "");
});

test("the webhook warning reads the last report, else only the missing secret", () => {
  const missing = { ...SETUP_OK, webhook: { status: "missing", hooks: [] } };
  const rows = BARE.map((r) => ({ ...r, hasWebhookSecret: true, setup: r.fullName === NAMES[0] ? missing : null }));
  const html = render({ result: { repos: rows } });
  assert.match(tile(html, NAMES[0]), /class="acWarn">⚠ no webhook on GitHub: it runs on its scan interval/);
  assert.doesNotMatch(tile(html, NAMES[1]), /acWarn/);
  assert.match(tile(render({ result: { repos: BARE } }), NAMES[1]), /no webhook secret on the engine/);
});

test("applySetupFrame: frames for another repo leave a repo alone; unknown states change nothing", () => {
  let m = applySetupFrame({}, { repo: "acme/widgets", state: "done", checkedAt: "2026-10-05T10:00:00Z", report: SETUP_TODO });
  const before = m["acme/widgets"];
  m = applySetupFrame(m, { repo: "codefrak-oss/frakworks", state: "failed", error: "GitHub: 502 Bad Gateway" });
  assert.equal(m["acme/widgets"], before);
  assert.equal(m["codefrak-oss/frakworks"].setupError, "GitHub: 502 Bad Gateway");
  assert.equal(applySetupFrame(m, { repo: "acme/widgets", state: "bogus" }), m);
  assert.equal(applySetupFrame(m, null), m);
});

// ---- Doctor files bugs (#1001) ---------

/** Every element under `el` (host elements only: the dialog and the confirm draw no components of their own). */
const elements = (el, out = []) => {
  if (Array.isArray(el)) { el.forEach((c) => elements(c, out)); return out; }
  if (!el || typeof el !== "object" || !el.props) return out;
  out.push(el);
  elements(el.props.children, out);
  return out;
};
const text = (el) => (el == null || typeof el === "boolean" ? "" : typeof el !== "object" ? String(el)
  : Array.isArray(el) ? el.map(text).join("") : text(el.props && el.props.children));
const button = (tree, label) => elements(tree).find((e) => e.type === "button" && text(e).trim() === label);

const DR = (repo, remedy, sighting = null, id = Math.random().toString(36).slice(2)) => ({ id, repo, issue: 1, rootInstanceId: "r", position: ["a"], remedy, outcome: "none", sighting });
const RUNS = [
  DR("codefrak-oss/frakworks", "unfixable", { id: 1, kind: "unfixable", where: "engine", bug: { repo: "codefrak-oss/frakworks", issue: 900, state: "open" }, tickets: 2, runs: 3, signature: "s1" }),
  DR("Codefrak-OSS/Frakworks", "retry", { id: 2, kind: "retried", where: "engine", bug: null, tickets: 1, runs: 1, signature: "s2" }),
  DR("codefrak-oss/frakworks", "retry", { id: 3, kind: "retried", where: "target", bug: null, tickets: 1, runs: 1, signature: "s3" }),
  DR("codefrak-oss/frakworks", "unfixable", { id: 5, kind: "unfixable", where: "target", bug: null, tickets: 1, runs: 2, signature: "s3" }),
  DR("codefrak-oss/frakworks", "healthy"),
  DR("acme/widgets", "unfixable", { id: 4, kind: "unfixable", where: "engine", bug: { repo: "acme/home", issue: 8, state: "open" }, tickets: 1, runs: 1, signature: "s4" }),
  DR("acme/widgets", "retry"),
];
const BUGS = [
  { id: 1, repo: "codefrak-oss/frakworks", issue: 900, state: "open", signatures: [{ signature: "s1" }], tickets: 2, runs: 3 },
  { id: 2, repo: "acme/home", issue: 7, state: "open", signatures: [{ signature: "s2" }, { signature: "s9" }], tickets: 1, runs: 1 },
  { id: 3, repo: "acme/home", issue: 8, state: "open", signatures: [{ signature: "s4" }], tickets: 1, runs: 1 },
  { id: 4, repo: "codefrak-oss/frakworks", issue: null, state: "filing", signatures: [{ signature: "s8" }], tickets: 1, runs: 1 },
];


test("fetchRepos reads each row's doctorBugs and engineDefects, a row without them (an older engine) as off", async () => {
  const rows = [{ ...REPOS[0], doctorBugs: true, engineDefects: true }, REPOS[1]];
  const r = await fetchRepos({ env, fetch: routes({ "GET /repos": [200, rows], "GET /issues": [200, BOARD] }) });
  assert.deepEqual(r.repos.map((x) => [x.doctorBugs, x.engineDefects]), [[true, true], [false, false]]);
});

test("fetchDoctorBugs reads GET /doctor/bugs?since= for every repo; a bad since is refused first, a 404 said in words", async () => {
  const calls = [];
  const r = await fetchDoctorBugs("2026-09-28T00:00:00Z", { env, fetch: routes({ "GET /doctor/bugs": [200, { since: "2026-09-28T00:00:00Z", bugs: BUGS }] }, calls) });
  assert.equal(r.error, null);
  assert.equal(r.bugs.length, 4);
  assert.equal(calls[0].url, "http://engine.test:8090/doctor/bugs?since=2026-09-28T00%3A00%3A00Z", "no repo: the endpoint takes none");
  const c = [];
  assert.match((await fetchDoctorBugs("last week", { env, fetch: routes({}, c) })).error, /ISO instant/);
  assert.equal(c.length, 0);
  assert.match((await fetchDoctorBugs("2026-09-28T00:00:00Z", { env, fetch: routes({}) })).error, /no \/doctor\/bugs/);
  assert.match((await fetchDoctorBugs("2026-09-28T00:00:00Z", { env, fetch: routes({ "GET /doctor/bugs": [200, {}] }) })).error, /no bugs list/);
});

test("doctor-bugs: PATCH with doctorBugs, and engineDefects only when given; the engine's 400 and 409 are the error", async () => {
  const calls = [];
  const ok = routes({ "PATCH /repos/acme/widgets": [200, { ...REPOS[1], doctorBugs: true }] }, calls);
  assert.equal((await repoAction({ op: "doctor-bugs", repo: "acme/widgets", doctorBugs: true }, "alice", { env, fetch: ok })).ok, true);
  await repoAction({ op: "doctor-bugs", repo: "acme/widgets", doctorBugs: true, engineDefects: true }, "alice", { env, fetch: ok });
  await repoAction({ op: "doctor-bugs", repo: "acme/widgets", doctorBugs: false }, "alice", { env, fetch: ok });
  assert.deepEqual(calls.map((c) => [c.method, c.url, c.body]), [
    ["PATCH", "http://engine.test:8090/repos/acme/widgets", { doctorBugs: true }],
    ["PATCH", "http://engine.test:8090/repos/acme/widgets", { doctorBugs: true, engineDefects: true }],
    ["PATCH", "http://engine.test:8090/repos/acme/widgets", { doctorBugs: false }],
  ]);
  for (const bad of [{}, { doctorBugs: "true" }, { doctorBugs: null }, { doctorBugs: true, engineDefects: 1 }, { doctorBugs: true, engineDefects: null }]) {
    const c = [];
    const x = await repoAction({ op: "doctor-bugs", repo: "acme/widgets", ...bad }, "alice", { env, fetch: routes({}, c) });
    assert.match(x.error, /doctorBugs|engineDefects/, JSON.stringify(bad));
    assert.equal(c.length, 0);
  }
  const taken = await repoAction({ op: "doctor-bugs", repo: "acme/widgets", doctorBugs: true, engineDefects: true }, "alice",
    { env, fetch: routes({ "PATCH /repos/acme/widgets": [409, { message: "codefrak-oss/frakworks takes engine defects" }] }) });
  assert.match(taken.error, /409 .*codefrak-oss\/frakworks takes engine defects/);
  const off = await repoAction({ op: "doctor-bugs", repo: "acme/widgets", doctorBugs: false, engineDefects: true }, "alice",
    { env, fetch: routes({ "PATCH /repos/acme/widgets": [400, { message: "engineDefects needs doctorBugs" }] }) });
  assert.match(off.error, /400 .*engineDefects needs doctorBugs/);
});

test("engineDefectsHolder names the other repo with engineDefects, or none", () => {
  const rows = [{ fullName: "a/x", engineDefects: false }, { fullName: "b/y", engineDefects: true }];
  assert.equal(engineDefectsHolder(rows, "a/x"), "b/y");
  assert.equal(engineDefectsHolder(rows, "B/Y"), null, "itself is not another repo");
  assert.equal(engineDefectsHolder([], "a/x"), null);
});

test("the modal's numbers are this repo's: runs, unfixable, retries and the distinct signatures it recorded, from every repo's rows", () => {
  assert.deepEqual(doctorNumbers("codefrak-oss/frakworks", RUNS, BUGS),
    { runs: 5, unfixable: 2, retries: 2, sightings: 4, defects: 3, filed: 2 },
    "its runs whatever the case; s1, s2 and s3 (seen twice) recorded; s1 on its sighting's bug, s2 on a listed bug; s3 on none; s8 is not its own");
  assert.deepEqual(doctorNumbers("acme/widgets", RUNS, BUGS), { runs: 2, unfixable: 1, retries: 1, sightings: 1, defects: 1, filed: 1 });
  assert.deepEqual(doctorNumbers("acme/widgets", RUNS, []), { runs: 2, unfixable: 1, retries: 1, sightings: 1, defects: 1, filed: 1 },
    "a sighting's own bug counts without the bug listed");
  assert.deepEqual(doctorNumbers("acme/none", RUNS, BUGS), { runs: 0, unfixable: 0, retries: 0, sightings: 0, defects: 0, filed: 0 });
  assert.deepEqual(doctorNumbers("acme/none", null, null), { runs: 0, unfixable: 0, retries: 0, sightings: 0, defects: 0, filed: 0 });
});

test("loadDoctorNumbers asks for 7 days of runs and bugs once, keeps this repo's, and says either side's error", async () => {
  const now = Date.parse("2026-10-05T12:00:00Z");
  assert.equal(DOCTOR_DAYS, 7);
  assert.equal(doctorSince(now), "2026-09-28T12:00:00.000Z");
  const asked = [];
  const ok = await loadDoctorNumbers(async (since) => { asked.push(since); return { runs: { runs: RUNS }, bugs: { bugs: BUGS } }; }, "acme/widgets", now);
  assert.deepEqual(asked, ["2026-09-28T12:00:00.000Z"]);
  assert.deepEqual(ok, { numbers: { runs: 2, unfixable: 1, retries: 1, sightings: 1, defects: 1, filed: 1 } });
  const failed = await loadDoctorNumbers(async () => ({ runs: { error: "boom" }, bugs: { error: "this engine has no /doctor/bugs (older than #1000)" } }), "a/x", now);
  assert.equal(failed.error, "boom; this engine has no /doctor/bugs (older than #1000)");
  assert.match((await loadDoctorNumbers(async () => { throw new Error("gone"); }, "a/x", now)).error, /IPC failed: gone/);
  assert.match((await loadDoctorNumbers(null, "a/x", now)).error, /no doctor numbers/);
});

// ---- Friction bugs (#1112) ---------

test("fetchRepos reads each row's frictionBugs, a row without it (an older engine) as off", async () => {
  const rows = [{ ...REPOS[0], frictionBugs: true }, REPOS[1]];
  const r = await fetchRepos({ env, fetch: routes({ "GET /repos": [200, rows], "GET /issues": [200, { issues: [] }] }) });
  assert.deepEqual(r.repos.map((x) => x.frictionBugs), [true, false]);
});

test("friction-bugs: PATCH with frictionBugs; the engine's 400, 403 and 409 are the error", async () => {
  const calls = [];
  const ok = routes({ "PATCH /repos/acme/widgets": [200, { ...REPOS[1], frictionBugs: true }] }, calls);
  assert.equal((await repoAction({ op: "friction-bugs", repo: "acme/widgets", frictionBugs: true }, "alice", { env, fetch: ok })).ok, true);
  await repoAction({ op: "friction-bugs", repo: "acme/widgets", frictionBugs: false }, "alice", { env, fetch: ok });
  assert.deepEqual(calls.map((c) => [c.method, c.url, c.body]), [
    ["PATCH", "http://engine.test:8090/repos/acme/widgets", { frictionBugs: true }],
    ["PATCH", "http://engine.test:8090/repos/acme/widgets", { frictionBugs: false }],
  ]);
  for (const bad of [{}, { frictionBugs: "true" }, { frictionBugs: null }]) {
    const c = [];
    const x = await repoAction({ op: "friction-bugs", repo: "acme/widgets", ...bad }, "alice", { env, fetch: routes({}, c) });
    assert.match(x.error, /frictionBugs is true or false/, JSON.stringify(bad));
    assert.equal(c.length, 0, "nothing sent");
  }
  const refused = await repoAction({ op: "friction-bugs", repo: "acme/widgets", frictionBugs: true }, "alice",
    { env, fetch: routes({ "PATCH /repos/acme/widgets": [403, { message: "a Smith sandbox (run r) may not set a repo's friction bugs" }] }) });
  assert.match(refused.error, /403 .*may not set a repo's friction bugs/);
});

// ---- the Edit… modal's Engine check tab (#1252, #1255) -----------------------

const { ModalOverlay, inertOutside } = require("../dist/repos.cjs");
const FRAK = { fullName: "codefrak-oss/frakworks", ciCheck: "engine", ciPaths: ["engine/", "observability/desktop/"] };
// The Edit… modal opened on its Engine check tab.
const checkDialog = ({ name = "engine", paths = "engine/\nobservability/desktop/", onName = () => {}, onPaths = () => {}, ...props } = {}) =>
  React.createElement(EditRepoDialog, { repo: FRAK, repos: [FRAK], tab: "check", onTab: () => {}, v: { ...editValues(FRAK), name, paths },
    set: (k, x) => (k === "name" ? onName(x) : onPaths(x)), send: async () => ({ ok: true }), numbers: null,
    error: null, busy: false, onSave: () => {}, onCancel: () => {}, ...props });
// The elements of a tree, walking function components that hold no hooks (EditRepoDialog and its pages, ModalOverlay excluded: it has effects).
const treeOf = (el, out = []) => {
  if (Array.isArray(el)) { el.forEach((e) => treeOf(e, out)); return out; }
  if (!el || typeof el !== "object") return out;
  out.push(el);
  if (typeof el.type === "function" && el.type !== ModalOverlay) treeOf(el.type(el.props), out);
  else treeOf(el.props && el.props.children, out);
  return out;
};

test("Edit check dialog: the update overlay's scrim and panel, then title, Check prose, name, Paths prose, one-per-line textarea, rules, Cancel / Save", () => {
  const html = renderToStaticMarkup(checkDialog());
  assert.match(html, /^<div class="uoBackdrop" role="dialog" aria-modal="true" aria-label="Edit: codefrak-oss\/frakworks"><div class="uoPanel erPanel">/);
  for (const t of ["GitHub check run", "path prefixes", "one per line", "<code>engine</code>", "<code>engine/</code>", "<code>observability/desktop/</code>",
    "the reviews start right after", "wait until the timeout", "No CI: leave both empty", "CI on every push"]) assert.ok(html.includes(t), t);
  const order = ["uoTitle", "<b>Check</b>", 'aria-label="ci check"', "<b>Paths</b>", 'aria-label="ci paths"', "Rules of thumb", ">Cancel<", ">Save<"]
    .map((t) => html.indexOf(t));
  assert.ok(order.every((i, k) => i >= 0 && (k === 0 || i > order[k - 1])), String(order));
  assert.match(html, /<input[^>]*aria-label="ci check"[^>]*value="engine"/);
  assert.match(html, /<textarea[^>]*aria-label="ci paths"[^>]*>engine\/\nobservability\/desktop\/<\/textarea>/);
  assert.match(html, /<button class="acBtn primary" type="submit">Save<\/button>/);
});

test("Edit check dialog: Save sends the check op with the edited name and prefixes; an empty name sends null and no paths", async () => {
  let saved = 0;
  const form = treeOf(checkDialog({ onSave: () => { saved++; } })).find((e) => e.type === "form");
  form.props.onSubmit({ preventDefault() {} });
  assert.equal(saved, 1);
  const sent = [];
  const send = async (a) => { sent.push(a); return { ok: true }; };
  let closed = 0;
  await saveCheck(send, FRAK, " build ", "src/\ndocs/\n", () => {}, () => { closed++; });
  await saveCheck(send, FRAK, "  ", "src/", () => {}, () => { closed++; });
  assert.deepEqual(sent, [
    { op: "check", repo: "codefrak-oss/frakworks", ciCheck: "build", ciPaths: ["src/", "docs/"] },
    { op: "check", repo: "codefrak-oss/frakworks", ciCheck: null, ciPaths: [] },
  ]);
  assert.equal(closed, 2);
  assert.deepEqual(checkAction("a/b", "engine", "engine/\nobservability/desktop/"), { op: "check", repo: "a/b", ciCheck: "engine", ciPaths: ["engine/", "observability/desktop/"] });
});

test("Edit check dialog: a refusal stays in the panel and keeps it open; Cancel and Escape send nothing", async () => {
  let error = null, closed = 0;
  const r = await saveCheck(async () => ({ error: "ciPaths are path prefixes, not blank, with no commas" }), FRAK, "engine", "a", (e) => { error = e; }, () => { closed++; });
  assert.equal(r, null);
  assert.equal(closed, 0);
  const html = renderToStaticMarkup(checkDialog({ error }));
  assert.match(html, /<div class="uoPanel erPanel">.*<div class="uoFail">ciPaths are path prefixes, not blank, with no commas<\/div>.*<\/div><\/div>$/s);
  let cancelled = 0;
  const onCancel = () => { cancelled++; };
  const els = treeOf(checkDialog({ onCancel }));
  els.find((e) => e.type === "button" && e.props.children === "Cancel").props.onClick();
  assert.equal(els.find((e) => e.type === ModalOverlay).props.onEscape, onCancel, "Escape is the overlay's, wired to Cancel");
  assert.equal(cancelled, 1);
});

test("ModalOverlay: everything outside it is inert while it is open, and only that is undone", () => {
  const el = (tagName, kids = [], inert = false) => { const n = { tagName, inert, children: kids }; kids.forEach((k) => { k.parentElement = n; }); return n; };
  const overlay = el("DIV"), app = el("DIV"), already = el("DIV", [], true);
  const root = el("DIV", [app, overlay]);
  const body = el("BODY", [root, already]);
  body.parentElement = el("HTML", [body]);
  const undo = inertOutside(overlay);
  assert.deepEqual([app.inert, overlay.inert, root.inert, already.inert], [true, false, false, true]);
  undo();
  assert.deepEqual([app.inert, already.inert], [false, true]);
});

// ---- the tile's status and the Edit… modal's tabs (#1255) --------------------

const FULL = { fullName: "acme/widgets", hasToken: true, tokenSetAt: "2026-09-27T09:00:00Z", hasWebhookSecret: true,
  webhookSecretSetAt: "2026-09-28T09:00:00Z", securityReview: false, doctorBugs: true, engineDefects: true, frictionBugs: true,
  scanIntervalMinutes: 15, scanCallsPerScan: 5, scanCalls: { listing: 1, authorized: 2, deferred: 1, checks: 1 },
  ciCheck: "build", ciPaths: [], running: 1, setup: null };
const page = (tab, over = {}) => renderToStaticMarkup(React.createElement(EditRepoDialog, {
  repo: FULL, repos: [FULL], tab, onTab: () => {}, v: editValues(FULL), set: () => {}, send: async () => ({ ok: true }),
  numbers: null, error: null, busy: false, onSave: () => {}, onCancel: () => {}, ...over }));
const paragraphs = (html) => (html.match(/<p class="ecProse">/g) || []).length;

test("a tile shows status only: no setting values and no per-setting controls, one Edit…", () => {
  const html = render({ result: { repos: [FULL] }, onAction: () => {} });
  for (const t of ["acme/widgets", "https://github.com/acme/widgets", ">1 flow<", ">set 2026-09-27<", ">secret set 2026-09-28<", ">Edit…</button>",
    ">Replace token</button>", ">Remove</button>"]) assert.ok(html.includes(t), t);
  assert.doesNotMatch(html, /type="checkbox"|Security reviews|Doctor files bugs|Friction bugs|Scan interval…|Edit check…|webhook secret<\/button>|calls a scan|every 15 min|engine defects/);
  assert.doesNotMatch(html, /role="dialog"/, "no modal until Edit… is clicked");
  const src = require("node:fs").readFileSync(require("node:path").join(__dirname, "..", "src", "renderer", "repos.jsx"), "utf8");
  assert.match(src, /is\("edit", r\.fullName\) &&\s*<EditRepoModal /);
});

test("Edit…: ModalOverlay titled Edit: owner/name with the six tabs in order, the open one selected", () => {
  assert.deepEqual(EDIT_TABS.map((t) => t.label), ["Engine check", "Doctor", "Friction", "Scanning and webhook", "Security review", "Docs path", "Refactoring"]);
  const html = page("friction");
  assert.match(html, /^<div class="uoBackdrop" role="dialog" aria-modal="true" aria-label="Edit: acme\/widgets"><div class="uoPanel erPanel"><div class="uoTitle">Edit: acme\/widgets<\/div>/);
  const tabs = html.match(/<button[^>]*role="tab"[^>]*>[^<]*<\/button>/g);
  assert.deepEqual(tabs.map((t) => t.replace(/<[^>]*>/g, "")), EDIT_TABS.map((t) => t.label));
  assert.match(tabs[2], /aria-selected="true"/);
  assert.match(tabs[0], /aria-selected="false"/);
  const switched = [];
  const els = treeOf(React.createElement(EditRepoDialog, { repo: FULL, repos: [FULL], tab: "check", onTab: (t) => switched.push(t), v: editValues(FULL),
    set: () => {}, send: async () => { throw new Error("nothing is sent"); }, numbers: null, error: null, busy: false, onSave: () => { throw new Error("no save"); }, onCancel: () => {} }));
  els.filter((e) => e.type === "button" && e.props.role === "tab").forEach((b) => b.props.onClick());
  assert.deepEqual(switched, EDIT_TABS.map((t) => t.key), "switching tabs only switches");
});

test("each tab's page: a title and at least three paragraphs carrying its substance", () => {
  const phrases = {
    doctor: ["a sandboxed Smith at the ticket&#x27;s own tier", "healthy, unfixable, retry, collect-exit, run-job", "Seen again", "What needs you"],
    friction: ["two things", "starts authorized runs", "the repo of the run&#x27;s ticket", "not stuck flows"],
    scan: ["the push half", "The scan is the fallback half", "as good as none", "Rule of thumb"],
    security: ["straight to the merge gate", "at its next round", "403 from a sandbox"],
    refactor: ["files small refactoring tickets", "refactor-scan ticket at the scan tier", "System badge", "Reset to default puts the shipped values back"],
  };
  for (const [tab, ts] of Object.entries(phrases)) {
    const html = page(tab);
    assert.ok(paragraphs(html) >= 3, tab);
    assert.match(html, new RegExp(`<div class="erTitle">${EDIT_TABS.find((t) => t.key === tab).label}</div>`));
    for (const t of ts) assert.ok(html.includes(t), `${tab}: ${t}`);
    // Refactoring's Save waits for its config, which a static render never reads.
    assert.match(html, tab === "refactor" ? />Cancel<\/button><button class="acBtn primary" type="submit" disabled="">Save<\/button>/
      : />Cancel<\/button><button class="acBtn primary" type="submit">Save<\/button>/, tab);
  }
});

test("Doctor tab: Engine defects home needs Doctor bugs, another repo's hold disables it and names that repo, turning off says what clears", () => {
  const box = (html, label) => (html.match(new RegExp(`<input type="checkbox" aria-label="${label}"[^>]*>`)) || [""])[0];
  const a = { ...FULL, engineDefects: false }, b = { fullName: "acme/home", doctorBugs: true, engineDefects: true };
  const held = page("doctor", { repo: a, repos: [a, b], v: editValues(a) });
  assert.match(held, /engine defects are filed in acme\/home; only one repo can hold this/);
  assert.match(box(held, "Engine defects home"), /disabled/);
  assert.match(box(held, "Doctor bugs"), /checked=""/);
  const free = page("doctor", { repo: a, repos: [a], v: editValues(a) });
  assert.doesNotMatch(box(free, "Engine defects home"), /disabled/);
  const off = page("doctor", { v: { ...editValues(FULL), doctorBugs: false } });
  assert.match(box(off, "Engine defects home"), /disabled/);
  assert.doesNotMatch(box(off, "Engine defects home"), /checked/);
  assert.match(off, /Stop filing; bugs already filed stay\. Turning Doctor bugs off clears\s+Engine defects home here too: engine defects are then filed nowhere/);
  assert.doesNotMatch(page("doctor"), /Stop filing/, "nothing to say until it is turned off");
  const plain = page("doctor", { repo: a, repos: [a], v: { ...editValues(a), doctorBugs: false } });
  assert.match(plain, /clears\s+Engine defects home here too\./);
  assert.doesNotMatch(plain, /filed nowhere/);
  assert.match(page("doctor", { numbers: { numbers: { runs: 1004, unfixable: 81, retries: 24, sightings: 120, defects: 3, filed: 1 } } }),
    /last 7 days: 1,004 doctor runs, 81 unfixable, 24 retries, 3 distinct defects \(1 already on a doctor bug\) in 120 sightings/);
  assert.match(page("doctor"), /reading this repo&#x27;s doctor runs…/);
  assert.match(page("doctor", { numbers: { error: "boom" } }), /doctor numbers are unavailable: boom/);
  const src = require("node:fs").readFileSync(require("node:path").join(__dirname, "..", "src", "renderer", "repos.jsx"), "utf8");
  assert.match(src, /if \(tab !== "doctor" \|\| asked\.current\) return;/, "the numbers are read when the Doctor tab is first shown");
});

test("Friction tab: a Friction bugs switch; turning it off says bugs already filed stay", () => {
  assert.match(page("friction"), /<input type="checkbox" aria-label="Friction bugs" checked=""/);
  assert.doesNotMatch(page("friction"), /Stop filing/);
  assert.match(page("friction", { v: { ...editValues(FULL), frictionBugs: false } }), /Stop filing; bugs already filed stay\./);
});

test("Scanning and webhook tab: the interval's cost recomputed from the typed minutes, the bands, Save anyway, and the webhook-secret form", () => {
  const other = { fullName: "acme/gizmos", scanIntervalMinutes: 60, scanCallsPerScan: 100 };
  const html = page("scan", { repos: [FULL, other] });
  assert.match(html, /calls per scan × 60 \/ interval = <b>5<\/b> × 60 \/ <b>15<\/b> = <b>20<\/b> requests\/hour/);
  assert.match(html, /1 issue listing, 2 authorized issue\(s\) read, 1 deferred issue\(s\) re-checked, 1 check wait\(s\) polled/);
  assert.match(html, /class="acBand ok">this repo: 0.4% of GitHub&#x27;s 5,000\/hour limit/);
  assert.match(html, /every repo, if they share one token: 120 requests\/hour, 2.4%/);
  const typed = page("scan", { v: { ...editValues(FULL), minutes: "1" }, repo: { ...FULL, scanCallsPerScan: 100 }, repos: [] });
  assert.match(typed, /= <b>100<\/b> × 60 \/ <b>1<\/b> = <b>6,000<\/b> requests\/hour/);
  assert.match(typed, /class="acBand danger">this repo: 120%/);
  assert.deepEqual([scanCost(FULL, [], "30").est.perHour, scanCost(FULL, [], "x").valid], [10, false]);
  const confirm = page("scan", { v: { ...editValues(FULL), minutes: "1", confirming: true } });
  assert.match(confirm, /save anyway\?[\s\S]*<button class="acBtn danger" type="submit">Save anyway<\/button>/);
  assert.match(page("scan", { v: { ...editValues(FULL), minutes: "0" } }), /a whole number of minutes from 1 to 1440[\s\S]*type="submit" disabled="">Save/);
  for (const t of ['aria-label="webhook secret"', ">Generate</button>", ">Replace webhook secret</button>", ">Remove secret</button>", "Webhook secret: <span"])
    assert.ok(html.includes(t), t);
  const none = page("scan", { repo: { ...FULL, hasWebhookSecret: false, webhookSecretSetAt: null } });
  assert.match(none, />Set webhook secret<\/button>/);
  assert.doesNotMatch(none, /Remove secret/);
  assert.match(none, /none: the engine refuses every delivery/);
});

test("Scanning and webhook tab: Instructions opens the steps to create the webhook on GitHub, Close hides them", () => {
  assert.ok(page("scan").includes(">Instructions</button>"));
  let open = false;
  const tree = () => WebhookInstructions({ repo: FULL, open, onToggle: () => { open = !open; } });
  const html = () => renderToStaticMarkup(tree());
  const click = (label) => {
    const find = (n) => !n || typeof n !== "object" ? null : Array.isArray(n) ? n.map(find).find(Boolean)
      : n.type === "button" && n.props.children === label ? n : find(n.props && n.props.children);
    find(tree()).props.onClick();
  };
  assert.doesNotMatch(html(), /hooks\/new/);
  click("Instructions");
  const shown = html();
  assert.ok(shown.includes('href="https://github.com/acme/widgets/settings/hooks/new"'));
  assert.match(shown, /<code>https:\/\/&lt;engine-public-host&gt;\/hooks\/github<\/code>/);
  for (const t of ["application/json", "Issues", "Check suites", "Issue comments", "Pull requests", "Pushes"]) assert.ok(shown.includes(`<code>${t}</code>`), t);
  click("Close");
  assert.doesNotMatch(html(), /hooks\/new/);
});

test("each tab's Save sends only its own keys, and nothing when nothing changed", () => {
  const v = editValues(FULL);
  for (const tab of EDIT_TABS.map((t) => t.key).filter((t) => t !== "refactor")) assert.equal(pageAction(tab, FULL, v), null, `${tab}: unchanged`);
  assert.equal(pageAction("refactor", FULL, v), null, "Refactoring saves through its own channel, never repo-action");
  assert.deepEqual(pageAction("check", FULL, { ...v, name: "ci", paths: "src/" }), { op: "check", repo: "acme/widgets", ciCheck: "ci", ciPaths: ["src/"] });
  assert.deepEqual(pageAction("doctor", FULL, { ...v, doctorBugs: false }), { op: "doctor-bugs", repo: "acme/widgets", doctorBugs: false });
  assert.deepEqual(pageAction("doctor", FULL, { ...v, engineDefects: false }), { op: "doctor-bugs", repo: "acme/widgets", doctorBugs: true, engineDefects: false });
  const bare = { fullName: "a/b" }, bv = editValues(bare);
  assert.deepEqual(pageAction("doctor", bare, { ...bv, doctorBugs: true }), { op: "doctor-bugs", repo: "a/b", doctorBugs: true });
  assert.deepEqual(pageAction("doctor", bare, { ...bv, doctorBugs: true, engineDefects: true }), { op: "doctor-bugs", repo: "a/b", doctorBugs: true, engineDefects: true });
  assert.equal(pageAction("doctor", bare, { ...bv, engineDefects: true }), null, "the home switch means nothing with Doctor bugs off");
  assert.deepEqual(pageAction("friction", FULL, { ...v, frictionBugs: false }), { op: "friction-bugs", repo: "acme/widgets", frictionBugs: false });
  assert.deepEqual(pageAction("scan", FULL, { ...v, minutes: " 30 " }), { op: "scan-interval", repo: "acme/widgets", scanIntervalMinutes: 30 });
  assert.equal(pageAction("scan", FULL, { ...v, minutes: "1441" }), null);
  assert.deepEqual(pageAction("security", FULL, { ...v, securityReview: true }), { op: "security-review", repo: "acme/widgets", securityReview: true });
});

test("savePage: nothing changed sends nothing; a refusal stays in the open tab and keeps the modal open", async () => {
  const sent = [];
  let closed = 0, error = "old";
  await savePage(async (a) => { sent.push(a); return { ok: true }; }, null, (e) => { error = e; }, () => { closed++; });
  assert.deepEqual([sent.length, closed, error], [0, 1, null]);
  const action = pageAction("friction", FULL, { ...editValues(FULL), frictionBugs: false });
  const r = await savePage(async () => ({ error: "engine answered 403 for PATCH /repos/acme/widgets: a Smith sandbox may not" }), action,
    (e) => { error = e; }, () => { closed++; });
  assert.deepEqual([r, closed], [null, 1]);
  const html = page("friction", { error, v: { ...editValues(FULL), frictionBugs: false } });
  assert.match(html, /role="tabpanel".*<div class="uoFail">engine answered 403 [^<]*<\/div><div class="uoFoot ecFoot">/s);
  const src = require("node:fs").readFileSync(require("node:path").join(__dirname, "..", "src", "renderer", "repos.jsx"), "utf8");
  assert.match(src, /if \(!r \|\| r\.error\) return \{ error: \(r && r\.error\) \|\| "no answer from the main process" \};/, "send sets no page notice on a refusal");
});

test("child-mode: the Docs path page shows the three-way choice with the repo's mode selected, serial when the engine omits it", async () => {
  const calls = [];
  const r = await fetchRepos({ env, fetch: routes({
    "GET /repos": [200, [{ ...REPOS[0], childMode: "parallel" }, { ...REPOS[1], childMode: "weird" }, { fullName: "acme/old" }]], "GET /issues": [200, BOARD],
  }, calls) });
  assert.deepEqual(r.repos.map((x) => x.childMode), ["parallel", "serial", "serial"], "an unknown or missing mode reads as serial");
  for (const mode of ["serial", "parallel", "live"]) {
    const repo = { ...FULL, childMode: mode };
    const html = page("docs", { repo, v: editValues(repo) });
    const choice = (html.match(/<select[^>]*aria-label="Child work"[^>]*>[\s\S]*?<\/select>/) || [""])[0];
    assert.ok(choice, "the Child work choice is on the Docs path page");
    assert.match(html, /aria-label="Docs-first"[\s\S]*aria-label="Child work"/, "beside the Docs-first switch");
    const options = [...choice.matchAll(/<option[^>]*value="([a-z]+)"[^>]*>/g)].map((m) => [m[1], /selected/.test(m[0])]);
    assert.deepEqual(options, [["serial", mode === "serial"], ["parallel", mode === "parallel"], ["live", mode === "live"]], mode);
    for (const t of ["Serial:", "Parallel:", "Live:"]) assert.ok(choice.includes(t), `${t} labelled`);
  }
  assert.equal(editValues({ fullName: "a/b" }).childMode, "serial");
});

test("child-mode: the Docs path page sends a changed mode as child-mode, with docsFirst only when it changed too, and the action PATCHes it", async () => {
  const repo = { fullName: "acme/widgets", docsFirst: false, childMode: "serial" };
  assert.equal(pageAction("docs", repo, editValues(repo)), null);
  assert.deepEqual(pageAction("docs", repo, { ...editValues(repo), childMode: "live" }), { op: "child-mode", repo: "acme/widgets", childMode: "live" });
  assert.deepEqual(pageAction("docs", repo, { ...editValues(repo), childMode: "parallel", docsFirst: true }),
    { op: "child-mode", repo: "acme/widgets", childMode: "parallel", docsFirst: true }, "both changed: one PATCH");
  const calls = [];
  const row = { ...REPOS[1], childMode: "parallel" };
  const r = await repoAction({ op: "child-mode", repo: "acme/widgets", childMode: "parallel" }, "alice", { env, fetch: routes({ "PATCH /repos/acme/widgets": [200, row] }, calls) });
  assert.deepEqual([r.ok, r.repo.childMode], [true, "parallel"]);
  assert.deepEqual(calls, [{ method: "PATCH", url: "http://engine.test:8090/repos/acme/widgets", body: { childMode: "parallel" } }]);
  await repoAction({ op: "child-mode", repo: "acme/widgets", childMode: "live", docsFirst: true }, "alice", { env, fetch: routes({ "PATCH /repos/acme/widgets": [200, row] }, calls) });
  assert.deepEqual(calls[1].body, { childMode: "live", docsFirst: true });
  const noCall = { env: {}, fetch: () => { throw new Error("no call"); } };
  for (const bad of ["fast", "Serial", undefined, true]) {
    assert.equal((await repoAction({ op: "child-mode", repo: "acme/widgets", childMode: bad }, "alice", noCall)).error, "childMode is serial, parallel or live", String(bad));
  }
  assert.equal((await repoAction({ op: "child-mode", repo: "acme/widgets", childMode: "live", docsFirst: "yes" }, "alice", noCall)).error, "docsFirst is true or false");
});

test("hitlp-surface: the Docs path page sends only a changed hitlpSurface, chained after a changed docsFirst, and the action PATCHes it", async () => {
  const repo = { fullName: "acme/widgets", docsFirst: false, hitlpSurface: false };
  assert.equal(editValues(repo).hitlpSurface, false);
  assert.equal(pageAction("docs", repo, editValues(repo)), null);
  assert.deepEqual(pageAction("docs", repo, { ...editValues(repo), hitlpSurface: true }), { op: "hitlp-surface", repo: "acme/widgets", hitlpSurface: true });
  assert.deepEqual(pageAction("docs", repo, { ...editValues(repo), docsFirst: true, hitlpSurface: true }),
    { op: "docs-first", repo: "acme/widgets", docsFirst: true, then: { op: "hitlp-surface", repo: "acme/widgets", hitlpSurface: true } });
  const sent = [];
  let closed = 0;
  await savePage(async (a) => { sent.push(a); return { ok: true }; }, pageAction("docs", repo, { ...editValues(repo), docsFirst: true, hitlpSurface: true }), () => {}, () => { closed += 1; });
  assert.deepEqual(sent, [{ op: "docs-first", repo: "acme/widgets", docsFirst: true }, { op: "hitlp-surface", repo: "acme/widgets", hitlpSurface: true }]);
  assert.equal(closed, 1);
  assert.equal((await repoAction({ op: "hitlp-surface", repo: "acme/widgets", hitlpSurface: "yes" }, "alice", { env: {}, fetch: () => { throw new Error("no call"); } })).error, "hitlpSurface is true or false");
  const calls = [];
  const r = await repoAction({ op: "hitlp-surface", repo: "acme/widgets", hitlpSurface: true }, "alice",
    { env, fetch: routes({ "PATCH /repos/acme/widgets": [200, { ...REPOS[1], hitlpSurface: true }] }, calls) });
  assert.deepEqual(calls.map((c) => [c.method, c.url, c.body]), [["PATCH", "http://engine.test:8090/repos/acme/widgets", { hitlpSurface: true }]]);
  assert.equal(r.ok, true);
  assert.equal(r.repo.hitlpSurface, true);
  const read = await fetchRepos({ env, fetch: routes({ "GET /repos": [200, [{ ...REPOS[0], hitlpSurface: true }, REPOS[1]]], "GET /issues": [200, { issues: [] }] }) });
  assert.deepEqual(read.repos.map((x) => x.hitlpSurface), [true, false]);
  assert.match(page("docs", { v: editValues(repo) }), /HITLP surface/);
});

test("docs-first: the Docs path page sends only a changed docsFirst, and the action PATCHes it", async () => {
  const repo = { fullName: "acme/widgets", docsFirst: false };
  assert.equal(pageAction("docs", repo, editValues(repo)), null);
  assert.deepEqual(pageAction("docs", repo, { ...editValues(repo), docsFirst: true }), { op: "docs-first", repo: "acme/widgets", docsFirst: true });
  assert.equal((await repoAction({ op: "docs-first", repo: "acme/widgets", docsFirst: "yes" }, "alice", { env: {}, fetch: () => { throw new Error("no call"); } })).error, "docsFirst is true or false");
});
