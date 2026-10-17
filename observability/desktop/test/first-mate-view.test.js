// First mate's view. Drawing only, renderToStaticMarkup like
// test/issues.test.js.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const { FirstMate, isAtBottom } = require("../dist/first-mate.cjs");

const draw = (el) => renderToStaticMarkup(el);

const snap = (over = {}) => ({
  version: 1, error: null, session_generation: 1, has_session: false, running: false, busy: false,
  recovery: null, notice: null, streaming: "", history: [], file: null, file_busy: false, file_error: null,
  filed: [{ intake_id: "intake-1", tier: 5, ask: "wire up the retry button" }],
  turns_since_filed: 0, unfiled_hint: false, ...over,
});

// ---------------------------------------------------------------------------
// Acceptance: "No First mate row or card renders a link whose target contains
// /intake/ or the engine URL."
// ---------------------------------------------------------------------------

test("first-mate.jsx never builds an /intake/ or engine-url link itself", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "first-mate.jsx"), "utf8");
  assert.doesNotMatch(src, /\/intake\//);
});

// ---------------------------------------------------------------------------
// isAtBottom (issue #235): the pure stick-to-bottom decision. A new/streaming
// reply should pull the transcript back down only while the user is already
// at (or within a few px of) the bottom; once they've scrolled up, it must
// not jump back down under them.
// ---------------------------------------------------------------------------

test("isAtBottom: flush with the bottom, or within slop, sticks", () => {
  assert.equal(isAtBottom(0), true);
  assert.equal(isAtBottom(3), true);
});

test("isAtBottom: scrolled up past the slop does not stick", () => {
  assert.equal(isAtBottom(5), false);
  assert.equal(isAtBottom(400), false);
});

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

test("a snapshot with filed entries renders no Filed section (#402)", () => {
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap(), onOpenTicket: () => {},
    draft: "", onDraft: () => {}, onSend: () => {}, onFile: () => {},
  }));
  assert.doesNotMatch(html, />Filed</);
  assert.doesNotMatch(html, /fmFiled/);
});

// ---------------------------------------------------------------------------
// First mate's own model (issue #240, part 2 of #226): the effective model,
// and main's notices. The strip's Override… button and the timed override
// it set were removed (#1751); there is no per-session override any more.
// ---------------------------------------------------------------------------

const strip = (tier, over = {}) => draw(React.createElement(FirstMate, {
  snapshot: snap({ filed: [], tier, ...over }),
  draft: "", onDraft: () => {}, onSend: () => {}, onFile: () => {},
}));
const walk = (el, out = []) => {
  if (!el || typeof el !== "object") return out;
  if (Array.isArray(el)) { for (const c of el) walk(c, out); return out; }
  out.push(el);
  if (el.props) walk(el.props.children, out);
  return out;
};

test("the strip draws no model line, and no Override control (#100107)", () => {
  const html = strip({ tier: 5, model: "opus", effort: "medium", source: "fallback" });
  assert.doesNotMatch(html, /class="fmModel|Opus medium|Fable-limit fallback|Override/);
});

test("the strip draws main's tier notices: the uncached re-read, and the Fable-limit fallback's reason", () => {
  const switched = strip({ tier: 5, model: "opus", effort: "medium", source: "standing" },
    { tier_notice: "Switched to tier 5 (opus medium): this turn re-reads the conversation uncached on the new model." });
  assert.match(switched, /class="fmNotice fmTierNotice">Switched to tier 5 \(opus medium\): this turn re-reads the conversation uncached on the new model\./);
  const fell = strip({ tier: 5, model: "opus", effort: "medium", source: "fallback" },
    { tier_notice: "Fable limit reached; this turn was retried at tier 5 (opus medium). Your saved tier is unchanged." });
  assert.match(fell, /class="fmNotice fmTierNotice">Fable limit reached; this turn was retried at tier 5 \(opus medium\)\. Your saved tier is unchanged\./);
});

// ---------------------------------------------------------------------------
// The History control (#265): a list panel and a read-only conversation
// panel, both swapped in for the transcript area in place — same header,
// same fixed width — never full-screen or a modal.
// ---------------------------------------------------------------------------

test("the strip draws no Copy, History or Continue in terminal control, live or on a past conversation", () => {
  const history = [{ role: "user", text: "hi", at: 1 }];
  const live = draw(React.createElement(FirstMate, {
    snapshot: snap({ history, can_handoff: true }), draft: "", onDraft: () => {}, onSend: () => {}, onFile: () => {},
  }));
  const past = draw(React.createElement(FirstMate, { snapshot: snap(), viewingConversation: { id: 1, messages: history } }));
  for (const html of [live, past]) assert.doesNotMatch(html, /fmCopyButton|fmHistoryButton|fmHandoffButton/);
  assert.match(live, /<textarea class="fmInput"/);
});

test("historyOpen with historyList renders each entry's start time and first message, and nothing else interactive", () => {
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap({ tier: { tier: 5, model: "opus", effort: "medium", source: "standing" } }),
    historyOpen: true,
    historyList: [
      { id: "c1", started_at: 1700000000000, first_message: "opening message one" },
      { id: "c2", started_at: 1700000100000, first_message: "opening message two" },
    ],
    onSelectConversation: () => {}, onCloseHistory: () => {},
    draft: "", onDraft: () => {}, onSend: () => {}, onFile: () => {},
  }));
  assert.equal([...html.matchAll(/class="fmHistoryRow"/g)].length, 2);
  assert.match(html, /opening message one/);
  assert.match(html, /opening message two/);
  assert.doesNotMatch(html, /class="fmInput"/);
  assert.doesNotMatch(html, /class="fmSend"/);
  assert.doesNotMatch(html, /fmFileCard|fmFileButton/);
  assert.doesNotMatch(html, /fmTierSelect|fmModelForm/);
  assert.doesNotMatch(html, /fmModelToggle|fmModelClear/);
  assert.doesNotMatch(html, /class="fmModel"/);
});

