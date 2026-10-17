// issues.jsx — the Issues view: the engine's issues board (`GET /issues`,
// engine/README.md "The issues board") as a table. The first screen fed by
// the engine rather than the old fleet's VictoriaMetrics and `gh`, and what
// the window opens on.
//
// Drawing only, like needs-you.jsx: the rows arrive as props from
// src/engine-data.js through the bridge, and the one thing a row can do is
// hand its number up (onOpenTicket) or open GitHub in the browser. No
// control here writes anything — except the "New intake" composer below,
// whose write (`onSubmitIntake`) and its wait on the stream (`onIntakeEvents`,
// `onHoldRepo`) are threaded down from index.jsx as props, so this file still never touches `window.fleet` or `fetch`
// itself.

import { useCallback, useEffect, useState } from "react";
import { RESTARTING_VIEW } from "./engine-events.js";
import { useRepoName } from "./repo-name-form.jsx";
import { StageBoard } from "./stage-board.jsx";

// "4d 2h" / "3h 07m" / "12m" — the same register as needs-you's fmtWaited.
export const fmtAgo = (iso, now = Date.now()) => {
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return "";
  const s = Math.max(0, Math.round((now - t) / 1000));
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  if (d) return `${d}d ${h}h`;
  if (h) return `${h}h ${String(m).padStart(2, "0")}m`;
  return `${m}m`;
};

// The Tickets row's live age, as a clock: "0:29", "59:59", "3:07:42", "1d 2:03:04".
export const fmtAgeClock = (seconds) => {
  if (!Number.isFinite(seconds)) return "";
  const s = Math.max(0, Math.floor(seconds));
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  const p2 = (n) => String(n).padStart(2, "0");
  if (d) return `${d}d ${h}:${p2(m)}:${p2(r)}`;
  if (h) return `${h}:${p2(m)}:${p2(r)}`;
  return `${m}:${p2(r)}`;
};

/** The age under a ticket's number, counting up once a second from `createdAt`; `now` pins it (tests). */
export const TicketAge = ({ createdAt, now: fixedNow = null }) => {
  const [, setTick] = useState(0);
  const t = Date.parse(createdAt);
  useEffect(() => {
    if (fixedNow != null || !Number.isFinite(t)) return undefined;
    const i = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(i);
  }, [t, fixedNow]);
  const now = fixedNow != null ? fixedNow : Date.now();
  return <div className="isAge" title={createdAt}>{Number.isFinite(t) ? fmtAgeClock((now - t) / 1000) : ""}</div>;
};

// Every activity of engine/bpmn/chore.bpmn, bug.bpmn, feature.bpmn and
// intake.bpmn, keyed by its id, as the phrase the state column shows while
// the flow's outermost process waits there (`flow.stage`). An id shared by
// flows means the same step in each, so one table covers all four. A Smith
// run's call activity says what that Smith is doing; the steps around it say
// what is done and what comes next.
const WAITING_ON_YOU = "waiting on your answer";
export const STAGE_PHRASES = {
  // started
  "start-chore-filed": "starting",
  "start-bug-filed": "starting",
  "start-feature-filed": "starting",
  "start-ask-submitted": "starting",
  "start-proposal-filed": "starting",
  "start-research-filed": "starting",
  // intake
  "task-open-ask": "opening the ask",
  "event-intake-authorized": "awaiting approval",
  "task-check-duplicate": "checking for an open ticket on this",
  "task-post-duplicate": "asking whether an open ticket covers this",
  "user-task-answer-duplicate": WAITING_ON_YOU,
  "task-record-duplicate-answer": "recording your answer",
  "call-refine-ask": "refining the ask",
  "task-file-unlabeled": "refined, filing for triage",
  "task-file-ticket": "refined, filing the ticket",
  "task-start-work": "ticket filed, starting its work",
  "task-close-intake": "ticket filed",
  "task-close-withdrawn": "ask withdrawn",
  "task-notify-session": "ticket filed, telling the session that filed it",
  "end-intake-closed": "done",
  // research
  "call-answer": "answering the question",
  "task-keep-answer": "keeping the answer",
  "task-post-ack": "answer ready, asking for your ack",
  "task-read-ack": "reading your ack",
  "task-save-answer": "saving the answer to the repo",
  "task-close-accepted": "closing as completed",
  "task-close-dropped": "closing as not planned",
  "end-research-decided": "done",
  // proposal
  "call-draft": "drafting the proposal",
  "call-review": "reviewing the draft",
  "task-post-decision": "pitch PR open, asking for your decision",
  "user-task-answer-decision": "awaiting your decision",
  "task-read-decision": "reading your decision",
  "task-file-build": "accepted, filing the build ticket",
  "task-log-decision": "logging the decision",
  "task-push-catch-up": "bringing the PR up to date",
  "task-start-build": "accepted, starting the build",
  // bug: reproduce first
  "call-reproduce": "reproducing the bug",
  "user-task-answer-reproduce": WAITING_ON_YOU,
  "task-mark-not-reproduced": "could not reproduce, marking it",
  "end-not-reproduced": "could not reproduce",
  // feature: design, and its parts when it splits
  "call-design": "designing",
  "task-file-child": "split, filing the next part",
  "event-child-closed": "split, waiting on its parts",
  "call-replan": "split, replanning the parts",
  "call-parent": "split, a live parent watching the parts",
  "task-reconcile-children": "split, checking which parts closed",
  "task-note-live-ended": "split, the live parent ended; one part at a time",
  "start-child-wait": "split, waiting on its parts",
  "end-child-wait": "split, a part closed",
  "event-restart-live-parent": "split, asked to watch its parts live again",
  "task-restart-live-parent": "split, starting its live parent again",
  "task-apply-replan": "split, updating the parts",
  "task-post-question": "posting a question for you",
  "task-post-work-question": "posting a question for you",
  "task-post-reproduce-question": "posting a question for you",
  "task-note-overflow": "too big to code in one go, noting it",
  // the work
  "call-do-work": "coding",
  "user-task-answer-question": WAITING_ON_YOU,
  "user-task-answer-work": WAITING_ON_YOU,
  "task-close-nothing-to-do": "nothing to change, closing",
  "task-close-split": "split into parts, handing back to the parent",
  "end-nothing-to-do": "nothing to change",
  "end-split": "split, handed back to the parent",
  // the work pushing back on the ticket's Allowed scope (#829)
  // review and merge
  "call-security-review": "coded, in security review",
  "task-open-pr": "coded, PR open, awaiting review",
  "call-ci-check": "waiting on CI build",
  "call-review-pr": "in review",
  "task-rebase-pr": "reviewed, bringing the PR up to date",
  "event-merge-conflict": "merge conflict, back to coding",
  "task-merge-pr": "review done, awaiting merge",
  "task-check-post-deploy": "reading the after-deploy check before merging",
  "call-post-deploy-check": "merged, checking after the deploy",
  "end-post-deploy-checked": "checked after the deploy",
  "task-post-merge-acceptance": "hitlp change, asking you to accept the merge",
  "user-task-answer-merge": "hitlp change, awaiting your acceptance to merge",
  "task-read-merge-acceptance": "hitlp change, reading your answer",
  // after the merge
  "call-verify": "merged, checking the fix on main",
  "task-report-still-broken": "merged, still broken",
  "end-still-broken": "merged, still broken",
  "call-accept": "merged, checking acceptance",
  // the user's acceptance, only on feature instances started before #583
  "task-post-acceptance": "merged, posting the acceptance checklist",
  "task-post-acceptance-reask": "merged, asking whether you meant accepted",
  "user-task-answer-acceptance": "awaiting your acceptance",
  "task-read-acceptance": "reading your acceptance",
  "task-report-unmet": "rejected at acceptance, reporting why",
  "end-not-accepted": "rejected at acceptance",
  "task-close-ticket": "merged, closing the ticket",
  "end-chore-done": "done",
  "end-bug-fixed": "done",
  "end-feature-done": "done",
};

