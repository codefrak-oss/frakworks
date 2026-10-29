// The Issues view, the first screen fed by the engine: src/engine-data.js
// against a stand-in fetch (every way the engine can fail becomes `error`,
// never a rejection), and issues.jsx against a board recorded from the local
// stack (fixture-issues.json, `curl engine.test/issues`, 2026-09-24).
"use strict";
// Config > frakworks > Engine URL, as main sets it at launch (src/engine-url-setting.js).
const TEST_ENGINE_URL = "http://engine.test";
require("../src/engine-data").setEngineUrl(TEST_ENGINE_URL);
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { fetchIssues, getJson, engineUrl, submitIntake } = require("../src/engine-data");
const { Issues, stateOf, withNeeds, needPhrase, STAGE_PHRASES, fmtAgo, fmtAgeClock, NewIntakeForm, runIntake, parseIssueUrl, issueUrlOf, buildIntakeBody, tierLabel, tieredAsk, intakeAsk, AUTO_TIER_LABEL, childrenSummary, filterIssues, BOARD_FILTERS } = require("../dist/issues.cjs");
const { SEEDED_TIERS } = require("./seeded-tiers");

// The picker's rows as main's engine:tiers handler hands them over: the
// engine's tier map as V42 seeds it.
const tierRows = SEEDED_TIERS;

const board = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-issues.json"), "utf8"));
const render = (props) => renderToStaticMarkup(React.createElement(Issues, props));

// A stand-in for fetch: one canned Response, or a thrown error.
const stubFetch = (status, body, calls = []) => async (url, init) => {
  calls.push({ url, init });
  if (status instanceof Error) throw status;
  return { ok: status >= 200 && status < 300, status, text: async () => body };
};

test("the engine's address is the Engine URL setting; there is no default", () => {
  assert.equal(engineUrl({}), TEST_ENGINE_URL);
  assert.equal(engineUrl({ engineUrl: "http://engine.local:8080/" }), "http://engine.local:8080");
});

test("fetchIssues returns the board the engine answered with", async () => {
  const calls = [];
  const r = await fetchIssues({ env: {}, fetch: stubFetch(200, JSON.stringify(board), calls) });
  assert.equal(r.error, null);
  assert.equal(r.issues.length, board.issues.length);
  assert.deepEqual(r.failures, []);
  assert.equal(calls[0].url, `${TEST_ENGINE_URL}/issues`);
});

test("every way the engine can fail is an error, never a rejection", async () => {
  const cases = [
    [stubFetch(503, "down"), /engine answered 503 for \/issues: down/],
    [stubFetch(200, "<html>"), /produced no JSON/],
    [stubFetch(200, JSON.stringify({ nope: 1 })), /no `issues` list/],
    [stubFetch(new Error("ECONNREFUSED")), /unreachable at http:\/\/engine.test\/issues: ECONNREFUSED/],
  ];
  for (const [fetch, expect] of cases) {
    const r = await fetchIssues({ env: {}, fetch });
    assert.match(r.error, expect);
    assert.deepEqual(r.issues, []);
  }
});

test("a request that hangs times out", async () => {
  const hang = (_url, { signal }) => new Promise((_res, rej) => signal.addEventListener("abort", () => {
    const e = new Error("aborted"); e.name = "AbortError"; rej(e);
  }));
  const r = await getJson("/issues", { env: { ENGINE_TIMEOUT_SECS: "0.01" }, fetch: hang });
  assert.match(r.error, /timed out after 0.01s/);
});

