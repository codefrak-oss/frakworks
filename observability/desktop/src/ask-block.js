// ask-block.js — the one parser for the fenced `ask` block a ticket comment
// may carry (issue #1337).
//
// The block's shape is **not invented here**. It is specified in the desktop
// app's design, §"(c) Prioritized 'things the
// user is needed for' view" — #1337 cites §"Actions and write access", which is
// one section further down and is where the `reply` verbs and the credentials
// they would need are argued:
//
//     ```ask
//     kind: choice            # yes-no | choice | text | confirm
//     question: Should the desktop app authenticate to VictoriaMetrics?
//     options:
//       - id: tailnet
//         label: Stay tailnet-only (no change)
//       - id: auth
//         label: Put VM behind auth (new ticket)
//     reply: comment           # comment | label:<name> | close
//     ```
//
// and its *adoption* — teaching Smiths to emit one — is that proposal's stage
// 3b and a separate ticket. Nothing here changes `bots/smith.md` or the
// dispatcher: this parses the block where it happens to appear, which today is
// a fixture.
//
// What this module produces is stage 2's **shared logical choice payload**
// (`src/choices.js`, `buildChoice`) — `{question, options:[{id,label}],
// recommended_id?}` — and nothing else. There is deliberately no second marker
// vocabulary and no second prose parser: `src/choices.js` owns the pane
// carrier (`Options: a | b`), this owns the ticket carrier (the fenced block),
// and both end at the same `buildChoice`, so a rule added there applies to
// both.
//
// `reply` is read and validated but is **metadata only**: it sits on the `ask`
// object, outside the rendering payload, and nothing in this stage acts on it.
// Posting an answer, applying a label and closing an issue are all writes, and
// writes are stage 4.
"use strict";

const { buildChoice } = require("./choices");

// The four kinds the proposal names, and what each renders as.
const ASK_KINDS = Object.freeze(["yes-no", "choice", "text", "confirm"]);

// `yes-no` is normalized to exactly these two options, so every carrier of the
// shared payload sees a plain two-option choice rather than a special case.
const YES_NO_OPTIONS = Object.freeze([
  Object.freeze({ id: "yes", label: "Yes" }),
  Object.freeze({ id: "no", label: "No" }),
]);

// `confirm` is one button. The proposal gives it no option list of its own, so
// a block that omits one gets this.
const CONFIRM_OPTION = Object.freeze({ id: "confirm", label: "Confirm" });

// `reply: comment` is the proposal's default — "comment (the default) posts
// `Answer: <id or text>` … `label:<name>` additionally applies a label;
// `close` closes with the answer". None of the three is acted on here.
const DEFAULT_REPLY = "comment";
const REPLY_RE = /^(comment|close|label:[^\s]+)$/;

