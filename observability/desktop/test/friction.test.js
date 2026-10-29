// friction.jsx — the Friction notes page (Explorer > Analytics > Friction
// notes, #1046, #1098), drawn from fixture-friction-summary.json,
// fixture-friction-notes.json and fixture-friction-groups.json (the shapes GET
// /friction/summary, /friction/notes and /friction/groups answer,
// engine/README.md "Friction notes"): the strip's counts and its groups stat,
// the per-day and by-kind bar data, the group rows with their members and
// joined-by column, the grouping… row, Pull out and Merge into…, the empty
// state and the one-line error; then the explorer row, the bridge and the
// engine client.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const {
  frictionStats, perDayBars, kindBars, noteRow, pageState, fmtUtc, fmtShort, FrictionNotesView, FrictionNotesTable, FrictionStrip,
  FrictionGroupsTable, FrictionMergeView, joinedBy, countLabel, groupRow, tableRows,
  KINDS, DEFAULT_WINDOW, DASH,
} = require("../dist/friction.cjs");
const { fetchFrictionSummary, fetchFrictionNotes, fetchFrictionGroups, frictionPullOut, frictionNotTwoThings, frictionMerge } = require("../src/engine-data");
const { twoThingsLabel, bugLink, fixLink, visibleGroups, fixedCount, parseFix } = require("../dist/friction.cjs");
const { frictionFix } = require("../src/engine-data");

const load = (f) => JSON.parse(fs.readFileSync(path.join(__dirname, "..", f), "utf8"));
const summary = { ...load("fixture-friction-summary.json"), groups: 2, ungrouped: 1 };
const notes = load("fixture-friction-notes.json");
const groups = load("fixture-friction-groups.json");
// The notes as the engine serves them beside the groups: every member grouped, and one note the grouper has not placed yet.
const grouped = groups.flatMap((g) => g.members);
const ungrouped = { ...notes[1], id: "u1", noted_at: "2026-10-04T11:30:00Z", summary: "the brief describes a draft", kind: "bug", severity: null,
  signature: "k-u1", group_id: null, grouped_by: null, jev_call_id: null, grouped_login: null, jev_confidence: null, two_things: null, two_things_call_id: null };
const allNotes = [...grouped, ungrouped];
const render = (C, props) => renderToStaticMarkup(React.createElement(C, props));
const src = (f) => fs.readFileSync(path.join(__dirname, "..", "src", f), "utf8");

test("the strip's counts are the summary's total, by kind and by source, every known one listed", () => {
  const s = frictionStats(summary);
  assert.equal(s.total, 6);
  assert.deepEqual(s.byKind, [["bug", 2], ["design", 1], ["docs", 1], ["tooling", 1], ["other", 1]]);
  assert.deepEqual(s.bySource, [["as-you-go", 2], ["retro", 2], ["first-mate", 2]]);
  const sparse = frictionStats({ total: 1, by_kind: { docs: 1 }, by_source: { retro: 1, "new-source": 3 } });
  assert.deepEqual(sparse.byKind, KINDS.map((k) => [k, k === "docs" ? 1 : 0]), "a kind the engine left out is 0");
  assert.deepEqual(sparse.bySource.at(-1), ["new-source", 3], "an unknown source is kept, after the known ones");
  assert.equal(frictionStats(null).total, null);

  const html = render(FrictionStrip, { stats: frictionStats(summary), win: "7d" });
  for (const w of ["24h", "7d", "30d", "90d"]) assert.ok(html.includes(`>${w}</button>`), w);
  assert.match(html, /<button class="on">7d<\/button>/);
  assert.match(html, /<b>6<\/b><span>notes<\/span>/);
  assert.match(html, /<b>2<\/b><span>groups<\/span>/, "the groups stat stands beside the total");
  assert.equal(s.groups, 2);
  assert.equal(frictionStats({ total: 3 }).groups, null, "an older engine's summary has no groups");
  assert.match(render(FrictionStrip, { stats: frictionStats({ total: 3, by_kind: {}, by_source: {} }), win: "7d" }), /<b>—<\/b><span>groups<\/span>/);
  assert.match(html, /<b>2<\/b><span>bug<\/span>/);
  assert.match(html, /<b>2<\/b><span>First mate<\/span>/);
  assert.match(render(FrictionStrip, { stats: null, win: "7d" }), /<b>–<\/b><span>notes<\/span>/, "a dash before the summary arrives");
});

