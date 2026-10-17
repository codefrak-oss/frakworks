// The engine reads that replace `gh` in the app (#239): src/engine-data.js
// fetchNeeds, fetchClosedIssues, fetchTicketDetail. All run against an
// injected fetch; none may reject.
const test = require("node:test");
const assert = require("node:assert/strict");
const { fetchNeeds, fetchClosedIssues, fetchTicketDetail, submitIntake,
  fetchIncidentFixes, fetchDoctorHistory, fetchDoctorRuns, fetchCiRuns, startIncidentFix, startIncidentPropose, cancelFlow, summarizeTicket,
  fetchJevCalls, fetchJevCall, fetchJevSummary, fetchIssues, fetchRepoNames,
  fetchSavedChats, fetchSavedChat, saveChat, deleteSavedChat, requestSetupCheck, fetchRepos,
  setBearer, authHeaders, fetchWhoAmI, getJson, sendJson, fetchProcessXml, fetchRepoFile, engineAccessAction, registerJob } = require("../src/engine-data");

const env = { ENGINE_URL: "http://engine.test:8090/" };

/** A fake fetch answering `status` with `body` (a string is sent as-is), recording every URL. */
function fake(status, body) {
  const calls = [];
  const fetch = async (url) => {
    calls.push(url);
    const text = typeof body === "string" ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text };
  };
  return { calls, opts: { env, fetch } };
}

const need = { repo: "o/r", number: 3, title: "t", kind: "question", wanted: "an answer", since: null, actionId: "a1" };

// ---- fetchNeeds -------------------------------------------------------------

test("fetchNeeds calls GET /needs and resolves to its rows", async () => {
  const f = fake(200, [need]);
  const r = await fetchNeeds(f.opts);
  assert.deepEqual(f.calls, ["http://engine.test:8090/needs"]);
  assert.deepEqual(r.rows, [need]);
  assert.equal(r.error, null);
});

test("fetchNeeds: a body that is not a list is an error, with no rows", async () => {
  const r = await fetchNeeds(fake(200, { rows: [need] }).opts);
  assert.match(r.error, /not a list/);
  assert.deepEqual(r.rows, []);
});

test("fetchNeeds: non-2xx and non-JSON are errors, with no rows", async () => {
  const a = await fetchNeeds(fake(500, "boom").opts);
  assert.match(a.error, /engine answered 500 for \/needs: boom/);
  assert.deepEqual(a.rows, []);
  const b = await fetchNeeds(fake(200, "<html>").opts);
  assert.match(b.error, /no JSON/);
  assert.deepEqual(b.rows, []);
});

test("fetchNeeds never rejects when the engine is unreachable", async () => {
  const r = await fetchNeeds({ env, fetch: async () => { throw new Error("ECONNREFUSED"); } });
  assert.match(r.error, /unreachable.*ECONNREFUSED/);
  assert.deepEqual(r.rows, []);
});

// ---- fetchClosedIssues ------------------------------------------------------

const closed = { repo: "o/r", number: 9, title: "done", url: "https://github.com/o/r/issues/9", labels: [], closedAt: "2026-09-01T00:00:00Z", stateReason: "completed", closedBy: [] };

test("fetchClosedIssues builds the path: state=closed, clamped limit, optional repo", async () => {
  const cases = [
    [{}, "/issues?state=closed&limit=100"],
    [{ limit: 20 }, "/issues?state=closed&limit=20"],
    [{ limit: 0 }, "/issues?state=closed&limit=1"],
    [{ limit: -5 }, "/issues?state=closed&limit=1"],
    [{ limit: 9999 }, "/issues?state=closed&limit=500"],
    [{ limit: "abc" }, "/issues?state=closed&limit=100"],
    [{ limit: 7, repo: "example-org/example-repo" }, "/issues?state=closed&limit=7&repo=example-org%2Fexample-repo"],
  ];
  for (const [args, want] of cases) {
    const f = fake(200, { issues: [closed], failures: ["x/y: boom"] });
    const r = await fetchClosedIssues(args, f.opts);
    assert.deepEqual(f.calls, [`http://engine.test:8090${want}`], JSON.stringify(args));
    assert.deepEqual(r.issues, [closed]);
    assert.deepEqual(r.failures, ["x/y: boom"]);
    assert.equal(r.error, null);
  }
});

test("fetchClosedIssues rejects a bad repo before any request", async () => {
  for (const repo of ["frakworks", "o/r/x", "o/r?state=open", "../x", "o/..", 42, ""]) {
    const f = fake(200, { issues: [] });
    const r = await fetchClosedIssues({ repo }, f.opts);
    assert.match(r.error, /not an owner\/name repo/, JSON.stringify(repo));
    assert.deepEqual(f.calls, []);
  }
});

test("fetchClosedIssues: 400, non-JSON and a body without `issues` are errors", async () => {
  const a = await fetchClosedIssues({ repo: "o/r" }, fake(400, '{"error":"not a target repo"}').opts);
  assert.match(a.error, /engine answered 400/);
  const b = await fetchClosedIssues({}, fake(200, "nope").opts);
  assert.match(b.error, /no JSON/);
  const c = await fetchClosedIssues({}, fake(200, [closed]).opts);
  assert.match(c.error, /no `issues` list/);
  const d = await fetchClosedIssues({}, fake(200, { issues: [] }).opts);
  assert.deepEqual(d, { issues: [], failures: [], error: null });
});

// ---- fetchIssues and fetchNeeds: the repo switcher's ?repo= (#577) ---------

