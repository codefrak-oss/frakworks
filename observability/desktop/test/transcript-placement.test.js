// Where the live transcript draws in the ticket detail (#867): directly under
// the Timeline's running `coding` card, with that card's ▾/▸ Transcript fold,
// which is scraping on/off as well (#883), while one runs; after the Jev section, under its
// own "Live transcript" heading only when the Timeline draws no cards; with
// cards but none running, under the most recent card. And the pure parts of scraping off:
// the tail is closed, and the byline says paused, not ended.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { TranscriptPane, mergeTranscript, isLive, liveNote } = require("../dist/ticket.cjs");
const { runningSmith, heardTimeline } = require("../dist/timeline.cjs");
const { TicketDetail, TicketDetailView, TranscriptControls } = require("../dist/index.cjs");

const HERE = path.join(__dirname, "..");
const doc = JSON.parse(fs.readFileSync(path.join(HERE, "fixture-ticket.json"), "utf8"));
const fixture = JSON.parse(fs.readFileSync(path.join(HERE, "fixture-ticket-timeline.json"), "utf8"));

// The fixture's timeline with its last step running under `role`, nothing else running.
const timelineWith = (role) => {
  const steps = fixture.steps.map((s) => ({ ...s, running: false }));
  steps.push({ ...steps[steps.length - 1], role, round: 9, start: 4e9, running: true, outcome: "" });
  return { ...fixture, steps };
};
const live = mergeTranscript(null, {
  events: [{ key: "a", lines: ["Reading the ticket."] }], live: true, liveKnown: true,
  session: "/logs/issue-1283.log", cursor: "c1",
});
const render = (C, props) => renderToStaticMarkup(React.createElement(C, props));
const base = { number: doc.ticket, doc, now: doc.now * 1000, transcript: live };

for (const role of ["coding", "correctness", "security", "refine", "reproduce", "verify", "design", "replan", "accept", "propose", "doctor"]) {
  test(`runningSmith finds the running ${role} step`, () => {
    const t = timelineWith(role);
    assert.equal(runningSmith(t), t.steps.find((s) => s.running));
    assert.equal(runningSmith(t).role, role);
  });
}

test("runningSmith is null with no running step", () => {
  assert.equal(runningSmith(null), null);
  assert.equal(runningSmith({ ...fixture, steps: fixture.steps.map((s) => ({ ...s, running: false })) }), null);
});

test("while a coding step runs, the transcript and summary panel sit directly under its card, before the flow body", () => {
  const html = render(TicketDetail, { ...base, timeline: timelineWith("coding"), scraping: true });
  const card = html.indexOf("tlCard coding running tlActive");
  assert.ok(card > 0, "the running coding card is drawn");
  const cardEnd = html.indexOf("</div></div>", html.indexOf("tlCardCtl", card));
  const pane = html.indexOf('<div class="tlTranscript"');
  assert.ok(pane > card, "the pane follows the card");
  assert.equal(pane, cardEnd + "</div></div>".length, "directly after the card");
  const withSummary = render(TicketDetailView, { ...base, timeline: timelineWith("coding"), transcriptOpen: true, summarized: { error: "x" } });
  assert.ok(withSummary.indexOf('class="tdSummary"') > withSummary.indexOf('<div class="tlTranscript"'), "the summary panel moves with the pane");
  assert.ok(html.indexOf('class="tdTranscript"') > pane);
  assert.ok(html.indexOf('class="tdTranscript"') < html.indexOf("Comments"));
  // the pane is inside the Timeline, and the Timeline is mounted before the flow body.
  const src = fs.readFileSync(path.join(HERE, "src/renderer/detail.jsx"), "utf8");
  assert.ok(src.indexOf("<Timeline ") < src.indexOf("<FlowBody "));
  assert.doesNotMatch(html, /<h2>Live transcript/, "no second Live transcript heading after the flow body");
  // the fold is the card's one transcript control: open is scraping on
  assert.match(html, /class="tlCtl tlCtlFold" aria-expanded="true"[^>]*>▾ Transcript</);
  assert.doesNotMatch(html, /tlCtlScrape|Scraping on|aria-pressed/);
  assert.match(html, /● live · tailing/);
});

test("with cards but none running, the pane sits in the track after the last card, with no Live transcript heading", () => {
  const ended = { ...fixture, steps: fixture.steps.map((s) => ({ ...s, running: false })) };
  const html = render(TicketDetailView, { ...base, timeline: ended, liveOpen: true });
  const track = html.indexOf('class="tlTrack"');
  const lastCard = html.lastIndexOf('class="tlCard ');
  const pane = html.indexOf('<div class="tlTranscript"');
  assert.ok(track > 0 && lastCard > track, "the cards are drawn");
  assert.ok(pane > lastCard, "the pane follows the last card");
  assert.ok(html.indexOf('class="tdTranscript"') > pane, "the transcript is in the pane");
  assert.doesNotMatch(html, /<h2>Live transcript/);
});