test("the per-day bars have one bar per UTC day the summary covers, zeros included, and the kind bars every kind", () => {
  const days = perDayBars(summary);
  assert.equal(days.length, 8);
  assert.deepEqual(days[0], ["2026-09-28", 0]);
  assert.deepEqual(days[4], ["2026-10-02", 3]);
  assert.equal(days.reduce((s, [, v]) => s + v, 0), summary.total);
  assert.deepEqual(perDayBars(null), []);
  assert.deepEqual(kindBars(summary).map(([k]) => k), KINDS);

  const html = render(FrictionNotesView, { result: { summary }, notes: { notes: allNotes }, groups: { groups }, win: DEFAULT_WINDOW });
  assert.match(html, /<h1>Friction notes<\/h1>/);
  assert.equal((html.match(/<svg[^>]*frictionPerDay[^>]*>/g) || []).length, 1);
  assert.equal((html.match(/<rect /g) || []).length, 8 + 5, "eight day bars and five kind bars");
  assert.match(html, /<title>2026-10-02: 3 notes<\/title>/);
  assert.match(html, /<title>design: 1 note<\/title>/);
  assert.match(html, /var\(--p-impl\)/, "drawn with the palette");
  const charts = (html.match(/<svg[\s\S]*?<\/svg>/g) || []).join("");
  assert.doesNotMatch(charts, /#[0-9a-f]{6}/i, "no fixed colour in the charts");
});

test("the table lists the notes newest first with a link that opens the ticket in the app, and a dash for First mate's", () => {
  const rows = notes.map(noteRow);
  assert.deepEqual(rows.map((r) => r.id), ["f2", "f1", "b2", "b1", "a2", "a1"]);
  assert.deepEqual(rows[5], {
    id: "a1", at: "10-02 10:00", atFull: "2026-10-02 10:00 UTC", repo: "acme/widgets",
    ticket: { repo: "acme/widgets", issue: 7, href: "frakworks://ticket/acme/widgets/7" },
    source: "as you go", kind: "bug", severity: "high", summary: "The <engine> answered 500 on a blank since.",
  });
  assert.equal(rows[0].ticket, null, "a First mate note has no ticket");
  assert.equal(rows[1].repo, DASH);
  assert.equal(rows[1].severity, DASH);
  assert.equal(rows[0].source, "First mate");

  const html = render(FrictionNotesTable, { notes, open: {} });
  assert.match(html, /<table class="jevCalls frictionNotes">/);
  assert.match(html, /<th class="jevWhen">noted at<\/th><th>repo<\/th><th>issue<\/th><th>source<\/th><th>kind<\/th><th>severity<\/th><th>summary<\/th>/);
  assert.match(html, /<a href="frakworks:\/\/ticket\/acme\/gadgets\/9" title="acme\/gadgets#9">#9<\/a>/);
  assert.equal((html.match(/<td>—<\/td>/g) || []).length >= 2, true, "the two First mate notes show a dash for the issue");
  assert.match(html, /The &lt;engine&gt; answered 500/, "a summary is text, escaped");
  assert.doesNotMatch(html, /frictionDetail/, "no detail row while closed");
});

test("an open row shows the full summary, the evidence preformatted, and the run, process instance and login when set", () => {
  const html = render(FrictionNotesTable, { notes, open: { b2: true, f1: true } });
  assert.equal((html.match(/class="doctorDetail frictionDetail"/g) || []).length, 2);
  assert.match(html, /<pre class="jevPre frictionEvidence">check-result work \/work\/out\/result.json\naddressed: expected array<\/pre>/);
  assert.match(html, /run<\/span> run-b2/);
  assert.match(html, /process instance<\/span> root-b2/);
  assert.match(html, /login<\/span> someone/, "First mate's login");
  const b2 = html.slice(html.indexOf("run-b2") - 400, html.indexOf("root-b2"));
  assert.doesNotMatch(b2, /login<\/span>/, "a Smith's note has no login");
  assert.match(render(FrictionNotesTable, { notes: [notes[0]], open: { f2: true } }), /none given/, "no evidence");
});

test("zero notes in the window is one line and no chart; an engine error is the one-line error", () => {
  const empty = { ...summary, total: 0, per_day: summary.per_day.map((d) => ({ ...d, count: 0 })),
    by_kind: {}, by_source: {}, by_severity: {}, by_repo: {} };
  assert.equal(pageState({ summary: empty }), "empty");
  const html = render(FrictionNotesView, { result: { summary: empty }, notes: { notes: [] }, groups: { groups: [] } });
  assert.match(html, /<div class="muted">no friction notes in the window<\/div>/);
  assert.doesNotMatch(html, /<svg|<table/);

  const error = "engine unreachable at http://engine.test:8090/friction/summary: ECONNREFUSED";
  assert.equal(pageState({ error }), "error");
  const bad = render(FrictionNotesView, { result: { error }, notes: { error }, groups: { error } });
  assert.match(bad, /<div class="err">engine unreachable at http:\/\/engine.test:8090\/friction\/summary: ECONNREFUSED<\/div>/);
  assert.equal((bad.match(/class="err"/g) || []).length, 1, "one line, no table");
  assert.doesNotMatch(bad, /<svg|<table/);

  assert.equal(pageState({ loading: true }), "loading");
  assert.equal(pageState({ summary }), "notes");
  assert.equal(fmtUtc(null), DASH);
});

test("the notes' and the groups' own errors show under the charts, and filters that match nothing say so", () => {
  const failed = render(FrictionNotesView, { result: { summary }, notes: { error: "engine answered 500 for /friction/notes" }, groups: { groups } });
  assert.match(failed, /<svg/);
  assert.match(failed, /<div class="err">engine answered 500 for \/friction\/notes<\/div>/);
  const noGroups = render(FrictionNotesView, { result: { summary }, notes: { notes: allNotes }, groups: { error: "this engine has no /friction/groups (older than #1095)" } });
  assert.match(noGroups, /<div class="err">this engine has no \/friction\/groups \(older than #1095\)<\/div>/);
  assert.doesNotMatch(noGroups, /<table/, "no table without the groups");
  const none = render(FrictionNotesView, { result: { summary }, notes: { notes: [] }, groups: { groups: [] }, kind: "bug", source: "retro" });
  assert.match(none, /no notes match the filters/);
  assert.match(none, /<option value="bug" selected="">bug<\/option>/);
  assert.match(none, /<option value="retro" selected="">retro<\/option>/);
});

test("Analytics has a Friction notes row after Doctor and before the Grafana rows, and its own page over the bridge (#1046)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const { VIEWS } = require("../dist/index.cjs");
  const html = renderToStaticMarkup(React.createElement(Explorer, {
    sel: { kind: "friction" }, onSelect: () => {}, grafana: [{ title: "Smith runs", url: "http://g" }],
  }));
  assert.match(html, />Doctor<\/span><\/div><div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">Friction notes</);
  assert.ok(html.indexOf(">Friction notes<") < html.indexOf(">Smith runs<") || !html.includes(">Smith runs<"));
  assert.match(html, /<span class="exChev">▾<\/span><span class="exLabel">Analytics</, "the branch opens on it");
  assert.ok(VIEWS.some((v) => v.id === "friction" && v.label === "Friction notes" && v.branch === "Analytics"));
  assert.match(src("renderer/explorer.jsx"), /const ANALYTICS_KINDS = \[[^\]]*"friction"/);
  assert.match(src("renderer/index.jsx"), /view === "friction"\s*\? <FrictionNotes onOpenTicket=\{onJevTicket\} \/>/);
  assert.match(src("preload.js"), /frictionSummary: \(filter\) => ipcRenderer\.invoke\("engine:friction-summary"/);
  assert.match(src("preload.js"), /frictionNotes: \(filter\) => ipcRenderer\.invoke\("engine:friction-notes"/);
  assert.match(src("main.js"), /ipcMain\.handle\("engine:friction-summary", async \(_e, filter\) => \{\n  const r = await fetchFrictionSummary\(/);
  assert.match(src("main.js"), /ipcMain\.handle\("engine:friction-notes", async \(_e, filter\) => \{\n  const r = await fetchFrictionNotes\(/);
  const page = src("renderer/friction.jsx");
  assert.doesNotMatch(page, /require\(|node:|fetch\(/, "the page reads the engine only through the bridge");
  assert.match(page, /import \{ WINDOWS, DEFAULT_WINDOW, sinceOf \} from "\.\/jev\.jsx"/, "the windows are Jev's, imported");
});

// ---- the engine client -------------------------------------------------------------

const env = { engineUrl: "http://engine.test:8090" };
const fake = (status, body) => {
  const calls = [];
  return { calls, opts: { env, fetch: async (url) => { calls.push(url); return { ok: status < 400, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)) }; } } };
};

test("fetchFrictionSummary calls GET /friction/summary with since and repo, and resolves to the summary", async () => {
  const f = fake(200, summary);
  const r = await fetchFrictionSummary({ since: "2026-09-28T12:00:00.000Z", repo: "acme/widgets" }, f.opts);
  assert.deepEqual(f.calls, ["http://engine.test:8090/friction/summary?since=2026-09-28T12%3A00%3A00.000Z&repo=acme%2Fwidgets"]);
  assert.deepEqual(r, { summary, error: null });
  const bare = fake(200, summary);
  await fetchFrictionSummary({}, bare.opts);
  assert.deepEqual(bare.calls, ["http://engine.test:8090/friction/summary"]);
  assert.match((await fetchFrictionSummary({}, fake(200, { from: "x" }).opts)).error, /no `total`\/`per_day`/);
  assert.equal((await fetchFrictionSummary({}, fake(404, "").opts)).error, "this engine has no /friction/summary (older than #1046)");
  const g = fake(200, summary);
  assert.match((await fetchFrictionSummary({ repo: "../x" }, g.opts)).error, /not an owner\/name repo/);
  assert.match((await fetchFrictionSummary({ since: 7 }, g.opts)).error, /not a since/);
  assert.deepEqual(g.calls, [], "a bad argument never reaches the engine");
});

test("fetchFrictionNotes calls GET /friction/notes with each filter, caps the limit, and resolves to the notes", async () => {
  const f = fake(200, notes);
  const r = await fetchFrictionNotes({ since: "2026-09-28T12:00:00Z", repo: "acme/widgets", kind: "bug", source: "first-mate", limit: 9000 }, f.opts);
  assert.deepEqual(f.calls, ["http://engine.test:8090/friction/notes?since=2026-09-28T12%3A00%3A00Z&repo=acme%2Fwidgets&kind=bug&source=first-mate&limit=500"]);
  assert.deepEqual(r, { notes, error: null });
  const g = fake(200, notes);
  assert.match((await fetchFrictionNotes({ kind: "nope" }, g.opts)).error, /not a friction kind/);
  assert.match((await fetchFrictionNotes({ source: "x&y=1" }, g.opts)).error, /not a friction source/);
  assert.match((await fetchFrictionNotes({ limit: "many" }, g.opts)).error, /not a limit/);
  assert.deepEqual(g.calls, []);
  assert.match((await fetchFrictionNotes({}, fake(200, { notes }).opts)).error, /is no list/);
  assert.match((await fetchFrictionNotes({}, fake(500, "boom").opts)).error, /engine answered 500 for \/friction\/notes/);
});

test("an unreachable engine yields the Jev fetches' error shape, and neither fetch rejects", async () => {
  const { fetchJevSummary } = require("../src/engine-data");
  const down = { env, fetch: async () => { throw new Error("ECONNREFUSED"); } };
  const jev = await fetchJevSummary(undefined, down);
  const s = await fetchFrictionSummary({}, down);
  const n = await fetchFrictionNotes({}, down);
  assert.deepEqual(Object.keys(s), Object.keys(jev));
  assert.deepEqual(Object.keys(n), Object.keys(jev));
  assert.equal(s.error, "engine unreachable at http://engine.test:8090/friction/summary: ECONNREFUSED");
  assert.equal(n.error, "engine unreachable at http://engine.test:8090/friction/notes: ECONNREFUSED");
});

// ---- the groups (#1098) ------------------------------------------------------------

test("a group row has the count (9 of 30 when members are hidden), newest, oldest, kind, repos, severity and title, newest activity first", () => {
  assert.equal(countLabel({ count: 30, total: 30 }), "30");
  assert.equal(countLabel({ count: 9, total: 30 }), "9 of 30");
  assert.equal(countLabel({}), DASH);
  assert.deepEqual(groupRow(groups[1]), {
    id: 12, count: "2 of 3", newest: "10-04 10:00", newestFull: "2026-10-04 10:00 UTC", oldest: "10-02 10:00", oldestFull: "2026-10-02 10:00 UTC", kind: "bug",
    repos: "gadgets, widgets", reposFull: "acme/gadgets, acme/widgets", severity: "high", title: "The <engine> answered 500 on a blank since.",
    bug: null, fix: null, state: "open" });
  assert.equal(groupRow({ id: 1, count: 1, total: 1, repos: [], kind: "mixed" }).repos, DASH, "no repo is a dash");
  assert.equal(groupRow({ id: 1, count: 1, total: 1 }).severity, DASH);

  const rows = tableRows(groups, allNotes);
  assert.deepEqual(rows.map((r) => r.key), ["g31", "nu1", "g12"], "groups and the ungrouped note, newest activity first");
  assert.deepEqual(tableRows(groups, notes).map((r) => r.kind), ["group", "note", "note", "group", "note", "note", "note", "note"],
    "a note with no group_id at all (an older engine) is shown as not yet grouped");
  assert.deepEqual(tableRows(null, null), []);

  const html = render(FrictionGroupsTable, { groups, notes: allNotes, open: {} });
  assert.match(html, /<table class="jevCalls frictionGroups">/);
  assert.match(html, /<th>count<\/th><th class="jevWhen">newest<\/th><th class="jevWhen">oldest<\/th><th>kind<\/th><th>repos<\/th><th>severity<\/th><th>title<\/th>/);
  assert.match(html, /<tr class="jevRow frictionGroupRow severity-high" title="Show this group&#x27;s notes"><td class="frictionCount">4<\/td><td class="jevWhen" title="2026-10-04 12:00 UTC">10-04 12:00<\/td><td class="jevWhen" title="2026-10-02 11:00 UTC">10-02 11:00<\/td><td>mixed<\/td><td class="jevText" title="acme\/widgets, acme\/gadgets">widgets, gadgets<\/td><td>high<\/td>/,
    "the highest severity colours the row as severity-high, the tie of kinds is mixed as the engine says");
  assert.match(html, /<td class="frictionCount">2 of 3<\/td>/);
  assert.match(html, /The &lt;engine&gt; answered 500 on a blank since\./, "a title is text, escaped");
  assert.doesNotMatch(html, /frictionGroupDetail|frictionNotes/, "no members while closed");
  // The ungrouped note: no count, its own time twice, its summary marked grouping…
  assert.match(html, /<tr class="jevRow frictionGroupRow frictionUngrouped severity-unset"[^>]*><td class="frictionCount">—<\/td><td class="jevWhen" title="2026-10-04 11:30 UTC">10-04 11:30<\/td><td class="jevWhen" title="2026-10-04 11:30 UTC">10-04 11:30<\/td><td>bug<\/td>/);
  assert.match(html, /the brief describes a draft <span class="muted frictionGrouping">\(grouping…\)<\/span>/);
  assert.equal((html.match(/<tr class="jevRow frictionGroupRow/g) || []).length, 3);
});

test("an open group lists its members as note rows with a joined-by column in each of its six forms, and a Pull out on each", () => {
  assert.equal(joinedBy(groups[1].members[1]), "founder");
  assert.equal(joinedBy(groups[0].members[3]), "exact");
  assert.equal(joinedBy(groups[1].members[0]), "jev 0.91");
  assert.equal(joinedBy({ group_id: 1, grouped_by: "jev", jev_confidence: null }), "jev", "a jev join whose call lost its answer");
  assert.equal(joinedBy(groups[0].members[1]), "fallback");
  assert.equal(joinedBy(groups[0].members[2]), "no jev");
  assert.equal(joinedBy(groups[0].members[0]), "@someone", "a user's pull-out names the login, not the call's confidence");
  assert.equal(joinedBy(ungrouped), "grouping…");
  assert.equal(joinedBy({ group_id: 1, grouped_by: "later" }), "later", "a value the engine adds later shows as it is");

  const html = render(FrictionGroupsTable, { groups, notes: allNotes, open: { g31: true }, onPullOut: () => {} });
  assert.equal((html.match(/class="doctorDetail frictionGroupDetail"/g) || []).length, 1);
  assert.match(html, /<table class="jevCalls frictionNotes"><thead><tr><th class="jevWhen">noted at<\/th><th>repo<\/th><th>issue<\/th><th>source<\/th><th>kind<\/th><th>severity<\/th><th>summary<\/th><th>joined by<\/th><th>actions<\/th><\/tr>/);
  assert.match(html, /<th>title<\/th><th>bug<\/th><th>fix<\/th><\/tr>/, "the group table has a bug column, then the fix (#1200)");
  const joined = [...html.matchAll(/<td class="frictionJoined"[^>]*>([^<]*)<\/td>/g)].map((m) => m[1]);
  assert.deepEqual(joined, ["@someone", "fallback", "no jev", "exact"], "g31's members newest first, each with how it joined");
  assert.equal((html.match(/<button class="pvButton frictionPullOut"[^>]*>Pull out<\/button>/g) || []).length, 4, "a Pull out on every member");
  assert.match(html, /<a href="frakworks:\/\/ticket\/acme\/gadgets\/9" title="acme\/gadgets#9">#9<\/a>/, "the member rows are today's note rows");
  assert.match(html, /title="jev call c-f1"/, "the joined-by cell names the call");
  const g12 = render(FrictionGroupsTable, { groups, notes: allNotes, open: { g12: true }, onPullOut: () => {} });
  assert.deepEqual([...g12.matchAll(/<td class="frictionJoined"[^>]*>([^<]*)<\/td>/g)].map((m) => m[1]), ["jev 0.91", "founder"]);
  const busy = render(FrictionGroupsTable, { groups, notes: allNotes, open: { g12: true }, onPullOut: () => {}, busy: "pull-out" });
  assert.match(busy, /<button class="pvButton frictionPullOut" disabled=""/, "a correction on its way disables the buttons");
  // The ungrouped note opens to itself alone, marked grouping…, with no Pull out.
  const u = render(FrictionGroupsTable, { groups, notes: allNotes, open: { nu1: true }, onPullOut: () => {} });
  assert.match(u, /<td class="frictionJoined"[^>]*>grouping…<\/td>/);
  assert.doesNotMatch(u, /frictionPullOut/);
  // The plain notes table (no `grouped`) keeps its seven columns.
  assert.doesNotMatch(render(FrictionNotesTable, { notes, open: {} }), /joined by|frictionJoined|actions/);
  const detail = render(FrictionNotesTable, { notes: groups[1].members, open: { b2: true }, grouped: true, onPullOut: () => {} });
  assert.match(detail, /<td colspan="9">/i, "the detail row spans the two extra columns");
  assert.match(detail, /jev call<\/span> c-b2/);
});

test("Merge into… is a select over the other groups' titles, then a confirm; a lone group has nothing to merge into", () => {
  const others = [groups[1]];
  const closed = render(FrictionMergeView, { group: groups[0], others, into: null, confirming: false });
  assert.match(closed, /<div class="frictionMerge"><label>Merge into…<select>/);
  assert.match(closed, /<option value="" selected="">choose a group<\/option><option value="12">The &lt;engine&gt; answered 500 on a blank since\.<\/option>/);
  assert.doesNotMatch(closed, /value="31"/, "never itself");
  assert.match(closed, /<button class="pvButton frictionMergeAsk" disabled="">Merge<\/button>/, "nothing chosen: Merge waits");
  const chosen = render(FrictionMergeView, { group: groups[0], others, into: 12, confirming: false });
  assert.match(chosen, /<option value="12" selected="">/);
  assert.match(chosen, /<button class="pvButton frictionMergeAsk">Merge<\/button>/);
  assert.doesNotMatch(chosen, /frictionConfirm/);
  const confirm = render(FrictionMergeView, { group: groups[0], others, into: 12, confirming: true });
  assert.match(confirm, /Merge the 4 notes of “Gradle is OOM-killed in the sandbox\.” into “The &lt;engine&gt; answered 500 on a blank since\.”\?/);
  assert.match(confirm, /<button class="pvButton is-go frictionMergeConfirm">Confirm<\/button><button class="pvButton frictionMergeCancel">Cancel<\/button>/);
  assert.doesNotMatch(confirm, /frictionMergeAsk/, "Merge gives way to the confirm");
  assert.match(render(FrictionMergeView, { group: groups[0], others: [], into: null }), /no other group in the window/);
  // The open group row carries it when the page can merge, with the other groups only.
  const html = render(FrictionGroupsTable, { groups, notes: allNotes, open: { g12: true }, onMerge: () => {} });
  assert.match(html, /<label>Merge into…<select>.*<option value="31">Gradle is OOM-killed in the sandbox\.<\/option><\/select>/);
  assert.doesNotMatch(html, /<option value="12">/);
  assert.doesNotMatch(render(FrictionGroupsTable, { groups, notes: allNotes, open: { g12: true } }), /frictionMerge/, "read-only without onMerge");
});

test("the page shows a failed correction's message from the engine, and the groups' loading line", () => {
  const html = render(FrictionNotesView, { result: { summary }, notes: { notes: allNotes }, groups: { groups },
    actionError: "engine answered 403 for PATCH /friction/notes/b2: a Smith sandbox (run x) may not regroup a friction note" });
  assert.match(html, /<div class="err frictionActionError">engine answered 403 for PATCH \/friction\/notes\/b2: a Smith sandbox \(run x\) may not regroup a friction note<\/div>/);
  assert.match(html, /<div class="jevLabel">Groups<\/div>/);
  assert.match(html, /frictionGroups/);
  const loading = render(FrictionNotesView, { result: { summary }, notes: { notes: allNotes }, groups: { loading: true } });
  assert.match(loading, /asking the engine for the notes…/);
  assert.doesNotMatch(loading, /<table/);
});

test("the actions are wired: the page calls fleet.frictionPullOut/frictionMerge, main supplies the login, engine-data sends them (#1098)", () => {
  const page = src("renderer/friction.jsx");
  assert.match(page, /window\.fleet\.frictionGroups\(filter\)/);
  assert.match(page, /window\.fleet\.frictionPullOut\(noteId\)/);
  assert.match(page, /window\.fleet\.frictionMerge\(groupId, into\)/);
  assert.match(page, /if \(r && r\.error\) setActionError\(r\.error\); else await load\(\);/, "reload on success, the engine's message on failure");
  assert.match(src("preload.js"), /frictionGroups: \(filter\) => ipcRenderer\.invoke\("engine:friction-groups"/);
  assert.match(src("preload.js"), /frictionPullOut: \(noteId\) => ipcRenderer\.invoke\("engine:friction-pull-out", String\(noteId\)\)/);
  assert.match(src("preload.js"), /frictionMerge: \(groupId, into\) => ipcRenderer\.invoke\("engine:friction-merge", Number\(groupId\), Number\(into\)\)/);
  const main = src("main.js");
  assert.match(main, /ipcMain\.handle\("engine:friction-groups", async \(_e, filter\) => \{\n  const r = await fetchFrictionGroups\(/);
  assert.match(main, /ipcMain\.handle\("engine:friction-pull-out", async \(_e, noteId\) => \{\n  const by = await signedInLogin\(\);\n  const r = await frictionPullOut\(String\(noteId\), by\);/);
  assert.match(main, /ipcMain\.handle\("engine:friction-merge", async \(_e, groupId, into\) => \{\n  const by = await signedInLogin\(\);\n  const r = await frictionMerge\(groupId, into, by\);/);
});

test("fetchFrictionGroups calls GET /friction/groups with the notes' filters and resolves to the groups", async () => {
  const f = fake(200, groups);
  const r = await fetchFrictionGroups({ since: "2026-09-28T12:00:00Z", kind: "bug", source: "retro", limit: 9000 }, f.opts);
  assert.deepEqual(f.calls, ["http://engine.test:8090/friction/groups?since=2026-09-28T12%3A00%3A00Z&kind=bug&source=retro&limit=500"]);
  assert.deepEqual(r, { groups, error: null });
  const g = fake(200, groups);
  assert.match((await fetchFrictionGroups({ kind: "nope" }, g.opts)).error, /not a friction kind/);
  assert.deepEqual(g.calls, []);
  assert.match((await fetchFrictionGroups({}, fake(200, { groups }).opts)).error, /is no list/);
  assert.equal((await fetchFrictionGroups({}, fake(404, "").opts)).error, "this engine has no /friction/groups (older than #1095)");
});

test("frictionPullOut PATCHes the note with group null; frictionMerge POSTs into; neither sends by, and neither rejects", async () => {
  const sent = [];
  const ok = { env, fetch: async (url, init) => { sent.push([init.method, url, init.body]); return { ok: true, status: 200, text: async () => "{}" }; } };
  assert.deepEqual(await frictionPullOut("b2", "someone", ok), { ok: true, status: 200 });
  assert.deepEqual(await frictionMerge(31, 12, "someone", ok), { ok: true, status: 200 });
  assert.deepEqual(sent, [
    ["PATCH", "http://engine.test:8090/friction/notes/b2", JSON.stringify({ group: null })],
    ["POST", "http://engine.test:8090/friction/groups/31/merge", JSON.stringify({ into: 12 })],
  ]);
  const never = { env, fetch: async () => { throw new Error("must not be called"); } };
  assert.match((await frictionPullOut("b2", null, never)).error, /no GitHub login to pull the note out as/);
  assert.match((await frictionMerge(31, 12, "", never)).error, /no GitHub login to merge the groups as/);
  assert.match((await frictionPullOut("../x", "someone", never)).error, /not a friction note id/);
  assert.match((await frictionMerge("31", 12, "someone", never)).error, /not a friction group id: "31"/);
  assert.match((await frictionMerge(31, 0, "someone", never)).error, /not a friction group id: 0/);
  const refused = { env, fetch: async () => ({ ok: false, status: 409, text: async () => JSON.stringify({ message: "g31 cannot be merged into itself" }) }) };
  assert.equal((await frictionMerge(31, 31, "someone", refused)).error, "engine answered 409 for POST /friction/groups/31/merge: g31 cannot be merged into itself");
  const down = { env, fetch: async () => { throw new Error("ECONNREFUSED"); } };
  assert.equal((await frictionPullOut("b2", "someone", down)).error, "engine unreachable at http://engine.test:8090/friction/notes/b2: ECONNREFUSED");
});

test("a marked member shows two things 0.86 and, with onNotTwoThings, a Not two things; a group's bug is a ticket link (#1112)", () => {
  assert.equal(twoThingsLabel(groups[0].members[2]), "two things 0.86");
  assert.equal(twoThingsLabel(groups[0].members[0]), null);
  assert.deepEqual(bugLink(groups[0]), { repo: "acme/gadgets", issue: 77, state: "open", href: "frakworks://ticket/acme/gadgets/77", label: "#77" });
  assert.equal(bugLink(groups[1]), null);
  assert.equal(bugLink({ bug: { repo: "acme/gadgets", issue: 77, state: "closed" } }).label, "#77 (closed)");
  assert.deepEqual(bugLink({ bug: { repo: "acme/gadgets", issue: null, state: "filing" } }), { repo: "acme/gadgets", issue: null, state: "filing", href: null, label: "filing…" });
  assert.equal(groupRow(groups[0]).bug.href, "frakworks://ticket/acme/gadgets/77");
  const html = render(FrictionGroupsTable, { groups, notes: allNotes, open: { g31: true }, onPullOut: () => {}, onNotTwoThings: () => {} });
  assert.match(html, /<td class="frictionBug"><a href="frakworks:\/\/ticket\/acme\/gadgets\/77"[^>]*>#77<\/a><\/td>/, "g31's bug");
  assert.equal((html.match(/<td class="frictionBug">—<\/td>/g) || []).length, 2, "g12 and the ungrouped note have none");
  assert.match(html, /<span class="frictionTwoThings" title="[^"]*call c-b1-two[^"]*">two things 0\.86<\/span>/);
  assert.equal((html.match(/frictionNotTwoThings/g) || []).length, 1, "Not two things on the marked member alone");
  assert.equal((html.match(/<button class="pvButton frictionPullOut"[^>]*>Pull out<\/button>/g) || []).length, 4, "Pull out stays on every member");
  const without = render(FrictionGroupsTable, { groups, notes: allNotes, open: { g31: true }, onPullOut: () => {} });
  assert.doesNotMatch(without, /frictionNotTwoThings/, "no handler, no button");
  assert.match(render(FrictionGroupsTable, { groups, notes: allNotes, open: { g31: true }, onPullOut: () => {}, onNotTwoThings: () => {}, busy: "not-two-things" }),
    /<button class="pvButton frictionNotTwoThings" disabled=""/);
  const u = render(FrictionGroupsTable, { groups, notes: allNotes, open: { nu1: true } });
  assert.match(u, /<td class="frictionBug">—<\/td>/, "an ungrouped row fills the column");
  const detail = render(FrictionGroupsTable, { groups, notes: allNotes, open: { g31: true } });
  assert.match(detail, /<td colspan="9">/i, "the detail row spans the bug and fix columns");
});

test("Not two things is wired: the page calls fleet.frictionNotTwoThings, main supplies the login, engine-data PATCHes twoThings null (#1112)", async () => {
  assert.match(src("renderer/friction.jsx"), /window\.fleet\.frictionNotTwoThings\(noteId\)/);
  assert.match(src("preload.js"), /frictionNotTwoThings: \(noteId\) => ipcRenderer\.invoke\("engine:friction-not-two-things", String\(noteId\)\)/);
  assert.match(src("main.js"), /ipcMain\.handle\("engine:friction-not-two-things", async \(_e, noteId\) => \{\n  const by = await signedInLogin\(\);\n  const r = await frictionNotTwoThings\(String\(noteId\), by\);/);
  const calls = [];
  const env = { engineUrl: "http://engine.test:8090" };
  const fetch = async (url, init) => { calls.push({ url, init }); return { ok: true, status: 200, text: async () => "{}" }; };
  assert.deepEqual(await frictionNotTwoThings("b1", "someone", { env, fetch }), { ok: true, status: 200 });
  assert.deepEqual([calls[0].init.method, calls[0].url, JSON.parse(calls[0].init.body)], ["PATCH", "http://engine.test:8090/friction/notes/b1", { twoThings: null }]);
  assert.match((await frictionNotTwoThings("b1", null, { env, fetch })).error, /no GitHub login to clear the two-things mark as/);
  assert.match((await frictionNotTwoThings("../x", "someone", { env, fetch })).error, /not a friction note id/);
  const refused = async () => ({ ok: false, status: 409, text: async () => JSON.stringify({ message: "friction note b1 is not marked as two things" }) });
  assert.match((await frictionNotTwoThings("b1", "someone", { env, fetch: refused })).error, /409 .*not marked as two things/);
});

// ---- fixed by (#1200) -------------------------------------------------------------

const fixed = { ...groups[1], id: 40, state: "fixed", repos: ["acme/widgets"], fix: { repo: "acme/widgets", issue: 1205, state: "fixed", recurredAt: null } };
const recurred = { ...groups[0], id: 41, state: "recurred", fix: { repo: "acme/fixes", issue: 1210, state: "fixed", recurredAt: "2026-10-04T12:00:00Z" } };

test("a group's fix is a ticket link with its state, #n in the group's only repo and owner/name#n otherwise (#1200)", () => {
  assert.deepEqual(fixLink(fixed), { repo: "acme/widgets", issue: 1205, state: "fixed", recurredAt: null,
    href: "frakworks://ticket/acme/widgets/1205", label: "#1205" });
  assert.equal(fixLink(recurred).label, "acme/fixes#1210");
  assert.equal(fixLink(recurred).state, "recurred");
  assert.equal(fixLink(groups[0]), null);
  assert.equal(groupRow(fixed).state, "fixed");
  assert.deepEqual(parseFix("#12", "acme/widgets"), { fix: { repo: "acme/widgets", issue: 12 } });
  assert.deepEqual(parseFix("acme/fixes#7", null), { fix: { repo: "acme/fixes", issue: 7 } });
  assert.match(parseFix("#7", null).error, /owner\/name#n/);
  assert.match(parseFix("seven", "acme/widgets").error, /not a ticket/);
});

test("a group pending on an intake shows Fix pending with the intake's short id, no ticket link", () => {
  const pending = { ...groups[0], id: 42, state: "fix-pending", fix: { intake: "c7945d8c-c1b0-11f1-b108-fe8d58e8bfcd", repo: "acme/fixes", issue: null, state: "fix-pending" } };
  assert.deepEqual(fixLink(pending), { repo: "acme/fixes", issue: null, intake: "c7945d8c-c1b0-11f1-b108-fe8d58e8bfcd", state: "fix-pending",
    recurredAt: null, href: null, label: "Fix pending: intake c7945d8c" });
  assert.equal(groupRow(pending).state, "fix-pending");
});

test("fixed groups are hidden until Show fixed (n); a recurred group always shows, highlighted with its time (#1200)", () => {
  const all = [...groups, fixed, recurred];
  assert.equal(fixedCount(all), 1);
  assert.deepEqual(visibleGroups(all, false).map((g) => g.id), [...groups.map((g) => g.id), 41]);
  assert.equal(visibleGroups(all, true).length, 4);
  const view = (showFixed) => render(FrictionNotesView, { result: { summary }, notes: { notes: allNotes }, groups: { groups: all }, open: {}, showFixed });
  assert.match(view(false), /Show fixed \(1\)/);
  assert.doesNotMatch(view(false), /#1205/);
  assert.match(view(true), /href="frakworks:\/\/ticket\/acme\/widgets\/1205"[^>]*>#1205 <span class="muted">\(fixed\)<\/span>/);
  assert.match(view(false), /<tr class="jevRow frictionGroupRow severity-high frictionRecurred" title="recurred 2026-10-04 12:00 UTC: a note arrived after the fix merged">/);
  assert.match(view(false), /<th>bug<\/th><th>fix<\/th>/);
  assert.doesNotMatch(render(FrictionNotesView, { result: { summary }, notes: { notes: allNotes }, groups: { groups }, open: {} }), /Show fixed/);
  const open = render(FrictionGroupsTable, { groups: [fixed], notes: [], open: { g40: true }, onFix: () => {} });
  assert.match(open, /Fixed by <input class="frictionFixInput"[^>]*placeholder="#n"/);
  assert.match(open, /frictionFixClear/);
});

test("Fixed by is wired: the page calls fleet.frictionFix, main supplies the login, engine-data PATCHes the group (#1200)", async () => {
  assert.match(src("renderer/friction.jsx"), /window\.fleet\.frictionFix\(groupId, fix\)/);
  assert.match(src("preload.js"), /frictionFix: \(groupId, fix\) => ipcRenderer\.invoke\("engine:friction-fix", Number\(groupId\), fix\)/);
  assert.match(src("main.js"), /ipcMain\.handle\("engine:friction-fix", async \(_e, groupId, fix\) => \{\n  const by = await signedInLogin\(\);\n  const r = await frictionFix\(groupId, fix, by\);/);
  assert.match((await frictionFix(4, { repo: "nope", issue: 1 }, "someone")).error, /not a fixing ticket/);
  assert.match((await frictionFix("x", null, "someone")).error, /not a friction group id/);
});

test("the tables' dates are short, the full UTC time in the tooltip (#100126)", () => {
  assert.equal(fmtShort("2026-10-17T19:44:31Z"), "10-17 19:44");
  assert.equal(fmtShort(null), DASH);
  assert.equal(fmtShort("nope"), DASH);
  const r = groupRow({ id: 1, count: 1, total: 1, newest: "2026-10-17T19:44:00Z", oldest: "2026-10-16T08:05:00Z", repos: ["codefrak-oss/frakworks"] });
  assert.equal(r.newest, "10-17 19:44");
  assert.equal(r.newestFull, "2026-10-17 19:44 UTC");
  assert.equal(r.oldest, "10-16 08:05");
  assert.equal(r.repos, "frakworks", "the repo without its owner");
  assert.equal(r.reposFull, "codefrak-oss/frakworks");
  const n = noteRow({ id: "x", noted_at: "2026-10-17T19:44:00Z", repo: "codefrak-oss/frakworks" });
  assert.equal(n.at, "10-17 19:44");
  assert.equal(n.atFull, "2026-10-17 19:44 UTC");
  const html = render(FrictionGroupsTable, { groups: [{ id: 1, count: 1, total: 1, newest: "2026-10-17T19:44:00Z", oldest: "2026-10-17T19:44:00Z", repos: ["codefrak-oss/frakworks"], title: "t" }], notes: [] });
  assert.match(html, /<td class="jevWhen" title="2026-10-17 19:44 UTC">10-17 19:44<\/td>/);
  assert.match(html, /<td class="jevText" title="codefrak-oss\/frakworks">frakworks<\/td>/);
});
