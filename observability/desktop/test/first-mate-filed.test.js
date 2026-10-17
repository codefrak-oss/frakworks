// First mate's "Filed as #<n>" row (issue #927; on the engine's event stream
// since #971): the main process takes `intake` frames from a stand-in stream
// (an EventEmitter in the shape of src/engine-events.js's: "frame" and
// "hello"), reads each unannounced filing once on every `hello` through a
// stand-in `fetchFlow` (no engine, no Claude login, no child: the filings are
// made through `fileTicket` with a card set by hand), and the renderer links
// the row's reference.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { EventEmitter } = require("node:events");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const { FirstMateManager, FILED_WATCH_MS, filedRowText, ITERM_ROW } = require("../src/first-mate");
const { loadFirstMate, saveFirstMate, freshSession } = require("../src/first-mate-store");
const { Row } = require("../dist/first-mate.cjs");

const tmpdir = () => fs.mkdtempSync(path.join(os.tmpdir(), "first-mate-filed-"));
const URL_HERE = "https://github.com/codefrak-oss/frakworks/issues/";
const REPO = "codefrak-oss/frakworks";

// A flow reader the test steers: `answer(id)` is what the next read of `id`
// gives; every read is counted.
const stand = () => {
  const s = { reads: [], answers: new Map() };
  s.fn = async (id) => {
    s.reads.push(id);
    const a = s.answers.get(id);
    if (typeof a === "function") return a();
    return a || { flow: { intakeId: id, ended: false, steps: [], variables: {} }, error: null };
  };
  s.ended = (id, variables) => s.answers.set(id, { flow: { intakeId: id, ended: true, steps: [], variables }, error: null });
  return s;
};

// The stream, in main's shape: `hello` on each connect, `frame` per event.
const fakeStream = () => {
  const e = new EventEmitter();
  e.hello = (repo = "*") => e.emit("hello", { repo, data: { repo, at: "2026-10-05T10:00:00Z" } });
  e.intake = (data) => e.emit("frame", { type: "intake", data: { repo: REPO, at: "2026-10-05T10:00:01Z", ...data }, subscription: "*" });
  e.filed = (intakeId, ticket, ticketUrl = `${URL_HERE}${ticket}`, state = "filed") =>
    e.intake({ intakeId, state, ticket, ticketUrl, kind: "chore", tier: 5 });
  return e;
};

const make = (over = {}) => {
  const dir = over.dir || tmpdir();
  const clock = over.clock || { t: 1_000_000 };
  const flows = over.flows || stand();
  const stream = over.stream || fakeStream();
  let n = 0;
  const m = new FirstMateManager({
    dir,
    submitIntakeFn: async () => ({ ok: true, intakeId: `intake-${++n}` }),
    resolveTierFn: () => ({ model: "sonnet", effort: "medium" }),
    fetchFlowFn: flows.fn,
    engineEvents: stream,
    now: () => clock.t,
    iterm: { available: false, why: "test" },
    ...over.opts,
  }).init();
  return { m, dir, clock, flows, stream };
};

// One filing from the live conversation, as the File button makes it.
const file = async (m, ask = "wire the retry state") => {
  m.session.history.push({ role: "user", text: `please file: ${ask}`, at: 1 });
  m.rt.fileCard = { tier: 5, ask, turn_id: "t" };
  const r = await m.fileTicket(5);
  assert.equal(r.ok, true);
  return r.intake_id;
};

const systemRows = (messages) => messages.filter((h) => h.role === "system").map((h) => h.text);
const settle = () => new Promise((r) => setImmediate(r));

// ---------------------------------------------------------------------------
// The frame path: `filed` announces at once.
// ---------------------------------------------------------------------------

test("a `filed` frame announces the ticket at once, in the live conversation, with no read of the flow", async () => {
  const { m, clock, flows, stream } = make();
  const id = await file(m);
  let updates = 0;
  m.on("update", () => { updates += 1; });
  assert.deepEqual(systemRows(m.session.history), [], "nothing while the intake runs");

  stream.filed(id, 931);
  assert.deepEqual(systemRows(m.session.history), ["Filed as #931"]);
  assert.equal(updates, 1);
  const f = m.session.filed[0];
  assert.equal(f.ticket, 931);
  assert.equal(f.ticket_url, `${URL_HERE}931`);
  assert.equal(f.announced_at, clock.t);
  assert.equal(typeof f.ended_at, "number");
  assert.deepEqual(flows.reads, [], "the frame carried everything");
  assert.equal(m.snapshot().error, null);
  m.stop();
});

