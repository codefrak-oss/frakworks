// incident-actions.jsx — a ticket view's Fix and Propose a fix, inside the
// collapsible Doctor section (flow.jsx DoctorSection), between its heading and
// its runs list (engine/README.md, "Fix and Propose a fix").
//
// Shown while the ticket's flow, or a process it called, is stuck on an open
// Operaton incident. Fix starts a sandboxed Smith run that diagnoses the
// incident and applies a remedy through the engine's retry path; when that did
// not fix it, "fix failed" shows next to the button. Propose a fix starts a
// diagnose-only run; its proposal (at most 40 characters, the engine cuts it)
// shows in a read-only text area next to its button.
//
// One read, window.fleet.incidentFixes (GET /camunda/incident-fixes), re-read
// on the issue's `incident` and `flow` frames; two writes, window.fleet.fixIncident and
// proposeIncidentFix. Who they run as is main's to say, as for a Retry.
import { useEffect, useMemo, useState } from "react";
import { useRefetchOn, coalesce } from "./engine-events.js";

/**
 * The engine's answer ({ incident, fix, propose }) as the buttons draw it.
 * `show` is false with no open incident and no run going: nothing to do.
 */
export function actionState(fixes) {
  const f = fixes || {};
  const fix = f.fix || null, propose = f.propose || null;
  const fixRunning = !!fix && fix.state === "running";
  const proposeRunning = !!propose && propose.state === "running";
  return {
    show: !!f.incident || fixRunning || proposeRunning,
    incidentId: f.incident ? f.incident.id : null,
    fixRunning,
    fixFailed: !!fix && !fixRunning && !!fix.fixFailed,
    fixDetail: fix && !fixRunning && fix.fixFailed ? fix.detail || fix.diagnosis || null : null,
    proposeRunning,
    proposeFailed: !!propose && propose.state === "failed",
    proposal: propose && propose.proposal ? propose.proposal : "",
  };
}

/** The buttons for one state; `onFix`/`onPropose` start a run, `sending` is the one on its way. */
export const IncidentActionsView = ({ fixes, sending, error, onFix, onPropose }) => {
  const s = actionState(fixes);
  if (!s.show) return null;
  const canStart = !!s.incidentId && !sending;
  return (
    <div className="incActions">
      <div className="incRow">
        <button className="pvButton" disabled={!canStart || s.fixRunning} onClick={onFix}>Fix</button>
        {(s.fixRunning || sending === "fix") && <span className="muted">fixing…</span>}
        {s.fixFailed && <span className="incFailed" title={s.fixDetail || undefined}>fix failed</span>}
      </div>
      <div className="incRow">
        <button className="pvButton" disabled={!canStart || s.proposeRunning} onClick={onPropose}>Propose a fix</button>
        <textarea className="incProposal" readOnly rows={1} maxLength={40} value={s.proposal}
          placeholder={s.proposeRunning || sending === "propose" ? "proposing…" : s.proposeFailed ? "the proposal run failed" : ""} />
      </div>
      {error && <div className="err">{error}</div>}
    </div>
  );
};

/** The ticket view's Fix and Propose a fix for `issueUrl`: loads, re-reads on the stream, and starts runs. */
export const IncidentActions = ({ issueUrl }) => {
  const [fixes, setFixes] = useState(null);
  const [sending, setSending] = useState(null); // null | "fix" | "propose"
  const [error, setError] = useState(null);
  // `coalesce`d: a frame's re-read during a read runs after it, never dropped.
  const load = useMemo(() => coalesce(async () => {
    if (!issueUrl) return;
    let r;
    try { r = await window.fleet.incidentFixes(issueUrl); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    // A failed read keeps what was shown; the Processes section says the engine is away.
    if (r && !r.error) setFixes(r.fixes);
  }), [issueUrl]);
  useEffect(() => { setFixes(null); setError(null); load(); }, [issueUrl, load]);
  const s = actionState(fixes);
  // An incident opens, is retried or goes with its instance as `incident`; a fix run's
  // steps and end as `flow`. Either re-reads the fixes.
  useRefetchOn(["incident", "flow"], load, { issueUrl });
  const start = (what) => async () => {
    if (!s.incidentId) return;
    setSending(what); setError(null);
    let r;
    try { r = await (what === "fix" ? window.fleet.fixIncident(s.incidentId) : window.fleet.proposeIncidentFix(s.incidentId)); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    if (r && r.error) setError(r.error);
    await load();
    setSending(null);
  };
  return <IncidentActionsView fixes={fixes} sending={sending} error={error} onFix={start("fix")} onPropose={start("propose")} />;
};
