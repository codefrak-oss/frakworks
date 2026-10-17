// The transcript loop follows the step that is running (#947): a flow runs one
// Smith step after another (`work` then `review`), and between the two there
// is no `smith_run_active` gauge for a while. The loop used to stop on that
// first gaugeless poll, so the pane kept the work run's lines and its ended
// byline until ⟳. Now the engine's `session` frames say when each run is live,
// the loop tails it (#1006), and the review run's session replaces the old lines.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { mergeTranscript, isLive, liveNote } = require("../dist/ticket.cjs");

const WORK = "/logs/issue-947-work-opus-20261005-100000.log";
const REVIEW = "/logs/issue-947-review-opus-20261005-103000.log";
const ev = (key, line) => ({ key, time: null, lines: [line] });

// One read as the loop in index.jsx folds it: the merge, with the flow's
// running state carried onto it.
const fold = (acc, doc, flowRunning) => ({ ...mergeTranscript(acc, doc), flowRunning });

test("a run that ends while the flow runs stops the loop until the next step's `session` `started`, whose run replaces the lines", () => {
  let acc = fold(null, { session: WORK, live: true, liveKnown: true, cursor: "t1",
                         events: [ev("w1", "→ Edit  ticket.jsx"), ev("w2", "── result: done")] }, true);
  assert.equal(isLive(acc), true);
  assert.equal(liveNote(acc), "● live · tailing");

  // the work run ended, the review run has not started: no gauge, no events
  acc = fold(acc, { session: null, live: false, liveKnown: true, cursor: null, events: [] }, true);
  assert.equal(isLive(acc), false, "between two steps no tail is open: `session` `started` opens one");
  assert.equal(liveNote(acc), "run ended · waiting for the next step");
  assert.deepEqual(acc.lines, ["→ Edit  ticket.jsx", "── result: done"], "the ended run's lines stay until the next one");

  // the review run began: a new session
  acc = fold(acc, { session: REVIEW, live: true, liveKnown: true, cursor: "t3",
                    events: [ev("r1", "Reading the diff.")] }, true);
  assert.deepEqual(acc.lines, ["Reading the diff."], "only the new session's lines");
  assert.equal(acc.session, REVIEW);
  assert.equal(liveNote(acc), "● live · tailing");
});

test("the flow ending is the bound: an ended run with no running flow stops the loop", () => {
  const ended = fold(null, { session: WORK, live: false, liveKnown: true, events: [] }, false);
  assert.equal(isLive(ended), false);
  assert.equal(liveNote(ended), "run ended · tail closed");
});

test("scraping off says the tail is closed while a run is live", () => {
  const live = fold(null, { session: WORK, live: true, liveKnown: true, events: [] }, true);
  assert.equal(liveNote(live, true), "scraping off · tail closed");
  assert.equal(liveNote({ ...live, lapsed: "transcript tail lapsed: ECONNRESET" }), "● live · tail dropped, reconnecting");
});

test("the loop carries the flow's running state onto each read and is woken when the flow is seen running", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "index.jsx"), "utf8");
  // The loop follows `session`: started sets the run (filename, host), ended marks it ended (#976).
  assert.match(src, /useEngineEvents\(\["session"\], \(\{ data \}\) => \{\s*if \(data\.state === "started" \|\| data\.state === "ended"\) setRun\(\(r\) => nextRun\(r, data\)\);/);
  assert.match(src, /const loop = transcriptLoop\(\{\s*number, run: runNow\.current, readDoc:[^\n]*\n\s*tail: fleetTail\(window\.fleet\)/);
  // One loop per run followed; its `ended` is that loop's `end()`, so the tail drains.
  assert.match(src, /\}, \[number, reloads, runFollowed\]\);/);
  assert.match(src, /if \(runEnded && loop\) loop\.end\(\);/);
  assert.doesNotMatch(src, /TRANSCRIPT_RETRY_MS|nextPollDelay/);
  assert.match(src, /const flowRunning = Boolean\(instance && flowResult && flowResult\.flow && !flowResult\.flow\.ended\)/);
  assert.match(src, /if \(loop\) loop\.flow\(flowRunning\);\n\s*\}, \[flowRunning\]\)/);
  assert.doesNotMatch(src, /manual refresh is what\s+\/\/\s*restarts it/);
});