test("fetchIssues and fetchNeeds send ?repo= for one repo and no filter for All repos", async () => {
  for (const [fn, body, path] of [[fetchIssues, { issues: [], failures: [] }, "/issues"], [fetchNeeds, [], "/needs"]]) {
    for (const [repo, want] of [
      [undefined, path], [null, path], ["example-org/example-repo", `${path}?repo=example-org%2Fexample-repo`],
    ]) {
      const f = fake(200, body);
      const r = await fn({ ...f.opts, repo });
      assert.deepEqual(f.calls, [`http://engine.test:8090${want}`], `${path} ${JSON.stringify(repo)}`);
      assert.equal(r.error, null);
    }
  }
});

test("fetchIssues and fetchNeeds refuse a malformed repo before any request", async () => {
  for (const fn of [fetchIssues, fetchNeeds]) {
    for (const repo of ["frakworks", "o/r/x", "o/r?state=open", "../x", "o/..", 42, ""]) {
      const f = fake(200, []);
      const r = await fn({ ...f.opts, repo });
      assert.match(r.error, /not an owner\/name repo/, JSON.stringify(repo));
      assert.deepEqual(f.calls, []);
    }
  }
});

// ---- fetchRepoNames: the switcher's list and its needs badges (#577) -------

/** A fake fetch answering per path: `routes[path]` is [status, body]. */
function routed(routes) {
  const calls = [];
  const fetch = async (url) => {
    const p = new URL(url).pathname;
    calls.push(p);
    const [status, body] = routes[p] || [404, "no route"];
    const text = typeof body === "string" ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text };
  };
  return { calls, opts: { env, fetch } };
}

const repoRows = [{ fullName: "example-org/example-repo" }, { fullName: "codefrak-oss/Other" }, { fullName: "bad" }, {}];

test("fetchRepoNames counts needs per repo, matching repos case-insensitively", async () => {
  const f = routed({
    "/repos": [200, repoRows],
    "/needs": [200, [
      { ...need, repo: "example-org/example-repo" }, { ...need, repo: "EXAMPLE-ORG/EXAMPLE-REPO" },
      { ...need, repo: "codefrak-oss/other" }, { ...need, repo: "not/listed" }, { ...need, repo: 7 },
    ]],
  });
  const r = await fetchRepoNames(f.opts);
  assert.deepEqual(r.repos, ["example-org/example-repo", "codefrak-oss/Other"]);
  assert.deepEqual(r.needs, { "example-org/example-repo": 2, "codefrak-oss/Other": 1 });
  assert.equal(r.needsError, null);
  assert.equal(r.error, null);
  assert.deepEqual([...f.calls].sort(), ["/needs", "/repos"]);
});

test("fetchRepoNames still lists the repos, without badges, when /needs fails", async () => {
  for (const needsAnswer of [[500, "boom"], [200, { rows: [] }]]) {
    const r = await fetchRepoNames(routed({ "/repos": [200, repoRows], "/needs": needsAnswer }).opts);
    assert.deepEqual(r.repos, ["example-org/example-repo", "codefrak-oss/Other"]);
    assert.deepEqual(r.needs, {});
    assert.ok(r.needsError);
    assert.equal(r.error, null);
  }
});

test("fetchRepoNames with needs: false reads only /repos (a repo pick, #1693)", async () => {
  const f = routed({ "/repos": [200, repoRows], "/needs": [200, []] });
  const r = await fetchRepoNames({ ...f.opts, needs: false });
  assert.deepEqual(r.repos, ["example-org/example-repo", "codefrak-oss/Other"]);
  assert.deepEqual(f.calls, ["/repos"]);
});

test("fetchRepoNames: a failing /repos is an error with no list", async () => {
  const a = await fetchRepoNames(routed({ "/repos": [500, "down"], "/needs": [200, []] }).opts);
  assert.match(a.error, /engine answered 500/);
  assert.equal(a.repos, null);
  const b = await fetchRepoNames(routed({ "/repos": [200, { repos: [] }], "/needs": [200, []] }).opts);
  assert.match(b.error, /not a list/);
});

// ---- fetchTicketDetail ------------------------------------------------------

const ticket = {
  repo: "o/r", nodeId: "I_1", number: 12, title: "t", body: "b", bodyHTML: "<p>b</p>", state: "OPEN",
  url: "https://github.com/o/r/issues/12", labels: ["bug"], createdAt: "2026-09-01T00:00:00Z", closedAt: null,
  comments: [{ id: "c1", url: "u", author: "a", createdAt: "x", updatedAt: "x", body: "hi", bodyHTML: "<p>hi</p>" }],
  prs: [{ number: 13, title: "fix", url: "u", state: "OPEN", isDraft: false, reviewDecision: null }],
};

test("fetchTicketDetail calls GET /ticket/<owner>/<repo>/<n> and resolves to the ticket", async () => {
  const f = fake(200, ticket);
  const r = await fetchTicketDetail("o", "r", 12, f.opts);
  assert.deepEqual(f.calls, ["http://engine.test:8090/ticket/o/r/12"]);
  assert.deepEqual(r.ticket, ticket);
  assert.equal(r.error, null);
  const g = fake(200, ticket);
  await fetchTicketDetail("o", "r", "12", g.opts);
  assert.deepEqual(g.calls, ["http://engine.test:8090/ticket/o/r/12"]);
});

test("fetchTicketDetail rejects a bad owner, repo or number before any request", async () => {
  const bad = [["o/x", "r", 1], ["o", "../r", 1], ["", "r", 1], [null, "r", 1], ["o", "r", 0], ["o", "r", -3], ["o", "r", 1.5], ["o", "r", "12abc"], ["o", "r", undefined]];
  for (const [owner, repo, n] of bad) {
    const f = fake(200, ticket);
    const r = await fetchTicketDetail(owner, repo, n, f.opts);
    assert.match(r.error, /^not a (repo owner|repo name|ticket number)/, JSON.stringify([owner, repo, n]));
    assert.deepEqual(f.calls, []);
  }
});