test("viewingConversation renders that conversation's rows read-only, with no input/send/File/ModelBar", () => {
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap({ tier: { tier: 5, model: "opus", effort: "medium", source: "standing" } }),
    viewingConversation: {
      id: "c1", started_at: 1700000000000,
      messages: [{ role: "user", text: "hello from the past", at: 1 }, { role: "assistant", text: "hi back", at: 2 }],
    },
    onCloseHistory: () => {},
    draft: "", onDraft: () => {}, onSend: () => {}, onFile: () => {},
  }));
  assert.match(html, /hello from the past/);
  assert.match(html, /hi back/);
  assert.doesNotMatch(html, /class="fmInput"/);
  assert.doesNotMatch(html, /class="fmSend"/);
  assert.doesNotMatch(html, /fmFileCard|fmFileButton/);
  assert.doesNotMatch(html, /class="fmModel"/);
});

test("onCloseHistory is wired to the Close/Back control, in both the list and the conversation view", () => {
  const { HistoryList, HistoryView } = require("../dist/first-mate.cjs");
  let closedList = 0;
  const listTree = HistoryList({ historyList: [], onSelectConversation: () => {}, onCloseHistory: () => { closedList += 1; } });
  walk(listTree).find((e) => e.type === "button" && e.props.className === "fmHistoryClose").props.onClick();
  assert.equal(closedList, 1);

  let closedView = 0;
  const viewTree = HistoryView({ viewingConversation: { messages: [] }, onCloseHistory: () => { closedView += 1; } });
  walk(viewTree).find((e) => e.type === "button" && e.props.className === "fmHistoryClose").props.onClick();
  assert.equal(closedView, 1);
});

// Issue #486: the conversation view's Resume control — live with a stored
// session id, disabled with a reason for a conversation archived before resume.
test("a resumable conversation draws an enabled Resume control that calls onResume with its id", () => {
  const conv = { id: 7, started_at: 1, resumable: true, messages: [{ role: "user", text: "old", at: 1 }] };
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap(), viewingConversation: conv, onCloseHistory: () => {}, onResume: () => {},
  }));
  assert.equal([...html.matchAll(/<button class="fmResume">Resume this conversation<\/button>/g)].length, 1);
  assert.doesNotMatch(html, /archived before resume existed/);
  const { HistoryView } = require("../dist/first-mate.cjs");
  const resumed = [];
  const btn = (tree) => walk(tree).find((e) => e.type === "button" && e.props.className === "fmResume");
  const tree = HistoryView({ viewingConversation: conv, onCloseHistory: () => {}, onResume: (id) => resumed.push(id) });
  const resumeEl = walk(tree).find((e) => e.props && e.props.viewingConversation === conv && e.props.onResume);
  const b = btn(resumeEl.type(resumeEl.props));
  assert.equal(b.props.disabled, false);
  b.props.onClick();
  assert.deepEqual(resumed, [7]);
});

test("a conversation with no stored session id draws the Resume control disabled, saying why", () => {
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap(), onCloseHistory: () => {}, onResume: () => { throw new Error("never called"); },
    viewingConversation: { id: 2, started_at: 1, resumable: false, messages: [{ role: "user", text: "legacy", at: 1 }] },
  }));
  assert.match(html, /<button class="fmResume" disabled="">Resume this conversation<\/button>/);
  assert.match(html, /archived before resume existed/);
  assert.match(html, /legacy/);
});

