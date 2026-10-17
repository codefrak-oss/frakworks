// The Pitch view (#563, src/renderer/pitch.jsx and src/pitch-frame.js): a
// proposal's pitch page drawn in a sandboxed srcdoc frame with a CSP of its
// own, a proposal with no page drawn as its sanitized rendered doc, and the
// decision controls beside it linking to the HITLP server where it is decided.
// Light and dark are the page's own prefers-color-scheme rules, which the
// frame keeps and lets follow the OS; the user checks the look by eye at
// acceptance (no display in the sandbox this was built in).
//
// The ticket detail's Pitch section (#579, TicketPitch in pitch.jsx, used by
// ticket.jsx) is tested at the end: the same read, always in the page's dark
// scheme, with a full-window control, and no section for a ticket with none.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { PITCH_CSP, PITCH_FRAME_ATTRS, PITCH_FRAME_STYLE, pitchSrcdoc } = require("../src/pitch-frame");
const { PitchBody, DecisionControls, PitchView, loadPitch, isProposal, ChoiceBar } = require("../dist/pitch.cjs");
const { TicketDetail } = require("../dist/index.cjs");
const { fetchTicketPitch } = require("../src/engine-data");

const draw = (el) => renderToStaticMarkup(el);
const src = (f) => fs.readFileSync(path.join(__dirname, "..", "src", f), "utf8");