test("fetchTicketDetail maps a 404 to 'no such ticket in a target repo'", async () => {
  const r = await fetchTicketDetail("o", "r", 404, fake(404, '{"error":"no such issue"}').opts);
  assert.match(r.error, /^no such ticket in a target repo: o\/r#404$/);
});

test("fetchTicketDetail: 502, non-JSON and a malformed body are errors", async () => {
  const a = await fetchTicketDetail("o", "r", 1, fake(502, '{"error":"github down"}').opts);
  assert.match(a.error, /engine answered 502.*github down/);
  const b = await fetchTicketDetail("o", "r", 1, fake(200, "<html>").opts);
  assert.match(b.error, /no JSON/);
  for (const body of [[], { title: "no number" }, { number: 1 }, null]) {
    const c = await fetchTicketDetail("o", "r", 1, fake(200, body).opts);
    assert.match(c.error, /has no `number`\/`comments`/, JSON.stringify(body));
  }
});

test("fetchTicketDetail defaults missing prs/labels to empty lists", async () => {
  const { prs, labels, ...bare } = ticket;
  const r = await fetchTicketDetail("o", "r", 12, fake(200, bare).opts);
  assert.deepEqual(r.ticket.prs, []);
  assert.deepEqual(r.ticket.labels, []);
});

test("submitIntake sends authorize only when given a boolean", async () => {
  const bodies = [];
  const fetch = async (url, init) => { bodies.push(JSON.parse(init.body)); return { ok: true, status: 202, text: async () => '{"intakeId":"i"}' }; };
  await submitIntake("do it", "someone", { env, fetch, repo: "acme/widgets", authorize: true });
  await submitIntake("do it", "someone", { env, fetch, repo: "acme/widgets", authorize: false });
  await submitIntake("do it", "someone", { env, fetch, repo: "acme/widgets" });
  assert.equal(bodies[0].authorize, true);
  assert.equal(bodies[1].authorize, false);
  assert.ok(!("authorize" in bodies[2]), "absent when not given, so other callers' requests are unchanged");
});

test("submitIntake always sends repo, and posts nothing without one or with one that is no owner/name", async () => {
  const bodies = [];
  const fetch = async (url, init) => { bodies.push(JSON.parse(init.body)); return { ok: true, status: 202, text: async () => '{"intakeId":"i"}' }; };
  await submitIntake("do it", "someone", { env, fetch, repo: "acme/widgets" });
  assert.equal(bodies[0].repo, "acme/widgets");
  assert.match((await submitIntake("do it", "someone", { env, fetch })).error, /needs the repo/);
  assert.match((await submitIntake("do it", "someone", { env, fetch, repo: "nope" })).error, /owner\/name/);
  assert.equal(bodies.length, 1, "no repo or a bad repo posts nothing");
});

// ---- Fix and Propose a fix --------------------------------------------------

const INCIDENT = "0f3b7b1e-9a1c-11f1-8f5e-0242ac120002";
const TICKET_URL = "https://github.com/o/r/issues/3";

test("fetchIncidentFixes calls GET /camunda/incident-fixes?issueUrl= and passes the answer through", async () => {
  const body = { incident: { id: INCIDENT }, fix: null, propose: { state: "proposed", proposal: "Retry it" } };
  const f = fake(200, body);
  const r = await fetchIncidentFixes(TICKET_URL, f.opts);
  assert.deepEqual(f.calls, [`http://engine.test:8090/camunda/incident-fixes?issueUrl=${encodeURIComponent(TICKET_URL)}`]);
  assert.deepEqual(r.fixes, body);
  assert.equal(r.error, null);
});

test("fetchIncidentFixes: a bad URL never reaches the engine; a 500 or a non-object is an error", async () => {
  const f = fake(200, {});
  assert.match((await fetchIncidentFixes("https://example.com/x", f.opts)).error, /not a GitHub issue URL/);
  assert.deepEqual(f.calls, []);
  assert.match((await fetchIncidentFixes(TICKET_URL, fake(500, "boom").opts)).error, /engine answered 500/);
  assert.match((await fetchIncidentFixes(TICKET_URL, fake(200, "null").opts)).error, /not an object/);
});

test("startIncidentFix and startIncidentPropose POST an empty body (the bearer names the login), and say the engine's 404, 409 and 403", async () => {
  const sent = [];
  const fetch = async (url, init) => { sent.push([url, init.method, JSON.parse(init.body)]); return { ok: true, status: 200, text: async () => "{}" }; };
  assert.deepEqual(await startIncidentFix(INCIDENT, "lundmatt1", { env, fetch }), { ok: true, status: 200 });
  assert.deepEqual(await startIncidentPropose(INCIDENT, "lundmatt1", { env, fetch }), { ok: true, status: 200 });
  assert.deepEqual(sent, [
    [`http://engine.test:8090/camunda/incidents/${INCIDENT}/fix`, "POST", {}],
    [`http://engine.test:8090/camunda/incidents/${INCIDENT}/propose`, "POST", {}],
  ]);
  for (const [status, message] of [[404, "no open incident"], [409, "already going"], [403, "may not fix"]]) {
    const r = await startIncidentFix(INCIDENT, "lundmatt1", fake(status, { message }).opts);
    assert.match(r.error, new RegExp(`engine answered ${status} .*${message}`));
  }
});

test("startIncidentFix: a bad id or no login never reaches the engine", async () => {
  const f = fake(200, {});
  assert.match((await startIncidentFix("../x", "lundmatt1", f.opts)).error, /not an incident id/);
  assert.match((await startIncidentPropose(INCIDENT, null, f.opts)).error, /no GitHub login to propose as/);
  assert.deepEqual(f.calls, []);
});

// ---- cancelFlow ---------------------------------------------------------------

const FLOW = "8352730d-b9af-11f1-81d3-925ef357741a";

test("cancelFlow sends DELETE /intake/<id> with the trimmed reason and no by, and keeps the engine's failed writes", async () => {
  const sent = [];
  const fetch = async (url, init) => {
    sent.push([url, init.method, JSON.parse(init.body)]);
    return { ok: true, status: 200, text: async () => JSON.stringify({ id: FLOW, failedWrites: ["close o/r#5: 502"] }) };
  };
  const r = await cancelFlow(FLOW, "lundmatt1", "  wrong approach ", { env, fetch });
  assert.deepEqual(r, { ok: true, status: 200, failedWrites: ["close o/r#5: 502"] });
  assert.deepEqual(sent, [[`http://engine.test:8090/intake/${FLOW}`, "DELETE", { reason: "wrong approach" }]]);
  const plain = async () => ({ ok: true, status: 200, text: async () => "" });
  assert.deepEqual(await cancelFlow(FLOW, "lundmatt1", "x", { env, fetch: plain }), { ok: true, status: 200, failedWrites: [] });
});

test("cancelFlow refuses without a login, with a blank reason or a bad id, before any request", async () => {
  const { calls, opts } = fake(200, {});
  assert.match((await cancelFlow(FLOW, null, "why", opts)).error, /no GitHub login to cancel as/);
  assert.match((await cancelFlow(FLOW, "bad login!", "why", opts)).error, /no GitHub login/);
  assert.match((await cancelFlow(FLOW, "lundmatt1", "   ", opts)).error, /needs a reason/);
  assert.match((await cancelFlow(FLOW, "lundmatt1", undefined, opts)).error, /needs a reason/);
  assert.match((await cancelFlow("../accounts", "lundmatt1", "why", opts)).error, /not a flow instance id/);
  assert.equal(calls.length, 0);
});

test("cancelFlow says the engine's refusal", async () => {
  const { opts } = fake(403, { message: "someone may not cancel a chore on o/r" });
  assert.match((await cancelFlow(FLOW, "someone", "why", opts)).error, /engine answered 403 for DELETE \/intake\/[^:]+: someone may not cancel a chore/);
});

// ---- Summarize (#423, #881) ---------------------------------------------------

test("summarizeTicket POSTs /ticket/{owner}/{repo}/{n}/summarize and resolves to the summary", async () => {
  const body = { runId: "r1", mode: "work", model: "claude-opus-5-5", summary: "It is storing sessions.", now: "Testing." };
  const methods = [];
  const f = fake(200, body);
  const fetch = async (url, init) => { methods.push(init.method); return f.opts.fetch(url, init); };
  const r = await summarizeTicket("o", "r", 5, { env, fetch });
  assert.deepEqual(f.calls, ["http://engine.test:8090/ticket/o/r/5/summarize"]);
  assert.deepEqual(methods, ["POST"]);
  assert.deepEqual(r, { summary: body, error: null });
});

test("summarizeTicket: the engine's 409 is an error; bad arguments never reach the engine; no summary/now is an error", async () => {
  const a = await summarizeTicket("o", "r", "5", fake(409, '{"error":"no Smith is running on o/r#5"}').opts);
  assert.equal(a.error, "engine answered 409 for POST /ticket/o/r/5/summarize: no Smith is running on o/r#5");
  assert.equal(a.summary, undefined);

  const f = fake(200, {});
  assert.match((await summarizeTicket("../x", "r", 5, f.opts)).error, /not a repo owner/);
  assert.match((await summarizeTicket("o", "r/../s", 5, f.opts)).error, /not a repo name/);
  assert.match((await summarizeTicket("o", "r", 0, f.opts)).error, /not a ticket number/);
  assert.deepEqual(f.calls, []);

  const b = await summarizeTicket("o", "r", 5, fake(200, { summary: "only" }).opts);
  assert.match(b.error, /has no `summary`\/`now`/);
});

// ---- the Jev decisions (#553) -------------------------------------------------

const jevFixture = (f) => JSON.parse(require("node:fs").readFileSync(require("node:path").join(__dirname, "..", f), "utf8"));

test("fetchJevCalls calls GET /jev/calls with each filter encoded, and resolves to the calls and total", async () => {
  const body = jevFixture("fixture-jev-calls.json");
  const f = fake(200, body);
  const r = await fetchJevCalls({ useCase: "ci-triage", repo: "codefrak-oss/frakworks", issue: "553", action: "re-ran the job",
    outcome: "set", since: "2026-09-20T12:00:00Z", limit: 9999, offset: 20 }, f.opts);
  assert.deepEqual(f.calls, ["http://engine.test:8090/jev/calls?use_case=ci-triage&repo=codefrak-oss%2Ffrakworks&issue=553" +
    "&action=re-ran%20the%20job&outcome=set&since=2026-09-20T12%3A00%3A00Z&limit=500&offset=20"]);
  assert.equal(r.total, 3);
  assert.deepEqual(r.calls, body.calls);
  assert.equal(r.error, null);
  const bare = fake(200, body);
  await fetchJevCalls({}, bare.opts);
  assert.deepEqual(bare.calls, ["http://engine.test:8090/jev/calls"], "no filter, no query string");
});

test("fetchJevCalls refuses a bad filter before any request, and a body with no list is an error", async () => {
  const f = fake(200, { calls: [] });
  for (const bad of [{ useCase: "../x" }, { repo: "not a repo" }, { issue: 0 }, { issue: "3a" }, { action: "" }, { since: 5 }, { offset: -1 }, { limit: "many" }]) {
    const r = await fetchJevCalls(bad, f.opts);
    assert.ok(r.error, JSON.stringify(bad));
  }
  assert.deepEqual(f.calls, [], "nothing reached the engine");
  assert.match((await fetchJevCalls({}, fake(200, { rows: [] }).opts)).error, /no `calls` list/);
  assert.match((await fetchJevCalls({}, fake(500, "boom").opts)).error, /500/);
});

test("fetchJevCall calls GET /jev/calls/<id> and resolves to the call; a 404 names the id", async () => {
  const body = jevFixture("fixture-jev-call.json");
  const f = fake(200, body);
  const r = await fetchJevCall(body.id, f.opts);
  assert.deepEqual(f.calls, [`http://engine.test:8090/jev/calls/${body.id}`]);
  assert.equal(r.call.use_case, "refine-pick");
  assert.equal(r.call.picks.length, 3);
  assert.match((await fetchJevCall(body.id, fake(404, { error: "no" }).opts)).error, new RegExp(`no jev call ${body.id}`));
  assert.match((await fetchJevCall(body.id, fake(200, { ...body, id: "other" }).opts)).error, /no matching `id`/);
  const g = fake(200, body);
  assert.match((await fetchJevCall("../../accounts", g.opts)).error, /not a jev call id/);
  assert.deepEqual(g.calls, []);
});

test("fetchJevSummary calls GET /jev/summary with since, and resolves to the summary", async () => {
  const body = jevFixture("fixture-jev-summary.json");
  const f = fake(200, body);
  const r = await fetchJevSummary("2026-09-20T12:00:00.000Z", f.opts);
  assert.deepEqual(f.calls, ["http://engine.test:8090/jev/summary?since=2026-09-20T12%3A00%3A00.000Z"]);
  assert.equal(r.summary.useCases.length, 6);
  const bare = fake(200, body);
  await fetchJevSummary(undefined, bare.opts);
  assert.deepEqual(bare.calls, ["http://engine.test:8090/jev/summary"]);
  assert.match((await fetchJevSummary(undefined, fake(200, { totals: {} }).opts)).error, /no `totals`\/`useCases`/);
  assert.match((await fetchJevSummary(7, fake(200, body).opts)).error, /not a since/);
});

// ---- saved First mate chats (#598) -----------------------------------------

const SAVED = "0b7c9a1e-3f2d-4c5b-9a8e-1d2c3b4a5f60";

test("fetchSavedChats calls GET /saved-chats; an error or a non-list is an error with no chats", async () => {
  const row = { id: SAVED, title: "t", savedAt: "2026-09-27T10:00:00Z", turns: 1, messageCount: 2 };
  const f = fake(200, [row, { title: "no id" }]);
  assert.deepEqual(await fetchSavedChats(f.opts), { chats: [row], error: null });
  assert.deepEqual(f.calls, ["http://engine.test:8090/saved-chats"]);
  assert.deepEqual((await fetchSavedChats(fake(200, { chats: [] }).opts)).chats, []);
  assert.match((await fetchSavedChats(fake(200, {}).opts)).error, /not a list/);
  const down = await fetchSavedChats(fake(502, "bad gateway").opts);
  assert.match(down.error, /engine answered 502/);
  assert.deepEqual(down.chats, []);
});

test("fetchSavedChat reads one chat by UUID, says a 404 plainly, and refuses a bad id before any request", async () => {
  const chat = { id: SAVED, title: "t", messages: [{ role: "user", text: "hi", at: 1 }] };
  const f = fake(200, chat);
  assert.deepEqual(await fetchSavedChat(SAVED, f.opts), { chat, error: null });
  assert.deepEqual(f.calls, [`http://engine.test:8090/saved-chats/${SAVED}`]);
  assert.equal((await fetchSavedChat(SAVED, fake(404, '{"error":"no saved chat"}').opts)).error, `no saved chat ${SAVED}`);
  assert.match((await fetchSavedChat(SAVED, fake(200, { id: SAVED }).opts)).error, /no matching/);
  for (const bad of ["../accounts", "1", "", null, `${SAVED}/x`]) {
    const g = fake(200, chat);
    assert.match((await fetchSavedChat(bad, g.opts)).error, /^not a saved chat id/);
    assert.deepEqual(g.calls, []);
  }
});

test("saveChat PUTs the body; 201 is created, 200 an update, and the engine's 400 is said back", async () => {
  const sent = [];
  const answer = (status) => async (url, init) => {
    sent.push([url, init.method, JSON.parse(init.body)]);
    return { ok: status < 300, status, text: async () => (status < 300 ? JSON.stringify({ id: SAVED, title: "t" }) : '{"error":"title is required"}') };
  };
  const body = { title: "t", messages: [{ role: "user", text: "hi", at: 1 }] };
  assert.deepEqual(await saveChat(SAVED, body, { env, fetch: answer(201) }), { ok: true, created: true, chat: { id: SAVED, title: "t" } });
  assert.equal((await saveChat(SAVED, body, { env, fetch: answer(200) })).created, false);
  assert.deepEqual(sent[0], [`http://engine.test:8090/saved-chats/${SAVED}`, "PUT", body]);
  assert.match((await saveChat(SAVED, body, { env, fetch: answer(400) })).error, /engine answered 400 for PUT \/saved-chats\/[^:]+: title is required/);
  const { calls, opts } = fake(201, {});
  assert.match((await saveChat("nope", body, opts)).error, /^not a saved chat id/);
  assert.equal(calls.length, 0);
});

test("deleteSavedChat sends DELETE /saved-chats/<id>; a 404 is an error; a bad id never reaches the engine", async () => {
  const sent = [];
  const fetch = (status) => async (url, init) => { sent.push([url, init.method]); return { ok: status < 300, status, text: async () => "" }; };
  assert.deepEqual(await deleteSavedChat(SAVED, { env, fetch: fetch(204) }), { ok: true, status: 204 });
  assert.deepEqual(sent, [[`http://engine.test:8090/saved-chats/${SAVED}`, "DELETE"]]);
  assert.match((await deleteSavedChat(SAVED, { env, fetch: fetch(404) })).error, /engine answered 404/);
  const { calls, opts } = fake(204, "");
  assert.match((await deleteSavedChat("x/../y", opts)).error, /^not a saved chat id/);
  assert.equal(calls.length, 0);
});

// ---- Doctor history ---------------------------------------------------------

test("fetchDoctorHistory calls GET /doctor/history?issueUrl= and resolves to its runs", async () => {
  const runs = [{ id: 1, outcome: "applied" }];
  const f = fake(200, { issueUrl: TICKET_URL, runs });
  const r = await fetchDoctorHistory(TICKET_URL, f.opts);
  assert.deepEqual(f.calls, [`http://engine.test:8090/doctor/history?issueUrl=${encodeURIComponent(TICKET_URL)}`]);
  assert.deepEqual(r.runs, runs);
  assert.equal(r.error, null);
});

test("fetchDoctorHistory: a bad URL never reaches the engine; a 400 or an answer without runs is an error", async () => {
  const f = fake(200, { runs: [] });
  assert.match((await fetchDoctorHistory("https://example.com/x", f.opts)).error, /not a GitHub issue URL/);
  assert.deepEqual(f.calls, []);
  assert.match((await fetchDoctorHistory(TICKET_URL, fake(400, "bad url").opts)).error, /engine answered 400/);
  assert.match((await fetchDoctorHistory(TICKET_URL, fake(200, { issueUrl: TICKET_URL }).opts)).error, /no runs list/);
});

test("fetchDoctorRuns calls GET /doctor/runs?since= and resolves to the runs and since (#706)", async () => {
  const runs = [{ id: "d-1", outcome: "applied" }];
  const since = "2026-09-21T12:00:00.000Z";
  const f = fake(200, { since: "2026-09-21T12:00Z", runs });
  const r = await fetchDoctorRuns(since, f.opts);
  assert.deepEqual(f.calls, [`http://engine.test:8090/doctor/runs?since=${encodeURIComponent(since)}`]);
  assert.deepEqual(r.runs, runs);
  assert.equal(r.since, "2026-09-21T12:00Z");
  assert.equal(r.error, null);
});

test("fetchDoctorRuns: a bad since never reaches the engine; a 404 is the older-engine line; other failures are one error; never rejects", async () => {
  const f = fake(200, { runs: [] });
  for (const bad of [undefined, null, "", "yesterday", "2026-09-21", "2026-09-21T00:00:00Z&x=1", 42]) {
    assert.match((await fetchDoctorRuns(bad, f.opts)).error, /not an ISO instant/, String(bad));
  }
  assert.deepEqual(f.calls, []);
  const since = "2026-09-21T00:00:00Z";
  assert.equal((await fetchDoctorRuns(since, fake(404, "").opts)).error, "this engine has no /doctor/runs (older than #706)");
  assert.match((await fetchDoctorRuns(since, fake(500, "boom").opts)).error, /engine answered 500 for \/doctor\/runs/);
  assert.match((await fetchDoctorRuns(since, fake(200, { since }).opts)).error, /no runs list/);
  assert.match((await fetchDoctorRuns(since, fake(200, "not json").opts)).error, /no JSON/);
  const down = await fetchDoctorRuns(since, { env, fetch: async () => { throw new Error("ECONNREFUSED"); } });
  assert.match(down.error, /engine unreachable/);
});

test("fetchCiRuns calls GET /repos/{owner}/{name}/ci/runs?limit= and resolves to the repo, runs and summary (#1935)", async () => {
  const runs = [{ id: 1, area: "engine", trigger: "pr" }];
  const f = fake(200, { repo: "acme/widgets", runs, summary: { count: 1 } });
  const r = await fetchCiRuns("acme/widgets", 50, null, null, f.opts);
  assert.deepEqual(f.calls, ["http://engine.test:8090/repos/acme/widgets/ci/runs?limit=50"]);
  assert.deepEqual(r.runs, runs);
  assert.equal(r.repo, "acme/widgets");
  assert.deepEqual(r.summary, { count: 1 });
  assert.equal(r.error, null);
  const g = fake(200, { runs: [] });
  await fetchCiRuns("acme/widgets", 25, "engine.yml", "main", g.opts);
  assert.deepEqual(g.calls, ["http://engine.test:8090/repos/acme/widgets/ci/runs?limit=25&workflow=engine.yml&branch=main"]);
});

test("fetchCiRuns: a bad repo or limit never reaches the engine; a 404 is the older-engine line; other failures are one error; never rejects", async () => {
  const f = fake(200, { runs: [] });
  for (const bad of [undefined, null, "", "acme", "acme/widgets/x", "../x/y", "acme/wid gets", 42]) {
    assert.match((await fetchCiRuns(bad, 50, null, null, f.opts)).error, /not a repo/, String(bad));
  }
  for (const bad of [undefined, null, 0, 101, 2.5, "50", NaN]) {
    assert.match((await fetchCiRuns("acme/widgets", bad, null, null, f.opts)).error, /not a limit/, String(bad));
  }
  assert.deepEqual(f.calls, []);
  assert.equal((await fetchCiRuns("acme/widgets", 50, null, null, fake(404, "").opts)).error,
    "this engine has no /repos/{owner}/{name}/ci/runs (older than this ticket)");
  assert.match((await fetchCiRuns("acme/widgets", 50, null, null, fake(500, "boom").opts)).error, /engine answered 500/);
  assert.match((await fetchCiRuns("acme/widgets", 50, null, null, fake(200, { repo: "acme/widgets" }).opts)).error, /no runs list/);
  const down = await fetchCiRuns("acme/widgets", 50, null, null, { env, fetch: async () => { throw new Error("ECONNREFUSED"); } });
  assert.match(down.error, /engine unreachable/);
});

// ---- requestSetupCheck and fetchRepos (#965) -----------------------------------

/** A fake fetch recording method, URL and body, answering by `METHOD /path`; an Error is thrown as a network failure. */
function posted(table) {
  const calls = [];
  const fetch = async (url, init = {}) => {
    const method = init.method || "GET";
    calls.push({ method, url, body: init.body ? JSON.parse(init.body) : undefined });
    const r = table[`${method} ${new URL(url).pathname}`];
    if (r instanceof Error) throw r;
    if (!r) return { ok: false, status: 404, text: async () => "" };
    const [status, body] = r;
    return { ok: status >= 200 && status < 300, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body ?? "")) };
  };
  return { calls, opts: { env, fetch } };
}

