// update-overlay.jsx — the Update engine overlay (#1053): a modal over the
// main window from the confirm's "Update engine" to its Close, listing the
// update's five steps, each pending, running, done or failed, with the last
// raw lines of the current step's source under it. Drawing only: the
// progress is what main pushes as `engine:update-progress`
// (src/engine-update-progress.js says which event ticks which step); nothing
// here asks for it on a timer.
//
// It ends on "engine updated to sha-<7>" or on the failed step with its last
// line (a failed pull adds that the running engine was left as it is), and
// only then offers Close; it never closes on its own.
//
// Under the steps, during the update and in its end state, "What this update
// brings" (#1398): the PRs merged between the running and the new commit, from
// `progress.before.changes` (src/engine-update-changes.js), each PR linked to
// GitHub and its tickets as frakworks://ticket links; or why the list is missing.

import { useState } from "react";
import { SetupChecklist, setupChecklist } from "./repos.jsx";
import { ModalOverlay } from "./modal-overlay.jsx";

const MARK = { pending: "○", running: "◌", done: "✓", failed: "✗" };

/** The overlay's heading: the update's state in one line. */
export const overlayTitle = (p) => {
  if (!p) return "";
  if (p.ended && p.ended.ok) return p.ended.text;
  if (p.ended) return "The engine update failed";
  return p.resumed ? "Updating the engine (resumed after a relaunch)" : "Updating the engine";
};

/** Which step's source lines are shown: the running one, else the failed one, else none. */
export const currentStep = (p) => {
  const steps = (p && p.steps) || [];
  return steps.find((s) => s.state === "failed") || steps.find((s) => s.state === "running") || null;
};

const Step = ({ s, open }) => (
  <li className={"uoStep uo-" + s.state} data-step={s.id} data-state={s.state}>
    <div className="uoStepHead">
      <span className="uoMark" aria-label={s.state}>{MARK[s.state] || "·"}</span>
      <span className="uoLabel">{s.label}</span>
      <span className="uoSource">{s.source}</span>
    </div>
    {s.detail && (s.state === "done" || s.state === "failed") && <div className="uoDetail">{s.detail}</div>}
    {open && s.notes && s.notes.length > 0 && (
      <div className="uoNotes">{s.notes.map((n, i) => <div key={i}>{n}</div>)}</div>
    )}
    {open && s.tail && s.tail.length > 0 && (
      <pre className="uoTail">{s.tail.join("\n")}</pre>
    )}
  </li>
);

/** The changes section's model: `{ note, items }`; null when there is nothing to say. */
export const changesView = (c) => {
  if (!c) return null;
  if (c.error) return { note: `The list of PRs is missing: ${c.error}`, items: [] };
  const items = c.changes || [];
  if (!items.length) return { note: "No PRs were merged between the two commits.", items: [] };
  return { note: `${items.length} PR${items.length === 1 ? "" : "s"} merged to main since the running build:`, items };
};

