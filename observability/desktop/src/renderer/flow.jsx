// flow.jsx — the engine's side of the ticket detail (detail.jsx): one issue
// as the engine sees it. The board's row (`GET /issues`) says what the engine
// did with the issue; the flow (`GET /intake/<instance>`, engine/README.md)
// says every step it has taken since, across the processes it called, what it
// asked and was told, and the variables it carries. Here are the flow's
// sections (FlowBody, DoctorSection), the `/needs` helpers that pick which row
// the detail's decision card draws, and the off-board read (#548).

import { useEffect, useState } from "react";
import { fmtAgo, fmtDuration, estimateSource } from "./issues.jsx";
import { IncidentActions } from "./incident-actions.jsx";

/** The flow an issue's detail reads: the approval's instance, or the intake a question waits on. */
export const instanceOf = (issue) =>
  (issue && issue.flow && issue.flow.instance) || (issue && issue.pendingQuestion && issue.pendingQuestion.intake) || null;

// A BPMN name wraps with "\n" in the Modeler; the list wants one line.
const stepName = (s) => (s.name || s.activity).replace(/\s*\n\s*/g, " ");

// Start and end events carry no name; they are the processes' seams, and the
// process column already says where one begins.
const shown = (s) => Boolean(s.name);

/**
 * The steps to draw: named ones only, with where each sits. `open` is a step
 * with no `endedAt` in a flow that has not ended (the engine sends times from
 * the version that added them; without them, the last step of a running flow
 * is the open one).
 */
export const stepRows = (flow) => {
  const all = (flow && flow.steps) || [];
  const timed = all.some((s) => s.startedAt);
  const rows = all.filter(shown).map((s) => ({ ...s, label: stepName(s), open: false }));
  if (flow && !flow.ended) {
    if (timed) rows.forEach((r) => { r.open = !r.endedAt; });
    else if (rows.length) rows[rows.length - 1].open = true;
  }
  return rows;
};

// An overrunning step's bar holds here until the step ends: never full by time alone.
export const STEP_HOLD_PCT = 92;

// Steps that wait on someone, not on work: no estimate, so no bar (the board's rule).
const WAITS = /^(user-task-|event-)/;

/**
 * The open step's bar (engine/README.md, "Stage estimates"): `{ pct,
 * overdue, elapsedMs, estimateMs, title }`, pct = elapsed time since the
 * step's `startedAt` over the `estimateMs` the engine sent with it, held at
 * STEP_HOLD_PCT once it overruns. Null for an ended step or flow, a step
 * with no start or no estimate, and one that waits on someone.
 */
export const stepProgress = (step, flow, now = Date.now()) => {
  if (!step || !flow || flow.ended || step.endedAt || WAITS.test(step.activity || "")) return null;
  const started = Date.parse(step.startedAt);
  const estimateMs = step.estimateMs;
  if (!Number.isFinite(started) || !Number.isFinite(estimateMs) || estimateMs <= 0) return null;
  const elapsedMs = Math.max(0, now - started);
  const overdue = elapsedMs > estimateMs;
  const pct = Math.min(STEP_HOLD_PCT, (100 * elapsedMs) / estimateMs);
  const source = estimateSource(step.estimateBasis, step.estimateRuns);
  const label = step.label || stepName(step);
  const title = `${label} · ${fmtDuration(elapsedMs)} elapsed · est. ${fmtDuration(estimateMs)}${source ? ` (${source})` : ""}`;
  return { pct, overdue, elapsedMs, estimateMs, title };
};

const Question = ({ q, now }) => (
  <div className={"flQuestion" + (q.answeredAt ? " answered" : "")}>
    <div className="flQMeta">
      asked {fmtAgo(q.askedAt, now)} ago
      {q.answeredAt ? ` · answered by ${q.answeredBy || "?"} ${fmtAgo(q.answeredAt, now)} ago` : " · waiting on you"}
    </div>
    <pre className="flText">{q.question}</pre>
    {q.answer && <pre className="flText flAnswer">{q.answer}</pre>}
  </div>
);