// The post-deploy check (engine/bpmn/post-deploy-check.bpmn,
// engine/docs/post-deploy-check.md) runs inside one call activity, so the
// board's `flow.stage` stays at `call-post-deploy-check` throughout; its
// `flow.step`, the innermost activity's name (CurrentStep.of), tells the
// states apart. Keyed by the name's first line: the BPMN names carry a
// second "(engine)" / "(ops)" line. A step in no row is the check's Smith run.
export const POST_DEPLOY_AWAITING = "merged, awaiting deploy";
export const POST_DEPLOY_CHECKING = "verifying in production";
export const POST_DEPLOY_SCHEDULED = "verifying, next check scheduled";
export const POST_DEPLOY_DEADLINE = "waiting on you: the deploy deadline";
const POST_DEPLOY_STEPS = {
  "merged": POST_DEPLOY_AWAITING,
  "set the deadline": POST_DEPLOY_AWAITING,
  "in production?": POST_DEPLOY_AWAITING,
  "deployed?": POST_DEPLOY_AWAITING,
  "wait for the deploy": POST_DEPLOY_AWAITING,
  "every 12 h": POST_DEPLOY_AWAITING,
  "when next?": POST_DEPLOY_SCHEDULED,
  "how to wait?": POST_DEPLOY_SCHEDULED,
  "wait for the event": POST_DEPLOY_SCHEDULED,
  "wake at": POST_DEPLOY_SCHEDULED,
  "fallback": POST_DEPLOY_SCHEDULED,
  "deadline": POST_DEPLOY_DEADLINE,
  "ask the user": POST_DEPLOY_DEADLINE,
  "decide at the deadline": POST_DEPLOY_DEADLINE,
  "read the answer": POST_DEPLOY_DEADLINE,
  "answer?": POST_DEPLOY_DEADLINE,
};

/** The post-deploy check's state phrase for a flow at `call-post-deploy-check`, from its step; null at any other stage. */
export const postDeployState = (flow) => {
  if (!flow || flow.stage !== "call-post-deploy-check") return null;
  const step = String(flow.step || "").split("\n")[0].trim();
  if (!step) return POST_DEPLOY_AWAITING;
  return POST_DEPLOY_STEPS[step] || POST_DEPLOY_CHECKING;
};

// How an ended post-deploy check ended, from the activity ids its flow took
// (`GET /intake/<instance>`'s `steps`): null when it never ran or has not ended.
export const POST_DEPLOY_ENDS = {
  passed: "checked in production: passed",
  failed: "check failed: bug filed",
  dropped: "deadline reached: dropped",
  deadlineBug: "deadline reached: bug filed",
};
export const postDeployEnd = (steps) => {
  const ids = new Set((steps || []).map((s) => (typeof s === "string" ? s : s && s.activityId)));
  if (ids.has("task-file-deadline-bug")) return POST_DEPLOY_ENDS.deadlineBug;
  if (ids.has("end-check-failed")) return POST_DEPLOY_ENDS.failed;
  if (ids.has("end-check-passed")) return POST_DEPLOY_ENDS.passed;
  if (ids.has("task-read-deadline-answer") && ids.has("end-post-deploy-check")) return POST_DEPLOY_ENDS.dropped;
  return null;
};

// What the scan did with an approval that started nothing (engine/README.md,
// "The issues board": `flow.outcome`), besides refused and deferred.
const OUTCOME_PHRASES = { ignored: "approval ignored, not started" };

// A work item with no ticket yet (work-items.jsx itemRows), by its engine state.
export const WORK_ITEM_PHRASES = { ask: "ask, awaiting refinement", blocked: "blocked", ready: "ready, not started",
  running: "running", done: "done", cancelled: "cancelled" };

/**
 * One phrase for where an issue is with the engine, from the fields
 * `GET /issues` joins on. In order of what matters most to the reader: a
 * question waiting on them, a flow running or done, a refusal, then the
 * labels alone. Never a raw engine step: a running flow reads through
 * STAGE_PHRASES, and a stage it does not know (or an older engine with no
 * `stage`) reads as "<flow> in progress".
 */