const Changes = ({ changes, onOpenTicket }) => {
  const v = changesView(changes);
  if (!v) return null;
  return (
    <div className="uoChanges">
      <div className="uoChangesHead">What this update brings</div>
      <div className="uoNote">{v.note}</div>
      {v.items.length > 0 && (
        <ul className="uoChangeList">
          {v.items.map((x) => (
            <li key={x.sha}>
              <a href={x.prUrl}>PR #{x.pr}</a> {x.title}
              {x.tickets.length > 0 && <> · closes {x.tickets.map((t, i) => <span key={t.n}>{i ? ", " : ""}<a href={t.url} onClick={(e) => { if (onOpenTicket) { e.preventDefault(); onOpenTicket(t.repo, t.n); } }}>#{t.n}</a>{t.title ? ` ${t.title}` : ""}</span>)}</>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};

export const UpdateOverlay = ({ progress, onClose, onOpenTicket }) => {
  if (!progress) return null;
  const cur = currentStep(progress);
  const e = progress.ended;
  return (
    <ModalOverlay label="Update engine">
        <div className={"uoTitle" + (e ? (e.ok ? " uo-ok" : " uo-bad") : "")}>{overlayTitle(progress)}</div>
        <ol className="uoSteps">
          {progress.steps.map((s) => <Step key={s.id} s={s} open={!!cur && cur.id === s.id} />)}
        </ol>
        <Changes changes={progress.before && progress.before.changes} onOpenTicket={onOpenTicket} />
        {e && !e.ok && (
          <div className="uoFail">
            <div>{e.text}{e.detail ? `: ${e.detail}` : ""}</div>
            {e.leftAsIs && <div>The running engine was left as it is.</div>}
          </div>
        )}
        <div className="uoFoot">
          {e
            ? <button className="uoClose" onClick={() => onClose && onClose()}>Close</button>
            : <span className="uoNote">The update runs on its own; quitting the app leaves it going.</span>}
        </div>
    </ModalOverlay>
  );
};

// ---- The Health check overlay (#1243) ---------------------------------------
//
// Config > Repos' **Health check**: the same modal, one step per target repo
// in the page's order, each ticked by the `setup` frames the click's one
// `POST /repos/setup/check {repo: "*", force: true}` brings (applySetupFrame,
// index.jsx). A done step shows its repo's checklist; a failed one the
// engine's error. Nothing here asks for anything on a timer.

export const STREAM_DOWN = "the engine's stream went down";

/**
 * The steps, `{ id, state, setup, error }`, from `check`: `{ repos, setups,
 * down, error }`, `setups` as applySetupFrame builds it from an empty map at
 * the click. A repo with no frame yet is pending, or failed once the stream went down.
 */
export const healthSteps = (check) => ((check && check.repos) || []).map((id) => {
  const s = (check.setups || {})[id.toLowerCase()];
  if (s && s.setupError) return { id, state: "failed", setup: null, error: s.setupError };
  if (s && s.setup && !s.checking) return { id, state: "done", setup: s.setup, error: null };
  if (check.down) return { id, state: "failed", setup: null, error: STREAM_DOWN };
  return { id, state: s && s.checking ? "running" : "pending", setup: null, error: null };
});

/** Whether a done step needs attention: an unreadable repo, or any failing checklist item. */
const needsAttention = (step) => step.state === "failed" || !step.setup.readable || setupChecklist(step.setup).some((i) => i.ok === false);

/** `{ text, ended, ok }`: the overlay's heading, and whether Close is offered. */
export const healthTitle = (check) => {
  if (!check) return { text: "", ended: false, ok: false };
  if (check.error) return { text: `Health check failed: ${check.error}`, ended: true, ok: false };
  const steps = healthSteps(check), n = steps.length;
  if (steps.some((s) => s.state === "pending" || s.state === "running")) return { text: `Checking ${n} repo${n === 1 ? "" : "s"}…`, ended: false, ok: false };
  const k = steps.filter(needsAttention).length;
  return k ? { text: `${k} of ${n} repos need attention`, ended: true, ok: false } : { text: `All ${n} repos set up`, ended: true, ok: true };
};

export const HealthCheckOverlay = ({ check, onAction, onClose }) => {
  const [busy, setBusy] = useState(false);
  if (!check) return null;
  const t = healthTitle(check);
  // Create labels, then its forced re-check of that repo: the step's frames follow.
  const run = onAction ? async (action) => {
    setBusy(true);
    let r;
    try { r = await onAction(action); } catch (e) { r = { error: String(e && e.message ? e.message : e) }; }
    setBusy(false);
    return r && !r.error ? r : null;
  } : null;
  return (
    <ModalOverlay label="Health check">
        <div className={"uoTitle" + (t.ended ? (t.ok ? " uo-ok" : " uo-bad") : "")}>{t.text}</div>
        {!check.error && (
          <ol className="uoSteps">
            {healthSteps(check).map((s) => (
              <li key={s.id} className={"uoStep uo-" + s.state} data-step={s.id} data-state={s.state}>
                <div className="uoStepHead">
                  <span className="uoMark" aria-label={s.state}>{MARK[s.state]}</span>
                  <span className="uoLabel">{s.id}</span>
                </div>
                {s.state === "failed" && <div className="uoDetail">{s.error}</div>}
                {s.state === "done" && <div className="uoChecklist"><SetupChecklist repo={s.id} setup={s.setup} busy={busy} run={run} /></div>}
              </li>
            ))}
          </ol>
        )}
        <div className="uoFoot">
          {t.ended
            ? <button className="uoClose" onClick={() => onClose && onClose()}>Close</button>
            : <span className="uoNote">Each repo is read from GitHub now.</span>}
        </div>
    </ModalOverlay>
  );
};
