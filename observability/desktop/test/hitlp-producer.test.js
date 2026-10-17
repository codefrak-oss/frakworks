// Utils > HITLP producer (#1947): the form's fields and defaults per kind
// (src/hitlp-request.js, against codefrak-oss/hitlp spec/hitlp.md at HITLP
// 0.2: `asserted` and `requiresHuman`, no `requester`; #2489), the tool
// arguments and JSON-RPC bodies built from it, main's client
// (src/hitlp-client.js) against a stand-in fetch with the pasted requester
// token as its only credential, and the page's Request and Response panes
// after a stubbed call.
"use strict";
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const H = require("../src/hitlp-request");
const { callHitlp } = require("../src/hitlp-client");
const { HitlpProducerView, serverSuggestions } = require("../dist/hitlp-producer.cjs");

const NOW = Date.parse("2026-10-09T12:00:00Z");
const uuid = () => "11111111-2222-4333-8444-555555555555";
const names = (kind, answerKind) => H.formFields(kind, answerKind).map((f) => f.name);
const ENVELOPE = ["idempotencyKey", "deadline", "defaultOnTimeout", "priority", "requires", "context", "requiresHuman",
  "asserted.why", "asserted.onBehalfOf", "asserted.recommendation", "asserted.lineage", "asserted.asOf"];

test("the form's fields: §5's envelope (0.2) for both kinds, then §4.1's body for Ask and §4.2's for Approve", () => {
  assert.deepEqual(H.ENVELOPE_FIELDS.map((f) => f.name), ENVELOPE);
  assert.ok(!ENVELOPE.some((n) => n.startsWith("requester")), "0.1's requester is gone");
  assert.equal(H.ENVELOPE_FIELDS.find((f) => f.name === "requiresHuman").type, "boolean");
  assert.equal(H.ENVELOPE_FIELDS.find((f) => f.name === "asserted.asOf").type, "datetime");
  assert.deepEqual(names("ask", "yes-no"), [...ENVELOPE, "question", "answerKind"]);
  assert.deepEqual(names("ask", "choice"), [...ENVELOPE, "question", "answerKind", "options"]);
  assert.deepEqual(names("ask", "object"), [...ENVELOPE, "question", "answerKind", "responseSchema"]);
  assert.deepEqual(names("approve"), [...ENVELOPE, "action", "payload", "payloadDigest", "scope"]);
  const required = H.formFields("approve").filter((f) => f.required).map((f) => f.name);
  assert.deepEqual(required, ["idempotencyKey", "deadline", "defaultOnTimeout", "action", "payload"]);
  assert.deepEqual(H.DEFAULT_ON_TIMEOUT, ["reject", "escalate", "cancel", "fail"]);
  assert.deepEqual(H.PRIORITIES, ["low", "normal", "high", "urgent"]);
  assert.deepEqual(H.ANSWER_KINDS.map((k) => k.id), ["text", "number", "integer", "yes-no", "choice", "object"]);
});

test("defaults: a fresh key, a deadline an hour on, reject, the login as asserted.onBehalfOf, requiresHuman off, and a digest of the payload", () => {
  const ask = H.defaultForm("ask", { login: "octo", now: NOW, uuid });
  assert.equal(ask.idempotencyKey, uuid());
  assert.equal(ask.deadline, "2026-10-09T13:00:00Z");
  assert.equal(ask.defaultOnTimeout, "reject");
  assert.equal(ask.priority, "normal");
  assert.equal(ask["asserted.onBehalfOf"], "octo");
  assert.ok(ask["asserted.why"]);
  assert.equal(ask.requiresHuman, false);
  assert.deepEqual([ask["asserted.recommendation"], ask["asserted.lineage"], ask["asserted.asOf"]], ["", "", ""]);
  assert.ok(!("requester.agent" in ask) && !("requester.onBehalfOf" in ask));
  assert.ok(ask.question);
  assert.equal(H.defaultForm("ask", { now: NOW, uuid })["asserted.onBehalfOf"], "", "no login, empty");
  assert.notEqual(H.defaultForm("ask").idempotencyKey, H.defaultForm("ask").idempotencyKey, "a fresh key per reset");
  const approve = H.defaultForm("approve", { login: "octo", now: NOW, uuid });
  assert.ok(approve.action);
  assert.equal(approve.payloadDigest, H.payloadDigest(approve.payload));
  assert.deepEqual(JSON.parse(approve.scope), { maxUses: 1, notAfter: "2026-10-09T13:00:00Z" });
});