test("a refused resume shows its error next to the control", () => {
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap(), onCloseHistory: () => {}, onResume: () => {}, resumeError: "there is no past conversation 9",
    viewingConversation: { id: 9, started_at: 1, resumable: true, messages: [] },
  }));
  assert.match(html, /Not resumed: there is no past conversation 9/);
});

test("the strip wrapper resumes through firstMate:resume and then closes History", () => {
  const indexSrc = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "index.jsx"), "utf8");
  const body = indexSrc.slice(indexSrc.indexOf("const onResume"), indexSrc.indexOf("return <FirstMate"));
  assert.match(body, /window\.firstMate\.resume\(id\)/);
  assert.match(body, /onCloseHistory\(\);/);
});

// ---------------------------------------------------------------------------
// Thinking indicator (issue #307): busy with no streamed text yet.
// ---------------------------------------------------------------------------

const history = [{ role: "user", text: "hello" }];

test("thinking: busy with empty streaming renders the fmThinking row after the last exchange", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap({ busy: true, streaming: "", history }) }));
  assert.match(html, /class="fmThinking"/);
  assert.match(html, /aria-label="First mate is thinking"/);
  assert.ok(html.indexOf("fmThinking") > html.indexOf("hello"));
  assert.match(html, /<span class="fmMark" role="img" aria-label="First mate">⏺<\/span><div class="fmText"><span class="fmThinking"/);
});

test("thinking: busy with streaming text shows fmStreaming, not fmThinking", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap({ busy: true, streaming: "partial", history }) }));
  assert.doesNotMatch(html, /fmThinking/);
  assert.match(html, /fmStreaming/);
  assert.match(html, /partial/);
});

test("thinking: not busy renders no fmThinking", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap({ busy: false, history }) }));
  assert.doesNotMatch(html, /fmThinking/);
});

// ---------------------------------------------------------------------------
// The terminal look (issue #366): Claude Code's marks instead of name labels,
// the > prompt box, and the iTerm2 font on the strip.
// ---------------------------------------------------------------------------

const { terminalFont, TERMINAL_FAMILY, FALLBACK_MONO } = require("../dist/first-mate.cjs");

test("turns are drawn with Claude Code's marks: > for the user, ⏺ for a reply, no name labels", () => {
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap({ history: [{ role: "user", text: "hi there" }, { role: "assistant", text: "hello" }], streaming: "more" }),
    draft: "", onDraft: () => {}, onSend: () => {},
  }));
  assert.match(html, /<div class="fmRow fmRow-user"><span class="fmMark" role="img" aria-label="the user">&gt;<\/span><div class="fmText">hi there<\/div>/);
  assert.match(html, /<div class="fmRow fmRow-assistant"><span class="fmMark" role="img" aria-label="First mate">⏺<\/span><div class="fmText">hello<\/div>/);
  assert.match(html, /fmStreaming"><span class="fmMark"[^>]*>⏺<\/span><div class="fmText">more</);
  assert.doesNotMatch(html, /fmWho/);
  assert.match(html, /<div class="fmCompose"><span class="fmPrompt" aria-hidden="true">&gt;<\/span><textarea class="fmInput"/);
});

test("terminalFont: an iTerm2 font becomes the strip's --fm-mono/--fm-mono-size and a local() @font-face", () => {
  const t = terminalFont({ family: "MesloLGS-NF-Regular", size: 13.5, source: "iterm2" });
  assert.deepEqual(t.style, {
    "--fm-mono": `"${TERMINAL_FAMILY}", "MesloLGS-NF-Regular", ${FALLBACK_MONO}`,
    "--fm-mono-size": "13.5px",
  });
  assert.equal(t.face, `@font-face { font-family: "${TERMINAL_FAMILY}"; src: local("MesloLGS-NF-Regular"); }`);
  const html = draw(React.createElement(FirstMate, { snapshot: snap(), font: { family: "MesloLGS-NF-Regular", size: 13, source: "iterm2" } }));
  assert.match(html, /<aside class="firstMate" style="--fm-mono:[^"]*MesloLGS-NF-Regular[^"]*;--fm-mono-size:13px">/);
  assert.match(html, /<style>@font-face \{ font-family: "iTerm2 Normal Font"; src: local\("MesloLGS-NF-Regular"\); \}<\/style>/);
});

