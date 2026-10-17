// saved-chat.jsx — a saved First mate chat (#598), opened from the explorer's
// Saved Chats branch: a summary, the read-only transcript (its most recent
// messages, src/saved-chats.js DISPLAY_CAP) and two actions. Drawing only:
// main shapes the chat (`savedChatView`: `summary`, `window`, `resumable`,
// `why`), and "Put it in First mate →", Continue in terminal (#819) and
// Delete are callbacks. Restore asks for no confirmation, since First mate
// archives the live conversation first and History has it; Delete asks once.

import { Fragment, useState } from "react";
import { Row, HandoffButton, HandoffNotice, handoffAnswer } from "./first-mate.jsx";

const when = (v) => {
  if (v == null || v === "") return "—";
  const d = new Date(typeof v === "number" ? v : String(v));
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString();
};

/** The summary grid's rows, `[label, value]`. */
export const summaryRows = (chat) => {
  const c = chat || {};
  const s = c.summary || {};
  return [
    ["Saved", when(c.savedAt)],
    ["Started", when(c.startedAt || s.first_at)],
    ["Last message", when(s.last_at)],
    ["Repo", c.repo || "All repos"],
    ["Tier", c.tier != null ? String(c.tier) : "—"],
    ["Turns", `${s.turns ?? c.turns ?? 0} (${s.messages ?? c.messageCount ?? 0} messages)`],
    ["Saved from", c.savedFrom || "—"],
    ["Resumes here", c.resumable ? "yes, its Claude session is on this machine"
      : `no, ${c.why || "no session id"}: restoring starts a fresh session given the transcript`],
  ];
};

/**
 * Runs one action (`fn(id)`, restore or delete) and reports it through `set`:
 * `{ busy }`, then `{ ok }` or `{ error }`. A throw or a missing answer is an
 * error, never a silent success.
 */
export const runAction = async (fn, id, set) => {
  set({ busy: true });
  let r;
  try { r = await fn(id); }
  catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
  const next = r && r.ok ? { ok: true } : { error: (r && r.error) || "no response from the main process" };
  set(next);
  return next;
};

/** Continue in terminal's title: the path follows "Resumes here". */
export const handoffTitleOf = (chat) => (chat && chat.resumable
  ? "Copy a command that resumes a fork of its Claude session in a terminal"
  : "Copy a command that starts a new Claude Code session with its transcript in a terminal");

/**
 * The view without state, so a test can draw each step. `restore` and `del`
 * are null, `{ busy }`, `{ ok }`, `{ error }` (and `del` also `{ confirm }`);
 * `handoff` is null, `{ busy }`, `{ error }` or `{ ok, command, mode }`;
 * `onRestore()`, `onHandoff()`, `onAskDelete()`, `onDelete()` and `onKeep()`
 * are the buttons.
 */
export const SavedChatView = ({ result, restore, del, handoff, onRestore, onHandoff, onDismissHandoff, onAskDelete, onDelete, onKeep, onOpenTicket }) => {
  if (!result || result.loading) return <div className="fleet-root"><div className="nmEmpty">loading…</div></div>;
  if (result.error || !result.chat) return <div className="fleet-root"><div className="err">{result.error || "no saved chat"}</div></div>;
  const c = result.chat;
  const win = c.window || { shown: c.messages || [], hidden: 0 };
  return (
    <div className="fleet-root scRoot">
      <div className="scTitle">{c.title}</div>
      <div className="scSummary">
        {summaryRows(c).map(([k, v]) => <Fragment key={k}><span className="scKey">{k}</span><span>{v}</span></Fragment>)}
      </div>
      <div className="scActions">
        <button className="scRestore" disabled={Boolean(restore && restore.busy)} onClick={onRestore}>
          Put it in First mate →
        </button>
        {restore && restore.ok ? <span className="scNote">Restored — the First mate strip has it</span> : null}
        {restore && restore.error ? <span className="scError">Not restored: {restore.error}</span> : null}
        <HandoffButton className="scHandoff" errorClass="scError" title={handoffTitleOf(c)}
                       state={handoff} onHandoff={onHandoff} />
        {del && del.confirm
          ? <>
              <span className="scNote">Delete this saved chat?</span>
              <button className="scDelete" onClick={onDelete}>Delete</button>
              <button className="scKeep" onClick={onKeep}>Keep</button>
            </>
          : <button className="scDelete" disabled={Boolean(del && del.busy)} onClick={onAskDelete}>Delete</button>}
        {del && del.error ? <span className="scError">Not deleted: {del.error}</span> : null}
      </div>
      <HandoffNotice state={handoff} onDismiss={onDismissHandoff} />
      {win.hidden > 0 ? <div className="scHidden">{win.hidden} earlier message{win.hidden === 1 ? "" : "s"} not shown</div> : null}
      <div className="fmTranscript scTranscript">
        {win.shown.map((row, i) => <Row key={i} row={row} onOpenTicket={onOpenTicket} />)}
      </div>
    </div>
  );
};

/**
 * `result` is `{ chat }`, `{ error }` or `{ loading }`. `onRestore(id)` and
 * `onDelete(id)` resolve to `{ ok }` or `{ ok: false, error }`;
 * `onHandoff(id)` to `{ ok, command, mode }` or `{ ok: false, error }`.
 */
export const SavedChat = ({ result, onRestore, onHandoff, onDelete, onOpenTicket }) => {
  const [restore, setRestore] = useState(null);
  const [handoff, setHandoff] = useState(null);
  const [del, setDel] = useState(null);
  const id = result && result.chat ? result.chat.id : null;
  return (
    <SavedChatView result={result} restore={restore} del={del} handoff={handoff} onOpenTicket={onOpenTicket}
      onRestore={() => runAction(onRestore, id, setRestore)}
      onHandoff={async () => { setHandoff({ busy: true }); setHandoff(await handoffAnswer(() => onHandoff(id))); }}
      onDismissHandoff={() => setHandoff(null)}
      onAskDelete={() => setDel({ confirm: true })}
      onDelete={() => runAction(onDelete, id, setDel)}
      onKeep={() => setDel(null)} />
  );
};
