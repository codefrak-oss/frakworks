"use strict";
// The explorer's Proposals branch and the proposal view (#660): the merge in
// src/proposal-list.js, its data path (src/engine-data.js, main, preload), and
// the drawing in src/renderer/proposals.jsx, on test/fixture-proposals.json's
// four doc cases (awaiting a decision, accepted with a build ticket, shelved,
// a doc from before pitches) plus a flow still drafting.
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { toProposalRows, groupByRepo, parseProposalId, proposalId, proposalIdOfTicket, stageLabel, findProposal } = require("../src/proposal-list");
const { fetchProposals, fetchProposalPage } = require("../src/engine-data");
const { fetchTicketTree } = require("../src/ticket-tree");
const { ProposalsBranch, ProposalView, ProposalFacts } = require("../dist/proposals.cjs");
const { Explorer } = require("../dist/explorer.cjs");

const FIX = JSON.parse(fs.readFileSync(path.join(__dirname, "fixture-proposals.json"), "utf8"));
const src = (f) => fs.readFileSync(path.join(__dirname, "../src", f), "utf8");
const draw = (el) => renderToStaticMarkup(el);
const rows = () => toProposalRows({ docs: FIX.docs, board: FIX.board, needs: FIX.needs });

// ---- the merge -------------------------------------------------------------------

test("a row per proposal: a doc and the ticket it names are one, a decision Need wins, then the flow's step, then the doc", () => {
  const r = rows();
  assert.deepEqual(r.map((x) => [x.id, x.status, x.statusSource]), [
    ["o/r#12", "awaiting decision", "decision"],
    ["o/r#14", "drafting", "flow"],
    ["o/r#8", "accepted", "doc"],
    ["o/r:later", "shelved", "doc"],
    ["o/other:old-doc", "unknown", "doc"],
  ]);
  const waiting = r[0];
  assert.equal(waiting.title, "Pitch me (ticket)", "the ticket's title over the doc's");
  assert.equal(waiting.slug, "pitch-me");
  assert.equal(waiting.prUrl, "https://github.com/o/r/pull/13");
  assert.deepEqual(waiting.decision, { actionId: "p-12", ask: "The proposal is ready for your decision" });
  assert.equal(waiting.onBoard, true);
  const built = r[2];
  assert.equal(built.buildTicket, 9);
  assert.equal(built.onBoard, false);
  assert.equal(built.url, "https://github.com/o/r/issues/8");
  assert.deepEqual(built.decisionLog, FIX.docs[1].decisionLog);
  assert.equal(r[3].number, null);
  assert.equal(r[3].url, null);
  assert.equal(r.find((x) => x.number === 15), undefined, "a work ticket, and its question, are no proposal");
});

test("a flow that ended leaves the doc's status; a flow at a step the branch does not know is `in flow`", () => {
  const board = [{ repo: "o/r", number: 12, title: "t", kind: "proposal", flow: { flow: "proposal", stage: "call-draft", ended: true } }];
  assert.equal(toProposalRows({ docs: FIX.docs.slice(0, 1), board }).find((x) => x.number === 12).status, "proposed");
  assert.equal(stageLabel({ flow: "proposal", stage: "task-new-thing", ended: false }), "in flow");
  assert.equal(stageLabel({ flow: "work", stage: "call-draft" }), null);
  const bare = toProposalRows({ board: [{ repo: "o/r", number: 3, title: "t", kind: "proposal", flow: null }] });
  assert.deepEqual(bare.map((x) => x.status), ["not started"]);
});

test("ids round-trip, and a ticket finds its proposal row", () => {
  assert.deepEqual(parseProposalId(proposalId("o/r", 12)), { repo: "o/r", number: 12, slug: null });
  assert.deepEqual(parseProposalId(proposalId("o/r", null, "later")), { repo: "o/r", number: null, slug: "later" });
  assert.equal(parseProposalId("o/r:../x"), null);
  assert.equal(parseProposalId(""), null);
  assert.equal(proposalIdOfTicket(rows(), "o/r", 8), "o/r#8");
  assert.equal(proposalIdOfTicket(rows(), "o/r", 15), null);
  assert.equal(findProposal(rows(), "o/r:later").title, "Later");
});

