// timeline.jsx — the ticket detail view's Timeline widget: one card per
// Smith run step (whatever its role), one line per wait, fed by the engine's
// not-yet-shipped `GET /ticket/{owner}/{repo}/{n}/timeline` (#91). Replaces
// the old Lifecycle stage bar, Cost by phase and Runs table (#101); built
// entirely against the fixture contract in observability/desktop/README.md,
// "The ticket detail view", since #91 has not merged — reconcile this shape
// against the real endpoint once it does.
//
// Every card is fixed-size regardless of `durationMs`: only the header's
// split bar encodes a value by length. `TimelineHeader`, `StepCard` and
// `WaitLine` take their data as props so a test can render them without an
// engine, the same as process.jsx's pure parts; `Timeline` does the IPC read.
import { Fragment, useEffect, useMemo, useRef, useState } from "react";

export const fmtDur = (s) => {
  s = Math.round(s || 0);
  if (s >= 86400) return `${Math.floor(s / 86400)}d ${Math.floor((s % 86400) / 3600)}h`;
  if (s >= 3600) return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;
  if (s >= 60) return `${Math.floor(s / 60)}m`;
  return `${s}s`;
};
import { useRefetchOn, coalesce } from "./engine-events.js";
import { stepRows, stepProgress } from "./flow.jsx";
import { PROGRESS_TICK_MS } from "./issues.jsx";

// Short human text per wait cause, and who acts on it.
export const WAIT_TEXT = {
  "person-authorization": "waiting on your authorization",
  "person-question": "waiting on your answer",
  stuck: "stuck",
  ci: "CI check",
  engine: "engine working",
};
export const WAIT_CLASS = { "person-authorization": "amber", "person-question": "amber", stuck: "red", ci: "ci", engine: "grey" };
// A `ci` wait is the engine check running on GitHub: nobody here acts on it.
export const waitActor = (w) =>
  w.cause === "stuck" ? (w.payload && w.payload.retrier) || "you"
    : w.cause === "ci" ? "github" : w.cause === "engine" ? "engine" : "you";
export const waitLabel = (w) =>
  `${fmtDur((w.durationMs || 0) / 1000)} · ${WAIT_TEXT[w.cause] || w.cause} · ${waitActor(w)}`;

// One card per step, whatever its role — the engine already returns every
// Smith run it made (call-refine-ask, call-reproduce, call-verify, ... as well
// as call-do-work/call-security-review/call-review-pr), and each one gets a
// block showing its duration. A step whose round is greater than the previous
// step of the *same* role is an additional "round N · rework" card, never a
// replacement of the earlier one. A step the engine marks `catchUp` (a
// security/correctness run in a round opened by merging main into the branch,
// not by a work push) is never rework: its card reads "round N · merged main".
export const cardsOf = (steps) => {
  const lastRound = {};
  const cards = [];
  for (const s of steps || []) {
    const prev = lastRound[s.role];
    cards.push({ step: s, rework: !s.catchUp && prev != null && s.round > prev });
    lastRound[s.role] = s.round;
  }
  return cards;
};

// Cards and waits, merged into track order by their own start time — a
// timeline reads left-to-right/top-to-bottom in the order things happened,
// even though neither a card nor a wait line is sized by its duration.
export const trackItems = (steps, waits) => {
  const cards = cardsOf(steps).map((c) => ({ kind: "card", start: c.step.start, card: c }));
  const ws = (waits || []).map((w, i) => ({ kind: "wait", start: w.start, wait: w, i }));
  return [...cards, ...ws].sort((a, b) => a.start - b.start);
};

// The post-deploy check on the track (engine/docs/post-deploy-check.md): a
// `deploy` engine event (EngineEvent.Deploy: repo, sha, source) is a line of
// its own, and a check run (a step of role `check`) reads its outcome as a
// phrase. `deploys` is optional on the timeline; an engine without it draws none.
export const CHECK_OUTCOMES = {
  passed: "passed",
  failed: "failed: bug filed",
  not_yet: "not yet: next check scheduled",
};
export const checkOutcome = (step) =>
  step && step.role === "check" && step.outcome ? CHECK_OUTCOMES[step.outcome] || step.outcome : null;