// transcriptLoop driven as index.jsx's effect drives it: one loop per (ticket,
// run followed), the next created when `onRun` or a `session` `started`
// changes the run; `ended` is the same loop's `end()`. `readDoc` records
// whether a read asked the gauge (no run passed) or read a known session; the
// tail is a fake that records its opens and hands back their frame callbacks.
const ticket = require("../dist/ticket.cjs");
const fakeTail = () => {
  const handles = [];
  return {
    handles,
    open: (number, run, since, onFrame) => {
      let endRelease;
      const h = { number, run, since, onFrame, closed: false, ended: false,
        end: () => { h.ended = true; return new Promise((res) => { endRelease = res; }); },
        finishEnd: () => endRelease(),
        close: () => { h.closed = true; } };
      handles.push(h);
      return h;
    },
    get open_() { return handles.filter((h) => !h.closed && !h.ended); },
  };
};
const drive = ({ run, docs, paused = () => false }) => {
  const reads = [];
  const acc = { current: null };
  const updates = [];
  const tail = fakeTail();
  let onRun = null;
  const loop = ticket.transcriptLoop({
    number: 947, run, tail, paused,
    readDoc: async (n, cursor, r) => { reads.push(r ? "session" : "gauge"); const d = docs.shift(); if (!d) throw new Error("no doc"); return d; },
    acc, update: (a) => updates.push(a), onRun: (r) => { onRun = r; },
  });
  return { loop, reads, acc, updates, tail, get onRun() { return onRun; } };
};
const settle = () => new Promise((r) => setTimeout(r, 5));

test("a `session` `started` reads the lines so far once, then tails from that read's cursor; `ended` drains the tail, then the byline says ended, with no gauge read and no reopen (#1006)", async () => {
  const live = drive({ run: { filename: WORK, host: "h" }, docs: [
    { session: WORK, live: true, liveKnown: true, host: "h", cursor: "t1", events: [ev("w1", "→ Edit  ticket.jsx")] }] });
  await settle();
  assert.deepEqual(live.reads, ["session"], "one read of the known session");
  assert.equal(live.tail.handles.length, 1, "then the tail");
  const h = live.tail.handles[0];
  assert.deepEqual(h.run, { filename: WORK, host: "h" });
  assert.equal(h.since, "t1", "from the read's cursor");
  assert.equal(live.loop.tailing, true);
  assert.equal(liveNote(live.acc.current), "● live · tailing");
  // The tail's look-back repeats w1: it appears once; w2 is appended.
  h.onFrame({ events: [ev("w1", "→ Edit  ticket.jsx"), ev("w2", "→ Read  index.jsx")], cursor: "t2" });
  assert.deepEqual(live.acc.current.lines, ["→ Edit  ticket.jsx", "→ Read  index.jsx"]);
  assert.equal(live.acc.current.cursor, "t2");
  // A drop: the byline says so; main's reopen says tailing again.
  h.onFrame({ state: "lapsed", error: "transcript tail lapsed: ECONNRESET" });
  assert.equal(liveNote(live.acc.current), "● live · tail dropped, reconnecting");
  h.onFrame({ state: "tailing" });
  assert.equal(liveNote(live.acc.current), "● live · tailing");
  // `ended`: the tail is asked to drain; the run is live until it has.
  const done = live.loop.end();
  await settle();
  assert.equal(h.ended, true, "the handle's end, not a close");
  assert.equal(live.acc.current.live, true, "live while the last lines drain");
  h.onFrame({ events: [ev("w3", "── result: done")], cursor: "t3" });
  h.finishEnd();
  await done;
  assert.equal(live.acc.current.live, false, "the byline says the run ended once the tail is dropped");
  assert.deepEqual(live.acc.current.lines, ["→ Edit  ticket.jsx", "→ Read  index.jsx", "── result: done"]);
  assert.equal(liveNote(live.acc.current), "run ended · tail closed");
  assert.deepEqual(live.reads, ["session"], "no gauge read, no second query read");
  assert.equal(live.onRun, null, "nothing restarts the loop");
  // Scraping back on, or a catch-up, opens nothing for an ended run.
  live.loop.resume();
  await live.loop.snapshot();
  await settle();
  assert.equal(live.tail.handles.length, 1);
});