test("groupByRepo keeps the target repos' order, then any other repo a row names", () => {
  assert.deepEqual(groupByRepo(rows(), ["o/other", "o/r"]).map((g) => [g.repo, g.rows.length]), [["o/other", 1], ["o/r", 4]]);
  assert.deepEqual(groupByRepo(rows(), []).map((g) => g.repo), ["o/r", "o/other"]);
});

// ---- the data path -----------------------------------------------------------------

const stubFetch = (status, body, calls = []) => async (url) => {
  calls.push(url);
  return { ok: status >= 200 && status < 300, status, text: async () => body };
};

test("fetchProposals reads GET /proposals, with ?repo= for one repo, and every failure is an error", async () => {
  const calls = [];
  const body = JSON.stringify({ docs: [{ ...FIX.docs[0], decisionLog: null }, { slug: "no-repo" }], errors: [{ repo: "o/x", error: "GitHub: 502" }] });
  const r = await fetchProposals({ env: {}, fetch: stubFetch(200, body, calls) });
  assert.equal(r.error, null);
  assert.deepEqual(r.docs.map((d) => [d.slug, d.decisionLog]), [["pitch-me", []]]);
  assert.deepEqual(r.failures, [{ repo: "o/x", error: "GitHub: 502" }]);
  assert.match(calls[0], /\/proposals$/);
  await fetchProposals({ repo: "o/r", env: {}, fetch: stubFetch(200, '{"docs":[]}', calls) });
  assert.match(calls[1], /\/proposals\?repo=o%2Fr$/);
  assert.match((await fetchProposals({ env: {}, fetch: stubFetch(502, '{"error":"GitHub: down"}') })).error, /502/);
  assert.match((await fetchProposals({ env: {}, fetch: stubFetch(200, '{"nope":1}') })).error, /no `docs` list/);
  assert.match((await fetchProposals({ repo: "not a repo", env: {}, fetch: stubFetch(200, "{}") })).error, /./);
});

