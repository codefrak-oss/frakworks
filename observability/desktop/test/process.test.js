// The process view: src/engine-data.js's three /camunda reads against a
// stand-in fetch, and process.jsx's pure parts against a tree shaped like the
// engine's answer for an intake that called a Smith run and tripped an
// incident. bpmn-js itself is not exercised here: it needs a DOM, and the
// view imports it lazily so this bundle never does.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { fetchProcessInstances, fetchProcessTree, fetchProcessXml, retryIncident, fetchTicketTimeline, DEFAULT_ENGINE_URL } = require("../src/engine-data");
const { markersOf, instanceState, calledChild, pathTo, ProcessList, ElementPanel, ProcessSection, ProcessVariables, VariablesFold } = require("../dist/process.cjs");

const ISSUE = "https://github.com/codefrak-oss/frakworks/issues/56";
const ROOT = "0f3b7b1e-9a1c-11f1-8f5e-0242ac120002";
const CHILD = "1a2b3c4d-9a1c-11f1-8f5e-0242ac120002";
const DEF = "intake:3:5e6f7a8b-9a1c-11f1-8f5e-0242ac120002";
const stubFetch = (status, body, calls = []) => async (url) => {
  calls.push(url);
  if (status instanceof Error) throw status;
  return { ok: status >= 200 && status < 300, status, text: async () => body };
};
const render = (C, props) => renderToStaticMarkup(React.createElement(C, props));

const instances = [
  { id: ROOT, processDefinitionKey: "intake", processDefinitionId: DEF, processName: "intake",
    startedAt: "2026-09-24T14:00:00Z", endedAt: null, ended: false, state: "incident", step: "file unlabeled ticket for human triage" },
  { id: CHILD, processDefinitionKey: "hello", processDefinitionId: "hello:1:x", processName: "hello",
    startedAt: "2026-09-24T13:00:00Z", endedAt: "2026-09-24T13:00:01Z", ended: true, state: "ended", step: null },
];
const tree = { id: ROOT, nodes: [
  { id: ROOT, parentId: null, calledFrom: null, processDefinitionKey: "intake", processDefinitionId: DEF, processName: "intake",
    startedAt: "2026-09-24T14:00:00Z", endedAt: null, ended: false,
    completed: ["start-intake", "task-create-issue", "call-refine-ask", "call-refine-ask"], active: ["task-file-unlabeled"],
    incidents: [{ id: "i1", activityId: "task-file-unlabeled", type: "failedJob", message: "not built", createdAt: "2026-09-24T14:05:00Z" }],
    variables: [{ name: "ask", type: "String", value: "hello", scope: null, truncated: false },
                { name: "log", type: "Bytes", value: "<8192 bytes>", scope: "call-refine-ask", truncated: false }] },
  { id: CHILD, parentId: ROOT, calledFrom: "call-refine-ask", processDefinitionKey: "reusable-smith-run", processDefinitionId: "reusable-smith-run:2:y",
    processName: "reusable smith run", startedAt: "2026-09-24T14:00:01Z", endedAt: "2026-09-24T14:04:00Z", ended: true,
    completed: ["call-smith-run"], active: [], incidents: [], variables: [] },
] };

test("fetchProcessInstances asks /camunda/instances for a GitHub issue URL and nothing else", async () => {
  const calls = [];
  const r = await fetchProcessInstances(ISSUE, { env: {}, fetch: stubFetch(200, JSON.stringify(instances), calls) });
  assert.equal(r.error, null);
  assert.equal(calls[0], `${DEFAULT_ENGINE_URL}/camunda/instances?issueUrl=${encodeURIComponent(ISSUE)}`);
  assert.equal(r.instances.length, 2);
  for (const bad of ["codefrak-oss/frakworks#56", "http://github.com/o/r/issues/1", "https://github.com/o/r/pull/1", null]) {
    assert.match((await fetchProcessInstances(bad, { env: {}, fetch: stubFetch(200, "[]", calls) })).error, /not a GitHub issue URL/);
  }
  assert.equal(calls.length, 1);
  assert.match((await fetchProcessInstances(ISSUE, { env: {}, fetch: stubFetch(200, "{}") })).error, /not a list/);
  assert.match((await fetchProcessInstances(ISSUE, { env: {}, fetch: stubFetch(new Error("ECONNREFUSED")) })).error, /unreachable/);
});

