// claude-code-session.jsx — a local Claude Code session (#1708), opened from
// Saved Chats > Claude Code: a summary, the read-only transcript (its most
// recent text messages, src/claude-code-sessions.js TRANSCRIPT_CAP) and
// Continue in terminal (#1937): on macOS with iTerm2, a new iTerm2 window
// running `claude --resume <id>` in the session's cwd; else it copies
// `cd <cwd> && claude --resume <id>`.
// Drawing only: main reads the session file; nothing under ~/.claude is written.

import { Fragment, useState } from "react";
import { Row, HandoffButton, HandoffNotice, handoffAnswer, HANDOFF_LABEL } from "./first-mate.jsx";

export const CC_ITERM_TITLE = "Open this session in Claude Code, in a new iTerm2 window";
export const CC_COPY_TITLE = "Copy a command that resumes this Claude Code session in a terminal";
export const CC_ITERM_NOTICE = "Opened this session in Claude Code, in a new iTerm2 window.";

/** Calls `fn()` and answers the button's next state: `{ ok, mode: "iterm" }`, handoffAnswer's copy state, or `{ error }`. */
export const ccHandoffAnswer = async (fn) => {
  let r;
  try { r = await fn(); }
  catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
  if (r && r.ok && r.mode === "iterm") return { ok: true, mode: "iterm" };
  return handoffAnswer(() => r);
};

// The iTerm2 form of Continue in terminal: "Opening…" while main works,
// "Not opened: <reason>" after a refusal.
const ItermHandoff = ({ state, onHandoff, onDismiss }) => {
  const busy = Boolean(state && state.busy);
  return (
    <>
      <div className="scActions">
        <button className="scHandoff" disabled={busy} title={CC_ITERM_TITLE} onClick={() => !busy && onHandoff && onHandoff()}>
          {busy ? "Opening…" : HANDOFF_LABEL}
        </button>
        {state && state.error ? <span className="scError" title={state.error}>Not opened: {state.error}</span> : null}
      </div>
      {state && state.ok && state.mode === "iterm" ? (
        <div className="fmNotice fmHandoffNotice" role="status">
          <span>{CC_ITERM_NOTICE}</span>
          {onDismiss ? <button className="fmHandoffDismiss" title="Dismiss" onClick={onDismiss}>×</button> : null}
        </div>
      ) : null}
    </>
  );
};

const when = (v) => {
  const d = new Date(v);
  return v && !Number.isNaN(d.getTime()) ? d.toLocaleString() : "—";
};

/** The summary grid's rows, `[label, value]`. */
export const sessionSummaryRows = (s) => [
  ["Project", s.cwd || s.project || "—"],
  ["Started", when(s.firstAt)],
  ["Last active", when(s.lastAt)],
  ["Session", s.sessionId || s.id],
];

/** `result` is `{ session }`, `{ error }` or `{ loading }`; `handoff` as in saved-chat.jsx. */
export const ClaudeCodeSessionView = ({ result, handoff, onHandoff, onDismissHandoff }) => {
  if (!result || result.loading) return <div className="fleet-root"><div className="nmEmpty">loading…</div></div>;
  if (result.error || !result.session) return <div className="fleet-root"><div className="err">{result.error || "no session"}</div></div>;
  const s = result.session;
  return (
    <div className="fleet-root scRoot">
      <div className="scTitle">{s.title}</div>
      <div className="scSummary">
        {sessionSummaryRows(s).map(([k, v]) => <Fragment key={k}><span className="scKey">{k}</span><span>{v}</span></Fragment>)}
      </div>
      {s.iterm && !(handoff && handoff.ok && handoff.mode !== "iterm") ? (
        <ItermHandoff state={handoff} onHandoff={onHandoff} onDismiss={onDismissHandoff} />
      ) : (
        <>
          <div className="scActions">
            <HandoffButton className="scHandoff" errorClass="scError" title={CC_COPY_TITLE} state={handoff} onHandoff={onHandoff} />
          </div>
          <HandoffNotice state={handoff} onDismiss={onDismissHandoff} />
        </>
      )}
      {s.hidden > 0 ? <div className="scHidden">{s.hidden} earlier message{s.hidden === 1 ? "" : "s"} not shown</div> : null}
      <div className="fmTranscript scTranscript">
        {(s.rows || []).map((row, i) => <Row key={i} row={row} />)}
      </div>
    </div>
  );
};

/** `onHandoff(id)` resolves to `{ ok, mode: "iterm" }`, `{ ok, command, mode }` or `{ ok: false, error }`. */
export const ClaudeCodeSession = ({ result, onHandoff }) => {
  const [handoff, setHandoff] = useState(null);
  const id = result && result.session ? result.session.id : null;
  return (
    <ClaudeCodeSessionView result={result} handoff={handoff}
      onHandoff={async () => { setHandoff({ busy: true }); setHandoff(await ccHandoffAnswer(() => onHandoff(id))); }}
      onDismissHandoff={() => setHandoff(null)} />
  );
};