test("the digest is sha256 over the payload's canonical JSON, as the reference server hashes it", () => {
  for (const s of ["", "abc", "é ✓", "x".repeat(200)]) {
    assert.equal(H.sha256Hex(s), crypto.createHash("sha256").update(s, "utf8").digest("hex"), JSON.stringify(s));
  }
  const want = "sha256:" + crypto.createHash("sha256").update('{"a":[1,{"c":2,"d":3}],"b":"x"}').digest("hex");
  assert.equal(H.payloadDigest('{ "b": "x", "a": [1, {"d": 3, "c": 2}] }'), want);
  assert.equal(H.payloadDigest("not json"), null);
  assert.equal(H.payloadDigest("[1]"), null);
});

test("Ask's arguments: the envelope and body flattened, responseSchema from the answer kind, options for a choice", () => {
  const form = { ...H.defaultForm("ask", { login: "octo", now: NOW, uuid }), requires: '{"roles":["ops"]}' };
  const r = H.toolArguments(form);
  assert.equal(r.tool, "human.ask");
  assert.deepEqual(r.arguments, {
    idempotencyKey: uuid(), deadline: "2026-10-09T13:00:00Z", defaultOnTimeout: "reject", priority: "normal",
    requires: { roles: ["ops"] }, context: JSON.parse(form.context),
    asserted: { why: form["asserted.why"], onBehalfOf: "octo" },
    question: form.question, responseSchema: { type: "boolean" },
  });
  assert.ok(!("requester" in r.arguments) && !("attested" in r.arguments) && !("requiresHuman" in r.arguments), "no 0.1 requester, no attested, requiresHuman only when true");
  const choice = H.toolArguments({ ...form, answerKind: "choice", options: [{ value: "a", label: "" }, { value: " ", label: "x" }, { value: "b", label: "Bee" }] });
  assert.deepEqual(choice.arguments.options, [{ value: "a", label: "a" }, { value: "b", label: "Bee" }]);
  assert.deepEqual(choice.arguments.responseSchema, { enum: ["a", "b"] });
  assert.deepEqual(H.toolArguments({ ...form, answerKind: "text" }).arguments.responseSchema, { type: "string", minLength: 1 });
  assert.deepEqual(H.toolArguments({ ...form, answerKind: "object" }).arguments.responseSchema, JSON.parse(form.responseSchema));
  assert.match(H.toolArguments({ ...form, answerKind: "choice", options: [] }).error, /at least one option/);
  assert.match(H.toolArguments({ ...form, context: "{oops" }).error, /context is not JSON/);
  const bare = H.toolArguments({ ...form, priority: "", requires: "", context: "", "asserted.why": "", "asserted.onBehalfOf": " " }).arguments;
  assert.deepEqual(Object.keys(bare), ["idempotencyKey", "deadline", "defaultOnTimeout", "question", "responseSchema"], "asserted only with a set sub-field");
});

test("0.2's asserted: recommendation an object, lineage an array of links, asOf; requiresHuman only when true", () => {
  const form = H.defaultForm("ask", { login: "octo", now: NOW, uuid });
  const full = H.toolArguments({ ...form, requiresHuman: true,
    "asserted.recommendation": '{"verdict":"approve","confidence":0.8,"by":"model:x","reasons":["safe"]}',
    "asserted.lineage": '[{"kind":"ticket","claim":"do it","source":{"repo":"a/b","number":1},"by":"octo"}]',
    "asserted.asOf": "2026-10-12T01:50:22Z" }).arguments;
  assert.equal(full.requiresHuman, true);
  assert.deepEqual(full.asserted, { why: form["asserted.why"], onBehalfOf: "octo", asOf: "2026-10-12T01:50:22Z",
    recommendation: { verdict: "approve", confidence: 0.8, by: "model:x", reasons: ["safe"] },
    lineage: [{ kind: "ticket", claim: "do it", source: { repo: "a/b", number: 1 }, by: "octo" }] });
  assert.equal(H.toolArguments({ ...form, requiresHuman: "true" }).arguments.requiresHuman, true, "a checkbox's string too");
  assert.ok(!("requiresHuman" in H.toolArguments({ ...form, requiresHuman: false }).arguments));
  assert.match(H.toolArguments({ ...form, "asserted.lineage": '{"kind":"x"}' }).error, /asserted\.lineage is not a JSON array/);
  assert.match(H.toolArguments({ ...form, "asserted.recommendation": "[]" }).error, /asserted\.recommendation is not a JSON object/);
  assert.match(H.toolArguments({ ...form, "asserted.recommendation": "{oops" }).error, /asserted\.recommendation is not JSON/);
  const only = H.toolArguments({ ...form, "asserted.why": "", "asserted.onBehalfOf": "", "asserted.asOf": " 2026-10-12T00:00:00Z " }).arguments;
  assert.deepEqual(only.asserted, { asOf: "2026-10-12T00:00:00Z" });
  assert.ok(!JSON.stringify(H.toolArguments(form)).includes("requester"));
});

