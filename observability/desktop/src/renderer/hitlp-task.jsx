// hitlp-task.jsx — one HITLP task over the work area, opened from Needs you
// (#2648): decision first, the story below. On top the priority and time left,
// who sent it (verified by the HITLP server), the headline (../hitlp-task.js
// headlineOf), the why as the sender states it, the command or script in a
// viewer of its own, the judge's odds and the decision bar. Below, how the
// task reached you: its lineage as a conversation, first step first, each
// step reported by the sender and not verified, ending with this request.
// Digest, scope and payload are folded away at the bottom.
//
// It is the one task view (#100070): HITLP Requests opens it under its list,
// a ticket's detail and a proposal's view mount it inline through `TicketTask`
// for the task the ticket's flow waits on, and the urgent popup
// (./hitlp-popup.jsx) draws it in a modal: with
// `popup` ({ onSnooze, onSilence }) the back link gives way to who needs you,
// the buttons answer 1–9 (and A / R on an approval), Escape snoozes 5 min, and
// a Later row snoozes or silences every popup for an hour.
import { useEffect, useRef, useState } from "react";
import { bodyHtml, sanitizeBody } from "./markdown.js";
import { JsonView } from "./json-view.jsx";
import { TeachButtons } from "./judge-teach.jsx";
import { buttonsFor, pressOf, surfaceDecide, sourceText, lineageAddsNothing, Countdown } from "./needs-you.jsx";
import { scriptOf, headlineOf, oddsOf, leanOf, initialsOf, clockOf, leftShare } from "../hitlp-task.js";
import { SNOOZES, HINT, headerText, keyAction } from "../hitlp-popup.js";
import { DesignDeckLink } from "./design-deck.jsx";

const Bell = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" /><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
  </svg>
);

// The button the Something else box sends with.
const ANSWER = { label: "Answer", outcome: "answered", other: true };

const party = (p) => (typeof p === "string" ? p
  : p && typeof p === "object" ? p.id || p.login || p.name || p.client || null : null);

const Check = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3"
    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>
);

/** The time-left ring: the share of the task's time still left, amber under a third, red under a tenth. */
const Ring = ({ share }) => {
  if (share == null) return null;
  const c = 2 * Math.PI * 15;
  const tone = share < 0.1 ? "low" : share < 0.34 ? "mid" : "ok";
  return (
    <svg className={"htRing " + tone} width="36" height="36" viewBox="0 0 36 36" aria-hidden="true">
      <circle cx="18" cy="18" r="15" fill="none" className="htRingTrack" strokeWidth="4" />
      <circle cx="18" cy="18" r="15" fill="none" className="htRingFill" strokeWidth="4" strokeLinecap="round"
        strokeDasharray={c} strokeDashoffset={c * (1 - share)} transform="rotate(-90 18 18)" />
    </svg>
  );
};

/** The command or script an approval runs: numbered lines in a box of its own height, Copy and Expand. */
export const ScriptBox = ({ script, compact = false }) => {
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  if (!script) return null;
  const copy = () => {
    try {
      const p = navigator.clipboard && navigator.clipboard.writeText(script.text);
      if (p && p.then) p.then(() => setCopied(true), () => {});
    } catch { /* no clipboard: the text is still on screen */ }
  };
  const width = String(script.lines).length;
  return (
    <div className={"htScript" + (compact ? " compact" : "")}>
      <div className="htScriptBar">
        <span className="htMono">{[script.user && script.host ? `${script.user}@${script.host}` : script.host, script.shell].filter(Boolean).join(" · ")}</span>
        <span>{script.lines === 1 ? "1 line" : `${script.lines} lines`} · {script.chars} characters</span>
        <span className="htGrow" />
        <button type="button" className="htSmallBtn" onClick={copy}>{copied ? "Copied" : "Copy"}</button>
        {script.lines > 8 && <button type="button" className="htSmallBtn" onClick={() => setOpen(!open)}>{open ? "Collapse" : "Expand"}</button>}
      </div>
      <pre className={"htScriptBody" + (open ? " open" : "")}>{script.text.split("\n").map((l, i) => (
        <div key={i} className="htLine"><span className="htLineNo" aria-hidden="true">{String(i + 1).padStart(width, " ")}</span>{l || " "}</div>
      ))}</pre>
    </div>
  );
};

