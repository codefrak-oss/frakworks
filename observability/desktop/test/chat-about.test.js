// Chat about this (#590): the Pitch view's button starts a new First mate
// conversation (the same firstMate:new as Start new conversation, which
// archives the current one into History) and leaves a kick-starter row in it,
// drawn as if sent but not sent. The user's first message goes out as the
// kick-starter, a blank line and what they typed, as one message, which is
// what main stores; a new conversation, or another proposal's button, drops
// or replaces a kick-starter still waiting.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { DecisionControls, PitchView } = require("../dist/pitch.cjs");
const { FirstMate, KickStarterRow, kickStarterOf, withKickStarter } = require("../dist/first-mate.cjs");
const { FirstMateStrip, chatAbout } = require("../dist/index.cjs");
const { FirstMateManager } = require("../src/first-mate");
const { makeFakeChild } = require("./fake-claude-child");

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

const PROPOSAL = { repo: "codefrak-oss/frakworks", number: 569, title: "a tree of related work in the desktop Explorer" };

// ---- the button ----------------------------------------------------------------

for (const enabled of [true, false]) {
  test(`the decision controls show Chat about this ${enabled ? "when a decision waits" : "when none does"}`, () => {
    const calls = [];
    const html = draw(React.createElement(DecisionControls, { enabled, onAnswer: () => {}, onChatAbout: () => calls.push(1) }));
    if (enabled) assert.match(html, />Your decision</);
    else assert.doesNotMatch(html, /pitchDecide|No decision is waiting/);
    const button = html.match(/<button[^>]*class="[^"]*pitchChat[^"]*"[^>]*>([^<]*)<\/button>/);
    assert.ok(button, "the button is there");
    assert.equal(button[1], "Chat about this");
    assert.doesNotMatch(button[0], /disabled/, "live in both states");
  });
}

test("the Pitch view hands its proposal to onChatAbout, and posts no answer", () => {
  const got = [];
  const answers = [];
  const real = { useState: React.useState, useEffect: React.useEffect };
  React.useState = (init) => [typeof init === "function" ? init() : init, () => {}];
  React.useEffect = () => {};
  let tree;
  try {
    tree = PitchView({ ...PROPOSAL, prUrl: "u", decision: null, onAnswer: (t) => answers.push(t), onChatAbout: (p) => got.push(p), fleet: null });
  } finally { Object.assign(React, real); }
  const controls = find(tree, (e) => e.type === DecisionControls);
  // The controls' own button calls its onChatAbout with nothing.
  const inner = (() => {
    React.useState = (init) => [typeof init === "function" ? init() : init, () => {}];
    try { return DecisionControls(controls.props); } finally { Object.assign(React, real); }
  })();
  find(inner, hasClass("pitchChat")).props.onClick();
  assert.deepEqual(got, [PROPOSAL]);
  assert.deepEqual(answers, []);
});

test("the issue detail passes the board row's title to the Pitch view", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "detail.jsx"), "utf8");
  assert.match(src, /<PitchView[^>]*title=\{issue\.title\}[^>]*onChatAbout=\{onChatAbout\}/);
});

// ---- the click -----------------------------------------------------------------

const bridge = () => {
  const calls = [];
  const fm = {
    calls,
    startNew: async () => { calls.push(["startNew"]); return { ok: true }; },
    send: async (text, images) => { calls.push(["send", text, images]); return { ok: true }; },
  };
  return fm;
};

test("a click starts a new conversation through firstMate.startNew, sets the kick-starter, and sends nothing", async () => {
  const fm = bridge();
  let kick = null;
  await chatAbout(PROPOSAL, { firstMate: fm, setKickStarter: (k) => { kick = k; } });
  assert.deepEqual(fm.calls, [["startNew"]], "the new-conversation call, and no send");
  assert.equal(kick.shown, "Discuss proposal #569 - a tree of related work in the desktop Explorer");
  assert.equal(kick.sent, "Discuss proposal codefrak-oss/frakworks#569 - a tree of related work in the desktop Explorer");
});

test("App wires the button to chatAbout and the strip to the same kick-starter", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "index.jsx"), "utf8");
  assert.match(src, /chatAbout\(p, \{ firstMate: window\.firstMate, setKickStarter \}\)/);
  assert.match(src, /<TicketScreen[\s\S]*?onChatAbout=\{onChatAbout\}/);
  assert.match(src, /<FirstMateStrip[^>]*kickStarter=\{kickStarter\} onKickStarter=\{setKickStarter\}/);
});

