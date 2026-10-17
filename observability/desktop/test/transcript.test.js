// The renderer side of the live transcript pane (stage 4, #1283): the merge
// that makes the read and the live tail incremental, when the pane appears, what it says when the
// run ends, and the promise that a VictoriaLogs failure lands inside the pane
// rather than blanking the detail view.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { TranscriptPane, mergeTranscript, hasTranscript, EMPTY_TRANSCRIPT, TRANSCRIPT_MAX_LINES, isLive, liveNote } = require("../dist/ticket.cjs");
const { TicketDetail } = require("../dist/index.cjs");
const { renderRows } = require("../src/transcript-data");

const HERE = path.join(__dirname, "..");
const doc = JSON.parse(fs.readFileSync(path.join(HERE, "fixture-ticket.json"), "utf8"));
const fixture = fs.readFileSync(path.join(HERE, "fixture-transcript.jsonl"), "utf8")
  .split("\n").filter(Boolean);
const expected = fs.readFileSync(path.join(HERE, "fixture-transcript.expected.txt"), "utf8")
  .replace(/\n$/, "").split("\n\n");
const LOG = "/home/user/frakworks/dispatch/logs/issue-1283-implementation-opus-20260921-031702.log";

const rows = fixture.map((msg, i) => ({
  _time: `2026-09-21T03:17:${String(10 + i).padStart(2, "0")}.000000000Z`,
  _msg: msg, filename: LOG,
}));
// `liveKnown: true` by default — the ordinary case is a poll that reached
// `smith_run_active` and got an answer, whatever that answer was.
const docFor = (rs, extra = {}) => ({
  ticket: 1283, live: true, liveKnown: true, host: "smith-runner", kind: "job",
  model: "opus", session: LOG, error: null, ...renderRows([...rs].reverse()), ...extra,
});

const render = (props) => renderToStaticMarkup(React.createElement(TicketDetail, props));
const renderPane = (transcript) => renderToStaticMarkup(
  React.createElement(TranscriptPane, { transcript }));

test("a line reaches the pane no later than the 4 s poll did: the tail's offset plus refresh interval is under 4 s (#1006)", () => {
  const { TAIL_OFFSET, TAIL_REFRESH_INTERVAL } = require("../src/transcript-tail");
  const secs = (d) => Number(String(d).replace(/s$/, ""));
  assert.ok(secs(TAIL_OFFSET) + secs(TAIL_REFRESH_INTERVAL) < 4, `${TAIL_OFFSET} + ${TAIL_REFRESH_INTERVAL}`);
});

test("a first merge renders the whole session's smith-tail lines", () => {
  const t = mergeTranscript(null, docFor(rows));
  assert.deepEqual(t.lines, expected);
  assert.equal(t.session, LOG);
  assert.equal(t.live, true);
  assert.equal(t.polled, true);
});

test("a read or tail batch that re-delivers events appends only the new ones", () => {
  const first = mergeTranscript(null, docFor(rows.slice(0, 4)));
  // the cursor range is inclusive, and the tail opens with a look-back, so the next batch repeats events
  const second = mergeTranscript(first, docFor(rows.slice(3)));
  assert.deepEqual(second.lines, expected);
  // and a batch with nothing new changes neither the lines nor their identity
  const third = mergeTranscript(second, docFor(rows.slice(-1)));
  assert.deepEqual(third.lines, second.lines);
  assert.equal(third.lines, second.lines, "the same array, so React skips the re-render work");
});

test("a new run for the same ticket clears the pane instead of concatenating", () => {
  const first = mergeTranscript(null, docFor(rows));
  const other = "/home/user/frakworks/dispatch/logs/issue-1283-rework-opus-20260921-081500.log";
  const second = mergeTranscript(first, docFor(rows.slice(0, 2), { session: other }));
  assert.equal(second.session, other);
  assert.deepEqual(second.lines, renderRows([...rows.slice(0, 2)].reverse())
    .events.flatMap((e) => e.lines));
  assert.ok(second.lines.length < first.lines.length);
});