/** The judge's lean as a bar over allow, escalate and deny, with the numbers. */
export const OddsBar = ({ rec }) => {
  const odds = oddsOf(rec);
  if (!odds) return null;
  const lean = leanOf(odds);
  const by = rec && rec.by && typeof rec.by === "object" ? rec.by.judge || party(rec.by) : party(rec && rec.by);
  const pct = (v) => `${Math.round(v * 100)}%`;
  return (
    <div className="htOdds">
      <span>{by ? `Judge (${String(by).replace(/^in-process /, "")})` : "Judge"} leans <strong className={"htLean " + lean}>{lean}</strong></span>
      <span className="htOddsBar" aria-hidden="true">
        <span className="allow" style={{ width: pct(odds.allow) }} />
        <span className="escalate" style={{ width: pct(odds.escalate) }} />
        <span className="deny" style={{ width: pct(odds.deny) }} />
      </span>
      <span className="htMuted">allow {odds.allow.toFixed(2)} · escalate {odds.escalate.toFixed(2)} · deny {odds.deny.toFixed(2)}
        {typeof rec.confidence === "number" ? ` · confidence ${rec.confidence.toFixed(2)}` : ""}</span>
    </div>
  );
};

/** One step of how the task reached you: who, what they said, where it came from. */
const Step = ({ who, label, trust, children, self = false, last = false }) => (
  <div className={"htStep" + (self ? " self" : "")}>
    <div className="htRail">
      <span className="htAvatar">{initialsOf(who)}</span>
      {!last && <span className="htRailLine" />}
    </div>
    <div className="htStepBody">
      <span className="htStepHead"><strong>{who || "someone"}</strong> {label}{trust}</span>
      <div className="htBubble">{children}</div>
    </div>
  </div>
);

/**
 * The task in full. `onSent(actionId)` fires on the press (the view closes
 * before the server answers), then `onDecided(actionId)` or `onFailed(actionId,
 * error)`; `initialError` draws a refused decision's error. `decide`, `teach`,
 * `teachReady` and `now` stand in for the bridge and the clock (tests).
 * `initialOther` opens the Something else box (tests).
 * `popup` ({ onSnooze(ms), onSilence() }) draws it as the urgent popup; the
 * "← Needs you" back link is drawn only with an `onBack`.
 */