// ---- the row -------------------------------------------------------------------

const snap = (over = {}) => ({ history: [], filed: [], busy: false, streaming: "", notice: "New conversation started.", ...over });

test("the First mate draws the kick-starter as a sent user row, with #<n> as its own pill", () => {
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap(), draft: "", onDraft: () => {}, onSend: () => {}, kickStarter: kickStarterOf(PROPOSAL),
  }));
  const row = html.match(/<div class="fmRow fmRow-user fmKickStarter">[\s\S]*?<\/div><\/div>/);
  assert.ok(row, "a user-side row");
  assert.match(row[0], /class="fmMark"[^>]*>&gt;</, "the user's > mark, like a sent message");
  const text = row[0].replace(/<span[^>]*>&gt;<\/span>/, "").replace(/<[^>]+>/g, "");
  assert.equal(text, "Discuss proposal #569 - a tree of related work in the desktop Explorer");
  assert.match(row[0], /<a class="fmKickRef" href="https:\/\/github.com\/codefrak-oss\/frakworks\/issues\/569"[^>]*>#569<\/a>/);
  const css = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "styles.css"), "utf8");
  assert.match(css, /\.fmKickRef \{[^}]*border-radius/, "the pill has a class of its own in styles.css");
});

test("no kick-starter, no row", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap(), draft: "", onDraft: () => {}, onSend: () => {} }));
  assert.doesNotMatch(html, /fmKickStarter|fmKickRef/);
});

test("clicking the pill opens the ticket through onOpenTicket; a modifier click is left alone", () => {
  const opened = [];
  const row = KickStarterRow({ kick: kickStarterOf(PROPOSAL), onOpenTicket: (repo, n) => opened.push([repo, n]) });
  const a = find(row, hasClass("fmKickRef"));
  let prevented = 0;
  a.props.onClick({ button: 0, preventDefault: () => { prevented += 1; } });
  assert.deepEqual(opened, [["codefrak-oss/frakworks", 569]]);
  assert.equal(prevented, 1);
  a.props.onClick({ button: 0, metaKey: true, preventDefault: () => { prevented += 1; } });
  assert.equal(opened.length, 1);
});

test("withKickStarter: the kick-starter, a blank line, the text; the text alone without one", () => {
  const k = kickStarterOf(PROPOSAL);
  assert.equal(withKickStarter(k, "is the tree lazy?"), `${k.sent}\n\nis the tree lazy?`);
  assert.equal(withKickStarter(null, "hi"), "hi");
  assert.equal(withKickStarter(k, ""), k.sent, "an images-only first send still carries it");
});

// ---- the strip: the first send, and dropping ------------------------------------

// A hook harness for FirstMateStrip, as accept-card.test.js does: hooks stood
// in, window.firstMate a recording bridge, and App's kick-starter state held
// here so a clear shows on the next draw.
const mountStrip = (fm) => {
  const slots = [];
  const app = { kick: null };
  const setKick = (v) => { app.kick = typeof v === "function" ? v(app.kick) : v; };
  const real = { useState: React.useState, useEffect: React.useEffect, useCallback: React.useCallback };
  const draw = () => {
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
    try { return FirstMateStrip({ kickStarter: app.kick, onKickStarter: setKick, onOpenTicket: () => {} }); }
    finally { Object.assign(React, real); global.window = had; }
  };
  return { draw, app, setKick };
};
const withWindow = async (fm, fn) => {
  const had = global.window;
  global.window = { firstMate: fm };
  try { return await fn(); } finally { global.window = had; }
};

test("the first send after the click is ONE message: the kick-starter, a blank line, what the user typed", async () => {
  const fm = bridge();
  const strip = mountStrip(fm);
  await chatAbout(PROPOSAL, { firstMate: fm, setKickStarter: strip.setKick });
  assert.equal(strip.draw().props.kickStarter.shown, "Discuss proposal #569 - a tree of related work in the desktop Explorer");
  await withWindow(fm, () => strip.draw().props.onSend("should the tree load lazily?"));
  const sends = fm.calls.filter((c) => c[0] === "send");
  assert.deepEqual(sends.map((c) => c[1]), [
    "Discuss proposal codefrak-oss/frakworks#569 - a tree of related work in the desktop Explorer\n\nshould the tree load lazily?",
  ]);
  assert.equal(strip.app.kick, null, "cleared by the send");
  assert.equal(strip.draw().props.kickStarter, null, "and no longer drawn");
  await withWindow(fm, () => strip.draw().props.onSend("and the second message"));
  assert.equal(fm.calls.filter((c) => c[0] === "send")[1][1], "and the second message", "not prepended again");
});