test("stateOf says the one thing that matters most about an issue, in plain words", () => {
  const base = { labels: [], kind: null, authorized: false, flow: null, pendingQuestion: null };
  // the labels alone, and the blank case
  assert.deepEqual(stateOf(base), { key: "none", text: "not triaged" });
  assert.deepEqual(stateOf({ ...base, kind: "chore" }), { key: "kind", text: "not approved yet" });
  assert.deepEqual(stateOf({ ...base, kind: "chore", authorized: true }), { key: "authorized", text: "approved, not started yet" });
  assert.deepEqual(stateOf({ ...base, authorized: true }), { key: "authorized", text: "approved, no kind label" });
  // a running flow names its stage and what comes next, never the Smith run's step
  const run = { flow: "chore", outcome: "started", ended: false, step: "start smith" };
  const at = (stage, flow = run) => stateOf({ ...base, flow: { ...flow, stage } });
  assert.deepEqual(at("call-do-work"), { key: "running", text: "coding" });
  assert.equal(at("call-security-review").text, "coded, in security review");
  assert.equal(at("task-open-pr").text, "coded, PR open, awaiting review");
  assert.equal(at("call-ci-check").text, "waiting on CI build");
  assert.equal(at("call-review-pr").text, "in review");
  assert.equal(at("task-merge-pr").text, "review done, awaiting merge");
  assert.equal(at("call-do-work", { ...run, flow: "bug", step: "smith exited" }).text, "coding");
  assert.equal(at("call-reproduce", { ...run, flow: "bug" }).text, "reproducing the bug");
  assert.equal(at("call-refine-ask", { ...run, flow: "intake", outcome: null, instance: "i-1" }).text, "refining the ask");
  // an unknown stage, or an older engine with none, is generic, never the raw step
  assert.deepEqual(at("call-something-new"), { key: "running", text: "chore in progress" });
  assert.deepEqual(stateOf({ ...base, flow: run }), { key: "running", text: "chore in progress" });
  // a split parent waiting on a child says which
  assert.equal(stateOf({ ...base, deferred: true, flow: { ...run, flow: "feature", stage: "event-child-closed", step: "waiting on #62", detail: "waiting on #62" } }).text, "waiting on #62");
  // finished, and the outcomes that started nothing
  assert.deepEqual(stateOf({ ...base, flow: { ...run, ended: true, step: null } }), { key: "done", text: "done" });
  assert.deepEqual(stateOf({ ...base, flow: { flow: "chore", outcome: "refused", detail: "no access" } }), { key: "refused", text: "refused: no access" });
  assert.deepEqual(stateOf({ ...base, flow: { flow: "chore", outcome: "deferred", detail: "waiting on #31" } }), { key: "deferred", text: "waiting on #31" });
  assert.deepEqual(stateOf({ ...base, flow: { flow: "chore", outcome: "ignored", detail: "x" } }), { key: "ignored", text: "approval ignored, not started" });
  assert.equal(stateOf({ ...base, flow: { flow: "chore", outcome: "something-new" } }).text, "approved, not started");
  // a question outranks everything
  assert.deepEqual(stateOf({ ...base, flow: run, pendingQuestion: { question: "which?" } }), { key: "question", text: "asking you" });
});

test("every activity of the four flows has a plain phrase, with no BPMN name or id in it", () => {
  const bpmn = path.join(__dirname, "..", "..", "..", "engine", "bpmn");
  const names = [];
  for (const flow of ["chore", "bug", "feature", "intake"]) {
    const xml = fs.readFileSync(path.join(bpmn, `${flow}.bpmn`), "utf8");
    const activities = [...xml.matchAll(/<bpmn:(?:startEvent|endEvent|intermediateCatchEvent|boundaryEvent|callActivity|serviceTask|sendTask|userTask|receiveTask|task)\b[^>]*\bid="([^"]+)"(?:[^>]*\bname="([^"]*)")?/g)];
    assert.ok(activities.length > 5, `${flow}.bpmn has activities`);
    for (const [, id, name] of activities) {
      assert.ok(STAGE_PHRASES[id], `${flow}.bpmn's ${id} has a phrase`);
      if (name) names.push(name.split("&#10;")[0].toLowerCase());
    }
  }
  for (const [id, phrase] of Object.entries(STAGE_PHRASES)) {
    assert.doesNotMatch(phrase, /smith exited|start smith|\(smith run\)|[a-z]+-[a-z]+-/, `${id}: ${phrase}`);
    assert.ok(!Object.keys(STAGE_PHRASES).some((k) => phrase.includes(k)), `${id}'s phrase holds no id`);
    assert.ok(!names.includes(phrase), `${id}'s phrase "${phrase}" is not its BPMN name`);
  }
});

test("the recorded board reads in plain words: coding, and a split parent's wait", () => {
  const byNumber = Object.fromEntries(board.issues.map((i) => [i.number, i]));
  assert.deepEqual(stateOf(byNumber[62]), { key: "running", text: "coding" });
  assert.equal(stateOf(byNumber[60]).text, "waiting on #62");
  for (const i of board.issues) assert.ok(stateOf(i).text, `#${i.number} has a state`);
});

test("fmtAgo is in the same register as the other views' clocks", () => {
  const now = Date.parse("2026-09-24T12:00:00Z");
  assert.equal(fmtAgo("2026-09-24T11:48:00Z", now), "12m");
  assert.equal(fmtAgo("2026-09-24T08:53:00Z", now), "3h 07m");
  assert.equal(fmtAgo("2026-09-20T10:00:00Z", now), "4d 2h");
  assert.equal(fmtAgo("garbage", now), "");
});

