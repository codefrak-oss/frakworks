// pitch.jsx — the Pitch view (#563): a proposal ticket's pitch, in the issue
// detail (flow.jsx), next to the controls that decide it. The engine reads
// the pitch from GitHub (`GET /ticket/<owner>/<repo>/<n>/pitch`, through
// `fleet.ticketPitch`); the desktop reads GitHub only through the engine.
//
// An `html` pitch is the page a Smith committed, drawn in a sandboxed srcdoc
// frame with a CSP of its own (src/pitch-frame.js): nothing in it runs or
// reaches the network. A `markdown` pitch (a proposal from before pitches) is
// the doc as GitHub renders it, through DOMPurify like the ticket body
// (ticket.jsx sanitizeBody); where DOMPurify cannot run it is not drawn.
//
// A waiting decision (or a research ack) is a HITLP task, answered here in
// place: the controls mount the live task (hitlp-task.jsx TicketTask), its
// decision sent to the HITLP server as everywhere else.
//
// Options (#1720, #2209): a pitch that lays out options declares them to the
// engine (the pitch's `options`), and its page only carries inert, disabled
// placeholders. The app lists them outside the frame, so the frame stays
// exactly as sandboxed; the choice itself is the task's buttons above.

import { useState, useEffect } from "react";
import { sanitizeBody } from "./ticket.jsx";
import { pitchSrcdoc, PITCH_FRAME_ATTRS, PITCH_FRAME_STYLE } from "../pitch-frame.js";
import { TicketTask } from "./hitlp-task.jsx";

/** A board row whose flow is a proposal with a PR: the ones the Pitch view is for. */
export const isProposal = (issue) =>
  !!(issue && issue.flow && issue.flow.flow === "proposal" && issue.flow.prUrl);

/** A board row whose flow is a research ticket (#1262): its page is the engine-kept answer, drawn under "Answer". */
export const isResearch = (issue) => !!(issue && issue.flow && issue.flow.flow === "research");

/** The section's heading: "Answer, round N" for a research answer (the pitch's `round`), else "Pitch". */
export const pitchHeading = (round) => (round == null ? "Pitch" : `Answer, round ${round}`);

/**
 * The pitch of `repo`#`number`, through `fleet.ticketPitch`; with a `slug` (a
 * proposal doc, #660), the doc's page from the default branch through
 * `fleet.proposalPage` when there is no ticket or its pitch read fails.
 * Resolves to `{ pitch }` or `{ error }`; never rejects.
 */
export const loadPitch = ({ repo, number, slug, fleet }) => {
  const [owner, name] = String(repo || "").split("/");
  const ask = (read, ...args) => Promise.resolve()
    .then(() => read(...args))
    .then((r) => (r && r.pitch ? { pitch: r.pitch } : { error: String((r && r.error) || "no response from the main process") }),
          (e) => ({ error: `IPC failed: ${e && e.message ? e.message : e}` }));
  const byDoc = () => (fleet && fleet.proposalPage ? ask(fleet.proposalPage, owner, name, slug)
    : Promise.resolve({ error: "this build has no proposal page read" }));
  if (number == null && slug) return byDoc();
  if (!fleet || !fleet.ticketPitch) return slug ? byDoc() : Promise.resolve({ error: "this build has no pitch read" });
  return ask(fleet.ticketPitch, owner, name, number).then((r) => (r.error && slug ? byDoc() : r));
};

/**
 * The pitch's options, listed; while the proposal waits on a choice
 * (`awaiting`), a pointer to the task above, whose buttons pick one.
 */
export const ChoiceBar = ({ options, awaiting }) => {
  if (!Array.isArray(options) || options.length === 0) return null;
  return (
    <div className="pitchChoices">
      <ul className="pitchOptions">
        {options.map((o) => <li key={o.id} className="pitchOption" data-option={o.id}>{o.id.toUpperCase()} · {o.label}</li>)}
      </ul>
      {awaiting ? <span className="muted">waiting on your choice: pick it in the task above</span> : <span className="muted">not waiting on a choice</span>}
    </div>
  );
};

/** The pitch itself: `result` is loadPitch's, or `{ loading: true }`. */
export const PitchBody = ({ result, sanitize = sanitizeBody }) => {
  if (!result || result.loading) return <div className="muted">asking the engine for the pitch…</div>;
  if (result.error) return <div className="err">{result.error}</div>;
  const p = result.pitch;
  if (p.kind === "html") {
    return <iframe className="pitchFrame" title={p.path || "pitch"} {...PITCH_FRAME_ATTRS} style={PITCH_FRAME_STYLE}
                   srcDoc={pitchSrcdoc(p.content)} />;
  }
  const safe = sanitize(p.content);
  return safe
    ? <div className="pitchDoc md" dangerouslySetInnerHTML={{ __html: safe }} />
    : <div className="muted">the doc cannot be drawn safely here; read it on the PR</div>;
};