test("a `triage` frame (the unlabeled filing) announces the same way", async () => {
  const { m, stream } = make();
  const id = await file(m);
  stream.filed(id, 12, `${URL_HERE}12`, "triage");
  assert.deepEqual(systemRows(m.session.history), ["Filed as #12"]);
  assert.equal(m.session.filed[0].ticket, 12);
  m.stop();
});

test("a frame for an already-announced entry pushes nothing; so does one for an unknown intake, another state, or no intakeId", async () => {
  const { m, flows, stream } = make();
  const id = await file(m);
  stream.filed(id, 5);
  let updates = 0;
  m.on("update", () => { updates += 1; });
  stream.filed(id, 5);
  stream.intake({ intakeId: id, state: "ended" });
  stream.intake({ intakeId: "someone-elses", state: "filed", ticket: 77, ticketUrl: `${URL_HERE}77` });
  stream.intake({ intakeId: id, state: "issue", number: 900, issueUrl: `${URL_HERE}900` });
  stream.intake({ state: "filed", ticket: 78, ticketUrl: `${URL_HERE}78` });
  stream.emit("frame", { type: "flow", data: { repo: REPO, number: 5, state: "ended" }, subscription: "*" });
  assert.deepEqual(systemRows(m.session.history), ["Filed as #5"]);
  assert.equal(updates, 0);
  assert.deepEqual(flows.reads, []);
  m.stop();
});

test("an `issue` or `question` frame for the entry changes nothing; `cancelled` ends it without a row and it is not read again", async () => {
  const { m, flows, stream } = make();
  const id = await file(m);
  stream.intake({ intakeId: id, state: "issue", number: 900, issueUrl: `${URL_HERE}900` });
  stream.intake({ intakeId: id, state: "question", question: "which repo?" });
  assert.equal(m.session.filed[0].ended_at, null);
  stream.intake({ intakeId: id, state: "cancelled" });
  assert.deepEqual(systemRows(m.session.history), []);
  const f = m.session.filed[0];
  assert.equal(f.ticket, null);
  assert.equal(f.announced_at, null);
  assert.equal(typeof f.ended_at, "number");
  stream.hello();
  await settle();
  assert.deepEqual(flows.reads, [], "an ended entry is not read on hello");
  m.stop();
});

test("the row lands in the archived conversation that filed it, not the live one", async () => {
  const { m, stream } = make();
  const id = await file(m);
  m.startNewConversation();
  m.session.history.push({ role: "user", text: "something else", at: 2 });
  stream.filed(id, 40);
  assert.deepEqual(systemRows(m.session.history), [], "the live conversation is left alone");
  assert.deepEqual(systemRows(m.session.conversations[0].messages), ["Filed as #40"]);
  assert.equal(m.session.filed[0].ticket, 40);
  const saved = loadFirstMate(m.dir).session;
  assert.deepEqual(systemRows(saved.conversations[0].messages), ["Filed as #40"]);
  m.stop();
});

test("a resumed conversation keeps its key, so its filing is announced in it once live again", async () => {
  const { m, stream } = make();
  const id = await file(m);
  m.session.claude_session_id = "sess-1";
  m.startNewConversation();
  m.session.history.push({ role: "user", text: "other", at: 2 });
  m.session.claude_session_id = "sess-2";
  assert.deepEqual(m.popConversation(), { ok: true });
  stream.filed(id, 41);
  assert.deepEqual(systemRows(m.session.history), ["Filed as #41"]);
  assert.deepEqual(systemRows(m.session.conversations[0].messages), []);
  m.stop();
});

test("a ticket in another repo is announced as owner/name#n", async () => {
  const { m, stream } = make();
  const id = await file(m);
  stream.filed(id, 7, "https://github.com/acme/widgets/issues/7");
  assert.deepEqual(systemRows(m.session.history), ["Filed as acme/widgets#7"]);
  assert.equal(filedRowText(3, `${URL_HERE}3`), "Filed as #3");
  m.stop();
});

