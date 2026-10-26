// "<-- chat about this" (#733): a button first in the live First mate header
// that starts a new conversation about whatever the middle pane shows. A
// screen with a Chat about this of its own (an issue detail, a proposal's
// Pitch view, a repo file the view drew) gets that button's very `about`;
// every other screen a `{ kind: "screen" }` kick-starter named from App's
// `sel` and VIEWS.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { FirstMate, KickStarterRow, kickStarterOf, withKickStarter, CHAT_SCREEN_TITLE } = require("../dist/first-mate.cjs");
const { FirstMateStrip, chatAbout, screenChatOf, screenLabel, issueAboutOf, tapRepoFile, VIEWS, NO_REPO_OFF } = require("../dist/index.cjs");
const { TicketDetailView } = require("../dist/index.cjs");
const { ProposalView } = require("../dist/proposals.cjs");
const { RepoFileView, loadRepoFile } = require("../dist/repo-files.cjs");

const draw = (el) => renderToStaticMarkup(el);
const walk = (el, out = []) => {
  if (!el || typeof el !== "object") return out;
  if (Array.isArray(el)) { for (const c of el) walk(c, out); return out; }
  out.push(el);
  if (el.props) walk(el.props.children, out);
  return out;
};
const find = (tree, pred) => walk(tree).find((e) => e.props && pred(e));
const hasClass = (cls) => (e) => String(e.props.className || "").split(" ").includes(cls);
// Each dist bundle has its own copy of a component it imports, so it is found by name.
const named = (name) => (e) => e.type && e.type.name === name;
// A component drawn as a function with its hooks stood in: `state` for every useState.
const withHooks = (fn, state) => {
  const real = { useState: React.useState, useEffect: React.useEffect, useRef: React.useRef };
  React.useState = (init) => [state !== undefined ? state : typeof init === "function" ? init() : init, () => {}];
  React.useEffect = () => {};
  React.useRef = (v) => ({ current: v });
  try { return fn(); } finally { Object.assign(React, real); }
};
// Both abouts give the same kick-starter row and the same first send.
const sameKick = (a, b) => {
  assert.deepEqual(kickStarterOf(a), kickStarterOf(b));
  assert.equal(draw(React.createElement(KickStarterRow, { kick: kickStarterOf(a) })),
               draw(React.createElement(KickStarterRow, { kick: kickStarterOf(b) })));
  assert.equal(withKickStarter(kickStarterOf(a), "why?"), withKickStarter(kickStarterOf(b), "why?"));
};

const snap = (over = {}) => ({ history: [], filed: [], busy: false, streaming: "", ...over });

// ---- the button -----------------------------------------------------------------

// Pulls the balanced `<div ...>...</div>` starting at `openTag`'s first
// occurrence in `html`, counting nested `<div`/`</div>` (the SSR markup has
// no whitespace to lean on, so a non-greedy regex would stop at the first
// child's close).
const balancedDiv = (html, openTag) => {
  const start = html.indexOf(openTag);
  assert.ok(start >= 0, `${openTag} found`);
  let depth = 0, i = start;
  const re = /<div\b[^>]*>|<\/div>/g;
  re.lastIndex = start;
  let m;
  while ((m = re.exec(html))) {
    if (m[0] === "</div>") { depth--; if (depth === 0) { i = m.index + m[0].length; break; } }
    else depth++;
  }
  return html.slice(start, i);
};

test("the live header has <-- chat about this on its own row below the heading", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap(), draft: "", onDraft: () => {}, onSend: () => {},
    onChatAboutScreen: () => {} }));
  const header = balancedDiv(html, '<div class="fmHeader">');
  const button = header.match(/<button class="fmChatScreen"([^>]*)>([^<]*)<\/button>/);
  assert.ok(button, "the button is in the header");
  assert.equal(button[2], "&lt;-- chat about this");
  assert.doesNotMatch(button[1], /disabled/);
  assert.match(button[1], new RegExp(`title="${CHAT_SCREEN_TITLE}"`));
  assert.ok(header.indexOf(">First mate<") < header.indexOf("fmChatScreen"), "after the heading text");
  const buttonsRow = balancedDiv(header, '<div class="fmHeaderButtons fmClusterRow">');
  assert.doesNotMatch(buttonsRow, /fmChatScreen/, "not a child of the buttons row (Start new conversation's row)");
  assert.ok(header.indexOf("fmChatScreen") < header.indexOf("fmNewConversation"), "before Start new conversation in document order");
});