/** The fold arrow the Pitch heading starts with (the same as Doctor's and the comments'). */
export const FoldArrow = ({ open }) => <span className="tdFoldArrow">{open ? "▾" : "▸"}</span>;
const foldTitle = (open) => (open ? "Hide the pitch" : "Show the pitch");

/**
 * The decision controls: while `enabled` (the flow waits on a decision #953, or
 * a research ack #1262), the HITLP task `repo`#`number` waits on, answered in
 * place (TicketTask; `taskId` the hint's, `fleet` for tests). Chat about this
 * (#590) is there whether or not a decision waits: `onChatAbout()` starts a
 * First Mate conversation about the proposal. `head` is the heading over the task.
 */
export const DecisionControls = ({ enabled, repo, number, taskId = null, fleet, onChatAbout, head = "Your decision" }) => (
  <div className="pitchControls">
    {enabled && <>
      <div className="askHead">{head}</div>
      <TicketTask repo={repo} number={number} taskId={taskId} {...(fleet !== undefined ? { fleet } : {})} />
    </>}
    {onChatAbout !== undefined && (
      <button type="button" className="pitchChat" disabled={!onChatAbout}
              title="Start a new First Mate conversation about this proposal"
              onClick={() => onChatAbout && onChatAbout()}>Chat about this</button>
    )}
  </div>
);

/**
 * The Pitch view: the decision's task above, the pitch below at full width. `decision` says a
 * decision need waits on the flow, `taskId` which task (the hint's, else looked up by ticket); `fleet` is window.fleet (a prop for tests).
 * `onChatAbout({ repo, number, title })` is the Chat about this button's (#590).
 */
export const PitchView = ({ repo, number, title, prUrl, decision, taskId = null, research = false, onChatAbout, fleet = typeof window !== "undefined" ? window.fleet : null }) => {
  const [result, setResult] = useState({ loading: true });
  const [open, setOpen] = useState(true);
  useEffect(() => {
    let live = true;
    setResult({ loading: true });
    setOpen(true);
    loadPitch({ repo, number, fleet }).then((r) => { if (live) setResult(r); });
    return () => { live = false; };
  }, [repo, number]);
  const p = result && result.pitch;
  return (
    <>
      <h2 className="tdFold" onClick={() => setOpen((o) => !o)} title={foldTitle(open)}><FoldArrow open={open} />
        {pitchHeading(p && p.round != null ? p.round : null)}{p && p.path ? <span className="muted"> · {p.kind === "html" ? p.path : `${p.path}, no pitch page`}</span> : null}
        {(p && p.prUrl) || prUrl ? <> · <a href={(p && p.prUrl) || prUrl} onClick={(e) => e.stopPropagation()}>PR</a></> : null}</h2>
      <div className="pitchView">
        {research
          ? (decision ? <DecisionControls enabled repo={repo} number={number} taskId={taskId} head="Your ack" /> : null)
          : <DecisionControls enabled={!!decision} repo={repo} number={number} taskId={taskId}
                              onChatAbout={onChatAbout ? () => onChatAbout({ repo, number, title }) : null} />}
        {p && p.kind === "html" && <ChoiceBar options={p.options} awaiting={!!p.awaitingChoice} />}
        {open && <div className="pitchMain"><PitchBody result={result} /></div>}
      </div>
    </>
  );
};

// ---- the ticket detail's Pitch section (#579) -------------------------------
//
// The same pitch on the old fleet's ticket detail (ticket.jsx), always in its
// dark scheme, in a tall frame the user can resize, with a control that opens
// it over the whole window. A ticket with no pitch page (a 404, a draft from
// before pitches, a failed read) gets no section at all: main logs a failure,
// and most tickets are not proposals.

// What this frame may do beyond the Pitch view's: open popups, which is how its
// links leave (every link targets a new window, darkPitch's `<base
// target="_blank">`, and main hands a new window to the system browser,
// src/main.js openOutside). No scripts, no origin of its own, no top
// navigation, no forms.
export const PITCH_SANDBOX = "allow-popups";

const DARK = /\(\s*prefers-color-scheme\s*:\s*dark\s*\)/gi;
const LIGHT = /\(\s*prefers-color-scheme\s*:\s*light\s*\)/gi;
// Always true, and never true: what the two color-scheme queries become.
export const ALWAYS = "(min-width: 0px)";
export const NEVER = "(max-width: -1px)";
const HEAD = '<base target="_blank"><style>:root { color-scheme: dark !important; }</style>';

/**
 * The page as the section's frame shows it, in its dark scheme whatever the
 * OS's is: every `(prefers-color-scheme: dark)` query in it made always true
 * and every `(prefers-color-scheme: light)` one never, `color-scheme: dark` on
 * its root, every link opening a new window, and the Pitch view's CSP first
 * (pitchSrcdoc, with a dark-only color-scheme meta). The additions go right
 * after `<head>` (or `<html>`, or the doctype), never before the doctype, so
 * the page keeps its standards mode.
 */
