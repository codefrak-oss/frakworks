// ticket.jsx — the GitHub side of the ticket detail (detail.jsx, #802): the
// issue's description, its linked PRs and its comments from the engine's
// GET /ticket read, and the live transcript pane with its summary panel. The
// detail itself, and the Timeline it draws (src/renderer/timeline.jsx), are
// detail.jsx's; this file was the tree's whole ticket detail until #802
// folded it and the Issues board's into one.
import { useEffect, useRef, useState } from "react";
import { FRAMEWORK_REPO } from "./ticket-refs.js";

const fmtClock = (ts) => new Date(ts * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

// The Jev decisions page's parts, re-exported so the tests reach them through
// this bundle (dist/ticket.cjs) — jev.jsx is not an entry point of its own.
export { JevDecisions, JevDecisionsView, JevCallDrawer, JevCallsTable, JevStrip,
  JevUseCase, PerDayBars, PickBars, ConfidenceHistogram, topPicks, daysOf, jevCallIdOf, mergeCalls, sinceOf,
  fmtPct, fmtAt, USE_CASES, WINDOWS, DEFAULT_WINDOW, TOP_PICKS } from "./jev.jsx";
// The Pitch section's parts, likewise (pitch.jsx, #579).
export { TicketPitch, TicketPitchView, PITCH_SANDBOX, darkPitch, pitchHtmlOf, pitchRoundOf, ALWAYS, NEVER } from "./pitch.jsx";

// The live counter's cadence: the detail (detail.jsx) ticks once a second for
// the Timeline's and the progress bar's counters.
export const TICK_MS = 1000;

// The five comment shapes the fleet writes, recognised by prefix — the claim
// (the retired Operator's), the run-stats receipt, and a Smith's three terminal
// comments (bots/smith.md §7). Everything else is prose.
const EVENT_PREFIXES = [
  ["🤖", "claim"],
  ["📊", "receipt"],
  ["Done.", "done"],
  ["Blocked:", "blocked"],
  ["Returning to the Source:", "returned"],
];

export const classifyComment = (body) => {
  const b = String(body || "").trimStart();
  for (const [prefix, kind] of EVENT_PREFIXES) if (b.startsWith(prefix)) return kind;
  return "prose";
};

const firstLine = (body) => String(body || "").trim().split("\n")[0].slice(0, 160);

// The kinds that are bookkeeping: one line each (full text in the tooltip).
// The Runs and Cost sections already carry what a receipt says. Every other
// kind is someone explaining something and is shown in full.
export const COLLAPSED_KINDS = new Set(["claim", "receipt"]);

export const Comment = ({ c, html, mode, sanitize = sanitizeBody }) => {
  const kind = classifyComment(c.body);
  const open = !COLLAPSED_KINDS.has(kind);
  const safe = open && mode === "rendered" ? bodyHtml(c.body, html, sanitize) : null;
  return (
    <div className={`tdEvent ${kind}${open ? " open" : ""}`}>
      <div className="tdEventHead">
        <span className="tdWhen">{c.createdAt ? fmtClock(Math.floor(Date.parse(c.createdAt) / 1000)) : ""}</span>
        <span className="tdKind">{kind}</span>
        {c.author && <span className="muted">{c.author}</span>}
        {!open && <span className="tdBody" title={c.body}>{firstLine(c.body)}</span>}
      </div>
      {open && (safe != null
        ? <div className="tdDesc md" dangerouslySetInnerHTML={{ __html: safe }} />
        : <pre className="tdDesc">{String(c.body || "").trim()}</pre>)}
    </div>
  );
};

// The comments section as a whole folds away behind its heading; the choice
// is remembered per viewer.
const COMMENTS_KEY = "ticket.commentsOpen";

export const CommentsSection = ({ comments, error, html, mode }) => {
  const [open, setOpen] = useState(() => {
    try { return localStorage.getItem(COMMENTS_KEY) !== "false"; } catch (e) { return true; }
  });
  const flip = () => {
    setOpen(!open);
    try { localStorage.setItem(COMMENTS_KEY, String(!open)); } catch (e) { /* per-viewer nicety only */ }
  };
  return (
    <>
      <h2 className="tdFold" onClick={flip} title={open ? "Hide the comments" : "Show the comments"}>
        <span className="tdFoldArrow">{open ? "▾" : "▸"}</span>
        Comments
        <span className="tdFoldCount">{comments.length}</span>
      </h2>
      {open && <Thread comments={comments} error={error} html={html} mode={mode} />}
    </>
  );
};

const Thread = ({ comments, error, html = {}, mode }) => {
  if (error) return <div className="err">{error}</div>;
  if (!comments.length) return <div className="muted">No comments</div>;
  return (
    <div className="tdThread">
      {comments.map((c, i) => <Comment key={c.id || i} c={c} html={html[c.url]} mode={mode} />)}
    </div>
  );
};

// The issue body, two ways: GitHub's own rendering (`bodyHTML`, fetched with
// the linked PRs), or for a work item its markdown rendered here (bodyHtml),
// or the markdown source. The HTML is written by whoever filed
// the issue, so it goes through DOMPurify before it touches the DOM, even
// though GitHub already sanitised it; where DOMPurify cannot run (no DOM, as
// in the node tests) the view falls back to raw. Links in it open in the
// browser via main.js's will-navigate handler. Long bodies are collapsed to
// DESCRIPTION_LINES lines (raw) or the equivalent height (rendered).
export const DESCRIPTION_LINES = 12;
const MODE_KEY = "ticket.descriptionMode";

export { sanitizeBody, markdownToHtml, bodyHtml } from "./markdown.js";
import { sanitizeBody, bodyHtml } from "./markdown.js";

export const readMode = () => { try { return localStorage.getItem(MODE_KEY) || "rendered"; } catch (e) { return "rendered"; } };
const saveMode = (m) => { try { localStorage.setItem(MODE_KEY, m); } catch (e) { /* per-viewer nicety only */ } };

// `mode` is owned by the detail (detail.jsx TicketDetail) so the switch here also flips the comments.
export const Description = ({ body, html, mode, onMode, sanitize = sanitizeBody }) => {
  const [open, setOpen] = useState(false);
  const text = String(body || "").trim();
  if (!text) return <div className="muted">No description</div>;
  const lines = text.split("\n");
  const long = lines.length > DESCRIPTION_LINES;
  const safe = bodyHtml(text, html, sanitize);
  const rendered = safe != null && mode === "rendered";
  const pick = (m) => { onMode(m); saveMode(m); };
  return (
    <div>
      {safe != null && (
        <div className="tdDescMode">
          <button className={rendered ? "on" : ""} onClick={() => pick("rendered")}>rendered</button>
          <span>·</span>
          <button className={rendered ? "" : "on"} onClick={() => pick("raw")}>raw</button>
        </div>
      )}
      {rendered
        ? <div className={"tdDesc md" + (long && !open ? " clipped" : "")} dangerouslySetInnerHTML={{ __html: safe }} />
        : <pre className="tdDesc">{long && !open ? lines.slice(0, DESCRIPTION_LINES).join("\n") + "\n…" : text}</pre>}
      {long && (
        <button className="tdMore" onClick={() => setOpen(!open)}>
          {open ? "show less" : rendered ? "show all" : `show all ${lines.length} lines`}
        </button>
      )}
    </div>
  );
};

// The PRs that close this ticket (src/ticket-data.js, from the engine's GET /ticket).
export const LinkedPrs = ({ prs, error }) => {
  if (error) return <div className="err">{error}</div>;
  return (
    <div className="tdPrs">
      {prs.map((p) => (
        <div key={p.number} className="tdPr">
          <a href={p.url} title="Open on GitHub">#{p.number}</a>
          <span className={"chip tdState " + p.state.toLowerCase()}>{p.state.toLowerCase()}</span>
          {p.review && <span className="chip">{p.review.toLowerCase().replace(/_/g, " ")}</span>}
          <span className="tdBody">{p.title}</span>
        </div>
      ))}
    </div>
  );
};

// ---- the live transcript pane (stage 4, #1283) -----------------------------

// While a run is live the pane follows a VictoriaLogs live tail held in main
// (src/transcript-tail.js, #1006): VictoriaLogs holds the lines, not the
// engine, and the tail pushes each one as it arrives, with no timer here. It is
// open only between a `session` `started` and its `ended`; between runs no
// connection is open at all.
// How much scrollback to keep. A long run ships thousands of events; the pane
// is a tail, not an archive, and `dispatch/smith-tail <n>` on the host is still
// the way to read a whole run.
export const TRANSCRIPT_MAX_LINES = 2000;
export const EMPTY_TRANSCRIPT = {
  session: null, keys: new Set(), lines: [], dropped: 0, live: false, liveKnown: false,
  host: null, kind: null, model: null, error: null, cursor: null, polled: false, lapsed: null,
};

// The pane's `run` (index.jsx) after one `session` frame. `started` names the
// run to follow; one with no `filename` is unknown (null: the gauge is asked),
// never the run before it. `ended` ends the run followed only when it names
// that run (or either side has no filename): a late `ended` for the previous
// step's run, arriving after the next step's `started`, leaves the new run live.
export const nextRun = (r, data) => {
  if (!data) return r;
  if (data.state === "started") return data.filename ? { filename: data.filename, host: data.host || null } : null;
  if (data.state !== "ended" || !r || r.ended) return r;
  if (data.filename && r.filename && data.filename !== r.filename) return r;
  return { ...r, ended: true };
};

// Fold one document (src/transcript-data.js's read, or a batch of the tail's
// lines) into the accumulated pane state. Two things make this worth being a
// pure function rather than inline effect code: it is where "appends only new
// events" actually happens — the read's cursor range is inclusive at both
// ends, and the tail opens with a look-back over the read it follows, so the
// same event comes twice — and it is where a *new* run for the same ticket
// clears the pane instead of concatenating two Smiths' output.
export const mergeTranscript = (prev, doc) => {
  const p = prev || EMPTY_TRANSCRIPT;
  const restart = !!(doc.session && p.session && doc.session !== p.session);
  const keys = restart ? new Set() : new Set(p.keys);
  const added = [];
  for (const ev of doc.events || []) {
    if (keys.has(ev.key)) continue;
    keys.add(ev.key);
    // One item per entry (#934): a result event's entry keeps its own newline
    // (the `is_error` detail line), so it stays one node with its `↳` line.
    for (const entry of ev.lines) added.push(String(entry));
  }
  let lines = restart ? added : (added.length ? [...p.lines, ...added] : p.lines);
  // `dropped` counts the lines the tail has let go of since the run began, so
  // `dropped + i` names line i for as long as it is on screen (the pane's key).
  let dropped = restart ? 0 : (p.dropped || 0);
  if (lines.length > TRANSCRIPT_MAX_LINES) {
    dropped += lines.length - TRANSCRIPT_MAX_LINES;
    lines = lines.slice(-TRANSCRIPT_MAX_LINES);
  }
  return {
    session: doc.session || (restart ? null : p.session),
    keys, lines, dropped,
    live: !!doc.live,
    liveKnown: !!doc.liveKnown,
    host: doc.host || p.host,
    kind: doc.kind || p.kind,
    model: doc.model || p.model,
    error: doc.error || null,
    cursor: doc.cursor || p.cursor,
    polled: true,
  };
};

// True when the pane belongs on screen: a live run, something already read, or
// a failure that has to be visible rather than silent.
export const hasTranscript = (t) =>
  !!(t && (t.live || t.lines.length || t.error));

// The byline. A run is live while the engine's `session` `started` frame has
// come and its `ended` has not (index.jsx carries that onto the state as
// `live`; the one read on mount and on each connect's snapshot asks
// `smith_run_active` once for a run already going). Between two steps of a
// flow still running (`flowRunning`, #947) the pane waits for the next
// `session` `started`, with no connection open. `lapsed`: the tail dropped
// while the run is live and main is reopening it. `paused`: the reader turned
// scraping off on the running card (#867), said only while a run is live.
export const liveNote = (t, paused = false) => {
  if (paused && isLive(t)) return "scraping off · tail closed";
  if (t.live && t.lapsed) return "● live · tail dropped, reconnecting";
  if (t.live) return "● live · tailing";
  if (!t.polled) return "…";
  if (t.flowRunning) return "run ended · waiting for the next step";
  return "run ended · tail closed";
};

// Whether the pane follows the run: only while it is live. A run's end
// (`session` `ended`) closes the tail; the next step's `started` opens one
// again, and the new `session` replaces the old lines.
export const isLive = (t) => !!(t && t.live);

// The renderer's side of main's tail (`transcript:tail-*`, src/preload.js):
// `open(number, run, since, onFrame)` asks main to tail `run` from the cursor
// and hands its pushed frames to `onFrame`; the handle's `end()` (the run
// ended: main drops the tail after its grace period and a last read, and the
// promise settles then) and `close()` (now). The token is ours, so a frame
// pushed before the open's answer is still ours. Null without the bridge.
let tailSeq = 0;
export const fleetTail = (fleet) => (fleet && fleet.openTranscriptTail && fleet.onTranscriptTail ? {
  open: (number, run, since, onFrame) => {
    const token = `tail-${++tailSeq}-${Math.random().toString(36).slice(2, 8)}`;
    const off = fleet.onTranscriptTail((f) => { if (f && f.token === token) onFrame(f); });
    const opened = Promise.resolve(fleet.openTranscriptTail(token, number, run, since))
      .then((r) => { if (r && r.error) onFrame({ state: "failed", error: String(r.error) }); })
      .catch((e) => onFrame({ state: "failed", error: `IPC failed: ${e && e.message ? e.message : e}` }));
    return {
      end: () => opened.then(() => fleet.endTranscriptTail(token)).catch(() => {}).finally(off),
      close: () => { off(); opened.then(() => fleet.closeTranscriptTail(token)).catch(() => {}); },
    };
  },
} : null);

// The transcript loop itself, as index.jsx's effect runs it for one (ticket,
// run): `read` reads VictoriaLogs once through `readDoc(number, cursor, run)`
// (main's `fetchTranscript`; `run` `{ filename, host }` makes it skip the
// gauge and the "which session?" query) and folds the answer with
// mergeTranscript; `tail.open` (fleetTail) then follows the run live. What the
// loop knows about the run is `run`:
// - `{ filename, host }`, live (from `session` `started`, or a gauge read that
//   found one): one read of the lines so far, then the tail from that read's
//   cursor, which pushes each line as it lands until `end()` or scraping off.
//   The read and the tail overlap (the tail's `start_offset`,
//   src/transcript-tail.js) and mergeTranscript's per-event key keeps a line
//   both return once, so nothing falls between the read's end and the tail's
//   start. A drop is main's to reopen: the byline says `lapsed` meanwhile, and
//   the gap read from the cursor arrives as tail lines.
// - `end()` (the `ended` frame, same run): the tail stays open for main's
//   grace period, main reads the run once more, then drops it; only then does
//   the byline say the run ended. No gauge read: the engine said the run
//   ended, and `smith_run_active` is a scraped series that can still answer
//   for a while after.
// - `{ filename, host, ended: true }` (a run handed up as ended): one read of
//   the known run, for its lines, and no tail.
// - `null`, unknown (the mount, ⟳ after an ended run): one read that asks the
//   gauge once; a run it finds is handed to `onRun`, whose new loop follows it.
// `snapshot()` (every connect's catch-up) is the same one gauge read, with a
// live run known or not (`read(null)`, whatever `current` says): a run started
// while the stream was down is found, and so is one that ended while it was
// down, whose `ended` frame is not replayed. A gauge that names the session
// already followed keeps the loop (and its tail) on it (no `onRun`: a restart
// would read the gauge again); one that names another run hands it to
// `onRun`; one that finds no run closes the tail, clears `current`, so the
// byline says ended, and hands the run followed (if any) to `onRun` as ended,
// so the pane's `run` says the same and ⟳ asks the gauge again rather than
// restarting on it. `pause()` (scraping off) closes the tail; `resume()` reads
// once from the cursor, the gap, and opens it again. `paused()` and
// `flowRunning()` are read at each fold, `update(acc)` sets the pane's state,
// `stop()` ends it: no read lands after it, no tail left open. The loop arms
// no timer: what follows a live run is the tail.
export const transcriptLoop = ({ number, run = null, readDoc, tail = null, acc, update, onRun, paused = () => false, flowRunning = () => false }) => {
  // A loop for another run than the lines held (the next step's `started`,
  // perhaps inside the old run's end grace period): start that run from the
  // beginning of its window, never from the old run's cursor or lines.
  if (run && run.filename && acc.current && acc.current.session && acc.current.session !== run.filename) {
    acc.current = null;
    update(null);
  }
  let stopped = false, inFlight = false, handle = null;
  // `end()`'s promise while the run's tail drains, and the handle draining.
  let ending = null, endingHandle = null, ended = false;
  // A `snapshot()` asked for while a read was out: its resolver, so the gauge
  // read runs (once) when that read settles instead of being skipped.
  let snapshotWanted = null;
  const settleSnapshot = () => { const res = snapshotWanted; snapshotWanted = null; if (res) res(); };
  // The live run the loop follows; an ended or unknown run is none.
  let current = run && !run.ended ? run : null;
  const set = (next) => { acc.current = next; update(next); };
  const closeTail = () => { if (handle) { const h = handle; handle = null; h.close(); } };
  // One tail's frames, for the run it was opened on.
  const framesFor = (following) => (f) => {
    if (stopped || !f) return;
    const a = acc.current || EMPTY_TRANSCRIPT;
    if (f.events) {
      const doc = { session: following.filename, host: following.host, live: true, liveKnown: true,
                    events: f.events, cursor: f.cursor, error: null };
      set({ ...mergeTranscript(a, doc), live: !ended, flowRunning: flowRunning(), lapsed: null });
    } else if (f.state === "lapsed") set({ ...a, lapsed: f.error || "the tail dropped" });
    else if (f.state === "tailing") set({ ...a, lapsed: null, error: null });
    else if (f.state === "failed") set({ ...a, error: f.error || "the tail could not be opened" });
  };
  const openTail = () => {
    if (stopped || !tail || handle || !current || ending || paused()) return;
    const prev = acc.current;
    const following = { filename: current.filename, host: current.host };
    handle = tail.open(number, following, prev && prev.cursor, framesFor(following));
  };
  const read = async (forRun = current) => {
    inFlight = true;
    const prev = acc.current;
    let doc;
    try { doc = await readDoc(number, prev && prev.cursor, forRun ? { filename: forRun.filename, host: forRun.host } : null); }
    catch (e) {
      doc = { events: [], live: false, liveKnown: Boolean(forRun), session: prev && prev.session,
              cursor: prev && prev.cursor, error: `IPC failed: ${e && e.message ? e.message : e}` };
    }
    inFlight = false;
    if (stopped) { settleSnapshot(); return; }
    // A run known from `session` is live until its `ended`, whatever one read said; an ended one is not.
    const live = !ended && (forRun ? !forRun.ended : Boolean(doc.live));
    const next = { ...mergeTranscript(acc.current, doc), live, flowRunning: flowRunning(), lapsed: (acc.current && acc.current.lapsed) || null };
    set(next);
    // A catch-up came while this read was out: its gauge read now.
    if (snapshotWanted) { const res = snapshotWanted; snapshotWanted = null; return read(null).then(res); }
    if (!forRun) {
      // The gauge was asked (the mount, ⟳, a catch-up). No run live: follow
      // none, and close the tail. A run followed before (its `ended` missed
      // while the stream was down) is over: hand it up as ended, so the pane's
      // `run` agrees (the effect's next loop reads the known run once, opens
      // nothing, and ⟳ asks the gauge again). None followed: nothing to hand
      // up, no second read.
      if (!doc.live) {
        closeTail();
        const over = current ? { ...current, ended: true } : null;
        current = null;
        if (over) onRun(over);
        return;
      }
      const found = { filename: doc.session || null, host: doc.host || null };
      // Another run than the one followed (or none was): hand it up; the effect's next loop follows it.
      if (!(current && current.filename && current.filename === found.filename)) { closeTail(); current = found; onRun(current); return; }
    }
    openTail();
  };
  const loop = {
    pause: () => closeTail(),
    // Back on: one read from the cursor (the lines missed while off), then the tail.
    resume: () => { if (!stopped && !handle && !inFlight && current && !ending) read(); },
    // The connect's snapshot: one read now with no run passed, so the gauge is
    // asked whether the run followed (if any) is still the live one. Not while
    // the run's end drains: the engine has already said.
    snapshot: () => {
      if (stopped) return Promise.resolve();
      if (ending) return ending;
      if (inFlight) return new Promise((res) => { settleSnapshot(); snapshotWanted = res; });
      return read(null);
    },
    // The run's `session` `ended`: let its tail drain and drop (main's grace
    // period and last read), then say the run ended. With no tail open
    // (scraping off, or the first read still out), one read of the known run
    // for its last lines instead.
    end: () => {
      if (stopped || !current) return Promise.resolve();
      if (ending) return ending;
      const h = handle; handle = null; endingHandle = h;
      const was = current;
      ending = (async () => {
        if (h) await h.end();
        else { ended = true; await read({ ...was, ended: true }); }
        endingHandle = null;
        ended = true; current = null;
        if (stopped) return;
        const a = acc.current || EMPTY_TRANSCRIPT;
        set({ ...a, polled: true, live: false, lapsed: null, flowRunning: flowRunning() });
      })();
      return ending;
    },
    // The flow was seen running or ended: carry it onto the state for the byline.
    flow: (running) => {
      const a = acc.current;
      if (stopped || !a || a.flowRunning === running) return;
      set({ ...a, flowRunning: running });
    },
    stop: () => {
      stopped = true;
      closeTail();
      if (endingHandle) { endingHandle.close(); endingHandle = null; }
      if (!inFlight) settleSnapshot();
    },
    get tailing() { return handle != null || endingHandle != null; },
  };
  // The first read: the run as given (an ended one reads its lines once).
  read(run);
  return loop;
};

// `paused`: scraping is off (the running card's control, #867), said in the byline.
export const TranscriptPane = ({ transcript, paused = false }) => {
  const t = transcript || EMPTY_TRANSCRIPT;
  const ref = useRef(null);
  // Follow the tail, the way `smith-tail` does. Keyed on the line count so a
  // re-render that added nothing does not yank a reader back to the bottom.
  useEffect(() => {
    const el = ref.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [t.lines.length]);
  const session = t.session ? String(t.session).split("/").pop() : null;
  return (
    <div>
      <div className="tdBarFoot">
        <span className="muted" title={t.session || ""}>
          {session || "no run log shipped"}{t.host ? ` · ${t.host}` : ""}{t.kind ? ` · ${t.kind}` : ""}
        </span>
        <span className={t.live && !paused ? "tdLive" : "muted"}>{liveNote(t, paused)}</span>
      </div>
      {t.error && <div className="err">{t.error}</div>}
      {/* One text node per entry, a blank line after each (#934), keyed by its place in the run: a read or the tail only
          appends nodes, and the ones on screen stay, with find in page's
          highlights on them (#703). One joined string was a new text node
          on every read, which cleared a match in the pane each time. */}
      <pre className="tdTranscript" ref={ref}>
        {t.lines.length
          ? t.lines.map((l, i) => <span key={(t.dropped || 0) + i} className={l.startsWith("→ ") ? "tdToolLine" : undefined}>{`${l}\n\n`}</span>)
          : (t.polled ? "no events shipped for this run yet" : "loading transcript…")}
      </pre>
    </div>
  );
};

// ---- the summary panel (#423, #881) -----------------------------------------

// What the running card's Summarize button (detail.jsx, TranscriptControls)
// got back, under the card above the pane: the engine's LLM summary of the
// running Smith's session, asked through main (window.fleet.summarize, the
// engine:summarize channel). Results only, the button is on the card. `state`
// is null before a click, { loading }, { summary: { summary, now } } or
// { error }; null and { loading } draw nothing.
export const SummaryPanel = ({ state }) => {
  const s = state || {};
  if (!s.error && !s.summary) return null;
  return (
    <div className="tdSummary">
      {s.error && <div className="err">{String(s.error)}</div>}
      {s.summary && (
        <dl className="sessionSummary">
          <dt>Session</dt>
          <dd>{s.summary.summary}</dd>
          <dt>Now</dt>
          <dd>{s.summary.now}</dd>
        </dl>
      )}
    </div>
  );
};

// One Summarize request for `ticket` in `repo` ("owner/name"), as a
// SummaryPanel state. Never rejects.
export const summarizeOn = async (fleet, repo, ticket) => {
  if (!fleet || typeof fleet.summarize !== "function") return { error: "the app has no summarize channel" };
  const [owner, name] = String(repo).split("/");
  try {
    const r = await fleet.summarize(owner, name, ticket);
    return r && r.summary ? { summary: r.summary } : { error: (r && r.error) || "the engine gave no summary" };
  } catch (e) {
    return { error: (e && e.message) || String(e) };
  }
};

// ---- the view --------------------------------------------------------------

// The repo the Timeline asks the engine under. The engine keys a ticket's
// history by the repo it recorded, which is the issue's current (post-move)
// name, `doc.issue_repo` — not necessarily `doc.repo`, the one asked for
// (#355).
export const historyRepoOf = (doc) => (doc && (doc.issue_repo || doc.repo)) || FRAMEWORK_REPO;