// A pitch page as smith/pitch-guide.md shapes one: inline styles with a dark
// variant, and its diagrams as inline SVG.
const PAGE = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>X</title>
<style>:root { color: #111; background: #fff; } @media (prefers-color-scheme: dark) { :root { color: #eee; background: #111; } }</style>
</head><body><h1>X</h1><svg viewBox="0 0 10 10" role="img"><rect width="10" height="10" fill="currentColor"/></svg></body></html>`;

const CSP_TAG = `<meta http-equiv="Content-Security-Policy" content="${PITCH_CSP}">`;

// ---- the frame ---------------------------------------------------------------

test("the frame's CSP lets it reach nothing: no network, inline styles and data: images only", () => {
  assert.equal(PITCH_CSP, "default-src 'none'; style-src 'unsafe-inline'; img-src data:");
});

test("the frame is sandboxed with every restriction on: no allow-scripts, no allow-same-origin", () => {
  assert.equal(PITCH_FRAME_ATTRS.sandbox, "");
  const html = draw(React.createElement(PitchBody, { result: { pitch: { kind: "html", path: "docs/proposals/x.html", prUrl: "u", content: PAGE } } }));
  const frame = html.match(/<iframe[^>]*>/)[0];
  assert.match(frame, / sandbox=""/);
  assert.doesNotMatch(frame, /allow-scripts|allow-same-origin|allow-/);
  assert.match(frame, / srcDoc="|srcdoc="/i);
  assert.doesNotMatch(frame, / src="/, "srcdoc only, never a URL");
  assert.match(frame, /referrerPolicy="no-referrer"|referrerpolicy="no-referrer"/i);
});

test("the srcdoc starts with the CSP meta: right after the doctype, before the page's own head", () => {
  const doc = pitchSrcdoc(PAGE);
  assert.ok(doc.startsWith("<!doctype html>" + CSP_TAG), "first after the doctype, so the page keeps standards mode");
  assert.ok(doc.indexOf(CSP_TAG) < doc.indexOf("<head>"));
  assert.equal(doc.split(CSP_TAG).length, 2, "once");
});

test("a page with no head, or no doctype, still gets the CSP first", () => {
  assert.ok(pitchSrcdoc("<p>bare</p><img src=\"https://example.com/x.png\">").startsWith(CSP_TAG));
  assert.ok(pitchSrcdoc("<html><body>x</body></html>").startsWith(CSP_TAG));
  assert.ok(pitchSrcdoc("﻿<!-- c --><!DOCTYPE html><img src=x>").startsWith("﻿<!-- c --><!DOCTYPE html>" + CSP_TAG));
  assert.ok(pitchSrcdoc("<header>not a head</header>").startsWith(CSP_TAG));
  assert.equal(pitchSrcdoc(null), CSP_TAG + '<meta name="color-scheme" content="light dark">');
});

test("light and dark: the srcdoc keeps the page's inline SVG diagrams and its prefers-color-scheme style, and the frame follows the OS scheme", () => {
  const doc = pitchSrcdoc(PAGE);
  assert.ok(doc.includes('<svg viewBox="0 0 10 10" role="img"><rect width="10" height="10" fill="currentColor"/></svg>'), "the diagram, untouched");
  assert.ok(doc.includes("@media (prefers-color-scheme: dark) { :root { color: #eee; background: #111; } }"), "the dark rules, untouched");
  assert.ok(doc.includes('<meta name="color-scheme" content="light dark">'), "the canvas may be light or dark");
  assert.equal(PITCH_FRAME_STYLE.colorScheme, "light dark");
  const html = draw(React.createElement(PitchBody, { result: { pitch: { kind: "html", path: "p", prUrl: "u", content: PAGE } } }));
  assert.match(html, /<iframe[^>]*style="color-scheme:light dark"/);
});

test("the renderer runs sandboxed with no node integration, and the app's own CSP is not loosened", () => {
  const main = src("main.js");
  const prefs = main.match(/webPreferences:\s*\{([^}]*)\}/)[1];
  assert.match(prefs, /nodeIntegration:\s*false/);
  assert.match(prefs, /sandbox:\s*true/);
  assert.match(prefs, /contextIsolation:\s*true/);
  const index = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "index.html"), "utf8");
  assert.match(index, /content="default-src 'self';/);
});

// ---- the markdown fallback ---------------------------------------------------

const MD = { pitch: { kind: "markdown", path: "docs/proposals/y.md", prUrl: "u", content: "<h1>Y</h1><script>alert(1)</script>" } };

test("a proposal with no pitch page shows its rendered doc, sanitized, and no frame", () => {
  const seen = [];
  const sanitize = (h) => { seen.push(h); return h.replace(/<script>[\s\S]*?<\/script>/g, ""); };
  const html = draw(React.createElement(PitchBody, { result: MD, sanitize }));
  assert.deepEqual(seen, [MD.pitch.content]);
  assert.ok(html.includes('<div class="pitchDoc md"><h1>Y</h1></div>'));
  assert.doesNotMatch(html, /<script|<iframe/);
});

test("where DOMPurify cannot run, the doc is not drawn raw", () => {
  const html = draw(React.createElement(PitchBody, { result: MD }));
  assert.doesNotMatch(html, /<script|<h1>Y/);
  assert.match(html, /read it on the PR/);
});

test("loading and errors draw as such", () => {
  assert.match(draw(React.createElement(PitchBody, { result: { loading: true } })), /asking the engine for the pitch/);
  assert.match(draw(React.createElement(PitchBody, { result: { error: "no pitch: o/r#1 is not a proposal with a PR" } })), /class="err">no pitch/);
});

// ---- the decision controls ---------------------------------------------------

// The accept-card.test.js harness: the component called as a function with
// useState stood in, so its handlers can be pressed and it redrawn.
const mount = (props) => {
  const slots = [];
  const real = { useState: React.useState, useEffect: React.useEffect };
  const draw = (over = {}) => {
    let i = 0;
    React.useState = (init) => {
      const k = i++;
      if (!(k in slots)) slots[k] = typeof init === "function" ? init() : init;
      return [slots[k], (v) => { slots[k] = typeof v === "function" ? v(slots[k]) : v; }];
    };
    React.useEffect = () => {};
    try { return DecisionControls({ ...props, ...over }); } finally { Object.assign(React, real); }
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
const find = (tree, pred) => walk(tree).find((e) => e.props && pred(e));
const button = (tree, cls) => find(tree, (e) => e.type === "button" && String(e.props.className).split(" ").includes(cls));

test("a waiting decision mounts the proposal ticket's HITLP task inline (#100070); no link, no box", () => {
  const tree = mount({ enabled: true, repo: "o/r", number: 5, taskId: "t-5", onChatAbout: () => {} }).draw();
  assert.ok(find(tree, (e) => e.props.className === "askHead" && e.props.children === "Your decision"));
  const task = find(tree, (e) => e.type && e.type.name === "TicketTask");
  assert.ok(task, "the inline task");
  assert.deepEqual([task.props.repo, task.props.number, task.props.taskId], ["o/r", 5, "t-5"]);
  const html = draw(tree);
  assert.doesNotMatch(html, /decideLink|decideTask|<textarea|pitchDecide/);
  assert.ok(button(tree, "pitchChat"), "Chat about this stays");
});

test("with no decision waiting, no decision control is drawn but Chat about this", () => {
  const tree = mount({ enabled: false, repo: "o/r", number: 5, onChatAbout: () => {} }).draw();
  assert.equal(find(tree, (e) => e.props.className === "askHead"), undefined);
  assert.equal(find(tree, (e) => e.type && e.type.name === "TicketTask"), undefined);
  assert.ok(button(tree, "pitchChat"));
});

// ---- the data path and the issue detail --------------------------------------

test("loadPitch reads through fleet.ticketPitch, and every failure is an error", async () => {
  const calls = [];
  const fleet = { ticketPitch: async (...a) => { calls.push(a); return { pitch: { kind: "html", content: "x" } }; } };
  assert.deepEqual(await loadPitch({ repo: "o/r", number: 5, fleet }), { pitch: { kind: "html", content: "x" } });
  assert.deepEqual(calls, [["o", "r", 5]]);
  assert.deepEqual(await loadPitch({ repo: "o/r", number: 5, fleet: { ticketPitch: async () => ({ error: "nope" }) } }), { error: "nope" });
  assert.match((await loadPitch({ repo: "o/r", number: 5, fleet: { ticketPitch: async () => { throw new Error("gone"); } } })).error, /IPC failed: gone/);
  assert.match((await loadPitch({ repo: "o/r", number: 5, fleet: {} })).error, /no pitch read/);
});

test("loadPitch with a slug reads the doc's page when there is no ticket, or its pitch read fails (#660)", async () => {
  const calls = [];
  const page = { pitch: { kind: "markdown", path: "docs/proposals/x.md", prUrl: "", content: "<p>x</p>" } };
  const fleet = { ticketPitch: async (...a) => { calls.push(["ticket", ...a]); return { error: "no pitch" }; },
    proposalPage: async (...a) => { calls.push(["page", ...a]); return page; } };
  assert.deepEqual(await loadPitch({ repo: "o/r", number: null, slug: "x", fleet }), page);
  assert.deepEqual(calls, [["page", "o", "r", "x"]]);
  calls.length = 0;
  assert.deepEqual(await loadPitch({ repo: "o/r", number: 7, slug: "x", fleet }), page);
  assert.deepEqual(calls, [["ticket", "o", "r", 7], ["page", "o", "r", "x"]]);
  calls.length = 0;
  assert.deepEqual(await loadPitch({ repo: "o/r", number: 7, fleet }), { error: "no pitch" }, "no slug, no fallback");
  assert.match((await loadPitch({ repo: "o/r", number: null, slug: "x", fleet: {} })).error, /no proposal page read/);
});

const stubFetch = (status, body, calls = []) => async (url) => {
  calls.push(url);
  return { ok: status >= 200 && status < 300, status, text: async () => body };
};

test("fetchTicketPitch reads GET /ticket/<owner>/<repo>/<n>/pitch and checks its shape", async () => {
  const calls = [];
  const body = JSON.stringify({ kind: "html", path: "docs/proposals/x.html", prUrl: "https://github.com/o/r/pull/2", content: PAGE });
  const r = await fetchTicketPitch("o", "r", 5, { env: {}, fetch: stubFetch(200, body, calls) });
  assert.equal(r.error, null);
  assert.deepEqual(r.pitch, { ...JSON.parse(body), options: [], awaitingChoice: false, awaitingDecision: false });
  assert.match(calls[0], /\/ticket\/o\/r\/5\/pitch$/);
  assert.match((await fetchTicketPitch("o", "r", 5, { env: {}, fetch: stubFetch(404, '{"error":"no proposal"}') })).error, /not a proposal with a PR/);
  assert.match((await fetchTicketPitch("o", "r", 5, { env: {}, fetch: stubFetch(200, '{"kind":"pdf","content":"x"}') })).error, /has no `kind`/);
  assert.match((await fetchTicketPitch("o", "r", "x", {})).error, /not a ticket number/);
  assert.match(src("preload.js"), /ticketPitch: \(owner, repoName, n\) => ipcRenderer\.invoke\("engine:ticket-pitch"/);
  assert.match(src("main.js"), /ipcMain\.handle\("engine:ticket-pitch"/);
});

const t0 = "2026-09-26T10:00:00Z";
const proposalIssue = (extra = {}) => ({
  repo: "o/r", number: 5, title: "Pitch it", author: "someone", labels: ["proposal"], url: "https://github.com/o/r/issues/5",
  createdAt: t0, updatedAt: t0, kind: "proposal", authorized: true,
  flow: { flow: "proposal", outcome: "started", instance: "p-1", startedAt: t0, ended: false, prUrl: "https://github.com/o/r/pull/6",
    pitch: "docs/proposals/x.html" },
  pendingQuestion: { id: 1, intake: "p-1", question: "The proposal is ready for your decision", askedAt: t0 },
  ...extra,
});

test("the issue detail shows the Pitch view for a proposal with a PR, with the controls in the decision card's place", () => {
  const needs = [{ repo: "o/r", ticket: 5, kind: "decision", actionId: "p-1", ask: "decide" }];
  const html = draw(React.createElement(TicketDetail, { repo: "o/r", number: 5, issue: proposalIssue(), needs, now: Date.parse(t0) }));
  assert.match(html, /class="pitchView"/);
  assert.match(html, /Your decision/);
  assert.doesNotMatch(html, /decisionCard/, "no second card");
  assert.ok(html.includes('href="https://github.com/o/r/pull/6"'));
  const chore = proposalIssue({ flow: { ...proposalIssue().flow, flow: "chore" }, pendingQuestion: null });
  assert.doesNotMatch(draw(React.createElement(TicketDetail, { repo: "o/r", number: 5, issue: chore, now: Date.parse(t0) })), /pitchView/);
  assert.equal(isProposal({ flow: { flow: "proposal", prUrl: null } }), false);
});

test("a proposal's question that is not its decision mounts its own inline task, and no decision control is drawn", () => {
  const html = draw(React.createElement(TicketDetail, { repo: "o/r", number: 5, issue: proposalIssue(), needs: [], now: Date.parse(t0) }));
  assert.equal((html.match(/reading the HITLP task/g) || []).length, 1);
  assert.doesNotMatch(html, /No decision is waiting|Your decision/);
  assert.match(html, /pitchChat/);
});

test("PitchView asks for the pitch on mount (drawn loading until it answers)", () => {
  const html = draw(React.createElement(PitchView, { repo: "o/r", number: 5, prUrl: "u", decision: true, fleet: null }));
  assert.match(html, /asking the engine for the pitch/);
});

// ---- the ticket detail's Pitch section (#579) --------------------------------

const { TicketPitchView, TicketPitch, PITCH_SANDBOX, darkPitch, pitchHtmlOf, ALWAYS, NEVER } = require("../dist/ticket.cjs");
const drawSection = (props) => draw(React.createElement(TicketPitchView, props));
const htmlPitch = { pitch: { kind: "html", path: "docs/proposals/x.html", prUrl: "u", content: PAGE } };

test("the ticket's Pitch section frames the engine's html in a srcdoc iframe with a sandbox that runs no script", () => {
  const html = drawSection({ html: pitchHtmlOf(htmlPitch) });
  assert.match(html, /<h2 class="pitchHead tdFold"[^>]*><span class="tdFoldArrow">▾<\/span>Pitch/);
  assert.match(html, /class="pitchOpen"/, "the full-window control");
  const frames = [...html.matchAll(/<iframe [^>]*>/g)].map((m) => m[0]);
  assert.equal(frames.length, 1, "no overlay until it is opened");
  assert.match(frames[0], /srcDoc="|srcdoc="/i);
  assert.match(frames[0], new RegExp(`sandbox="${PITCH_SANDBOX}"`));
  assert.ok(html.includes("&lt;h1&gt;X&lt;/h1&gt;") || html.includes("<h1>X</h1>"), "the page is in the frame's srcdoc");
});

test("the section's sandbox allows neither scripts nor its own origin", () => {
  const tokens = PITCH_SANDBOX.split(/\s+/);
  assert.ok(!tokens.includes("allow-scripts"));
  assert.ok(!tokens.includes("allow-same-origin"));
  assert.ok(!tokens.includes("allow-top-navigation"));
  assert.deepEqual(tokens, ["allow-popups"], "popups only: its links leave through main's window-open handler");
});

test("a 404, a failure, a markdown-only draft or an empty page is no Pitch section and no error", async () => {
  const notFound = await loadPitch({ repo: "o/r", number: 5, fleet: { ticketPitch: async () => ({ error: "no pitch: o/r#5 is not a proposal with a PR" }) } });
  for (const r of [notFound, { error: "engine answered 502" }, { pitch: { kind: "markdown", content: "<p>x</p>" } },
    { pitch: { kind: "html", content: "  " } }, null]) {
    assert.equal(drawSection({ html: pitchHtmlOf(r) }), "");
  }
});

test("Full window opens the same page over the whole window, with a close control", () => {
  const html = drawSection({ html: PAGE, full: true });
  assert.match(html, /class="pitchOverlay"/);
  assert.match(html, /class="pitchClose"/);
  const frames = [...html.matchAll(/<iframe [^>]*>/g)].map((m) => m[0]);
  assert.equal(frames.length, 2);
  for (const f of frames) assert.match(f, new RegExp(`sandbox="${PITCH_SANDBOX}"`));
});

test("the ticket detail has no Pitch section before (or without) an answer from the engine", () => {
  const doc = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-ticket.json"), "utf8"));
  const html = renderToStaticMarkup(React.createElement(TicketDetail, { number: doc.ticket, doc, now: doc.now * 1000 }));
  assert.doesNotMatch(html, /pitchHead|<iframe/);
  assert.equal(draw(React.createElement(TicketPitch, { repo: "o/r", issue: 5, fleet: null })), "");
});

test("the section shows the page's dark rule whatever the OS scheme: its dark query is made always true", () => {
  const out = darkPitch(PAGE);
  assert.doesNotMatch(out, /prefers-color-scheme/);
  assert.ok(out.includes(`@media ${ALWAYS} { :root { color: #eee; background: #111; } }`));
  assert.equal(ALWAYS, "(min-width: 0px)");
  assert.ok(darkPitch("<style>@media (prefers-color-scheme:light){body{color:#000}}</style>").includes(`@media ${NEVER}{`));
  assert.ok(darkPitch("<style>@media screen and ( Prefers-Color-Scheme : DARK ){x{}}</style>").includes(`screen and ${ALWAYS}{`));
});

test("the section's srcdoc keeps the doctype first, then the CSP, a dark-only color-scheme, and links to a new window", () => {
  const out = darkPitch(PAGE);
  assert.ok(out.startsWith("<!doctype html>" + CSP_TAG + '<meta name="color-scheme" content="dark">'), out.slice(0, 200));
  assert.match(out, /<head><base target="_blank"><style>:root \{ color-scheme: dark !important; \}<\/style>/);
  assert.doesNotMatch(out, /content="light dark"/);
  assert.ok(darkPitch("<!DOCTYPE html><p>x</p>").startsWith("<!DOCTYPE html>" + CSP_TAG));
  assert.ok(darkPitch("<header>x</header>").startsWith(CSP_TAG), "<header> is not <head>");
});

test("the ticket detail draws the section, and main sends a pitch frame's own navigations to the browser", () => {
  assert.match(src("main.js"), /will-frame-navigate[\s\S]*?about:srcdoc[\s\S]*?openOutside\(e\.url\)/);
  assert.match(src("renderer/detail.jsx"), /<TicketPitch repo=\{historyRepo\} issue=\{number\} \/>/);
});

test("PitchView puts the decision controls before the pitch", () => {
  const html = draw(React.createElement(PitchView, { repo: "o/r", number: 5, prUrl: "u", decision: true, fleet: null }));
  const controls = html.indexOf('class="pitchControls"'), main = html.indexOf('class="pitchMain"');
  assert.ok(controls >= 0 && main >= 0 && controls < main);
});

// ---- the fold (#1091) ----------------------------------------------------------

// A component called as a function with useState stood in and each effect run
// when its deps change, so its fold can be pressed and its ticket switched.
const mountOf = (Comp, props) => {
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
    try { return Comp({ ...props, ...over }); } finally { Object.assign(React, real); }
  };
  return { draw };
};
const foldOf = (tree) => walk(tree).find((e) => e.type === "h2" && /tdFold/.test(e.props.className || ""));
const tick = () => new Promise((r) => setImmediate(r));