test("the pane opened with a run already live: the gauge read's lines and the tail's merge with no line twice (#1006)", async () => {
  // The mount: no run known, the gauge finds one; the effect's next loop follows it.
  const mount = drive({ run: null, docs: [
    { session: WORK, live: true, liveKnown: true, host: "h", cursor: "t5", events: [ev("a", "one"), ev("b", "two")] }] });
  await settle();
  assert.deepEqual(mount.onRun, { filename: WORK, host: "h" });
  assert.equal(mount.tail.handles.length, 0, "this loop opens nothing: the next one, with the run, does");
  const acc = mount.acc;
  const next = ticket.transcriptLoop({
    number: 947, run: mount.onRun, tail: mount.tail, acc, update: () => {}, onRun: () => {},
    readDoc: async () => ({ session: WORK, live: true, liveKnown: true, host: "h", cursor: "t5", events: [ev("b", "two")] }),
  });
  await settle();
  const h = mount.tail.handles[0];
  assert.equal(h.since, "t5");
  h.onFrame({ events: [ev("a", "one"), ev("b", "two"), ev("c", "three")], cursor: "t6" });
  assert.deepEqual(acc.current.lines, ["one", "two", "three"], "a line both returned appears once");
  // A new run's lines still clear the pane.
  const other = mergeTranscript(acc.current, { session: REVIEW, live: true, events: [ev("r1", "Reading the diff.")] });
  assert.deepEqual(other.lines, ["Reading the diff."]);
  next.stop();
  assert.equal(h.closed, true, "stop closes the tail");
});

test("scraping off closes the tail; back on reads the gap from the cursor once, then tails again (#867, #1006)", async () => {
  let off = true;
  const d = drive({ run: { filename: WORK, host: "h" }, paused: () => off, docs: [
    { session: WORK, live: true, liveKnown: true, host: "h", cursor: "t1", events: [ev("w1", "one")] },
    { session: WORK, live: true, liveKnown: true, host: "h", cursor: "t4", events: [ev("w1", "one"), ev("w2", "two")] }] });
  await settle();
  assert.deepEqual(d.reads, ["session"], "the first read still shows the lines so far");
  assert.equal(d.tail.handles.length, 0, "off: no tail");
  assert.equal(liveNote(d.acc.current, true), "scraping off · tail closed");
  off = false;
  d.loop.resume();
  await settle();
  assert.deepEqual(d.reads, ["session", "session"], "one gap read from the cursor");
  assert.equal(d.tail.handles.length, 1);
  assert.equal(d.tail.handles[0].since, "t4", "then the tail from where that read ended");
  assert.deepEqual(d.acc.current.lines, ["one", "two"]);
  off = true;
  d.loop.pause();
  assert.equal(d.tail.handles[0].closed, true, "off closes it");
  assert.equal(d.loop.tailing, false);
  // `ended` with no tail open: one read of the known run for its last lines.
  d.loop.end();
  await settle();
  assert.deepEqual(d.reads, ["session", "session", "session"]);
});

