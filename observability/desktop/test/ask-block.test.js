// The fenced `ask` block a ticket comment may carry, and its mapping onto
// stage 2's shared logical choice payload (stage 3, #1337).
//
// The block's shape is the desktop app design's §"Actions and write access"
// (in the predecessor repo); the payload's is `src/choices.js`. This suite is about
// the seam between them: four kinds in, one payload shape out, and every
// malformed input degrading to prose plus the free-text box rather than to
// buttons nobody can trust.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const { parseAskBlock, ASK_KINDS, DEFAULT_REPLY } = require("../src/ask-block");
const { buildChoice, CHOICE_MAX_OPTIONS } = require("../src/choices");

const block = (body) => "Some prose first.\n\n```ask\n" + body + "\n```\n\n— 🤖 Agent Smith `x` · issue #1";

test("the four kinds are the proposal's, and the proposal is where they are written down", (t) => {
  assert.deepEqual([...ASK_KINDS], ["yes-no", "choice", "text", "confirm"]);
  // The proposal stayed in the private predecessor repo (bots/proposals/ is a frozen
  // archive there; frakworks CLAUDE.md, "A proposal says its latest
  // decision"), so this check runs only where that checkout sits beside it.
  const proposalPath = path.join(__dirname, "..", "..", "..", "bots", "proposals", "agent-army-desktop-app.md");
  if (!fs.existsSync(proposalPath)) return t.skip("bots/proposals/agent-army-desktop-app.md is in the predecessor repo, not here");
  const proposal = fs.readFileSync(proposalPath, "utf8");
  // The block itself is specified in §"(c) Prioritized …" — #1337's wording
  // cites §"Actions and write access", which is where its `reply` verbs and
  // the credentials they would need are argued, one section further down.
  const section = proposal.split('### (c) Prioritized')[1].split("\n## ")[0];
  const fenced = section.match(/```ask\n([\s\S]*?)\n```/);
  assert.ok(fenced, "the proposal still specifies a fenced ask block");
  for (const kind of ASK_KINDS) assert.ok(section.includes(kind), `${kind} is named there`);
  for (const verb of ["comment", "label:<name>", "close"]) {
    assert.ok(section.includes(verb), `${verb} is one of the proposal's reply values`);
  }
  // and the proposal's own example parses through this module unchanged
  const r = parseAskBlock("prose\n\n```ask\n" + fenced[1] + "\n```");
  assert.equal(r.error, null, r.error);
  assert.equal(r.ask.kind, "choice");
  assert.deepEqual(r.choice.options.map((o) => o.id), ["tailnet", "auth"]);
});

test("kind: choice with labels over forty characters still gets buttons, with full labels (#347)", () => {
  const a = "Narrow it to the README and nothing else at all";
  const b = "Widen it to the engine and every Smith brief too";
  const r = parseAskBlock(block(
    `kind: choice\nquestion: Which?\noptions:\n  - id: narrow\n    label: ${a}\n  - id: wide\n    label: ${b}`));
  assert.ok(r.choice);
  assert.deepEqual(r.choice.options, [{ id: "narrow", label: a }, { id: "wide", label: b }]);
});