test("requestSetupCheck POSTs /repos/setup/check and resolves to the 202's queued and fresh", async () => {
  const f = posted({ "POST /repos/setup/check": [202, { queued: ["acme/widgets"], fresh: ["o/r"] }] });
  assert.deepEqual(await requestSetupCheck("*", {}, f.opts), { queued: ["acme/widgets"], fresh: ["o/r"] });
  assert.deepEqual(f.calls[0], { method: "POST", url: "http://engine.test:8090/repos/setup/check", body: { repo: "*" } });
  await requestSetupCheck("acme/widgets", { force: true }, f.opts);
  assert.deepEqual(f.calls[1].body, { repo: "acme/widgets", force: true });
});

test("requestSetupCheck: a 400 and a 404 are said, a network error too; a bad repo sends nothing; it never rejects", async () => {
  const bad = posted({ "POST /repos/setup/check": [400, { message: "repo is required: a target repo (acme/widgets) or * for every repo" }] });
  assert.match((await requestSetupCheck("*", {}, bad.opts)).error, /400.*repo is required/);
  const gone = posted({ "POST /repos/setup/check": [404, { message: "acme/gone is not a target repo" }] });
  assert.equal((await requestSetupCheck("acme/gone", {}, gone.opts)).error, "acme/gone is not a target repo");
  const down = posted({ "POST /repos/setup/check": new Error("ECONNREFUSED") });
  assert.match((await requestSetupCheck("*", {}, down.opts)).error, /unreachable.*ECONNREFUSED/);
  const none = posted({});
  assert.match((await requestSetupCheck("../etc", {}, none.opts)).error, /not an owner\/name repo or \*/);
  assert.equal(none.calls.length, 0);
});

