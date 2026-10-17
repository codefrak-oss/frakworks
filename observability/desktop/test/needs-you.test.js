// The "needs you" view: the engine's GET /needs list (fixture-needs-you.json
// covers every kind, an incident with no issue, a row in another repo, and
// three ask/approve rows the engine no longer serves, which toRows ignores)
// and the HITLP server's surface tasks, which HITLP Requests draws and Needs
// you no longer does (#100064) (test/fixture-hitlp-tasks.json, HITLP
// 0.2, #2487: an ask with options, an ask with a string schema for another
// login, and an approve with a recommendation), turned into rows by
// src/needs-you.js surfaceRows() and toRows(), and drawn by needs-you.jsx.
// Every way the engine can fail must draw the .err block, not a blank view.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { toRows, surfaceRows, personOf, lineageOf, fetchNeeds, fetchHitlpTasks, KIND_ORDER } = require("../src/needs-you");
const { NeedsYou, Row, Countdown, fmtWaited, fmtDeadline, buttonsFor, pressOf, parseTyped, KIND_TITLES, HITLP_KINDS,
  byPriority, needsTicketCount } = require("../dist/needs-you.cjs");

const tasks = JSON.parse(fs.readFileSync(path.join(__dirname, "fixture-hitlp-tasks.json"), "utf8"));
const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-needs-you.json"), "utf8"));
// A proposal's decision (#555), after the acceptance in the ordering.
fixture.push({ repo: "codefrak-oss/frakworks", number: 908, title: "Proposal: tiers", kind: "decision",
  wanted: "Decide the proposal", since: "2026-09-24T21:00:00Z", actionId: "d-908" });
// A ticket an intake filed unlabeled for human triage (#730), after the incidents.
fixture.push({ repo: "codefrak-oss/frakworks", number: 909, title: "Sign the mac build", kind: "insufficient-permissions",
  wanted: "Needs a mac code-signing secret", since: "2026-09-24T20:00:00Z",
  actionId: "https://github.com/codefrak-oss/frakworks/issues/909" });
const NOW = Math.floor(Date.parse("2026-09-25T00:00:00Z") / 1000);
const rows = toRows(fixture, NOW);
const byKey = new Map([...surfaceRows(tasks, NOW), ...rows].map((r) => [r.key, r]));
const render = (props) => renderToStaticMarkup(React.createElement(NeedsYou, props));
const answering = (status, body) => async () => ({ ok: status < 300, status, text: async () => body });

test("every kind of Need becomes a row, one per Need; GET /needs' ask and approve are ignored (#2487)", () => {
  const engineHitlp = fixture.filter((n) => n.kind === "ask" || n.kind === "approve");
  assert.equal(engineHitlp.length, 3);
  assert.equal(toRows(engineHitlp, NOW).length, 0);
  assert.equal(rows.length, fixture.length - engineHitlp.length);
  assert.deepEqual([...new Set(rows.map((r) => r.kind))], KIND_ORDER);
  assert.equal(new Set(rows.map((r) => r.key)).size, rows.length, "keys are unique");
  const r = byKey.get("questionq-11");
  assert.deepEqual(r, {
    ticket: 901, repo: "codefrak-oss/frakworks", title: "Keycloak scope for the tickets MCP",
    url: "https://github.com/codefrak-oss/frakworks/issues/901", kind: "question",
    ask: "Which Keycloak client should carry the tickets scope?", waited: 14 * 3600,
    source: "question", actionId: "q-11", key: "questionq-11", question: null, for: "lundmatt1",
  });
});

test("a row carries the engine's full recorded question through as `question`, and null without one", () => {
  const full = "1. Which store?\n2. Keep the old API?\n\n```ask\nkind: choice\noptions:\n  - id: pg\n    label: Postgres\n```";
  const [asked, bare, incident] = toRows([
    { repo: "codefrak-oss/frakworks", number: 681, title: "t", kind: "question", wanted: "answer: 1. Which store?",
      since: "2026-09-24T10:00:00Z", actionId: "abc", question: full },
    { repo: "codefrak-oss/frakworks", number: 682, title: "t", kind: "question", wanted: "answer the question on the issue",
      since: "2026-09-24T11:00:00Z", actionId: "def", question: null },
    { repo: null, number: null, title: "", kind: "incident", wanted: "retry or cancel: x", since: null, actionId: "inc" },
  ], NOW);
  assert.equal(asked.question, full);
  assert.equal(asked.ask, "answer: 1. Which store?", "`ask` stays the one-line wanted");
  assert.equal(bare.question, null);
  assert.equal(incident.question, null, "a row the engine sends without the field");
});

