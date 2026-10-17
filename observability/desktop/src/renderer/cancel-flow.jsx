// cancel-flow.jsx — the issue detail's Cancel, in its header (engine/README.md,
// "Cancel and the sweep"). Shown while the issue has a root flow running or
// stuck on an incident: "Cancel…" opens a confirm with a required reason and a
// line on what a cancel does; only "Cancel this flow" sends anything.
//
// One read, window.fleet.processInstances (the list the process view reads),
// and one write, window.fleet.cancelFlow (DELETE /intake/<id>). Who it cancels
// as is main's to say, as for a Retry. A refusal is said under the reason,
// which stays typed; a cancel that went through refreshes the detail.
import { useCallback, useEffect, useState } from "react";

/** The newest root instance a cancel can stop (running, or stuck on an incident), or null. */
export const cancelTarget = (instances) =>
  (instances || []).find((i) => i && !i.ended && (i.state === "running" || i.state === "incident")) || null;

export const IDLE = { phase: "idle", reason: "", error: null };

/**
 * One step of the control: `open` the confirm, type a `reason`, `keep` it
 * (back to idle), `send` (to sending, unless the reason is blank), or the
 * engine's `answer` to a send (idle on success; back to confirm with the
 * reason kept and the engine's message on a refusal).
 */
export function cancelStep(s, ev) {
  switch (ev.type) {
    case "open": return s.phase === "idle" ? { phase: "confirm", reason: "", error: null } : s;
    case "reason": return s.phase === "confirm" ? { ...s, reason: ev.reason, error: null } : s;
    case "keep": return s.phase === "confirm" ? IDLE : s;
    case "send":
      if (s.phase !== "confirm") return s;
      if (!s.reason.trim()) return { ...s, error: "say why: the reason goes on the ticket" };
      return { ...s, phase: "sending", error: null };
    case "answer":
      if (s.phase !== "sending") return s;
      if (ev.result && !ev.result.error) return IDLE;
      return { ...s, phase: "confirm", error: (ev.result && ev.result.error) || "no answer from the engine" };
    default: return s;
  }
}

/**
 * Sends the cancel of `instanceId` when `s` is a confirm with a reason, and
 * answers with the next state. Anything else sends nothing and says why.
 */
export async function submitCancel(s, instanceId, cancelFlow) {
  const sending = cancelStep(s, { type: "send" });
  if (sending.phase !== "sending") return { state: sending, result: null };
  let result;
  try { result = await cancelFlow(instanceId, sending.reason.trim()); }
  catch (e) { result = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
  return { state: cancelStep(sending, { type: "answer", result }), result };
}

export const WHAT_CANCEL_DOES = "Stops its Smith, closes the ticket as not planned and any open PR unmerged. " +
  "The branch and its commits stay; a merged PR stays merged.";

/** The control for one state; the callbacks move it along. */
export const CancelFlowView = ({ target, state, onOpen, onReason, onKeep, onSend }) => {
  if (!target) return null;
  if (state.phase === "idle") {
    return <button className="cfButton" title={`Cancel ${target.processDefinitionKey || "the flow"} ${target.id}`} onClick={onOpen}>Cancel…</button>;
  }
  const sending = state.phase === "sending";
  return (
    <span className="cfWrap"><div className="cfConfirm">
      <div className="cfWhat">{WHAT_CANCEL_DOES}</div>
      <textarea className="cfReason" rows={2} placeholder="Why (required; it goes on the ticket)" value={state.reason}
        disabled={sending} onChange={(e) => onReason && onReason(e.target.value)} />
      <div className="cfRow">
        <button className="cfButton cfDanger" disabled={sending || !state.reason.trim()} onClick={onSend}>
          {sending ? "cancelling…" : "Cancel this flow"}
        </button>
        <button className="cfButton" disabled={sending} onClick={onKeep}>Keep it</button>
      </div>
      {state.error && <div className="err">{state.error}</div>}
    </div></span>
  );
};

/** The issue detail's Cancel for `issueUrl`; `onDone` refreshes the detail after a cancel. */
export const CancelFlow = ({ issueUrl, onDone }) => {
  const [instances, setInstances] = useState([]);
  const [state, setState] = useState(IDLE);
  const [failed, setFailed] = useState([]);
  const load = useCallback(async () => {
    if (!issueUrl) return;
    let r;
    try { r = await window.fleet.processInstances(issueUrl); }
    catch (e) { r = { error: String(e) }; }
    // A failed read keeps what was shown; the Processes section says the engine is away.
    if (r && !r.error) setInstances(r.instances || []);
  }, [issueUrl]);
  useEffect(() => { setInstances([]); setState(IDLE); setFailed([]); load(); }, [issueUrl, load]);
  const target = cancelTarget(instances);
  const send = async () => {
    if (!target) return;
    setState((s) => cancelStep(s, { type: "send" }));
    const { state: next, result } = await submitCancel(state, target.id, window.fleet.cancelFlow);
    setState(next);
    if (result && !result.error) {
      setFailed(result.failedWrites || []);
      await load();
      onDone && onDone();
    }
  };
  return (
    <span className="cfWrap">
      <CancelFlowView target={target} state={state} onOpen={() => setState((s) => cancelStep(s, { type: "open" }))}
        onReason={(reason) => setState((s) => cancelStep(s, { type: "reason", reason }))}
        onKeep={() => setState((s) => cancelStep(s, { type: "keep" }))} onSend={send} />
      {failed.length > 0 && <div className="err cfFailed">Cancelled, but these GitHub writes failed: {failed.join("; ")}</div>}
    </span>
  );
};