export const stateOf = (issue) => {
  if (issue.closed) return { key: issue.closed, text: issue.closed };
  if (issue.workItem) return { key: "item", text: WORK_ITEM_PHRASES[issue.workItem] || issue.workItem };
  if (issue.needOnly) return { key: "need", text: issue.needOnly.text };
  if (issue.pendingQuestion) return { key: "question", text: "asking you" };
  const f = issue.flow;
  if (f) {
    // An intake started by POST /intake has a flow but no approval row, so no outcome.
    const started = f.outcome === "started" || (f.outcome == null && f.instance != null);
    if (started && f.ended) return { key: "done", text: postDeployEnd(f.steps) || "done" };
    if (started) {
      // A split parent waiting on a child: the engine puts "waiting on #n" in its step.
      if (issue.deferred && f.detail && /^waiting on /.test(f.detail)) return { key: "running", text: f.detail };
      const post = postDeployState(f);
      if (post === POST_DEPLOY_DEADLINE) return { key: "question", text: post };
      return { key: "running", text: post || STAGE_PHRASES[f.stage] || `${f.flow || "flow"} in progress` };
    }
    if (f.outcome === "refused") return { key: "refused", text: `refused${f.detail ? `: ${f.detail}` : ""}` };
    if (f.outcome === "deferred") return { key: "deferred", text: f.detail || "waiting on its blockers" };
    const outcome = f.outcome || "unknown";
    return { key: outcome, text: OUTCOME_PHRASES[outcome] || "approved, not started" };
  }
  if (issue.authorized) return { key: "authorized", text: issue.kind ? "approved, not started yet" : "approved, no kind label" };
  if (issue.kind) return { key: "kind", text: "not approved yet" };
  return { key: "none", text: "not triaged" };
};

// ---- issues the board does not list, but /needs does ----------------------
//
// `GET /issues` lists open issues only, while `GET /needs` keeps a merged and
// closed `feature:confirm` ticket started before #583 waiting at the user's
// acceptance (engine/README.md, "Split features"). The view is a superset of Needs you:
// every issue a need row names gets a row here, marked with what it waits on.

// A need-only row's state phrase, per need kind; a kind not here reads as
// "awaiting <kind>".
export const NEED_PHRASES = {
  acceptance: "awaiting acceptance",
  question: "awaiting your answer",
  decision: "awaiting your decision",
  incident: "flow hit an incident",
  reauthorize: "awaiting re-authorization",
  authorize: "awaiting authorization",
};
export const needPhrase = (kind) => NEED_PHRASES[kind] || `awaiting ${kind || "you"}`;

// The board's list filter: every issue, or the ones the logged-in login asked
// for (the engine's `askedBy` on GET /issues, ignoring case).
// Table or Board view (#100094), remembered across sessions.
export const TICKET_VIEWS = [{ id: "table", label: "Table" }, { id: "board", label: "Board" }];
const VIEW_KEY = "tickets.view";
const readView = () => { try { return globalThis.localStorage && globalThis.localStorage.getItem(VIEW_KEY) === "board" ? "board" : "table"; } catch { return "table"; } };
const saveView = (v) => { try { globalThis.localStorage && globalThis.localStorage.setItem(VIEW_KEY, v); } catch { /* not kept */ } };
export const BOARD_FILTERS = [{ id: "all", label: "all" }, { id: "askedByMe", label: "asked by me" }];
export const filterIssues = (issues, filter, login) => {
  const list = Array.isArray(issues) ? issues : [];
  if (filter !== "askedByMe" || !login) return list;
  const me = String(login).toLowerCase();
  return list.filter((i) => i && typeof i.askedBy === "string" && i.askedBy.toLowerCase() === me);
};

/**
 * The board's issues plus one row per issue that `needRows` (src/needs-you.js
 * toRows's rows, keyed by `ticket`) names and the board does not: the first
 * need of that issue (toRows orders them by kind) says its state. A need with
 * no repo or number (an incident on a flow tied to no issue) is left out: it
 * has no issue to list. The added row says `closed` unless its repo is one
 * the board could not list (`failures`), where it is only not listed.
 */
export const withNeeds = (issues, needRows, failures = []) => {
  const list = Array.isArray(issues) ? issues : [];
  const seen = new Set(list.map((i) => `${i.repo}#${i.number}`));
  const added = [];
  for (const n of Array.isArray(needRows) ? needRows : []) {
    if (!n || !n.repo || !Number.isInteger(n.ticket)) continue;
    const key = `${n.repo}#${n.ticket}`;
    if (seen.has(key)) continue;
    seen.add(key);
    added.push({
      repo: n.repo, number: n.ticket, title: n.title || "",
      url: n.url || `https://github.com/${n.repo}/issues/${n.ticket}`,
      labels: [failures.includes(n.repo) ? "not listed" : "closed"],
      needOnly: { kind: n.kind, text: needPhrase(n.kind) },
    });
  }
  return list.concat(added);
};

/**
 * One phrase for a split parent's parts (engine/README.md "Split features"):
 * "3 parts: #61 merged, #62 open, 1 pending". Null for an issue with none.
 */
export const childrenSummary = (issue) => {
  const kids = (issue && issue.children) || [];
  if (!kids.length) return null;
  const filed = kids.filter((c) => c.number != null).map((c) => `#${c.number} ${c.state}`);
  const pending = kids.filter((c) => c.state === "pending").length;
  const dropped = kids.filter((c) => c.state === "dropped").length;
  const live = kids.length - dropped;
  const bits = filed.concat(pending ? [`${pending} pending`] : []).concat(dropped ? [`${dropped} dropped`] : []);
  return `${live} part${live === 1 ? "" : "s"}: ${bits.join(", ")}`;
};