// A fenced block whose info string is exactly `ask`. Three or more backticks so
// a block that itself contains a fence (the proposal quotes one inside four)
// still closes on its own opener.
const ASK_FENCE = /^[ \t]*(`{3,})ask[ \t]*\r?\n([\s\S]*?)\r?\n[ \t]*\1[ \t]*$/gm;

// `kind:` and `reply:` are keyword values, so a trailing `# …` on those lines
// is the proposal's own annotation and not part of the value. `question:` and
// `label:` are prose and keep every character, `#` included.
const uncomment = (v) => String(v).replace(/\s+#.*$/, "").trim();

// Parse the block's body. Deliberately not a YAML parser: the block is four
// keys and a list of pairs, and a real YAML dependency would let a comment in
// a ticket thread reach an expression evaluator.
function parseBody(body) {
  const out = { kind: null, question: null, options: [], reply: null };
  const lines = String(body).split("\n");
  let inOptions = false;
  let current = null;
  for (const raw of lines) {
    const line = raw.replace(/\r$/, "");
    if (!line.trim()) continue;
    const indented = /^[ \t]+/.test(line);
    const item = /^[ \t]*-[ \t]*(.*)$/.exec(line);
    if (inOptions && (item || (indented && current))) {
      if (item) { current = { id: null, label: null }; out.options.push(current); }
      const rest = item ? item[1] : line.trim();
      const kv = /^([A-Za-z_][\w-]*):[ \t]*(.*)$/.exec(rest);
      if (!kv || !current) return { error: `unparseable option line: ${line.trim()}` };
      const key = kv[1].toLowerCase();
      if (key === "id") current.id = kv[2].trim();
      else if (key === "label") current.label = kv[2].trim();
      else return { error: `unknown option field: ${key}` };
      continue;
    }
    inOptions = false;
    const kv = /^([A-Za-z_][\w-]*):[ \t]*(.*)$/.exec(line.trim());
    if (!kv) return { error: `unparseable line: ${line.trim()}` };
    const key = kv[1].toLowerCase();
    const value = kv[2];
    if (key === "kind") out.kind = uncomment(value);
    else if (key === "question") out.question = value.trim();
    else if (key === "reply") out.reply = uncomment(value);
    else if (key === "options") { inOptions = true; current = null; }
    else return { error: `unknown field: ${key}` };
  }
  return { value: out };
}

// Validate `reply` **without** letting it break rendering. An unrecognised
// value is reported on `reply_error` and the metadata is dropped; the question
// and its buttons are unaffected, because `reply` says how an answer would
// reach the fleet and this stage sends no answer anywhere.
function validateReply(raw) {
  if (raw == null || raw === "") return { reply: DEFAULT_REPLY, reply_error: null };
  const v = String(raw).trim();
  if (REPLY_RE.test(v)) return { reply: v, reply_error: null };
  return { reply: null, reply_error: `unrecognised reply: ${JSON.stringify(v)}` };
}

// Turn one parsed block into the shared payload. Returns null when the block
// describes no buttons — `text`, or a `choice` whose options fail
// `buildChoice`'s rules (too few, too many, an empty or duplicate id). A long
// label does not reject it: the button shortens it on display.
function choiceOf(ask) {
  if (ask.kind === "text") return null;
  if (ask.kind === "yes-no") {
    return buildChoice({ question: ask.question, options: YES_NO_OPTIONS.map((o) => ({ ...o })) });
  }
  if (ask.kind === "confirm") {
    const one = ask.options.length ? ask.options[0] : { ...CONFIRM_OPTION };
    // One button is a real state for `confirm` and only for `confirm`, so the
    // floor is lowered here rather than in the shared constant.
    return buildChoice({ question: ask.question, options: [one] }, { minOptions: 1 });
  }
  return buildChoice({ question: ask.question, options: ask.options });
}

/**
 * Read one comment body.
 *
 * Returns `{ prose, ask, choice, error }`:
 *
 *   prose   what is displayed — the body with **every** `ask` fence removed,
 *           whether or not it parsed. A block that failed to parse is still
 *           plumbing, and showing the user the plumbing is the one outcome worse
 *           than showing them no buttons (this mirrors `normalizeChoice`'s
 *           "a marker in the wrong place is still a marker").
 *   ask     the validated metadata — `{kind, question, options, reply,
 *           reply_error}` — or null when there was no block or it was
 *           malformed.
 *   choice  the shared logical payload, or null.
 *   error   why `ask` is null, when a block was present but unusable.
 *
 * Never throws. A comment with no block is `{prose: body, ask: null,
 * choice: null, error: null}`, which the pane renders as prose plus the
 * free-text box, exactly as it renders every other comment.
 */
function parseAskBlock(body) {
  const text = String(body == null ? "" : body);
  const blocks = [...text.matchAll(ASK_FENCE)];
  if (!blocks.length) return { prose: text, ask: null, choice: null, error: null };
  // Every block is stripped; the **last** is the one read. A comment carrying
  // two is a Smith correcting itself, and the correction is the later one.
  let prose = text;
  for (const m of blocks) prose = prose.replace(m[0], "");
  prose = prose.replace(/\n{3,}/g, "\n\n").trim();

  const parsed = parseBody(blocks[blocks.length - 1][2]);
  if (parsed.error) return { prose, ask: null, choice: null, error: parsed.error };
  const raw = parsed.value;
  if (!ASK_KINDS.includes(raw.kind)) {
    return { prose, ask: null, choice: null, error: `unknown ask kind: ${JSON.stringify(raw.kind)}` };
  }
  if (!raw.question) return { prose, ask: null, choice: null, error: "an ask block needs a question" };
  const options = raw.options.map((o) => ({ id: o.id || "", label: o.label || "" }));
  const { reply, reply_error } = validateReply(raw.reply);
  const ask = { kind: raw.kind, question: raw.question, options, reply, reply_error };
  const choice = choiceOf(ask);
  // `choice` describes buttons by definition; a block that claims that kind and
  // then supplies nothing usable is malformed, not a text question.
  if (raw.kind === "choice" && !choice) {
    return { prose, ask: null, choice: null, error: "a choice ask block needs two to five usable options" };
  }
  return { prose, ask, choice, error: null };
}

module.exports = {
  ASK_KINDS, YES_NO_OPTIONS, CONFIRM_OPTION, DEFAULT_REPLY,
  parseAskBlock, parseBody, validateReply, choiceOf,
};
