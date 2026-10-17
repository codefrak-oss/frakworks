// judge-teach.js — the pure parts of the judge-escalation teach buttons
// (#2436; engine/README.md, "Teaching the judge"): which HITLP card is a judge
// escalation (an approve whose recommendation is by a judge,
// `asserted.recommendation.by.judge`, or whose request carries
// `context.data.judge`), the two
// buttons and the outcome each decides, and why they are disabled without a
// first-mate token. src/renderer/judge-teach.jsx draws them in the urgent
// popup and the Needs card; test/judge-teach.test.js exercises both.
"use strict";

const TEACH_BUTTONS = [
  { label: "Allow and teach", outcome: "approved", decision: "allow" },
  { label: "Deny and teach", outcome: "rejected", decision: "deny" },
];

const NO_TOKEN_TIP = "Set a first-mate token in Config > frakworks > Judge teach token: only a first-mate token may commit to the rubric. Approve and Reject still work.";

/** Config > frakworks > Judge teach token's hint: what the token is for and why the sign-in token cannot stand in. */
const TEACH_TOKEN_HINT = "A first-mate token (POST /tokens with purpose first-mate, or Pair a phone). Only the judge's Allow and teach / Deny and teach send it: the engine's POST /judge/teach refuses any other token. Kept encrypted with safeStorage.";

const objOf = (v) => (v && typeof v === "object" && !Array.isArray(v) ? v : null);

/**
 * The judge behind an approve: its recommendation's `by.judge` (HITLP 0.2,
 * `asserted.recommendation`), else the request's `context.data.judge`; null
 * when `row` is not an approve or names none.
 */
const judgeOf = (row) => {
  if (!row || row.kind !== "approve") return null;
  const rec = objOf(row.recommendation) || objOf(row.asserted && row.asserted.recommendation);
  const byJudge = rec && objOf(rec.by) ? objOf(rec.by.judge) : null;
  if (byJudge) return byJudge;
  const data = objOf(row.context) ? objOf(row.context.data) : null;
  return data ? objOf(data.judge) : null;
};

/** The tooltip of a teach button: why it is disabled, or what it commits to. */
const teachTitle = (judge, ready) => (ready
  ? `Decide, then commit a dated line under ## Decisions of ${judge.rubricPath || "the rubric"} in ${judge.repo || "its repo"}`
  : NO_TOKEN_TIP);

/**
 * One teach click: `decide(taskId, outcome, null, note)` as Approve or Reject
 * would (on the HITLP server), then `teach(taskId, allow|deny, note)` (the engine's
 * `POST /judge/teach`, with the task id). Resolves to `{ ok, line }` or
 * `{ error }`; a teach that fails after the decision says the task was decided.
 */
async function teachClick(row, button, note, decide, teach) {
  const n = note && String(note).trim() ? String(note).trim() : null;
  const d = await decide(row.actionId, button.outcome, null, n);
  if (!d || !d.ok) return { error: (d && d.error) || "the engine did not answer" };
  const t = await teach(row.actionId, button.decision, n);
  if (t && t.ok) return { ok: true, line: t.line || "taught" };
  return { error: `decided, but not taught: ${(t && t.error) || "the engine did not answer"}` };
}

module.exports = { TEACH_BUTTONS, NO_TOKEN_TIP, TEACH_TOKEN_HINT, judgeOf, teachTitle, teachClick };