test("a refused first send hands the kick-starter back with the draft", async () => {
  const fm = bridge();
  fm.send = async (text) => { fm.calls.push(["send", text]); return { refused: true, busy: true }; };
  const strip = mountStrip(fm);
  await chatAbout(PROPOSAL, { firstMate: fm, setKickStarter: strip.setKick });
  await withWindow(fm, () => strip.draw().props.onSend("hello"));
  assert.equal(strip.app.kick && strip.app.kick.number, 569);
  assert.equal(strip.draw().props.draft, "hello");
});

test("Start new conversation before sending drops a waiting kick-starter", async () => {
  const fm = bridge();
  const strip = mountStrip(fm);
  await chatAbout(PROPOSAL, { firstMate: fm, setKickStarter: strip.setKick });
  await withWindow(fm, () => strip.draw().props.onNewConversation());
  assert.equal(strip.app.kick, null);
  assert.deepEqual(fm.calls, [["startNew"], ["startNew"]]);
  await withWindow(fm, () => strip.draw().props.onSend("fresh"));
  assert.equal(fm.calls.at(-1)[1], "fresh");
});

test("Chat about this on another proposal replaces the waiting kick-starter", async () => {
  const fm = bridge();
  const strip = mountStrip(fm);
  await chatAbout(PROPOSAL, { firstMate: fm, setKickStarter: strip.setKick });
  await chatAbout({ repo: "codefrak-oss/other", number: 12, title: "another idea" }, { firstMate: fm, setKickStarter: strip.setKick });
  assert.equal(strip.draw().props.kickStarter.shown, "Discuss proposal #12 - another idea");
  await withWindow(fm, () => strip.draw().props.onSend("go"));
  assert.equal(fm.calls.at(-1)[1], "Discuss proposal codefrak-oss/other#12 - another idea\n\ngo");
});

// ---- what main stores ------------------------------------------------------------

test("main stores the combined first message once, live and in History", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "chat-about-test-"));
  const { command, launchArgs } = makeFakeChild(path.join(dir, "bin"), {});
  const m = new FirstMateManager({
    dir, command, launchArgs,
    submitIntakeFn: async () => ({ ok: true }),
    resolveTierFn: () => ({ model: "sonnet", effort: "medium" }),
  }).init();
  await m.send("an earlier chat");
  m.startNewConversation(); // the click
  const first = withKickStarter(kickStarterOf(PROPOSAL), "is it lazy?");
  await m.send(first);
  const users = m.session.history.filter((r) => r.role === "user").map((r) => r.text);
  assert.deepEqual(users, [first], "live: one user row, the combined text");
  m.startNewConversation();
  const list = m.listConversations();
  assert.equal(list[0].first_message, first);
  assert.equal(list[1].first_message, "an earlier chat", "the conversation in progress was archived");
  const stored = m.getConversation(list[0].id).messages.filter((r) => r.role === "user").map((r) => r.text);
  assert.deepEqual(stored, [first], "History: once, no separate kick-starter row");
  m.stop();
});

// ---- a file (#683): the repo file view's Chat about this ---------------------------

const FILE = { repo: "codefrak-oss/frakworks", path: "engine/README.md", commit: "0123456789abcdef0123456789abcdef01234567" };

test("kickStarterOf a file: its path shown, the repo, path and commit sent", () => {
  const k = kickStarterOf(FILE);
  assert.equal(k.shown, "Discuss file engine/README.md");
  assert.equal(k.sent, "Discuss file codefrak-oss/frakworks:engine/README.md @ 0123456789abcdef0123456789abcdef01234567");
});

test("kickStarterOf a proposal is what it was before files", () => {
  assert.deepEqual(kickStarterOf(PROPOSAL), {
    repo: "codefrak-oss/frakworks", number: 569, title: "a tree of related work in the desktop Explorer",
    shown: "Discuss proposal #569 - a tree of related work in the desktop Explorer",
    sent: "Discuss proposal codefrak-oss/frakworks#569 - a tree of related work in the desktop Explorer",
  });
});

