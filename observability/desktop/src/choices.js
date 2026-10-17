// choices.js — the one normalizer for the **shared** logical choice payload
// (issue #1334).
//
// The payload is defined once ("one schema, two carriers"):
//
//     {"question": <the spoken/displayed text>,
//      "options": [{"id": …, "label": …}, …]}
//
// plus the **optional** `recommended_id` that #1334 asks for and that this
// file defines as an addition to the shared payload, not as a payload of its
// own. The bridge carrier maps to it too, when whoever builds that side is
// ready; nothing here is desktop-shaped.
//
// The carrier is also the shared one: **one trailing line**
// `Options: a | b | c`, the marker `mobile-bridge-choice-buttons.md` §1
// recommends. There is deliberately no second marker vocabulary here — no
// fenced block, no JSON tail — and no prose parser: option B of that proposal
// ("the server guesses from the prose") was rejected there and is not
// resurrected here. A reply with no marker produces no payload and is
// displayed byte-for-byte as it arrived.
//
// The one extension this file makes to the carrier is how a recommendation is
// written: a trailing `*` on the recommended label, inside the same single
// line — `Options: stay | move*`. It is the same marker, one character wider,
// which is what §3's "optional addition to that shared contract" has to look
// like if it is not to become a second vocabulary.
"use strict";

// The marker, anchored at the start of a line. Case-sensitive: `Options:` is
// what the prompt asks for, and a lowercase `options:` in ordinary prose
// ("the options: A and B") must not be mistaken for a carrier.
const CHOICE_MARKER = /^Options:[ \t]*(.*)$/;

// The sanity checks, shared with the bridge side by being stated once here.
// Two is the smallest real choice (yes/no); five is where a button row stops
// being faster than saying the answer. A label longer than this is still
// accepted and kept whole in the payload; the view shortens it on the button
// with `buttonLabel` below (#347).
const CHOICE_MIN_OPTIONS = 2;
const CHOICE_MAX_OPTIONS = 5;
const CHOICE_LABEL_MAX = 40;

// Build and validate the shared payload. This is the *only* place a choice
// object is constructed, so every carrier that ever produces one — this
// module's marker reader, a future bridge adapter, a future `ask`-block
// parser — inherits exactly these rules.
//
// Returns the payload, or `null` when it fails a check. Never throws and never
// half-builds: a caller that gets `null` shows prose and the free-text box,
// which is what every one of these rules degrades to.
//
// `minOptions` (stage 3, #1337) is the one rule a carrier may relax, and only
// downwards to one: the ticket `ask` block's `confirm` kind is specified as a
// single button, which is a real question rather than a degenerate choice.
// It defaults to `CHOICE_MIN_OPTIONS`, so the pane carrier above is unchanged,
// and every other rule — the ceiling, the ids, the labels, the duplicate
// check — still applies to both.
function buildChoice({ question, options, recommended_id } = {}, { minOptions = CHOICE_MIN_OPTIONS } = {}) {
  if (!Array.isArray(options)) return null;
  const floor = Math.max(1, Math.min(Number(minOptions) || CHOICE_MIN_OPTIONS, CHOICE_MIN_OPTIONS));
  if (options.length < floor || options.length > CHOICE_MAX_OPTIONS) return null;
  const clean = [];
  const seen = new Set();
  for (const o of options) {
    if (!o || typeof o !== "object") return null;
    const id = typeof o.id === "string" ? o.id.trim() : "";
    const label = typeof o.label === "string" ? o.label.trim() : "";
    if (!id || !label) return null;
    // Two buttons with the same id cannot be told apart once one is tapped.
    if (seen.has(id)) return null;
    seen.add(id);
    clean.push({ id, label });
  }
  const payload = { question: String(question == null ? "" : question), options: clean };
  // An id that names no option in the set is *ignored*, not a rejection: a
  // recommendation is decoration, and losing it is not a reason to take the
  // buttons away. Nothing is highlighted in that case — never the first
  // option, which proposal §3 rules out explicitly.
  if (typeof recommended_id === "string" && seen.has(recommended_id.trim())) {
    payload.recommended_id = recommended_id.trim();
  }
  return payload;
}

// The text a button shows for `label`: unchanged up to `CHOICE_LABEL_MAX`
// characters, otherwise its first `CHOICE_LABEL_MAX - 3` followed by `...`,
// so the result is never longer than the limit. The full label stays in the
// payload (and on the button's tooltip); the answer is still the option's id.
function buttonLabel(label) {
  const s = String(label == null ? "" : label);
  if (s.length <= CHOICE_LABEL_MAX) return s;
  return s.slice(0, CHOICE_LABEL_MAX - 3) + "...";
}

// Read one reply. Returns `{ reply, choice }`:
//
//   reply  — what is displayed, with **every** marker line removed, whether or
//            not it produced a payload. A marker in the wrong place is still a
//            marker: showing it to the user would be showing them the plumbing.
//   choice — the shared payload, or null.
//
// The contract puts the marker on the last non-empty line and nowhere else.
// A marker anywhere else, or more than one, fails the "is it where the
// contract puts it" check: the text is stripped and no payload is produced.
function normalizeChoice(reply) {
  const text = String(reply == null ? "" : reply);
  const lines = text.split("\n");
  const markerAt = [];
  let lastContentAt = -1;
  lines.forEach((line, i) => {
    if (CHOICE_MARKER.test(line)) markerAt.push(i);
    if (line.trim()) lastContentAt = i;
  });
  if (!markerAt.length) return { reply: text, choice: null };

  const kept = lines.filter((_l, i) => !markerAt.includes(i));
  // Trailing blank lines left behind by the strip would otherwise show as a
  // gap under the message.
  const cleaned = kept.join("\n").replace(/\s+$/, "");

  const wellPlaced = markerAt.length === 1 && markerAt[0] === lastContentAt;
  if (!wellPlaced) return { reply: cleaned, choice: null };

  const body = CHOICE_MARKER.exec(lines[markerAt[0]])[1];
  const parts = body.split("|").map((s) => s.trim()).filter((s) => s.length);
  let recommended = null;
  const options = [];
  for (const part of parts) {
    const starred = part.endsWith("*");
    const label = starred ? part.slice(0, -1).trim() : part;
    // Two recommendations is not a recommendation. Reject rather than pick
    // one: a highlighted button the user did not expect is worse than none.
    if (starred && recommended !== null) return { reply: cleaned, choice: null };
    if (starred) recommended = label;
    options.push({ id: label, label });
  }
  // `question` is §5's "the spoken/displayed text" — here, the whole reply
  // with the marker line removed, because that reply *is* what the model said
  // to introduce its options and there is no second field naming the question
  // separately. The desktop carrier does not use it (`ChoiceRow` draws only
  // the buttons; the prose is already a transcript row), but a carrier that
  // speaks this payload speaks this string, so a normalizer change here
  // changes what the phone would say. Noted rather than narrowed: guessing
  // which sentence is "the question" would be the prose parser that
  // `mobile-bridge-choice-buttons.md` rejected.
  const choice = buildChoice({ question: cleaned, options, recommended_id: recommended });
  return { reply: cleaned, choice };
}

module.exports = {
  CHOICE_MARKER, CHOICE_MIN_OPTIONS, CHOICE_MAX_OPTIONS, CHOICE_LABEL_MAX,
  buildChoice, buttonLabel, normalizeChoice,
};