// The Doctor section: the ticket's doctor runs, GET /doctor/history through
// window.fleet.doctorHistory (engine/README.md, "Doctor"). `result` is
// `{ runs }`, `{ error }` or null while the first read is out. With `ticket`
// (the issue number), each run offers its transcript (DoctorTranscript).
const dash = (x) => (x == null || x === "" ? "—" : String(x));
export const DoctorRuns = ({ result, ticket = null, poll = null }) => {
  if (!result) return <div className="muted">asking the engine for the doctor runs…</div>;
  if (result.error) return <div className="err">{result.error}</div>;
  if (!result.runs || result.runs.length === 0) return <div className="muted">No doctor runs</div>;
  return (
    <table className="flVars flDoctor"><tbody>
      {result.runs.map((r, i) => (
        <tr key={r.id != null ? r.id : i}>
          <td className="flProc">{dash(r.startedAt)}<br />{r.endedAt == null ? "running" : String(r.endedAt)}</td>
          <td>
            <div><b>{dash(r.outcome)}</b> · remedy {dash(r.remedy)} · target {dash(r.target)}</div>
            <div className="muted">stuck since {dash(r.stuckSince)} at</div>
            {(Array.isArray(r.position) ? r.position : r.position == null ? [] : [r.position])
              .map((p, j) => <div key={j} className="flDoctorPos">{String(p)}</div>)}
            {r.diagnosis != null && <div>{String(r.diagnosis)}</div>}
            {r.detail != null && <div className="muted">{String(r.detail)}</div>}
            {ticket != null && <DoctorTranscript ticket={ticket} runId={r.smithRunId} running={r.endedAt == null} poll={poll} />}
          </td>
        </tr>
      ))}
    </tbody></table>
  );
};

// One doctor run's transcript, folded until asked for: its Smith's log file
// in VictoriaLogs, `issue-<n>-doctor-<smithRunId>.log`, read through
// window.fleet.doctorTranscript (src/transcript-data.js fetchDoctorTranscript)
// and formatted as the live pane's. A run that ended reads the same as one
// going; while open, it reads again whenever `poll` changes (the detail's
// Refresh and its poll), so a running doctor's transcript grows. A run with no
// `smithRunId` (an engine before #705, or a Smith not started yet) has none.
export const DoctorTranscript = ({ ticket, runId, running = false, poll = null, initialOpen = false, initial = null }) => {
  const [open, setOpen] = useState(initialOpen);
  const [doc, setDoc] = useState(initial);
  useEffect(() => {
    if (!open || !runId) return undefined;
    let live = true;
    (async () => {
      let d;
      try { d = await window.fleet.doctorTranscript(ticket, runId); }
      catch (e) { d = { error: `IPC failed: ${e && e.message ? e.message : e}`, lines: [] }; }
      if (live) setDoc(d || { error: "no response from the main process", lines: [] });
    })();
    return () => { live = false; };
  }, [open, ticket, runId, poll]);
  if (!runId) return <div className="muted flDoctorLog">no transcript: {running ? "its Smith has not started" : "the engine named no Smith run"}</div>;
  const d = doc || {};
  const lines = Array.isArray(d.lines) ? d.lines : [];
  return (
    <div className="flDoctorLog">
      <button type="button" className="flDoctorLogBtn" title={`issue-${ticket}-doctor-${runId}.log`}
              onClick={() => setOpen(!open)}>{open ? "▾ Hide transcript" : "▸ Transcript"}</button>
      {open && <>
        {d.error && <div className="err">{d.error}</div>}
        <pre className="tdTranscript">
          {lines.length
            ? lines.map((l, i) => <span key={i} className={l.startsWith("→ ") ? "tdToolLine" : undefined}>{`${l}\n\n`}</span>)
            : (doc ? "no events shipped for this run" : "loading transcript…")}
        </pre>
      </>}
    </div>
  );
};

// The issue number of `https://github.com/<owner>/<repo>/issues/<n>`, or null.
export const issueNumberOf = (issueUrl) => {
  const m = /\/issues\/(\d+)$/.exec(String(issueUrl || ""));
  return m ? Number(m[1]) : null;
};

// Folds behind its heading, collapsed by
// default and remembered per viewer under its own key.
const DOCTOR_KEY = "ticket.doctorOpen";