test("a file click starts a new conversation through firstMate.startNew, sets the kick-starter, and sends nothing", async () => {
  const fm = bridge();
  const fleetWrites = [];
  const fleet = new Proxy({}, { get: (_, k) => (...a) => { fleetWrites.push([k, a]); return Promise.resolve({}); } });
  let kick = null;
  await withWindow(fm, async () => {
    global.window.fleet = fleet;
    global.window.engine = fleet;
    await chatAbout(FILE, { firstMate: fm, setKickStarter: (k) => { kick = k; } });
  });
  assert.deepEqual(fm.calls, [["startNew"]], "the new-conversation call, and no send");
  assert.deepEqual(fleetWrites, [], "nothing to the engine or fleet");
  assert.equal(kick.shown, "Discuss file engine/README.md");
});

test("App wires the file view to chatAbout and the strip's file pill to the repo-file selection", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "index.jsx"), "utf8");
  assert.match(src, /<RepoFileView[^>]*onChatAbout=\{onChatAbout\}/);
  assert.match(src, /<FirstMateStrip[^>]*onOpenFile=\{\(k\) => onSelect\(\{ kind: "repo-file", id: repoFileIdOf\(k\.repo, k\.path\), ref: k\.commit \}\)\}/);
});

test("the First mate draws a file kick-starter as a sent user row, with the path as its pill", () => {
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap(), draft: "", onDraft: () => {}, onSend: () => {}, kickStarter: kickStarterOf(FILE),
  }));
  const row = html.match(/<div class="fmRow fmRow-user fmKickStarter">[\s\S]*?<\/div><\/div>/);
  assert.ok(row, "a user-side row");
  const text = row[0].replace(/<span[^>]*>&gt;<\/span>/, "").replace(/<[^>]+>/g, "");
  assert.equal(text, "Discuss file engine/README.md");
  assert.match(row[0], /<a class="fmKickRef"[^>]*>engine\/README\.md<\/a>/);
});

test("clicking a file pill selects the file in the app, not a browser; a proposal pill still opens the ticket", () => {
  const files = [];
  const tickets = [];
  const row = KickStarterRow({ kick: kickStarterOf(FILE), onOpenFile: (k) => files.push(k), onOpenTicket: (...a) => tickets.push(a) });
  let prevented = 0;
  find(row, hasClass("fmKickRef")).props.onClick({ button: 0, preventDefault: () => { prevented += 1; } });
  assert.equal(prevented, 1, "the link's own navigation (a browser window) is stopped");
  assert.deepEqual(files.map((k) => [k.repo, k.path, k.commit]), [[FILE.repo, FILE.path, FILE.commit]]);
  assert.deepEqual(tickets, []);
  const prop = KickStarterRow({ kick: kickStarterOf(PROPOSAL), onOpenFile: (k) => files.push(k), onOpenTicket: (...a) => tickets.push(a) });
  find(prop, hasClass("fmKickRef")).props.onClick({ button: 0, preventDefault: () => {} });
  assert.deepEqual(tickets, [["codefrak-oss/frakworks", 569]]);
  assert.equal(files.length, 1);
});

test("the first send with a file kick-starter is ONE message, and clears it", async () => {
  const fm = bridge();
  const strip = mountStrip(fm);
  await chatAbout(FILE, { firstMate: fm, setKickStarter: strip.setKick });
  await withWindow(fm, () => strip.draw().props.onSend("hello"));
  const sends = fm.calls.filter((c) => c[0] === "send");
  assert.deepEqual(sends.map((c) => c[1]), [`${kickStarterOf(FILE).sent}\n\nhello`]);
  assert.equal(strip.app.kick, null);
});

test("a refused first send hands a file kick-starter back", async () => {
  const fm = bridge();
  fm.send = async (text) => { fm.calls.push(["send", text]); return { refused: true, busy: true }; };
  const strip = mountStrip(fm);
  await chatAbout(FILE, { firstMate: fm, setKickStarter: strip.setKick });
  await withWindow(fm, () => strip.draw().props.onSend("hello"));
  assert.equal(strip.app.kick && strip.app.kick.path, "engine/README.md");
  assert.equal(strip.draw().props.draft, "hello");
});

