// first-mate.jsx — First mate (issue #100): drawing only.
// No fetch, no require, no timers, no `window.` reference in here — the
// bridge is reached from index.jsx's FirstMateStrip, through window.firstMate
// and nothing else (test/first-mate-ipc.test.js checks it).
"use strict";
import { useEffect, useRef, useState } from "react";
import { imagesLabel } from "../first-mate-images.js";
import { splitTicketRefs } from "./ticket-refs.js";
import { useRepoName } from "./repo-name-form.jsx";

// Acceptance criterion 9: "transcript shows only the last few exchanges,
// older ones collapsed under a count". An exchange is one user row plus the
// assistant/system row(s) that follow it.
const VISIBLE_EXCHANGES = 3;
// Replies render at full length (#285): the whole transcript scrolls as one,
// so a long reply is read by scrolling the conversation, not cut or boxed.

// Split history into exchanges: each one starts at a "user" row and includes
// every row up to (not including) the next "user" row.
function toExchanges(history) {
  const out = [];
  for (const row of history || []) {
    if (row.role === "user" || !out.length) out.push([row]);
    else out[out.length - 1].push(row);
  }
  return out;
}

const WHO = { user: "the user", assistant: "First mate", system: "system" };

// Claude Code's TUI marks (#366): the user's line is its `>` prompt, a reply
// hangs off its `⏺` bullet (in the Claude accent, styles.css). The role's name
// stays on the mark for screen readers instead of a visible label.
const MARK = { user: ">", assistant: "⏺", system: "⎿" };

// The iTerm2 font (#366): what main read from the default profile's `Normal
// Font` (src/iterm-font.js), as `{ family, size, source }`. A PostScript name
// is matched with `@font-face { src: local() }` under a family of our own,
// and also tried as a family name; the fallback stack in styles.css follows.
export const TERMINAL_FAMILY = "iTerm2 Normal Font";
export const FALLBACK_MONO = "ui-monospace, SFMono-Regular, Menlo, monospace";
const POSTSCRIPT_NAME = /^[A-Za-z0-9][A-Za-z0-9._+-]*$/;

/** `font` -> the strip's CSS custom properties and its @font-face rule (null when falling back). */
export const terminalFont = (font) => {
  const family = font && typeof font.family === "string" && POSTSCRIPT_NAME.test(font.family) ? font.family : null;
  const size = font && Number.isFinite(font.size) && font.size > 0 ? font.size : null;
  const style = {};
  if (family) style["--fm-mono"] = `"${TERMINAL_FAMILY}", "${family}", ${FALLBACK_MONO}`;
  if (size) style["--fm-mono-size"] = `${size}px`;
  const face = family ? `@font-face { font-family: "${TERMINAL_FAMILY}"; src: local("${family}"); }` : null;
  return { style, face };
};

// Stick-to-bottom (issue #235): whether the transcript scroll region is close
// enough to its bottom that a new/streaming reply should pull it back down.
// `distance` is `scrollHeight - scrollTop - clientHeight`; a few px of slop
// covers sub-pixel layout so "at the bottom" isn't defeated by rounding.
export const AT_BOTTOM_SLOP_PX = 4;
export const isAtBottom = (distance) => distance <= AT_BOTTOM_SLOP_PX;

const Mark = ({ role }) => (
  <span className="fmMark" role="img" aria-label={WHO[role] || role}>{MARK[role] || "·"}</span>
);