test("kind: choice becomes the shared payload, and only the shared payload", () => {
  const r = parseAskBlock(block(
    "kind: choice\nquestion: Stay or move?\noptions:\n  - id: stay\n    label: Stay put\n  - id: move\n    label: Move it\nreply: comment"));
  assert.equal(r.error, null);
  assert.deepEqual(r.choice, buildChoice({
    question: "Stay or move?",
    options: [{ id: "stay", label: "Stay put" }, { id: "move", label: "Move it" }],
  }), "built through src/choices.js, not by a second constructor");
  assert.deepEqual(Object.keys(r.choice).sort(), ["options", "question"]);
  assert.equal(r.ask.reply, "comment", "reply is metadata on the ask…");
  assert.ok(!("reply" in r.choice), "…and never in the rendering payload");
  assert.doesNotMatch(r.prose, /```|kind:|options:/, "the block is stripped from the display");
  assert.match(r.prose, /Some prose first\./, "the prose around it survives");
});

test("kind: yes-no normalizes to two options", () => {
  const r = parseAskBlock(block("kind: yes-no\nquestion: Ship it?"));
  assert.deepEqual(r.choice.options, [{ id: "yes", label: "Yes" }, { id: "no", label: "No" }]);
  assert.equal(r.choice.question, "Ship it?");
  assert.equal(r.ask.reply, DEFAULT_REPLY, "an absent reply is the proposal's default");
});

test("kind: confirm normalizes to one option", () => {
  const bare = parseAskBlock(block("kind: confirm\nquestion: Proceed?"));
  assert.equal(bare.choice.options.length, 1);
  assert.deepEqual(bare.choice.options[0], { id: "confirm", label: "Confirm" });
  const named = parseAskBlock(block(
    "kind: confirm\nquestion: Proceed?\noptions:\n  - id: go\n    label: Go ahead"));
  assert.deepEqual(named.choice.options, [{ id: "go", label: "Go ahead" }]);
  // one button is a `confirm` privilege and not a general relaxation
  assert.equal(buildChoice({ question: "q", options: [{ id: "go", label: "Go" }] }), null);
});

test("kind: text normalizes to no options — prose and the free-text box only", () => {
  const r = parseAskBlock(block("kind: text\nquestion: What should it say?"));
  assert.equal(r.choice, null, "no buttons");
  assert.equal(r.ask.kind, "text");
  assert.equal(r.ask.question, "What should it say?");
  assert.doesNotMatch(r.prose, /kind: text/);
});

test("a malformed block falls back to prose, with the block text still stripped", () => {
  for (const body of [
    "kind: interpretive-dance\nquestion: ?",           // not one of the four
    "kind: choice\nquestion: pick\noptions:\n  - just a string",
    "kind: choice",                                     // no question
    "this is not key: value\nat all: or is it",         // unknown field
    "kind: choice\nquestion: pick\noptions:\n  - id: only\n    label: One",  // one option is not a choice
  ]) {
    const r = parseAskBlock(block(body));
    assert.equal(r.ask, null, `ask rejected: ${body.split("\n")[0]}`);
    assert.equal(r.choice, null, "and no buttons are drawn from it");
    assert.ok(r.error, "with a reason");
    assert.doesNotMatch(r.prose, /```|kind:/, "the plumbing is not shown to the user");
    assert.match(r.prose, /Some prose first\./);
  }
});

test("more than five options is refused by the shared ceiling, not by a second rule", () => {
  const many = Array.from({ length: CHOICE_MAX_OPTIONS + 1 },
    (_v, i) => `  - id: o${i}\n    label: Option ${i}`).join("\n");
  const r = parseAskBlock(block(`kind: choice\nquestion: too many?\noptions:\n${many}`));
  assert.equal(r.choice, null);
  assert.equal(r.ask, null);
});

test("an unrecognised reply is rejected as metadata and breaks nothing", () => {
  const r = parseAskBlock(block("kind: yes-no\nquestion: Ship it?\nreply: rm -rf /"));
  assert.equal(r.ask.reply, null, "the value is not kept");
  assert.match(r.ask.reply_error, /unrecognised reply/);
  assert.deepEqual(r.choice.options.map((o) => o.id), ["yes", "no"], "the buttons still render");
  // the three the proposal names are accepted, and none of them is acted on here
  for (const ok of ["comment", "close", "label:needs-review"]) {
    const good = parseAskBlock(block(`kind: yes-no\nquestion: q\nreply: ${ok}`));
    assert.equal(good.ask.reply, ok);
    assert.equal(good.ask.reply_error, null);
  }
});

test("a comment with no block is prose, unchanged, and offers no buttons", () => {
  const body = "Blocked: waiting on a decision.\n\n— 🤖 Agent Smith `x` · issue #1";
  const r = parseAskBlock(body);
  assert.equal(r.prose, body, "byte for byte");
  assert.equal(r.ask, null);
  assert.equal(r.choice, null);
  assert.equal(r.error, null, "no block is not an error");
});

test("two blocks: the later one is read, both are stripped", () => {
  const body = "prose\n\n```ask\nkind: yes-no\nquestion: first?\n```\n\nmore\n\n```ask\nkind: yes-no\nquestion: second?\n```";
  const r = parseAskBlock(body);
  assert.equal(r.ask.question, "second?");
  assert.doesNotMatch(r.prose, /```|question:/);
  assert.match(r.prose, /prose[\s\S]*more/);
});

test("the parser introduces no second marker vocabulary", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "ask-block.js"), "utf8");
  // no `Options:` line reader here — that carrier belongs to src/choices.js
  assert.doesNotMatch(src, /\^Options:/, "the pane carrier is not re-implemented");
  assert.match(src, /require\("\.\/choices"\)/, "and the payload is built there");
  // stage 2's own marker still means what it meant
  const { normalizeChoice } = require("../src/choices");
  assert.deepEqual(normalizeChoice("pick one\nOptions: a | b").choice.options,
    [{ id: "a", label: "a" }, { id: "b", label: "b" }]);
});