test("the mount, ⟳ with no live run and the catch-up ask the gauge once, a run known or not; a run it finds becomes the loop's run, the same one keeps its tail, none closes it", async () => {
  // Unknown run (the mount): the gauge answers "no run"; nothing is opened.
  const idle = drive({ run: null, docs: [{ session: null, live: false, liveKnown: true, events: [] }] });
  await settle();
  assert.deepEqual(idle.reads, ["gauge"]);
  assert.equal(idle.tail.handles.length, 0);
  // The catch-up on a connect: the same one gauge read, which now finds a run started while the stream was down.
  const found = drive({ run: null, docs: [
    { session: null, live: false, liveKnown: true, events: [] },
    { session: REVIEW, live: true, liveKnown: true, host: "h2", cursor: "t3", events: [ev("r1", "Reading the diff.")] }] });
  await settle();
  await found.loop.snapshot();
  assert.deepEqual(found.reads, ["gauge", "gauge"]);
  assert.deepEqual(found.onRun, { filename: REVIEW, host: "h2" }, "the found run is handed up; the effect's next loop follows it");
  assert.equal(found.tail.handles.length, 0, "this loop opens nothing: the next one, with the run, does");
  // The catch-up with a run known: the gauge is asked all the same. The same session live: the tail stays.
  const same = drive({ run: { filename: WORK, host: "h" }, docs: [
    { session: WORK, live: true, liveKnown: true, host: "h", cursor: "t1", events: [ev("w1", "→ Edit  ticket.jsx")] },
    { session: WORK, live: true, liveKnown: true, host: "h", cursor: "t2", events: [ev("w2", "→ Read  index.jsx")] }] });
  await settle();
  await same.loop.snapshot();
  assert.deepEqual(same.reads, ["session", "gauge"], "the snapshot asks the gauge once, run known or not");
  assert.equal(same.onRun, null, "the same session does not restart the loop");
  assert.equal(same.tail.handles.length, 1, "nor reopen its tail");
  assert.equal(same.tail.handles[0].closed, false);
  assert.deepEqual(same.acc.current.lines, ["→ Edit  ticket.jsx", "→ Read  index.jsx"]);
  same.loop.stop();
  // The same, and the gauge finds no run: the followed run ended while the stream was down.
  const gone = drive({ run: { filename: WORK, host: "h" }, docs: [
    { session: WORK, live: true, liveKnown: true, host: "h", cursor: "t1", events: [ev("w1", "→ Edit  ticket.jsx")] },
    { session: null, live: false, liveKnown: true, cursor: null, events: [] }] });
  await settle();
  await gone.loop.snapshot();
  assert.equal(gone.tail.handles[0].closed, true, "no run live: the tail is closed");
  assert.equal(gone.acc.current.live, false, "and the byline says the run ended");
  assert.deepEqual(gone.onRun, { filename: WORK, host: "h", ended: true },
    "the run followed is handed up as ended, so the pane's `run` agrees and ⟳ asks the gauge again");
  assert.equal(liveNote(gone.acc.current), "run ended · tail closed");
  gone.loop.resume();
  await settle();
  assert.deepEqual(gone.reads, ["session", "gauge"], "resume follows no run");
  // A loop on a run handed up as ended: one read of the known run, no gauge, no tail.
  const after = drive({ run: gone.onRun, docs: [{ session: WORK, live: false, liveKnown: true, host: "h", cursor: "t1", events: [] }] });
  await settle();
  assert.deepEqual(after.reads, ["session"]);
  assert.equal(after.tail.handles.length, 0);
  // The same, and the gauge finds another run (the next step started while the stream was down): handed up.
  const other = drive({ run: { filename: WORK, host: "h" }, docs: [
    { session: WORK, live: true, liveKnown: true, host: "h", cursor: "t1", events: [] },
    { session: REVIEW, live: true, liveKnown: true, host: "h2", cursor: "t3", events: [ev("r1", "Reading the diff.")] }] });
  await settle();
  await other.loop.snapshot();
  assert.deepEqual(other.onRun, { filename: REVIEW, host: "h2" });
  assert.equal(other.tail.handles[0].closed, true, "the old run's tail is closed");
  // A snapshot asked for while a read is out is not skipped: it is the gauge read run when that read settles.
  let release;
  const heldReads = [];
  const busy = ticket.transcriptLoop({
    number: 947, run: { filename: WORK, host: "h" }, tail: fakeTail(),
    readDoc: (n, cursor, r) => { heldReads.push(r ? "session" : "gauge"); return heldReads.length === 1
      ? new Promise((res) => { release = () => res({ session: WORK, live: true, liveKnown: true, host: "h", cursor: "t1", events: [] }); })
      : Promise.resolve({ session: null, live: false, liveKnown: true, cursor: null, events: [] }); },
    acc: { current: null }, update: () => {}, onRun: () => {},
  });
  await settle();
  let snapped = false;
  const snap = busy.snapshot().then(() => { snapped = true; });
  await settle();
  assert.equal(snapped, false, "waits for the read that is out");
  release();
  await snap;
  assert.deepEqual(heldReads, ["session", "gauge"], "then the gauge read, once");
  // A read failure while a run is known keeps the run live (said in the pane), and the tail still opens.
  const blip = drive({ run: { filename: WORK, host: "h" }, docs: [] });
  await settle();
  assert.equal(blip.acc.current.live, true);
  assert.match(blip.acc.current.error, /IPC failed: no doc/);
  assert.equal(blip.tail.handles.length, 1);
  blip.loop.stop();
});