test("an `ended` frame with a ticket (the `filed` frame missed) announces it, with the URL built from the frame's repo; one without a ticket, or `cancelled`, ends the entry silently", async () => {
  const { m, clock, flows, stream } = make();
  const id = await file(m);
  const id2 = await file(m, "another");
  const id3 = await file(m, "a third");
  // closeIntake's frame: `ticket`, no `ticketUrl`.
  stream.intake({ intakeId: id, state: "ended", repo: "acme/widgets", ticket: 44 });
  assert.deepEqual(systemRows(m.session.history), ["Filed as acme/widgets#44"]);
  const f = m.session.filed.find((x) => x.intake_id === id);
  assert.equal(f.ticket, 44);
  assert.equal(f.ticket_url, "https://github.com/acme/widgets/issues/44");
  assert.equal(f.announced_at, clock.t);
  stream.intake({ intakeId: id, state: "ended", repo: "acme/widgets", ticket: 44 });
  assert.deepEqual(systemRows(m.session.history), ["Filed as acme/widgets#44"], "a second ended frame pushes nothing");

  stream.intake({ intakeId: id2, state: "ended" });
  stream.intake({ intakeId: id3, state: "cancelled", ticket: 45 });
  assert.deepEqual(systemRows(m.session.history), ["Filed as acme/widgets#44"]);
  for (const x of [id2, id3]) {
    const g = m.session.filed.find((e) => e.intake_id === x);
    assert.equal(g.ticket, null);
    assert.equal(g.announced_at, null);
    assert.equal(typeof g.ended_at, "number");
  }
  stream.hello();
  await settle();
  assert.deepEqual(flows.reads, [], "every entry is done: nothing is read on hello");
  m.stop();
});

// ---------------------------------------------------------------------------
// The one-shot read on hello: the snapshot of the stream's catch-up.
// ---------------------------------------------------------------------------

test("an intake that ended while the app was closed is announced on hello, by one read per unannounced entry", async () => {
  const first = make();
  const id = await file(first.m);
  const id2 = await file(first.m, "another");
  first.m.stop();
  // The app is closed; the intakes end meanwhile.
  first.flows.ended(id, { ticket: 931, ticketUrl: `${URL_HERE}931`, outcome: "file" });

  const { m, flows, stream } = make({ dir: first.dir, clock: first.clock, flows: first.flows });
  assert.deepEqual(systemRows(m.session.history), [], "nothing before the stream connects");
  stream.hello();
  await settle();
  assert.deepEqual(flows.reads.sort(), [id, id2].sort(), "one read each");
  assert.deepEqual(systemRows(m.session.history), ["Filed as #931"]);
  assert.equal(m.session.filed.find((f) => f.intake_id === id).ticket, 931);
  assert.equal(m.session.filed.find((f) => f.intake_id === id2).ended_at, null, "still running: read again on the next hello");

  // The next connect reads only what is still unannounced.
  flows.ended(id2, { outcome: "cancelled" });
  stream.hello();
  await settle();
  assert.deepEqual(flows.reads.length, 3);
  assert.equal(flows.reads[2], id2);
  assert.deepEqual(systemRows(m.session.history), ["Filed as #931"]);
  assert.equal(typeof m.session.filed.find((f) => f.intake_id === id2).ended_at, "number");
  stream.hello();
  await settle();
  assert.equal(flows.reads.length, 3, "nothing left to read");
  m.stop();
});

test("a frame and the hello read for the same intake push one row, whichever comes first", async () => {
  const { m, flows, stream } = make();
  const id = await file(m);
  let release;
  flows.answers.set(id, () => new Promise((r) => { release = () => r({ flow: { intakeId: id, ended: true, steps: [], variables: { ticket: 5, ticketUrl: `${URL_HERE}5` } }, error: null }); }));
  stream.hello();
  await settle();
  assert.deepEqual(flows.reads, [id]);
  // The frame lands while the read is in flight.
  stream.filed(id, 5);
  assert.deepEqual(systemRows(m.session.history), ["Filed as #5"]);
  release();
  await settle();
  assert.deepEqual(systemRows(m.session.history), ["Filed as #5"]);
  // Two hellos while a read is in flight read once.
  const id2 = await file(m, "two");
  let n = 0;
  flows.answers.set(id2, () => { n += 1; return new Promise(() => {}); });
  stream.hello();
  stream.hello();
  await settle();
  assert.equal(n, 1);
  m.stop();
});

