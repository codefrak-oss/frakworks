// A ticket view's Fix and Propose a fix (src/renderer/incident-actions.jsx):
// what the engine's GET /camunda/incident-fixes answer draws, and that
// flow.jsx's DoctorSection puts it between its heading and the runs list.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { actionState, IncidentActionsView } = require("../dist/incident-actions.cjs");

const incident = { id: "inc-1", activityId: "task-start-smith", type: "failedJob", message: "boom",
  processInstanceId: "p-2", createdAt: "2026-09-26T04:00:00Z" };
const render = (props) => renderToStaticMarkup(React.createElement(IncidentActionsView, props));

test("no open incident and nothing going draws nothing", () => {
  assert.equal(actionState(null).show, false);
  assert.equal(render({ fixes: { incident: null, fix: { state: "retried", fixFailed: false }, propose: null } }), "");
});

test("an open incident draws Fix and Propose a fix, with an empty read-only proposal box", () => {
  const html = render({ fixes: { incident, fix: null, propose: null } });
  assert.match(html, /<button[^>]*>Fix<\/button>/);
  assert.match(html, /<button[^>]*>Propose a fix<\/button>/);
  assert.match(html, /<textarea[^>]*readOnly=""/);
  assert.doesNotMatch(html, /fix failed/);
  assert.doesNotMatch(html, /disabled/);
});

test("a Fix that did not fix it says fix failed next to the button, with why on hover", () => {
  const s = actionState({ incident, fix: { state: "failed", fixFailed: true, detail: "the smith found it unfixable" } });
  assert.equal(s.fixFailed, true);
  assert.equal(s.fixDetail, "the smith found it unfixable");
  const html = render({ fixes: { incident, fix: { state: "retried", fixFailed: true, diagnosis: "flaky" }, propose: null } });
  assert.match(html, /Fix<\/button><span class="incFailed" title="flaky">fix failed<\/span>/);
  assert.doesNotMatch(render({ fixes: { incident, fix: { state: "retried", fixFailed: false } } }), /fix failed/);
});

test("a going run disables its own button and says so; the other stays", () => {
  const html = render({ fixes: { incident, fix: { state: "running", fixFailed: false }, propose: null } });
  assert.match(html, /<button[^>]*disabled=""[^>]*>Fix<\/button><span class="muted">fixing…<\/span>/);
  assert.match(html, /<button class="pvButton">Propose a fix<\/button>/);
  assert.match(render({ fixes: { incident, propose: { state: "running" } } }), /placeholder="proposing…"/);
  assert.equal(actionState({ incident: null, fix: { state: "running" } }).show, true, "a going fix shows after its retry cleared the incident");
});

test("a proposal shows in the text area next to Propose a fix", () => {
  const html = render({ fixes: { incident, propose: { state: "proposed", proposal: "Retry: Docker blip, run should pass" } } });
  assert.match(html, /Propose a fix<\/button><textarea[^>]*>Retry: Docker blip, run should pass<\/textarea>/);
  assert.match(render({ fixes: { incident, propose: { state: "failed" } } }), /placeholder="the proposal run failed"/);
});

test("an error from a start is said under the buttons", () => {
  assert.match(render({ fixes: { incident }, error: "engine answered 409: already going" }), /<div class="err">engine answered 409: already going<\/div>/);
});

test("the Doctor section puts the buttons between its heading and the runs list", () => {
  const src = fs.readFileSync(path.join(__dirname, "../src/renderer/flow.jsx"), "utf8");
  const heading = src.indexOf("Doctor\n"), actions = src.indexOf("<IncidentActions issueUrl={issueUrl} />"), runs = src.indexOf("<DoctorRuns");
  assert.ok(heading > 0 && actions > heading && actions < runs, "IncidentActions between the heading and DoctorRuns");
});