export const deployLabel = (d) =>
  `deployed to production · ${String((d && d.sha) || "").slice(0, 7)}${d && d.source ? ` · ${d.source}` : ""}`;
export const deployItems = (deploys) =>
  (deploys || []).map((d, i) => ({ kind: "deploy", start: Date.parse(d.at) || d.start || 0, deploy: d, i }));

// The state chip: "<state> · round <n>", lowercased. No richer phrase is
// derivable from the fixture contract alone (no per-role-outcome mapping is
// specified), so this is the simplest reading the ticket allows.
export const stateChip = (ticket) => `${String((ticket && ticket.state) || "").toLowerCase()} · round ${ticket && ticket.round}`;

// A `ci` wait whose check is still running (#1678): no conclusion yet, and it
// is the last thing on the track (nothing started after it), so its line
// animates. A concluded or superseded check stays static.
export const ciRunning = (items, it) =>
  !!(it && it.kind === "wait" && it.wait.cause === "ci" && !(it.wait.payload && it.wait.payload.conclusion)
    && items.length && items[items.length - 1] === it);

// A wait's expand/collapse state lives in `Timeline`, as a Set of indices
// into `waits`; this is the pure toggle a click (or a test) drives, kept
// separate from any DOM event so it can be exercised without one (process.jsx
// keeps bpmn-js out of the test bundle for the same reason — no jsdom here).
export const toggleWait = (open, i) => {
  const next = new Set(open);
  if (next.has(i)) next.delete(i); else next.add(i);
  return next;
};

// The running step of a timeline, whatever its role (each step is one Smith
// run, any mode), or null: while there is one, the detail draws the live transcript directly under its card (detail.jsx, #867).
// The first if the engine ever reports two.
export const runningSmith = (timeline) =>
  ((timeline && timeline.steps) || []).find((s) => s && s.running) || null;

// The step the live transcript sits under: the running one, else the most
// recent card by start (between two runs, or after the last), else null when
// the Timeline draws no cards.
export const transcriptAnchor = (timeline) => {
  const running = runningSmith(timeline);
  if (running) return running;
  const cards = cardsOf(timeline && timeline.steps);
  return cards.length ? cards.reduce((a, c) => (c.step.start >= a.step.start ? c : a)).step : null;
};

// Which step a step is (#945): a step has no id, so its role, round and
// start; null for no step.
// A token count, compact: 950, 9.3k, 41k, 1.2M.
export const fmtTokens = (n) => {
  const short = (v, unit) => `${v < 10 ? Number(v.toFixed(1)) : Math.round(v)}${unit}`;
  if (n < 1000) return String(n);
  if (n < 1e6) return short(n / 1000, "k");
  return short(n / 1e6, "M");
};

// A running step's tokens so far, from the engine's live tally (null before its
// first assistant event, and on a finished step): `in · out · cache read · cache write`.
export const tokensLine = (t) =>
  t == null ? null
    : `in ${fmtTokens(t.input)} · out ${fmtTokens(t.output)} · cache read ${fmtTokens(t.cacheRead)} · cache write ${fmtTokens(t.cacheWrite)}`;

export const stepKey = (step) => (step ? `${step.role}|${step.round}|${step.start}` : null);

// ---- pure parts -------------------------------------------------------------

export const TimelineHeader = ({ ticket, totals }) => {
  const t = totals || {};
  const segs = [
    ["working", t.workingMs || 0],
    ["waiting-person", t.waitingPersonMs || 0],
    ["stuck", t.stuckMs || 0],
    ["ci", t.ciMs || 0],
    ["engine", t.engineMs || 0],
  ];
  return (
    <div className="tlHeader">
      <div className="tlHeaderTop">
        <b>#{ticket && ticket.number}</b>
        <span className="tdBody">{ticket && ticket.title}</span>
        <span className="chip tdState">{stateChip(ticket)}</span>
        <span className="muted">{ticket && ticket.kind} · tier {ticket && ticket.tier}</span>
      </div>
      <div className="tlTotals">
        <span>Elapsed <b>{fmtDur((t.elapsedMs || 0) / 1000)}</b></span>
        <span>Working <b>{fmtDur((t.workingMs || 0) / 1000)}</b></span>
        <span>Waiting <b>{fmtDur((t.waitingMs || 0) / 1000)}</b></span>
        <span>CI <b>{fmtDur((t.ciMs || 0) / 1000)}</b></span>
        <span>Cost <b>${(t.costUsd || 0).toFixed(2)}</b></span>
      </div>
      <div className="tlSplitBar">
        {segs.map(([k, v]) => (
          <div key={k} className={`tl-${k}`} style={{ flex: `${Math.max(v, 0)} 0 0` }} title={`${k}: ${fmtDur(v / 1000)}`} />
        ))}
      </div>
    </div>
  );
};