test("with no timeline, the Live transcript heading fallback draws", () => {
  const html = render(TicketDetailView, { ...base, timeline: null, liveOpen: true });
  assert.match(html, /<h2>Live transcript/);
  assert.doesNotMatch(html, /tlTranscript/);
});

test("collapsed hides the pane and the summary panel; the control says so", () => {
  const html = render(TicketDetailView, { ...base, timeline: timelineWith("coding"), transcriptOpen: false, summarized: { error: "x" } });
  assert.match(html, /aria-expanded="false"[^>]*>▸ Transcript</);
  assert.doesNotMatch(html, /tlTranscript|tdTranscript|tdSummary/);
  assert.doesNotMatch(html, /<h2>Live transcript/);
});

test("the fold drives scraping: an open click turns it on, a close click turns it off", () => {
  const calls = [];
  const onScraping = (on) => calls.push(on);
  const click = (scraping) => {
    const el = TicketDetail({ ...base, timeline: timelineWith("coding"), scraping, onScraping });
    assert.equal(el.props.transcriptOpen, scraping, "the pane's state is the loop's");
    assert.equal(el.props.scraping, undefined, "no separate scraping prop on the view");
    assert.equal(el.props.onScraping, undefined);
    const ctl = TranscriptControls({ open: el.props.transcriptOpen, onOpen: el.props.onTranscriptOpen });
    ctl.props.children.find((c) => c && c.props.className === "tlCtl tlCtlFold").props.onClick();
  };
  // TicketDetail uses hooks; call it inside a render so they have a dispatcher.
  const Probe = ({ scraping }) => { click(scraping); return null; };
  render(Probe, { scraping: false });
  render(Probe, { scraping: true });
  assert.deepEqual(calls, [true, false]);
});

test("with a running security step, the transcript sits under its card with the controls", () => {
  const html = render(TicketDetail, { ...base, timeline: timelineWith("security"), scraping: true });
  assert.match(html, /tlCard security running tlActive/);
  assert.match(html, /tlCardCtl/);
  assert.match(html, /tlTranscript/);
  assert.doesNotMatch(html, /<h2>Live transcript/);
});