test("rows are ordered by kind, then longest wait first", () => {
  assert.deepEqual(rows.map((r) => r.key), [
    "questionq-12", "questionq-11",
    "acceptance4d1c2b3a-0000-4000-8000-000000000904",
    "decisiond-908",
    "incidentinc-chore", "incidentinc-905",
    "insufficient-permissionshttps://github.com/codefrak-oss/frakworks/issues/909",
    "reauthorize906", "authorize907",
  ]);
});

test("waited is seconds since `since`, and 0 when the engine has no time", () => {
  assert.equal(byKey.get("questionq-12").waited, NOW - Math.floor(Date.parse("2026-09-22T10:00:00Z") / 1000));
  assert.equal(byKey.get("authorize907").waited, 0);
  assert.equal(fmtWaited(byKey.get("questionq-12").waited), "2d 14h");
});

test("an insufficient-permissions row asks the blocker and links its ticket", () => {
  const r = byKey.get("insufficient-permissionshttps://github.com/codefrak-oss/frakworks/issues/909");
  assert.equal(r.ask, "Needs a mac code-signing secret");
  assert.equal(r.url, "https://github.com/codefrak-oss/frakworks/issues/909");
  assert.equal(KIND_ORDER.indexOf("insufficient-permissions"), KIND_ORDER.indexOf("incident") + 1);
  const html = render({ result: { error: null, repo: "codefrak-oss/frakworks", rows: [r] } });
  assert.match(html, /Can(&#x27;|')t do with current permissions/);
  assert.match(html, /Needs a mac code-signing secret/);
  assert.match(html, /href="https:\/\/github.com\/codefrak-oss\/frakworks\/issues\/909"/);
  assert.match(html, />#909<\/a>/);
});

test("an incident with no issue has no ticket and no link", () => {
  const r = byKey.get("incidentinc-chore");
  assert.equal(r.ticket, null);
  assert.equal(r.repo, null);
  assert.equal(r.url, null);
  const html = renderToStaticMarkup(React.createElement(Row, { row: r, onOpenTicket: () => {} }));
  assert.doesNotMatch(html, /href=/);
  assert.doesNotMatch(html, /nmPin/);
});

test("the view draws one section per kind, a link per ticket, repo#n outside the selected repo", () => {
  const html = render({ result: { error: null, repo: "codefrak-oss/frakworks", rows } });
  for (const k of KIND_ORDER) assert.ok(html.includes(KIND_TITLES[k].replace(/'/g, "&#x27;")), `section for ${k}`);
  assert.equal((html.match(/class="nmRow( nmHitlp nmOpenable)?"/g) || []).length, rows.length);
  assert.match(html, />#901<\/a>/);
  assert.match(html, />codefrak-oss\/other-repo#17<\/a>/);
  assert.match(html, /href="https:\/\/github.com\/codefrak-oss\/other-repo\/issues\/17"/);
  // no pin control: pinning to a chat went with the Chats tab (#634)
  assert.doesNotMatch(html, /nmPin/);
  // All repos: every row carries its repo
  const all = render({ result: { error: null, repo: null, rows } });
  assert.match(all, />codefrak-oss\/frakworks#901<\/a>/);
  assert.doesNotMatch(all, />#901<\/a>/);
});

test("a plain click on a row opens the in-app detail in that row's repo; a modified click keeps the GitHub link", () => {
  const opened = [];
  const local = Row({ row: byKey.get("questionq-11"), repo: "codefrak-oss/frakworks", onOpenTicket: (r, n) => opened.push([r, n]) });
  const a = local.props.children[0];
  let prevented = false;
  a.props.onClick({ button: 0, preventDefault: () => { prevented = true; } });
  assert.equal(prevented, true);
  assert.deepEqual(opened, [["codefrak-oss/frakworks", 901]]);
  a.props.onClick({ button: 0, metaKey: true, preventDefault: () => { throw new Error("must not prevent"); } });
  assert.equal(opened.length, 1);
  const other = Row({ row: byKey.get("questionq-12"), repo: "codefrak-oss/frakworks", onOpenTicket: (r, n) => opened.push([r, n]) });
  other.props.children[0].props.onClick({ button: 0, preventDefault: () => {} });
  assert.deepEqual(opened[1], ["codefrak-oss/other-repo", 17]);
});

test("fetchNeeds reads GET /needs for every repo and keeps the selected repo's rows (#2423), all of them in All repos", async () => {
  const seen = [];
  const r = await fetchNeeds({
    repo: "codefrak-oss/frakworks", now: NOW, env: { ENGINE_URL: "http://engine:8090" },
    fetch: async (url, init) => { seen.push(url); return answering(200, JSON.stringify(fixture))(url, init); },
  });
  assert.deepEqual(seen, ["http://engine:8090/needs"]);
  assert.equal(r.error, null);
  assert.equal(r.repo, "codefrak-oss/frakworks");
  assert.deepEqual(r.rows, rows.filter((x) => !HITLP_KINDS.includes(x.kind) && x.repo === "codefrak-oss/frakworks"),
    "no surface given: the engine's rows alone");
  const all = await fetchNeeds({
    now: NOW, env: { ENGINE_URL: "http://engine:8090" },
    fetch: async (url, init) => { seen.push(url); return answering(200, JSON.stringify(fixture))(url, init); },
  });
  assert.equal(seen[1], "http://engine:8090/needs");
  assert.equal(all.repo, null);
});

test("fetchNeeds is the engine's rows only; fetchHitlpTasks reads the surface once: open and closed, its error, or not set (#100064)", async () => {
  const env = { ENGINE_URL: "http://engine:8090" };
  const fetch = answering(200, JSON.stringify(fixture));
  const r = await fetchNeeds({ repo: "codefrak-oss/frakworks", now: NOW, env, fetch, surface: async () => ({ tasks }) });
  assert.equal(r.error, null);
  assert.equal(r.rows.filter((x) => HITLP_KINDS.includes(x.kind)).length, 0, "no HITLP row in Needs you, even with a surface");
  const h = await fetchHitlpTasks({ surface: async () => ({ tasks }), url: "https://hitlp.example/surface/v1", now: NOW });
  assert.equal(h.error, null);
  assert.equal(h.host, "hitlp.example");
  assert.deepEqual(h.open.map((x) => x.actionId), ["task-ask-2", "task-ask-1", "task-approve-1"]);
  assert.deepEqual(h.closed.map((x) => x.actionId), ["task-ask-closed", "task-approve-closed"], "newest decided first");
  const failed = await fetchHitlpTasks({ surface: async () => ({ error: "the HITLP server refused the credential", tasks: [] }) });
  assert.equal(failed.error, "HITLP server: the HITLP server refused the credential");
  assert.deepEqual([failed.open, failed.closed, failed.notSet], [[], [], false]);
  const threw = await fetchHitlpTasks({ surface: async () => { throw new Error("boom"); } });
  assert.equal(threw.error, "HITLP server: boom");
  const unset = await fetchHitlpTasks({ surface: async () => ({ tasks: [] }), configured: false });
  assert.equal(unset.notSet, true);
  assert.match(unset.error, /Config > frakworks > HITLP server/);
});

test("fetchNeeds passes the engine's errors through, and the view draws them", async () => {
  const env = { ENGINE_URL: "http://engine:8090" };
  const down = await fetchNeeds({ env, fetch: async () => { throw new Error("ECONNREFUSED"); } });
  assert.match(down.error, /engine unreachable at http:\/\/engine:8090\/needs: ECONNREFUSED/);
  assert.deepEqual(down.rows, []);
  const bad = await fetchNeeds({ env, fetch: answering(500, "boom") });
  assert.match(bad.error, /engine answered 500 for \/needs/);
  const notList = await fetchNeeds({ env, fetch: answering(200, "{}") });
  assert.match(notList.error, /not a list/);
  for (const r of [down, bad, notList]) {
    const html = render({ result: r });
    assert.match(html, /class="err"/);
    assert.doesNotMatch(html, /nmRow/);
  }
  assert.match(render({ result: { error: "IPC failed: x", rows: [] } }), /IPC failed: x/);
});

test("an empty list says nothing is waiting; loading says so", () => {
  assert.match(render({ result: { error: null, rows: [] } }), /Nothing is waiting on you/);
  assert.match(render({ result: { rows: [], loading: true } }), /asking the engine/);
});

test("the wait format", () => {
  assert.equal(fmtWaited(0), "0m");
  assert.equal(fmtWaited(12 * 60), "12m");
  assert.equal(fmtWaited(3 * 3600 + 7 * 60), "3h 07m");
  assert.equal(fmtWaited(4 * 86400 + 2 * 3600), "4d 2h");
});

test("NeedsYou draws every row of mixed kinds, with no checkbox in the header", () => {
  const html = render({ result: { rows } });
  assert.doesNotMatch(html, /<input type="checkbox"/);
  assert.ok(html.includes("Keycloak scope"));
  assert.ok(html.includes("Exporter host"));
  assert.ok(html.includes("Desktop: needs view"));
  const withHitlp = render({ result: { rows: [...surfaceRows(tasks, NOW), ...rows] } });
  assert.doesNotMatch(withHitlp, /Name the release|<h2>Ask<\/h2>|<h2>Approve<\/h2>|nmHitlp/, "a HITLP task is never drawn in Needs you");
});

// ---- HITLP tasks (#2173) ----------------------------------------------------

const ASK = byKey.get("asktask-ask-1"), ASK2 = byKey.get("asktask-ask-2"), APPROVE = byKey.get("approvetask-approve-1");
const hitlpRows = [ASK, ASK2, APPROVE];
// A task is drawn by the one task view (hitlp-task.jsx HitlpTask, #100070), wherever it opens.
const { HitlpTask } = require("../dist/hitlp-task.cjs");
const renderRow = (row, props = {}) => renderToStaticMarkup(React.createElement(HitlpTask, { row, now: NOW, ...props }));

test("KIND_ORDER has no ask or approve, and a surface row carries the deadline, attested, asserted and the §4 body", () => {
  assert.ok(!KIND_ORDER.includes("ask") && !KIND_ORDER.includes("approve"));
  assert.deepEqual(HITLP_KINDS, ["ask", "approve"]);
  assert.equal(ASK.actionId, "task-ask-1", "actionId is the task id");
  assert.equal(ASK.source, "hitlp");
  assert.equal(ASK.deadline, Math.floor(Date.parse("2026-09-25T02:14:00Z") / 1000), "epoch seconds");
  assert.equal(ASK.since, Math.floor(Date.parse("2026-09-24T23:30:00Z") / 1000));
  assert.equal(ASK.attested.requester, "smith-run-7");
  assert.equal(ASK.why, "The cache layer of codefrak-oss/frakworks#2201 needs a store before it merges.");
  assert.equal(ASK.priority, "urgent");
  assert.equal(ASK2.priority, "normal", "absent reads normal");
  assert.deepEqual(ASK.lineage.map((l) => l.kind), ["run", "ticket"], "lineage newest first");
  assert.deepEqual(APPROVE.recommendation.reasons, ["the image is signed", "one use only"]);
  assert.deepEqual(ASK.responseSchema, { type: "string", enum: ["pg", "redis"] });
  assert.deepEqual(ASK.options, [{ value: "pg", label: "Postgres" }, { value: "redis", label: "Redis" }]);
  assert.equal(ASK.question, "Which store should the cache use?\n\nPostgres is already in the stack; Redis would be new.");
  assert.equal(ASK.for, "lundmatt1");
  assert.equal(ASK2.options, null);
  assert.equal(ASK2.ticket, null, "a surface task has no ticket");
  assert.equal(ASK.repo, null);
  assert.equal(APPROVE.action, "Deploy engine 1.42 to the cluster");
  assert.deepEqual(APPROVE.payload, { image: "engine:1.42", namespace: "frakworks" });
  assert.equal(APPROVE.payloadDigest, "sha256:9f3c0d1e");
  assert.deepEqual(APPROVE.scope, { uses: 1 });
  assert.equal(APPROVE.responseSchema, null, "an ask's body is null on an approve");
  assert.equal(APPROVE.for, null, "a team's task waits on no one person");
  assert.equal(APPROVE.key, "approvetask-approve-1");
});

test("personOf: attested.onBehalfOf, else asserted.onBehalfOf, only of kind person", () => {
  assert.equal(personOf({ attested: { onBehalfOf: { kind: "person", id: "a" } }, asserted: { onBehalfOf: { kind: "person", id: "b" } } }), "a");
  assert.equal(personOf({ attested: {}, asserted: { onBehalfOf: { kind: "person", login: "b" } } }), "b");
  assert.equal(personOf({ asserted: { onBehalfOf: { kind: "agent", id: "bot" } } }), null);
  assert.equal(personOf({ attested: { onBehalfOf: "c" } }), "c");
  assert.equal(personOf({}), null);
});

test("lineageOf takes an array or { links }, newest first", () => {
  assert.deepEqual(lineageOf({ lineage: [{ claim: "a", at: "2026-01-01T00:00:00Z" }, { claim: "b", at: "2026-02-01T00:00:00Z" }] }).map((l) => l.claim), ["b", "a"]);
  assert.deepEqual(lineageOf({ lineage: { asOf: "2026-01-01T00:00:00Z", links: [{ claim: "x" }, { claim: "y" }] } }).map((l) => l.claim), ["y", "x"]);
  assert.deepEqual(lineageOf(null), []);
});

test("a surface task with context.data.repo and context.data.ticket (the engine's HitlpFiling) links frakworks://ticket; one without either links nothing", () => {
  const [both, repoOnly, ticketOnly, topLevel] = surfaceRows([
    { id: "a", primitive: "ask", status: "working", question: "?", context: { summary: "s", data: { repo: "codefrak-oss/frakworks", ticket: 100014 } } },
    { id: "b", primitive: "ask", status: "working", question: "?", context: { data: { repo: "codefrak-oss/frakworks" } } },
    { id: "c", primitive: "ask", status: "working", question: "?", context: { data: { ticket: 100014 } } },
    { id: "d", primitive: "ask", status: "working", question: "?", context: { repo: "codefrak-oss/frakworks", ticket: 100014 } },
  ], NOW);
  assert.equal(both.url, "frakworks://ticket/codefrak-oss/frakworks/100014");
  assert.equal(both.ticket, 100014);
  assert.equal(both.repo, "codefrak-oss/frakworks");
  for (const r of [repoOnly, ticketOnly, topLevel]) assert.deepEqual([r.url, r.ticket, r.repo], [null, null, null]);
});

test("surfaceRows keeps only open ask and approve tasks with an id, named by the spec's `primitive`", () => {
  const open = { status: "working" };
  assert.deepEqual(surfaceRows([null, { primitive: "ask", ...open }, { id: "x", primitive: "notify", ...open }, { id: "y", primitive: "approve", ...open },
    { id: "k", kind: "approve", ...open }, { id: "t", type: "approve", ...open }], NOW).map((r) => r.key), ["approvey"]);
  // An Ask, and an Approve with no decision page, stay `working` until decided (spec 7.3); only
  // URL-mode approves reach `input_required`. Decided tasks are in the list too and wait on no one.
  assert.deepEqual(surfaceRows(["working", "completed", "failed", "cancelled", "input_required"].map((status) => ({ id: status, primitive: "ask", status })), NOW)
    .map((r) => r.actionId).sort(), ["input_required", "working"]);
});

test("the spec's example task list (HITLP spec/examples/surface/task-list.json) reads as the spec means it: the working approve waits on a person, the completed ask on no one", () => {
  const specList = [
    { id: "task-7f3a", primitive: "approve", status: "working", createdAt: "2026-10-08T18:00:02Z", deadline: "2026-10-08T19:00:00Z", requiresHuman: false,
      attested: { source: "server", requester: "release-orchestrator", onBehalfOf: { kind: "run", id: "rel-4821" }, by: "hitlp-server" },
      action: "deploy release 4821 to production", payload: { service: "api", image: "api:4821" }, payloadDigest: "sha256:9f2c1e0a" },
    { id: "task-81b0", primitive: "ask", status: "completed", createdAt: "2026-10-08T17:00:00Z", deadline: "2026-10-08T19:00:00Z", requiresHuman: true,
      attested: { source: "server", requester: "billing-agent", by: "hitlp-server" }, question: "Which currency?", responseSchema: { type: "string" } },
  ];
  const rows = surfaceRows(specList, NOW);
  assert.deepEqual(rows.map((r) => r.actionId), ["task-7f3a"]);
  const [row] = rows;
  assert.deepEqual([row.kind, row.action, row.payloadDigest, row.attested.requester], ["approve", "deploy release 4821 to production", "sha256:9f2c1e0a", "release-orchestrator"]);
});

test("today's sections are labelled in HITLP's vocabulary; Ask and Approve are HITLP Requests', not sections here", () => {
  assert.equal(KIND_TITLES.ask, undefined);
  assert.equal(KIND_TITLES.approve, undefined);
  assert.match(KIND_TITLES.question, /^Ask: /);
  assert.match(KIND_TITLES.decision, /^Ask: /);
  assert.match(KIND_TITLES.authorize, /^Approve: /);
  const html = render({ result: { error: null, repo: "codefrak-oss/frakworks", rows } });
  assert.match(html, /<h1>Needs you · \d+<\/h1>[\s\S]*<h2>Ask: a question is waiting<\/h2>/);
});

test("the Needs you badge counts tickets: two rows of one ticket once, an incident with no ticket once by its actionId, HITLP tasks never", () => {
  const R = "codefrak-oss/frakworks";
  assert.equal(needsTicketCount([
    { kind: "question", repo: R, ticket: 1, actionId: "a" }, { kind: "incident", repo: R, ticket: 1, actionId: "b" },
    { kind: "authorize", repo: "o/x", ticket: 1, actionId: "c" },
    { kind: "incident", repo: null, ticket: null, actionId: "i1" }, { kind: "incident", repo: null, ticket: null, actionId: "i2" },
    { kind: "ask", repo: R, ticket: 5, actionId: "t" },
  ]), 4);
  assert.equal(needsTicketCount(null), 0);
});

test("the countdown says what is left, re-drawn each minute, and overdue past the deadline", () => {
  assert.equal(fmtDeadline(ASK.deadline, NOW), "2h 14m left");
  assert.equal(fmtDeadline(APPROVE.deadline, NOW), "overdue");
  assert.equal(fmtDeadline(null, NOW), null);
  const html = renderRow(ASK);
  assert.match(html, /<span class="nmDeadline"[^>]*>2h 14m left<\/span>/);
  assert.match(renderRow(APPROVE), /<span class="nmDeadline overdue"[^>]*>overdue<\/span>/);
  assert.doesNotMatch(renderToStaticMarkup(React.createElement(Countdown, { deadline: null, now: NOW })), /nmDeadline/);
  // The live countdown re-renders from a 60 s interval (the source is checked: static markup runs no effects).
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "needs-you.jsx"), "utf8");
  assert.match(src, /setInterval\(\(\) => setTick\(\(n\) => n \+ 1\), 60000\)/);
});

test("an ask task shows the attested who, the why, the lineage and the question in full; an approve its recommendation, digest, scope and payload", () => {
  const html = renderRow(ASK);
  assert.match(html, /FROM smith-run-7 FOR lundmatt1 <span class="htVerified">/);
  assert.match(html, /Why, as smith-run-7 states it:<\/span> The cache layer/);
  assert.match(html, /Add a cache layer[\s\S]*Smith run 7 works the ticket/, "lineage open, first step first");
  assert.match(html, /reported by smith-run-7, not verified/);
  assert.match(html, /Postgres is already in the stack; Redis would be new\./);
  assert.doesNotMatch(html, /htBack|Needs you/, "no back link without onBack");
  const other = renderRow(ASK2);
  assert.match(other, /FROM release-bot FOR someone-else/);
  const ap = renderRow(APPROVE);
  assert.match(ap, /APPROVAL NEEDED · FROM deploy-bot/);
  assert.match(ap, /digest sha256:9f3c0d1e/);
  assert.match(ap, /scope \{&quot;uses&quot;:1\}/);
  assert.match(ap, /Technical details: digest, scope, payload/);
  assert.match(ap, /engine:1\.42/);
});

test("buttonsFor: options, enum, boolean, string, number, object and an approve", () => {
  assert.deepEqual(buttonsFor(ASK), { buttons: [
    { label: "Postgres", outcome: "answered", answer: "pg" }, { label: "Redis", outcome: "answered", answer: "redis" }],
    input: null, reason: false, schemaShown: false });
  const enumOnly = buttonsFor({ kind: "ask", responseSchema: { type: "string", enum: ["a", "b"] }, options: null });
  assert.deepEqual(enumOnly.buttons.map((b) => [b.label, b.answer]), [["a", "a"], ["b", "b"]]);
  const ids = buttonsFor({ kind: "ask", options: [{ id: "x", label: "Ex" }, "plain"] });
  assert.deepEqual(ids.buttons.map((b) => [b.label, b.answer]), [["Ex", "x"], ["plain", "plain"], ["Something else", undefined]], "no schema enum: Something else last");
  assert.deepEqual(buttonsFor({ kind: "ask", responseSchema: { type: "boolean" } }).buttons,
    [{ label: "Yes", outcome: "answered", answer: true }, { label: "No", outcome: "answered", answer: false }]);
  assert.deepEqual(buttonsFor(ASK2), { buttons: [{ label: "Answer", outcome: "answered" }], input: "text", reason: false, schemaShown: false });
  assert.equal(buttonsFor({ kind: "ask", responseSchema: { type: "number" } }).input, "number");
  assert.equal(buttonsFor({ kind: "ask", responseSchema: { type: "integer" } }).input, "number");
  const obj = buttonsFor({ kind: "ask", responseSchema: { type: "object", properties: { a: { type: "string" } } } });
  assert.deepEqual([obj.input, obj.schemaShown], ["json", true]);
  assert.deepEqual([buttonsFor({ kind: "ask" }).input, buttonsFor({ kind: "ask" }).schemaShown], ["json", false]);
  assert.deepEqual(buttonsFor(APPROVE), { buttons: [{ label: "Approve", outcome: "approved" }, { label: "Reject", outcome: "rejected" }],
    input: null, reason: true, schemaShown: false });
  // what the typed box sends
  assert.deepEqual(parseTyped("number", "42"), { answer: 42 });
  assert.match(parseTyped("number", "x").error, /must be a number/);
  assert.deepEqual(parseTyped("json", '{"a":1}'), { answer: { a: 1 } });
  assert.deepEqual(parseTyped("json", "plain words"), { answer: "plain words" });
  assert.deepEqual(parseTyped("text", " hi "), { answer: " hi " });
  assert.match(parseTyped("text", "  ").error, /type an answer/);
});

test("each task draws its button set: one per option, a text box with Answer, Approve and Reject with a comment box", () => {
  const ask = renderRow(ASK);
  assert.match(ask, /<button[^>]*data-outcome="answered"[^>]*>Postgres/);
  assert.match(ask, /<button[^>]*data-outcome="answered"[^>]*>Redis/);
  assert.doesNotMatch(ask, /htAnswer/);
  const text = renderRow(ASK2);
  assert.match(text, /<input class="htAnswer"[^>]*type="text"/);
  assert.match(text, />Answer<\/button>/);
  const bool = renderRow({ ...ASK2, responseSchema: { type: "boolean" } });
  assert.match(bool, />Yes<\/button>[\s\S]*>No<\/button>/);
  const num = renderRow({ ...ASK2, responseSchema: { type: "number" } });
  assert.match(num, /<input class="htAnswer"[^>]*inputMode="decimal"[^>]*placeholder="a number"/);
  const obj = renderRow({ ...ASK2, responseSchema: { type: "object", properties: { a: { type: "string" } } } });
  assert.match(obj, /<summary>response schema<\/summary>/);
  assert.match(obj, /placeholder="answer \(JSON or text\)"/);
  const ap = renderRow(APPROVE);
  assert.match(ap, /<button[^>]*data-outcome="approved"[^>]*class="htBtn approve"[^>]*>Approve/);
  assert.match(ap, /<button[^>]*data-outcome="rejected"[^>]*class="htBtn reject"[^>]*>Reject/);
  assert.match(ap, /Comment \(optional, sent with your decision\)/);
});

test("a decide error is drawn under the buttons, with the buttons still there", () => {
  const html = renderRow(ASK, { initialError: "the task is already decided (or timed out)" });
  assert.match(html, /<div class="err htErr">the task is already decided \(or timed out\)<\/div>/);
  assert.match(html, />Postgres/);
  assert.doesNotMatch(html, /htDone/);
});

test("a click decides on the HITLP server through hitlpSurfaceDecide with the shown payloadDigest; preload, main and the channel list carry it", async () => {
  const calls = [];
  const was = global.window;
  global.window = { fleet: { hitlpSurfaceDecide: async (...a) => { calls.push(a); return { ok: true }; } } };
  try {
    const { surfaceDecide } = require("../dist/needs-you.cjs");
    assert.deepEqual(await surfaceDecide(APPROVE)("task-approve-1", "rejected", null, "too risky"), { ok: true });
    assert.deepEqual(calls[0], ["task-approve-1", "rejected", null, "too risky", "sha256:9f3c0d1e"]);
    await surfaceDecide(ASK)("task-ask-1", "answered", "pg", null);
    assert.deepEqual(calls[1], ["task-ask-1", "answered", "pg", null, null]);
  } finally { global.window = was; }
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "hitlp-task.jsx"), "utf8");
  assert.match(src, /const call = decide \|\| surfaceDecide\(row\);/);
  assert.match(src, /call\(row\.actionId, b\.outcome, answer === undefined \? null : answer, comment\.trim\(\) \? comment\.trim\(\) : null\)/);
  const preload = fs.readFileSync(path.join(__dirname, "..", "src", "preload.js"), "utf8");
  assert.match(preload, /hitlpSurfaceDecide: \(taskId, outcome, answer, reason, payloadDigest\) => ipcRenderer\.invoke\("hitlp:surface-decide", String\(taskId\), String\(outcome\),/);
  const main = fs.readFileSync(path.join(__dirname, "..", "src", "main.js"), "utf8");
  assert.match(main, /ipcMain\.handle\("hitlp:surface-decide", async \(_e, taskId, outcome, answer, reason, payloadDigest\) => \{\s*const r = await hitlpSurface\.decide\(taskId, \{ outcome, answer, reason, payloadDigest \}\);/);
  assert.doesNotMatch(main, /surface\] decide[^\n]*\$\{answer/, "the answer is not logged");
  assert.doesNotMatch(main, /engine:hitlp-decide|decideHitlp/);
});

test("with a repo selected, its own rows show; the engine is read for every repo (#2423)", async () => {
  // The engine's own approve (h1) is ignored since #2487; HITLP tasks are HITLP Requests' (#100064).
  const { inRepo } = require("../src/needs-you");
  const A = "codefrak-oss/frakworks";
  assert.equal(inRepo({ repo: A, kind: "question" }, A), true);
  assert.equal(inRepo({ repo: "o/other", kind: "question" }, A), false);
  assert.equal(inRepo({ repo: null, kind: "incident" }, A), false);
  assert.equal(inRepo({ repo: "o/other", kind: "question" }, null), true);
  const urls = [];
  const fetch = async (url) => { urls.push(String(url)); return { ok: true, status: 200, text: async () => JSON.stringify([
    { repo: A, number: 1, title: "t", kind: "question", wanted: "x", since: null, actionId: "q1" },
    { repo: "o/other", number: 2, title: "t", kind: "question", wanted: "x", since: null, actionId: "q2" },
    { repo: null, number: null, title: "", kind: "approve", action: "host.patch", client: "login:b/token:1", since: null, actionId: "h1" },
  ]) }; };
  const r = await fetchNeeds({ repo: A, fetch, env: { ENGINE_URL: "http://engine.test" } });
  assert.equal(r.error, null);
  assert.deepEqual(r.rows.map((x) => x.actionId).sort(), ["q1"]);
  assert.ok(urls.every((u) => !u.includes("repo=")));
});

test("HITLP tasks sort by priority, then the nearest deadline", () => {
  const t = (id, priority, deadline) => ({ actionId: id, priority, deadline });
  assert.deepEqual(byPriority([t("a", "low", 1), t("b", "high", 9), t("c", undefined, 2), t("d", "urgent", 5), t("e", "high", 3)])
    .map((r) => r.actionId), ["d", "e", "b", "c", "a"]);
});



test("a lineage source is drawn whatever its fields: ticket, host, run, the command as code; a lineage that only repeats the why is left out", () => {
  const { sourceText, lineageAddsNothing } = require("../dist/needs-you.cjs");
  assert.equal(sourceText({ repo: "o/n", number: 5 }), "o/n#5");
  assert.equal(sourceText({ host: "host-a", ip: "192.0.2.12", session: "s-1", command: "uptime", run: "r-9" }), "host host-a · ip 192.0.2.12 · run r-9");
  assert.equal(sourceText("plain"), "plain");
  assert.equal(sourceText(null), null);
  const cmd = { kind: "command", claim: "Check host-a's load", by: "ops-laptop", source: { host: "host-a", session: "s", command: "uptime" } };
  const goal = { kind: "goal", claim: "Test that the laptop ops server works", by: "claude-code" };
  const row = (lineage, why) => renderRow({ ...APPROVE, attested: { requester: "ops-laptop" }, why, lineage });
  const two = row([cmd, goal], "Check host-a's load");
  assert.match(two, /Test that the laptop ops server works[\s\S]*Check host-a&#x27;s load[\s\S]*<span class="htChip">host host-a<\/span><code class="htChip code">uptime<\/code>/);
  assert.equal(lineageAddsNothing([cmd], "Check host-a's load"), true);
  assert.doesNotMatch(row([cmd], "Check host-a's load"), /HOW THIS REACHED YOU/, "one link that is the why: no lineage");
  assert.match(row([cmd], "something else"), /HOW THIS REACHED YOU/);
});

test("buttonsFor: a choice ask with no schema enum adds Something else, its text box shown once picked", () => {
  const spec = buttonsFor({ kind: "ask", responseSchema: { type: "string" }, options: [{ value: "pg", label: "Postgres" }, { value: "redis", label: "Redis" }] });
  assert.deepEqual(spec, { buttons: [
    { label: "Postgres", outcome: "answered", answer: "pg" }, { label: "Redis", outcome: "answered", answer: "redis" },
    { label: "Something else", outcome: "answered", other: true }], input: "text", reason: false, schemaShown: false });
  assert.deepEqual(pressOf(spec, spec.buttons[0], false, ""), { answer: "pg" }, "an option sends its value, no box");
  assert.deepEqual(pressOf(spec, spec.buttons[2], false, ""), { reveal: true }, "Something else opens the box first");
  assert.deepEqual(pressOf(spec, spec.buttons[2], true, "  MySQL "), { answer: "  MySQL " });
  assert.deepEqual(pressOf(spec, spec.buttons[2], true, "  "), { error: "type an answer first" });
});

test("buttonsFor: a schema that enumerates the values (a proposal's verbs) gives exactly the options", () => {
  const spec = buttonsFor({ kind: "ask", responseSchema: { type: "string", enum: ["accept", "reject"] }, options: [{ value: "accept", label: "accept" }, { value: "reject", label: "reject" }] });
  assert.deepEqual(spec.buttons.map((b) => b.label), ["accept", "reject"]);
  assert.equal(spec.input, null);
  assert.ok(!spec.buttons.some((b) => b.other));
});