test("fetchRepos makes no /setup request: each row's setup is null until a setup frame fills it", async () => {
  const f = posted({ "GET /repos": [200, [{ fullName: "acme/widgets" }, { fullName: "o/r" }]], "GET /issues": [200, { issues: [] }] });
  const r = await fetchRepos(f.opts);
  assert.equal(r.error, null);
  assert.deepEqual(r.repos.map((x) => [x.setup, x.setupError]), [[null, null], [null, null]]);
  assert.deepEqual(f.calls.map((c) => new URL(c.url).pathname).sort(), ["/issues", "/repos"]);
});

test("accountAction's email op PUTs the trimmed note, clears on empty, and refuses a bad name", async () => {
  const { accountAction } = require("../src/engine-data");
  const calls = [];
  const fetch = async (url, init) => { calls.push([url, init.method, JSON.parse(init.body)]); return { ok: true, status: 204, text: async () => "" }; };
  assert.deepEqual(await accountAction({ op: "email", name: "max-1", email: "  me@example.com " }, { env, fetch }), { ok: true, status: 204 });
  await accountAction({ op: "email", name: "max-1", email: "  " }, { env, fetch });
  assert.deepEqual(calls, [
    ["http://engine.test:8090/accounts/max-1/email", "PUT", { email: "me@example.com" }],
    ["http://engine.test:8090/accounts/max-1/email", "PUT", { email: null }],
  ]);
  assert.match((await accountAction({ op: "email", name: "../x", email: "a" }, { env, fetch })).error, /name is lowercase/);
  assert.match((await accountAction({ op: "email", name: "max-1", email: "x".repeat(201) }, { env, fetch })).error, /at most 200/);
  await accountAction({ op: "add", name: "max-2", provider: "anthropic", email: " a@b " }, { env, fetch });
  assert.deepEqual(calls.at(-1), ["http://engine.test:8090/accounts", "POST", { name: "max-2", provider: "anthropic", email: "a@b" }]);
  assert.equal(calls.length, 3);
});