// ---- the progress bar ------------------------------------------------------
//
// Each running issue's bar is driven by `flow.stage` (engine/README.md, "The
// issues board"): the stage puts it in a fixed segment of the bar for its
// kind, which is a fact from the engine; only the fill inside that segment is
// an estimate, elapsed time in the stage (`flow.stageStartedAt`) over
// `flow.stageEstimateMs`, capped at the segment's end so an overrunning step
// never shows a phase it has not reached. The bar ticks from its own timer
// between the 30 s polls and asks the engine for nothing.

export const PROGRESS_TICK_MS = 1000;

// [stage ids, start %, end %] per flow kind, in flow order. A question and its
// answer sit in the segment of the step that asked; a merge conflict goes back
// to coding. A stage in no row (a start event, or one the engine added since)
// sits at the start.
const START = [["start-chore-filed", "start-bug-filed", "start-feature-filed"], 0, 5];
const CI = [["task-open-pr", "call-ci-check"], 50, 55];
const SECURITY = [["call-security-review"], 55, 70];
const CODING = ["call-do-work", "task-post-work-question", "user-task-answer-work", "task-close-nothing-to-do", "task-close-split", "event-merge-conflict"];
export const STAGE_SEGMENTS = {
  chore: [
    START,
    [[...CODING, "user-task-answer-question"], 5, 50],
    CI, SECURITY,
    [["call-review-pr", "task-rebase-pr"], 70, 90],
    [["task-post-merge-acceptance", "user-task-answer-merge", "task-read-merge-acceptance", "task-check-post-deploy", "task-merge-pr", "task-close-ticket"], 90, 96],
    [["call-post-deploy-check"], 96, 100],
  ],
  bug: [
    START,
    [["call-reproduce", "task-post-reproduce-question", "user-task-answer-reproduce", "task-mark-not-reproduced"], 5, 15],
    [CODING, 15, 50],
    CI, SECURITY,
    [["call-review-pr", "task-rebase-pr"], 70, 90],
    [["task-post-merge-acceptance", "user-task-answer-merge", "task-read-merge-acceptance", "task-check-post-deploy", "task-merge-pr"], 90, 92],
    [["call-verify"], 92, 98],
    [["task-report-still-broken", "task-close-ticket"], 98, 99],
    [["call-post-deploy-check"], 99, 100],
  ],
  feature: [
    START,
    [["call-design", "task-post-question", "user-task-answer-question"], 5, 15],
    [[...CODING, "task-note-overflow"], 15, 50],
    CI, SECURITY,
    [["call-review-pr", "task-rebase-pr"], 70, 85],
    [["task-post-merge-acceptance", "user-task-answer-merge", "task-read-merge-acceptance", "task-check-post-deploy", "task-merge-pr"], 85, 88],
    // the ids after call-accept, bar task-report-unmet, are pre-#583 instances' acceptance steps
    [["call-accept", "task-post-acceptance", "task-post-acceptance-reask", "user-task-answer-acceptance", "task-read-acceptance", "task-report-unmet"], 88, 97],
    [["task-close-ticket"], 97, 98],
    [["call-post-deploy-check"], 98, 100],
    // A split parent's own stages: its bar is its parts, merged over total.
    [["task-file-child", "call-parent", "task-reconcile-children", "task-note-live-ended", "start-child-wait", "event-child-closed", "end-child-wait", "event-restart-live-parent", "task-restart-live-parent", "call-replan", "task-apply-replan"], 15, 100],
  ],
  intake: [
    [["start-ask-submitted", "task-open-ask"], 0, 5],
    [["event-intake-authorized"], 5, 10],
    [["task-check-duplicate", "task-post-duplicate", "user-task-answer-duplicate", "task-record-duplicate-answer"], 10, 20],
    [["call-refine-ask", "task-post-question", "user-task-answer-question"], 20, 90],
    [["task-file-ticket", "task-file-unlabeled", "task-start-work", "task-close-intake", "task-close-withdrawn", "task-notify-session"], 90, 100],
  ],
  research: [
    [["start-research-filed"], 0, 5],
    [["call-answer", "task-post-question", "user-task-answer-question"], 5, 70],
    [["task-keep-answer", "task-post-ack"], 70, 75],
    [["user-task-answer-decision", "task-read-ack"], 75, 90],
    [["task-save-answer", "task-close-accepted", "task-close-dropped"], 90, 100],
  ],
  proposal: [
    [["start-proposal-filed"], 0, 5],
    [["call-draft", "task-post-question", "user-task-answer-question"], 5, 50],
    [["call-review"], 50, 75],
    [["task-open-pr"], 75, 80],
    [["task-post-decision", "user-task-answer-decision", "task-read-decision"], 80, 88],
    [["task-file-build", "task-log-decision", "task-rebase-pr", "task-push-catch-up", "task-merge-pr", "event-merge-conflict"], 88, 96],
    [["task-start-build", "task-close-ticket"], 96, 100],
  ],
};

/** `{ start, end }` in percent for a stage of a chore, bug, feature, intake, proposal or research flow; null for any other kind. */
export const stageSegment = (kind, stage) => {
  const rows = STAGE_SEGMENTS[kind];
  if (!rows) return null;
  const row = rows.find(([ids]) => ids.includes(stage));
  return row ? { start: row[1], end: row[2] } : { start: 0, end: 5 };
};

// The post-deploy segment, split: awaiting the deploy is its first half,
// checking (and the waits and the deadline between checks) its second.
export const postDeploySegment = (seg, post) => {
  if (!seg || !post) return seg;
  const mid = (seg.start + seg.end) / 2;
  return post === POST_DEPLOY_AWAITING ? { start: seg.start, end: mid } : { start: mid, end: seg.end };
};

// Stages that wait on someone, not on work: the bar holds at their segment's start.
export const HOLDS = /^(user-task-|task-post-.*question$|event-merge-conflict$|task-rebase-pr$|event-child-closed$|event-intake-authorized$)/;

