// The saved-chat view (#598), src/renderer/saved-chat.jsx: the summary, the
// capped read-only transcript in First mate's terminal look, "Put it in First
// mate →" and Delete with its confirm step, and the loading and error states.
// Drawing with renderToStaticMarkup like test/first-mate-view.test.js; the
// buttons are found in SavedChatView's element tree and their onClick called.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const { DISPLAY_CAP, savedChatView } = require("../src/saved-chats");
const { SavedChat, SavedChatView, summaryRows, runAction } = require("../dist/saved-chat.cjs");

const msg = (role, text, at) => ({ role, text, at });
const convo = (n) => Array.from({ length: n }, (_, i) => msg(i % 2 ? "assistant" : "user", `message ${i}`, 1000 + i));
const draw = (type, props) => renderToStaticMarkup(React.createElement(type, props));
const noop = () => {};
const viewProps = (over = {}) => ({ restore: null, del: null, onRestore: noop, onAskDelete: noop,
  onDelete: noop, onKeep: noop, ...over });

// A chat as main hands it over: savedChatView over a transcript, resumable
// or not by whether a session file exists under a fake home.
const shaped = (n, over = {}) => savedChatView({ id: "c1", title: "Plan the week", savedAt: "2026-09-27T10:00:00Z",
  startedAt: "2026-09-27T09:00:00Z", repo: null, tier: 8, savedFrom: "laptop", claudeSessionId: "gone",
  messages: convo(n), ...over }, "/nowhere", { home: "/nonexistent" });

/** Every <button> in an element tree (not entering components), as `{ text, props }`. */
const buttons = (el, out = []) => {
  if (Array.isArray(el)) { el.forEach((c) => buttons(c, out)); return out; }
  if (!el || typeof el !== "object" || !el.props) return out;
  if (el.type === "button") {
    const text = [].concat(el.props.children).filter((c) => typeof c === "string").join("").trim();
    out.push({ text, props: el.props });
  }
  if (typeof el.type !== "function") buttons(el.props.children, out);
  return out;
};
const button = (el, text) => buttons(el).find((b) => b.text === text);

// ---- summary --------------------------------------------------------------------

test("summaryRows: saved, started, repo or All repos, tier, turns and messages, saved from, resumes here", () => {
  const rows = Object.fromEntries(summaryRows(shaped(6)));
  assert.equal(rows.Saved, new Date("2026-09-27T10:00:00Z").toLocaleString());
  assert.equal(rows.Started, new Date("2026-09-27T09:00:00Z").toLocaleString());
  assert.equal(rows.Repo, "All repos");
  assert.equal(rows.Tier, "8");
  assert.equal(rows.Turns, "3 (6 messages)");
  assert.equal(rows["Saved from"], "laptop");
  assert.match(rows["Resumes here"], /^no, saved on another machine: restoring starts a fresh session/);

  const here = Object.fromEntries(summaryRows({ repo: "o/r", tier: 3, resumable: true, summary: { turns: 2, messages: 4 } }));
  assert.equal(here.Repo, "o/r");
  assert.equal(here.Tier, "3");
  assert.equal(here.Turns, "2 (4 messages)");
  assert.match(here["Resumes here"], /^yes, its Claude session is on this machine/);

  const noId = Object.fromEntries(summaryRows(shaped(2, { claudeSessionId: null })));
  assert.match(noId["Resumes here"], /^no, no session id:/);
  const blank = Object.fromEntries(summaryRows({}));
  assert.equal(blank.Saved, "—");
  assert.equal(blank.Tier, "—");
  assert.equal(blank["Saved from"], "—");
});

test("the view draws every summary row beside the title", () => {
  const chat = shaped(6);
  const html = draw(SavedChatView, viewProps({ result: { chat } }));
  assert.match(html, /class="scTitle">Plan the week</);
  for (const [k, v] of summaryRows(chat)) {
    assert.ok(html.includes(`<span class="scKey">${k}</span>`), `row ${k}`);
    assert.ok(html.includes(v.replace(/&/g, "&amp;")), `value of ${k}`);
  }
});

// ---- the transcript and its cap ------------------------------------------------------