test("terminalFont: no font, the fallback, or an unsafe name leaves styles.css's fallback stack in place", () => {
  assert.deepEqual(terminalFont(null), { style: {}, face: null });
  assert.deepEqual(terminalFont({ family: null, size: 12, source: "fallback" }), { style: { "--fm-mono-size": "12px" }, face: null });
  assert.equal(terminalFont({ family: 'x"); } body { color: red', size: 12 }).face, null);
  const html = draw(React.createElement(FirstMate, { snapshot: snap() }));
  assert.doesNotMatch(html, /<style>/);
  assert.match(html, /<aside class="firstMate">/);
});

// ---------------------------------------------------------------------------
// Ticket references in replies (issue #374)
// ---------------------------------------------------------------------------

const { Row } = require("../dist/first-mate.cjs");

test("a reply's bare #356 and owner/repo#N render as links; abc#12 stays text", () => {
  const html = draw(React.createElement(Row, { row: { role: "assistant", text: "see #356, acme/widgets#7 and abc#12 or page.html#3 and # x" } }));
  assert.match(html, /see <a class="fmTicketRef" href="https:\/\/github.com\/codefrak-oss\/frakworks\/issues\/356">#356<\/a>, /);
  assert.match(html, /<a class="fmTicketRef" href="https:\/\/github.com\/acme\/widgets\/issues\/7">acme\/widgets#7<\/a>/);
  assert.equal((html.match(/<a /g) || []).length, 2);
  assert.match(html, /abc#12 or page.html#3 and # x/);
});

test("a full GitHub URL resolves to its repo; user rows are not linked", () => {
  const html = draw(React.createElement(Row, { row: { role: "assistant", text: "https://github.com/o/r/pull/9" } }));
  assert.match(html, /href="https:\/\/github.com\/o\/r\/issues\/9"/);
  const user = draw(React.createElement(Row, { row: { role: "user", text: "#356" } }));
  assert.doesNotMatch(user, /<a /);
});

test("a plain click on a reference calls onOpenTicket and preventDefault", () => {
  const calls = [];
  const el = Row({ row: { role: "assistant", text: "open acme/widgets#7" }, onOpenTicket: (r, n) => calls.push([r, n]) });
  const find = (node) => {
    if (!node || typeof node !== "object") return null;
    if (Array.isArray(node)) { for (const c of node) { const f = find(c); if (f) return f; } return null; }
    if (node.props && node.props.className === "fmTicketRef") return node;
    if (node.type && typeof node.type === "function") return find(node.type(node.props));
    return find(node.props && node.props.children);
  };
  const a = find(el);
  let prevented = false;
  a.props.onClick({ button: 0, preventDefault: () => { prevented = true; } });
  assert.deepEqual(calls, [["acme/widgets", 7]]);
  assert.equal(prevented, true);
});

// ---------------------------------------------------------------------------
// Save for later (#598): in the header, off until there is a message, and a
// label that says the save held until the conversation moves on.
// ---------------------------------------------------------------------------

test("Save for later sits after Start new conversation, disabled with nothing to save", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap({ can_save: false }) }));
  assert.match(html, /Start new conversation<\/button><button class="fmSaveButton" disabled=""[^>]*>Save for later</);
  const on = draw(React.createElement(FirstMate, { snapshot: snap({ can_save: true, history: [{ role: "user", text: "hi", at: 1 }] }) }));
  assert.match(on, /<button class="fmSaveButton" title="[^"]*">Save for later</);
});

test("saveLabel: Saving…, Saved ✓ or Updated ✓ until the history moves on, and a refusal is said", () => {
  const { saveLabel } = require("../dist/first-mate.cjs");
  assert.equal(saveLabel(null, 2), "Save for later");
  assert.equal(saveLabel({ busy: true }, 2), "Saving…");
  assert.equal(saveLabel({ ok: true, created: true, at: 2 }, 2), "Saved ✓");
  assert.equal(saveLabel({ ok: true, created: false, at: 2 }, 2), "Updated ✓");
  assert.equal(saveLabel({ ok: true, created: true, at: 2 }, 4), "Save for later");
  const html = draw(React.createElement(FirstMate, { snapshot: snap({ can_save: true, history: [{ role: "user", text: "hi", at: 1 }] }),
    saveState: { error: "engine unreachable" } }));
  assert.match(html, /Not saved: engine unreachable/);
});

// ---------------------------------------------------------------------------
// Open in Claude Code ⇗ follows Save for later directly; the strip has no
// Continue in terminal (#819 moved to Saved Chats only).
// ---------------------------------------------------------------------------

test("Open in Claude Code follows Save for later directly", () => {
  const header = (h) => h.slice(h.indexOf("fmHeaderButtons"), h.indexOf("fmTranscript"));
  const html = header(draw(React.createElement(FirstMate, { snapshot: snap({ can_save: true, history: [{ role: "user", text: "hi", at: 1 }] }) })));
  const save = html.indexOf("fmSaveButton"), open = html.indexOf("Open in Claude Code");
  assert.ok(html.indexOf("Start new conversation") < save && save < open);
  assert.match(html, /Save for later<\/button><button class="fmIterm"[^>]*>Open in Claude Code ⇗</);
});