test("accountAction's rename op PUTs the trimmed new name to /name and refuses a bad one before a request", async () => {
  const { accountAction } = require("../src/engine-data");
  const calls = [];
  const fetch = async (url, init) => { calls.push([url, init.method, JSON.parse(init.body)]); return { ok: true, status: 204, text: async () => "" }; };
  assert.deepEqual(await accountAction({ op: "rename", name: "anthropic-3", to: " anthropic-3-frank " }, { env, fetch }), { ok: true, status: 204 });
  assert.deepEqual(calls, [["http://engine.test:8090/accounts/anthropic-3/name", "PUT", { name: "anthropic-3-frank" }]]);
  assert.match((await accountAction({ op: "rename", name: "anthropic-3", to: "Bad Name" }, { env, fetch })).error, /new name is lowercase/);
  assert.match((await accountAction({ op: "rename", name: "anthropic-3", to: "a".repeat(101) }, { env, fetch })).error, /at most 100/);
  assert.equal(calls.length, 1);
});

// ---- the bearer token (#2206) ----------------------------------------------

const TOKEN = "frk_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

/** A fake fetch recording each request's headers, answering `status` with `body`. */
function recording(status, body, { xml = false } = {}) {
  const requests = [];
  const fetch = async (url, init) => {
    requests.push({ url, headers: (init && init.headers) || {}, method: (init && init.method) || "GET" });
    const text = typeof body === "string" ? body : JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, text: async () => text, arrayBuffer: async () => new TextEncoder().encode(text).buffer, headers: { get: () => (xml ? "application/xml" : "application/json") } };
  };
  return { requests, opts: { env, fetch } };
}

