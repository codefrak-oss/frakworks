// hitlp-popup.jsx — the urgent HITLP popup (#2196): an `ask` or `approve`
// whose `priority` is `urgent` pops up over whichever view is shown, as the
// same task view the Needs list opens (./hitlp-task.jsx) in a modal, the app
// blurred behind a red-bordered panel: 1–9 press a button (A / R on an
// approval), Escape snoozes 5 min, Later snoozes one task, Stop all silences
// every popup for an hour. App (index.jsx) feeds it the Needs rows it already
// holds; the choice of row and the snooze and silence bookkeeping are
// ../hitlp-popup.js. The decision goes to the HITLP server through
// `window.fleet.hitlpSurfaceDecide`.

import { useEffect, useState } from "react";
import { ModalOverlay } from "./modal-overlay.jsx";
import { HitlpTask } from "./hitlp-task.jsx";
import { SILENCE_MS, pickPopup, nextWake, snooze, silence } from "../hitlp-popup.js";

/**
 * Over every view: the oldest urgent task for `login` (or everyone) that is
 * neither snoozed nor silenced, from `rows` (App's Needs rows, refetched on
 * the `needs` frame). Gone from `rows`, it closes; a snooze or silence ends
 * with one timeout that redraws, never a poll. A task decided here closes at
 * once, on the press before the server answers, and asks for a refetch
 * (`onDecided`): no `needs` frame comes for a task the engine did not file.
 * A decision that fails brings the task back with the error.
 */
export const HitlpPopup = ({ rows, login, storage, decide = null, onDecided = null }) => {
  const store = storage || (typeof localStorage !== "undefined" ? localStorage : null);
  const [tick, setTick] = useState(0);
  const [decided, setDecided] = useState(() => new Set());
  const [failures, setFailures] = useState({});
  const open = rows.filter((r) => !decided.has(r.actionId));
  const now = Date.now();
  const row = login ? pickPopup(open, login, store, now) : null;
  const wake = login ? nextWake(open, login, store, now) : null;
  useEffect(() => {
    if (wake == null) return undefined;
    const t = setTimeout(() => setTick((n) => n + 1), Math.max(0, wake - Date.now()) + 50);
    return () => clearTimeout(t);
  }, [wake, tick]);
  if (!row) return null;
  const onSnooze = (ms) => { snooze(store, login, row.actionId, ms); setTick((n) => n + 1); };
  const onSilence = () => { silence(store, login, SILENCE_MS); setTick((n) => n + 1); };
  const onSent = (id) => { setDecided((s) => new Set(s).add(id)); setFailures(({ [id]: _, ...f }) => f); };
  const onFailed = (id, error) => {
    setDecided((s) => { const n = new Set(s); n.delete(id); return n; });
    setFailures((f) => ({ ...f, [id]: error }));
  };
  return (
    <ModalOverlay label="A HITLP task needs you" className="hpPanel">
      <div className="fleet-root hpRoot">
        <HitlpTask key={row.actionId} row={row} decide={decide} popup={{ onSnooze, onSilence }}
          initialError={failures[row.actionId] || null} onSent={onSent} onDecided={onDecided} onFailed={onFailed} />
      </div>
    </ModalOverlay>
  );
};