test("the pane is a tail, not an archive", () => {
  const many = Array.from({ length: TRANSCRIPT_MAX_LINES + 50 }, (_, i) => ({
    key: `k${i}`, time: null, lines: [`line ${i}`],
  }));
  const t = mergeTranscript(null, { session: LOG, live: true, events: many });
  assert.equal(t.lines.length, TRANSCRIPT_MAX_LINES);
  assert.equal(t.lines[t.lines.length - 1], `line ${many.length - 1}`, "the newest lines are kept");
});

test("the pane appears for a live run, and not for a ticket with nothing to show", () => {
  assert.equal(hasTranscript(null), false);
  assert.equal(hasTranscript(EMPTY_TRANSCRIPT), false);
  assert.equal(hasTranscript({ ...EMPTY_TRANSCRIPT, live: true }), true);
  assert.equal(hasTranscript({ ...EMPTY_TRANSCRIPT, lines: ["x"] }), true);
  assert.equal(hasTranscript({ ...EMPTY_TRANSCRIPT, error: "boom" }), true);

  const props = { number: 1283, doc, error: null, now: doc.now * 1000 };
  assert.ok(!render(props).includes("Live transcript"), "no pane without a run");
  const live = render({ ...props, transcript: mergeTranscript(null, docFor(rows)), liveOpen: true });
  assert.ok(live.includes("Live transcript"));
  assert.ok(live.includes("tdTranscript"));
});

test("a live run says it is tailing; an ended one says the tail is closed", () => {
  const live = renderPane(mergeTranscript(null, docFor(rows)));
  assert.ok(live.includes("● live · tailing"), live);
  assert.ok(!live.includes("polling"), live);
  const ended = renderPane(mergeTranscript(null, docFor(rows, { live: false })));
  assert.ok(ended.includes("run ended · tail closed"), ended);
  assert.ok(!ended.includes("polling"), ended);
  // the transcript it already read stays on screen
  assert.ok(ended.includes("── result:"));
  // and the log file's basename identifies which run, with the host
  assert.ok(ended.includes("issue-1283-implementation-opus-20260921-031702.log"));
  assert.ok(ended.includes("smith-runner"));
});

test("between two steps the byline says it is watching for the next one, then live again on the next run (#947)", () => {
  const NEXT = LOG.replace("implementation", "review");
  const live = { ...mergeTranscript(null, docFor(rows)), flowRunning: true };
  const ended = { ...mergeTranscript(live, docFor([], { live: false, session: null })), flowRunning: true };
  const html = renderPane(ended);
  assert.ok(html.includes("run ended · waiting for the next step"), html);
  assert.ok(!html.includes("tail closed"), html);
  const next = { ...mergeTranscript(ended, docFor(rows.slice(0, 1), { session: NEXT })), flowRunning: true };
  const again = renderPane(next);
  assert.ok(again.includes("● live · tailing"), again);
  assert.ok(again.includes("issue-1283-review-opus"), again);
  // with the flow over too, the ended byline is the old one
  assert.ok(renderPane({ ...ended, flowRunning: false }).includes("run ended · tail closed"));
});

test("the pane follows only a live run: no liveness gate, no retry cadence, no poll (#976, #1006)", () => {
  // The engine's `session` frames say when a run is live; a failed read while
  // one is keeps it live, said in the pane, and no tail is open between runs.
  const blip = mergeTranscript(null, docFor(rows, { error: "transcript lookup failed: ECONNREFUSED" }));
  assert.equal(isLive(blip), true);
  assert.equal(liveNote(blip), "● live · tailing");
  const html = renderPane(blip);
  assert.ok(!html.includes("run ended"), html);
  assert.ok(!html.includes("live-run state unknown"), html);
  assert.ok(html.includes("transcript lookup failed: ECONNREFUSED"), html);
  const ended = mergeTranscript(null, docFor(rows, { live: false }));
  assert.equal(isLive(ended), false);
  assert.equal(isLive(mergeTranscript(null, docFor(rows))), true);
  const ticket = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "ticket.jsx"), "utf8");
  assert.doesNotMatch(ticket, /TRANSCRIPT_RETRY_MS|nextPollDelay|TRANSCRIPT_POLL_MS|shouldSchedulePoll|polling every/);
});