test("authHeaders is empty until setBearer answers a value, and never throws", () => {
  setBearer(null);
  assert.deepEqual(authHeaders(), {});
  setBearer(() => { throw new Error("boom"); });
  assert.deepEqual(authHeaders(), {});
  setBearer(() => `Bearer ${TOKEN}`);
  assert.deepEqual(authHeaders(), { authorization: `Bearer ${TOKEN}` });
  setBearer(() => null);
  assert.deepEqual(authHeaders(), {});
});

test("signed in, every read and write carries Authorization: Bearer; signed out, none does", async () => {
  const calls = async () => {
    const out = [];
    let f = recording(200, { rows: [] }); await getJson("/needs", f.opts); out.push(["getJson", f.requests[0]]);
    f = recording(200, {}); await sendJson("POST", "/x", { a: 1 }, f.opts); out.push(["sendJson", f.requests[0]]);
    f = recording(202, { intakeId: "i1" }); await submitIntake("an ask", "someone", { ...f.opts, repo: "o/r" }); out.push(["submitIntake", f.requests[0]]);
    f = recording(200, "<bpmn:definitions>", { xml: true }); await fetchProcessXml("work:3:0123456789ab", f.opts); out.push(["fetchProcessXml", f.requests[0]]);
    f = recording(200, "hello"); await fetchRepoFile("o", "r", "README.md", null, f.opts); out.push(["fetchRepoFile", f.requests[0]]);
    f = recording(200, {}); await engineAccessAction({ op: "remove", login: "x" }, "someone", f.opts); out.push(["engineAccess remove", f.requests[0]]);
    return out;
  };
  setBearer(() => `Bearer ${TOKEN}`);
  try {
    for (const [what, req] of await calls()) {
      assert.equal(req.headers.authorization, `Bearer ${TOKEN}`, `${what} carries the bearer`);
    }
  } finally { setBearer(null); }
  for (const [what, req] of await calls()) {
    assert.equal(req.headers.authorization, undefined, `${what} carries no Authorization when signed out`);
  }
});

