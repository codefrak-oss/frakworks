// The ticket detail view (stage 2, #1281): the Timeline widget's presence
// on the page (its own rendering is test/timeline.test.js), the thread's
// recognised event prefixes, and the error state. The old segmented stage
// bar, cost-by-phase bar and runs table were replaced by Timeline in #101.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { classifyComment, historyRepoOf } = require("../dist/ticket.cjs");
const { TicketDetail } = require("../dist/index.cjs");

const doc = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-ticket.json"), "utf8"));
const render = (props) => renderToStaticMarkup(React.createElement(TicketDetail, props));
const at = doc.now * 1000;

test("the issue, PRs and thread render, and Timeline has its own heading", () => {
  const html = render({ number: doc.ticket, doc, now: at });
  assert.match(html, /class="fleet-root"/);
  assert.doesNotMatch(html, /class="err"/, "no error block for a clean document");
  assert.ok(html.includes(doc.issue.title), "issue title");
  assert.ok(html.includes(doc.issue.url), "GitHub link still available from the detail view");
  assert.match(html, /class="backBtn"/, "back control to the dashboard");
  // Timeline: no window.fleet stub in this render-only test, so effects never
  // fire (renderToStaticMarkup does not run them) and it sits in its initial
  // loading state — asserting that is enough to prove it is wired in at all.
  assert.match(html, /<h2>Timeline<\/h2>/);
  assert.match(html, /asking the engine for the timeline/);
  // No "can't do with current permissions" banner until a loaded timeline carries ticket.blocked (#443).
  assert.doesNotMatch(html, /tdBlocked/);
  // thread
  assert.match(html, /class="tdThread"/);
  assert.equal([...html.matchAll(/class="tdEvent /g)].length, doc.issue.comments.length);
});

test("the fleet's five comment prefixes are recognised as events", () => {
  assert.equal(classifyComment("🤖 Claimed by Smith `smith-runner-1-2`"), "claim");
  assert.equal(classifyComment("📊 Run stats: 48 turns · 29m · ~$3.21 (success)"), "receipt");
  assert.equal(classifyComment("Done. Ticket detail view landed."), "done");
  assert.equal(classifyComment("Blocked: VictoriaLogs refused the query."), "blocked");
  assert.equal(classifyComment("Returning to the Source: the claim lock is gone."), "returned");
  assert.equal(classifyComment("ticket-data: second IPC handler wired, 1a2b3c4"), "prose");
  // every fixture comment lands somewhere, and the five events are all present
  const kinds = doc.issue.comments.map((c) => classifyComment(c.body));
  for (const k of ["claim", "receipt", "done", "blocked", "returned", "prose"]) {
    assert.ok(kinds.includes(k), `fixture exercises ${k}`);
  }
  const html = render({ number: doc.ticket, doc, now: at });
  for (const k of new Set(kinds)) assert.ok(new RegExp(`class="tdEvent ${k}( open)?"`).test(html), `event class ${k}`);
});

test("a fetch failure renders the .err block, not a blank view", () => {
  const html = render({ number: 1281, doc: null, error: "IPC failed: no handler for ticket:fetch" });
  assert.match(html, /class="fleet-root"/, "root still rendered");
  assert.match(html, /class="err"/, "widget-style error block");
  assert.ok(html.includes("IPC failed: no handler for ticket:fetch"), "the message is in it");
  assert.match(html, /class="backBtn"/, "and the way back is still there");
  // a document with no error at all is not the error state
  assert.doesNotMatch(render({ number: doc.ticket, doc, now: at }), /class="err"/);
});

test("one dead source does not blank the other panels", () => {
  const partial = { ...doc, prs_error: "linked PR lookup failed: gh exited with status 1" };
  const html = render({ number: doc.ticket, doc: partial, now: at });
  assert.match(html, /class="err"/);
  assert.ok(html.includes("gh exited with status 1"));
  assert.ok(html.includes(doc.issue.title), "the issue still rendered");
  assert.match(html, /class="tdThread"/, "the thread still renders");
});

test("the description is shown, collapsed past DESCRIPTION_LINES, and PRs are listed", () => {
  const { DESCRIPTION_LINES } = require("../dist/ticket.cjs");
  const body = Array.from({ length: DESCRIPTION_LINES + 5 }, (_, i) => `line ${i + 1}`).join("\n");
  const d = { ...doc, issue: { ...doc.issue, body },
    prs: [{ number: 9, title: "the fix", url: "p9", state: "OPEN", review: "CHANGES_REQUESTED" }] };
  const html = render({ number: doc.ticket, doc: d, now: at });
  assert.match(html, /Description/);
  assert.match(html, new RegExp(`line ${DESCRIPTION_LINES}\\b`));
  assert.doesNotMatch(html, new RegExp(`line ${DESCRIPTION_LINES + 1}\\b`));
  assert.match(html, new RegExp(`show all ${DESCRIPTION_LINES + 5} lines`));
  assert.match(html, />#9</);
  assert.match(html, /changes requested/);
});

test("without a DOM to sanitise in, the rendered body is never used: raw, no toggle", () => {
  const { sanitizeBody } = require("../dist/ticket.cjs");
  assert.equal(sanitizeBody("<p>x</p><script>alert(1)</script>"), null);
  const d = { ...doc, issue: { ...doc.issue, body: "raw text" }, body_html: "<p>RENDERED</p>" };
  const html = render({ number: doc.ticket, doc: d, now: at });
  assert.match(html, /raw text/);
  assert.doesNotMatch(html, /RENDERED/);
  assert.doesNotMatch(html, /tdDescMode/);
});

test("prose, blocked and done comments are shown in full; claim and receipt are one line", () => {
  const comments = [
    { id: "1", author: "smith", createdAt: "2026-09-21T00:00:00Z", body: "🤖 claimed by smith-3\nrun details" },
    { id: "2", author: "smith", createdAt: "2026-09-21T00:01:00Z", body: "📊 receipt\nturns: 12" },
    { id: "3", author: "matt", createdAt: "2026-09-21T00:02:00Z", body: "Why this?\nSECOND PROSE LINE" },
    { id: "4", author: "smith", createdAt: "2026-09-21T00:03:00Z", body: "Blocked: no creds\nSECOND BLOCKED LINE" },
  ];
  const d = { ...doc, issue: { ...doc.issue, comments } };
  // the one-line kinds carry their full text in a tooltip; judge the visible text
  const html = render({ number: doc.ticket, doc: d, now: at }).replace(/ title="[^"]*"/g, "");
  assert.match(html, /SECOND PROSE LINE/);
  assert.match(html, /SECOND BLOCKED LINE/);
  assert.match(html, /claimed by smith-3/);
  assert.doesNotMatch(html, /run details/);
  assert.doesNotMatch(html, /turns: 12/);
});

test("the comments section has a fold with the comment count, open by default", () => {
  const html = render({ number: doc.ticket, doc, now: at });
  assert.match(html, /class="tdFold"/);
  assert.match(html, /▾/);
  assert.ok(html.includes(`<span class="tdFoldCount">${doc.issue.comments.length}</span>`));
  assert.match(html, /class="tdThread"/);
  assert.doesNotMatch(html, /tdEventHead"[^>]*title="(Collapse|Expand)"/, "no per-comment toggle");
});

test("no PRs hides the Pull requests section; no body still says so", () => {
  const d = { ...doc, issue: { ...doc.issue, body: "" }, prs: [] };
  const html = render({ number: doc.ticket, doc: d, now: at });
  assert.match(html, /No description/);
  assert.doesNotMatch(html, /No linked PRs/);
  assert.doesNotMatch(html, /Pull requests/);
});

test("a PRs error still shows the Pull requests section", () => {
  const d = { ...doc, prs: [], prs_error: "boom" };
  const html = render({ number: doc.ticket, doc: d, now: at });
  assert.match(html, /Pull requests/);
  assert.match(html, /boom/);
});

test("the head row and title sit in the pinned wrapper, before the chips (#342)", () => {
  const html = render({ number: doc.ticket, doc, now: at });
  const m = html.match(/<div class="tdPinned">(<div class="head">[\s\S]*?<\/div>)<div class="tdTitle">[\s\S]*?<\/div><\/div>/);
  assert.ok(m, "tdPinned wraps .head then .tdTitle");
  assert.ok(html.indexOf('class="tdPinned"') < html.indexOf('class="chips"'));
  const err = render({ number: 7, doc: null, error: "boom" });
  assert.match(err, /<div class="tdPinned"><div class="head">[\s\S]*<div class="err">/);
});

// #355: the Timeline asks the engine under the issue's current repo, which is
// where the engine recorded its history, not under the name it was opened by.
test("the Timeline's repo is the issue's current repo, falling back to the one asked for", () => {
  assert.equal(historyRepoOf({ repo: "example-org/example-repo", issue_repo: "codefrak-oss/frakworks" }), "codefrak-oss/frakworks");
  assert.equal(historyRepoOf({ repo: "o/r", issue_repo: null }), "o/r");
  assert.equal(historyRepoOf(null), "codefrak-oss/frakworks");
});

test("the detail says \"Asked by <login>\" when askedBy is set, nothing when null", () => {
  const asked = { ...doc, issue: { ...doc.issue, askedBy: "lundmatt1" } };
  assert.match(render({ number: doc.ticket, doc: asked, now: at }), /Asked by lundmatt1/);
  const none = { ...doc, issue: { ...doc.issue, askedBy: null } };
  assert.doesNotMatch(render({ number: doc.ticket, doc: none, now: at }), /Asked by/);
});

// A work item (#100087): the engine sends no HTML, so the markdown is rendered
// here. DOMPurify cannot run in node, so these pass a stand-in sanitiser and
// check what reaches it and the DOM.
const passThrough = (h) => h;
const { Description, Comment } = require("../dist/ticket.cjs");

test("a work item's body with inline SVG renders it as an element, with the switch", () => {
  const body = 'Pitch:\n\n<svg xmlns="http://www.w3.org/2000/svg"><rect width="4" height="4"/></svg>';
  const out = renderToStaticMarkup(React.createElement(Description, { body, html: "", mode: "rendered", onMode() {}, sanitize: passThrough }));
  assert.match(out, /<svg/);
  assert.match(out, /<rect/);
  assert.doesNotMatch(out, /&lt;svg/);
  assert.match(out, /tdDescMode/);
});

test("a work item's comment renders its markdown, not a <pre> of it", () => {
  const c = { id: "1", body: "This is **bold** prose.", author: "engine" };
  const out = renderToStaticMarkup(React.createElement(Comment, { c, mode: "rendered", sanitize: passThrough }));
  assert.match(out, /<strong>bold<\/strong>/);
  assert.doesNotMatch(out, /<pre/);
});

test("a link in locally rendered markdown opens in the browser", () => {
  const out = renderToStaticMarkup(React.createElement(Description, { body: "[x](https://example.com)", mode: "rendered", onMode() {}, sanitize: passThrough }));
  assert.match(out, /<a href="https:\/\/example.com"[^>]*target="_blank"/);
});

test("the engine's HTML is used when it is sent", () => {
  const out = renderToStaticMarkup(React.createElement(Description, { body: "**md**", html: "<p>gh</p>", mode: "rendered", onMode() {}, sanitize: passThrough }));
  assert.match(out, /<p>gh<\/p>/);
});