test("the recorded board draws one row per issue, its labels, and its state", () => {
  const html = render({ result: board, now: Date.parse("2026-09-24T14:00:00Z") });
  assert.match(html, /class="fleet-root"|class="isView"/);
  assert.equal([...html.matchAll(/class="isRow/g)].length, board.issues.length);
  assert.ok(html.includes(`Tickets · ${board.issues.length}`));
  assert.ok(html.includes("1 asking you"), "the header counts the questions waiting");
  assert.match(html, /is-question/);
  for (const i of board.issues) assert.ok(html.includes(`href="${i.url}"`), `#${i.number} links to GitHub`);
  assert.doesNotMatch(html, /<h2>/, "one repo: no group headings");
});

test("the age sits under the number as a clock of time since createdAt, not updatedAt, with createdAt on hover", () => {
  const now = Date.parse("2026-09-25T12:00:00Z");
  const html = render({ result: board, now });
  assert.doesNotMatch(html, /<th[^>]*>age<\/th>/, "no age column");
  assert.doesNotMatch(html, />updated</);
  const issue = board.issues.find((i) => i.number === 60);
  assert.notEqual(issue.createdAt, issue.updatedAt, "fixture must have distinct createdAt/updatedAt to catch the old code");
  const clock = fmtAgeClock((now - Date.parse(issue.createdAt)) / 1000);
  assert.match(clock, /^(\d+d )?\d+:\d\d(:\d\d)?$/);
  assert.match(html, new RegExp(`<td class="isNum"><a [^>]*>#60</a><div class="isAge" title="${issue.createdAt}">${clock}</div></td>`),
    "the age is inside the number cell, under the link, with createdAt as its title");
  assert.doesNotMatch(html, new RegExp(`title="${issue.updatedAt}"`), "updatedAt is no longer rendered");
});

test("fmtAgeClock: m:ss under an hour, h:mm:ss from an hour, a day count from a day", () => {
  assert.equal(fmtAgeClock(29), "0:29");
  assert.equal(fmtAgeClock(5 * 60 + 7), "5:07");
  assert.equal(fmtAgeClock(59 * 60 + 59), "59:59");
  assert.equal(fmtAgeClock(3600), "1:00:00");
  assert.equal(fmtAgeClock(3 * 3600 + 7 * 60 + 42), "3:07:42");
  assert.equal(fmtAgeClock(86400 + 2 * 3600 + 3 * 60 + 4), "1d 2:03:04");
  assert.equal(fmtAgeClock(Date.parse("garbage") / 1000), "");
  assert.equal(fmtAgeClock(NaN), "");
});

test("more than one repo groups the rows under headings, and a failed repo is said", () => {
  const two = { issues: [...board.issues, { ...board.issues[0], repo: "acme/other", number: 1 }], failures: ["acme/broken: 502"] };
  const html = render({ result: two });
  assert.match(html, /<h2>codefrak-oss\/frakworks<\/h2>/);
  assert.match(html, /<h2>acme\/other<\/h2>/);
  assert.ok(html.includes("could not list acme/broken: 502"));
});

test("repo groups share one column geometry: fixed layout and the same colgroup in every table", () => {
  const two = { issues: [{ ...board.issues[0], number: 7 }, { ...board.issues[0], repo: "acme/other", number: 1743 }] };
  const html = render({ result: two });
  const groups = html.match(/<colgroup>.*?<\/colgroup>/g);
  assert.equal(groups.length, (html.match(/<table/g) || []).length);
  assert.equal(groups.length, 2);
  assert.equal(groups[0], groups[1]);
  assert.equal((groups[0].match(/<col[ />]/g) || []).length, 3, "number, title, state");
  const css = require("node:fs").readFileSync(require("node:path").join(__dirname, "../src/renderer/styles.css"), "utf8");
  assert.match(css, /\.isView table \{[^}]*table-layout: fixed/);
});

test("the title cell wraps an unbreakable token instead of overlapping the state column", () => {
  const css = require("node:fs").readFileSync(require("node:path").join(__dirname, "../src/renderer/styles.css"), "utf8");
  assert.match(css, /\.isTitle \{[^}]*overflow-wrap: anywhere/);
});

test("the by column never renders, whatever the authors on the board", () => {
  const two = { issues: [...board.issues, { ...board.issues[0], repo: "acme/other", number: 1, author: "other" }] };
  const html = render({ result: two });
  assert.doesNotMatch(html, /<th>by<\/th>/);
  assert.doesNotMatch(html, /isWho/);
});

test("an error from the engine draws the .err block, not a blank view", () => {
  const html = render({ result: { error: "engine unreachable at http://engine.test/issues: ECONNREFUSED", issues: [] } });
  assert.match(html, /class="err"/);
  assert.ok(html.includes("ECONNREFUSED"));
  assert.doesNotMatch(html, /<table/);
  assert.ok(render({ result: { issues: [], loading: true } }).includes("asking the engine…"));
  assert.ok(render({ result: { issues: [], failures: [] } }).includes("No open tickets"));
});

test("the view is drawing only: no fs, net or host name of its own", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "issues.jsx"), "utf8");
  assert.doesNotMatch(src, /require\(|node:|fetch\(|localhost|8090/);
});

// ---- the "New intake" composer --------------------------------------------

test("submitIntake posts { ask, submittedBy, repo } verbatim to POST /intake and returns { ok, status, intakeId } on 202", async () => {
  const calls = [];
  const r = await submitIntake("do the thing", "lundmatt1", { env: {}, repo: "acme/widgets", fetch: stubFetch(202, JSON.stringify({ intakeId: "abc-123" }), calls) });
  assert.deepEqual(r, { ok: true, status: 202, intakeId: "abc-123" });
  assert.equal(calls[0].url, `${TEST_ENGINE_URL}/intake`);
  assert.equal(calls[0].init.method, "POST");
  assert.deepEqual(JSON.parse(calls[0].init.body), { ask: "do the thing", submittedBy: "lundmatt1", repo: "acme/widgets" });
});

test("submitIntake omits submittedBy when unset, and never blocks on it", async () => {
  const calls = [];
  const r = await submitIntake("do the thing", undefined, { env: {}, repo: "acme/widgets", fetch: stubFetch(202, JSON.stringify({ intakeId: "abc-123" }), calls) });
  assert.equal(r.intakeId, "abc-123");
  assert.deepEqual(JSON.parse(calls[0].init.body), { ask: "do the thing", repo: "acme/widgets" });
});

test("submitIntake surfaces a non-2xx engine error body, and refuses an empty ask before any request", async () => {
  const calls = [];
  const r = await submitIntake("do the thing", "matt", { env: {}, repo: "acme/widgets", fetch: stubFetch(503, "draining", calls) });
  assert.match(r.error, /engine answered 503 for POST \/intake: draining/);
  const empty = await submitIntake("   ", "matt", { env: {}, repo: "acme/widgets", fetch: stubFetch(202, "{}", calls) });
  assert.match(empty.error, /needs some text/);
  assert.equal(calls.length, 1, "the empty ask never made a request");
});

test("buildIntakeBody shapes the posted body the way submitIntake does", () => {
  assert.deepEqual(buildIntakeBody("hi", "matt"), { ask: "hi", submittedBy: "matt", authorize: true });
  assert.deepEqual(buildIntakeBody("hi", "matt", "o/r"), { ask: "hi", submittedBy: "matt", authorize: true, repo: "o/r" });
  assert.deepEqual(buildIntakeBody("hi"), { ask: "hi" });
});

test("parseIssueUrl parses a github.com issue URL; issueUrlOf reads an intake `issue` frame's URL", () => {
  assert.deepEqual(parseIssueUrl("https://github.com/codefrak-oss/frakworks/issues/128"), { repo: "codefrak-oss/frakworks", number: 128 });
  assert.equal(parseIssueUrl("not a url"), null);
  assert.equal(issueUrlOf({ type: "intake", data: { state: "issue", intakeId: "i", issueUrl: "https://github.com/a/b/issues/1" } }), "https://github.com/a/b/issues/1");
  assert.equal(issueUrlOf({ type: "intake", data: { state: "submitted", intakeId: "i" } }), null);
  assert.equal(issueUrlOf({ type: "flow", data: { issueUrl: "x" } }), null);
  assert.equal(issueUrlOf(null), null);
});

// A stand-in for window.fleet.onEngineEvent: `listen(cb)` records the
// subscriber, `push(frame)` delivers to it, `offs` counts unsubscribes.
const fakeStream = () => {
  const subs = new Set(); let offs = 0;
  return { listen: (cb) => { subs.add(cb); return () => { subs.delete(cb); offs++; }; },
           push: (frame) => { for (const cb of [...subs]) cb(frame); }, get offs() { return offs; }, get open() { return subs.size; } };
};
const issueFrame = (intakeId, issueUrl = "https://github.com/a/b/issues/9") => ({ type: "intake", data: { repo: "a/b", intakeId, state: "issue", number: 9, issueUrl }, subscription: "*" });

test("runIntake's success path resolves { issueUrl } from the intake's `issue` frame, calling onIntakeId once as soon as submit succeeds", async () => {
  const st = fakeStream();
  const submit = async () => ({ intakeId: "i-1" });
  let seen = 0;
  const p = runIntake({ ask: "do it" }, { submit, listen: st.listen, onIntakeId: (id) => { seen++; assert.equal(id, "i-1"); } });
  await new Promise((r) => setImmediate(r));
  assert.equal(st.open, 1, "listening before and while the submit is in flight");
  st.push({ type: "flow", data: { number: 1 } });
  st.push(issueFrame("i-other"));
  st.push(issueFrame("i-1"));
  assert.deepEqual(await p, { issueUrl: "https://github.com/a/b/issues/9" });
  assert.equal(seen, 1);
  assert.equal(st.offs, 1, "unsubscribed after");
});

test("runIntake: the `issue` frame delivered before the submit resolves is not missed (the engine publishes it inside the correlation)", async () => {
  const st = fakeStream();
  let resolveSubmit;
  const submit = () => new Promise((r) => { resolveSubmit = r; });
  const p = runIntake({ ask: "do it" }, { submit, listen: st.listen });
  await new Promise((r) => setImmediate(r));
  st.push(issueFrame("i-1", "https://github.com/a/b/issues/12"));
  resolveSubmit({ intakeId: "i-1" });
  assert.deepEqual(await p, { issueUrl: "https://github.com/a/b/issues/12" });
});

test("runIntake's error path resolves { error } without listening on", async () => {
  const st = fakeStream();
  const submit = async () => ({ error: "engine answered 503 for POST /intake: draining" });
  const r = await runIntake({ ask: "do it" }, { submit, listen: st.listen });
  assert.deepEqual(r, { error: "engine answered 503 for POST /intake: draining" });
  assert.equal(st.open, 0);
});

test("runIntake also stops on an intake that ends before its issue exists, and without a stream", async () => {
  const st = fakeStream();
  const submit = async () => ({ intakeId: "i-1" });
  const p = runIntake({ ask: "x" }, { submit, listen: st.listen });
  await new Promise((r) => setImmediate(r));
  st.push({ type: "intake", data: { intakeId: "i-1", state: "cancelled" } });
  assert.deepEqual(await p, { error: "the intake cancelled before its ticket was created" });
  assert.deepEqual(await runIntake({ ask: "x" }, { submit }), { error: "no event stream to wait for the ticket on" });
});

test("runIntake holds the intake's repo stream only when it is not the selected one", async () => {
  const held = [];
  const hold = (repo) => { held.push(`+${repo}`); return () => held.push(`-${repo}`); };
  const st = fakeStream();
  const submit = async () => ({ intakeId: "i-1" });
  const run = (args) => { const p = runIntake(args, { submit, listen: st.listen, hold }); setImmediate(() => st.push(issueFrame("i-1"))); return p; };
  await run({ ask: "a", repo: "o/b", selected: "o/a" });
  assert.deepEqual(held, ["+o/b", "-o/b"]);
  held.length = 0;
  await run({ ask: "a", repo: "o/b", selected: null });
  await run({ ask: "a", repo: null, selected: "o/a" });
  assert.deepEqual(held, [], "All repos hears every repo; the selected repo's stream is already held");
});

test("NewIntakeForm: closed is just the button; open has the ask textarea", () => {
  const closed = renderToStaticMarkup(React.createElement(NewIntakeForm, { open: false }));
  assert.match(closed, /class="newIntakeBtn"/);
  assert.ok(closed.includes("New intake"));

  const open = renderToStaticMarkup(React.createElement(NewIntakeForm, { open: true, ask: "" }));
  assert.match(open, /<textarea[^>]*class="[^"]*newIntakeText/);
  assert.doesNotMatch(open, /type="checkbox"/);
});

const sendBtn = (html) => /<button type="button" class="askSend"[^>]*>/.exec(html)[0];
const tierOptions = (html) => {
  const sel = [...html.matchAll(/<select[^>]*class="newIntakeTier"[^>]*>(.*?)<\/select>/g)];
  assert.equal(sel.length, 1);
  return [...sel[0][1].matchAll(/<option([^>]*)>(.*?)<\/option>/g)]
    .map((m) => ({ attrs: m[1], label: m[2].replace(/&#x27;/g, "'") }));
};

test("NewIntakeForm renders one tier <select>, Auto first and selected on open, then one option per tier of the engine's map", () => {
  assert.equal(tierRows.length, 10);
  const open = renderToStaticMarkup(React.createElement(NewIntakeForm, { open: true, ask: "", tier: null, tiers: tierRows }));
  assert.doesNotMatch(open, /type="radio"/);
  const opts = tierOptions(open);
  assert.equal(opts[0].label, "Auto — refiner picks");
  assert.equal(opts[0].label, AUTO_TIER_LABEL);
  assert.match(opts[0].attrs, /selected/);
  assert.deepEqual(opts.slice(1).map((o) => o.label), tierRows.map(tierLabel));
  assert.ok(opts.slice(1).every((o) => !/selected/.test(o.attrs)));
});

test("Start intake is enabled with Auto and an ask, with no pick-a-tier hint; a picked tier selects its option", () => {
  const auto = renderToStaticMarkup(React.createElement(NewIntakeForm, { open: true, ask: "x", tier: null, tiers: tierRows, target: "o/r" }));
  assert.doesNotMatch(sendBtn(auto), /disabled/);
  assert.doesNotMatch(auto, /newIntakeHint|Pick a tier/);

  const picked = renderToStaticMarkup(React.createElement(NewIntakeForm, { open: true, ask: "x", tier: 5, tiers: tierRows, target: "o/r" }));
  assert.doesNotMatch(sendBtn(picked), /disabled/);
  const selected = tierOptions(picked).filter((o) => /selected/.test(o.attrs));
  assert.deepEqual(selected.map((o) => o.label), ["5 · opus medium"]);

  // A blank ask still disables it, Auto or not.
  for (const tier of [null, 5]) {
    const empty = renderToStaticMarkup(React.createElement(NewIntakeForm, { open: true, ask: "  ", tier, tiers: tierRows }));
    assert.match(sendBtn(empty), /disabled/);
  }
});

// #577: the composer files in the selected repo, or asks for one in All repos.
test("the composer names the selected repo; in All repos Start waits for a repo pick", () => {
  const fixed = renderToStaticMarkup(React.createElement(NewIntakeForm, { open: true, ask: "x", tier: null, tiers: tierRows, target: "o/r" }));
  assert.match(fixed, /class="newIntakeRepo">files in <b>o\/r<\/b>/);
  assert.doesNotMatch(fixed, /newIntakeRepoPick/);
  const all = renderToStaticMarkup(React.createElement(NewIntakeForm, { open: true, ask: "x", tier: null, tiers: tierRows, repos: ["o/a", "o/b"] }));
  assert.match(all, /<select class="newIntakeRepoPick" aria-label="repo" required=""/);
  assert.match(all, /<option value="" selected="">Pick a repo…<\/option><option value="o\/a">o\/a<\/option><option value="o\/b">o\/b<\/option>/);
  assert.match(sendBtn(all), /disabled/);
  const picked = renderToStaticMarkup(React.createElement(NewIntakeForm, { open: true, ask: "x", tier: null, tiers: tierRows, repos: ["o/a", "o/b"], repo: "o/b" }));
  assert.doesNotMatch(sendBtn(picked), /disabled/);
  assert.match(picked, /<option value="o\/b" selected="">o\/b<\/option>/);
});

test("runIntake sends the picked repo only when there is one", async () => {
  const sent = [];
  const submit = async (...a) => { sent.push(a); return { intakeId: "i" }; };
  const st = fakeStream();
  const go = (args) => { const p = runIntake(args, { submit, listen: st.listen }); setImmediate(() => st.push(issueFrame("i", "https://github.com/o/b/issues/3"))); return p; };
  await go({ ask: "a" });
  await go({ ask: "a", repo: "o/b" });
  assert.deepEqual(sent, [["a"], ["a", "o/b"]]);
});

test("a tier table main could not read shows an .err line; Auto stays selected and startable", () => {
  const html = renderToStaticMarkup(React.createElement(NewIntakeForm, { open: true, ask: "x", tier: null, tiers: { error: "ENOENT" }, target: "o/r" }));
  assert.match(html, /class="err">could not load the tiers: ENOENT</);
  const opts = tierOptions(html);
  assert.equal(opts.length, 1);
  assert.match(opts[0].attrs, /selected/);
  assert.doesNotMatch(sendBtn(html), /disabled/);
});

test("with Auto the posted ask is exactly as typed, no Tier: line; with a tier it is tieredAsk", () => {
  const typed = "do the thing\n  with a second line";
  assert.equal(intakeAsk(tierRows, null, typed), typed);
  assert.equal(intakeAsk({ error: "ENOENT" }, null, typed), typed);
  assert.doesNotMatch(intakeAsk(tierRows, null, typed), /Tier:/);
  const row5 = tierRows.find((r) => r.tier === 5);
  assert.equal(intakeAsk(tierRows, 5, typed), tieredAsk(row5, typed));
});

test("the posted ask is the Tier: line first, then the typed ask verbatim, and reaches submit unchanged", async () => {
  const row5 = tierRows.find((r) => r.tier === 5);
  const ask = tieredAsk(row5, "do the thing\n  with a second line");
  const lines = ask.split("\n");
  assert.equal(lines[0], "Tier: 5 (opus medium) — prescribed by the user; do not re-judge");
  assert.equal(lines.slice(1).join("\n"), "do the thing\n  with a second line");

  const seen = [];
  const submit = async (a) => { seen.push([a]); return { intakeId: "i-1" }; };
  const st = fakeStream();
  const p = runIntake({ ask }, { submit, listen: st.listen });
  setImmediate(() => st.push(issueFrame("i-1")));
  await p;
  assert.deepEqual(seen, [[ask]]);
  assert.deepEqual(buildIntakeBody(seen[0][0]), { ask });
});

test("NewIntakeForm renders the engine's error text inline when an error prop is set", () => {
  const html = renderToStaticMarkup(React.createElement(NewIntakeForm, { open: true, ask: "x", error: "engine answered 503 for POST /intake: draining" }));
  assert.match(html, /class="err"/);
  assert.ok(html.includes("engine answered 503 for POST /intake: draining"));
});

test("NewIntakeForm renders the intake id and the resolved issue link", () => {
  const html = renderToStaticMarkup(React.createElement(NewIntakeForm, { open: true, ask: "x", intakeId: "i-1", issueUrl: "https://github.com/a/b/issues/9" }));
  assert.ok(html.includes("i-1"));
  assert.ok(html.includes('href="https://github.com/a/b/issues/9"'));
});

test("the Issues view's head block has the New intake button, and no refresh button or \"from the engine\" label", () => {
  const html = render({ result: board, now: Date.parse("2026-09-24T14:00:00Z") });
  const headIdx = html.indexOf('class="head"');
  const metaIdx = html.indexOf('class="meta"', headIdx);
  const btnIdx = html.indexOf('class="newIntakeBtn"', metaIdx);
  assert.ok(headIdx >= 0 && metaIdx > headIdx, "the head block has a meta span");
  assert.ok(btnIdx > metaIdx, "New intake sits in the head");
  assert.doesNotMatch(html, /refreshBtn/);
  assert.doesNotMatch(html, /from the engine/);
});

test("a split parent's row summarises its parts and a child's row names its parent", () => {
  const parent = board.issues.find((i) => i.number === 60);
  const child = board.issues.find((i) => i.number === 62);
  assert.equal(childrenSummary(parent), "3 parts: #61 merged, #62 open, 1 pending");
  assert.equal(childrenSummary(child), null);
  assert.equal(childrenSummary({ children: [{ state: "merged", number: 1 }, { state: "dropped" }] }), "1 part: #1 merged, 1 dropped");
  const html = render({ result: board, now: Date.parse("2026-09-25T12:00:00Z") });
  assert.match(html, /3 parts: #61 merged, #62 open, 1 pending/);
  assert.match(html, /class="isLabel isPart">part of #60/);
  assert.match(html, /class="isState"[^>]*>waiting on #62</);
});

// ---- Issues ⊇ Needs (#565) -------------------------------------------------
//
// A merged-and-closed feature:confirm ticket (#508) is off GET /issues but
// still on GET /needs as an acceptance: the view lists it anyway.

const { toRows: needsToRows } = require("../src/needs-you");
const NEED_REPO = "codefrak-oss/frakworks";
const onBoard = { repo: NEED_REPO, number: 509, title: "on the board", labels: ["feature"], url: `https://github.com/${NEED_REPO}/issues/509` };
const needRows = needsToRows([
  { repo: NEED_REPO, number: 508, title: "Desktop: a confirm-mode feature", kind: "acceptance",
    wanted: "Does the feature do what you asked?", since: "2026-09-25T10:00:00Z", actionId: "0541c973-ba0b-11f1-a5bc-4ac7a8a66890" },
  { repo: NEED_REPO, number: 509, title: "on the board", kind: "question", wanted: "?", since: null, actionId: "q-509" },
  { repo: null, number: null, title: "", kind: "incident", wanted: "a flow tied to no issue", since: null, actionId: "inc-1" },
]);
const rowCount = (html, n) => (html.match(new RegExp(`>#${n}</a>`, "g")) || []).length;

test("an issue off the board with a need row gets a row marked awaiting acceptance", () => {
  const html = render({ result: { issues: [onBoard], failures: [] }, needs: needRows });
  assert.equal(rowCount(html, 508), 1);
  assert.match(html, /Desktop: a confirm-mode feature/);
  assert.match(html, /class="isRow is-need"/);
  assert.match(html, /awaiting acceptance/);
  assert.match(html, /class="isLabel">closed</);
  assert.match(html, /href="https:\/\/github.com\/codefrak-oss\/frakworks\/issues\/508"/);
  assert.match(html, /Tickets · 2/);
});

test("an issue on the board that also has a need row is not duplicated", () => {
  const html = render({ result: { issues: [onBoard], failures: [] }, needs: needRows });
  assert.equal(rowCount(html, 509), 1);
  assert.doesNotMatch(html, /awaiting your answer/);
});

test("every issue in the Needs rows appears in the Issues rows (Issues ⊇ Needs)", () => {
  const issues = withNeeds(board.issues, needRows, board.failures || []);
  const listed = new Set(issues.map((i) => `${i.repo}#${i.number}`));
  for (const r of needRows.filter((r) => r.repo && r.ticket != null)) assert.ok(listed.has(`${r.repo}#${r.ticket}`), `${r.repo}#${r.ticket}`);
  const html = render({ result: board, needs: needRows });
  for (const r of needRows.filter((r) => r.repo && r.ticket != null)) assert.equal(rowCount(html, r.ticket), 1);
});

test("a need with no repo or number is left out, and another need kind reads with its own wording", () => {
  const rows = withNeeds([], needsToRows([
    { repo: null, number: null, kind: "incident", actionId: "x" },
    { repo: NEED_REPO, number: 7, title: "t", kind: "decision", actionId: "d" },
  ]));
  assert.equal(rows.length, 1);
  assert.equal(stateOf(rows[0]).text, needPhrase("decision"));
  assert.equal(needPhrase("acceptance"), "awaiting acceptance");
  assert.equal(needPhrase("someday"), "awaiting someday");
  // a repo the board could not list: the row is there, but not called closed
  assert.deepEqual(withNeeds([], needsToRows([{ repo: NEED_REPO, number: 7, kind: "acceptance", actionId: "a" }]), [NEED_REPO])[0].labels, ["not listed"]);
});

test("a failed or missing needs read draws the board as before", () => {
  // One fixed clock: rendered ages tick between calls otherwise.
  const now = Date.now();
  const plain = render({ result: board, now });
  assert.equal(render({ result: board, needs: [], now }), plain);
  assert.equal(render({ result: board, needs: undefined, now }), plain);
  assert.doesNotMatch(plain, /awaiting acceptance/);
});

test("clicking the added row hands its number and repo to onOpenTicket, the detail that reads it through readOffBoard", () => {
  // The added row is an ordinary Row: its click hands up (number, repo), and
  // index.jsx's IssueScreen reads an off-board issue (test/issue-off-board.test.js).
  const added = withNeeds([onBoard], needRows).find((i) => i.needOnly);
  assert.equal(added.number, 508);
  assert.equal(added.repo, NEED_REPO);
  const index = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "index.jsx"), "utf8");
  assert.match(index, /needs=\{needs && needs\.rows\} onOpenTicket=/);
  // The needs rows follow the stream, not a timer gated on the view (#976).
  assert.match(index, /useRefetchOn\(\["needs", "ticket"\], refreshNeeds\)/);
});

test("a proposal whose flow has a pitch page is marked, and one without is not (#563)", () => {
  const { hasPitch } = require("../dist/issues.cjs");
  const at = "2026-09-26T10:00:00Z";
  const row = (n, pitch) => ({ repo: "o/r", number: n, title: `P ${n}`, author: "a", labels: ["proposal"], url: `https://github.com/o/r/issues/${n}`,
    createdAt: at, updatedAt: at, kind: "proposal", authorized: true,
    flow: { flow: "proposal", outcome: "started", instance: `p-${n}`, startedAt: at, ended: false, prUrl: "u", pitch } });
  const html = render({ result: { issues: [row(1, "docs/proposals/x.html"), row(2, null)], failures: [] }, now: Date.parse(at) });
  assert.equal([...html.matchAll(/class="isLabel isPitch"/g)].length, 1);
  assert.ok(html.includes('title="docs/proposals/x.html">pitch</span>'));
  assert.equal(hasPitch(row(2, null)), false);
  assert.equal(hasPitch({ flow: null }), false);
});

test("the board's \"asked by me\" filter keeps the login's own asks", () => {
  assert.ok(BOARD_FILTERS.some((f) => f.label === "asked by me"));
  const board = [
    { repo: "o/r", number: 1, title: "Mine", labels: [], askedBy: "LundMatt1" },
    { repo: "o/r", number: 2, title: "Theirs", labels: [], askedBy: "someone" },
    { repo: "o/r", number: 3, title: "Nobody", labels: [], askedBy: null },
  ];
  assert.deepEqual(filterIssues(board, "askedByMe", "lundmatt1").map((i) => i.number), [1]);
  assert.equal(filterIssues(board, "all", "lundmatt1").length, 3);
  const html = renderToStaticMarkup(React.createElement(Issues, { result: { issues: board }, login: "lundmatt1", filter: "askedByMe" }));
  assert.match(html, /asked by me/);
  // A radio pair beside the heading, not a select (#1289); the checked one is the filter.
  assert.ok(!html.includes("<select"));
  assert.equal([...html.matchAll(/type="radio" name="isFilter"/g)].length, 2);
  assert.match(html, /<label class="on"><input type="radio" name="isFilter" checked="" value="askedByMe"/);
  assert.match(html, /<h1>Tickets[^<]*<\/h1><span class="isFilter"/);
  const anon = renderToStaticMarkup(React.createElement(Issues, { result: { issues: board } }));
  assert.ok(!anon.includes('name="isFilter"'));
  assert.ok(html.includes("Mine") && !html.includes("Theirs") && !html.includes("Nobody"));
});

test("a row shows its story points as a chip, and nothing when it has none", () => {
  const row = { ...board.issues[0], parent: null, children: [] };
  const html = render({ result: { issues: [{ ...row, points: 5 }], failures: [] } });
  assert.match(html, /5 pts/);
  assert.match(html, /Story points \(bots\/story-points\.md\)/);
  const none = render({ result: { issues: [{ ...row, points: null }], failures: [] } });
  assert.doesNotMatch(none, /pts/);
});

test("a flow at the post-deploy check reads its state from its step, and an ended check from its end", () => {
  const { postDeployState, postDeployEnd, POST_DEPLOY_ENDS } = require("../dist/issues.cjs");
  const at = (step) => ({ number: 1, kind: "chore", flow: { flow: "chore", outcome: "started", ended: false, stage: "call-post-deploy-check", step } });
  assert.deepEqual(stateOf(at("set the deadline\n(engine)")), { key: "running", text: "merged, awaiting deploy" });
  assert.equal(stateOf(at("wait for the deploy")).text, "merged, awaiting deploy");
  assert.equal(stateOf(at("check it\n(smith run)")).text, "verifying in production");
  assert.equal(stateOf(at("coding")).text, "verifying in production");
  assert.equal(stateOf(at("wake at")).text, "verifying, next check scheduled");
  assert.equal(stateOf(at("wait for the event")).text, "verifying, next check scheduled");
  assert.deepEqual(stateOf(at("decide at the deadline")), { key: "question", text: "waiting on you: the deploy deadline" });
  assert.equal(postDeployState({ stage: "call-do-work", step: "wake at" }), null);
  const ended = (steps) => stateOf({ number: 1, kind: "chore", flow: { flow: "chore", outcome: "started", ended: true, steps } }).text;
  assert.equal(ended([{ activityId: "call-check" }, { activityId: "end-check-passed" }]), POST_DEPLOY_ENDS.passed);
  assert.equal(ended(["task-file-check-bug", "end-check-failed"]), "check failed: bug filed");
  assert.equal(ended(["user-task-answer-deadline", "task-read-deadline-answer", "end-post-deploy-check"]), "deadline reached: dropped");
  assert.equal(ended(["task-read-deadline-answer", "task-file-deadline-bug", "end-post-deploy-check"]), "deadline reached: bug filed");
  assert.equal(ended(["end-chore-done"]), "done");
  assert.equal(postDeployEnd(null), null);
});