// Fixed-size regardless of durationMs — the header's split bar is the only
// place length encodes a value. A running card is `tlActive` as well: a
// stripe sweeps along its accent border and a highlight across the card
// (styles.css; a solid accent instead under prefers-reduced-motion).
// `controls`, for the running card only, is a row of buttons drawn at
// the card's foot (the transcript's expand/collapse and scraping on/off, #867).
// The open step's bar (flow.jsx, stepProgress) for the flow `detail.jsx`
// loads next to the Timeline: the first open step that has one, or null.
export const openStepProgress = (flow, now = Date.now()) => {
  for (const s of stepRows(flow)) {
    const p = s.open ? stepProgress(s, flow, now) : null;
    if (p) return p;
  }
  return null;
};

// The running card's live bar (#1014, moved from the Steps fold): its own 1 s
// clock moves only the fill; the steps still come from the flow load,
// refetched on the stream's `flow` and `intake` frames. A row of fixed height
// inside the card: the card's size never follows the percentage.
export const StepBar = ({ flow, now }) => {
  const [tick, setTick] = useState(0);
  const at = Math.max(now || 0, tick) || Date.now();
  const bar = openStepProgress(flow, at);
  const live = !!bar;
  useEffect(() => {
    if (!live) return undefined;
    const t = setInterval(() => setTick(Date.now()), PROGRESS_TICK_MS);
    return () => clearInterval(t);
  }, [live]);
  if (!bar) return null;
  return (
    <div className="tlCardBar">
      <div className={"isProgress isProgressStep" + (bar.overdue ? " hold" : "")} title={bar.title}
           role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(bar.pct)}>
        <div className="isProgressFill" style={{ width: `${bar.pct.toFixed(1)}%` }} />
      </div>
    </div>
  );
};

// The review prediction (#1746): Jev's call on what a running review run
// will decide, `value` in -100 (deny) .. +100 (approve), the engine's
// `POST /ticket/…/predict-review`. A bar centred at 0: the fill grows right
// for approve, left for deny, by |value| percent of its half. Null (no answer,
// or none yet) is an empty bar labelled so.
export const predictionLabel = (p) =>
  p && Number.isInteger(p.value) ? `${p.value > 0 ? "+" : ""}${p.value} ${p.value >= 0 ? "approve" : "deny"}` : "no prediction yet";

export const PredictionBar = ({ prediction }) => {
  const v = prediction && Number.isInteger(prediction.value) ? Math.max(-100, Math.min(100, prediction.value)) : null;
  const side = v == null ? "" : v >= 0 ? " approve" : " deny";
  const half = v == null ? 0 : Math.abs(v) / 2;
  return (
    <div className={`tlPredict${side}`} title="Jev's prediction of the review's outcome: -100 deny … +100 approve">
      <div className="tlPredictTrack" role="meter" aria-valuemin={-100} aria-valuemax={100} aria-valuenow={v == null ? undefined : v}>
        <span className="tlPredictZero" aria-hidden="true" />
        {v != null && <div className="tlPredictFill" style={v >= 0 ? { left: "50%", width: `${half}%` } : { right: "50%", width: `${half}%` }} />}
      </div>
      <div className="tlPredictLabel">{predictionLabel(prediction)}</div>
    </div>
  );
};