test("the History panels' header has no <-- chat about this", () => {
  for (const over of [{ historyOpen: true, historyList: [] }, { viewingConversation: { id: "c", messages: [] } }]) {
    const html = draw(React.createElement(FirstMate, { snapshot: snap(), draft: "", onDraft: () => {}, onSend: () => {},
      onChatAboutScreen: () => {}, ...over }));
    assert.doesNotMatch(html, /fmChatScreen/);
    assert.match(html, /fmNewConversation/);
  }
});

test("a click calls onChatAboutScreen; off, it is disabled and its title says why", () => {
  const calls = [];
  const tree = withHooks(() => FirstMate({ snapshot: snap(), draft: "", onDraft: () => {}, onSend: () => {}, onChatAboutScreen: () => calls.push(1) }));
  const control = find(tree, named("ChatScreenControl"));
  find(control.type(control.props), hasClass("fmChatScreen")).props.onClick();
  assert.equal(calls.length, 1);
  const html = draw(React.createElement(FirstMate, { snapshot: snap(), draft: "", onDraft: () => {}, onSend: () => {},
    onChatAboutScreen: null, chatAboutScreenOff: NO_REPO_OFF }));
  const b = html.match(/<button class="fmChatScreen"([^>]*)>/)[1];
  assert.match(b, /disabled=""/);
  assert.match(b, /title="All repos is selected, so this ticket has no repo: pick one in the switcher to chat about it\."/);
});

// ---- the middle pane's own Chat about this: the very same about ------------------

const TICKET = { number: 7, repo: "o/r", title: "a chore", author: "a", url: "https://github.com/o/r/issues/7",
  kind: "chore", labels: ["chore"], children: [], pendingQuestion: null, flow: null };

test("an issue detail: the same about, kick-starter and first send as the detail's own button", () => {
  const got = [];
  const tree = TicketDetailView({ repo: "o/r", number: 7, issue: TICKET, now: Date.now(), onChatAbout: (a) => got.push(a) });
  find(tree, hasClass("ticketChat")).props.onClick();
  const own = issueAboutOf({ repo: "o/r", number: 7, issue: TICKET, offBoard: null });
  const { about } = screenChatOf({ sel: { kind: "tickets" }, issueSel: { repo: "o/r", number: 7 }, about: own });
  assert.deepEqual(about, { kind: "ticket", repo: "o/r", number: 7, title: "a chore" });
  assert.deepEqual(about, got[0]);
  sameKick(about, got[0]);
});

test("an off-board issue detail: titled from the ticket read, as its own button is", () => {
  const got = [];
  const offBoard = { ticket: { title: "closed one" } };
  const tree = TicketDetailView({ repo: "o/r", number: 9, issue: null, offBoard, needs: [], now: Date.now(), onChatAbout: (a) => got.push(a) });
  find(tree, hasClass("ticketChat")).props.onClick();
  const { about } = screenChatOf({ sel: { kind: "needs" }, issueSel: { repo: "o/r", number: 9 },
    about: issueAboutOf({ repo: "o/r", number: 9, issue: null, offBoard }) });
  assert.deepEqual(about, got[0]);
  sameKick(about, got[0]);
});

test("a proposal's issue detail: the Pitch view's about", () => {
  const issue = { ...TICKET, title: "a pitch", labels: ["proposal"], flow: { flow: "proposal", prUrl: "u" } };
  const got = [];
  const detail = TicketDetailView({ repo: "o/r", number: 7, issue, now: Date.now(), onChatAbout: (a) => got.push(a), needs: [] });
  const pitch = find(detail, named("PitchView"));
  const view = withHooks(() => pitch.type(pitch.props));
  const controls = find(view, named("DecisionControls"));
  withHooks(() => find(controls.type(controls.props), hasClass("pitchChat")).props.onClick());
  assert.deepEqual(got, [{ repo: "o/r", number: 7, title: "a pitch" }]);
  const { about } = screenChatOf({ sel: { kind: "tickets" }, issueSel: { repo: "o/r", number: 7 },
    about: issueAboutOf({ repo: "o/r", number: 7, issue, offBoard: null }) });
  assert.deepEqual(about, got[0]);
  sameKick(about, got[0]);
  assert.equal(kickStarterOf(about).shown, "Discuss proposal #7 - a pitch");
});

test("the Proposals branch's view: the proposal view's own about", () => {
  const row = { id: "o/r#12", repo: "o/r", number: 12, title: "an idea", status: "open", decision: null };
  const got = [];
  const view = withHooks(() => ProposalView({ row, loading: false, onChatAbout: (a) => got.push(a), fleet: {} }), { loading: true });
  const controls = find(view, named("DecisionControls"));
  withHooks(() => find(controls.type(controls.props), hasClass("pitchChat")).props.onClick());
  const { about } = screenChatOf({ sel: { kind: "proposal", id: row.id }, proposalRow: row });
  assert.deepEqual(about, got[0]);
  sameKick(about, got[0]);
});