// `row.images` is the count a user message carried (#368); the images
// themselves are not kept, so the row says how many there were.
// A finished assistant reply links its ticket references (#374): a plain
// left click opens the in-app issue view, a modifier click is left alone.
// So does a system row (#927), for `Filed as #<n>`; one without a reference
// (the iTerm2 row, a recovery message) is its plain text, as before.
const LinkedText = ({ text, onOpenTicket }) => splitTicketRefs(text).map((part, i) =>
  typeof part === "string" ? part : (
    <a key={i} className="fmTicketRef" href={`https://github.com/${part.repo}/issues/${part.number}`}
       onClick={(e) => {
         if (e.button || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
         e.preventDefault();
         onOpenTicket && onOpenTicket(part.repo, part.number);
       }}>{part.text}</a>
  ));

export const Row = ({ row, onOpenTicket }) => (
  <div className={`fmRow fmRow-${row.role}`}>
    <Mark role={row.role} />
    {row.images > 0 ? <div className="fmImagesMarker">{imagesLabel(row.images)}</div> : null}
    {row.text ? <div className="fmText">{row.role === "assistant" || row.role === "system"
      ? <LinkedText text={row.text} onOpenTicket={onOpenTicket} /> : row.text}</div> : null}
  </div>
);

// The kick-starter (#590): Chat about this on a proposal's Pitch view starts a
// new conversation and leaves this row in it, drawn as if already sent. It is
// not sent: the user's first message goes out as `sent`, a blank line, and
// what they typed (withKickStarter), and that one message is what main stores.
// `sent` names the repo so First mate can find the ticket with several repos
// selected; the row shows the short `#<n>`, as a pill that opens the ticket.
// Chat about this on the repo file view (#683) names a file instead: the row
// shows its path as a pill that opens the file in the app, and `sent` names
// the repo and the tree's commit it was picked at, so First mate can fetch it.

/** A repo file `{ repo, path, commit }` -> its kick-starter. */
export const fileKickStarterOf = ({ repo, path, commit }) => {
  const c = commit ? ` @ ${commit}` : "";
  return { kind: "file", repo, path: String(path), commit: commit || null,
           shown: `Discuss file ${path}`,
           sent: `Discuss file ${repo}:${path}${c}` };
};

// Chat about this on a ticket's detail (#699) names the ticket: its `about`
// carries `kind: "ticket"`, and the row reads like a proposal's.

/** A ticket `{ repo, number, title }` -> its kick-starter. */
export const ticketKickStarterOf = ({ repo, number, title }) => {
  const t = String(title || "").trim();
  const tail = t ? ` - ${t}` : "";
  return { kind: "ticket", repo, number: Number(number), title: t,
           shown: `Discuss ticket #${Number(number)}${tail}`,
           sent: `Discuss ticket ${repo}#${Number(number)}${tail}` };
};

// "<-- chat about this" in the header (#733) names whatever else the work area
// shows as a screen: its `label` is the explorer's path to it (index.jsx
// screenLabel), and `sent` says it is the desktop app's screen and, with one
// selected, the repo the screen follows, so First mate knows where to look.

/** A screen `{ kind: "screen", label, repo }` -> its kick-starter. */
export const screenKickStarterOf = ({ label, repo }) => {
  const l = String(label || "").trim();
  const r = repo ? `, repo ${repo} selected` : "";
  return { kind: "screen", label: l,
           shown: `Discuss screen ${l}`,
           sent: `Discuss screen ${l} (the frakworks desktop app's work area${r})` };
};

/** A proposal `{ repo, number, title }`, a repo file `{ repo, path, commit }`, a ticket `{ kind: "ticket", repo, number, title }` or a screen `{ kind: "screen", label, repo }` -> its kick-starter. */
export const kickStarterOf = (about) => (about && about.kind === "ticket" ? ticketKickStarterOf(about)
  : about && about.kind === "screen" ? screenKickStarterOf(about)
  : about && about.path != null ? fileKickStarterOf(about) : proposalKickStarterOf(about));

const proposalKickStarterOf = ({ repo, number, title }) => {
  const t = String(title || "").trim();
  const tail = t ? ` - ${t}` : "";
  return { repo, number: Number(number), title: t,
           shown: `Discuss proposal #${Number(number)}${tail}`,
           sent: `Discuss proposal ${repo}#${Number(number)}${tail}` };
};

/** The first message's text: the kick-starter, a blank line, then `text`; `text` alone without one. */
export const withKickStarter = (kick, text) => {
  if (!kick) return text;
  const t = String(text || "").trim();
  return t ? `${kick.sent}\n\n${t}` : kick.sent;
};

/** A plain click opens in the app; a modified one is left to the link. */
const inApp = (open) => (e) => {
  if (e.button || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
  e.preventDefault();
  open && open();
};

// A screen has no ticket or file to open, so its row is the words alone.
export const KickStarterRow = ({ kick, onOpenTicket, onOpenFile }) => kick.kind === "screen" ? (
  <div className="fmRow fmRow-user fmKickStarter">
    <Mark role="user" />
    <div className="fmText">{kick.shown}</div>
  </div>
) : kick.kind === "file" ? (
  <div className="fmRow fmRow-user fmKickStarter">
    <Mark role="user" />
    <div className="fmText">
      Discuss file{" "}
      <a className="fmKickRef" href={`https://github.com/${kick.repo}/blob/${kick.commit || "HEAD"}/${kick.path}`}
         title={`${kick.repo}:${kick.path}${kick.commit ? ` @ ${kick.commit}` : ""}`}
         onClick={inApp(onOpenFile && (() => onOpenFile(kick)))}>{kick.path}</a>
    </div>
  </div>
) : (
  <div className="fmRow fmRow-user fmKickStarter">
    <Mark role="user" />
    <div className="fmText">
      Discuss {kick.kind === "ticket" ? "ticket" : "proposal"}{" "}
      <a className="fmKickRef" href={`https://github.com/${kick.repo}/issues/${kick.number}`}
         title={`${kick.repo}#${kick.number}`}
         onClick={inApp(onOpenTicket && (() => onOpenTicket(kick.repo, kick.number)))}>#{kick.number}</a>
      {kick.title ? ` - ${kick.title}` : ""}
    </div>
  </div>
);

// The image files a paste carries (#368), or none for a text paste, which
// then goes on into the input as before.
export const pastedImageFiles = (clipboardData) =>
  Array.from((clipboardData && clipboardData.items) || [])
    .filter((it) => it && it.kind === "file" && /^image\//.test(it.type || ""))
    .map((it) => it.getAsFile())
    .filter(Boolean);

// What a key in the compose box does (#758): Enter sends, unless Shift is
// held (the textarea then inserts the newline itself) or an IME composition
// is open (Enter there commits the composition; keyCode 229 is how Chromium
// reports a key the IME took). Anything else is the textarea's own.
export const composeKeyAction = (e) =>
  e.key === "Enter" && !e.shiftKey && !(e.nativeEvent || e).isComposing && e.keyCode !== 229 ? "send" : null;

// The pasted images waiting to go with the next message: a thumbnail each,
// with its own remove control.
const Attachments = ({ attachments, onRemove, disabled }) => (
  <div className="fmAttachments">
    {attachments.map((im, i) => (
      <div className="fmAttachment" key={i}>
        <img className="fmThumb" alt={`pasted image ${i + 1}`} src={`data:${im.media_type};base64,${im.data}`} />
        <button className="fmAttachmentRemove" aria-label={`remove image ${i + 1}`} title="Remove"
                disabled={disabled} onClick={() => onRemove && onRemove(i)}>×</button>
      </div>
    ))}
  </div>
);

// `repoChoice` (#577): in All repos (`selection.repo` null) the card asks for
// the repo to file in, from `selection.repos`, and File waits for one; with a
// repo selected it files there and asks nothing.
export const FileCard = ({ file, fileBusy, fileError, onFile, tierDraft, onTierDraft, selection, repoDraft, onRepoDraft, chatRepo }) => {
  const repoName = useRepoName(selection && selection.repos);
  const tier = tierDraft == null ? file.tier : tierDraft;
  const allRepos = !!selection && !selection.repo;
  // The chat's repo (#2343) is the pick until the user picks another.
  const repo = allRepos ? repoDraft || chatRepo || null : null;
  const blocked = allRepos && !repo;
  const file_ = () => onFile(tier, repo);
  return (
    <div className="fmFileCard">
      <div className="fmFileAsk">{file.ask}</div>
      <div className="fmFileRow">
        {allRepos && (
          <label className="fmTierLabel">
            Repo
            <select className="fmRepoSelect" required value={repo || ""} disabled={fileBusy}
                    onChange={(e) => onRepoDraft && onRepoDraft(e.target.value || null)}>
              <option value="">Pick a repo…</option>
              {(selection.repos || []).map((r) => <option key={r} value={r}>{repoName(r)}</option>)}
            </select>
          </label>
        )}
        <label className="fmTierLabel">
          Tier
          <select className="fmTierSelect" value={tier}
                  onChange={(e) => onTierDraft(Number(e.target.value))} disabled={fileBusy}>
            {Array.from({ length: 10 }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
        <button className="fmFileButton" disabled={fileBusy || blocked} onClick={file_}>
          {fileBusy ? "Filing…" : "File"}
        </button>
      </div>
      {fileError ? <div className="fmFileError">{fileError} <button className="fmFileRetry" disabled={blocked} onClick={file_}>Retry</button></div> : null}
    </div>
  );
};

// First mate's own model (issue #240, part 2 of #226). `snapshot.tier` is
// main's `{ tier, model, effort, source }` for the next turn (source
// "standing" or "fallback"); the renderer only ever sends a tier number back.

// The History control (#265): the list and the read-only conversation view
// are panels that replace the transcript area in place — same header, same
// fixed width/position — never full-screen or a modal. Neither branch draws
// an input, a send button or a File card.
const HistoryList = ({ historyList, onSelectConversation, onCloseHistory }) => (
  <>
    <button className="fmHistoryClose" onClick={onCloseHistory}>Close</button>
    <div className="fmHistoryList">
      {(historyList || []).map((entry) => (
        <div key={entry.id} className="fmHistoryRow" onClick={() => onSelectConversation(entry.id)}>
          <div className="fmHistoryWhen">{entry.started_at ? new Date(entry.started_at).toLocaleString() : ""}</div>
          <div className="fmText">{entry.first_message}</div>
        </div>
      ))}
    </div>
  </>
);

// Issue #486: the one control here that changes anything. With a stored
// session id it resumes the conversation (the caller then closes History);
// without one (archived before resume existed) it is drawn disabled and says why.
const ResumeControl = ({ viewingConversation, onResume, resumeBusy, resumeError }) => {
  const c = viewingConversation || {};
  if (!c.resumable) {
    return (
      <div className="fmResumeRow">
        <button className="fmResume" disabled>Resume this conversation</button>
        <span className="fmResumeNote">This conversation was archived before resume existed, so it cannot be resumed.</span>
      </div>
    );
  }
  return (
    <div className="fmResumeRow">
      <button className="fmResume" disabled={Boolean(resumeBusy)} onClick={() => onResume && onResume(c.id)}>
        Resume this conversation
      </button>
      {resumeError ? <span className="fmResumeError">Not resumed: {resumeError}</span> : null}
    </div>
  );
};

// Issue #601: "Back to previous conversation", in the header beside Start new
// conversation (its inverse) and History, so it is one click from the live
// strip and from either History panel. `snapshot.can_pop` says whether main
// has a resumable past conversation; without one it is drawn disabled and
// its title says why.
export const POP_LABEL = "Back to previous conversation";
export const POP_TITLE_ON = "Resume the newest past conversation. This one moves to History, where it can be resumed again.";
export const POP_TITLE_OFF = "No past conversation to go back to: History has none with a session to resume.";
export const PopControl = ({ canPop, onPop }) => (
  <button className="fmPopConversation" disabled={!canPop} title={canPop ? POP_TITLE_ON : POP_TITLE_OFF}
          onClick={() => canPop && onPop && onPop()}>
    <span aria-hidden="true">↩ </span>{POP_LABEL}
  </button>
);

// Issue #2343: the chat's own repo, a chip on its own row below the header
// buttons (#2551), labelled "This chat's repo:" so it reads as the current
// conversation's, not the next one's. It shows the repo
// first mate is told this chat is about (`snapshot.chat_repo`), or an amber
// "No repo yet" in All repos, and its dropdown sets or changes it from the
// target repos (`selection.repos`) without leaving the pane. Not drawn without
// a repo switcher (`snapshot.chat_repo_on` false).
export const NO_CHAT_REPO = "No repo yet";
export const CHAT_REPO_LABEL = "This chat's repo:";
// Issue #2551: Start new conversation names the repo the new chat starts with
// (the switcher's, or none in All repos), so the order is plain: the switcher
// picks a new chat's repo, the chip below changes it afterwards.
export const newConversationRepoText = (selection) =>
  selection && selection.repo ? ` · in ${selection.repo}` : " · no repo yet";
export const NEW_CONVERSATION_TITLE =
  "Starts a new chat in the repo switcher's repo (none in All repos); change it afterwards with \"This chat's repo\"";
export const ChatRepoChip = ({ chatRepo, repos, onSetRepo, disabled }) => {
  const list = [...new Set([...(repos || []), ...(chatRepo ? [chatRepo] : [])])];
  return (
    <label className={chatRepo ? "fmChatRepo" : "fmChatRepo fmChatRepoNone"}
           title="The repo this chat is about: First mate is told it with every message">
      <span className="fmChatRepoLabel">{CHAT_REPO_LABEL}</span>
      <select className="fmChatRepoSelect" value={chatRepo || ""} disabled={Boolean(disabled)}
              onChange={(e) => onSetRepo && onSetRepo(e.target.value || null)}>
        <option value="">{NO_CHAT_REPO}</option>
        {list.map((r) => <option key={r} value={r}>{r}</option>)}
      </select>
    </label>
  );
};

// Issue #733: "<-- chat about this", on its own row below the heading in the
// live strip's header (#1752), points at the work area and starts a new
// conversation about what it shows (index.jsx screenChatOf). `off` is why it
// cannot, drawn disabled with that as its title.
export const CHAT_SCREEN_LABEL = "<-- chat about this";
export const CHAT_SCREEN_TITLE = "Start a new First Mate conversation about what the middle pane shows";
export const ChatScreenControl = ({ off, onChat }) => (
  <button className="fmChatScreen" disabled={Boolean(off) || !onChat} title={off || CHAT_SCREEN_TITLE}
          onClick={() => !off && onChat && onChat()}>{CHAT_SCREEN_LABEL}</button>
);

const HistoryView = ({ viewingConversation, onCloseHistory, onOpenTicket, onResume, resumeBusy, resumeError }) => (
  <div className="fmHistoryView">
    <button className="fmHistoryClose" onClick={onCloseHistory}>Back</button>
    <ResumeControl viewingConversation={viewingConversation} onResume={onResume}
                   resumeBusy={resumeBusy} resumeError={resumeError} />
    <div className="fmTranscript">
      {((viewingConversation && viewingConversation.messages) || []).map((row, i) => <Row key={i} row={row} onOpenTicket={onOpenTicket} />)}
    </div>
  </div>
);

// "Save for later" (#598): keeps the live conversation in the engine's Saved
// Chats. `saveState` is null, `{ busy }`, `{ error }` or `{ ok, created, at }`,
// `at` the history length it saved; the label says it saved until that changes.
export const saveLabel = (saveState, historyLength) => {
  if (!saveState) return "Save for later";
  if (saveState.busy) return "Saving…";
  if (saveState.ok && saveState.at === historyLength) return saveState.created ? "Saved ✓" : "Updated ✓";
  return "Save for later";
};

const SaveButton = ({ snapshot, saveState, onSave }) => {
  const s = snapshot || {};
  const n = (s.history || []).length;
  return (
    <>
      <button className="fmSaveButton" disabled={!s.can_save || Boolean(saveState && saveState.busy)}
              title="Keep this conversation under Saved Chats" onClick={() => onSave && onSave(n)}>
        {saveLabel(saveState, n)}
      </button>
      {saveState && saveState.error ? <span className="fmSaveError" title={saveState.error}>Not saved: {saveState.error}</span> : null}
    </>
  );
};

// Continue in terminal (#819):
// main builds a shell command, copies it and answers `{ ok, command, mode }`.
// `state` is null, `{ busy }`, `{ error }` or that answer; the notice shows
// the copied command in the mono font until it is dismissed or pressed again.
// The strip's and the Saved Chats view's buttons are this one.
export const HANDOFF_LABEL = "Continue in terminal";
export const HANDOFF_NOTICE = {
  resume: "Copied a command to resume this conversation. Paste it in a terminal.",
  seed: "Copied a command to start a new session with this transcript. Paste it in a terminal.",
};
/** Calls `fn()` (a hand-off IPC) and answers the button's next state; never rejects. */
export const handoffAnswer = async (fn) => {
  let r;
  try { r = await fn(); }
  catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
  return r && r.ok ? { ok: true, command: String(r.command), mode: r.mode === "resume" ? "resume" : "seed" }
    : { error: (r && r.error) || "no response from the main process" };
};

export const HandoffButton = ({ disabled, title, state, onHandoff, className = "fmHandoffButton", errorClass = "fmSaveError" }) => (
  <>
    <button className={className} disabled={Boolean(disabled || (state && state.busy))} title={title}
            onClick={() => onHandoff && onHandoff()}>
      {HANDOFF_LABEL}
    </button>
    {state && state.error ? <span className={errorClass} title={state.error}>Not copied: {state.error}</span> : null}
  </>
);

export const HandoffNotice = ({ state, onDismiss }) => state && state.ok ? (
  <div className="fmNotice fmHandoffNotice" role="status">
    <span>{HANDOFF_NOTICE[state.mode] || HANDOFF_NOTICE.seed}</span>
    {onDismiss ? <button className="fmHandoffDismiss" title="Dismiss" onClick={onDismiss}>×</button> : null}
    <code className="fmHandoffCommand">{state.command}</code>
  </div>
) : null;

// "Open in Claude Code ⇗" (#820): last in the live strip's header, after Save
// for later. It hands the live conversation to a Claude Code session in a new
// iTerm2 window; main does everything (src/first-mate-iterm.js). The History
// panels do not draw it: it acts on the live conversation only. `snapshot.can_open_in_iterm`
// says whether main would do it, and `snapshot.iterm.why` why not when
// iTerm2 cannot be driven, so the title is right without a round-trip.
export const ITERM_LABEL = "Open in Claude Code ⇗";
export const ITERM_TITLE_ON = "Open this conversation in Claude Code, in a new iTerm2 window. First mate keeps its copy.";
export const ITERM_TITLE_EMPTY = "No conversation to hand off yet.";
export const ITERM_TITLE_BUSY = "First mate is still working. Wait for it, or cancel.";

/** The button's title, by why it is off: no conversation, a running turn, then iTerm2's own reason. */
export const itermTitle = (snapshot) => {
  const s = snapshot || {};
  if (s.can_open_in_iterm) return ITERM_TITLE_ON;
  if (!((s.history || []).length)) return ITERM_TITLE_EMPTY;
  if (s.busy) return ITERM_TITLE_BUSY;
  if (s.iterm && !s.iterm.available && s.iterm.why) return s.iterm.why;
  return ITERM_TITLE_ON;
};

// `itermState` is null, `{ busy }` or `{ error }`: "Opening…" while main
// works, and "Not opened: <reason>" beside the button, enabled again, after a refusal.
export const OpenInItermButton = ({ snapshot, itermState, onOpenInIterm }) => {
  const s = snapshot || {};
  const busy = Boolean(itermState && itermState.busy);
  return (
    <>
      <button className="fmIterm" disabled={!s.can_open_in_iterm || busy} title={itermTitle(s)}
              onClick={() => s.can_open_in_iterm && !busy && onOpenInIterm && onOpenInIterm()}>
        {busy ? "Opening…" : ITERM_LABEL}
      </button>
      {itermState && itermState.error
        ? <span className="fmItermError" title={itermState.error}>Not opened: {itermState.error}</span> : null}
    </>
  );
};

// Escape interrupts a busy turn, like Escape in Claude Code (#815). It counts
// when focus is inside the strip, or on the page body: the composer is
// disabled while busy, so the focus it had drops to the body. A key another
// handler already took (find bar, a modal) is left alone.
export const STOP_TITLE = "Stop (Esc)";
export const escapeCancels = ({ key, busy, defaultPrevented, inStrip, onBody }) =>
  key === "Escape" && Boolean(busy) && !defaultPrevented && Boolean(inStrip || onBody);

export const FirstMate = ({ snapshot, font, onOpenTicket, onOpenFile, draft, onDraft, onSend,
                            attachments, attachNotice, onPasteImages, onRemoveAttachment, tierDraft, onTierDraft, onFile, onNewConversation,
                            selection, repoDraft, onRepoDraft,
                            historyOpen, historyList, viewingConversation, onSelectConversation, onCloseHistory,
                            onResume, resumeBusy, resumeError, kickStarter, onSave, saveState, onPopConversation, popError,
                            onChatAboutScreen, chatAboutScreenOff, onStop,
                            onOpenInIterm, itermState, onSetChatRepo }) => {
  const [showAll, setShowAll] = useState(false);
  const s = snapshot || { history: [], filed: [] };
  const exchanges = toExchanges(s.history);
  const hidden = Math.max(0, exchanges.length - VISIBLE_EXCHANGES);
  const shown = showAll ? exchanges : exchanges.slice(-VISIBLE_EXCHANGES);
  const busy = Boolean(s.busy);
  const transcriptRef = useRef(null);
  // Sticky until the user scrolls up; a new/streaming reply then pulls the
  // view back to the bottom only while it's still true.
  const stickyRef = useRef(true);
  const onTranscriptScroll = () => {
    const el = transcriptRef.current;
    if (!el) return;
    stickyRef.current = isAtBottom(el.scrollHeight - el.scrollTop - el.clientHeight);
  };
  useEffect(() => {
    const el = transcriptRef.current;
    if (el && stickyRef.current) el.scrollTop = el.scrollHeight;
  }, [shown.length, s.streaming, showAll, busy, kickStarter]);
  const attached = attachments || [];
  const canSend = Boolean((draft || "").trim()) || attached.length > 0;
  const submit = () => {
    if (!canSend) return;
    onSend((draft || "").trim());
  };
  const onPaste = (e) => {
    const files = pastedImageFiles(e.clipboardData);
    if (!files.length) return;
    e.preventDefault();
    if (onPasteImages) onPasteImages(files);
  };
  const term = terminalFont(font);
  const face = term.face ? <style>{term.face}</style> : null;
  const pop = <PopControl canPop={Boolean(s.can_pop)} onPop={onPopConversation} />;
  // The live strip draws the pop control under the compose row (#725); the
  // History panels, which have no compose row, keep it in the header. Only the
  // live strip has "<-- chat about this" (#733), on its own row under the
  // heading (#1752), and, last, "Open in Claude Code ⇗" (#820).
  const makeHeader = (withPop) => (
    <div className="fmHeader">
      <div className="fmHeaderTitle">First mate</div>
      {withPop ? null : (
        <div className="fmChatScreenRow">
          <ChatScreenControl off={chatAboutScreenOff} onChat={onChatAboutScreen} />
        </div>
      )}
      <div className="fmHeaderButtons">
        <button className="fmNewConversation" onClick={onNewConversation}
                title={s.chat_repo_on ? NEW_CONVERSATION_TITLE : undefined}>Start new conversation{s.chat_repo_on ? (
          <span className="fmNewConversationRepo">{newConversationRepoText(selection)}</span>
        ) : null}</button>
        {withPop ? pop : null}
        <SaveButton snapshot={s} saveState={saveState} onSave={onSave} />
        {withPop ? null : <OpenInItermButton snapshot={s} itermState={itermState} onOpenInIterm={onOpenInIterm} />}
      </div>
      {s.chat_repo_on ? (
        <div className="fmChatRepoRow">
          <ChatRepoChip chatRepo={s.chat_repo || null} repos={selection && selection.repos}
                        onSetRepo={onSetChatRepo} disabled={busy} />
        </div>
      ) : null}
    </div>
  );
  const header = makeHeader(true);
  if (viewingConversation) {
    return (
      <aside className="firstMate" style={term.style}>
        {face}
        {makeHeader(true)}
        <HistoryView viewingConversation={viewingConversation} onCloseHistory={onCloseHistory} onOpenTicket={onOpenTicket}
                     onResume={onResume} resumeBusy={resumeBusy} resumeError={resumeError} />
      </aside>
    );
  }
  if (historyOpen) {
    return (
      <aside className="firstMate" style={term.style}>
        {face}
        {header}
        <HistoryList historyList={historyList} onSelectConversation={onSelectConversation} onCloseHistory={onCloseHistory} />
      </aside>
    );
  }
  return (
    <aside className="firstMate" style={term.style}>
      {face}
      {makeHeader(false)}
      {s.remoteState ? <div className="fmRemoteState" title={s.remote_detail || undefined}>{s.remoteState}</div> : null}
      {s.tier_notice ? <div className="fmNotice fmTierNotice">{s.tier_notice}</div> : null}
      {s.account_notice ? <div className="fmNotice fmAccountNotice">{s.account_notice}</div> : null}
      {s.mcp_notice ? <div className="fmNotice fmMcpNotice">{s.mcp_notice}</div> : null}
      {s.notice ? <div className="fmNotice">{s.notice}</div> : null}
      {popError ? <div className="fmNotice fmPopError">Not switched: {popError}</div> : null}
      {s.recovery ? <div className="fmRecovery">{s.recovery.message}</div> : null}
      <div className="fmTranscript" ref={transcriptRef} onScroll={onTranscriptScroll}>
        {hidden > 0 && !showAll
          ? <button className="fmOlder" onClick={() => setShowAll(true)}>{hidden} earlier exchange{hidden === 1 ? "" : "s"}</button>
          : null}
        {shown.map((exchange, i) => (
          <div className="fmExchange" key={i}>
            {exchange.map((row, j) => <Row key={j} row={row} onOpenTicket={onOpenTicket} />)}
          </div>
        ))}
        {kickStarter ? <KickStarterRow kick={kickStarter} onOpenTicket={onOpenTicket} onOpenFile={onOpenFile} /> : null}
        {busy && !s.streaming ? (
          <div className="fmRow fmRow-assistant">
            <Mark role="assistant" />
            <div className="fmText"><span className="fmThinking" role="status" aria-label="First mate is thinking"><span /><span /><span /></span></div>
          </div>
        ) : null}
        {s.streaming ? <div className="fmRow fmRow-assistant fmStreaming"><Mark role="assistant" /><div className="fmText"><LinkedText text={s.streaming} onOpenTicket={onOpenTicket} /></div></div> : null}
      </div>
      {s.file ? (
        <FileCard file={s.file} fileBusy={s.file_busy} fileError={s.file_error}
                  onFile={onFile} tierDraft={tierDraft} onTierDraft={onTierDraft}
                  selection={selection} repoDraft={repoDraft} onRepoDraft={onRepoDraft}
                  chatRepo={s.chat_repo || null} />
      ) : null}
      {s.unfiled_hint ? (
        <div className="fmHint">Long thread. Ask First mate to file it.</div>
      ) : null}
      {attachNotice ? <div className="fmNotice fmAttachNotice">{attachNotice}</div> : null}
      {attached.length ? <Attachments attachments={attached} onRemove={onRemoveAttachment} disabled={busy} /> : null}
      <div className="fmCompose">
        <span className="fmPrompt" aria-hidden="true">&gt;</span>
        <textarea className="fmInput" rows={1} value={draft || ""} placeholder="Ask First mate…"
                  disabled={busy}
                  onChange={(e) => onDraft(e.target.value)}
                  onPaste={onPaste}
                  onKeyDown={(e) => { if (composeKeyAction(e) === "send") { e.preventDefault(); submit(); } }} />
        {busy && onStop
          ? <button className="fmStop" title={STOP_TITLE} onClick={onStop}>Stop</button>
          : <button className="fmSend" disabled={busy || !canSend} onClick={submit}>Send</button>}
      </div>
      <div className="fmPopRow">{pop}</div>
    </aside>
  );
};

export { toExchanges, VISIBLE_EXCHANGES, HistoryList, HistoryView, ResumeControl };