test(`a chat over ${DISPLAY_CAP} messages shows only the newest ${DISPLAY_CAP} in First mate's rows and says how many are hidden`, () => {
  const chat = shaped(DISPLAY_CAP + 4);
  assert.equal(chat.window.hidden, 4);
  const html = draw(SavedChatView, viewProps({ result: { chat } }));
  assert.match(html, /class="scHidden">4 earlier messages not shown</);
  assert.match(html, /class="fmTranscript scTranscript"/);
  assert.equal((html.match(/class="fmRow fmRow-/g) || []).length, DISPLAY_CAP);
  assert.equal((html.match(/class="fmRow fmRow-user"/g) || []).length, DISPLAY_CAP / 2);
  assert.equal((html.match(/class="fmRow fmRow-assistant"/g) || []).length, DISPLAY_CAP / 2);
  assert.doesNotMatch(html, />message 3</, "the hidden, oldest messages are not drawn");
  assert.match(html, />message 4</);
  assert.match(html, new RegExp(`>message ${DISPLAY_CAP + 3}<`));
});

test("one hidden message is said in the singular; none hidden draws no line", () => {
  assert.match(draw(SavedChatView, viewProps({ result: { chat: shaped(DISPLAY_CAP + 1) } })), /1 earlier message not shown/);
  const html = draw(SavedChatView, viewProps({ result: { chat: shaped(3) } }));
  assert.doesNotMatch(html, /scHidden|not shown/);
  assert.equal((html.match(/class="fmRow fmRow-/g) || []).length, 3);
});

test("a chat without a window (not shaped by main) draws all its messages", () => {
  const html = draw(SavedChatView, viewProps({ result: { chat: { id: "c", title: "t", messages: convo(2) } } }));
  assert.equal((html.match(/class="fmRow fmRow-/g) || []).length, 2);
});

// ---- restore and delete ---------------------------------------------------------------

test("Put it in First mate → is a button that calls onRestore, disabled while restoring, and says how it went", () => {
  const chat = shaped(4);
  let restored = 0;
  const el = SavedChatView(viewProps({ result: { chat }, onRestore: () => { restored++; } }));
  const put = button(el, "Put it in First mate →");
  assert.ok(put, "the restore button is there");
  assert.equal(put.props.className, "scRestore");
  assert.equal(put.props.disabled, false);
  put.props.onClick();
  assert.equal(restored, 1);

  const busy = SavedChatView(viewProps({ result: { chat }, restore: { busy: true } }));
  assert.equal(button(busy, "Put it in First mate →").props.disabled, true);
  assert.match(draw(SavedChatView, viewProps({ result: { chat }, restore: { ok: true } })),
    /Restored — the First mate strip has it/);
  assert.match(draw(SavedChatView, viewProps({ result: { chat }, restore: { error: "engine down" } })),
    /Not restored: engine down/);
});

test("Delete asks first: the first click only opens the confirm, Delete there deletes, Keep backs out", () => {
  const chat = shaped(4);
  const calls = [];
  const on = { onAskDelete: () => calls.push("ask"), onDelete: () => calls.push("delete"), onKeep: () => calls.push("keep") };

  const idle = SavedChatView(viewProps({ result: { chat }, ...on }));
  assert.doesNotMatch(draw(SavedChatView, viewProps({ result: { chat } })), /Delete this saved chat\?/);
  assert.deepEqual(buttons(idle).map((b) => b.text), ["Put it in First mate →", "Delete"]);
  button(idle, "Delete").props.onClick();
  assert.deepEqual(calls, ["ask"], "the first Delete deletes nothing");

  const confirm = SavedChatView(viewProps({ result: { chat }, del: { confirm: true }, ...on }));
  assert.match(draw(SavedChatView, viewProps({ result: { chat }, del: { confirm: true } })), /Delete this saved chat\?/);
  assert.deepEqual(buttons(confirm).map((b) => b.text), ["Put it in First mate →", "Delete", "Keep"]);
  button(confirm, "Delete").props.onClick();
  button(confirm, "Keep").props.onClick();
  assert.deepEqual(calls, ["ask", "delete", "keep"]);

  const busy = SavedChatView(viewProps({ result: { chat }, del: { busy: true } }));
  assert.equal(button(busy, "Delete").props.disabled, true);
  assert.match(draw(SavedChatView, viewProps({ result: { chat }, del: { error: "no saved chat c1" } })),
    /Not deleted: no saved chat c1/);
});

test("runAction passes the chat id, goes busy then ok, and turns a refusal, a throw or no answer into an error", async () => {
  const run = async (fn) => { const seen = []; const r = await runAction(fn, "c1", (s) => seen.push(s)); return { seen, r }; };
  const ids = [];
  const ok = await run(async (id) => { ids.push(id); return { ok: true }; });
  assert.deepEqual(ids, ["c1"]);
  assert.deepEqual(ok.seen, [{ busy: true }, { ok: true }]);
  assert.deepEqual((await run(async () => ({ ok: false, error: "engine said 404" }))).r, { error: "engine said 404" });
  assert.deepEqual((await run(async () => { throw new Error("boom"); })).r, { error: "IPC failed: boom" });
  assert.deepEqual((await run(async () => undefined)).r, { error: "no response from the main process" });
});

test("SavedChat (the stateful view) starts with neither action run: restore enabled, Delete not yet confirming", () => {
  const html = draw(SavedChat, { result: { chat: shaped(DISPLAY_CAP + 2) }, onRestore: noop, onDelete: noop });
  assert.match(html, /<button class="scRestore">Put it in First mate →<\/button>/);
  assert.match(html, /<button class="scDelete">Delete<\/button>/);
  assert.doesNotMatch(html, /Delete this saved chat\?|Restored|Not restored|Not deleted/);
  assert.match(html, /2 earlier messages not shown/);
});

// ---- Continue in terminal (#819) ------------------------------------------------------

test("Continue in terminal sits beside Put it in First mate →, its title follows Resumes here, and it says how it went", () => {
  const elsewhere = draw(SavedChatView, viewProps({ result: { chat: shaped(4) } }));
  assert.match(elsewhere, /Put it in First mate →<\/button><button class="scHandoff" title="Copy a command that starts a new Claude Code session with its transcript in a terminal">Continue in terminal<\/button>/);
  const here = draw(SavedChatView, viewProps({ result: { chat: { ...shaped(4), resumable: true } } }));
  assert.match(here, /class="scHandoff" title="Copy a command that resumes a fork of its Claude session in a terminal"/);

  let pressed = 0;
  const el = SavedChatView(viewProps({ result: { chat: shaped(4) }, onHandoff: () => { pressed++; } }));
  const find = (n) => (Array.isArray(n) ? n.map(find).find(Boolean)
    : n && n.props ? (typeof n.props.onHandoff === "function" ? n : find(n.props.children)) : null);
  const hb = find(el);
  assert.ok(hb, "the hand-off button is drawn");
  hb.props.onHandoff();
  assert.equal(pressed, 1);

  const busy = draw(SavedChatView, viewProps({ result: { chat: shaped(4) }, handoff: { busy: true } }));
  assert.match(busy, /<button class="scHandoff" disabled=""/);
  const seeded = draw(SavedChatView, viewProps({ result: { chat: shaped(4) },
    handoff: { ok: true, mode: "seed", command: "cd '/r' && claude \"$(cat '/u/h/c1.md')\"" } }));
  assert.match(seeded, /Copied a command to start a new session with this transcript\. Paste it in a terminal\./);
  assert.match(seeded, /<code class="fmHandoffCommand">cd &#x27;\/r&#x27; &amp;&amp; claude &quot;\$\(cat &#x27;\/u\/h\/c1\.md&#x27;\)&quot;<\/code>/);
  const resumed = draw(SavedChatView, viewProps({ result: { chat: shaped(4) },
    handoff: { ok: true, mode: "resume", command: "cd '/r' && claude --resume s --fork-session" } }));
  assert.match(resumed, /Copied a command to resume this conversation\. Paste it in a terminal\./);
  assert.match(draw(SavedChatView, viewProps({ result: { chat: shaped(4) }, handoff: { error: "engine down" } })),
    /class="scError"[^>]*>Not copied: engine down</);
});

// ---- loading and errors ---------------------------------------------------------------

test("loading, an error, and no chat each draw their state and no actions", () => {
  for (const type of [SavedChat, SavedChatView]) {
    const loading = draw(type, viewProps({ result: { loading: true } }));
    assert.match(loading, /class="nmEmpty">loading…</);
    assert.match(draw(type, viewProps({ result: null })), /loading…/);
    const err = draw(type, viewProps({ result: { error: "no saved chat x" } }));
    assert.match(err, /class="err">no saved chat x</);
    assert.match(draw(type, viewProps({ result: {} })), /class="err">no saved chat</);
    for (const html of [loading, err]) assert.doesNotMatch(html, /<button/);
  }
});

test("the view is drawing only: no fs, net or window of its own", () => {
  const code = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "saved-chat.jsx"), "utf8").replace(/^\s*\/\/.*$/gm, "");
  assert.doesNotMatch(code, /require\(|node:|fetch\(|window\./);
});
