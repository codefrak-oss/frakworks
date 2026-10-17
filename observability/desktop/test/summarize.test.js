// The running coding card's Summarize button (#881, was the Spy button of
// #423): drawn only on that card, its in-flight state, and the panel that shows
// the engine's answer and errors under the card. The request itself is
// src/engine-data.js summarizeTicket (test/engine-data.test.js).
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { SummaryPanel, summarizeOn, mergeTranscript, EMPTY_TRANSCRIPT } = require("../dist/ticket.cjs");
const { TicketDetail, TicketDetailView, summaryFor } = require("../dist/index.cjs");

const HERE = path.join(__dirname, "..");
const doc = JSON.parse(fs.readFileSync(path.join(HERE, "fixture-ticket.json"), "utf8"));
const fixture = JSON.parse(fs.readFileSync(path.join(HERE, "fixture-ticket-timeline.json"), "utf8"));
const timelineWith = (role) => {
  const steps = fixture.steps.map((s) => ({ ...s, running: false }));
  steps.push({ ...steps[steps.length - 1], role, round: 9, start: 4e9, running: true, outcome: "" });
  return { ...fixture, steps };
};
const live = mergeTranscript(null, { events: [{ key: "a", lines: ["Reading the ticket."] }], live: true, liveKnown: true, cursor: "c1" });
const render = (C, props) => renderToStaticMarkup(React.createElement(C, props));
const base = { number: doc.ticket, doc, now: doc.now * 1000, transcript: live };
const summary = { runId: "r1", mode: "work", model: "claude-opus-5-5",
  summary: "It is storing each running session so the engine can summarize it.", now: "Writing the endpoint's tests." };

test("the coding card's controls are, in order, the fold and Summarize", () => {
  const html = render(TicketDetailView, { ...base, timeline: timelineWith("coding") });
  const fold = html.indexOf("tlCtlFold"), sum = html.indexOf("tlCtl tlCtlSummarize");
  assert.ok(fold > 0 && sum > fold, html);
  assert.doesNotMatch(html, /tlCtlScrape|Scraping/);
  assert.match(html, /<button type="button" class="tlCtl tlCtlSummarize"[^>]*>Summarize<\/button>/);
  assert.doesNotMatch(html, /sessionSummary|tdSummary/);
});

test("no Summarize button without a running step, whatever the transcript; any running role has one", () => {
  assert.doesNotMatch(render(TicketDetail, { ...base, transcript: undefined }), /tlCtlSummarize/);
  assert.doesNotMatch(render(TicketDetail, { ...base }), /tlCtlSummarize/);
  assert.match(render(TicketDetailView, { ...base, timeline: timelineWith("security") }), /tlCtlSummarize/);
  assert.doesNotMatch(render(TicketDetail, { ...base, transcript: { ...EMPTY_TRANSCRIPT, lines: ["done"], liveKnown: true, polled: true } }), /tlCtlSummarize/);
});

test("while it asks, the button says Summarizing… and is disabled", () => {
  const html = render(TicketDetailView, { ...base, timeline: timelineWith("coding"), summarized: { loading: true } });
  assert.match(html, /<button type="button" class="tlCtl tlCtlSummarize" disabled=""[^>]*>Summarizing…<\/button>/);
  assert.equal(render(SummaryPanel, { state: { loading: true } }), "");
  assert.equal(render(SummaryPanel, { state: null }), "");
});