test("handoffAnswer: the answer on success, an error for a refusal, a throw or no answer", async () => {
  const { handoffAnswer } = require("../dist/first-mate.cjs");
  assert.deepEqual(await handoffAnswer(async () => ({ ok: true, command: "c", mode: "resume" })), { ok: true, command: "c", mode: "resume" });
  assert.deepEqual(await handoffAnswer(async () => ({ ok: false, error: "no" })), { error: "no" });
  assert.deepEqual(await handoffAnswer(async () => { throw new Error("boom"); }), { error: "IPC failed: boom" });
  assert.deepEqual(await handoffAnswer(async () => undefined), { error: "no response from the main process" });
});

// ---------------------------------------------------------------------------
// Issue #601: "Back to previous conversation", in the header, enabled or
// disabled from snapshot.can_pop, on the live strip and in History.
// ---------------------------------------------------------------------------

const popButton = (html) => {
  const m = html.match(/<button class="fmPopConversation"[^>]*>[\s\S]*?<\/button>/);
  assert.ok(m, "the control is drawn");
  return m[0];
};

test("the pop control is enabled with a resumable past conversation, and says where it goes", () => {
  const { POP_TITLE_ON } = require("../dist/first-mate.cjs");
  const html = draw(React.createElement(FirstMate, { snapshot: snap({ can_pop: true }), onSend: () => {} }));
  const b = popButton(html);
  assert.doesNotMatch(b, /disabled/);
  assert.match(b, /Back to previous conversation/);
  assert.ok(b.includes(`title="${POP_TITLE_ON}"`));
  // On the live strip it sits under the compose row, not in the header (#725).
  assert.ok(html.indexOf("fmPopConversation") > html.indexOf("fmCompose"));
  assert.ok(html.indexOf("fmPopConversation") > html.indexOf("fmSend"));
  const header = html.slice(html.indexOf("fmHeader"), html.indexOf("fmTranscript"));
  assert.doesNotMatch(header, /fmPopConversation/);
  const order = ["Start new conversation", "fmSave"].map((k) => header.indexOf(k));
  assert.ok(order[0] >= 0 && order[0] < order[1], "new conversation before Save");
  assert.doesNotMatch(html, /fmHistoryList|fmHistoryView/);
});

test("the pop control is disabled, with a title saying why, when there is nothing to go back to", () => {
  const { POP_TITLE_OFF } = require("../dist/first-mate.cjs");
  for (const s of [snap({ can_pop: false }), snap()]) {
    const b = popButton(draw(React.createElement(FirstMate, { snapshot: s, onSend: () => {} })));
    assert.match(b, /disabled=""/);
    assert.ok(b.includes(`title="${POP_TITLE_OFF}"`));
    assert.match(POP_TITLE_OFF, /no past conversation/i);
  }
});

test("the pop control is also in the header while History is open", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap({ can_pop: true }), historyOpen: true, historyList: [] }));
  assert.doesNotMatch(popButton(html), /disabled/);
});

test("clicking the pop control calls onPopConversation; a disabled one does not; a refusal is shown", () => {
  const { PopControl } = require("../dist/first-mate.cjs");
  let calls = 0;
  PopControl({ canPop: true, onPop: () => { calls += 1; } }).props.onClick();
  PopControl({ canPop: false, onPop: () => { calls += 1; } }).props.onClick();
  assert.equal(calls, 1);
  const html = draw(React.createElement(FirstMate, { snapshot: snap({ can_pop: true }), popError: "nope", onSend: () => {} }));
  assert.match(html, /class="fmNotice fmPopError">Not switched: nope</);
});

// ---------------------------------------------------------------------------
// The multi-line compose box (#758): a textarea that grows with the
// draft up to 8 lines, then scrolls. Enter sends; Shift+Enter is a newline.
// ---------------------------------------------------------------------------

const { composeKeyAction } = require("../dist/first-mate.cjs");

const composeOf = (html) => html.match(/<div class="fmCompose">[\s\S]*?<\/div>/)[0];

test("a 3-line draft renders whole in the fmInput textarea, one row tall at rest", () => {
  const html = draw(React.createElement(FirstMate, {
    snapshot: snap(), draft: "one\ntwo\nthree", onDraft: () => {}, onSend: () => {},
  }));
  const compose = composeOf(html);
  assert.match(compose, /<textarea class="fmInput" rows="1"/);
  assert.match(compose, />one\ntwo\nthree<\/textarea>/);
  assert.doesNotMatch(compose, /<input/);
});