test("a failed read on hello leaves no row and no error in the chat; the next hello reads again", async () => {
  const { m, flows, stream } = make();
  const id = await file(m);
  flows.answers.set(id, () => ({ error: `the engine has no flow ${id}` }));
  stream.hello();
  await settle();
  flows.answers.set(id, () => { throw new Error("ECONNREFUSED"); });
  stream.hello();
  await settle();
  assert.deepEqual(systemRows(m.session.history), []);
  assert.equal(m.snapshot().error, null);
  assert.equal(m.snapshot().recovery, null);
  flows.ended(id, { ticket: 9, ticketUrl: `${URL_HERE}9` });
  stream.hello();
  await settle();
  assert.equal(flows.reads.length, 3);
  assert.deepEqual(systemRows(m.session.history), ["Filed as #9"]);
  m.stop();
});

test("an entry a day old is not read on hello, and a load of the saved file after a restart never pushes the row again", async () => {
  const { m, dir, clock, flows, stream } = make();
  const id = await file(m);
  clock.t = m.session.filed[0].at + FILED_WATCH_MS;
  stream.hello();
  await settle();
  assert.deepEqual(flows.reads, [], "a day-old entry is not read");
  m.stop();

  const again = make({ dir, clock, flows });
  const id2 = await file(again.m, "fresh");
  again.stream.filed(id2, 12);
  again.m._filedEnded(again.m.session.filed[0], { ticket: 12, ticketUrl: `${URL_HERE}12` });
  assert.deepEqual(systemRows(again.m.session.history), ["Filed as #12"]);
  again.m.stop();
  const saved = loadFirstMate(dir).session;
  assert.equal(saved.filed[0].ticket, 12);
  assert.equal(typeof saved.filed[0].announced_at, "number");

  const third = make({ dir, clock, flows });
  third.stream.hello();
  await settle();
  assert.deepEqual(flows.reads, [], "neither the announced nor the day-old entry is read");
  assert.deepEqual(systemRows(third.m.session.history), ["Filed as #12"]);
  assert.equal(id, third.m.session.filed[1].intake_id);
  third.m.stop();
});

test("no timer is armed between connects: nothing runs without a frame or a hello, and stop() unsubscribes", async () => {
  const { m, flows, stream } = make();
  const id = await file(m);
  flows.ended(id, { ticket: 3, ticketUrl: `${URL_HERE}3` });
  assert.equal(m.watch, undefined, "no watch runtime");
  assert.equal(m.filedPollMs, undefined);
  assert.equal(typeof m._scheduleFiledWatch, "undefined");
  assert.equal(typeof m.pollFiled, "undefined");
  // Real time passes; nothing reads the flow of its own accord.
  await new Promise((r) => setTimeout(r, 30));
  assert.deepEqual(flows.reads, []);
  assert.deepEqual(systemRows(m.session.history), []);
  assert.equal(stream.listenerCount("frame"), 1);
  assert.equal(stream.listenerCount("hello"), 1);
  m.stop();
  assert.equal(stream.listenerCount("frame"), 0);
  assert.equal(stream.listenerCount("hello"), 0);
  stream.filed(id, 3);
  stream.hello();
  await settle();
  assert.deepEqual(flows.reads, [], "a stopped manager hears nothing");
  assert.deepEqual(systemRows(m.session.history), []);
});

test("the source keeps no poll: FILED_POLL_MS, _scheduleFiledWatch and a re-armed setTimeout are gone; FILED_WATCH_MS stays", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "first-mate.js"), "utf8");
  assert.doesNotMatch(src, /FILED_POLL_MS|_scheduleFiledWatch|pollFiled|watch\.timer|setInterval/);
  assert.match(src, /const FILED_WATCH_MS = 24 \* 60 \* 60 \* 1000;/);
  assert.equal(FILED_WATCH_MS, 24 * 60 * 60 * 1000);
  // The one setTimeout left is the turn's watchdog (claude-session's timeout).
  assert.equal([...src.matchAll(/setTimeout\(/g)].length, 1);
});