const FILE_ID = "o/r:docs/a.md";
const COMMIT = "0123456789abcdef0123456789abcdef01234567";

test("a repo file the view drew: the file view's own { repo, path, commit }", async () => {
  const reads = [];
  const fleet = tapRepoFile({ repoFile: async () => ({ file: { bytes: new TextEncoder().encode("# hi") } }) }, (r) => reads.push(r));
  const result = await loadRepoFile({ id: FILE_ID, ref: COMMIT, fleet });
  assert.equal(reads.length, 1);
  assert.deepEqual([reads[0].id, reads[0].ref], [FILE_ID, COMMIT]);
  assert.equal(reads[0].result, result, "the tap hands the view the read unchanged");
  const got = [];
  const view = withHooks(() => RepoFileView({ id: FILE_ID, commit: COMMIT, onChatAbout: (a) => got.push(a), fleet }), result);
  find(view, hasClass("repoChat")).props.onClick();
  const { about } = screenChatOf({ sel: { kind: "repo-file", id: FILE_ID, ref: COMMIT }, repoFile: reads[0] });
  assert.deepEqual(about, { repo: "o/r", path: "docs/a.md", commit: COMMIT });
  assert.deepEqual(about, got[0]);
  sameKick(about, got[0]);
});

test("a repo file the view did not draw (loading, an error, binary, another file) is a screen", () => {
  const sel = { kind: "repo-file", id: FILE_ID, ref: COMMIT };
  for (const repoFile of [null, { id: FILE_ID, ref: COMMIT, result: { error: "no" } },
    { id: FILE_ID, ref: COMMIT, result: { file: { bytes: Uint8Array.from([0, 1, 2]) } } },
    { id: "o/r:other.md", ref: COMMIT, result: { file: { bytes: new TextEncoder().encode("x") } } }]) {
    const { about } = screenChatOf({ sel, repoFile });
    assert.equal(about.kind, "screen");
    assert.equal(about.label, "Repos > o/r:docs/a.md");
  }
});

// ---- a ticket's detail from the explorer ------------------------------------------

test("a ticket from the explorer: a ticket about, titled once its read reports; stale reports ignored", () => {
  const sel = { kind: "ticket", id: "o/r#7" };
  const ticket = { repo: "o/r", number: 7 };
  assert.deepEqual(screenChatOf({ sel, ticket }).about, { kind: "ticket", repo: "o/r", number: 7, title: "" });
  const about = { kind: "ticket", repo: "o/r", number: 7, title: "a chore" };
  assert.deepEqual(screenChatOf({ sel, ticket, about }).about, about);
  assert.deepEqual(screenChatOf({ sel, ticket, about: { ...about, number: 8 } }).about.title, "", "another ticket's report");
});

test("a ticket with no repo (All repos) turns the button off, saying why", () => {
  const r = screenChatOf({ sel: { kind: "ticket", id: "7" }, ticket: { repo: null, number: 7 } });
  assert.equal(r.about, null);
  assert.equal(r.off, NO_REPO_OFF);
});

// ---- every other screen: kind "screen" ------------------------------------------

test("screen labels: every VIEWS id, under its explorer branch", () => {
  const table = Object.fromEntries(VIEWS.map((v) => [v.id, screenLabel({ kind: v.id })]));
  assert.deepEqual(table, {
    tickets: "Tickets",
    needs: "Tickets > Needs you",
    "hitlp-requests": "HITLP Requests",
    "hitlp-closed": "HITLP Requests > Closed",
    accounts: "Config > AI Accounts",
    repos: "Config > Repos",
    jobs: "Config > Jobs",
    frakworks: "Config > frakworks",
    palettes: "Config > Palettes",
    jev: "Analytics > Jev decisions",
    doctor: "Analytics > Doctor",
    friction: "Analytics > Friction notes",
    refactoring: "Analytics > Refactoring",
    "ssh-verdicts": "Analytics > SSH verdicts",
    "account-usage": "Analytics > AI Accounts > Account balances",
    "account-consumption": "Analytics > AI Accounts > Account consumption",
    builds: "Analytics > Builds",
    "hitlp-producer": "Utils > HITLP producer",
  });
});