export const darkPitch = (html) => {
  const text = String(html || "").replace(DARK, ALWAYS).replace(LIGHT, NEVER);
  for (const re of [/<head\b[^>]*>/i, /<html\b[^>]*>/i, /<!doctype[^>]*>/i]) {
    const m = re.exec(text);
    if (m) return pitchSrcdoc(text.slice(0, m.index + m[0].length) + HEAD + text.slice(m.index + m[0].length), { scheme: "dark" });
  }
  return pitchSrcdoc(HEAD + text, { scheme: "dark" });
};

/** The page from loadPitch's answer: an `html` pitch's content, or null for anything else. */
export const pitchHtmlOf = (r) => {
  const p = r && r.pitch;
  return p && p.kind === "html" && typeof p.content === "string" && p.content.trim() ? p.content : null;
};

/** The round from loadPitch's answer: a research answer's `round`, or null for a proposal's pitch. */
export const pitchRoundOf = (r) => {
  const p = r && r.pitch;
  return p && typeof p.round === "number" ? p.round : null;
};

const TicketFrame = ({ doc, full }) => (
  <iframe className={"pitchFrame ticketPitchFrame" + (full ? " full" : "")} title="Pitch" sandbox={PITCH_SANDBOX}
          referrerPolicy="no-referrer" srcDoc={doc} />
);

/**
 * The section: nothing without a page; else the page in a tall frame the user
 * can resize (drag its bottom edge), and a control that opens it over the
 * whole window, closed with its button or Escape.
 */
/**
 * Escape closes a full-window page. Keydown in the sandboxed frame never
 * reaches this window, so when the frame takes focus (a click in the deck
 * blurs the window) focus comes back to the overlay's Close button, where
 * Escape is heard again. Answers the unbind.
 */
export const bindPitchEscape = (win, close) => {
  const onKey = (e) => { if (e.key === "Escape") close(); };
  const onBlur = () => win.setTimeout(() => {
    const doc = win.document;
    const a = doc && doc.activeElement;
    if (!(a && a.tagName === "IFRAME" && a.closest && a.closest(".pitchOverlay"))) return;
    const btn = doc.querySelector(".pitchOverlay .pitchClose");
    if (btn && btn.focus) btn.focus();
  }, 0);
  win.addEventListener("keydown", onKey);
  win.addEventListener("blur", onBlur);
  return () => { win.removeEventListener("keydown", onKey); win.removeEventListener("blur", onBlur); };
};

export const usePitchEscape = (full, close) => useEffect(() => {
  if (!full || typeof window === "undefined") return undefined;
  const unbind = bindPitchEscape(window, close);
  const btn = window.document && window.document.querySelector(".pitchOverlay .pitchClose");
  if (btn && btn.focus) btn.focus();
  return unbind;
}, [full]);

/** The full-window page alone, over the whole window, closed with its button or Escape. */
export const PitchOverlay = ({ doc, title, onClose }) => (
  <div className="pitchOverlay" role="dialog" aria-label="Pitch">
    <div className="pitchOverlayBar">
      <span>{title}</span>
      <button className="pitchClose" title="Close (Esc)" onClick={() => onClose && onClose()}>Close ✕</button>
    </div>
    <TicketFrame doc={doc} full />
  </div>
);

export const TicketPitchView = ({ html, round = null, full, onOpen, onClose, open = true, onFlip, heading = null }) => {
  if (!html) return null;
  const doc = darkPitch(html);
  return (
    <>
      <h2 className="pitchHead tdFold" onClick={() => onFlip && onFlip()} title={foldTitle(open)}>
        <FoldArrow open={open} />
        {heading || pitchHeading(round)}
        <button className="pitchOpen" title="Open it over the whole window"
                onClick={(e) => { e.stopPropagation(); if (onOpen) onOpen(); }}>Full window ⤢</button>
      </h2>
      {open && <div className="pitchBox"><TicketFrame doc={doc} /></div>}
      {full && <PitchOverlay doc={doc} title={heading || pitchHeading(round)} onClose={onClose} />}
    </>
  );
};

/** Reads the pitch of `repo` ("owner/name") `issue` through loadPitch, once per ticket. */
export const TicketPitch = ({ repo, issue, fleet = typeof window !== "undefined" ? window.fleet : null }) => {
  const [html, setHtml] = useState(null);
  const [round, setRound] = useState(null);
  const [full, setFull] = useState(false);
  const [open, setOpen] = useState(true);
  useEffect(() => {
    setHtml(null);
    setRound(null);
    setFull(false);
    setOpen(true);
    if (!(repo && issue)) return undefined;
    let live = true;
    loadPitch({ repo, number: Number(issue), fleet }).then((r) => { if (live) { setHtml(pitchHtmlOf(r)); setRound(pitchRoundOf(r)); } });
    return () => { live = false; };
  }, [repo, issue]);
  usePitchEscape(full, () => setFull(false));
  return <TicketPitchView html={html} round={round} full={full} onOpen={() => setFull(true)} onClose={() => setFull(false)}
                          open={open} onFlip={() => setOpen((o) => !o)} />;
};