export const HitlpTask = ({ row, now = null, initialError = null, decide = null, teach = null, teachReady = null,
  onBack, onSent = null, onDecided = null, onFailed = null, popup = null, sanitize = sanitizeBody, initialOther = false }) => {
  const [text, setText] = useState("");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState(initialError);
  const [taught, setTaught] = useState(null);
  const [other, setOther] = useState(initialOther);
  const spec = buttonsFor(row);
  const approve = row.kind === "approve";
  const call = decide || surfaceDecide(row);
  const head = headlineOf(row);
  const restHtml = head.rest ? bodyHtml(head.rest, null, sanitize) : null;
  const whyHtml = row.why ? bodyHtml(row.why, null, sanitize) : null;
  const script = scriptOf(row);
  const sender = party((row.attested || {}).requester);
  const behalf = party((row.attested || {}).onBehalfOf);
  const at = now != null ? now : Math.floor(Date.now() / 1000);
  const all = Array.isArray(row.lineage) ? row.lineage : [];
  const steps = lineageAddsNothing(all, row.why) ? [] : [...all].reverse();
  const send = async (b) => {
    if (busy || done) return;
    // Something else reveals its box first; its Answer button sends the box.
    const p = pressOf(spec, b, other, text);
    if (p.reveal) { setOther(true); setError(null); return; }
    if (p.error) { setError(p.error); return; }
    const answer = p.answer;
    setBusy(true); setError(null);
    if (onSent) onSent(row.actionId);
    let failed = null;
    try {
      const r = await call(row.actionId, b.outcome, answer === undefined ? null : answer, comment.trim() ? comment.trim() : null);
      if (r && r.ok) { setDone(true); if (onDecided) onDecided(row.actionId); }
      else failed = (r && r.error) || "the HITLP server did not answer";
    } catch (e) { failed = e && e.message ? e.message : String(e); }
    if (failed) { setError(failed); if (onFailed) onFailed(row.actionId, failed); }
    setBusy(false);
  };
  const sendRef = useRef(send);
  sendRef.current = send;
  const popupRef = useRef(popup);
  popupRef.current = popup;
  // A and R approve and reject, unless a box has focus. As the popup, 1–9 press a
  // button and Escape snoozes; as the work-area view, nothing while the popup is up.
  useEffect(() => {
    const onKey = (e) => {
      const t = e.target;
      const inBox = !!t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA");
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      const p = popupRef.current;
      if (!p && typeof document !== "undefined" && document.querySelector(".hpPanel")) return;
      if (p) {
        const a = keyAction(e.key, spec.buttons, inBox);
        if (a) {
          e.preventDefault();
          if (a.snooze) { if (p.onSnooze) p.onSnooze(a.snooze); } else sendRef.current(spec.buttons[a.press]);
          return;
        }
      }
      if (!approve || inBox) return;
      const k = e.key.toLowerCase();
      const b = k === "a" ? spec.buttons.find((x) => x.outcome === "approved") : k === "r" ? spec.buttons.find((x) => x.outcome === "rejected") : null;
      if (b) { e.preventDefault(); sendRef.current(b); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [row.actionId, approve]);
  const ends = clockOf(row.deadline);
  const summary = row.context && typeof row.context.summary === "string" ? row.context.summary : null;
  return (
    <div className={"htView" + (popup ? " htPopup" : "")} data-task={row.actionId}>
      <div className="htTop">
        {popup
          ? <span className="htNeeds"><Bell /> {headerText(row)}</span>
          : onBack ? <button type="button" className="htBack" onClick={() => onBack()}>← Needs you</button> : <span />}
        <div className="htWhen">
          {row.priority && row.priority !== "normal" && <span className={"htPriority " + row.priority}>{row.priority.toUpperCase()}</span>}
          <Ring share={leftShare(row, at)} />
          <span className="htWhenText"><Countdown deadline={row.deadline} now={now} />
            {ends && <span className="htMuted">ends at {ends}</span>}</span>
        </div>
      </div>

      <div className="htAsk">
        <span className="htEyebrow">{approve ? "APPROVAL NEEDED" : "QUESTION"}
          {sender && <> · FROM {sender}{behalf ? ` FOR ${behalf}` : ""} <span className="htVerified"><Check /> VERIFIED</span></>}</span>
        <h1 className={"htHeadline" + (head.text.length > 80 ? " long" : "")}>{head.text}{head.code && <> <code className="htInline">{head.code}</code>{head.where ? ` ${head.where}` : ""}</>}</h1>
        {head.rest && (restHtml
          ? <div className="htQuestionMore htMd" dangerouslySetInnerHTML={{ __html: restHtml }} />
          : <p className="htQuestionMore">{head.rest}</p>)}
        {row.why && (whyHtml
          ? <div className="htWhy"><span className="htMuted">Why, as {sender || "the sender"} states it:</span><div className="htMd" dangerouslySetInnerHTML={{ __html: whyHtml }} /></div>
          : <p className="htWhy"><span className="htMuted">Why, as {sender || "the sender"} states it:</span> {row.why}</p>)}
        {summary && summary !== row.why && <p className="htContext">{summary}</p>}
        {row.repo && row.ticket != null && <DesignDeckLink repo={row.repo} number={row.ticket} />}
      </div>

      {script && !head.code && <ScriptBox script={script} />}

      <div className="htDecide">
        <OddsBar rec={row.recommendation} />
        {done ? <div className="htDone">{approve ? "Decided" : "Answered"}{taught ? `, taught: ${taught}` : ""}.</div> : <>
          {spec.input && (other || !spec.buttons.some((b) => b.other)) && <input className="htAnswer" aria-label="Your answer" type="text" inputMode={spec.input === "number" ? "decimal" : undefined} value={text}
            placeholder={spec.input === "json" ? "answer (JSON or text)" : spec.input === "number" ? "a number" : "your answer"} disabled={busy}
            onChange={(e) => setText(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter" && (other || spec.buttons.length === 1)) send(other ? ANSWER : spec.buttons[0]); }} />}
          <div className="htButtons">
            {spec.buttons.map((b, i) => (
              <button key={b.label} type="button" disabled={busy} data-outcome={b.outcome}
                className={"htBtn" + (b.outcome === "approved" ? " approve" : b.outcome === "rejected" ? " reject" : b.other && other ? " picked" : "")}
                onClick={() => send(b)}>
                {b.outcome === "approved" ? "Approve" : b.label}
                {popup && i < 9 ? <kbd>{i + 1}</kbd>
                  : <>{b.outcome === "approved" && <kbd>A</kbd>}{b.outcome === "rejected" && <kbd>R</kbd>}</>}
              </button>
            ))}
            {other && <button type="button" className="htBtn htOtherAnswer" disabled={busy} data-outcome="answered" onClick={() => send(ANSWER)}>Answer</button>}
            <TeachButtons row={row} decide={call} teach={teach} ready={teachReady} note={comment} busy={busy} setBusy={setBusy}
              onDone={(line) => { setTaught(line); setDone(true); }} onError={setError} className="htBtn" />
          </div>
          <label className="htComment">Comment (optional, sent with your {approve ? "decision" : "answer"})
            <input type="text" value={comment} disabled={busy} onChange={(e) => setComment(e.target.value)} />
          </label>
        </>}
        {error && <div className="err htErr">{String(error)}</div>}
        {popup && (
          <div className="htLater">
            <span>Later:</span>
            {SNOOZES.map((s) => <button key={s.label} type="button" className="htSmallBtn" onClick={() => popup.onSnooze && popup.onSnooze(s.ms)}>{s.label}</button>)}
            <span className="htGrow" />
            <button type="button" className="htSmallBtn" onClick={() => popup.onSilence && popup.onSilence()}>Stop all for 1 hour</button>
            <span className="htMuted htHint">{HINT}</span>
          </div>
        )}
        {!approve && spec.schemaShown && <details className="htFold"><summary>response schema</summary><JsonView value={row.responseSchema} /></details>}
      </div>

      {steps.length > 0 && (
        <div className="htStory">
          <span className="htEyebrow">HOW THIS REACHED YOU <span className="htMuted">first step first</span></span>
          {steps.map((l, i) => {
            const src = sourceText(l.source);
            const cmd = l.source && typeof l.source === "object" && typeof l.source.command === "string" ? l.source.command : null;
            return (
              <Step key={i} who={party(l.by)} label={l.kind ? `· ${l.kind}` : ""}
                trust={<span className="htReported"> · reported by {sender || "the sender"}, not verified</span>}>
                {l.claim}
                {(src || cmd) && <span className="htChips">
                  {src && <span className="htChip">{src}</span>}
                  {cmd && <code className="htChip code">{cmd.length > 80 ? cmd.slice(0, 80) + "…" : cmd}</code>}
                </span>}
              </Step>
            );
          })}
          <Step who={sender} label="asks you" self last
            trust={<span className="htVerified"> · <Check /> verified sender</span>}>
            <strong>{[head.text, head.code, head.code ? head.where : null].filter(Boolean).join(" ")}</strong>
            {row.why && <span className="htBubbleWhy">{row.why}</span>}
            <span className="htThis">This request: decide at the top.</span>
          </Step>
        </div>
      )}

      {approve && (row.payloadDigest || row.scope != null || row.payload != null) && (
        <details className="htFold">
          <summary>Technical details: digest, scope, payload</summary>
          <div className="htTech">
            {row.payloadDigest && <span>digest {row.payloadDigest}</span>}
            {row.scope != null && <span>scope {JSON.stringify(row.scope)}</span>}
            <span>task {row.actionId}</span>
            {row.payload != null && <JsonView value={row.payload} />}
          </div>
        </details>
      )}
    </div>
  );
};

const when = (secs) => (secs == null ? "at no known time" : new Date(secs * 1000).toLocaleString());

/** A decided task (src/needs-you.js fetchHitlpTask's `decided` row): the question, the outcome, who and when. */
export const DecidedTask = ({ row }) => {
  const head = headlineOf(row);
  return (
    <div className="htView htDecided" data-task={row.actionId}>
      <div className="htAsk">
        <span className="htEyebrow">{row.kind === "approve" ? "APPROVAL" : "QUESTION"} · DECIDED</span>
        <h1 className={"htHeadline" + (head.text.length > 80 ? " long" : "")}>{head.text}{head.code && <> <code className="htInline">{head.code}</code></>}</h1>
      </div>
      <div className="htDecide">
        <div className="htDone">
          <span className="htOutcome">{row.outcome}</span>
          {row.decidedBy && <> by <span className="htDecidedBy">{row.decidedBy}</span></>}
          {" "}<span className="htDecidedAt">{when(row.decidedAt)}</span>
          {row.channel && <span className="htMuted"> · via {row.channel}</span>}
          {row.reason && <span className="htMuted"> · {row.reason}</span>}
        </div>
      </div>
    </div>
  );
};

/**
 * The id of the HITLP task `repo`#`number` waits on: `taskId` (the hint on the
 * ticket's newest announcing comment) when there is one, else the open task in
 * `fleet.hitlpTasks()` whose `repo` and `ticket` are the ticket's. Then the task
 * itself, through `fleet.hitlpSurfaceGet`. Resolves to `{ row }` or `{ error }`
 * (the surface's own text); never rejects.
 */
export const resolveTicketTask = async ({ repo, number, taskId = null, fleet }) => {
  try {
    if (!fleet || !fleet.hitlpSurfaceGet || !fleet.hitlpTasks) return { error: "this build has no HITLP task read" };
    let id = taskId;
    if (!id) {
      const list = await fleet.hitlpTasks();
      if (!list || list.error) return { error: String((list && list.error) || "the HITLP server did not answer") };
      const hit = (list.open || []).find((r) => r.repo === repo && Number(r.ticket) === Number(number));
      if (!hit) return { error: `no open HITLP task names ${repo}#${number} on the HITLP server` };
      id = hit.actionId;
    }
    const r = await fleet.hitlpSurfaceGet(id);
    if (!r || r.error || !r.row) return { error: String((r && r.error) || "the HITLP server did not answer") };
    // A hint is a comment anyone can write: its task must be this ticket's.
    if (taskId && r.row.repo && r.row.ticket != null
        && (r.row.repo !== repo || Number(r.row.ticket) !== Number(number))) {
      return { error: `the hint names a task of ${r.row.repo}#${r.row.ticket}, not this ticket` };
    }
    return { row: r.row };
  } catch (e) {
    return { error: `HITLP server: ${e && e.message ? e.message : e}` };
  }
};

/**
 * The HITLP task a ticket's flow waits on, inline (detail.jsx, pitch.jsx): read
 * live from the surface API (`resolveTicketTask`) and drawn by `HitlpTask`, its
 * decision sent through `window.fleet.hitlpSurfaceDecide` as everywhere else.
 * After `{ ok }` the task is read again, and a decided one draws `DecidedTask`.
 * Not set, or not readable: the surface's error in one line, no buttons.
 * `initial` (`{ row }`, `{ error }`) stands in for the read (tests).
 */
export const TicketTask = ({ repo, number, taskId = null, now = null, decide = null, initial = null,
  fleet = typeof window !== "undefined" ? window.fleet : null }) => {
  const [state, setState] = useState(initial || { loading: true });
  const [round, setRound] = useState(0);
  useEffect(() => {
    if (initial) return undefined;
    let live = true;
    resolveTicketTask({ repo, number, taskId, fleet }).then((r) => {
      if (!live) return;
      // A re-read after the decision that cannot find the task keeps the view's own "Answered".
      setState((s) => (round > 0 && r.error && s.row ? s : r));
    });
    return () => { live = false; };
  }, [repo, number, taskId, round]);
  if (state.loading) return <div className="muted htTicketWait">reading the HITLP task…</div>;
  if (state.error) return <div className="err htTicketErr">{state.error}</div>;
  const row = state.row;
  if (row.decided) return <DecidedTask row={row} />;
  return <HitlpTask key={row.actionId} row={row} now={now} decide={decide} onDecided={() => setRound((n) => n + 1)} />;
};