test("fetchProposalPage reads GET /proposals/<owner>/<repo>/<slug>/page; a bad slug never reaches the engine", async () => {
  const calls = [];
  const page = FIX.pages["o/other:old-doc"].pitch;
  const r = await fetchProposalPage("o", "other", "old-doc", { env: {}, fetch: stubFetch(200, JSON.stringify(page), calls) });
  assert.deepEqual(r, { pitch: page, error: null });
  assert.match(calls[0], /\/proposals\/o\/other\/old-doc\/page$/);
  assert.match((await fetchProposalPage("o", "r", "x", { env: {}, fetch: stubFetch(404, '{"error":"no proposal"}') })).error, /no proposal doc x in o\/r/);
  assert.match((await fetchProposalPage("o", "r", "x", { env: {}, fetch: stubFetch(200, '{"kind":"pdf","content":"x"}') })).error, /has no `kind`/);
  const none = [];
  for (const [o, n, s] of [["o", "r", "../etc"], ["o", "r", ""], ["o/x", "r", "x"], ["o", "r", 5]]) {
    assert.ok((await fetchProposalPage(o, n, s, { env: {}, fetch: stubFetch(200, "{}", none) })).error);
  }
  assert.deepEqual(none, [], "nothing asked of the engine");
  assert.match(src("preload.js"), /proposals: \(\) => ipcRenderer\.invoke\("engine:proposals"\)/);
  assert.match(src("preload.js"), /proposalPage: \(owner, repoName, slug\) => ipcRenderer\.invoke\("engine:proposal-page"/);
  assert.match(src("main.js"), /ipcMain\.handle\("engine:proposals"/);
  assert.match(src("main.js"), /ipcMain\.handle\("engine:proposal-page"/);
});

test("the ticket tree hands the branch the board's proposal tickets, so the branch reads no board of its own", async () => {
  const fetchIssues = async () => ({ issues: FIX.board.concat([{ ...FIX.board[1], repo: "o/other", number: 3 }]) });
  const one = await fetchTicketTree({ repo: "o/r", fetchIssues });
  assert.deepEqual(one.proposals.map((i) => i.number), [12, 14]);
  const all = await fetchTicketTree({ repo: null, fetchIssues });
  assert.deepEqual(all.proposals.map((i) => `${i.repo}#${i.number}`), ["o/r#12", "o/r#14", "o/other#3"]);
});

// ---- the branch --------------------------------------------------------------------

const noop = () => {};
const branch = (props) => draw(React.createElement(ProposalsBranch, { open: {}, toggle: noop, sel: null, onSelect: noop, ...props }));

test("the branch is closed until opened, and open when the page restored is a proposal", () => {
  const proposals = { rows: rows(), error: null, failures: [] };
  const closed = branch({ proposals });
  assert.match(closed, /Proposals/);
  assert.match(closed, /class="exCount">5</);
  assert.doesNotMatch(closed, /Pitch me/);
  const restored = branch({ proposals, sel: { kind: "proposal", id: "o/r#12" } });
  assert.match(restored, /class="exRow on"[^>]*title="Pitch me \(ticket\) \(awaiting decision\)"/);
  assert.match(restored, /exStatus st-awaiting-decision">awaiting decision</);
  assert.match(restored, /exStatus st-shelved">shelved</);
});

test("in All repos a sub-branch per repo; loading, an error and a failing repo each draw a row", () => {
  const proposals = { rows: rows(), error: null, failures: [{ repo: "o/x", error: "GitHub: 502" }] };
  const all = branch({ proposals, allRepos: true, repos: ["o/other", "o/r"], open: { proposals: true } });
  assert.ok(all.indexOf(">o/other<") > 0 && all.indexOf(">o/other<") < all.indexOf(">o/r<"));
  assert.match(all, /o\/x: GitHub: 502/);
  assert.match(branch({ proposals: null, open: { proposals: true } }), /loading…/);
  assert.match(branch({ proposals: { rows: [], error: "engine unreachable", failures: [] }, open: { proposals: true } }), /exErr[^>]*>engine unreachable/);
  assert.match(branch({ proposals: { rows: [], error: null, failures: [] }, open: { proposals: true } }), />none</);
});

test("the explorer draws Proposals after Tickets and before Saved Chats", () => {
  const html = draw(React.createElement(Explorer, { sel: null, onSelect: noop, tree: { repo: "o/r", groups: [] },
    proposals: { rows: rows(), error: null, failures: [] }, savedChats: { chats: [] } }));
  const at = (s) => html.indexOf(`<span class="exLabel">${s}</span>`);
  assert.ok(at("Tickets") > 0 && at("Tickets") < at("Proposals") && at("Proposals") < at("Saved Chats"));
});

// ---- the view ----------------------------------------------------------------------

// The pitch.test.js harness: the view called as a function with useState stood
// in and each effect run at once when its deps change, so its load and handlers can be driven.
const mount = (props) => {
  const slots = [];
  const deps = [];
  const real = { useState: React.useState, useEffect: React.useEffect };
  const draw = (over = {}) => {
    let i = 0;
    let j = 0;
    React.useState = (init) => {
      const k = i++;
      if (!(k in slots)) slots[k] = typeof init === "function" ? init() : init;
      return [slots[k], (v) => { slots[k] = typeof v === "function" ? v(slots[k]) : v; }];
    };
    React.useEffect = (f, d) => {
      const k = j++;
      if (deps[k] && d && d.every((x, n) => x === deps[k][n])) return;
      deps[k] = d;
      f();
    };
    try { return ProposalView({ ...props, ...over }); } finally { Object.assign(React, real); }
  };
  return { draw };
};
const walk = (el, out = []) => {
  if (!el || typeof el !== "object") return out;
  if (Array.isArray(el)) { for (const c of el) walk(c, out); return out; }
  out.push(el);
  if (el.props) walk(el.props.children, out);
  return out;
};
const tick = () => new Promise((r) => setImmediate(r));

test("the facts: status and its source, the ticket, the PR, the build ticket's link and the decision log", () => {
  const selected = [];
  const html = draw(React.createElement(ProposalFacts, { row: findProposal(rows(), "o/r#8"), onSelect: (x) => selected.push(x) }));
  assert.match(html, /st-accepted">accepted</);
  assert.match(html, /the doc&#x27;s Status: on the default branch/);
  assert.match(html, /class="proposalBuild">#9</);
  assert.match(html, /<ol><li>2026-09-20 proposed \(#8\)<\/li><li>2026-09-21 accepted \(#8\); build filed as #9<\/li><\/ol>/);
  const facts = ProposalFacts({ row: findProposal(rows(), "o/r#8"), onSelect: (x) => selected.push(x) });
  walk(facts).find((e) => e.props && e.props.className === "proposalBuild").props.onClick({ preventDefault: noop });
  assert.deepEqual(selected, [{ kind: "ticket", id: "o/r#9" }]);
});

test("a waiting decision mounts the proposal ticket's HITLP task inline (#100070), found by its ticket", async () => {
  const fleet = { ticketPitch: async () => FIX.pages["o/r:later"] };
  const v = mount({ row: findProposal(rows(), "o/r#12"), fleet });
  v.draw();
  await tick();
  const controls = walk(v.draw()).find((e) => e.props && e.props.enabled !== undefined && "number" in e.props);
  assert.equal(controls.props.enabled, true);
  assert.deepEqual([controls.props.repo, controls.props.number], ["o/r", 12]);
  assert.match(draw(controls), /reading the HITLP task/);
  assert.doesNotMatch(draw(controls), /<textarea|decideLink/);
});

test("with no decision waiting no decision control is drawn and no ticket is read", async () => {
  const reads = [];
  const fleet = { ticketPitch: async () => ({ error: "x" }), proposalPage: async () => ({ error: "y" }),
    ticketDetail: async (...a) => { reads.push(a); return { ticket: { comments: [] } }; } };
  const v = mount({ row: findProposal(rows(), "o/r#8"), fleet });
  const controls = walk(v.draw()).find((e) => e.props && e.props.enabled !== undefined && "number" in e.props);
  assert.equal(controls.props.enabled, false);
  const html = draw(controls);
  assert.doesNotMatch(html, /reading the HITLP task|<textarea|No decision is waiting/);
  assert.match(html, /pitchChat/);
  await tick();
  assert.deepEqual(reads, []);
});

test("a doc with no ticket reads its page through fleet.proposalPage; a pre-pitch doc is its markdown", async () => {
  const calls = [];
  const fleet = { ticketPitch: async () => { throw new Error("no ticket to read"); },
    proposalPage: async (o, n, s) => { calls.push([o, n, s]); return FIX.pages[`${o}/${n}:${s}`]; } };
  const v = mount({ row: findProposal(rows(), "o/other:old-doc"), fleet });
  v.draw();
  await tick();
  assert.deepEqual(calls, [["o", "other", "old-doc"]]);
  const body = walk(v.draw()).find((e) => e.props && e.props.result);
  assert.equal(body.props.result.pitch.kind, "markdown");
  const head = walk(v.draw()).filter((e) => e.type === "h2")[1];
  assert.match(draw(head), /docs\/proposals\/old-doc\.md, no pitch page/);
});

test("an id the list does not have says so, or loading while the first list is out", () => {
  assert.match(draw(React.createElement(ProposalView, { row: null, loading: true })), /loading…/);
  assert.match(draw(React.createElement(ProposalView, { row: null, loading: false })), /not in the list any more/);
});

test("the app restores a proposal page, keeps it in the explorer's sel, and routes a decision Need to it", () => {
  const index = src("renderer/index.jsx");
  assert.match(index, /lastSel\.kind === "proposal" && lastSel\.id \? "proposal"/);
  assert.match(index, /view === "proposal" \? \{ kind: "proposal", id: proposalSel \}/);
  assert.match(index, /if \(row && row\.decision\) \{ onSelect\(\{ kind: "proposal", id: row\.id \}\); return; \}/);
});

// ---- the way in from the issue detail and the ticket detail -------------------------

const { TicketDetail } = require("../dist/index.cjs");
const { ProposalLink } = require("../dist/proposals.cjs");

test("a proposal reached from the Issues view shows its facts and a link to the proposal view", () => {
  const selected = [];
  const row = findProposal(rows(), proposalId("o/r", 8));
  const issue = { repo: "o/r", number: 8, title: "Built thing", url: "https://github.com/o/r/issues/8",
    labels: [], author: "u", flow: null };
  const props = { repo: "o/r", number: 8, issue, flowResult: null, now: Date.now(), proposal: row, onSelect: (x) => selected.push(x) };
  const html = draw(React.createElement(TicketDetail, props));
  assert.match(html, /class="proposalOpen">Proposal view</);
  assert.match(html, /class="proposalFacts"/);
  assert.match(html, /class="proposalBuild">#9</);
  const link = walk(ProposalLink({ row, onSelect: (x) => selected.push(x) })).find((e) => e.props && e.props.className === "proposalOpen");
  link.props.onClick({ preventDefault: noop });
  assert.deepEqual(selected, [{ kind: "proposal", id: "o/r#8" }]);
  const plain = draw(React.createElement(TicketDetail, { ...props, proposal: null }));
  assert.doesNotMatch(plain, /proposalOpen|proposalFacts/);
});

test("a proposal reached from the Tickets tree links to the proposal view as from the Issues view (#802)", () => {
  const row = findProposal(rows(), proposalId("o/r", 12));
  const doc = { repo: "o/r", issue: { title: "Pitch me", url: "https://github.com/o/r/issues/12", state: "OPEN", labels: [], body: "", comments: [] }, prs: [] };
  const html = draw(React.createElement(TicketDetail, { number: 12, repo: "o/r", doc, now: 0, proposal: row, onSelect: noop }));
  assert.match(html, /class="proposalOpen">Proposal view<\/a><\/div>/);
  assert.match(html, /class="proposalFacts"/);
  assert.equal(html.split("awaiting decision").length - 1, 1);
  assert.doesNotMatch(draw(React.createElement(TicketDetail, { number: 12, repo: "o/r", doc, now: 0 })), /proposalOpen/);
});

test("the flow view states an awaiting proposal's status once: one chip, then its source text (#791)", () => {
  const row = findProposal(rows(), proposalId("o/r", 12));
  const issue = { repo: "o/r", number: 12, title: "Pitch me", url: "https://github.com/o/r/issues/12",
    labels: [], author: "u", flow: null };
  const html = draw(React.createElement(TicketDetail, { repo: "o/r", number: 12, issue, flowResult: null, now: Date.now(), proposal: row, onSelect: noop }));
  assert.equal(row.status, "awaiting decision");
  assert.equal(html.split("awaiting decision").length - 1, 1);
  assert.match(html, /awaiting decision<\/span> <span class="muted">a decision waits on you/);
  assert.match(html, /class="proposalOpen">Proposal view<\/a><\/div>/);
});

test("the app hands the ticket detail the proposal row for its ticket, however it was opened", () => {
  const index = fs.readFileSync(path.join(__dirname, "../src/renderer/index.jsx"), "utf8");
  assert.match(index, /<TicketScreen[\s\S]*?proposal=\{findProposal\(proposalRows, proposalIdOf\(ref\.repo, ref\.number\)\)\} onSelect=\{onSelect\}/);
  assert.match(index, /detail\(ticket, /);
  assert.match(index, /detail\(issueSel, /);
});

test("the Pitch heading folds the pitch away and back, open by default and on every new proposal, controls kept", async () => {
  let calls = 0;
  const fleet = { ticketPitch: async () => { calls++; return FIX.pages["o/r:later"]; } };
  const v = mount({ row: findProposal(rows(), "o/r#12"), fleet });
  v.draw();
  await tick();
  const fold = () => walk(v.draw()).find((e) => e.type === "h2" && e.props.className === "tdFold");
  assert.match(draw(fold()), /<span class="tdFoldArrow">▾<\/span>Pitch/);
  assert.ok(walk(v.draw()).some((e) => e.props && e.props.className === "pitchMain"));
  fold().props.onClick();
  assert.match(draw(fold()), /tdFoldArrow">▸/);
  const tree = walk(v.draw());
  assert.ok(!tree.some((e) => e.props && e.props.className === "pitchMain"));
  assert.ok(tree.some((e) => e.props && e.props.enabled === true && "number" in e.props), "the decision controls stay");
  fold().props.onClick();
  assert.ok(walk(v.draw()).some((e) => e.props && e.props.className === "pitchMain"));
  assert.equal(calls, 1, "no refetch on expand");
  fold().props.onClick();
  v.draw({ row: findProposal(rows(), "o/r#8") });
  assert.match(draw(fold()), /tdFoldArrow">▾/, "a new proposal opens the fold again");
});