test("the explorer names each branch the VIEWS rows say they sit under", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "explorer.jsx"), "utf8");
  for (const v of VIEWS) {
    // A nested branch ("Analytics > AI Accounts") is found by its last label, after the one above it.
    const branch = v.branch.split(" > ").map((b) => `label="${b}"`).join("[\\s\\S]*?");
    // A branch's own pane (Tickets, HITLP Requests) is the heading's item, selected on its label.
    const re = v.branch === v.label
      ? new RegExp(`<Branch [^\\n]*label="${v.label}"[^\\n]*\\n?[^\\n]*item=\\{\\{ kind: "${v.id}" \\}\\}`)
      : new RegExp(`${branch}[\\s\\S]*?item=\\{\\{ kind: "${v.id}" \\}\\}[^>]*>${v.label}<`);
    assert.match(src, re, `${v.id} under ${v.branch}`);
  }
});

const screen = (sel, ctx = {}) => {
  const { about } = screenChatOf({ sel, ...ctx });
  assert.equal(about.kind, "screen");
  return kickStarterOf(about);
};

test("Tickets, Config > Repos, Config > frakworks: a screen kick-starter naming the screen", () => {
  for (const [kind, label] of [["tickets", "Tickets"], ["repos", "Config > Repos"], ["frakworks", "Config > frakworks"]]) {
    const k = screen({ kind }, { repo: "o/r" });
    assert.equal(k.kind, "screen");
    assert.equal(k.shown, `Discuss screen ${label}`);
    assert.match(k.shown, /^Discuss screen /);
    assert.equal(k.sent, `Discuss screen ${label} (the frakworks desktop app's work area, repo o/r selected)`);
  }
  assert.equal(screen({ kind: "repos" }).sent, "Discuss screen Config > Repos (the frakworks desktop app's work area)",
    "All repos: no repo named");
});

test("a Grafana panel, a saved chat and the proposals list are screens named from App's lists", () => {
  const grafana = { dashboards: [{ label: "Fleet", url: "u" }, { label: "Costs", url: "v" }] };
  assert.equal(screen({ kind: "grafana", id: 1 }, { grafana }).shown, "Discuss screen Analytics > Costs (Grafana)");
  assert.equal(screen({ kind: "grafana", id: 5 }, { grafana }).shown, "Discuss screen Analytics > Grafana");
  const savedChats = { chats: [{ id: "s1", title: "the tier talk" }] };
  assert.equal(screen({ kind: "saved-chat", id: "s1" }, { savedChats }).shown, "Discuss screen Saved Chats > the tier talk");
  assert.equal(screen({ kind: "proposal", id: "o/r:slug" }).shown, "Discuss screen Proposals > o/r:slug", "not listed");
  assert.equal(screen({ kind: "proposal", id: "o/r:slug" }, { proposalRow: { repo: "o/r", number: null, title: "a doc" } }).shown,
    "Discuss screen Proposals > a doc", "a doc with no ticket has no Chat about this of its own");
});

test("a screen kick-starter's row: the words, no pill, no throw", () => {
  const kick = screen({ kind: "repos" });
  const html = draw(React.createElement(KickStarterRow, { kick }));
  assert.equal(html, '<div class="fmRow fmRow-user fmKickStarter"><span class="fmMark" role="img" aria-label="the user">&gt;</span>'
    + '<div class="fmText">Discuss screen Config &gt; Repos</div></div>');
  assert.doesNotMatch(html, /fmKickRef|<a /);
  const live = draw(React.createElement(FirstMate, { snapshot: snap(), draft: "", onDraft: () => {}, onSend: () => {}, kickStarter: kick }));
  assert.match(live, /fmKickStarter/);
});

// ---- the click and the first send ------------------------------------------------

const bridge = () => {
  const calls = [];
  return {
    calls,
    startNew: async () => { calls.push(["startNew"]); return { ok: true }; },
    send: async (text, images) => { calls.push(["send", text, images]); return { ok: true }; },
  };
};
const mountStrip = (fm) => {
  const slots = [];
  const app = { kick: null };
  const setKick = (v) => { app.kick = typeof v === "function" ? v(app.kick) : v; };
  const real = { useState: React.useState, useEffect: React.useEffect, useCallback: React.useCallback };
  const drawStrip = (props = {}) => {
    let i = 0;
    React.useState = (init) => {
      const k = i++;
      if (!(k in slots)) slots[k] = typeof init === "function" ? init() : init;
      return [slots[k], (v) => { slots[k] = typeof v === "function" ? v(slots[k]) : v; }];
    };
    React.useEffect = () => {};
    React.useCallback = (fn) => fn;
    const had = global.window;
    global.window = { firstMate: fm };
    try { return FirstMateStrip({ kickStarter: app.kick, onKickStarter: setKick, onOpenTicket: () => {}, ...props }); }
    finally { Object.assign(React, real); global.window = had; }
  };
  return { draw: drawStrip, app, setKick };
};