test("a timeline load that draws no timeline is heard as null, and the transcript falls back after Jev", () => {
  // Timeline reports every load: an error or a 404 draws no cards, so it is
  // heard as null and TicketDetail drops its last (coding) timeline with it.
  assert.equal(heardTimeline({ error: "IPC failed: engine down" }), null);
  assert.equal(heardTimeline({ notFound: true }), null);
  assert.equal(heardTimeline(null), null);
  const coding = timelineWith("coding");
  assert.equal(heardTimeline({ timeline: coding }), coding);
  const src = fs.readFileSync(path.join(HERE, "src/renderer/timeline.jsx"), "utf8");
  assert.match(src, /heard\.current\(heardTimeline\(r\)\)/, "every load is heard, not only one with a timeline");
  const detail = fs.readFileSync(path.join(HERE, "src/renderer/detail.jsx"), "utf8");
  assert.match(detail, /onTimeline=\{\(t\) => \{[^}]*if \(t\) setBlocked[\s\S]*?setTimeline\(t\);/, "a null is stored, not dereferenced");
  // What the detail draws then, with the coding run's transcript still live:
  // the transcript is on the page, under its own heading after Jev.
  const html = render(TicketDetail, { ...base, timeline: heardTimeline({ error: "IPC failed: engine down" }), scraping: false, liveOpen: true });
  assert.doesNotMatch(html, /tlCardCtl|tlTranscript/);
  const h2 = html.indexOf("<h2>Live transcript");
  assert.ok(h2 > html.indexOf("<h2>Timeline</h2>"));
  assert.ok(html.indexOf('class="tdTranscript"') > h2);
  assert.match(html, /Reading the ticket\./);
});

test("paused closes the tail; on tails as before; paused is not 'run ended' or 'unknown'", () => {
  assert.equal(isLive(live), true);
  assert.equal(liveNote(live, false), "● live · tailing");
  assert.equal(liveNote(live, true), "scraping off · tail closed");
  const ended = { ...live, live: false, liveKnown: true };
  assert.equal(liveNote(ended, true), "run ended · tail closed", "a run already over says so, paused or not");
  // There is no "unknown" state any more: a run is live between `session` `started` and `ended` (#976).
  const between = { ...live, live: false, liveKnown: true, flowRunning: true };
  assert.equal(liveNote(between, false), "run ended · waiting for the next step");
  assert.equal(liveNote(between, true), "run ended · waiting for the next step");
  const pane = render(TranscriptPane, { transcript: live, paused: true });
  assert.match(pane, /<span class="muted">scraping off · tail closed<\/span>/);
});

test("the transcript loop reads scraping before opening the tail, and resumes from its cursor without a reset", () => {
  // The loop is `transcriptLoop` (ticket.jsx, #976); index.jsx hands it scraping as `paused`.
  const idx = fs.readFileSync(path.join(HERE, "src/renderer/index.jsx"), "utf8");
  assert.match(idx, /paused: \(\) => !scrapingNow\.current/);
  const src = fs.readFileSync(path.join(HERE, "src/renderer/ticket.jsx"), "utf8");
  assert.match(src, /if \(stopped \|\| !tail \|\| handle \|\| !current \|\| ending \|\| paused\(\)\) return;/);
  assert.match(src, /pause: \(\) => closeTail\(\)/);
  const resume = src.slice(src.indexOf("resume: () =>"), src.indexOf("\n", src.indexOf("resume: () =>")));
  assert.doesNotMatch(resume, /setTranscript|acc = null/, "resume keeps `acc` (cursor, lines, dropped)");
});

test("a fresh ticket starts collapsed with scraping off, and nothing remembers it open", () => {
  const html = render(TicketDetail, { ...base, timeline: timelineWith("coding") });
  assert.match(html, /class="tlCtl tlCtlFold" aria-expanded="false"[^>]*>▸ Transcript</);
  assert.doesNotMatch(html, /tlTranscript|tdTranscript/);
  const detail = fs.readFileSync(path.join(HERE, "src/renderer/detail.jsx"), "utf8");
  assert.doesNotMatch(detail, /ticket\.transcriptOpen|readTranscriptOpen|saveTranscriptOpen|TRANSCRIPT_OPEN_KEY/);
  const src = fs.readFileSync(path.join(HERE, "src/renderer/index.jsx"), "utf8");
  assert.match(src, /useState\(false\);\n\s*const scrapingNow = useRef\(false\)/);
  assert.match(src, /useEffect\(\(\) => setScraping\(false\), \[number\]\)/);
  assert.doesNotMatch(src, /setScraping\(true\)/);
  // with scraping off, the byline says the tail is closed after the initial fetch
  assert.equal(liveNote(live, true), "scraping off · tail closed");
});

test("with review running after a work step ended, the pane under the review card drops the work run's lines once a review read lands (#947)", () => {
  const WORK = "/logs/issue-1283-work-opus-20261005-100000.log";
  const REVIEW = "/logs/issue-1283-review-opus-20261005-103000.log";
  const t = timelineWith("correctness");
  t.steps[t.steps.length - 2] = { ...t.steps[t.steps.length - 2], role: "coding", running: false, outcome: "done" };
  let acc = { ...mergeTranscript(null, { session: WORK, live: true, liveKnown: true, cursor: "c1",
    events: [{ key: "w", lines: ["→ Edit  the work run's line"] }] }), flowRunning: true };
  acc = { ...mergeTranscript(acc, { session: null, live: false, liveKnown: true, events: [] }), flowRunning: true };
  assert.equal(isLive(acc), false, "between the steps no tail is open: the review's `session` `started` opens one (#976)");
  acc = { ...mergeTranscript(acc, { session: REVIEW, live: true, liveKnown: true, cursor: "c2",
    events: [{ key: "r", lines: ["Reviewing the diff."] }] }), flowRunning: true };
  const html = render(TicketDetail, { ...base, timeline: t, transcript: acc, scraping: true });
  const card = html.indexOf("tlCard correctness running tlActive");
  assert.ok(card > 0, "the running review card is drawn");
  const pane = html.indexOf('<div class="tlTranscript"');
  assert.ok(pane > card, "the pane is under the review card");
  assert.doesNotMatch(html, /the work run&#x27;s line|the work run's line/);
  assert.match(html, /Reviewing the diff\./);
  assert.match(html, /issue-1283-review-opus/);
  assert.match(html, /● live · tailing/);
});

test("the non-running Live transcript section starts collapsed and opens on its fold (#1700)", () => {
  const props = { ...base, timeline: heardTimeline({ error: "x" }), scraping: false };
  const shut = render(TicketDetail, props);
  assert.match(shut, /<h2>Live transcript <button type="button" class="tlCtl tlCtlFold" aria-expanded="false"[^>]*>▸<\/button><\/h2>/);
  assert.doesNotMatch(shut, /tdTranscript|Reading the ticket\./);
  let opened = null;
  const view = TicketDetailView({ ...base, liveOpen: false, onLiveOpen: (o) => { opened = o; } });
  const find = (n) => {
    if (!n || typeof n !== "object") return null;
    if (Array.isArray(n)) { for (const c of n) { const r = find(c); if (r) return r; } return null; }
    if (n.props && n.props.className === "tlCtl tlCtlFold" && n.props.onClick) return n;
    return n.props ? find(n.props.children) : null;
  };
  find(view).props.onClick();
  assert.equal(opened, true, "the fold asks to open");
  const open = render(TicketDetailView, { ...base, liveOpen: true });
  assert.match(open, /aria-expanded="true"[^>]*>▾<\/button><\/h2>/);
  assert.match(open, /tdTranscript/);
  assert.match(open, /Reading the ticket\./);
  const src = fs.readFileSync(path.join(HERE, "src/renderer/detail.jsx"), "utf8");
  assert.match(src, /useEffect\(\(\) => setLiveOpen\(false\), \[repo, number\]\)/, "collapsed again on a new ticket");
});
