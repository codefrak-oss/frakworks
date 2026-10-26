// The ticket detail's Implementation summary (#100141): between Description and
// Pull requests on a closed ticket the engine gave one; absent otherwise.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { TicketDetail } = require("../dist/index.cjs");

const doc = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-ticket.json"), "utf8"));
const SUMMARY = "Adds an implementationSummary to work results, kept per ticket by the engine and shown here once merged.";
const merged = [{ number: 9, title: "t", url: "https://github.com/o/r/pull/9", state: "MERGED", review: null, mergeSha: "abc" }];
const render = (issue, prs = merged) => renderToStaticMarkup(React.createElement(TicketDetail,
  { number: doc.ticket, now: doc.now * 1000, doc: { ...doc, prs, issue: { ...doc.issue, ...issue } } }));

test("a completed ticket shows its summary between Description and Pull requests", () => {
  const html = render({ state: "CLOSED", implementationSummary: SUMMARY });
  const at = html.indexOf("<h2>Implementation summary</h2>");
  assert.ok(at > html.indexOf("<h2>Description</h2>"), "after Description");
  assert.ok(at < html.indexOf("<h2>Pull requests</h2>"), "before Pull requests");
  assert.ok(html.includes(SUMMARY));
});

test("an open ticket, or a completed one with no summary, draws no section", () => {
  for (const issue of [{ state: "OPEN", implementationSummary: SUMMARY }, { state: "CLOSED", implementationSummary: null },
    { state: "CLOSED", implementationSummary: "" }]) {
    const html = render(issue);
    assert.ok(!html.includes("Implementation summary"), JSON.stringify(issue));
    assert.ok(!html.includes("tdImplSummary"), JSON.stringify(issue));
  }
});