test("without a stream (tests, a run with none) nothing is announced and nothing throws", async () => {
  const dir = tmpdir();
  const flows = stand();
  const m = new FirstMateManager({
    dir, submitIntakeFn: async () => ({ ok: true, intakeId: "i-1" }), resolveTierFn: () => ({ model: "sonnet", effort: "medium" }),
    fetchFlowFn: flows.fn, iterm: { available: false, why: "test" },
  }).init();
  await file(m);
  assert.equal(m.engineEvents, null);
  m.stop();
  assert.deepEqual(flows.reads, []);
});

test("a first-mate.json from before #927 loads its filed entries with null fields, and they are read on hello", async () => {
  const dir = tmpdir();
  const old = { version: 2, session: { ...freshSession(), history: [{ role: "user", text: "hi", at: 1 }],
    filed: [{ intake_id: "old-1", tier: 5, ask: "a", at: 1_000_000 }] } };
  delete old.session.conversation_key;
  fs.writeFileSync(path.join(dir, "first-mate.json"), JSON.stringify(old));
  const loaded = loadFirstMate(dir).session.filed[0];
  assert.deepEqual(loaded, { intake_id: "old-1", tier: 5, ask: "a", at: 1_000_000, conversation_key: null,
    ticket: null, ticket_url: null, announced_at: null, ended_at: null });
  const { m, flows, stream } = make({ dir });
  flows.ended("old-1", { ticket: 2, ticketUrl: `${URL_HERE}2` });
  stream.hello();
  await settle();
  assert.deepEqual(flows.reads, ["old-1"]);
  assert.deepEqual(systemRows(m.session.history), ["Filed as #2"], "with no key, the live conversation gets it");
  m.stop();
});

test("conversation_key survives archive, save and load", () => {
  const dir = tmpdir();
  const s = { ...freshSession(), conversation_key: "k-live",
    conversations: [{ id: 1, started_at: 1, conversation_key: "k-old", messages: [{ role: "user", text: "x", at: 1 }] }] };
  saveFirstMate(dir, s);
  const back = loadFirstMate(dir).session;
  assert.equal(back.conversation_key, "k-live");
  assert.equal(back.conversations[0].conversation_key, "k-old");
});

// ---------------------------------------------------------------------------
// The renderer: the row's reference is a pill that opens the ticket.
// ---------------------------------------------------------------------------

test("a system row with a ticket reference links it; other system rows render as before", () => {
  const html = renderToStaticMarkup(React.createElement(Row, { row: { role: "system", text: "Filed as #927", at: 1 } }));
  assert.match(html, /Filed as <a class="fmTicketRef" href="https:\/\/github.com\/codefrak-oss\/frakworks\/issues\/927">#927<\/a>/);
  const other = renderToStaticMarkup(React.createElement(Row, { row: { role: "system", text: "Filed as acme/widgets#7", at: 1 } }));
  assert.match(other, /href="https:\/\/github.com\/acme\/widgets\/issues\/7">acme\/widgets#7<\/a>/);
  const iterm = renderToStaticMarkup(React.createElement(Row, { row: { role: "system", text: ITERM_ROW, at: 1 } }));
  assert.equal(iterm, `<div class="fmRow fmRow-system"><span class="fmMark" role="img" aria-label="system">⎿</span>` +
    `<div class="fmText">${ITERM_ROW}</div></div>`);
});

test("a plain left click on the row's pill opens the ticket in the app", () => {
  const opened = [];
  const el = Row({ row: { role: "system", text: "Filed as #927", at: 1 }, onOpenTicket: (repo, n) => opened.push([repo, n]) });
  const linked = el.props.children[2].props.children;
  const pill = linked.type(linked.props).find((p) => typeof p !== "string");
  let prevented = false;
  pill.props.onClick({ button: 0, preventDefault: () => { prevented = true; } });
  assert.equal(prevented, true);
  assert.deepEqual(opened, [["codefrak-oss/frakworks", 927]]);
});