test("fetchProcessTree checks the id first, and says a 404 plainly", async () => {
  const calls = [];
  const r = await fetchProcessTree(ROOT, { env: {}, fetch: stubFetch(200, JSON.stringify(tree), calls) });
  assert.equal(r.error, null);
  assert.equal(calls[0], `${DEFAULT_ENGINE_URL}/camunda/instances/${ROOT}`);
  assert.equal(r.tree.nodes.length, 2);
  assert.match((await fetchProcessTree("../accounts", { env: {}, fetch: stubFetch(200, "{}", calls) })).error, /not a process instance id/);
  assert.equal(calls.length, 1);
  assert.match((await fetchProcessTree(ROOT, { env: {}, fetch: stubFetch(404, "") })).error, /has no process instance/);
  assert.match((await fetchProcessTree(ROOT, { env: {}, fetch: stubFetch(200, "{}") })).error, /no `nodes` list/);
});

test("fetchProcessXml takes a definition id, asks for XML and refuses anything that is not BPMN", async () => {
  const calls = [];
  const xml = '<?xml version="1.0"?><bpmn:definitions xmlns:bpmn="x"><bpmn:process id="intake"/></bpmn:definitions>';
  const r = await fetchProcessXml(DEF, { env: {}, fetch: stubFetch(200, xml, calls) });
  assert.equal(r.error, null);
  assert.equal(r.xml, xml);
  assert.equal(calls[0], `${DEFAULT_ENGINE_URL}/camunda/definitions/${encodeURIComponent(DEF)}/xml`);
  assert.match((await fetchProcessXml("intake", { env: {}, fetch: stubFetch(200, xml, calls) })).error, /not a process definition id/);
  assert.equal(calls.length, 1);
  assert.match((await fetchProcessXml(DEF, { env: {}, fetch: stubFetch(404, "") })).error, /has no process definition/);
  assert.match((await fetchProcessXml(DEF, { env: {}, fetch: stubFetch(200, "<html>oops</html>") })).error, /not BPMN XML/);
  assert.match((await fetchProcessXml(DEF, { env: {}, fetch: stubFetch(new Error("ECONNREFUSED")) })).error, /unreachable/);
});

test("markers: an incident beats active, active beats completed, and a re-entered step counts once", () => {
  const m = markersOf(tree.nodes[0]);
  assert.deepEqual([...m.incident], ["task-file-unlabeled"]);
  assert.deepEqual([...m.active], []);
  assert.deepEqual([...m.completed], ["start-intake", "task-create-issue", "call-refine-ask"]);
  const m2 = markersOf({ completed: ["a", "b"], active: ["b"], incidents: [] });
  assert.deepEqual([[...m2.completed], [...m2.active], [...m2.incident]], [["a"], ["b"], []]);
  assert.deepEqual([...markersOf(null).completed], []);
});

test("the tree's shape: the called child of a call activity, and the path to a node", () => {
  assert.equal(calledChild(tree, tree.nodes[0], "call-refine-ask").id, CHILD);
  assert.equal(calledChild(tree, tree.nodes[0], "task-create-issue"), null);
  assert.deepEqual(pathTo(tree, tree.nodes[1]).map((n) => n.processDefinitionKey), ["intake", "reusable-smith-run"]);
  assert.deepEqual(pathTo(tree, tree.nodes[0]).map((n) => n.id), [ROOT]);
});