test("a VictoriaLogs failure renders in the pane and leaves the detail view intact", () => {
  const failed = mergeTranscript(null, {
    ticket: 1283, live: true, host: null, session: null, events: [],
    error: "transcript lookup failed: VictoriaLogs HTTP 500",
  });
  const html = render({ number: 1283, doc, error: null, now: doc.now * 1000, transcript: failed, liveOpen: true });
  // the error is inside the pane, in the widget's own `.err` block
  assert.ok(html.includes('<div class="err">transcript lookup failed: VictoriaLogs HTTP 500</div>'), html);
  // …and every other section of the detail view still drew its data
  assert.ok(html.includes("<h2>Live transcript "), "Live transcript still rendered");
  for (const heading of ["Timeline"]) {
    assert.ok(html.includes(`<h2>${heading}</h2>`), `${heading} still rendered`);
  }
  assert.ok(html.includes("Comments"), "the comments section still rendered");
  assert.ok(html.includes("tdThread"), "the issue thread survived");
  // and the pane says it has nothing rather than pretending it is loading
  assert.ok(html.includes("no events shipped for this run yet"));
});

test("an unpolled pane says it is loading, not that the run ended", () => {
  const html = renderPane({ ...EMPTY_TRANSCRIPT, live: true });
  assert.ok(html.includes("loading transcript…"), html);
  assert.ok(!html.includes("run ended"), html);
});

test("each transcript line is its own keyed node, so a read or the tail appends and leaves the lines on screen (and find's highlights on them) alone (#703)", () => {
  const src = require("node:fs").readFileSync(require("node:path").join(__dirname, "..", "src", "renderer", "ticket.jsx"), "utf8");
  assert.match(src, /t\.lines\.map\(\(l, i\) => <span key=\{\(t\.dropped \|\| 0\) \+ i\} className=\{l\.startsWith\("→ "\) \? "tdToolLine" : undefined\}>\{`\$\{l\}\\n\\n`\}<\/span>\)/);
  const html = renderPane(mergeTranscript(null, docFor(rows)));
  assert.match(html, /<pre class="tdTranscript"><span>[^<]*\n\n<\/span><span( class="tdToolLine")?>/);
  // `dropped` counts what the tail let go of, so a line keeps its key as the tail moves
  const many = (from, n) => Array.from({ length: n }, (_, i) => ({ key: `k${from + i}`, time: null, lines: [`line ${from + i}`] }));
  const a = mergeTranscript(null, { session: LOG, live: true, events: many(0, TRANSCRIPT_MAX_LINES) });
  assert.equal(a.dropped, 0);
  const b = mergeTranscript(a, { session: LOG, live: true, events: many(TRANSCRIPT_MAX_LINES, 30) });
  assert.equal(b.dropped, 30);
  assert.equal(b.lines[0], "line 30", "line 30 is still key 30");
  // a new run starts counting again
  assert.equal(mergeTranscript(b, { session: `${LOG}.2`, live: true, events: many(0, 3) }).dropped, 0);
});

test("tool lines carry tdToolLine so they draw dimmer than prose (#1023)", () => {
  const html = renderPane({ lines: ["→ Read  a", "prose", "→ Bash  b"], dropped: 0, polled: true });
  const pre = html.match(/<pre class="tdTranscript">(.*?)<\/pre>/s)[1];
  const spans = [...pre.matchAll(/<span([^>]*)>([^<]*)<\/span>/g)];
  assert.equal(spans.length, 3);
  assert.match(spans[0][1], /class="tdToolLine"/);
  assert.doesNotMatch(spans[1][1], /tdToolLine/);
  assert.match(spans[2][1], /class="tdToolLine"/);
  assert.equal(spans[1][2], "prose\n\n");
});
