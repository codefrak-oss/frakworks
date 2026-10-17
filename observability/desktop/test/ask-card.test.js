// What is left of the ask card (src/renderer/ask-card.jsx, #100070): the
// engine's hint names the HITLP task a waiting ticket mounts inline
// (hitlp-task.jsx TicketTask); the read-only decision card, its option list
// and its "Decide on the HITLP server" link are gone from the renderer.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const card = require("../dist/ask-card.cjs");
const { TicketDetail } = require("../dist/index.cjs");

const board = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-issues.json"), "utf8"));
const RENDERER = path.join(__dirname, "..", "src", "renderer");

test("the hint's task id is the newest announcing comment's; a decision URL or no hint gives none", () => {
  const c = (body) => ({ body });
  assert.equal(card.hintTaskIdOf([c("— engine feature `f`. HITLP task t-9 on the engine's HITLP server.")]), "t-9");
  assert.equal(card.hintTaskIdOf([c("HITLP task `t-1` on the engine's HITLP server."), c("HITLP task t-2 on the engine's HITLP server.")]), "t-2");
  assert.equal(card.hintTaskIdOf([c("HITLP task t-1 on the engine's HITLP server."), c("Decide it on https://h/d/2.")]), null);
  assert.equal(card.hintTaskIdOf([c("no hint here")]), null);
  assert.equal(card.hintTaskIdOf(null), null);
});

test("the read-only card is gone: no DecisionCard, DecideLink, decisionHintOf, useDecisionHint or askOption in the renderer", () => {
  for (const k of ["DecisionCard", "DecideLink", "decisionHintOf", "useDecisionHint", "summarize"]) assert.equal(card[k], undefined, k);
  for (const f of fs.readdirSync(RENDERER).filter((n) => /\.jsx?$/.test(n))) {
    const src = fs.readFileSync(path.join(RENDERER, f), "utf8");
    assert.doesNotMatch(src, /Decide HITLP task|on the engine's HITLP server<\/span>|className="askOption|<DecisionCard|<DecideLink/, f);
  }
  const css = fs.readFileSync(path.join(RENDERER, "styles.css"), "utf8");
  assert.doesNotMatch(css, /\.askOption|\.decideTask|a\.decideLink/);
});

test("the issue detail mounts the inline task above the ticket read only while a question waits", () => {
  const issue = board.issues.find((i) => i.pendingQuestion);
  assert.ok(issue, "fixture-issues.json has an issue waiting on a question");
  const html = renderToStaticMarkup(React.createElement(TicketDetail, { repo: issue.repo, number: issue.number, issue, flowResult: null, now: Date.now() }));
  const task = html.indexOf("reading the HITLP task"), engine = html.indexOf("asking the engine for the ticket");
  assert.ok(task > 0 && task < engine, "task before the ticket read");
  const quiet = renderToStaticMarkup(React.createElement(TicketDetail, { repo: issue.repo, number: issue.number,
    issue: { ...issue, pendingQuestion: null }, flowResult: null, now: Date.now() }));
  assert.ok(!quiet.includes("reading the HITLP task") && !quiet.includes("htView"));
});

// Answer attachments (#1730): the engine's endpoint stays, with its client.
const { uploadAttachment } = require("../src/engine-data");

test("uploadAttachment posts name, mime, base64 content (no by) to /intake/<id>/attachments", async () => {
  const id = "0a1b2c3d-0000-0000-0000-000000000001";
  let seen;
  const fetch = async (url, init) => { seen = { url, init }; return { ok: true, status: 200, text: async () => "{}" }; };
  const r = await uploadAttachment(id, { name: "spec.md", mime: "text/markdown", content: "IyBTcGVj" }, "lundmatt1", { env: { ENGINE_URL: "http://e:1" }, fetch });
  assert.equal(r.ok, true);
  assert.equal(seen.url, `http://e:1/intake/${id}/attachments`);
  assert.deepEqual(JSON.parse(seen.init.body), { name: "spec.md", content: "IyBTcGVj", mime: "text/markdown" });
  assert.match((await uploadAttachment("nope", { name: "a", content: "" }, "x")).error, /not an intake id/);
});