test("the list says where each instance is, and the section's first paint asks the engine", () => {
  assert.equal(instanceState(instances[0]), "incident");
  assert.equal(instanceState(instances[1]), "ended");
  assert.equal(instanceState({ state: "running", step: "poll smith" }), "running · at poll smith");
  const html = render(ProcessList, { instances, open: ROOT, now: Date.parse("2026-09-24T15:00:00Z") });
  assert.match(html, /class="open is-incident"/);
  // A chevron says the row opens and closes; the open one points down.
  assert.match(html, /is-incident"[^>]*><td class="pvChev">▾/);
  assert.match(html, /is-ended"[^>]*><td class="pvChev">▸/);
  assert.match(html, /started 1h 00m ago/);
  assert.match(html, /ended 1h 59m ago/);
  assert.match(render(ProcessList, { instances: [] }), /no process instances/);
  global.localStorage = { getItem: (k) => (k === "ticket.processesOpen" ? "true" : null), setItem: () => {} };
  try {
    assert.match(render(ProcessSection, { issueUrl: ISSUE }), /<h2 class="tdFold"[^>]*>.*Processes<\/h2>.*asking the engine/);
  } finally { delete global.localStorage; }
});

test("the Processes section's first paint is a closed fold: no list, no diagram", () => {
  const html = render(ProcessSection, { issueUrl: ISSUE });
  assert.match(html, /<h2 class="tdFold" title="Show the processes"><span class="tdFoldArrow">▸<\/span>Processes<\/h2>/);
  assert.doesNotMatch(html, /asking the engine|pvList|pvCanvas/);
});

test("an open Processes fold renders the list", () => {
  global.localStorage = { getItem: (k) => (k === "ticket.processesOpen" ? "true" : null), setItem: () => {} };
  try {
    const html = render(ProcessSection, { issueUrl: ISSUE });
    assert.match(html, /title="Hide the processes"><span class="tdFoldArrow">▾/);
    assert.match(html, /asking the engine for the processes/);
  } finally { delete global.localStorage; }
});

test("the element panel: the incident, the scope's variables, the called process, or nothing", () => {
  const root = tree.nodes[0];
  const hit = render(ElementPanel, { tree, node: root, element: { id: "task-file-unlabeled", name: "file unlabeled ticket", type: "bpmn:ServiceTask" } });
  assert.match(hit, /is-incident">incident/);
  assert.match(hit, /failedJob/);
  assert.match(hit, /not built/);
  const call = render(ElementPanel, { tree, node: root, element: { id: "call-refine-ask", name: "refine ask", type: "bpmn:CallActivity" } });
  assert.match(call, /is-completed">completed/);
  assert.match(call, /called <button[^>]*>reusable smith run<\/button>/);
  assert.match(call, /<td[^>]*>log<\/td><td[^>]*>&lt;8192 bytes&gt;/);
  const none = render(ElementPanel, { tree, node: root, element: { id: "start-intake", name: null, type: "bpmn:StartEvent" } });
  assert.match(none, /nothing set in this step/);
  assert.match(render(ElementPanel, { tree, node: root, element: { id: "end-x" } }), /not-reached">not reached/);
  assert.match(render(ElementPanel, { tree, node: root, element: null }), /click a step/);
});

test("a step with an active timer reads waits until its dueAt in the panel, and another step does not", () => {
  const node = { ...tree.nodes[0], timers: [{ activityId: "event-wait-reset", dueAt: "2026-10-05T21:50:00Z" }] };
  const wait = render(ElementPanel, { tree, node, element: { id: "event-wait-reset", name: "wait for reset", type: "bpmn:IntermediateCatchEvent" } });
  assert.match(wait, /waits until 2026-10-05T21:50:00Z/);
  const other = render(ElementPanel, { tree, node, element: { id: "start-intake", name: null, type: "bpmn:StartEvent" } });
  assert.doesNotMatch(other, /waits until/);
});

test("an incident in the step panel carries a Retry, and a step without one does not", () => {
  const root = tree.nodes[0];
  assert.match(render(ElementPanel, { tree, node: root, element: { id: "task-file-unlabeled" } }), /class="pvButton">Retry</);
  assert.doesNotMatch(render(ElementPanel, { tree, node: root, element: { id: "start-intake" } }), /Retry/);
});

test("retryIncident posts an empty body (no by) to /camunda/incidents/<id>/retry and refuses anything it cannot name", async () => {
  const INC = "96af5de7-b847-11f1-98a3-de69bb551eb7";
  const calls = [];
  const fetch = async (url, init) => { calls.push({ url, init }); return { ok: true, status: 200, text: async () => "{}" }; };
  assert.deepEqual(await retryIncident(INC, "lundmatt1", { env: {}, fetch }), { ok: true, status: 200 });
  assert.equal(calls[0].url, `${DEFAULT_ENGINE_URL}/camunda/incidents/${INC}/retry`);
  assert.equal(calls[0].init.method, "POST");
  assert.deepEqual(JSON.parse(calls[0].init.body), {});
  assert.match((await retryIncident("../accounts", "lundmatt1", { env: {}, fetch })).error, /not an incident id/);
  assert.match((await retryIncident(INC, null, { env: {}, fetch })).error, /no GitHub login/);
  assert.equal(calls.length, 1);
  const refused = async () => ({ ok: false, status: 403, text: async () => JSON.stringify({ message: "x may not retry a intake" }) });
  assert.match((await retryIncident(INC, "x", { env: {}, fetch: refused })).error, /403.*may not retry/);
});

// The ticket detail's Timeline (src/renderer/timeline.jsx): the engine's
// not-yet-shipped GET /ticket/{owner}/{repo}/{n}/timeline (#91), built ahead
// of it (#101). Only the read + its error/notFound mapping is engine-data's
// job; the rendering is timeline.test.js's.
test("fetchTicketTimeline asks /ticket/<owner>/<repo>/<n>/timeline, maps a 404 to notFound and validates its arguments", async () => {
  const calls = [];
  const body = JSON.stringify({ ticket: { number: 101 }, steps: [], waits: [], totals: {} });
  const r = await fetchTicketTimeline("codefrak-oss", "frakworks", 101, { env: {}, fetch: stubFetch(200, body, calls) });
  assert.equal(r.error, null);
  assert.equal(calls[0], `${DEFAULT_ENGINE_URL}/ticket/codefrak-oss/frakworks/101/timeline`);
  assert.equal(r.timeline.steps.length, 0);
  assert.deepEqual(await fetchTicketTimeline("codefrak-oss", "frakworks", 101, { env: {}, fetch: stubFetch(404, "") }), { notFound: true });
  assert.match((await fetchTicketTimeline("codefrak-oss", "frakworks", 101, { env: {}, fetch: stubFetch(new Error("ECONNREFUSED")) })).error, /unreachable/);
  assert.match((await fetchTicketTimeline("codefrak-oss", "frakworks", 101, { env: {}, fetch: stubFetch(200, "{}") })).error, /no `steps`\/`waits`\/`totals`/);
  assert.match((await fetchTicketTimeline("../etc", "frakworks", 101, { env: {}, fetch: stubFetch(200, body) })).error, /not a repo owner/);
  assert.match((await fetchTicketTimeline("codefrak-oss", "../etc", 101, { env: {}, fetch: stubFetch(200, body) })).error, /not a repo name/);
  assert.match((await fetchTicketTimeline("codefrak-oss", "frakworks", "abc", { env: {}, fetch: stubFetch(200, body) })).error, /not a ticket number/);
  assert.equal(calls.length, 1);
});

test("the process's own variables fold behind a closed \"Camunda variables\" heading", () => {
  const store = {};
  global.localStorage = { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = v; } };
  try {
    const node = { variables: [{ name: "issueUrl", value: ISSUE }, { name: "tier", value: 4 }, { name: "x", value: 1, scope: "Task_1" }] };
    let html = render(ProcessVariables, { node });
    assert.match(html, /Camunda variables/);
    assert.match(html, /tdFoldCount">2</);
    assert.doesNotMatch(html, /flVars/);
    // The heading's click handler: the stateful wrapper hands VariablesFold a flip that stores the choice.
    const vars = node.variables.filter((v) => !v.scope);
    let opened = false;
    const el = VariablesFold({ vars, open: false, flip: () => { opened = true; } });
    const h2 = React.Children.toArray(el.props.children)[0];
    h2.props.onClick();
    assert.equal(opened, true);
    assert.match(render(VariablesFold, { vars, open: true, flip() {} }), /flVars[\s\S]*issueUrl[\s\S]*tier/);
    // The wrapper's own state is remembered under ticket.variablesOpen and restored on the next render.
    store["ticket.variablesOpen"] = "true";
    html = render(ProcessVariables, { node });
    assert.match(html, /flVars/);
    assert.doesNotMatch(html, />x</);
    assert.equal(render(ProcessVariables, { node: { variables: [] } }), "");
  } finally { delete global.localStorage; }
});