test("composeKeyAction: Enter sends; Shift+Enter, IME composition and other keys do not", () => {
  assert.equal(composeKeyAction({ key: "Enter" }), "send");
  assert.equal(composeKeyAction({ key: "Enter", nativeEvent: { isComposing: false } }), "send");
  assert.equal(composeKeyAction({ key: "Enter", shiftKey: true }), null);
  assert.equal(composeKeyAction({ key: "Enter", nativeEvent: { isComposing: true } }), null);
  assert.equal(composeKeyAction({ key: "Enter", isComposing: true }), null);
  assert.equal(composeKeyAction({ key: "Enter", keyCode: 229 }), null);
  assert.equal(composeKeyAction({ key: "a" }), null);
});

test("Send is enabled only when not busy and there is a non-blank draft or an attachment", () => {
  const disabled = (props) => {
    const { busy, ...rest } = props;
    const html = draw(React.createElement(FirstMate, { snapshot: snap({ busy: Boolean(busy) }), onDraft: () => {}, onSend: () => {}, ...rest }));
    return /disabled=""/.test(composeOf(html).match(/<button class="fmSend"[^>]*>/)[0]);
  };
  assert.equal(disabled({ draft: "" }), true, "empty draft");
  assert.equal(disabled({ draft: " \n  \n" }), true, "whitespace-only draft");
  assert.equal(disabled({ draft: "hello", busy: true }), true, "busy");
  assert.equal(disabled({ draft: "one\ntwo\nthree" }), false, "multi-line draft");
  assert.equal(disabled({ draft: "", attachments: [{ media_type: "image/png", data: "AAAA" }] }), false, "attachment only");
});

test("styles.css: the compose textarea grows to 8 lines, then scrolls, with the prompt and Send on its first line", () => {
  const css = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "styles.css"), "utf8");
  const rule = (sel) => {
    const m = css.match(new RegExp(`(?:^|\\n)${sel.replace(".", "\\.")}\\s*\\{([^}]*)\\}`));
    assert.ok(m, `${sel} rule`);
    return m[1];
  };
  const input = rule(".fmInput");
  assert.match(input, /field-sizing:\s*content/);
  assert.match(input, /min-height:\s*1lh/);
  assert.match(input, /max-height:\s*8lh/);
  assert.match(input, /overflow-y:\s*auto/);
  assert.match(input, /resize:\s*none/);
  assert.match(rule(".fmCompose"), /align-items:\s*flex-start/);
});

test("first-mate.jsx: the textarea keeps the image paste handler and Send keeps busy || !canSend", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "first-mate.jsx"), "utf8");
  const textarea = src.match(/<textarea className="fmInput"[\s\S]*?\/>/);
  assert.ok(textarea, "the compose textarea");
  assert.match(textarea[0], /onPaste=\{onPaste\}/);
  assert.match(textarea[0], /composeKeyAction\(e\) === "send"/);
  assert.match(src, /<button className="fmSend" disabled=\{busy \|\| !canSend\}/);
});

// ---------------------------------------------------------------------------
// Open in Claude Code ⇗ (#820): last in the live header, never in History,
// its title by why it is off, "Opening…" and "Not opened: …".
// ---------------------------------------------------------------------------

const { OpenInItermButton, itermTitle, ITERM_LABEL, ITERM_TITLE_ON, ITERM_TITLE_EMPTY, ITERM_TITLE_BUSY } = require("../dist/first-mate.cjs");
const itermButton = (html) => {
  const m = html.match(/<button class="fmIterm"[^>]*>[\s\S]*?<\/button>/);
  assert.ok(m, "the button is drawn");
  return m[0];
};
const liveHistory = [{ role: "user", text: "hi", at: 1 }, { role: "assistant", text: "hello", at: 2 }];
const canSnap = (over = {}) => snap({ history: liveHistory, can_save: true, can_open_in_iterm: true, iterm: { available: true, why: null }, ...over });

test("the button is last in the live header, after Save for later, enabled with its title", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: canSnap(), onSend: () => {} }));
  const header = html.slice(html.indexOf('class="fmHeader"'), html.indexOf("fmTranscript"));
  const b = itermButton(header);
  assert.doesNotMatch(b, /disabled/);
  assert.ok(b.includes(`title="${ITERM_TITLE_ON}"`));
  assert.ok(b.includes(ITERM_LABEL));
  assert.equal(ITERM_LABEL, "Open in Claude Code ⇗");
  assert.ok(header.indexOf("fmSaveButton") < header.indexOf("fmIterm"), "after Save for later");
  assert.match(header, /fmIterm"[^>]*>Open in Claude Code ⇗<\/button><\/div>/, "nothing after it in the header");
});