test("the strip hands the button's click and off reason to First mate", () => {
  const fn = () => {};
  const el = mountStrip(bridge()).draw({ onChatAboutScreen: fn, chatAboutScreenOff: "why" });
  assert.equal(el.props.onChatAboutScreen, fn);
  assert.equal(el.props.chatAboutScreenOff, "why");
});

test("a click on a screen starts a new conversation, sends nothing, and the first send carries it", async () => {
  const fm = bridge();
  const strip = mountStrip(fm);
  const { about } = screenChatOf({ sel: { kind: "repos" }, repo: "o/r" });
  await chatAbout(about, { firstMate: fm, setKickStarter: strip.setKick });
  assert.deepEqual(fm.calls, [["startNew"]], "the new-conversation call, and no send");
  assert.equal(strip.draw().props.kickStarter.shown, "Discuss screen Config > Repos");
  const had = global.window;
  global.window = { firstMate: fm };
  try { await strip.draw().props.onSend("why is it red?"); } finally { global.window = had; }
  assert.deepEqual(fm.calls.filter((c) => c[0] === "send").map((c) => c[1]),
    ["Discuss screen Config > Repos (the frakworks desktop app's work area, repo o/r selected)\n\nwhy is it red?"]);
  assert.equal(strip.app.kick, null);
});

test("App wires the button through onChatAbout, and the detail screens and file view report what they are about", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "index.jsx"), "utf8");
  assert.match(src, /const onChatAboutScreen = screenChat\.about \? \(\) => onChatAbout\(screenChat\.about\) : null;/);
  assert.match(src, /<FirstMateStrip[^/]*onChatAboutScreen=\{onChatAboutScreen\} chatAboutScreenOff=\{screenChat\.off \|\| null\}/);
  assert.match(src, /<TicketScreen[\s\S]*?onChatTarget=\{setChatTarget\}/);
  assert.match(src, /<RepoFileView[^>]*fleet=\{repoFileFleet\}/);
});

test("Configure First Mate is a modal on Config > Repos, not a screen of its own (#1801)", () => {
  assert.equal(VIEWS.some((v) => /first.?mate/i.test(v.id)), false);
  assert.equal(screen({ kind: "repos" }, { repo: "o/r" }).shown, "Discuss screen Config > Repos");
});

test("the Needs view's HITLP decision (#2173, #2487) reaches main over hitlp:surface-decide, like every other write", () => {
  const preload = fs.readFileSync(path.join(__dirname, "..", "src", "preload.js"), "utf8");
  assert.match(preload, /hitlpSurfaceDecide: \(taskId, outcome, answer, reason, payloadDigest\) => ipcRenderer\.invoke\("hitlp:surface-decide"/);
  assert.doesNotMatch(preload, /engine:hitlp-decide|decideHitlp/);
  // #100091: the update preview modal's four channels; none takes an argument from the renderer
  assert.match(preload, /updatePreview: \(\) => ipcRenderer\.invoke\("app:update-preview"\)/);
  assert.match(preload, /snapshot: \(\) => ipcRenderer\.invoke\("app:snapshot"\)/);
  assert.match(preload, /engineUpdatePreview: \(\) => ipcRenderer\.invoke\("engine:update-preview"\)/);
  assert.match(preload, /updateEngine: \(\) => ipcRenderer\.invoke\("engine:update-run"\)/);
  // #2206: Sign in to the engine, three channels; the token goes inward only
  assert.match(preload, /signIn: \(token\) => ipcRenderer\.invoke\("engine:sign-in", String\(token == null \? "" : token\)\)/);
  assert.match(preload, /signOut: \(\) => ipcRenderer\.invoke\("engine:sign-out"\)/);
  assert.match(preload, /signInStatus: \(\) => ipcRenderer\.invoke\("engine:sign-in-status"\)/);
});

test("a ticket at the post-deploy check is chatted about as a ticket, whatever its check's state", () => {
  const issue = { repo: "o/r", number: 7, title: "deployed", kind: "chore", labels: ["chore"],
    flow: { flow: "chore", outcome: "started", ended: false, stage: "call-post-deploy-check", step: "decide at the deadline" } };
  assert.deepEqual(issueAboutOf({ repo: "o/r", number: 7, issue, offBoard: null }), { kind: "ticket", repo: "o/r", number: 7, title: "deployed" });
});