// Reads on open and again whenever `poll` changes: the detail's flow poll
// and its Refresh button each hand it a new object. `initial` is a read to
// start from (a test's; the section reads its own regardless). It reads
// regardless of whether the section is folded open, so expanding it shows
// the current runs without a Refresh.
export const DoctorSection = ({ issueUrl, poll, initial = null }) => {
  const [result, setResult] = useState(initial);
  const [open, setOpen] = useState(() => {
    try { return localStorage.getItem(DOCTOR_KEY) === "true"; } catch (e) { return false; }
  });
  const flipOpen = () => {
    setOpen(!open);
    try { localStorage.setItem(DOCTOR_KEY, String(!open)); } catch (e) { /* per-viewer nicety only */ }
  };
  useEffect(() => {
    let live = true;
    (async () => {
      let r;
      try { r = await window.fleet.doctorHistory(issueUrl); }
      catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
      if (live) setResult(r || { error: "no response from the main process" });
    })();
    return () => { live = false; };
  }, [issueUrl, poll]);
  return (
    <>
      <h2 className="tdFold" onClick={flipOpen} title={open ? "Hide the doctor runs" : "Show the doctor runs"}>
        <span className="tdFoldArrow">{open ? "▾" : "▸"}</span>
        Doctor
      </h2>
      {open && <>
        {/* Fix and Propose a fix (src/renderer/incident-actions.jsx), above the
            runs list; folds with the section since both sit behind `open`. */}
        <IncidentActions issueUrl={issueUrl} />
        <DoctorRuns result={result} ticket={issueNumberOf(issueUrl)} poll={poll} />
      </>}
    </>
  );
};

export const FlowBody = ({ flow, now }) => {
  const v = flow.variables || {};
  return (
    <>
      {flow.questions.length > 0 && <>
        <h2>Questions</h2>
        {flow.questions.map((q) => <Question key={q.id} q={q} now={now} />)}
      </>}
      {v.ask && <>
        <h2>Ask</h2>
        <pre className="flText">{v.ask}</pre>
      </>}
    </>
  );
};

// The needs kinds the detail mounts the inline HITLP task for: each is a HITLP
// task, answered in place (hitlp-task.jsx TicketTask).
export const DECIDED_KINDS = ["question", "acceptance", "decision"];

/** The `/needs` rows (src/needs-you.js toRows) for one issue. */
export const needsOf = (rows, repo, number) =>
  (Array.isArray(rows) ? rows : []).filter((r) => r && r.repo === repo && r.ticket === number);

/** The row of those the detail's inline task waits on, or null. */
export const pendingNeed = (rows) =>
  (rows || []).find((r) => DECIDED_KINDS.includes(r.kind) && r.actionId) || null;

/**
 * The question a `/needs` row waits on (the detail's `pending`): the row's full
 * recorded `question` (the Smith's numbered questions and `ask` block, as the
 * board's `pendingQuestion` carries it), else its one-line `ask`.
 */
export const needQuestion = (row) =>
  row ? { question: row.question || row.ask } : null;

/**
 * The needs kind of a board row's `pendingQuestion`: its `/needs` row, matched
 * on the intake it waits on (the row's actionId), else "question".
 */
export const pendingKind = (pending, rows) => {
  const intake = pending && pending.intake;
  const row = intake && (rows || []).find((r) => r && r.actionId === intake && DECIDED_KINDS.includes(r.kind));
  return row ? row.kind : "question";
};

/**
 * The off-board read (#548): an issue the board has no row for (`GET /issues`
 * lists open issues only, so a closed confirm-mode ticket still waiting on
 * acceptance is missing) is read from `GET /ticket/<owner>/<repo>/<n>` through
 * `fleet.ticketDetail`. Null, with no request, while the board is loading or
 * when it has the row. Otherwise resolves to `{ ticket }` or `{ error }`.
 */
export const readOffBoard = ({ board, issue, repo, number, fleet }) => {
  if (!board || board.loading || issue) return null;
  const [owner, name] = String(repo || "").split("/");
  if (!fleet || !fleet.ticketDetail) return Promise.resolve({ error: "this build has no ticket read" });
  return Promise.resolve()
    .then(() => fleet.ticketDetail(owner, name, number))
    .then((r) => (r && r.ticket ? { ticket: r.ticket } : { error: String((r && r.error) || "no response from the main process") }),
          (e) => ({ error: `IPC failed: ${e && e.message ? e.message : e}` }));
};
