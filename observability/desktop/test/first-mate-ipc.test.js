// First mate's IPC surface and its component (issue #100).
//
// Acceptance criterion 2: a pane of its own (the two Chats panes it stood beside
// went in #634), with no peer tools. Criterion 3: its identity is fixed — no rename, no
// "new conversation"/reset, no pin control anywhere in its UI, checked here
// both against the bridge (no such channel exists) and against the drawn
// component (no such channel name is ever referenced, and none of the
// bridge methods a rename/reset/pin control would call are used).
//
// Issue #185 relaxed criterion 3 for reset only: the bridge gained one
// no-argument `firstMate:new` channel and the strip one "Start new
// conversation" button. Rename and pin stay absent, and first-mate.jsx still
// never touches `window.chats`.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const { FirstMate } = require("../dist/first-mate.cjs");
const { FirstMateManager } = require("../src/first-mate");
// The tier map main loads from GET /tiers (src/engine-tiers.js), as V42 seeds it.
require("../src/engine-tiers").setTiers(require("./seeded-tiers").SEEDED_TIERS);

const src = (...p) => fs.readFileSync(path.join(__dirname, "..", "src", ...p), "utf8");
const draw = (el) => renderToStaticMarkup(el);

// The channels a rename, reset ("new conversation") or pin control would
// have to name, on the `chats` bridge or a hypothetical `firstMate` one.
const IDENTITY_CHANNELS = [
  "firstMate:rename", "firstMate:reset", "firstMate:pane-reset",
  "firstMate:pin", "firstMate:unpin",
];