// #1775: the next step's run replaces the old one whatever the frame order.
const OLD = { filename: WORK, host: "h" };
const NEW = { filename: REVIEW, host: "h" };
const frame = (state, run) => ({ state, filename: run.filename, host: run.host });

test("a late `ended` for the old run does not end the new run (#1775)", () => {
  let r = ticket.nextRun(OLD, frame("started", NEW));
  r = ticket.nextRun(r, frame("ended", OLD));
  assert.deepEqual(r, NEW, "the new run stays live");
  assert.deepEqual(ticket.nextRun(r, frame("ended", NEW)), { ...NEW, ended: true });
});

test("a `started` with no filename is an unknown run, not the old one (#1775)", () => {
  assert.equal(ticket.nextRun({ ...OLD, ended: true }, { state: "started", filename: null }), null);
});

for (const order of ["started-then-ended", "ended-then-started"]) {
  test(`next step's run replaces the old run's lines, ${order}, even during the old run's drain (#1775)`, async () => {
    const tail = fakeTail();
    const acc = { current: null };
    let run = OLD;
    const old = ticket.transcriptLoop({ number: 947, run, tail, acc, update: () => {}, onRun: () => {},
      readDoc: async () => ({ session: WORK, live: true, liveKnown: true, host: "h", cursor: "t9", events: [ev("w1", "old line")] }) });
    await settle();
    const oh = tail.handles[0];
    let draining = null;
    if (order === "ended-then-started") { run = ticket.nextRun(run, frame("ended", OLD)); draining = old.end(); await settle(); }
    run = ticket.nextRun(run, frame("started", NEW));
    if (order === "started-then-ended") run = ticket.nextRun(run, frame("ended", OLD));
    assert.deepEqual(run, NEW);
    old.stop(); // the effect's cleanup: runFollowed changed
    const cursors = [];
    ticket.transcriptLoop({ number: 947, run, tail, acc, update: () => {}, onRun: () => {},
      readDoc: async (n, cursor) => { cursors.push(cursor); return { session: REVIEW, live: true, liveKnown: true, host: "h", cursor: "r1", events: [ev("r1", "new line")] }; } });
    // the old tail's drain lands late: it must not write
    oh.onFrame({ events: [ev("w2", "old drain")], cursor: "t10" });
    if (draining) { oh.finishEnd(); await draining; }
    await settle();
    assert.deepEqual(cursors, [null], "the new run is read from the start of its window");
    assert.deepEqual(acc.current.lines, ["new line"]);
    assert.equal(acc.current.live, true);
    assert.equal(liveNote(acc.current), "● live · tailing");
    const nh = tail.handles[tail.handles.length - 1];
    assert.equal(nh.run.filename, REVIEW, "a tail is open on the new run");
    assert.equal(nh.closed, false);
  });
}