test("the answer shows under the card as Session and Now", () => {
  const html = render(TicketDetailView, { ...base, timeline: timelineWith("coding"), transcriptOpen: true, summarized: { summary } });
  assert.match(html, /<dt>Session<\/dt><dd>It is storing each running session/);
  assert.match(html, /<dt>Now<\/dt><dd>Writing the endpoint&#x27;s tests\.<\/dd>/);
  assert.ok(html.indexOf('class="tdSummary"') > html.indexOf('class="tlTranscript"'));
  assert.ok(html.indexOf('class="tdSummary"') < html.indexOf('class="tdTranscript"'));
  assert.doesNotMatch(html, /class="err"/);
  assert.match(html, /class="tlCtl tlCtlSummarize"[^>]*>Summarize</, "enabled again");
});

test("an error renders in the .err block and the button stays enabled", () => {
  const error = "engine answered 409 for POST /ticket/o/r/5/summarize: no Smith is running on o/r#5";
  const html = render(TicketDetailView, { ...base, timeline: timelineWith("coding"), transcriptOpen: true, summarized: { error } });
  assert.match(html, /<div class="err">engine answered 409 .*no Smith is running on o\/r#5<\/div>/);
  assert.match(html, /<button type="button" class="tlCtl tlCtlSummarize" title/);
  assert.doesNotMatch(html, /sessionSummary/);
});

test("summarizeOn calls window.fleet.summarize with owner, repo and number, and maps its answer to a panel state", async () => {
  const calls = [];
  const fleet = { summarize: async (...a) => { calls.push(a); return { summary }; } };
  assert.deepEqual(await summarizeOn(fleet, "acme/widgets", 7), { summary });
  assert.deepEqual(calls, [["acme", "widgets", 7]]);
  assert.deepEqual(await summarizeOn({ summarize: async () => ({ error: "nope" }) }, "a/b", 1), { error: "nope" });
  assert.deepEqual(await summarizeOn({ summarize: async () => { throw new Error("IPC down"); } }, "a/b", 1), { error: "IPC down" });
  assert.deepEqual(await summarizeOn(undefined, "a/b", 1), { error: "the app has no summarize channel" });
});

test("Summarize opens the collapsed pane through the fold's own callback, and leaves it alone when already open (#940)", async () => {
  const calls = [];
  const onScraping = (on) => calls.push(on);
  let captured;
  const Probe = ({ scraping }) => {
    captured = TicketDetail({ ...base, timeline: timelineWith("coding"), scraping, onScraping });
    return null;
  };
  render(Probe, { scraping: false });
  await captured.props.onSummarize();
  render(Probe, { scraping: true });
  await captured.props.onSummarize();
  assert.deepEqual(calls, [true], "called with true when collapsed, not called when already open");
});

test("a review run's summary shows in the same panel under its correctness card", () => {
  const review = { ...summary, mode: "review" };
  const html = render(TicketDetailView, { ...base, timeline: timelineWith("correctness"), transcriptOpen: true, summarized: { summary: review } });
  assert.match(html, /tlCtlSummarize/);
  assert.match(html, /Session/);
  assert.match(html, /It is storing each running session/);
  assert.match(html, /Writing the endpoint&#x27;s tests\./);
});

// #945: a summary belongs to the step it was asked for. TicketDetail's
// onTimeline runs each new timeline through summaryFor, and its render draws
// only what summaryFor keeps.
const roundOf = (role, round, start) => {
  const t = timelineWith(role);
  const steps = t.steps.slice();
  steps[steps.length - 1] = { ...steps[steps.length - 1], round, start };
  return { ...t, steps };
};
const asked = { summary, step: "coding|9|4000000000" };
const panelOf = (timeline, summarized) => {
  let captured;
  const Probe = () => { captured = TicketDetail({ ...base, timeline, summarized, transcriptOpen: true, scraping: true }); return null; };
  render(Probe, {});
  return captured;
};

test("a summary stays while timeline polls keep its running step (#945)", () => {
  const same = timelineWith("coding");
  assert.equal(summaryFor(asked, same), asked);
  assert.equal(summaryFor(asked, timelineWith("coding")), asked, "a fresh poll with the same role, round and start");
  assert.equal(panelOf(same, asked).props.summarized, asked);
  assert.match(render(TicketDetailView, { ...base, timeline: same, transcriptOpen: true, summarized: summaryFor(asked, same) }), /tdSummary/);
});

test("a summary is cleared on a new round, a new role, or no running step (#945)", () => {
  const noneRunning = { ...fixture, steps: fixture.steps.map((s) => ({ ...s, running: false })) };
  for (const [what, t] of [["new round", roundOf("coding", 10, 5e9)], ["new role", timelineWith("correctness")], ["none running", noneRunning]]) {
    assert.equal(summaryFor(asked, t), null, what);
    assert.equal(panelOf(t, asked).props.summarized, null, what);
    assert.doesNotMatch(render(TicketDetailView, { ...base, timeline: t, transcriptOpen: true, summarized: summaryFor(asked, t) }), /tdSummary/, what);
  }
  assert.equal(summaryFor({ summary, step: null }, noneRunning), null);
});

test("onTimeline drops the summary through summaryFor when the running step changes (#945)", () => {
  const src = fs.readFileSync(path.join(HERE, "src/renderer/detail.jsx"), "utf8");
  assert.match(src, /setTimeline\(t\);\s*setSummarized\(\(s\) => summaryFor\(s, t\)\);/);
});