test("the preload bridge exposes exactly seventeen firstMate invoke channels and two push subscriptions", () => {
  const preload = src("preload.js");
  // The firstMate bridge only: the app bridge after it has find's own
  // `find:update` subscription (#703).
  const body = preload.slice(preload.indexOf('exposeInMainWorld("firstMate"'), preload.indexOf('exposeInMainWorld("app"'));
  assert.ok(body.includes("firstMate:send"));
  const invoked = [...body.matchAll(/ipcRenderer\.invoke\("(firstMate:[^"]+)"/g)].map((m) => m[1]).sort();
  assert.deepEqual(invoked,
    ["firstMate:cancel", "firstMate:conversation", "firstMate:conversations",
      "firstMate:file", "firstMate:font", "firstMate:handoff", "firstMate:list",
      "firstMate:new", "firstMate:open-in-iterm", "firstMate:pop", "firstMate:restore-saved", "firstMate:resume", "firstMate:save", "firstMate:send", "firstMate:set-repo", "firstMate:set-tier",
      "firstMate:tier"]);
  const listened = [...body.matchAll(/ipcRenderer\.on\("([^"]+)"/g)].map((m) => m[1]).sort();
  assert.deepEqual(listened, ["firstMate:stream", "firstMate:update"]);
  // None of a rename/reset/pin control's channels exist on this bridge.
  for (const c of IDENTITY_CHANNELS) assert.ok(!body.includes(`"${c}"`), `${c} is not on the firstMate bridge`);
  // Every invoke channel is handled in main, and nowhere else.
  const main = src("main.js");
  for (const c of invoked) assert.match(main, new RegExp(`ipcMain\\.handle\\("${c}"`), c);
});

// Issue #819: Continue in terminal. The renderer sends no path, command or
// transcript: the live conversation's channel takes nothing, a saved chat's
// its id only. Main builds the command, writes the file 0600 and copies it.
test("firstMate:handoff takes nothing, savedChat:handoff a String id; main copies the command and writes the file 0600", () => {
  const preload = src("preload.js");
  const body = preload.slice(preload.indexOf('exposeInMainWorld("firstMate"'), preload.indexOf('exposeInMainWorld("app"'));
  assert.match(body, /handoff: \(\) => ipcRenderer\.invoke\("firstMate:handoff"\),/);
  assert.match(preload, /savedChatHandoff: \(id\) => ipcRenderer\.invoke\("savedChat:handoff", String\(id\)\),/);
  assert.equal([...preload.matchAll(/"savedChat:handoff"/g)].length, 1);
  const main = src("main.js");
  assert.match(main, /ipcMain\.handle\("firstMate:handoff", \(\) => \{\s*const chat = firstMate\.handoffPayload\(\);/);
  assert.match(main, /ipcMain\.handle\("savedChat:handoff", async \(_e, id\) => \{\s*const r = await fetchSavedChat\(String\(id\)\);/);
  // The cwd is the conversation's (#960): the live one's stored cwd from handoffPayload, a saved chat's clone.
  assert.match(main, /const cwd = chat\.cwd \|\| savedChatCwd\(chat\);\s*const \{ resumable \} = resumability\(chat, cwd\);/);
  assert.match(main, /handoffPlan\(chat, \{ cwd, dir:/);
  assert.match(main, /fs\.writeFileSync\(plan\.file, plan\.text, \{ mode: 0o600 \}\);\s*fs\.chmodSync\(plan\.file, 0o600\);/);
  assert.match(main, /path\.join\(app\.getPath\("userData"\), "first-mate", "handoff"\)/);
  assert.match(main, /clipboard\.writeText\(plan\.command\);/);
  for (const f of ["first-mate.jsx", "saved-chat.jsx", "index.jsx"]) {
    assert.doesNotMatch(src("renderer", f), /first-mate-handoff|--resume|--fork-session|\$\(cat/, `${f} builds no command`);
  }
});

// Issue #366: the terminal font is a no-argument read; main does the reading.
test("firstMate:font takes nothing from the renderer and is answered by readItermFont in main", () => {
  const preload = src("preload.js");
  const body = preload.slice(preload.indexOf('exposeInMainWorld("firstMate"'), preload.indexOf('exposeInMainWorld("app"'));
  assert.match(body, /font: \(\) => ipcRenderer\.invoke\("firstMate:font"\),/);
  assert.match(src("main.js"), /ipcMain\.handle\("firstMate:font", \(\) => \(itermFont \|\|= readItermFont\(\)\)\)/);
  for (const f of ["first-mate.jsx", "index.jsx"]) {
    const r = src("renderer", f);
    assert.doesNotMatch(r, /child_process|execFile|defaults export|com\.googlecode\.iterm2/, `${f} never reads the defaults itself`);
  }
});

test("firstMate:send and firstMate:file take a string/number and nothing else — no model, tier source or argv", () => {
  const preload = src("preload.js");
  const body = preload.slice(preload.indexOf('exposeInMainWorld("firstMate"'), preload.indexOf('exposeInMainWorld("app"'));
  // #368 added pasted images, as plain `{ media_type, data }` only.
  assert.match(body, /send: \(text, images\) => ipcRenderer\.invoke\("firstMate:send", String\(text\),/);
  // #577: and the card's repo pick in All repos, a string main checks against GET /repos.
  assert.match(body, /file: \(tier, repo\) => ipcRenderer\.invoke\("firstMate:file", Number\(tier\), repo \? String\(repo\) : null\)/);
  assert.match(body, /startNew: \(\) => ipcRenderer\.invoke\("firstMate:new"\)/);
  // #601: back to the previous conversation, no arguments at all.
  assert.match(body, /pop: \(\) => ipcRenderer\.invoke\("firstMate:pop"\)/);
  // #820: open in Claude Code in iTerm2, no arguments at all: which session,
  // binary and directory are main's; main hands it straight to the manager.
  assert.match(body, /openInIterm: \(\) => ipcRenderer\.invoke\("firstMate:open-in-iterm"\),/);
  assert.match(src("main.js"), /ipcMain\.handle\("firstMate:open-in-iterm", async \(\) => \{\s*const r = await firstMate\.openInIterm\(\)/);
  assert.match(src("main.js"), /iterm: detectIterm\(\),/, "detected once at startup in main");
  for (const forbidden of ["command", "cwd", "allowedTools", "argv", "settings", "binary", "spawn"]) {
    assert.ok(!new RegExp(forbidden, "i").test(body), `the bridge has no ${forbidden} parameter`);
  }
});

// Issue #231: the tier calls carry tier numbers and a duration key, never a
// model or an effort (#100's rule); main validates the numbers.
// Issue #265: the two History channels are plain reads — a string/number id
// in, nothing else. No model, effort or other argv rides along.
test("the History bridge methods take only a string/number id and nothing else", () => {
  const preload = src("preload.js");
  const body = preload.slice(preload.indexOf('exposeInMainWorld("firstMate"'), preload.indexOf('exposeInMainWorld("app"'));
  assert.match(body, /conversations: \(\) => ipcRenderer\.invoke\("firstMate:conversations"\)/);
  assert.match(body, /conversation: \(id\) => ipcRenderer\.invoke\("firstMate:conversation", String\(id\)\)/);
  for (const m of body.matchAll(/^\s*\w+: \(([^)]*)\) => ipcRenderer\.invoke\(([^\n]*)\)/gm)) {
    assert.doesNotMatch(m[1], /model|effort/i, m[0]);
    assert.doesNotMatch(m[2], /model|effort/i, m[0]);
  }
  const main = src("main.js");
  assert.match(main, /ipcMain\.handle\("firstMate:conversations", \(\) => firstMate\.listConversations\(\)\)/);
  assert.match(main, /ipcMain\.handle\("firstMate:conversation", \(_e, id\) => firstMate\.getConversation\(String\(id\)\)\)/);
});

// Issue #486: resume takes a conversation id, as a string, and nothing else;
// main hands it straight to the manager, which matches it as a number.
test("firstMate:resume takes only a string/number conversation id, and main passes it to resumeConversation", () => {
  const preload = src("preload.js");
  const body = preload.slice(preload.indexOf('exposeInMainWorld("firstMate"'), preload.indexOf('exposeInMainWorld("app"'));
  assert.match(body, /resume: \(id\) => ipcRenderer\.invoke\("firstMate:resume", String\(id\)\),/);
  const main = src("main.js");
  assert.match(main, /ipcMain\.handle\("firstMate:resume", \(_e, id\) => \{\s*const r = firstMate\.resumeConversation\(String\(id\)\)/);
  const dir = fs.mkdtempSync(path.join(require("node:os").tmpdir(), "first-mate-ipc-test-"));
  const m = new FirstMateManager({ dir, command: "true" }).init();
  m.session.conversations = [{ id: 3, started_at: 1, claude_session_id: "sess-3", messages: [{ role: "user", text: "q", at: 1 }] }];
  for (const raw of [null, "", "abc", "3; rm -rf", 99]) assert.equal(m.resumeConversation(String(raw)).ok, false, String(raw));
  assert.equal(m.resumeConversation(String(3)).ok, true);
  assert.equal(m.session.claude_session_id, "sess-3");
  m.stop();
});

test("the tier bridge methods pass only Number(tier)", () => {
  const preload = src("preload.js");
  const body = preload.slice(preload.indexOf('exposeInMainWorld("firstMate"'), preload.indexOf('exposeInMainWorld("app"'));
  assert.match(body, /tierState: \(\) => ipcRenderer\.invoke\("firstMate:tier"\),/);
  assert.match(body, /setTier: \(tier\) => ipcRenderer\.invoke\("firstMate:set-tier", Number\(tier\)\),/);
  // No bridge method takes or sends a model or an effort.
  for (const m of body.matchAll(/^\s*\w+: \(([^)]*)\) => ipcRenderer\.invoke\(([^\n]*)\)/gm)) {
    assert.doesNotMatch(m[1], /model|effort/i, m[0]);
    assert.doesNotMatch(m[2], /model|effort/i, m[0]);
  }
  const main = src("main.js");
  assert.match(main, /ipcMain\.handle\("firstMate:set-tier", \(_e, tier\) => \{\s*const r = firstMate\.setStandingTier\(Number\(tier\)\)/);
  assert.match(main, /configFile: configPath\(app\.getPath\("userData"\)\)/);
});

test("main's manager rejects what Number() makes of a bad tier", () => {
  const dir = fs.mkdtempSync(path.join(require("node:os").tmpdir(), "first-mate-ipc-test-"));
  const configFile = path.join(dir, "agent-army.config.json");
  const m = new FirstMateManager({ dir, configFile, command: "true" }).init();
  for (const raw of [0, 11, 2.5, "abc", "", null, "8; rm -rf"]) {
    assert.equal(m.setStandingTier(Number(raw)).ok, false, String(raw));
  }
  assert.ok(!fs.existsSync(configFile));
  const ok = m.setStandingTier(Number("6"));
  assert.equal(ok.ok, true);
  assert.equal(ok.standing, 6);
});

test("the File button's POST is built and sent from main, never the renderer", () => {
  const main = src("main.js");
  assert.match(main, /submitIntakeFn: \(ask, repo\) => submitIntake\(ask, undefined, repo \? \{ repo \} : \{\}\)/,
    "main wires the engine's real submitIntake in, with the repo to file in (#577)");
  const firstMateSrc = src("first-mate.js");
  // The Tier: header — built once, in the manager, not in the renderer.
  assert.match(firstMateSrc, /Tier: \$\{t\} \(\$\{resolved\.model\} \$\{resolved\.effort\}\) — prescribed by the user; do not re-judge/);
  const rendererSrc = src(path.join("renderer", "first-mate.jsx"));
  assert.doesNotMatch(rendererSrc, /require\(/, "the renderer component makes no direct engine call");
  assert.doesNotMatch(rendererSrc, /fetch\(/, "no fetch in the drawing component");
});

// ---------------------------------------------------------------------------
// Criterion 3, the renderer side: no rename, reset or pin control is ever
// invoked from the First mate component or its screen wrapper.
// ---------------------------------------------------------------------------

test("first-mate.jsx never calls window.chats.* or a rename/reset/pin-shaped method", () => {
  const rendererSrc = src(path.join("renderer", "first-mate.jsx"));
  assert.doesNotMatch(rendererSrc, /window\.chats/, "the drawing component reaches no bridge at all");
  for (const verb of ["rename", "resetPane", "\\bpin\\b", "unpin"]) {
    assert.doesNotMatch(rendererSrc, new RegExp(verb, "i"), `no ${verb} in first-mate.jsx`);
  }
  const indexSrc = src(path.join("renderer", "index.jsx"));
  const stripSrc = indexSrc.slice(indexSrc.indexOf("export const FirstMateStrip"),
    indexSrc.indexOf("// The explorer's data"));
  assert.doesNotMatch(stripSrc, /window\.chats/, "the strip's own wrapper reaches no chats bridge either");
  for (const c of IDENTITY_CHANNELS) assert.ok(!stripSrc.includes(c), c);
});

test("first-mate.jsx draws only — no fetch, no require, no timers of its own", () => {
  const rendererSrc = src(path.join("renderer", "first-mate.jsx"));
  assert.doesNotMatch(rendererSrc, /\brequire\(/);
  assert.doesNotMatch(rendererSrc, /\bfetch\(/);
  assert.doesNotMatch(rendererSrc, /setTimeout|setInterval/);
});

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

const snap = (over = {}) => ({
  version: 1, error: null, session_generation: 1, has_session: false, running: false, busy: false,
  recovery: null, notice: null, streaming: "", history: [], file: null, file_busy: false, file_error: null,
  filed: [], turns_since_filed: 0, unfiled_hint: false, ...over,
});

test("ModelBar draws no Override control and no Clear button (#1751)", () => {
  const { ModelBar } = require("../dist/first-mate.cjs");
  const html = draw(React.createElement(ModelBar, { tier: { tier: 5, model: "opus", effort: "medium", source: "standing" } }));
  assert.doesNotMatch(html, /Override/);
  assert.doesNotMatch(html, /fmModelClear/);
  assert.match(html, /Opus medium/);
});

test("the strip draws exactly one new-conversation button, no rename input and no pin control", () => {
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap({ history: [{ role: "user", text: "hi", at: 1 }, { role: "assistant", text: "hello", at: 2 }] }),
    draft: "", onDraft: () => {}, onSend: () => {}, onFile: () => {}
  }));
  assert.match(html, /class="firstMate"/);
  assert.doesNotMatch(html, /class="chatName"/, "no rename input");
  assert.equal([...html.matchAll(/class="fmNewConversation"/g)].length, 1, "one new-conversation control");
  assert.match(html, />Start new conversation</);
  assert.doesNotMatch(html, /pin/i, "no pin control anywhere in the markup");
  // Multi-line textarea (#758).
  assert.match(html, /<textarea class="fmInput"/);
});

test("the File: card renders the ask, a 1-10 tier selector defaulted to the proposed tier, and one File button", () => {
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap({ file: { tier: 7, ask: "wire up the retry button", turn_id: "t1" } }),
    draft: "", onDraft: () => {}, onSend: () => {}, onFile: () => {}
  }));
  assert.match(html, /wire up the retry button/);
  assert.equal([...html.matchAll(/<option[^>]*value="(\d+)"/g)].map((m) => m[1]).join(","),
    "1,2,3,4,5,6,7,8,9,10");
  assert.match(html, /value="7" selected/);
  assert.equal([...html.matchAll(/class="fmFileButton"/g)].length, 1);
});

test("the unfiled-turn hint is a line of text with no action of its own (the move to Chats went with the Chats tab, #634)", () => {
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap({ unfiled_hint: true }),
    draft: "", onDraft: () => {}, onSend: () => {}, onFile: () => {}
  }));
  assert.match(html, /class="fmHint">Long thread\. Ask First mate to file it\.</);
  assert.doesNotMatch(html, /move to Chats|fmMoveToChats/i);
});

test("older exchanges collapse under a count; a long reply renders in full", () => {
  const history = [];
  for (let i = 0; i < 6; i += 1) {
    history.push({ role: "user", text: `q${i}`, at: i });
    history.push({ role: "assistant", text: `a${i}`, at: i });
  }
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap({ history }),
    draft: "", onDraft: () => {}, onSend: () => {}, onFile: () => {}
  }));
  assert.match(html, /earlier exchange/);
  assert.doesNotMatch(html, />q0<|>q1<|>q2</);

  const longReply = Array.from({ length: 90 }, (_, i) => `word${i}`).join(" ");
  const html2 = draw(React.createElement(FirstMate, {
    snapshot: snap({ history: [{ role: "user", text: "go on", at: 1 }, { role: "assistant", text: longReply, at: 2 }] }),
    draft: "", onDraft: () => {}, onSend: () => {}, onFile: () => {}
  }));
  assert.doesNotMatch(html2, /truncated/);
  assert.match(html2, /word0 /);
  assert.match(html2, /word89/);
});

test("the earlier-exchanges button is the first child of the scrolling transcript (#285)", () => {
  const history = [];
  for (let i = 0; i < 6; i += 1) {
    history.push({ role: "user", text: `q${i}`, at: i });
    history.push({ role: "assistant", text: `a${i}`, at: i });
  }
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap({ history }),
    draft: "", onDraft: () => {}, onSend: () => {}, onFile: () => {}
  }));
  assert.match(html, /<div class="fmTranscript"><button class="fmOlder"/);
});

// ---------------------------------------------------------------------------
// Escape interrupts a busy turn through firstMate:cancel (#815).
// ---------------------------------------------------------------------------

test("Escape cancels only while busy, inside the strip or with focus dropped to the body", () => {
  const { escapeCancels } = require("../dist/first-mate.cjs");
  const base = { key: "Escape", busy: true, defaultPrevented: false, inStrip: true, onBody: false };
  assert.equal(escapeCancels(base), true);
  assert.equal(escapeCancels({ ...base, inStrip: false, onBody: true }), true);
  assert.equal(escapeCancels({ ...base, busy: false }), false);
  assert.equal(escapeCancels({ ...base, key: "Enter" }), false);
  assert.equal(escapeCancels({ ...base, defaultPrevented: true }), false);
  assert.equal(escapeCancels({ ...base, inStrip: false, onBody: false }), false);
});

test("the strip's Escape handler and Stop reach window.firstMate.cancel, and leave the draft alone", () => {
  const src = require("node:fs").readFileSync(require("node:path").join(__dirname, "..", "src", "renderer", "index.jsx"), "utf8");
  const body = src.slice(src.indexOf("const onStop"), src.indexOf("return <FirstMate"));
  assert.match(body, /window\.firstMate\.cancel\(\)/);
  assert.match(body, /escapeCancels\(/);
  assert.match(body, /if \(!busyNow\) return/);
  assert.doesNotMatch(body, /setDraft|setAttachments/);
  assert.match(src, /onStop=\{onStop\}/);
});