// The chain behind the bar: `call()` resolves to `{ prediction }` or `{ error }`;
// the next call goes out only once the previous has answered (no timer, so
// no poll: the engine answers an unchanged transcript from what it kept). It
// stops on `stop()`, when `isRunning()` turns false, or on an error answer.
export const predictionChain = ({ call, isRunning, onPrediction, onEnd = () => {} }) => {
  let stopped = false;
  (async () => {
    while (!stopped && isRunning()) {
      let r;
      try { r = await call(); } catch (e) { r = { error: (e && e.message) || String(e) }; }
      if (stopped) return;
      if (!r || r.error) { onEnd(r ? r.error : "no answer"); return; }
      onPrediction(r.prediction);
    }
    if (!stopped) onEnd(null);
  })();
  return () => { stopped = true; };
};

// The running review step of a timeline (role `correctness`), or null.
export const runningReview = (timeline) => {
  const s = runningSmith(timeline);
  return s && s.role === "correctness" ? s : null;
};

// The prediction a card draws: only the running review's card gets one (null
// before the first answer), and only while it runs; any other card, a finished
// review included, gets undefined and draws no bar.
export const cardPrediction = (step, review, predicted) =>
  step && step === review ? (predicted.key && stepKey(step) === predicted.key ? predicted.prediction : null) : undefined;

// The running card's account label (#2600): the AI account the live run is
// on, from the timeline step's `account`; null when the engine does not know it.
export const accountLabel = (step) =>
  step && typeof step.account === "string" && step.account.trim() ? `account: ${step.account.trim()}` : null;

// `flow`, for the running card only, is the flow whose open step feeds its bar.
// `prediction` is given to a review card (role `correctness`) the chain ran
// for: `undefined` draws no bar, null the empty one.
export const StepCard = ({ step, rework, controls = null, flow = null, now = 0, prediction = undefined }) => {
  const s = step || {};
  const cls = `tlCard ${s.role || ""}${s.running ? " running tlActive" : ""}`;
  const soFar = s.running ? " so far" : "";
  return (
    <div className={cls}>
      {s.running && <span className="tlSweep" aria-hidden="true" />}
      {s.running && <span className="tlPulse" aria-hidden="true" />}
      {s.catchUp
        ? <div className="tlRework tlCatchUp">round {s.round} · merged main</div>
        : rework && <div className="tlRework">round {s.round} · rework</div>}
      <div className="tlCardRole">{s.role}</div>
      <div className="tlCardDur">{fmtDur((s.durationMs || 0) / 1000)}{soFar}</div>
      <div className="tlCardModel">{s.model} · {s.effort}</div>
      {s.running && accountLabel(s) && <div className="tlCardAccount">{accountLabel(s)}</div>}
      {s.cost != null && <div className="tlCardCost">{`$${s.cost.toFixed(2)}${soFar}`}</div>}
      <div className="tlCardTurns">{s.turns == null ? "turns not yet known" : `${s.turns} turns${soFar}`}</div>
      {s.running && s.tokens && (
        <div className="tlCardTokens"
             title={`input ${s.tokens.input} · output ${s.tokens.output} · cache read ${s.tokens.cacheRead} · cache write ${s.tokens.cacheWrite} tokens so far`}>
          {tokensLine(s.tokens)} so far
        </div>
      )}
      <div className="tlCardOutcome">{checkOutcome(s) || s.outcome}</div>
      {s.role === "correctness" && prediction !== undefined && <PredictionBar prediction={prediction} />}
      {s.running && flow && <StepBar flow={flow} now={now} />}
      {controls && <div className="tlCardCtl">{controls}</div>}
    </div>
  );
};

// A running CI check's elapsed time, live (#1690): the engine's `durationMs`
// was true when the timeline was loaded at `since`; it has grown by the time
// passed since. Never below what the engine said.
export const liveDurationMs = (durationMs, since, now) =>
  (durationMs || 0) + Math.max(0, (now || 0) - (since || 0));

// One dotted line, fixed size regardless of durationMs. Clicking it toggles
// `expanded`, which `Timeline` owns; this component is otherwise pure.
// `running` marks a CI check still in progress: its line pulses (CSS only).
// While `running`, its own 1 s clock counts the elapsed time up; a finished
// check shows the engine's final duration and keeps no timer.
export const DeployLine = ({ deploy }) => (
  <div className="tlWait tlDeploy" title={deploy && deploy.sha}>{deployLabel(deploy)}</div>
);