test("Start new conversation drops a waiting file kick-starter", async () => {
  const fm = bridge();
  const strip = mountStrip(fm);
  await chatAbout(FILE, { firstMate: fm, setKickStarter: strip.setKick });
  await withWindow(fm, () => strip.draw().props.onNewConversation());
  assert.equal(strip.app.kick, null);
});

test("Chat about this on another file, or on a proposal, replaces a waiting file kick-starter", async () => {
  const fm = bridge();
  const strip = mountStrip(fm);
  await chatAbout(FILE, { firstMate: fm, setKickStarter: strip.setKick });
  await chatAbout({ ...FILE, path: "README.md" }, { firstMate: fm, setKickStarter: strip.setKick });
  assert.equal(strip.draw().props.kickStarter.shown, "Discuss file README.md");
  await chatAbout(PROPOSAL, { firstMate: fm, setKickStarter: strip.setKick });
  assert.equal(strip.draw().props.kickStarter.shown, "Discuss proposal #569 - a tree of related work in the desktop Explorer");
  await withWindow(fm, () => strip.draw().props.onSend("go"));
  assert.equal(fm.calls.at(-1)[1], `${kickStarterOf(PROPOSAL).sent}\n\ngo`);
});

// ---- a ticket's detail (#699) ----------------------------------------------------

const { TicketDetail, TicketDetailView } = require("../dist/index.cjs");
const TICKET = { number: 7, repo: "o/r", title: "a chore", author: "a", url: "https://github.com/o/r/issues/7",
  kind: "chore", labels: ["chore"], children: [], pendingQuestion: null, flow: null };
const chatButtons = (html) => html.match(/>Chat about this</g) || [];

test("a ticket's detail has one Chat about this, handing the ticket to onChatAbout", () => {
  const got = [];
  const props = { repo: "o/r", number: 7, issue: TICKET, now: Date.now(), onChatAbout: (p) => got.push(p) };
  const html = draw(React.createElement(TicketDetail, props));
  assert.equal(chatButtons(html).length, 1);
  assert.match(html, /class="ticketChat" title="Start a new First Mate conversation about this ticket"/);
  const button = find(TicketDetailView(props), hasClass("ticketChat"));
  button.props.onClick();
  assert.deepEqual(got, [{ kind: "ticket", repo: "o/r", number: 7, title: "a chore" }]);
});

test("a proposal's detail draws no second Chat about this", () => {
  const issue = { ...TICKET, labels: ["proposal"], flow: { flow: "proposal", prUrl: "u" } };
  const html = draw(React.createElement(TicketDetail, { repo: "o/r", number: 7, issue, now: Date.now(), onChatAbout: () => {} }));
  assert.doesNotMatch(html, /ticketChat/);
  assert.equal(chatButtons(html).length, 1);
});

test("an off-board ticket's detail has the button, titled from offBoard.ticket or not at all", () => {
  const got = [];
  for (const offBoard of [{ ticket: { title: "closed one" } }, { error: "x" }]) {
    const props = { repo: "o/r", number: 9, issue: null, offBoard, needs: [], now: Date.now(), onChatAbout: (p) => got.push(p) };
    const tree = TicketDetailView(props);
    find(tree, hasClass("ticketChat")).props.onClick();
  }
  assert.deepEqual(got.map(kickStarterOf).map((k) => k.shown), ["Discuss ticket #9 - closed one", "Discuss ticket #9"]);
});

test("a ticket kick-starter: shown, sent, and its pill", () => {
  const kick = kickStarterOf({ kind: "ticket", repo: "o/r", number: 7, title: "a chore" });
  assert.equal(kick.shown, "Discuss ticket #7 - a chore");
  assert.equal(kick.sent, "Discuss ticket o/r#7 - a chore");
  assert.equal(withKickStarter(kick, "hi"), "Discuss ticket o/r#7 - a chore\n\nhi");
  assert.equal(kickStarterOf({ repo: "o/r", number: 7, title: "p" }).shown, "Discuss proposal #7 - p");
  const html = draw(React.createElement(KickStarterRow, { kick }));
  assert.match(html, /Discuss ticket <a class="fmKickRef"[^>]*>#7<\/a> - a chore/);
  const opened = [];
  const row = KickStarterRow({ kick, onOpenTicket: (r, n) => opened.push([r, n]) });
  find(row, hasClass("fmKickRef")).props.onClick({ button: 0, preventDefault() {} });
  assert.deepEqual(opened, [["o/r", 7]]);
});