test("sendJson's caller headers ride beside the bearer, and a job write names no X-Frakworks-Login", async () => {
  setBearer(() => `Bearer ${TOKEN}`);
  try {
    const f = recording(201, { id: "j1" });
    await registerJob({ name: "n", kind: "prompt", repo: "o/r", config: { prompt: "p" }, signal: { kind: "execute" } }, "someone", f.opts);
    assert.equal(f.requests[0].headers.authorization, `Bearer ${TOKEN}`);
    assert.equal(f.requests[0].headers["X-Frakworks-Login"], undefined);
    const g = recording(200, {});
    await sendJson("POST", "/x", {}, { ...g.opts, headers: { "X-Extra": "1" } });
    assert.equal(g.requests[0].headers.authorization, `Bearer ${TOKEN}`);
    assert.equal(g.requests[0].headers["X-Extra"], "1");
  } finally { setBearer(null); }
});

test("fetchWhoAmI sends the given token's header and parses {login, tokenId, name}; a 401 is the engine's text, never the token", async () => {
  const f = recording(200, { login: "someone", tokenId: "t-1", name: "desktop" });
  const r = await fetchWhoAmI(TOKEN, f.opts);
  assert.deepEqual(r, { login: "someone", tokenId: "t-1", name: "desktop" });
  assert.deepEqual(f.requests.map((q) => q.url), ["http://engine.test:8090/whoami"]);
  assert.equal(f.requests[0].headers.authorization, `Bearer ${TOKEN}`);
  const no = await fetchWhoAmI(TOKEN, recording(401, { error: "no such token" }).opts);
  assert.deepEqual(no, { error: "engine answered 401 for GET /whoami: no such token", status: 401 });
  const forbidden = await fetchWhoAmI(TOKEN, recording(403, "@someone is not a trusted login").opts);
  assert.deepEqual(forbidden, { error: "engine answered 403 for GET /whoami: @someone is not a trusted login", status: 403 });
  const bad = await fetchWhoAmI(TOKEN, recording(200, { name: "x" }).opts);
  assert.match(bad.error, /named no login/);
  const down = await fetchWhoAmI(TOKEN, { env, fetch: async () => { throw new Error("ECONNREFUSED"); } });
  assert.match(down.error, /engine unreachable at http:\/\/engine.test:8090\/whoami: ECONNREFUSED/);
  for (const r2 of [no, forbidden, bad, down]) assert.ok(!JSON.stringify(r2).includes(TOKEN));
  assert.match((await fetchWhoAmI("", f.opts)).error, /no token to verify/);
});

test("a 401 from the engine is the Sign in prompt, on a read and on a write: an unsigned desktop acts as no login", async () => {
  const { SIGN_IN_PROMPT } = require("../src/engine-data");
  let f = recording(401, { error: "no such token" });
  const read = await getJson("/needs", f.opts);
  assert.ok(read.error.startsWith(SIGN_IN_PROMPT), read.error);
  f = recording(401, { error: "no such token" });
  const write = await sendJson("POST", "/access", { login: "grace" }, f.opts);
  assert.ok(write.error.startsWith(SIGN_IN_PROMPT), write.error);
  assert.equal(f.requests[0].headers["X-Frakworks-Login"], undefined);
});