test("Approve's arguments and the tools/call body: task-augmented, its ttl the time to the deadline", () => {
  const form = H.defaultForm("approve", { login: "octo", now: NOW, uuid });
  const r = H.toolArguments(form);
  assert.equal(r.tool, "human.approve");
  assert.equal(r.arguments.action, form.action);
  assert.deepEqual(r.arguments.payload, JSON.parse(form.payload));
  assert.equal(r.arguments.payloadDigest, form.payloadDigest);
  assert.deepEqual(r.arguments.scope, { maxUses: 1, notAfter: "2026-10-09T13:00:00Z" });
  assert.match(H.toolArguments({ ...form, payload: "[]" }).error, /payload is not a JSON object/);
  assert.deepEqual(H.toolsCallBody(r.tool, r.arguments, { now: NOW }), {
    jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "human.approve", arguments: r.arguments, task: { ttl: 3600000 } } });
  assert.deepEqual(H.initializeBody().params.protocolVersion, "2026-07-28");
  assert.deepEqual(H.tasksGetBody("task-1"), { jsonrpc: "2.0", id: 2, method: "tasks/get", params: { taskId: "task-1" } });
});

// A stand-in streamable HTTP server: answers `initialize`, 202 for the notification, then `reply`.
const stubFetch = (reply, seen = []) => async (url, init) => {
  const body = JSON.parse(init.body);
  seen.push({ url, body, headers: init.headers });
  const res = (status, text, sid) => ({ status, text: async () => text, headers: { get: (h) => (h === "mcp-session-id" ? sid || null : null) } });
  if (body.method === "initialize") return res(200, JSON.stringify({ jsonrpc: "2.0", id: body.id, result: { protocolVersion: "2026-07-28" } }), "s-1");
  if (!("id" in body)) return res(202, "");
  return typeof reply === "function" ? reply(body) : res(reply.status, reply.text);
};

test("main's client: initialize, initialized, then the call, with the session id; refusals and transport errors come back as text", async () => {
  const seen = [];
  const handle = { jsonrpc: "2.0", id: 2, result: { task: { taskId: "task-7", status: "working", pollInterval: 5000 } } };
  const args = H.toolArguments(H.defaultForm("ask", { now: NOW, uuid })).arguments;
  const r = await callHitlp("http://localhost:4000/mcp", { tool: "human.ask", arguments: args },
    { fetchImpl: stubFetch({ status: 200, text: JSON.stringify(handle) }, seen), now: NOW });
  assert.deepEqual(seen.map((s) => s.body.method), ["initialize", "notifications/initialized", "tools/call"]);
  assert.equal(seen[2].headers["mcp-session-id"], "s-1");
  assert.equal(seen[2].headers["mcp-protocol-version"], "2026-07-28");
  assert.deepEqual(r.request, H.toolsCallBody("human.ask", args, { now: NOW }));
  assert.equal(r.status, 200);
  assert.equal(H.taskIdOf(r.body), "task-7");
  const poll = await callHitlp("http://localhost:4000/mcp", { taskId: "task-7" }, { fetchImpl: stubFetch({ status: 200, text: "{}" }) });
  assert.deepEqual(poll.request, H.tasksGetBody("task-7"));
  const notJson = await callHitlp("http://h/mcp", { taskId: "t" }, { fetchImpl: stubFetch({ status: 502, text: "<html>bad gateway</html>" }) });
  assert.deepEqual([notJson.status, notJson.body], [502, "<html>bad gateway</html>"]);
  const down = await callHitlp("http://h/mcp", { taskId: "t" }, { fetchImpl: async () => { throw new TypeError("fetch failed", { cause: new Error("connect ECONNREFUSED") }); } });
  assert.match(down.error, /initialize: connect ECONNREFUSED/);
  assert.ok(seen.every((s) => !("authorization" in s.headers)), "no token pasted: no Authorization header on any POST");
  const withToken = [];
  await callHitlp("http://localhost:4000/mcp", { taskId: "task-7" }, { fetchImpl: stubFetch({ status: 200, text: "{}" }, withToken), headers: { authorization: "Bearer rq_pasted" } });
  assert.equal(withToken.length, 3);
  assert.ok(withToken.every((s) => s.headers.authorization === "Bearer rq_pasted"), "the pasted requester token on each POST");
  assert.match((await callHitlp("ftp://h", { taskId: "t" })).error, /http or https/);
  assert.match((await callHitlp("http://h", { tool: "human.do", arguments: {} })).error, /nothing to send/);
  const sse = 'event: message\ndata: {"jsonrpc":"2.0","id":2,"result":{"task":{"taskId":"task-9"}}}\n\n';
  assert.equal(H.taskIdOf(sse), "task-9", "a text/event-stream answer's task id");
});