test("PitchView: the Pitch heading folds the pitch away and back, open by default, without a refetch, controls kept", async () => {
  let calls = 0;
  const fleet = { ticketPitch: async () => { calls++; return htmlPitch; } };
  const v = mountOf(PitchView, { repo: "o/r", number: 5, prUrl: "u", decision: true, fleet });
  v.draw();
  await tick();
  let html = draw(v.draw());
  assert.match(html, /<h2 class="tdFold"[^>]*><span class="tdFoldArrow">▾<\/span>/);
  assert.match(html, /class="pitchMain"/);
  assert.match(html, />PR</);
  foldOf(v.draw()).props.onClick();
  html = draw(v.draw());
  assert.match(html, /tdFoldArrow">▸/);
  assert.doesNotMatch(html, /pitchMain/);
  assert.match(html, /reading the HITLP task/, "the decision's task stays while collapsed");
  foldOf(v.draw()).props.onClick();
  html = draw(v.draw());
  assert.match(html, /class="pitchMain"/);
  assert.equal(calls, 1, "expanding draws the loaded pitch, no refetch");
  foldOf(v.draw()).props.onClick();
  assert.doesNotMatch(draw(v.draw()), /pitchMain/);
  v.draw({ number: 6 });
  assert.match(draw(v.draw({ number: 6 })), /tdFoldArrow">▾/, "a new ticket opens the fold again");
});

test("the ticket's Pitch section folds behind its heading; Full window opens the overlay without flipping it", async (t) => {
  const fleet = { ticketPitch: async () => htmlPitch };
  const hadWindow = "window" in global;
  if (!hadWindow) global.window = { addEventListener() {}, removeEventListener() {} };
  t.after(() => { if (!hadWindow) delete global.window; });
  const v = mountOf(TicketPitch, { repo: "o/r", issue: 5, fleet });
  v.draw();
  await tick();
  const view = () => { const el = v.draw(); return el.type(el.props); };
  assert.match(draw(view()), /class="pitchBox"/);
  const head = foldOf(view());
  let stopped = false;
  walk(head).find((e) => e.type === "button").props.onClick({ stopPropagation: () => { stopped = true; } });
  assert.ok(stopped, "the button keeps its click from the fold");
  let html = draw(view());
  assert.match(html, /pitchOverlay/);
  assert.match(html, /class="pitchBox"/);
  foldOf(view()).props.onClick();
  html = draw(view());
  assert.doesNotMatch(html, /pitchBox/);
  assert.match(html, /tdFoldArrow">▸/);
  v.draw({ issue: 6 });
  await tick();
  assert.match(draw(view()), /class="pitchBox"/, "a new ticket opens the fold again");
});

// ---- picking an option (#1720, #2209) -- ------------------------------------------------

const OPTIONS = [{ id: "a", label: "Blue" }, { id: "b", label: "Red" }];

// The decision-controls harness, for ChoiceBar.
const mountChoices = (props) => {
  const slots = [];
  const real = { useState: React.useState };
  const draw = (over = {}) => {
    let i = 0;
    React.useState = (init) => {
      const k = i++;
      if (!(k in slots)) slots[k] = init;
      return [slots[k], (v) => { slots[k] = typeof v === "function" ? v(slots[k]) : v; }];
    };
    try { return ChoiceBar({ ...props, ...over }); } finally { Object.assign(React, real); }
  };
  return { draw };
};
const chooseButtons = (tree) => walk(tree).filter((e) => e.type === "button" && /pitch(Build|Record|Answer)/.test(String(e.props.className)));
const text = (el) => (el == null || typeof el === "boolean" ? "" : typeof el !== "object" ? String(el)
  : Array.isArray(el) ? el.map(text).join("") : text(el.props && el.props.children));
const radios = (tree) => walk(tree).filter((e) => e.type === "input" && e.props.type === "radio");

test("fetchTicketPitch keeps the pitch's options, awaitingChoice and awaitingDecision, dropping malformed ids", async () => {
  const body = JSON.stringify({ kind: "html", path: "docs/proposals/x.html", prUrl: "", content: PAGE, awaitingChoice: true, awaitingDecision: true,
    options: [...OPTIONS, { id: "../x", label: "bad" }] });
  const r = await fetchTicketPitch("o", "r", 5, { env: {}, fetch: stubFetch(200, body) });
  assert.deepEqual(r.pitch.options, OPTIONS);
  assert.equal(r.pitch.awaitingChoice, true);
  assert.equal(r.pitch.awaitingDecision, true);
});

test("the options are listed, pointing at the task above while a choice waits; nothing is selectable or sent", () => {
  const tree = mountChoices({ options: OPTIONS, awaiting: true }).draw();
  assert.deepEqual(walk(tree).filter((e) => e.type === "li").map((e) => e.props["data-option"]), ["a", "b"]);
  assert.equal(radios(tree).length, 0);
  assert.equal(chooseButtons(tree).length, 0);
  assert.match(draw(tree), /pick it in the task above/);
  assert.doesNotMatch(draw(tree), /href=/);
  assert.match(draw(mountChoices({ options: OPTIONS, awaiting: false }).draw()), /not waiting on a choice/);
  assert.equal(mountChoices({ options: [], awaiting: true }).draw(), null, "no options, no bar");
});

test("main, preload and engine-data no longer post a choice", () => {
  assert.doesNotMatch(src("preload.js"), /chooseOption|engine:choose-option/);
  assert.doesNotMatch(src("main.js"), /engine:choose-option/);
  assert.doesNotMatch(src("engine-data.js"), /\/choice`/);
});

test("the option list is the app's own: the pitch frame keeps its sandbox with no scripts", () => {
  assert.equal(PITCH_FRAME_ATTRS.sandbox, "");
});