export const WaitLine = ({ wait, expanded, onToggle, running = false }) => {
  const cls = WAIT_CLASS[wait.cause] || "grey";
  const [since, setSince] = useState(() => Date.now());
  const [now, setNow] = useState(since);
  useEffect(() => { const t = Date.now(); setSince(t); setNow(t); }, [wait.durationMs]);
  useEffect(() => {
    if (!running) return undefined;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [running]);
  const shown = running ? { ...wait, durationMs: liveDurationMs(wait.durationMs, since, now) } : wait;
  return (
    <div className={`tlWait ${cls}${running ? " running" : ""}${expanded ? " expanded" : ""}`}
         onClick={onToggle} title={expanded ? "Collapse" : "Expand"}>
      {running && <span className="tlWaitDot" aria-label="running" />}
      <span className="tlWaitLabel">{waitLabel(shown)}</span>
      {expanded && <WaitMinor payload={wait.payload} cause={wait.cause} />}
    </div>
  );
};

// The minor steps/question+answer/incident+retry/check+conclusion a wait's payload carries,
// shown inline once its line is expanded.
const WaitMinor = ({ payload, cause }) => {
  const p = payload || {};
  return (
    <div className="tlMinor">
      {cause === "person-question" && (
        <>
          {p.question && <div><b>Q:</b> {p.question}</div>}
          {p.answer && <div><b>A:</b> {p.answer}</div>}
        </>
      )}
      {cause === "stuck" && p.incident && <div><b>Incident:</b> {p.incident}</div>}
      {cause === "stuck" && p.retrier && <div><b>Retrier:</b> {p.retrier}</div>}
      {cause === "ci" && p.check && <div><b>Check:</b> {p.check}</div>}
      {cause === "ci" && p.conclusion && <div><b>Conclusion:</b> {p.conclusion}</div>}
      {(p.steps || []).map((st, i) => (
        <div key={i} className="tlMinorStep">
          {st.name}{st.start != null && st.end != null ? ` · ${fmtDur(Math.max(0, st.end - st.start))}` : ""}
        </div>
      ))}
    </div>
  );
};

// The banner an intake gets when its refiner said `insufficient-permissions` (#443): the
// engine's `ticket.blocked`, `{kind, summary, reason}`. `TicketDetail` draws it
// at the top of the view, above everything the Timeline shows; the incident's
// own wait line stays as it is. Nothing at all without `blocked`.
export const BlockedBanner = ({ blocked }) => {
  if (!blocked) return null;
  return (
    <div className="tdBlocked" role="alert">
      <h3 className="tdBlockedHead">Blocked: can't do with current permissions</h3>
      {blocked.summary && <div className="tdBlockedSummary">{blocked.summary}</div>}
      {blocked.reason && (
        <details className="tdBlockedReason">
          <summary>Full reason</summary>
          <div className="tdBlockedReasonText">{blocked.reason}</div>
        </details>
      )}
    </div>
  );
};

/** What `onTimeline` hears for a load's result: its timeline, else null (#867). */
export const heardTimeline = (r) => (r && r.timeline) || null;

// ---- the widget --------------------------------------------------------------

/**
 * The ticket detail's Timeline: the engine's `/ticket/{owner}/{repo}/{n}/timeline`,
 * re-read on the issue's `flow`, `session` and `incident` frames. `.err` for an
 * unreachable engine or non-2xx, a `.muted` empty state for a 404 (no history
 * yet, distinct from an error) — neither blanks the rest of the ticket page.
 * `onTimeline` hears each load, for what the page shows outside the widget
 * (`TicketDetail`'s BlockedBanner, where the live transcript goes): the
 * timeline, or null when the load drew none (an error or a 404), so the page
 * never places anything under a card the widget is not drawing. `initial` is a
 * timeline to start from (a test's: renderToStaticMarkup runs no effects).
 * While a step runs, whatever its role, its card carries `runningControls` and
 * `runningSlot` is drawn directly after it (the live transcript, #867), and
 * the bar of `flow`'s open step (`StepBar`, #1014). With no step running,
 * `idleSlot` is drawn after the most recent card (transcriptAnchor). `now` is ms.
 */
export const Timeline = ({ owner, repo, ticket, onTimeline, initial = null, runningControls = null, runningSlot = null, idleSlot = null, flow = null, now = 0 }) => {
  const [result, setResult] = useState(() => (initial ? { timeline: initial } : { loading: true }));
  const [expanded, setExpanded] = useState(() => new Set());
  // A ref, so a new callback each parent render does not re-fire `load`.
  const heard = useRef(onTimeline);
  heard.current = onTimeline;

  // `coalesce`d: a frame's re-read during a read runs after it, never dropped.
  const load = useMemo(() => coalesce(async () => {
    if (!owner || !repo || !ticket) return;
    let r;
    try { r = await window.fleet.ticketTimeline(owner, repo, ticket); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setResult(r);
    if (heard.current) heard.current(heardTimeline(r));
  }), [owner, repo, ticket]);

  useEffect(() => { setResult({ loading: true }); setExpanded(new Set()); load(); }, [owner, repo, ticket, load]);

  const timeline = result.timeline;
  useRefetchOn(["flow", "session", "incident"], load, { repo: `${owner}/${repo}`, number: ticket });

  // The review prediction (#1746): while a review step runs, chained calls;
  // the bar is removed from the card once the step ends.
  const [predicted, setPredicted] = useState({ key: null, prediction: null });
  const review = runningReview(timeline);
  const reviewKey = stepKey(review);
  const reviewNow = useRef(reviewKey);
  reviewNow.current = reviewKey;
  useEffect(() => {
    if (!reviewKey || !owner || !repo || !ticket) return undefined;
    setPredicted((p) => (p.key === reviewKey ? p : { key: reviewKey, prediction: null }));
    return predictionChain({
      call: () => window.fleet.predictReview(`${owner}/${repo}`, ticket),
      isRunning: () => reviewNow.current === reviewKey,
      onPrediction: (prediction) => setPredicted({ key: reviewKey, prediction }),
    });
  }, [owner, repo, ticket, reviewKey]);

  const toggle = (i) => setExpanded((prev) => toggleWait(prev, i));
  const smith = runningSmith(timeline);
  const anchor = transcriptAnchor(timeline);
  const items = timeline ? [...trackItems(timeline.steps, timeline.waits), ...deployItems(timeline.deploys)].sort((a, b) => a.start - b.start) : [];

  return (
    <>
      <h2>Timeline</h2>
      {result.loading && <div className="muted">asking the engine for the timeline…</div>}
      {result.error && <div className="err">{result.error}</div>}
      {result.notFound && <div className="muted">no process history for this ticket</div>}
      {timeline && (
        <>
          <TimelineHeader ticket={timeline.ticket} totals={timeline.totals} />
          <div className="tlTrack">
            {items.map((it) => it.kind === "deploy" ? <DeployLine key={`d-${it.i}`} deploy={it.deploy} /> : it.kind === "card"
              ? (it.card.step === smith
                ? <Fragment key={`c-${it.card.step.role}-${it.card.step.round}`}>
                    <StepCard step={it.card.step} rework={it.card.rework} controls={runningControls} flow={flow} now={now}
                              prediction={cardPrediction(it.card.step, review, predicted)} />
                    {runningSlot}
                  </Fragment>
                : it.card.step === anchor && idleSlot
                  ? <Fragment key={`c-${it.card.step.role}-${it.card.step.round}`}>
                      <StepCard step={it.card.step} rework={it.card.rework} />
                      {idleSlot}
                    </Fragment>
                  : <StepCard key={`c-${it.card.step.role}-${it.card.step.round}`} step={it.card.step} rework={it.card.rework} />)
              : <WaitLine key={`w-${it.i}`} wait={it.wait} expanded={expanded.has(it.i)} onToggle={() => toggle(it.i)} running={ciRunning(items, it)} />)}
          </div>
        </>
      )}
    </>
  );
};