test("neither History panel draws the button", () => {
  const list = draw(React.createElement(FirstMate, { snapshot: canSnap(), historyOpen: true, historyList: [] }));
  assert.doesNotMatch(list, /fmIterm/);
  const view = draw(React.createElement(FirstMate, { snapshot: canSnap(), onCloseHistory: () => {},
    viewingConversation: { id: 1, started_at: 1, resumable: true, messages: liveHistory } }));
  assert.doesNotMatch(view, /fmIterm/);
});

test("each disabled state carries its title: no conversation, a running turn, not macOS, iTerm2 missing", () => {
  const cases = [
    [snap({ history: [], can_open_in_iterm: false, iterm: { available: true, why: null } }), ITERM_TITLE_EMPTY],
    [snap({ history: liveHistory, busy: true, can_open_in_iterm: false, iterm: { available: true, why: null } }), ITERM_TITLE_BUSY],
    [snap({ history: liveHistory, can_open_in_iterm: false, iterm: { available: false, why: "Only on macOS: iTerm2 is a Mac app." } }), "Only on macOS: iTerm2 is a Mac app."],
    [snap({ history: liveHistory, can_open_in_iterm: false, iterm: { available: false, why: "iTerm2 is not installed (looked in /Applications and ~/Applications)." } }),
      "iTerm2 is not installed (looked in /Applications and ~/Applications)."],
  ];
  for (const [s, title] of cases) {
    assert.equal(itermTitle(s), title);
    const b = itermButton(draw(React.createElement(FirstMate, { snapshot: s, onSend: () => {} })));
    assert.match(b, /disabled=""/);
    assert.ok(b.includes(`title="${title}"`), title);
  }
  assert.equal(ITERM_TITLE_EMPTY, "No conversation to hand off yet.");
  assert.equal(ITERM_TITLE_BUSY, "First mate is still working. Wait for it, or cancel.");
  // Precedence: an empty conversation on Linux says "no conversation", a busy one on Linux says "still working".
  assert.equal(itermTitle(snap({ history: [], iterm: { available: false, why: "Only on macOS: iTerm2 is a Mac app." } })), ITERM_TITLE_EMPTY);
  assert.equal(itermTitle(snap({ history: liveHistory, busy: true, iterm: { available: false, why: "Only on macOS: iTerm2 is a Mac app." } })), ITERM_TITLE_BUSY);
  // A snapshot from before #820 (no can_open_in_iterm, no handoff) is drawn disabled, not thrown on.
  const legacy = itermButton(draw(React.createElement(FirstMate, { snapshot: snap({ history: liveHistory }), onSend: () => {} })));
  assert.match(legacy, /disabled=""/);
});

test("Opening… while busy, and Not opened: <reason> beside an enabled button after a refusal", () => {
  const busy = draw(React.createElement(FirstMate, { snapshot: canSnap(), itermState: { busy: true }, onSend: () => {} }));
  const b = itermButton(busy);
  assert.match(b, /disabled=""/);
  assert.match(b, />Opening…</);
  const failed = draw(React.createElement(FirstMate, { snapshot: canSnap(), onSend: () => {},
    itermState: { error: "macOS refused automation of iTerm2. Allow it in System Settings > Privacy & Security > Automation." } }));
  const f = itermButton(failed);
  assert.doesNotMatch(f, /disabled/);
  assert.match(f, />Open in Claude Code ⇗</);
  assert.match(failed, /<span class="fmItermError"[^>]*>Not opened: macOS refused automation of iTerm2\. Allow it in System Settings &gt; Privacy &amp; Security &gt; Automation\.<\/span>/);
});

test("clicking the button calls onOpenInIterm; a disabled or busy one does not", () => {
  let calls = 0;
  const btn = (props) => walk(OpenInItermButton(props)).find((e) => e.type === "button" && e.props.className === "fmIterm");
  btn({ snapshot: canSnap(), onOpenInIterm: () => { calls += 1; } }).props.onClick();
  btn({ snapshot: canSnap({ can_open_in_iterm: false }), onOpenInIterm: () => { calls += 1; } }).props.onClick();
  btn({ snapshot: canSnap(), itermState: { busy: true }, onOpenInIterm: () => { calls += 1; } }).props.onClick();
  assert.equal(calls, 1);
});