// "45s" / "12m" / "1h 05m" / "2d 3h", for the tooltip.
export const fmtDuration = (ms) => {
  if (!Number.isFinite(ms)) return "?";
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s}s`;
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  if (d) return `${d}d ${h}h`;
  if (h) return `${h}h ${String(m).padStart(2, "0")}m`;
  return `${m}m`;
};

/**
 * What an engine estimate rests on, for a tooltip, from its `basis` and `runs`
 * (engine/README.md, "Stage estimates"): the scope bucket's median is "from N
 * similar runs", a coarser median "from N runs", and the two that rest on no
 * run say so. Null when the engine sent no basis.
 */
export const estimateSource = (basis, runs) => {
  const n = Number.isFinite(runs) ? runs : 0;
  const plural = n === 1 ? "run" : "runs";
  switch (basis) {
    case "scope": return `from ${n} similar ${plural}`;
    case "tier": case "kind": case "role": return `from ${n} ${plural}`;
    case "default": return "role default";
    case "prior-round": return "this ticket's first round";
    default: return null;
  }
};

const fmtPts = (n) => String(Math.round(n * 10) / 10);
const isDone = (c) => c.state === "merged" || c.state === "satisfied";

/**
 * A split parent's parts (dropped ones already left out) as done over total,
 * weighted by story points: `{ done, total, unit: "pts" | "parts", parts, estimated }`.
 * The missing-points rule: a part with no points (a pending draft not filed
 * yet, or a ticket filed unpointed) weighs the mean of its pointed siblings,
 * rounded to one decimal, and counts in `estimated`. When no part has points
 * the bar counts parts instead (`unit` "parts", nothing estimated).
 */
export const partsProgress = (kids) => {
  const pointed = kids.filter((c) => Number.isFinite(c.points) && c.points > 0);
  if (!pointed.length) {
    return { done: kids.filter(isDone).length, total: kids.length, unit: "parts", parts: kids.length, estimated: 0 };
  }
  const mean = Math.round((10 * pointed.reduce((a, c) => a + c.points, 0)) / pointed.length) / 10;
  const weight = (c) => (Number.isFinite(c.points) && c.points > 0 ? c.points : mean);
  const sum = (xs) => xs.reduce((a, c) => a + weight(c), 0);
  return { done: sum(kids.filter(isDone)), total: sum(kids), unit: "pts", parts: kids.length,
    estimated: kids.length - pointed.length };
};

/**
 * Where a running chore, bug, feature, intake or proposal is, as a bar:
 * `{ start, end, pct, hold, phrase, elapsedMs, estimateMs, title }`, pct in
 * percent. Null for an issue with no running flow. An intake started by
 * `POST /intake` has no approval row, so no outcome, but runs all the same. Holds at the segment start
 * for a stage that waits on someone, an incident, a rework round
 * (`stageRound` > 1), or when the engine sent no start or estimate. A split
 * parent with parts is its parts merged over its parts not dropped, weighted
 * by story points ([partsProgress], with its missing-points rule).
 */
export const progressOf = (issue, now = Date.now()) => {
  const f = issue && issue.flow;
  const started = f && (f.outcome === "started" || (f.outcome == null && f.instance));
  if (!started || f.ended || !f.stage) return null;
  const kind = STAGE_SEGMENTS[f.flow] ? f.flow : issue.kind;
  const seg = postDeploySegment(stageSegment(kind, f.stage), postDeployState(f));
  if (!seg) return null;
  const phrase = stateOf(issue).text;
  const kids = (issue.children || []).filter((c) => c.state !== "dropped");
  if (kids.length) {
    const p = partsProgress(kids);
    const pct = p.total > 0 ? (100 * p.done) / p.total : 0;
    const est = p.estimated ? ` (${p.estimated} of ${p.parts} parts estimated)` : "";
    return { start: 0, end: 100, pct, hold: false, phrase, elapsedMs: null, estimateMs: null, estimated: p.estimated > 0,
      title: `${phrase} · ${fmtPts(p.done)} of ${fmtPts(p.total)} ${p.unit} merged${est}` };
  }
  const enteredAt = Date.parse(f.stageStartedAt);
  const elapsedMs = Number.isFinite(enteredAt) ? Math.max(0, now - enteredAt) : null;
  const estimateMs = Number.isFinite(f.stageEstimateMs) && f.stageEstimateMs > 0 ? f.stageEstimateMs : null;
  // The deploy wait, the next-check wait and the deadline question each have their own clock: they hold.
  const post = postDeployState(f);
  const hold = HOLDS.test(f.stage) || (post != null && post !== POST_DEPLOY_CHECKING) || f.incident != null || (f.stageRound || 1) > 1 || elapsedMs == null || estimateMs == null;
  const pct = hold ? seg.start : seg.start + (seg.end - seg.start) * Math.min(1, elapsedMs / estimateMs);
  const source = estimateMs == null ? null : estimateSource(f.stageEstimateBasis, f.stageEstimateRuns);
  const title = `${phrase} · ${elapsedMs == null ? "?" : fmtDuration(elapsedMs)} elapsed · ${estimateMs == null ? "no estimate" : `est. ${fmtDuration(estimateMs)}`}${source ? ` (${source})` : ""}`;
  return { ...seg, pct, hold, phrase, elapsedMs, estimateMs, title };
};

/**
 * The bar under a row's state. Its own 1 s interval only moves the clock it
 * draws with, and only while the fill can still move; the rows themselves
 * still come from the 30 s poll.
 */
export const ProgressBar = ({ issue, now, className }) => {
  const [tick, setTick] = useState(0);
  const p = progressOf(issue, Math.max(now || 0, tick) || Date.now());
  const live = Boolean(p && !p.hold && p.pct < p.end);
  useEffect(() => {
    if (!live) return undefined;
    const t = setInterval(() => setTick(Date.now()), PROGRESS_TICK_MS);
    return () => clearInterval(t);
  }, [live]);
  if (!p) return null;
  return (
    <div className={"isProgress" + (className ? " " + className : "") + (p.hold ? " hold" : "") + (p.estimated ? " estimated" : "")} title={p.title}
         role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(p.pct)}>
      <div className="isProgressFill" style={{ width: `${p.pct.toFixed(1)}%` }} />
    </div>
  );
};

/** A proposal whose flow has a pitch page (the board's `flow.pitch`): its row and tree entry are marked. */
export const hasPitch = (issue) => !!(issue && issue.flow && issue.flow.pitch);

const Row = ({ issue, onOpenTicket, now }) => {
  const state = stateOf(issue);
  const open = (e) => {
    if (!onOpenTicket || e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    onOpenTicket(issue.number, issue.repo);
  };
  return (
    <tr className={"isRow is-" + state.key}>
      <td className="isNum">{issue.number == null ? <span className="muted" title={issue.id}>ask</span>
        : <a href={issue.url} title={issue.url} onClick={open}>#{issue.number}</a>}
        <TicketAge createdAt={issue.createdAt} now={now} /></td>
      <td className="isTitle">
        <div>{issue.title}</div>
        <div className="isLabels">
          {(issue.labels || []).map((l) => <span key={l} className="isLabel">{l}</span>)}
          {Number.isInteger(issue.points) && <span className="isLabel isPoints" title="Story points (bots/story-points.md)">{issue.points} pts</span>}
          {issue.parent && <span className="isLabel isPart">part of #{issue.parent.number}</span>}
          {hasPitch(issue) && <span className="isLabel isPitch" title={issue.flow.pitch}>pitch</span>}
        </div>
        {childrenSummary(issue) && <div className="isChildren muted">{childrenSummary(issue)}</div>}
      </td>
      <td className="isState" title={issue.flow && issue.flow.instance ? `instance ${issue.flow.instance}` : ""}>
        {state.text}
        {issue.flow && issue.flow.ticketUrl && <> · <a href={issue.flow.ticketUrl}>ticket</a></>}
        <ProgressBar issue={issue} now={now} />
      </td>
    </tr>
  );
};

// ---- the "New intake" composer -------------------------------------------
//
// A POST /intake: an ask textarea and a tier <select>.
// The tier is optional (CLAUDE.md, "Filing tickets: a stated tier binds;
// otherwise the refiner picks"): the form opens on "Auto — refiner picks",
// which posts the ask as typed with no `Tier:` line so the refining Smith
// chooses by bots/model-choice.md. A picked tier makes the posted ask carry
// the same `Tier:` first line .claude/skills/intake/SKILL.md step 2 writes. The rows are the engine's tier map (GET /tiers), read in main
// (src/engine-tiers.js) and handed over as `tiers`. The pure pieces below hold no
// React and no bridge call, so they are tested directly; the two React
// pieces (NewIntakeForm's markup, and Issues' own state wrapper) are the
// only things that touch props like onSubmitIntake/onIntakeEvents.

// The github.com issue URL shape `variables.issueUrl` comes back as, split
// into what onOpenTicket wants: the same repo/number pair the board's own
// rows carry.
const ISSUE_URL_RE = /^https:\/\/github\.com\/([\w.-]+\/[\w.-]+)\/issues\/(\d+)$/;
export const parseIssueUrl = (url) => {
  const m = ISSUE_URL_RE.exec(String(url || ""));
  return m ? { repo: m[1], number: Number(m[2]) } : null;
};

// An `intake` frame's `issueUrl` (engine/README.md, "Events": the `issue`
// state carries the ask work item's ticket `number` and `issueUrl`), else null.
export const issueUrlOf = (frame) => {
  const d = frame && frame.type === "intake" ? frame.data : frame && frame.data === undefined ? frame : null;
  return (d && d.state === "issue" && d.issueUrl) || null;
};

// The literal POST /intake body, kept as a named shape so the test can build
// its expectation the same way the submit call does rather than duplicating
// the object inline. `engine:start-intake` always authorizes as the resolved
// login, like First mate's submit_ask, so `authorize: true` goes with
// `submittedBy` and `repo` whenever there is a login to file as.
export const buildIntakeBody = (ask, submittedBy, repo) => ({
  ask: String(ask == null ? "" : ask),
  ...(submittedBy ? { submittedBy, authorize: true } : {}),
  ...(repo ? { repo } : {}),
});

// One picker row's label, e.g. "5 · opus medium". `row` is one of the
// `{ tier, model, effort }` rows window.fleet.tiers() resolves to.
export const tierLabel = (row) => `${row.tier} · ${row.model} ${row.effort}`;

// The ask as submitted: the user's tier on the first line, then the typed ask
// verbatim — the shape .claude/skills/intake/SKILL.md step 2 builds.
export const tieredAsk = (row, ask) =>
  `Tier: ${row.tier} (${row.model} ${row.effort}) — prescribed by the user; do not re-judge\n${ask}`;

// The label of the select's first option: no tier, the refiner picks.
export const AUTO_TIER_LABEL = "Auto — refiner picks";

// The ask Issues posts: as typed when `tier` is unset (Auto) or not in the
// table, else tieredAsk with the chosen row.
export const intakeAsk = (tiers, tier, ask) => {
  const row = tier == null || !Array.isArray(tiers) ? null : tiers.find((t) => t.tier === tier);
  return row ? tieredAsk(row, ask) : ask;
};

// The intake states after which no `issue` frame will come.
const INTAKE_OVER = new Set(["cancelled", "ended", "filed", "triage"]);

/**
 * Submits an ask and waits for the issue it becomes, with no React, no bridge
 * of its own and no poll: `submit(ask, repo?)` is
 * `window.fleet.startIntake` (`repo` only when the composer picked one, in All
 * repos), resolving to `{ intakeId }` or `{ error }` like every bridge call;
 * `listen(cb)` is `window.fleet.onEngineEvent`, returning its unsubscribe. The
 * engine publishes the `intake` `issue` frame inside the start-message
 * correlation, so it can be on the stream before `POST /intake` returns the
 * id (engine/README.md, "Events"): the listener is on before the submit, the
 * frames seen while the submit is in flight are kept, and once the response
 * names the `intakeId` the kept ones are searched first, then the stream is
 * waited on. The frame is on the stream of the intake's repo: when the
 * composer's `repo` is not the `selected` one (and the selection is not All
 * repos, `selected` null) `hold(repo)` (`engineSubscribe`) is held for the
 * wait and its returned release (`engineUnsubscribe`) called after. Calls
 * `onIntakeId` once, as soon as `submit` succeeds. Resolves to `{ issueUrl }`,
 * or `{ error }` on a failed submit, an intake that ends before its issue
 * exists, or no stream to listen on.
 */
export async function runIntake({ ask, repo, selected = null }, { submit, listen, hold, onIntakeId }) {
  const seen = [];
  let wanted = null;
  let settle = null;
  const found = new Promise((resolve) => { settle = resolve; });
  const consider = (d) => {
    if (!d || typeof d !== "object" || d.intakeId == null) return;
    if (!wanted) { seen.push(d); return; }
    if (d.intakeId !== wanted) return;
    if (d.state === "issue" && d.issueUrl) settle({ issueUrl: d.issueUrl });
    else if (INTAKE_OVER.has(d.state)) settle({ error: `the intake ${d.state} before its ticket was created` });
  };
  let off = null;
  try { off = listen ? listen((frame) => { if (frame && frame.type === "intake") consider(frame.data); }) : null; }
  catch (e) { off = null; }
  const release = repo && selected && repo !== selected && hold ? hold(repo) : null;
  try {
    let started;
    try { started = await (repo ? submit(ask, repo) : submit(ask)); }
    catch (e) { started = { error: e && e.message ? e.message : String(e) }; }
    if (!started || started.error) return { error: (started && started.error) || "no response from the main process" };
    if (onIntakeId) onIntakeId(started.intakeId);
    if (!off) return { error: "no event stream to wait for the ticket on" };
    wanted = started.intakeId;
    const kept = seen.splice(0);
    for (const d of kept) consider(d);
    return await found;
  } finally {
    if (typeof off === "function") off();
    if (typeof release === "function") release();
  }
}

/**
 * The button (closed) or the form (open): an ask textarea, a tier <select>
 * ("Auto — refiner picks", selected while `tier` is unset, then one option per
 * `tiers` row),
 * and either the intake id once accepted, the resolved issue link, or the
 * engine's inline error — same markup conventions as ask-card.jsx's compose
 * box (`.askText`/`.askSend`/`.err`), no new UI library. State is the
 * caller's (Issues, below); this component only draws it.
 *
 * Where it files (#577): `target`, the selected repo, shown as a fixed line;
 * in All repos (`target` null) a required repo <select> over `repos` (the
 * engine's GET /repos names), and Start intake stays disabled until one is
 * picked (`repo`).
 */
export const NewIntakeForm = ({ open, ask, tier, tiers, busy, intakeId, issueUrl, error, onOpen, onClose, onAsk, onTier, onSubmit,
                                target = null, repos = null, repo = null, onRepo }) => {
  const repoName = useRepoName(repos);
  if (!open) return <button type="button" className="newIntakeBtn" onClick={onOpen}>New intake</button>;
  const done = Boolean(issueUrl);
  const rows = Array.isArray(tiers) ? tiers : [];
  const tiersError = tiers && !Array.isArray(tiers) ? tiers.error || "no tier table" : null;
  return (
    <div className="newIntakeForm">
      {target
        ? <div className="newIntakeRepo">files in <b>{target}</b></div>
        : <select className="newIntakeRepoPick" aria-label="repo" required value={repo || ""}
                  disabled={busy || done} onChange={(e) => onRepo && onRepo(e.target.value || null)}>
            <option value="">Pick a repo…</option>
            {(repos || []).map((r) => <option key={r} value={r}>{repoName(r)}</option>)}
          </select>}
      <textarea className="askText newIntakeText" rows={3} value={ask} disabled={busy || done}
                placeholder="what should the fleet do?"
                onChange={(e) => onAsk && onAsk(e.target.value)} />
      <select className="newIntakeTier" aria-label="tier" value={tier == null ? "" : String(tier)}
              disabled={busy || done}
              onChange={(e) => onTier && onTier(e.target.value === "" ? null : Number(e.target.value))}>
        <option value="">{AUTO_TIER_LABEL}</option>
        {rows.map((row) => <option key={row.tier} value={String(row.tier)}>{tierLabel(row)}</option>)}
      </select>
      <div className="askCompose">
        <button type="button" className="askSend" disabled={busy || done || !String(ask || "").trim() || (!target && !repo)}
                onClick={() => onSubmit && onSubmit()}>
          {busy ? "starting…" : "Start intake"}
        </button>
        <button type="button" className="newIntakeCancel" disabled={busy} onClick={() => onClose && onClose()}>Cancel</button>
      </div>
      {tiersError && <div className="err">{`could not load the tiers: ${tiersError}`}</div>}
      {intakeId && <div className="newIntakeStatus">intake {intakeId}{issueUrl ? "" : " started…"}</div>}
      {issueUrl && <div className="newIntakeStatus">→ <a href={issueUrl}>{issueUrl}</a></div>}
      {error && <div className="err">{error}</div>}
    </div>
  );
};

const EMPTY_INTAKE_FORM = { open: false, ask: "", tier: null, busy: false, intakeId: null, issueUrl: null, error: null, repo: null };

/**
 * `result` is what src/engine-data.js fetchIssues returned: `{ issues,
 * failures }` or `{ error }`, which draws the same .err block as the other
 * views, never a blank one. Grouped by repo when there is more than one.
 * `onSubmitIntake` is the "New intake" composer's write and `onIntakeEvents`
 * its wait on the stream (`onHoldRepo` holds another repo's stream for it)
 * — index.jsx wires them to `window.fleet.startIntake`/`onEngineEvent`/
 * `engineSubscribe`, so this file never calls the bridge itself. `tiers` is what
 * `window.fleet.tiers` resolved to (the composer's picker rows, or `{ error }`),
 * null while it loads. `needs` is the Needs you rows (`GET /needs`, through
 * src/needs-you.js): an issue they name that the board does not list is
 * added by withNeeds; a failed or missing needs read adds nothing.
 * `target` is the repo switcher's selection (#577): the board shows that
 * repo's issues and the composer files there; null is All repos, where the
 * composer asks for one of `repos`. `restarting` (an engine update in flight,
 * #1053) draws a read error as the expected restart, muted, not as a failure.
 */
export const Issues = ({ result, onOpenTicket, now, onSubmitIntake, onIntakeEvents, onHoldRepo, tiers, needs,
                         target = null, repos: targetRepos = null, restarting = false, login = null, filter: filterProp,
                         showCompleted = false, onShowCompleted = null, toolbar = null, view: viewProp }) => {
  const [viewState, setViewState] = useState(readView);
  const view = viewProp || viewState;
  const setView = (v) => { setViewState(v); saveView(v); };
  const { issues: board = [], failures = [], error } = result || {};
  const [filterState, setFilter] = useState(BOARD_FILTERS[0].id);
  const filter = filterProp || filterState;
  const issues = filterIssues(result && result.loading ? board : withNeeds(board, needs, failures), filter, login);
  const asking = issues.filter((i) => i.pendingQuestion).length;
  const [intake, setIntake] = useState(EMPTY_INTAKE_FORM);
  const submitIntake = useCallback(async () => {
    if (!onSubmitIntake) return;
    const { ask } = intake;
    const repo = target ? null : intake.repo;
    if (!target && !repo) { setIntake((f) => ({ ...f, error: "pick the repo to file in" })); return; }
    setIntake((f) => ({ ...f, busy: true, error: null, intakeId: null, issueUrl: null }));
    const r = await runIntake({ ask: intakeAsk(tiers, intake.tier, ask), repo, selected: target }, {
      submit: onSubmitIntake, listen: onIntakeEvents, hold: onHoldRepo,
      onIntakeId: (intakeId) => setIntake((f) => ({ ...f, intakeId })),
    });
    if (r.error) { setIntake((f) => ({ ...f, busy: false, error: r.error })); return; }
    const parsed = parseIssueUrl(r.issueUrl);
    setIntake(EMPTY_INTAKE_FORM);
    if (parsed && onOpenTicket) onOpenTicket(parsed.number, parsed.repo);
  }, [intake.ask, intake.tier, intake.repo, target, tiers, onSubmitIntake, onIntakeEvents, onHoldRepo, onOpenTicket]);
  const head = (
    <div className="head">
      <span className="isHeading">
        <h1>Tickets{issues.length ? ` · ${issues.length}` : ""}</h1>
        {onShowCompleted && <label className="isCompleted" title="Add the done and cancelled tickets">
          <input type="checkbox" checked={showCompleted} onChange={(e) => onShowCompleted(e.target.checked)} />
          Show completed
        </label>}
        {login && <span className="isFilter" role="radiogroup" title="Which tickets to list">
          {BOARD_FILTERS.map((f) => <label key={f.id} className={filter === f.id ? "on" : undefined}>
            <input type="radio" name="isFilter" value={f.id} checked={filter === f.id} onChange={() => setFilter(f.id)} />
            {f.label}
          </label>)}
        </span>}
      </span>
      <span className="meta">
        <span className="isFilter isViewToggle" role="radiogroup" title="Table or Board view">
          {TICKET_VIEWS.map((v) => <label key={v.id} className={view === v.id ? "on" : undefined}>
            <input type="radio" name="isView" value={v.id} checked={view === v.id} onChange={() => setView(v.id)} />
            {v.label}
          </label>)}
        </span>
        {asking ? `${asking} asking you` : null}
        {toolbar}
        <NewIntakeForm open={false} onOpen={() => setIntake({ ...EMPTY_INTAKE_FORM, open: true })} />
      </span>
    </div>
  );
  const form = intake.open && (
    <NewIntakeForm {...intake} tiers={tiers} target={target} repos={targetRepos}
      onRepo={(repo) => setIntake((f) => ({ ...f, repo }))}
      onClose={() => setIntake(EMPTY_INTAKE_FORM)}
      onAsk={(ask) => setIntake((f) => ({ ...f, ask }))}
      onTier={(tier) => setIntake((f) => ({ ...f, tier }))}
      onSubmit={submitIntake} />
  );
  if (error && restarting) return <div className="isView">{head}{form}<div className="nmEmpty restarting" title={String(error)}>{RESTARTING_VIEW}</div></div>;
  if (error) return <div className="isView">{head}{form}<div className="err">{String(error)}</div></div>;
  if (!issues.length && !failures.length) {
    return <div className="isView">{head}{form}
      <div className="nmEmpty">{result && result.loading ? "asking the engine…" : filter === "askedByMe" && login ? `No ${showCompleted ? "" : "open "}tickets asked for by ${login}.` : target ? `No ${showCompleted ? "" : "open "}tickets in ${target}.` : `No ${showCompleted ? "" : "open "}tickets in the engine's target repos.`}</div>
    </div>;
  }
  const repos = [...new Set(issues.map((i) => i.repo))];
  return (
    <div className="isView">
      {head}
      {form}
      {failures.map((f) => <div className="nmWarn" key={f}>could not list {f}</div>)}
      {view === "board" ? <StageBoard issues={issues} onOpenTicket={onOpenTicket} now={now} /> : repos.map((repo) => (
        <div key={repo}>
          {repos.length > 1 && <h2>{repo}</h2>}
          <table>
            <colgroup><col className="isNumCol" /><col /><col className="isStateCol" /></colgroup>
            <thead><tr><th>#</th><th>title</th><th>state</th></tr></thead>
            <tbody>
              {issues.filter((i) => i.repo === repo).map((i) =>
                <Row key={i.number == null ? i.id : `${i.repo}#${i.number}`} issue={i} onOpenTicket={onOpenTicket} now={now} />)}
            </tbody>
          </table>
        </div>
      ))}
    </div>
  );
};