const view = (props) => renderToStaticMarkup(React.createElement(HitlpProducerView, {
  server: "http://localhost:4000/mcp", form: H.defaultForm("ask", { now: NOW, uuid }), result: null,
  onServer() {}, onKind() {}, onField() {}, onReset() {}, onSubmit() {}, onPoll() {}, onCopy() {}, ...props }));
const fieldsShown = (html) => [...html.matchAll(/data-field="([^"]+)"/g)].map((m) => m[1]);

test("the page draws Server, the Requester token (a password field), Kind and each kind's fields, and after a call the Request and Response panes, with Poll for a task handle", async () => {
  assert.deepEqual(fieldsShown(view({})), ["server", "token", "kind", ...names("ask", "yes-no")]);
  assert.deepEqual(fieldsShown(view({ form: H.defaultForm("approve", { now: NOW, uuid }) })), ["server", "token", "kind", ...names("approve")]);
  const html0 = view({ token: "rq_secret" });
  assert.match(html0, /data-field="token"[^]*?<input[^>]*type="password"[^>]*>/);
  assert.match(html0, /Requester token/);
  assert.match(html0, /data-field="requiresHuman"[^]*?<input type="checkbox"/);
  assert.doesNotMatch(html0, /requester\.agent|Requester agent/);
  assert.doesNotMatch(html0, /mcp\/hitlp/);
  assert.doesNotMatch(view({}), /data-pane=/, "no panes before a Submit");
  const handle = { jsonrpc: "2.0", id: 2, result: { task: { taskId: "task-7", status: "working" } } };
  const args = H.toolArguments(H.defaultForm("ask", { now: NOW, uuid })).arguments;
  const result = await callHitlp("http://h/mcp", { tool: "human.ask", arguments: args }, { fetchImpl: stubFetch({ status: 200, text: JSON.stringify(handle) }), now: NOW });
  const html = view({ result });
  assert.ok(html.includes('data-pane="Request"') && html.includes('data-pane="Response"'));
  const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#x27;");
  assert.ok(html.includes(esc(JSON.stringify(result.request, null, 2))), "the request as sent, pretty");
  assert.ok(html.includes(esc(JSON.stringify(handle, null, 2))), "the response, pretty");
  assert.match(html, /HTTP 200/);
  assert.match(html, /title="tasks\/get task-7"[^>]*>Poll</);
  assert.equal((html.match(/>Copy</g) || []).length, 2);
  const raw = view({ result: { request: result.request, status: 500, body: "boom <b>" } });
  assert.match(raw, /HTTP 500/);
  assert.ok(raw.includes("boom &lt;b&gt;"), "a non-JSON body verbatim");
  assert.doesNotMatch(raw, />Poll</);
  assert.match(view({ result: { request: null, error: "connect ECONNREFUSED" } }), /transport error: connect ECONNREFUSED/);
  assert.match(view({ formError: "context is not JSON" }), /class="err">context is not JSON/);
});

test("main's hitlp:call sends only the pasted token to the form's URL, never the engine token or the surface credential; preload passes three arguments", () => {
  const main = fs.readFileSync(path.join(__dirname, "..", "src", "main.js"), "utf8");
  const start = main.indexOf('ipcMain.handle("hitlp:call"');
  assert.ok(start > 0);
  const handler = main.slice(start, main.indexOf("\n});", start));
  assert.match(handler, /async \(_e, url, op, token\)/);
  assert.doesNotMatch(handler, /engineToken|engineUrl|hitlpSurface/);
  assert.match(handler, /authorization: `Bearer \$\{requesterToken\}`/);
  const preload = fs.readFileSync(path.join(__dirname, "..", "src", "preload.js"), "utf8");
  assert.match(preload, /hitlpCall: \(url, op, token\) => ipcRenderer\.invoke\("hitlp:call", String\(url\),[^]*?token == null \? "" : String\(token\)\)/);
});

test("the Server suggestions: the repo's http and sse servers, never an engine /mcp/hitlp", () => {
  assert.deepEqual(serverSuggestions([
    { name: "a", kind: "http", url: "http://a/mcp" }, { name: "b", kind: "stdio", command: "x" },
    { name: "c", kind: "sse", url: "http://c/sse" }, { name: "d", kind: "http", url: "http://a/mcp" }]),
  ["http://a/mcp", "http://c/sse"]);
  assert.deepEqual(serverSuggestions(null), []);
});

test("the page's root is a fleet-root and every select takes the input class (#2143)", () => {
  for (const form of [H.defaultForm("ask", { now: NOW, uuid }), H.defaultForm("approve", { now: NOW, uuid })]) {
    const html = view({ form });
    assert.match(html, /^<div class="fleet-root hitlpProducer">/);
    const selects = [...html.matchAll(/<select[^>]*>/g)].map((m) => m[0]);
    assert.ok(selects.length >= 3, "Kind, Default on timeout and Priority at least");
    for (const s of selects) assert.match(s, /class="acInput"/, s);
  }
});