test("the strip wrapper hands off through firstMate:open-in-iterm with no arguments", () => {
  const indexSrc = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "index.jsx"), "utf8");
  const body = indexSrc.slice(indexSrc.indexOf("const onOpenInIterm"), indexSrc.indexOf("return <FirstMate"));
  assert.match(body, /window\.firstMate\.openInIterm\(\)/);
  assert.match(body, /setItermState\(\{ busy: true \}\)/);
  const css = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "styles.css"), "utf8");
  assert.match(css, /\.fmIterm \{[^}]*var\(--fm-claude\)/, "the Claude accent");
  assert.match(css, /\.fmIterm:disabled \{[^}]*opacity: 0\.5/);
  assert.match(css, /\.fmItermError \{/);
});

// ---------------------------------------------------------------------------
// Stop (Esc) (#815): shown only while busy, with the Esc hint.
// ---------------------------------------------------------------------------

test("busy: the composer shows Stop titled 'Stop (Esc)' instead of Send", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap({ busy: true }), draft: "keep me", onStop: () => {} }));
  assert.match(html, /<button class="fmStop" title="Stop \(Esc\)">Stop<\/button>/);
  assert.doesNotMatch(html, /class="fmSend"/);
});

test("idle: no Stop control, Send is back", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap({ busy: false }), draft: "x", onStop: () => {} }));
  assert.doesNotMatch(html, /fmStop/);
  assert.match(html, /class="fmSend"/);
});

test("the chat repo chip shows No repo yet or the repo, and picks call onSetRepo (#2343)", () => {
  const { ChatRepoChip, NO_CHAT_REPO } = require("../dist/first-mate.cjs");
  const none = draw(React.createElement(FirstMate, { snapshot: snap({ chat_repo_on: true, chat_repo: null }), selection: { repo: null, repos: ["o/a", "o/b"] } }));
  assert.match(none, /fmChatRepoNone/);
  assert.match(none, new RegExp(NO_CHAT_REPO));
  assert.match(none, /o\/b/);
  const set = draw(React.createElement(FirstMate, { snapshot: snap({ chat_repo_on: true, chat_repo: "o/a" }), selection: { repo: null, repos: ["o/a"] } }));
  assert.doesNotMatch(set, /fmChatRepoNone/);
  assert.match(set, /<option value="o\/a" selected="">o\/a<\/option>/);
  assert.doesNotMatch(draw(React.createElement(FirstMate, { snapshot: snap() })), /fmChatRepo/);
  const picks = [];
  const el = ChatRepoChip({ chatRepo: null, repos: ["o/a"], onSetRepo: (r) => picks.push(r) });
  const select = el.props.children[1];
  select.props.onChange({ target: { value: "o/a" } });
  select.props.onChange({ target: { value: "" } });
  assert.deepEqual(picks, ["o/a", null]);
});

test("a kubernetes conversation draws no mode line, and resuming… with the pod-status as its tooltip (#2523)", () => {
  const html = draw(React.createElement(FirstMate, { snapshot: snap({
    runtime: "kubernetes", chat_repo: "acme/widgets", 
    remoteState: "resuming…", remote_detail: "ContainerCreating",
  }) }));
  assert.doesNotMatch(html, /fmModeLine|runs in a pod on the cluster/);
  assert.match(html, /class="fmRemoteState" title="ContainerCreating">resuming…</);
  const local = draw(React.createElement(FirstMate, { snapshot: snap() }));
  assert.doesNotMatch(local, /fmModeLine|fmRemoteState/);
});

test("Start new conversation names the new chat's repo, the chip below it is this chat's, and a differing switcher draws no note (#2551, #100107)", () => {
  const one = draw(React.createElement(FirstMate, { snapshot: snap({ chat_repo_on: true, chat_repo: "o/a" }), selection: { repo: "o/a", repos: ["o/a"] } }));
  assert.match(one, />Start new conversation<span class="fmNewConversationRepo"> · in o\/a<\/span>/);
  assert.match(one, /This chat&#x27;s repo:/);
  assert.ok(one.indexOf("fmNewConversation") < one.indexOf("fmChatRepoRow"), "chip row after the button");
  assert.doesNotMatch(one, /fmChatRepoMismatch/);
  const all = draw(React.createElement(FirstMate, { snapshot: snap({ chat_repo_on: true, chat_repo: "o/a" }), selection: { repo: null, repos: ["o/a"] } }));
  assert.match(all, / · no repo yet</);
  assert.doesNotMatch(all, /fmChatRepoMismatch|applies to new chats only/);
  const other = draw(React.createElement(FirstMate, { snapshot: snap({ chat_repo_on: true, chat_repo: null }), selection: { repo: "o/b", repos: ["o/b"] } }));
  assert.doesNotMatch(other, /applies to new chats only/);
  const off = draw(React.createElement(FirstMate, { snapshot: snap() }));
  assert.match(off, />Start new conversation<\/button>/);
});
