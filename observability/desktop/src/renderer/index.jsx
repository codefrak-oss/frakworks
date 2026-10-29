// index.jsx — renderer entry. Owns each view's read and when it runs: on the
// engine's event stream (renderer/engine-events.js: a refetch on the frames
// the view cares about, and once on every connect's catch-up), and on the few
// timers left for what the engine does not hold (the proposals' default
// branch, the transcript's VictoriaLogs lines while a run is live). No view
// polls the engine for anything the stream carries (engine/README.md, "Events").
// The data itself comes through the preload bridge (src/preload.js ->
// src/main.js -> engine-data / ticket-data).
//
// It is also the router, such as it is: one piece of state says whether the
// window shows a view or a ticket's detail screen (stage 2,
// #1281). A ticket picked in the explorer sets it; the back control
// clears it. No history stack and no URLs.
import { createRoot } from "react-dom/client";
import { useEffect, useState, useCallback, useRef, useMemo } from "react";
import { transcriptLoop, fleetTail, nextRun } from "./ticket.jsx";
import { useEngineEvents, useRefetchOn, useStreamState, applyBoardRow, dropBoardRow, withoutRemoved, rememberRemoved, removedClosed,
  closedIssue, useBoardRows, StaleLine, coalesce } from "./engine-events.js";
import { treeOf, withClosed } from "../ticket-groups.js";
import { TicketDetail } from "./detail.jsx";
// The one ticket detail, re-exported so the tests reach it through dist/index.cjs.
export { TicketDetail, TicketDetailView, summaryFor, TranscriptControls, AuthorizeNotice, authorizeNeed, AUTHORIZE_KINDS } from "./detail.jsx";
import { TitleBar } from "./title-bar.jsx";
import { titleFor, ticketId, parseTicketRef } from "./repo-switcher.jsx";
import { NeedsYou, needsTicketCount } from "./needs-you.jsx";
import { HitlpRequests, HitlpClosed } from "./hitlp-requests.jsx";
import { HitlpPopup } from "./hitlp-popup.jsx";
import { FirstMate, kickStarterOf, withKickStarter, escapeCancels } from "./first-mate.jsx";
import { admitPaste, IMAGE_MAX_COUNT } from "../first-mate-images.js";
import { FirstMateModel } from "./first-mate-model.jsx";
import { Explorer, treeEntryOf } from "./explorer.jsx";
import { Accounts } from "./accounts.jsx";
import { RefinerTierPanel } from "./refiner-tier.jsx";
import { FrakpanelTilesPanel } from "./frakpanel-tiles.jsx";
import { ScreenshotSettingPanel } from "./screenshot-setting.jsx";
import { EngineUrlPanel } from "./engine-url-setting.jsx";
import { SmithHostingPanel, FirstMateHostingPanel } from "./smith-hosting.jsx";
import { PalettesView, applyPalette, loadPaletteId } from "./palettes.jsx";
import { RepoNamePanel } from "./repo-name-form.jsx";
import { SignInPanel } from "./sign-in.jsx";
import { TEACH_TOKEN_HINT } from "../judge-teach.js";
import { SURFACE_HINT, SURFACE_HINT_WEB } from "../hitlp-surface-hint.js";
import { TiersPanel } from "./tiers.jsx";
import { EnvLinesPanel } from "./env-lines.jsx";
import { Repos, applySetupFrame } from "./repos.jsx";
import { StatusBar } from "./status-bar.jsx";
import { UpdateOverlay, HealthCheckOverlay } from "./update-overlay.jsx";
import { UpdatePreview } from "./update-preview.jsx";
import { instanceOf, readOffBoard, needsOf } from "./flow.jsx";
import { GrafanaDashboard } from "./grafana.jsx";
import { JevDecisions } from "./jev.jsx";
import { SshVerdicts } from "./ssh-verdicts.jsx";
import { DoctorRuns } from "./doctor.jsx";
import { Consumption } from "./consumption.jsx";
import { Builds } from "./builds.jsx";
import { HitlpProducer } from "./hitlp-producer.jsx";
import { Jobs } from "./jobs.jsx";
import { FrictionNotes } from "./friction.jsx";
import { Tickets } from "./work-items.jsx";
import { RefactorRunsPage } from "./refactor-runs.jsx";
import { SavedChat } from "./saved-chat.jsx";
import { ClaudeCodeSession } from "./claude-code-session.jsx";
export { ClaudeCodeSessionView, ccHandoffAnswer, CC_ITERM_TITLE, CC_COPY_TITLE, CC_ITERM_NOTICE } from "./claude-code-session.jsx";
import { ProposalView, PROPOSALS_REFRESH_MS } from "./proposals.jsx";
import { RepoFileView, isChattable } from "./repo-files.jsx";
import { isProposal } from "./pitch.jsx";
import { repoFileId as repoFileIdOf, parseRepoFileId } from "../repo-files.js";
import { toProposalRows, findProposal, proposalId as proposalIdOf } from "../proposal-list.js";
import { Splitter, clampWidth, dragWidth, readWidth, saveWidth, DEFAULT_WIDTH, EXPLORER_KEY, FIRST_MATE_KEY } from "./splitter.jsx";

// How often the status bar asks main to compare the checkout with the running
// commit. Local git only, milliseconds, so it can be often.
export const VERSION_CHECK_MS = 3000;

// The status bar's state: the version main reports (with the running-vs-
// checkout comparison), and the restart in flight if any.
const useVersion = (openPreview = null) => {
  const [version, setVersion] = useState(null);
  const [updating, setUpdating] = useState(null);
  useEffect(() => {
    if (!(typeof window !== "undefined" && window.app && window.app.version)) return undefined;
    let live = true;
    const check = () => window.app.version().then((v) => { if (live) setVersion(v); }).catch(() => {});
    check();
    const t = setInterval(check, VERSION_CHECK_MS);
    return () => { live = false; clearInterval(t); };
  }, []);
  const act = useCallback(async (fn) => {
    setUpdating({ steps: [] });
    let r;
    try { r = await fn(); }
    catch (e) { r = { ok: false, steps: [{ step: "restart", ok: false, detail: `IPC failed: ${e && e.message ? e.message : e}` }] }; }
    // A success relaunches the app; only a failure comes back to be drawn.
    // Stay, at the prompt an engine update in flight puts first, cancels it.
    if (r && r.ok === false) setUpdating(r);
    else if (r && r.cancelled) setUpdating(null);
  }, []);
  // A downloaded desktop update first opens the update preview modal
  // (#100091); its Update restarts, its Cancel leaves the app as it is.
  const onRestart = useCallback(async () => {
    const restart = () => act(() => window.app.restart());
    if (!(window.app.updatePreview && openPreview)) return restart();
    const [model, snapshot] = await Promise.all([window.app.updatePreview().catch(() => null), snap()]);
    if (!model) return restart();
    openPreview({ model, snapshot, onUpdate: restart });
  }, [act, openPreview]);
  return { version, updating, onRestart };
};

// The window as it is, captured once for the update preview's blur (#100091); null without one.
const snap = () => (window.app && window.app.snapshot ? window.app.snapshot().catch(() => null) : Promise.resolve(null));

// The update preview modal's state (#100091): `{ model, snapshot, onUpdate }` or null.
const useUpdatePreview = () => {
  const [preview, setPreview] = useState(null);
  const openPreview = useCallback((p) => setPreview(p), []);
  const onCancel = useCallback(() => setPreview(null), []);
  const onUpdate = useCallback(() => { const p = preview; setPreview(null); if (p) p.onUpdate(); }, [preview]);
  return { preview, openPreview, onCancel, onUpdate };
};

// The Update engine button's state (src/engine-update.js, checked by main
// every 30 s): polled with the version, and the click's result, or the
// result main pushes when no click awaits one (a resumed update's end, an
// engine started from Created). The click takes nothing: main checks again,
// confirms, and only then updates.
const useEngineUpdate = (openPreview = null) => {
  const [state, setState] = useState(null);
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const has = typeof window !== "undefined" && window.app && window.app.engineUpdate;
  const read = useCallback(() => (has ? window.app.engineUpdate().then(setState).catch(() => {}) : Promise.resolve()), [has]);
  useEffect(() => {
    if (!has) return undefined;
    read();
    const t = setInterval(read, VERSION_CHECK_MS);
    const off = window.app.onEngineUpdateResult ? window.app.onEngineUpdateResult((r) => { setResult(r); read(); }) : null;
    return () => { clearInterval(t); if (off) off(); };
  }, [has, read]);
  // The click asks main for the update preview (#100091); the modal's Update runs the update.
  const run = useCallback(async () => {
    setBusy(true);
    setResult(null);
    let r;
    // While it runs, the state is read each second, so a deploy shows "deploying…" through the gap.
    const follow = setInterval(read, 1000);
    try { r = await window.app.updateEngine(); }
    catch (e) { r = { ok: false, steps: [{ step: "update", ok: false, detail: `IPC failed: ${e && e.message ? e.message : e}` }] }; }
    clearInterval(follow);
    setBusy(false);
    setResult(r);
    read();
  }, [read]);
  const onUpdateEngine = useCallback(async () => {
    if (!(window.app.engineUpdatePreview && openPreview)) return run();
    setBusy(true);
    setResult(null);
    let p;
    try { p = await window.app.engineUpdatePreview(); }
    catch (e) { p = { result: { ok: false, steps: [{ step: "update", ok: false, detail: `IPC failed: ${e && e.message ? e.message : e}` }] } }; }
    const snapshot = p && p.preview ? await snap() : null;
    setBusy(false);
    if (p && p.preview) openPreview({ model: p.preview, snapshot, onUpdate: run });
    else { setResult((p && p.result) || p); read(); }
  }, [openPreview, run, read]);
  return { engineUpdate: state, engineResult: result, engineBusy: busy, onUpdateEngine };
};

// The Update engine overlay's progress (#1053): read once when the window
// loads (a relaunch that resumed an update), then every push main makes as a
// line or an answer arrives; null once the overlay is closed. No timer: the
// progress is pushed, never asked for.
export const useUpdateProgress = () => {
  const [progress, setProgress] = useState(null);
  const has = typeof window !== "undefined" && window.app && window.app.onEngineUpdateProgress;
  useEffect(() => {
    if (!has) return undefined;
    let live = true;
    if (window.app.engineUpdateProgress) {
      Promise.resolve(window.app.engineUpdateProgress()).then((p) => { if (live && p) setProgress((prev) => prev || p); }).catch(() => {});
    }
    const off = window.app.onEngineUpdateProgress((p) => { if (live) setProgress(p || null); });
    return () => { live = false; if (typeof off === "function") off(); };
  }, [has]);
  const onClose = useCallback(async () => {
    const r = window.app.closeEngineUpdateProgress ? await window.app.closeEngineUpdateProgress() : { ok: true };
    if (r && r.ok) setProgress(null);
  }, []);
  return { progress, restarting: !!(progress && progress.inFlight), onClose };
};

// What the detail view will draw for a ticket result, reported to the main process so a headless run can log it.
// All four sources dead is the only thing that counts as an error: any one
// panel answering means the view is useful.
export const classifyTicket = ({ doc, error }) => {
  if (error) return { kind: "error", message: String(error) };
  if (!doc) return { kind: "error", message: "no ticket data" };
  const errs = ["issue_error", "prs_error"].map((k) => doc[k]).filter(Boolean);
  if (errs.length === 2) return { kind: "error", message: errs[0] };
  return {
    kind: "ticket",
    message: `#${doc.ticket}: ${(doc.issue && doc.issue.comments.length) || 0} comments, ${(doc.prs || []).length} PRs` +
      (errs.length ? ` — ${errs.join(" | ")}` : ""),
  };
};

const noop = () => undefined;

/** "owner/repo#12" → { repo, number }, else null. */
export const parseIssueRef = (s) => {
  const m = typeof s === "string" && s.match(/^([\w.-]+\/[\w.-]+)#(\d+)$/);
  return m ? { repo: m[1], number: Number(m[2]) } : null;
};

/**
 * What the ticket detail's own Chat about this hands onChatAbout (#733): a
 * proposal's Pitch view's `{ repo, number, title }` (pitch.jsx PitchView), else
 * the detail's `{ kind: "ticket", repo, number, title }` (detail.jsx, #699),
 * titled from the board row, else `title` (the ticket read's), else the
 * off-board read's.
 */
export const issueAboutOf = ({ repo, number, issue, offBoard, title = null }) => (issue && isProposal(issue)
  ? { repo: issue.repo, number: issue.number, title: issue.title }
  : { kind: "ticket", repo, number, title: issue ? issue.title : title || (offBoard && offBoard.ticket && offBoard.ticket.title) || "" });

// The ticket detail screen (#802): the one screen every list opens a ticket
// `{ repo, number }` on — Tickets > Tickets, Tickets > Needs you, a Tickets tree
// row, a Jev or Doctor link, a saved chat, the First mate — so the detail
// (detail.jsx) and its actions are the same whichever it came from. It holds
// the reads the detail draws:
// - the board's row for the issue (`board`, App's GET /issues, applied in place
//   from `flow` frames) and its `/needs` rows (`needs`, App's GET /needs);
// - the issue's flow from the engine, re-read on its `flow` and `intake` frames;
// - the engine's ticket read through window.fleet.fetchTicket (description,
//   PRs, comments), which never rejects;
// - for an issue the board has no row for (#548), the off-board read;
// - the live transcript, a VictoriaLogs live tail while a Smith runs
//   (`session` `started` to `ended`).
// `intake` ({ number, url }) is the intake that filed it, from its Explorer
// tree entry; null for none. `onChatTarget(about)` (#733), optional, hears
// issueAboutOf as it changes. `selected` is the repo switcher's selection: a
// detail on another repo holds a second stream subscription for its repo
// while it is open (window.fleet.engineSubscribe).
const TicketScreen = ({ repo, number, board, needs, intake, onRefreshBoard, onRefreshNeeds, onBack, onChatAbout, proposal, onSelect, onChatTarget, selected = null }) => {
  const issue = ((board && board.issues) || []).find((i) => i.repo === repo && i.number === number) || null;
  const instance = instanceOf(issue);
  useEffect(() => {
    if (!repo || !selected || selected === repo || !(window.fleet && window.fleet.engineSubscribe)) return undefined;
    window.fleet.engineSubscribe(repo);
    return () => { window.fleet.engineUnsubscribe(repo); };
  }, [repo, selected]);
  // No board row (#548): the board lists open issues only, so a closed ticket
  // Needs you still lists is read from GET /ticket instead. An issue on the
  // board makes no such read.
  const onBoard = Boolean(issue);
  const boardReady = Boolean(board && !board.loading);
  // Keyed on booleans, not the board object, so a board poll does not re-read the ticket.
  const [offBoard, setOffBoard] = useState({ loading: true });
  const loadOffBoard = useCallback(async () => {
    const p = readOffBoard({ board: boardReady ? {} : null, issue: onBoard, repo, number, fleet: window.fleet });
    if (!p) { setOffBoard(onBoard ? null : { loading: true }); return; }
    setOffBoard((prev) => (prev && !prev.loading ? prev : { loading: true }));
    setOffBoard(await p);
  }, [boardReady, onBoard, repo, number]);
  useEffect(() => { loadOffBoard(); }, [loadOffBoard]);
  const issueNeeds = needsOf(needs && needs.rows, repo, number);

  // The flow.
  const [flowResult, setFlowResult] = useState(null);
  const [flowRefreshing, setFlowRefreshing] = useState(false);
  const ended = Boolean(flowResult && flowResult.flow && flowResult.flow.ended);
  // Every read the stream triggers is `coalesce`d (engine-events.js): a frame's
  // refetch that lands while a read is out is not dropped (nothing would
  // re-read it) but run once more when that read settles.
  const loadFlow = useMemo(() => coalesce(async () => {
    if (!instance) return;
    setFlowRefreshing(true);
    let r;
    try { r = await window.fleet.flow(instance); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setFlowRefreshing(false);
    setFlowResult(r || { error: "no response from the main process" });
  }), [instance]);
  useEffect(() => {
    setFlowResult(instance ? { loading: true } : null);
    loadFlow();
  }, [loadFlow, instance]);
  // Re-read on the instance's `flow` frames (as root or called) and its `intake` frames.
  useRefetchOn(["flow", "intake"], loadFlow, { instance: instance || "none", repo });

  // The ticket read.
  const [ticketState, setTicketState] = useState({ doc: null, error: null, loading: true });
  const [ticketRefreshing, setTicketRefreshing] = useState(false);
  const loadTicket = useMemo(() => coalesce(async () => {
    if (!(window.fleet && window.fleet.fetchTicket)) return;
    setTicketRefreshing(true);
    let next;
    try { next = { doc: await window.fleet.fetchTicket(repo, number), error: null }; }
    catch (e) { next = { doc: null, error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setTicketRefreshing(false);
    setTicketState(next);
  }), [repo, number]);
  useEffect(() => {
    setTicketState({ doc: null, error: null, loading: true });
    loadTicket();
  }, [loadTicket]);
  // GitHub changed the issue (a comment, a label, a PR): the webhook's `ticket` frame.
  useRefetchOn(["ticket"], loadTicket, { repo, number });

  // The transcript's own loop (`transcriptLoop`, ticket.jsx), separate from
  // the reads above because it is the only thing here that follows a
  // *running* process: the lines live in VictoriaLogs, which the engine does
  // not hold, so while a run is live main holds a VictoriaLogs live tail for
  // it (src/transcript-tail.js) and pushes each line as it lands; no timer.
  // When a run is live is the engine's to say: `session` `started` for this
  // issue starts the loop with the frame's `filename` and `host` (so main
  // skips the "which session?" VictoriaLogs query): one read of the lines so
  // far, then the tail. `session` `ended` ends it through `loop.end()`: the
  // tail drains for a grace period, main reads the run once more, and drops
  // it (no gauge: the engine has said); between runs no tail is open at all.
  // On mount, on ⟳ with no live run known, and on every connect's catch-up the
  // pane reads once with no run known: that read asks `smith_run_active` once,
  // so a run already live when the detail opened, or started while the stream
  // was down, is picked up; a run it finds becomes `run` and the loop starts.
  // The next step's run is a new `session`, which `mergeTranscript` puts in
  // place of the old lines (#947); `flowRunning` rides on the state so the
  // byline can say it is waiting for the next step. `scraping` is the running
  // card's ▾/▸ Transcript fold (#867, #883): off closes the tail; on reads
  // once from the cursor the loop carries (the lines missed while off), onto
  // the lines already on screen, and opens it again. It starts off, and off
  // again on each new ticket (#878): the first read still shows the lines so far.
  const [transcript, setTranscript] = useState(null);
  const [reloads, setReloads] = useState(0);
  const [scraping, setScraping] = useState(false);
  const scrapingNow = useRef(false);
  scrapingNow.current = scraping;
  // The run the loop follows: `{ filename, host }` from `session` `started`
  // (or a gauge read that found one), `{ ..., ended: true }` from its `ended`
  // (the loop's `end()`: its tail drains and is dropped, then no read and no
  // gauge: the gauge is a scraped series that can still answer for a run that
  // just ended),
  // null when unknown (a new ticket; ⟳ after an ended run), which is the one
  // read that asks `smith_run_active`.
  const [run, setRun] = useState(null);
  useEffect(() => setRun(null), [number]);
  useEngineEvents(["session"], ({ data }) => {
    if (data.state === "started" || data.state === "ended") setRun((r) => nextRun(r, data));
  }, { repo, number });
  const loopRef = useRef(null);
  const transcriptAcc = useRef(null);
  const flowRunning = Boolean(instance && flowResult && flowResult.flow && !flowResult.flow.ended);
  const flowRunningNow = useRef(false);
  flowRunningNow.current = flowRunning;
  useEffect(() => setScraping(false), [number]);
  useEffect(() => { transcriptAcc.current = null; setTranscript(null); }, [number, reloads]);
  // One loop per run followed, not per state of it: its `ended` is the same
  // loop's `end()`, so the tail drains rather than being dropped and reread.
  const runFollowed = run ? `${run.filename || ""}\n${run.host || ""}` : null;
  const runNow = useRef(run);
  runNow.current = run;
  useEffect(() => {
    if (!(window.fleet && window.fleet.fetchTranscript)) return undefined;
    const loop = transcriptLoop({
      number, run: runNow.current, readDoc: (n, cursor, r) => window.fleet.fetchTranscript(n, cursor, r),
      tail: fleetTail(window.fleet), acc: transcriptAcc, update: setTranscript, onRun: setRun,
      paused: () => !scrapingNow.current, flowRunning: () => flowRunningNow.current,
    });
    loopRef.current = loop;
    return () => { loop.stop(); loopRef.current = null; };
  }, [number, reloads, runFollowed]);
  const runEnded = Boolean(run && run.ended);
  useEffect(() => {
    const loop = loopRef.current;
    if (runEnded && loop) loop.end();
  }, [runEnded, runFollowed]);
  // The one read of `smith_run_active` on every connect's catch-up (no frames: the catch-up only).
  const snapshotTranscript = useCallback(() => (loopRef.current ? loopRef.current.snapshot() : undefined), []);
  useRefetchOn([], snapshotTranscript, { repo });
  useEffect(() => {
    const loop = loopRef.current;
    if (loop) { if (scraping) loop.resume(); else loop.pause(); }
  }, [scraping]);
  useEffect(() => {
    const loop = loopRef.current;
    if (loop) loop.flow(flowRunning);
  }, [flowRunning]);

  // The same after-a-painted-frame evidence line as the other screens, so
  // AGENT_ARMY_ONCE can log and photograph this screen too.
  useEffect(() => {
    if (!board || (flowResult && flowResult.loading) || ticketState.loading) return undefined;
    if (!issue && (!offBoard || offBoard.loading)) return undefined;
    const state = issue
      ? (flowResult && flowResult.error ? { kind: "error", message: String(flowResult.error) }
        : { kind: "issue", message: `${repo}#${number}: ` + (flowResult && flowResult.flow
          ? `${flowResult.flow.steps.length} step(s), ${flowResult.flow.ended ? "ended" : "running"}` : "no flow") })
      : offBoard.ticket ? { kind: "issue", message: `${repo}#${number}: off the board, ${offBoard.ticket.state || "?"}` }
      : classifyTicket(ticketState);
    const id = requestAnimationFrame(() => setTimeout(() => window.fleet.rendered(state), 50));
    return () => cancelAnimationFrame(id);
  }, [board, flowResult, issue, offBoard, ticketState, repo, number]);

  const refreshAll = useCallback(() => {
    if (onRefreshBoard) onRefreshBoard();
    if (onRefreshNeeds) onRefreshNeeds();
    // ⟳ keeps a live run the stream named (the gauge may not have scraped it
    // yet); an ended or gauge-found-nothing run is asked about again.
    setRun((r) => (r && !r.ended ? r : null));
    setReloads((n) => n + 1);
    return Promise.all([loadTicket(), issue ? loadFlow() : loadOffBoard()]);
  }, [onRefreshBoard, onRefreshNeeds, issue, loadFlow, loadOffBoard, loadTicket]);
  const about = issueAboutOf({ repo, number, issue, offBoard,
    title: ticketState.doc && ticketState.doc.issue && ticketState.doc.issue.title });
  const aboutKey = JSON.stringify(about);
  useEffect(() => { if (onChatTarget) onChatTarget(about); }, [onChatTarget, aboutKey]);
  return <TicketDetail repo={repo} number={number} issue={issue} flowResult={flowResult}
                       offBoard={issue ? null : offBoard} needs={issueNeeds}
                       doc={ticketState.doc} error={ticketState.error} transcript={transcript} intake={intake}
                       scraping={scraping} onScraping={setScraping}
                       onBack={onBack} onRefresh={refreshAll} refreshing={flowRefreshing || ticketRefreshing}
                       onChatAbout={onChatAbout} proposal={proposal} onSelect={onSelect} />;
};

// The app's tabs. Stage 2's ticket detail is the third screen but deliberately
// not a tab: it is reached by drilling into the fleet overview and has Back.
// Stage 3a adds "needs you", whose tab carries its row count as the badge.
// The Issues view is the first one fed by the engine (engine/README.md, "The
// issues board") and what the window opens on; the three below it still read
// the old fleet's sources until they are moved over.
// `branch` is the explorer branch the view's row sits under (explorer.jsx), so
// "<-- chat about this" (#733) can name it as the explorer does.
// A ticket selected in the explorer's tree whose proposal waits on a decision
// resolves to that proposal, as a Needs you row does (#660, #748); any other
// selection is left as it is.
export const resolveSelection = (item, proposalRows, fallbackRepo = null) => {
  if (!item || item.kind !== "ticket") return item;
  const ref = parseTicketRef(item.id, fallbackRepo);
  const row = ref && findProposal(proposalRows || [], proposalIdOf(ref.repo, ref.number));
  return row && row.decision ? { kind: "proposal", id: row.id } : item;
};

/**
 * The explorer's Saved Chats list and the open chat (`view` "saved-chat",
 * `savedId`). The list is read at start, on main's `savedChats:changed`
 * (a save or a delete) and on each catch-up of the event stream, so an
 * "engine unreachable" from a read made while the engine was down clears
 * once it is back (#1055). No frame type names saved chats: `types` is
 * empty, the catch-up only. The open chat is read when opened, and again
 * on the catch-up only when what is shown is an error: a loaded chat stays.
 */
export const useSavedChats = (view, savedId) => {
  const [savedChats, setSavedChats] = useState(null);
  const refreshSavedChats = useCallback(async () => {
    let r;
    try { r = await window.fleet.savedChats(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}`, chats: [] }; }
    setSavedChats(r || { error: "no response from the main process", chats: [] });
  }, []);
  const [savedChat, setSavedChat] = useState(null);
  const loadSavedChat = useCallback(async (id) => {
    setSavedChat({ loading: true });
    let r;
    try { r = await window.fleet.savedChat(id); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setSavedChat(r || { error: "no response from the main process" });
  }, []);
  useEffect(() => {
    if (!(window.fleet && window.fleet.savedChats)) return undefined;
    refreshSavedChats();
    return window.fleet.onSavedChatsChanged ? window.fleet.onSavedChatsChanged(() => refreshSavedChats()) : undefined;
  }, [refreshSavedChats]);
  useEffect(() => {
    if (view === "saved-chat" && savedId && window.fleet && window.fleet.savedChat) loadSavedChat(savedId);
  }, [view, savedId, loadSavedChat]);
  const shown = useRef(null);
  shown.current = { view, savedId, savedChat };
  useRefetchOn([], useCallback(async () => {
    if (window.fleet && window.fleet.savedChats) await refreshSavedChats();
    const s = shown.current;
    if (s.view === "saved-chat" && s.savedId && s.savedChat && s.savedChat.error && window.fleet.savedChat) await loadSavedChat(s.savedId);
  }, [refreshSavedChats, loadSavedChat]));
  return { savedChats, refreshSavedChats, savedChat, loadSavedChat };
};

/**
 * Saved Chats > Claude Code (#1708): the local Claude Code sessions' folders,
 * read at start and on main's `claudeSessions:changed` (its debounced watch
 * of ~/.claude/projects), and the open session (`view` "claude-session",
 * `ccId`), read when opened and again when the folders change.
 */
export const useClaudeSessions = (view, ccId) => {
  const [claudeSessions, setClaudeSessions] = useState(null);
  const [claudeSession, setClaudeSession] = useState(null);
  const [tick, setTick] = useState(0);
  const refresh = useCallback(async () => {
    let r;
    try { r = await window.fleet.claudeSessions(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setClaudeSessions(r || { error: "no response from the main process" });
  }, []);
  useEffect(() => {
    if (!(window.fleet && window.fleet.claudeSessions)) return undefined;
    refresh();
    return window.fleet.onClaudeSessionsChanged ? window.fleet.onClaudeSessionsChanged(() => { refresh(); setTick((t) => t + 1); }) : undefined;
  }, [refresh]);
  useEffect(() => {
    if (!(view === "claude-session" && ccId && window.fleet && window.fleet.claudeSession)) return;
    let live = true;
    if (!tick) setClaudeSession({ loading: true });
    window.fleet.claudeSession(ccId)
      .then((r) => { if (live) setClaudeSession(r || { error: "no response from the main process" }); },
        (e) => { if (live) setClaudeSession({ error: `IPC failed: ${e && e.message ? e.message : e}` }); });
    return () => { live = false; };
  }, [view, ccId, tick]);
  return { claudeSessions, claudeSession };
};

export const VIEWS = [
  { id: "tickets", label: "Tickets", branch: "Tickets" },
  { id: "needs", label: "Needs you", branch: "Tickets" },
  { id: "hitlp-requests", label: "HITLP Requests", branch: "HITLP Requests" },
  { id: "hitlp-closed", label: "Closed", branch: "HITLP Requests" },
  { id: "accounts", label: "AI Accounts", branch: "Config" },
  { id: "repos", label: "Repos", branch: "Config" },
  { id: "jobs", label: "Jobs", branch: "Config" },
  { id: "frakworks", label: "frakworks", branch: "Config" },
  { id: "palettes", label: "Palettes", branch: "Config" },
  { id: "jev", label: "Jev decisions", branch: "Analytics" },
  { id: "doctor", label: "Doctor", branch: "Analytics" },
  { id: "friction", label: "Friction notes", branch: "Analytics" },
  { id: "refactoring", label: "Refactoring", branch: "Analytics" },
  { id: "ssh-verdicts", label: "SSH verdicts", branch: "Analytics" },
  { id: "account-usage", label: "Account balances", branch: "Analytics > AI Accounts" },
  { id: "account-consumption", label: "Account consumption", branch: "Analytics > AI Accounts" },
  { id: "builds", label: "Builds", branch: "Analytics" },
  { id: "hitlp-producer", label: "HITLP producer", branch: "Utils" },
];

// "<-- chat about this" (#733): the First mate header's button starts a new
// conversation about whatever the work area shows. Where the screen has a Chat
// about this of its own (an issue detail, a proposal, a repo file the view
// drew) it hands chatAbout that button's very `about`; any other screen is a
// `{ kind: "screen", label, repo }`, `label` the explorer's path to it.
const crumbs = (...parts) => parts.filter(Boolean).join(" > ");

/**
 * App's `sel` -> the screen's name in the app's words: a VIEWS row under its
 * explorer branch ("Config > Repos"), else the explorer's branch and row for a
 * Grafana dashboard, a saved chat, a proposal or a repo file. `ctx` carries
 * what names the row: `grafana` and `savedChats` (App's lists) and
 * `proposalRow` (the one shown).
 */
export const screenLabel = (sel, ctx = {}) => {
  const s = sel || {};
  const view = VIEWS.find((v) => v.id === s.kind);
  // A branch's own pane (Tickets, HITLP Requests) is named by its heading alone.
  if (view) return view.branch === view.label ? view.label : crumbs(view.branch, view.label);
  if (s.kind === "grafana") {
    const d = ((ctx.grafana && ctx.grafana.dashboards) || [])[Number(s.id)];
    return crumbs("Analytics", d && d.label ? `${d.label} (Grafana)` : "Grafana");
  }
  if (s.kind === "saved-chat") {
    const c = ((ctx.savedChats && ctx.savedChats.chats) || []).find((x) => String(x.id) === String(s.id));
    return crumbs("Saved Chats", c ? c.title : s.id);
  }
  if (s.kind === "proposal") return crumbs("Proposals", ctx.proposalRow ? ctx.proposalRow.title : s.id);
  if (s.kind === "repo-file") return crumbs("Repos", s.id);
  if (s.kind === "ticket") return `ticket ${s.id}`;
  return String(s.kind || "");
};

/** Why the button is off with a ticket that has no repo; "<-- chat about this" says it as its title. */
export const NO_REPO_OFF = "All repos is selected, so this ticket has no repo: pick one in the switcher to chat about it.";

/**
 * What "<-- chat about this" hands chatAbout: `{ about }`, or `{ about: null,
 * off }` with why it cannot. `sel` is App's; `ticket` its ticket `{ repo,
 * number }` and `issueSel` the open issue detail, each with `about` what its
 * screen reported (onChatTarget), if it has yet. `proposalRow` is the proposal
 * shown, `repoFile` the file view's last read `{ id, ref, result }`, and
 * `repo` the switcher's selection (null for All repos).
 */
export const screenChatOf = ({ sel, ticket = null, issueSel = null, about = null, proposalRow = null, repoFile = null,
                               grafana = null, savedChats = null, repo = null }) => {
  const ours = (a, r, n) => a && a.repo === r && Number(a.number) === Number(n) ? a : null;
  if (sel && sel.kind === "ticket") {
    if (!ticket || !ticket.repo) return { about: null, off: NO_REPO_OFF };
    return { about: ours(about, ticket.repo, ticket.number) || { kind: "ticket", repo: ticket.repo, number: ticket.number, title: "" } };
  }
  if (issueSel) {
    if (!issueSel.repo) return { about: null, off: NO_REPO_OFF };
    return { about: ours(about, issueSel.repo, issueSel.number) || issueAboutOf({ ...issueSel, issue: null, offBoard: null }) };
  }
  if (sel && sel.kind === "proposal" && proposalRow && proposalRow.number != null) {
    return { about: { repo: proposalRow.repo, number: proposalRow.number, title: proposalRow.title } };
  }
  if (sel && sel.kind === "repo-file" && repoFile && sel.id && repoFile.id === sel.id && (repoFile.ref || null) === (sel.ref || null)) {
    const p = parseRepoFileId(sel.id);
    if (p && isChattable(p.path, repoFile.result)) return { about: { repo: p.repo, path: p.path, commit: sel.ref || null } };
  }
  return { about: { kind: "screen", label: screenLabel(sel, { grafana, savedChats, proposalRow }), repo: repo || null } };
};

/**
 * `fleet` with its `repoFile` read reported to `onRead({ id, ref, result })`
 * as well as returned: how App learns whether the file view drew the file
 * (isChattable) without the view's own state leaving it.
 */
export const tapRepoFile = (fleet, onRead) => (fleet && fleet.repoFile ? {
  ...fleet,
  repoFile: async (owner, name, filePath, ref) => {
    const result = await fleet.repoFile(owner, name, filePath, ref);
    onRead({ id: repoFileIdOf(`${owner}/${name}`, filePath), ref: ref || null, result });
    return result;
  },
} : fleet);

// First mate (issue #100): the third, singular pane, docked outside the
// `<main className="work">` switch so it renders on every screen — the whole
// point of it. Owns its subscription here; its component (first-mate.jsx)
// draws only.
// Chat about this (#590): the new conversation starts through the same
// firstMate:new as the strip's own button (archiving the current one into
// History), and the kick-starter waits in the renderer for the first send;
// nothing goes to First mate on the click. `about` is a proposal
// `{ repo, number, title }` or, from the repo file view (#683), a file
// `{ repo, path, commit }`, or, from a ticket's detail (#699), a ticket
// `{ kind: "ticket", repo, number, title }`.
export const chatAbout = (about, { firstMate, setKickStarter }) => {
  setKickStarter(kickStarterOf(about));
  return firstMate.startNew();
};

// `kickStarter` is App's pending kick-starter (kickStarterOf) and
// `onKickStarter` sets it: the first send carries it and clears it, and a new
// conversation started here drops it.
// `onChatAboutScreen` and `chatAboutScreenOff` are "<-- chat about this" (#733):
// App's click and, when it cannot, why.
export const FirstMateStrip = ({ onOpenTicket, onOpenFile, selection, kickStarter = null, onKickStarter = () => {},
                                 onChatAboutScreen = null, chatAboutScreenOff = null }) => {
  const [snapshot, setSnapshot] = useState(null);
  // The File card's repo pick, in All repos only (#577).
  const [repoDraft, setRepoDraft] = useState(null);
  const [draft, setDraft] = useState("");
  // Pasted images (#368), `{ media_type, data }` each, waiting for the next
  // send, and the notice a refused paste or send leaves by the input.
  const [attachments, setAttachments] = useState([]);
  const [attachNotice, setAttachNotice] = useState(null);
  const [tierDraft, setTierDraft] = useState(null);
  // The History control (#265): a list panel and a read-only conversation
  // panel, both independent of `draft`/`tierDraft` above — no History
  // handler ever touches those two.
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyList, setHistoryList] = useState(null);
  const [viewingConversation, setViewingConversation] = useState(null);
  // Issue #486: the Resume control's in-flight flag and refusal, cleared
  // whenever History closes or another conversation is opened.
  const [resumeBusy, setResumeBusy] = useState(false);
  const [resumeError, setResumeError] = useState(null);
  // "Save for later" (#598): null, `{ busy }`, `{ error }` or `{ ok, created, at }`.
  const [saveState, setSaveState] = useState(null);
  useEffect(() => {
    if (!(typeof window !== "undefined" && window.firstMate)) return undefined;
    let live = true;
    window.firstMate.list().then((s) => { if (live) setSnapshot(s); }).catch(() => {});
    const offUpdate = window.firstMate.onUpdate((s) => { if (live) setSnapshot(s); });
    const offStream = window.firstMate.onStream(({ text }) => {
      if (!live) return;
      setSnapshot((prev) => prev && { ...prev, streaming: (prev.streaming || "") + text });
    });
    return () => { live = false; if (offUpdate) offUpdate(); if (offStream) offStream(); };
  }, []);
  // The terminal font (#366): read once; until it answers, and whenever it
  // cannot, the strip draws in styles.css's fallback stack.
  const [font, setFont] = useState(null);
  useEffect(() => {
    if (!(typeof window !== "undefined" && window.firstMate && window.firstMate.font)) return undefined;
    let live = true;
    window.firstMate.font().then((f) => { if (live) setFont(f); }).catch(() => {});
    return () => { live = false; };
  }, []);
  const onPasteImages = useCallback(async (files) => {
    const { accept, notice } = admitPaste(attachments.length, files);
    setAttachNotice(notice);
    if (!accept.length) return;
    let read;
    try { read = await Promise.all(accept.map(readImageFile)); }
    catch (e) { setAttachNotice(`Could not read the pasted image: ${e && e.message ? e.message : e}`); return; }
    // Two quick pastes both admitted against the same count: keep the limit.
    setAttachments((prev) => [...prev, ...read].slice(0, IMAGE_MAX_COUNT));
  }, [attachments.length]);
  const onRemoveAttachment = useCallback((i) => {
    setAttachments((prev) => prev.filter((_, j) => j !== i));
    setAttachNotice(null);
  }, []);
  // Draft and images clear as the send goes out; a refused send (busy, or
  // main's own image check) hands both back unless the user has moved on.
  // A pending kick-starter goes out in front of the text, as one message, and
  // clears with the draft; a refused send hands it back too.
  const onSend = useCallback(async (text) => {
    const images = attachments;
    const kick = kickStarter;
    setDraft("");
    setAttachments([]);
    setAttachNotice(null);
    if (kick) onKickStarter(null);
    const r = await window.firstMate.send(withKickStarter(kick, text), images);
    if (r && r.refused) {
      setDraft((d) => d || text);
      setAttachments((a) => (a.length ? a : images));
      if (kick) onKickStarter((k) => k || kick);
      if (!r.busy && r.error) setAttachNotice(`Not sent: ${r.error}.`);
    }
  }, [attachments, kickStarter, onKickStarter]);
  const onFile = useCallback(async (tier, repo) => {
    setTierDraft(tier);
    await window.firstMate.file(tier, repo || null);
  }, []);
  const onSelectConversation = useCallback(async (id) => {
    const c = await window.firstMate.conversation(id);
    setResumeError(null);
    setViewingConversation(c);
  }, []);
  const onCloseHistory = useCallback(() => {
    setHistoryOpen(false);
    setHistoryList(null);
    setViewingConversation(null);
    setResumeError(null);
  }, []);
  // A new conversation leaves History too: a past transcript opened there
  // would otherwise stay on screen over the new, empty one.
  const onNewConversation = useCallback(() => {
    onKickStarter(null);
    onCloseHistory();
    return window.firstMate.startNew();
  }, [onKickStarter, onCloseHistory]);
  // Main archives the live conversation, makes this one live and sends a
  // firstMate:update, so closing History shows the resumed messages.
  const onResume = useCallback(async (id) => {
    setResumeBusy(true);
    setResumeError(null);
    let r;
    try { r = await window.firstMate.resume(id); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setResumeBusy(false);
    if (!r || !r.ok) { setResumeError((r && r.error) || "no response from the main process"); return; }
    onCloseHistory();
  }, [onCloseHistory]);
  // Main PUTs the live conversation to the engine under its saved-chat id, so
  // a second save updates the first; `at` is the history length it saved.
  const onSave = useCallback(async (at) => {
    setSaveState({ busy: true });
    let r;
    try { r = await window.firstMate.save(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setSaveState(r && r.ok ? { ok: true, created: Boolean(r.created), at }
      : { error: (r && r.error) || "no response from the main process" });
  }, []);
  // Issue #601: back to the previous conversation. Main archives the live one
  // and sends a firstMate:update with the resumed messages and its notice; a
  // pending kick-starter belonged to the conversation being left, so it goes,
  // and an open History panel closes onto the live transcript.
  const [popError, setPopError] = useState(null);
  const onPopConversation = useCallback(async () => {
    setPopError(null);
    onKickStarter(null);
    let r;
    try { r = await window.firstMate.pop(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    onCloseHistory();
    if (!r || !r.ok) setPopError((r && r.error) || "no response from the main process");
  }, [onKickStarter, onCloseHistory]);
  // Open in Claude Code ⇗ (#820): null, `{ busy }` or `{ error }`. Main writes
  // the launcher and asks iTerm2; the strip's own row arrives on its update.
  const [itermState, setItermState] = useState(null);
  const onOpenInIterm = useCallback(async () => {
    setItermState({ busy: true });
    let r;
    try { r = await window.firstMate.openInIterm(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setItermState(r && r.ok ? null : { error: (r && r.error) || "no response from the main process" });
  }, []);

  // Stop / Escape interrupts the turn in progress (#815): main kills the child
  // and the snapshot brings the interrupted row; draft and images stay put.
  const busyNow = Boolean(snapshot && snapshot.busy);
  const onStop = useCallback(() => { window.firstMate.cancel().catch(() => {}); }, []);
  useEffect(() => {
    if (!busyNow) return undefined;
    const onKey = (e) => {
      const el = document.activeElement;
      const strip = document.querySelector(".firstMate");
      if (!escapeCancels({ key: e.key, busy: busyNow, defaultPrevented: e.defaultPrevented,
                           inStrip: Boolean(strip && el && strip.contains(el)),
                           onBody: !el || el === document.body })) return;
      e.preventDefault();
      onStop();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busyNow, onStop]);
  // A kick-starter arriving (Chat about this) shows the live conversation it is in.
  useEffect(() => { if (kickStarter) onCloseHistory(); }, [kickStarter, onCloseHistory]);
  return <FirstMate snapshot={snapshot} font={font} onOpenTicket={onOpenTicket} onOpenFile={onOpenFile} draft={draft} onDraft={setDraft}
                     attachments={attachments} attachNotice={attachNotice}
                     onPasteImages={onPasteImages} onRemoveAttachment={onRemoveAttachment}
                     onSend={onSend} onStop={onStop} tierDraft={tierDraft} onTierDraft={setTierDraft}
                     onFile={onFile}
                     selection={selection} repoDraft={repoDraft} onRepoDraft={setRepoDraft}
                     onNewConversation={onNewConversation} onPopConversation={onPopConversation} popError={popError}
                     historyOpen={historyOpen} historyList={historyList} viewingConversation={viewingConversation}
                     onSelectConversation={onSelectConversation} onCloseHistory={onCloseHistory}
                     onResume={onResume} resumeBusy={resumeBusy} resumeError={resumeError}
                     kickStarter={kickStarter} onSave={onSave} saveState={saveState}
                     onChatAboutScreen={onChatAboutScreen} chatAboutScreenOff={chatAboutScreenOff}
                     onOpenInIterm={onOpenInIterm} itermState={itermState}
                     onSetChatRepo={(repo) => window.firstMate.setRepo(repo)} />;
};

// A pasted image File as `{ media_type, data }`: the base64 after the data
// URL's comma, which is all main and the child take.
const readImageFile = (file) => new Promise((resolve, reject) => {
  const reader = new FileReader();
  reader.onload = () => {
    const url = String(reader.result || "");
    resolve({ media_type: file.type, data: url.slice(url.indexOf(",") + 1) });
  };
  reader.onerror = () => reject(reader.error || new Error("unreadable"));
  reader.readAsDataURL(file);
});

// Config > frakworks > First mate model (#240, a drop-down since #616): the standing tier. Reads
// window.firstMate.tierState() when shown and again on every firstMate:update,
// and saves a row with window.firstMate.setTier(<number>). `tiers` is the
// same table the Issues composer uses.
export const FirstMateModelScreen = ({ tiers, children }) => {
  const [state, setState] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  useEffect(() => {
    if (!(typeof window !== "undefined" && window.firstMate && window.firstMate.tierState)) return undefined;
    let live = true;
    const load = () => window.firstMate.tierState().then((s) => { if (live && s) setState(s); }).catch(() => {});
    load();
    const off = window.firstMate.onUpdate(() => load());
    return () => { live = false; if (off) off(); };
  }, []);
  const onSetTier = useCallback(async (n) => {
    setBusy(true); setError(null);
    let r;
    try { r = await window.firstMate.setTier(Number(n)); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    if (r && r.ok) setState(r);
    else setError((r && r.error) || "no response from the main process");
  }, []);
  return <FirstMateModel tiers={tiers} state={state} busy={busy} error={error} onSetTier={onSetTier}>{children}</FirstMateModel>;
};

// Analytics > a Grafana dashboard (#332). Each time one is opened, main probes
// it (window.fleet.grafanaProbe, by index — the renderer never names a URL)
// and the frame is drawn only once Grafana has answered.
export const GrafanaScreen = ({ index, dashboard }) => {
  const [check, setCheck] = useState(null);
  useEffect(() => {
    setCheck(null);
    if (!(dashboard && dashboard.url && window.fleet && window.fleet.grafanaProbe)) return undefined;
    let live = true;
    window.fleet.grafanaProbe(Number(index))
      .then((r) => { if (live) setCheck(r || { ok: false, error: "no response from the main process" }); })
      .catch((e) => { if (live) setCheck({ ok: false, error: `IPC failed: ${e && e.message ? e.message : e}` }); });
    return () => { live = false; };
  }, [index, dashboard && dashboard.url]);
  // The builds strip (#1389): re-read every 15 s and tick each second, only
  // while a dashboard that asks for it is open.
  const wantsBuilds = Boolean(dashboard && dashboard.builds);
  const [builds, setBuilds] = useState(null);
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    setBuilds(null);
    if (!(wantsBuilds && window.fleet && window.fleet.activeBuilds)) return undefined;
    let live = true;
    const read = () => window.fleet.activeBuilds()
      .then((r) => { if (live) setBuilds(r || { error: "no response from the main process" }); })
      .catch((e) => { if (live) setBuilds({ error: `IPC failed: ${e && e.message ? e.message : e}` }); });
    read();
    const poll = setInterval(read, 15000);
    const tick = setInterval(() => setNow(Date.now()), 1000);
    return () => { live = false; clearInterval(poll); clearInterval(tick); };
  }, [index, wantsBuilds]);
  return <GrafanaDashboard dashboard={dashboard} check={check} builds={builds} now={now} />;
};

// The explorer selection survives a restart, best effort: remembered in
// localStorage and read once at startup.
const LAST_SEL_KEY = "explorerSel";
// A selection remembered before #616 or #617 names the old Config > First mate model or
// Intake refiner tier row, both now on the frakworks page.
const OLD_FRAKWORKS_KINDS = ["first-mate-model", "refiner-tier"];
// Engine > Issues and Engine > Work items, both now Tickets > Tickets (#100029).
const OLD_TICKETS_KINDS = ["issues", "work-items"];
export const migrateSel = (sel) => (sel && OLD_FRAKWORKS_KINDS.includes(sel.kind) ? { ...sel, kind: "frakworks" }
  : sel && OLD_TICKETS_KINDS.includes(sel.kind) ? { ...sel, kind: "tickets" } : sel);
const loadSel = () => { try { return migrateSel(JSON.parse(localStorage.getItem(LAST_SEL_KEY)) || null); } catch (e) { return null; } };
const saveSel = (sel) => { try { localStorage.setItem(LAST_SEL_KEY, JSON.stringify(sel)); } catch (e) { /* private window etc. */ } };

const App = () => {
  const [lastSel] = useState(loadSel);
  const [view, setView] = useState(() =>
    lastSel && lastSel.kind === "saved-chat" && lastSel.id ? "saved-chat"
      : lastSel && lastSel.kind === "claude-session" && lastSel.id ? "claude-session"
      : lastSel && lastSel.kind === "proposal" && lastSel.id ? "proposal"
      : lastSel && lastSel.kind === "repo-file" && lastSel.id ? "repo-file"
      : lastSel && VIEWS.some((v) => v.id === lastSel.kind) ? lastSel.kind : "tickets");
  // The repo switcher (#577): main's selection (`repo`, null for All repos),
  // the target repos and their needs counts. Every view but a ticket's own
  // detail follows it; main does the filtering, so here it is only drawn and
  // each view refetched when it moves.
  const [selection, setSelection] = useState(null);
  const [switcherOpen, setSwitcherOpen] = useState(false);
  const [repoNotice, setRepoNotice] = useState(null);
  const selRepo = selection ? selection.repo : null;
  const loadSelection = useCallback(async () => {
    if (!(window.repo && window.repo.get)) return;
    let r = null;
    try { r = await window.repo.get(); } catch (e) { /* the strip keeps its last answer */ }
    if (!r) return;
    setSelection(r);
    if (r.notice) setRepoNotice(r.notice);
  }, []);
  useEffect(() => {
    loadSelection();
    if (!(window.repo && window.repo.onChanged)) return undefined;
    // The change carries the new repo: the strip and the views move on it at
    // once, and the badges (GET /needs, slow) fill in after (#1693).
    const off = window.repo.onChanged((sel) => {
      if (sel && "repo" in sel) setSelection((prev) => ({ ...(prev || {}), repo: sel.repo, title: sel.title, notice: null }));
      loadSelection();
    });
    return () => { if (off) off(); };
  }, [loadSelection]);
  // Opening the dropdown re-reads the repos and their needs counts.
  useEffect(() => { if (switcherOpen) loadSelection(); }, [switcherOpen, loadSelection]);
  const onPickRepo = useCallback(async (repo) => {
    let r;
    try { r = await window.repo.set(repo); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setRepoNotice(r && r.ok ? null : (r && r.error) || "could not switch the repo");
  }, []);
  useEffect(() => {
    if (selection && typeof document !== "undefined") document.title = titleFor(selRepo);
  }, [selection, selRepo]);
  // null = the view; else `{ repo, number }` (#577). AGENT_ARMY_TICKET
  // (`owner/name#n`, or a bare `n` in the selected repo) opens straight onto
  // one ticket. A deep link wins; otherwise the ticket selected when the app
  // last closed, where an old numeric entry is in the selected repo too.
  const [ticket, setTicket] = useState(() =>
    parseTicketRef(typeof window !== "undefined" && window.fleet && window.fleet.startTicket)
      || (lastSel && lastSel.kind === "ticket" ? parseTicketRef(lastSel.id) : null));
  // A ticket named without its repo takes the selection's, once it is known.
  useEffect(() => {
    if (selection && ticket && !ticket.repo && selRepo) setTicket({ ...ticket, repo: selRepo });
  }, [selection, selRepo, ticket]);
  // The issue the Issues board drilled into: { repo, number }, or null.
  const [issueSel, setIssueSel] = useState(() => parseIssueRef(window.fleet && window.fleet.startIssue));
  // A frakworks://ticket/… link opened outside the app (src/app-link.js) opens
  // that ticket's detail: the one that started the app, and each one after.
  useEffect(() => {
    if (!(window.app && window.app.takeLink)) return undefined;
    const open = async () => {
      const linked = await window.app.takeLink();
      if (linked) { setIssueSel(null); setTicket(linked); }
    };
    open();
    const off = window.app.onLink ? window.app.onLink(open) : null;
    return () => { if (off) off(); };
  }, []);
  // A ticket detail is open (#802): from the tree, or from any other list.
  const detailOpen = Boolean((ticket && ticket.repo) || issueSel);
  // The First mate's pending kick-starter (#590), set by a Chat about this: a
  // Pitch view's, or the repo file view's (#683).
  const [kickStarter, setKickStarter] = useState(null);
  const onChatAbout = useCallback((p) => chatAbout(p, { firstMate: window.firstMate, setKickStarter }), []);
  // "<-- chat about this" (#733): what a ticket or issue detail reports it is
  // about, and the file view's last read, for screenChatOf below.
  const [chatTarget, setChatTarget] = useState(null);
  const [repoFileRead, setRepoFileRead] = useState(null);
  const repoFileFleet = useMemo(() => tapRepoFile(typeof window !== "undefined" ? window.fleet : null, setRepoFileRead), []);
  // The issues board: read when shown, then kept by the stream (useBoardRows,
  // below, with the tree): a `flow`, `ticket` or `created` frame's board row
  // is applied in place, so a new issue is inserted from its frame and not
  // from GitHub's list, which lags it; a `ticket` `closed` frame removes the
  // row, and every read is filtered against what a close removed
  // (withoutRemoved), since the same list lags a close; `incident` and a
  // frame with no row re-read it (each read is one GitHub listing per target
  // repo, spent by the engine, so the re-read runs only while a board or a
  // detail is shown).
  const [issues, setIssues] = useState(null);
  const [issuesRefreshing, setIssuesRefreshing] = useState(false);
  // Each read here is `coalesce`d (engine-events.js): one in flight at a time,
  // and a frame's refetch during it runs once more after it, never dropped.
  const refreshIssues = useMemo(() => coalesce(async () => {
    setIssuesRefreshing(true);
    let r;
    try { r = await window.fleet.issues(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}`, issues: [] }; }
    setIssuesRefreshing(false);
    if (r && Array.isArray(r.issues)) r = { ...r, issues: withoutRemoved(r.issues) };
    setIssues(r || { error: "no response from the main process", issues: [] });
  }), []);
  // The engine's tier map (GET /tiers, via main): the "New intake" composer's
  // and First mate's tier rows, and Config > frakworks > Tiers. null while
  // loading; `{ error }` if the engine could not answer. Read again on a
  // `setting` frame for the map, so an edit anywhere shows here.
  const [tiers, setTiers] = useState(null);
  const refreshTiers = useMemo(() => coalesce(async () => {
    if (!(window.fleet && window.fleet.tiers)) return;
    let r;
    try { r = await window.fleet.tiers(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setTiers(r || { error: "no response from the main process" });
  }), []);
  useEffect(() => { refreshTiers(); }, [refreshTiers]);
  useRefetchOn(["setting"], refreshTiers, { where: (d) => d.name === "tiers" });
  // The ticket detail also needs the board when opened from Needs you,
  // where `view` stays "needs" rather than switching to "tickets", or from
  // the Tickets tree (#802).
  const issuesShown = view === "tickets" || Boolean(issueSel) || detailOpen;
  const issuesShownNow = useRef(issuesShown);
  issuesShownNow.current = issuesShown;
  useEffect(() => {
    if (!issuesShown || !(window.fleet && window.fleet.issues)) return undefined;
    refreshIssues();
  }, [refreshIssues, issuesShown]);
  const refreshIssuesShown = useCallback(() => (issuesShownNow.current ? refreshIssues() : undefined), [refreshIssues]);
  // The issues view's evidence line, on the same after-a-painted-frame rule.
  useEffect(() => {
    if (view !== "tickets" || issueSel || issuesRefreshing || !issues) return;
    const state = issues.error
      ? { kind: "error", message: String(issues.error) }
      : { kind: "issues", message: `${(issues.issues || []).length} issue(s)` };
    const id = requestAnimationFrame(() => setTimeout(() => window.fleet.rendered(state), 50));
    return () => cancelAnimationFrame(id);
  }, [issues, issuesRefreshing, view, issueSel]);
  // Config > AI Accounts and Analytics > AI Accounts (#640), from the engine:
  // one read, polled only while either is shown, like issues.
  const accountsShown = view === "accounts" || view === "account-usage";
  const [accounts, setAccounts] = useState(null);
  const [accountsRefreshing, setAccountsRefreshing] = useState(false);
  const refreshAccounts = useMemo(() => coalesce(async () => {
    setAccountsRefreshing(true);
    let r;
    try { r = await window.fleet.accounts(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}`, accounts: [] }; }
    setAccountsRefreshing(false);
    setAccounts(r || { error: "no response from the main process", accounts: [] });
  }), []);
  const accountsShownNow = useRef(accountsShown);
  accountsShownNow.current = accountsShown;
  useEffect(() => {
    if (!accountsShown || !(window.fleet && window.fleet.accounts)) return undefined;
    refreshAccounts();
  }, [refreshAccounts, accountsShown]);
  // An account changed (`setting` `accounts`), or a run's usage landed at collect (`session` `ended`).
  const refreshAccountsShown = useCallback(() => (accountsShownNow.current ? refreshAccounts() : undefined), [refreshAccounts]);
  useRefetchOn(["setting", "session"], refreshAccountsShown,
    { where: (d) => (d.name === "accounts") || (d.state === "ended" && d.runId != null) });
  useEffect(() => {
    if (!accountsShown || accountsRefreshing || !accounts) return;
    const state = accounts.error
      ? { kind: "error", message: String(accounts.error) }
      : { kind: "accounts", message: `${(accounts.accounts || []).length} account(s)` };
    const id = requestAnimationFrame(() => setTimeout(() => window.fleet.rendered(state), 50));
    return () => cancelAnimationFrame(id);
  }, [accounts, accountsRefreshing, accountsShown]);
  // Config > frakworks > Intake refiner tier (#594, #617), from the engine:
  // read when the frakworks page is shown and on `setting` `intake.tier`.
  const [refinerTier, setRefinerTier] = useState(null);
  const [refinerTierRefreshing, setRefinerTierRefreshing] = useState(false);
  const refreshRefinerTier = useMemo(() => coalesce(async () => {
    setRefinerTierRefreshing(true);
    let r;
    try { r = await window.fleet.refinerTier(); }
    catch (e) { r = { refinerTier: null, error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setRefinerTierRefreshing(false);
    setRefinerTier(r || { refinerTier: null, error: "no response from the main process" });
  }), []);
  const viewNow = useRef(view);
  viewNow.current = view;
  useEffect(() => {
    if (view !== "frakworks" || !(window.fleet && window.fleet.refinerTier)) return undefined;
    refreshRefinerTier();
  }, [refreshRefinerTier, view]);
  const refreshRefinerTierShown = useCallback(() => (viewNow.current === "frakworks" ? refreshRefinerTier() : undefined), [refreshRefinerTier]);
  useRefetchOn(["setting"], refreshRefinerTierShown, { where: (d) => d.name === "intake.tier" });
  // Config > frakworks > Tiers' security row (GET /security/tier): read when the
  // page is shown and on its `setting` frame, like the refiner tier.
  const [securityTier, setSecurityTier] = useState(null);
  const refreshSecurityTier = useMemo(() => coalesce(async () => {
    let r;
    try { r = await window.fleet.securityTier(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setSecurityTier(r || { securityTier: null, error: "no response from the main process" });
  }), []);
  useEffect(() => {
    if (view !== "frakworks" || !(window.fleet && window.fleet.securityTier)) return undefined;
    refreshSecurityTier();
  }, [refreshSecurityTier, view]);
  const refreshSecurityTierShown = useCallback(() => (viewNow.current === "frakworks" ? refreshSecurityTier() : undefined), [refreshSecurityTier]);
  useRefetchOn(["setting"], refreshSecurityTierShown, { where: (d) => d.name === "security.tier" });
  // Its post-deployment check row (GET /check/tier), read and refetched the same way.
  const [checkTier, setCheckTier] = useState(null);
  const refreshCheckTier = useMemo(() => coalesce(async () => {
    let r;
    try { r = await window.fleet.checkTier(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setCheckTier(r || { checkTier: null, error: "no response from the main process" });
  }), []);
  useEffect(() => {
    if (view !== "frakworks" || !(window.fleet && window.fleet.checkTier)) return undefined;
    refreshCheckTier();
  }, [refreshCheckTier, view]);
  const refreshCheckTierShown = useCallback(() => (viewNow.current === "frakworks" ? refreshCheckTier() : undefined), [refreshCheckTier]);
  useRefetchOn(["setting"], refreshCheckTierShown, { where: (d) => d.name === "check.tier" });
  // Config > frakworks > Smith hosting (#1348, GET /settings/smith-hosting):
  // read when the page is shown and on its `setting` frame, like the refiner tier.
  const [smithHosting, setSmithHosting] = useState(null);
  const refreshSmithHosting = useMemo(() => coalesce(async () => {
    let r;
    try { r = await window.fleet.smithHosting(); }
    catch (e) { r = { smithHosting: null, error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setSmithHosting(r || { smithHosting: null, error: "no response from the main process" });
  }), []);
  useEffect(() => {
    if (view !== "frakworks" || !(window.fleet && window.fleet.smithHosting)) return undefined;
    refreshSmithHosting();
  }, [refreshSmithHosting, view]);
  const refreshSmithHostingShown = useCallback(() => (viewNow.current === "frakworks" ? refreshSmithHosting() : undefined), [refreshSmithHosting]);
  useRefetchOn(["setting"], refreshSmithHostingShown, { where: (d) => d.name === "smith.hosting" });
  // Config > frakworks > First mate hosting (#2521, GET /settings/first-mate-hosting), read as Smith hosting is.
  const [firstMateHosting, setFirstMateHosting] = useState(null);
  const refreshFirstMateHosting = useMemo(() => coalesce(async () => {
    let r;
    try { r = await window.fleet.firstMateHosting(); }
    catch (e) { r = { firstMateHosting: null, error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setFirstMateHosting(r || { firstMateHosting: null, error: "no response from the main process" });
  }), []);
  useEffect(() => {
    if (view !== "frakworks" || !(window.fleet && window.fleet.firstMateHosting)) return undefined;
    refreshFirstMateHosting();
  }, [refreshFirstMateHosting, view]);
  const refreshFirstMateHostingShown = useCallback(() => (viewNow.current === "frakworks" ? refreshFirstMateHosting() : undefined), [refreshFirstMateHosting]);
  useRefetchOn(["setting"], refreshFirstMateHostingShown, { where: (d) => d.name === "firstmate.hosting" });
  // Config > frakworks > .env (#912), from main, not the engine: checked each
  // time the frakworks page is shown and after a removal, never polled.
  const [envCheck, setEnvCheck] = useState(null);
  const refreshEnvCheck = useCallback(async () => {
    let r;
    try { r = await window.fleet.envCheck(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setEnvCheck(r || { error: "no response from the main process" });
  }, []);
  useEffect(() => {
    if (view !== "frakworks" || !(window.fleet && window.fleet.envCheck)) return undefined;
    refreshEnvCheck();
  }, [refreshEnvCheck, view]);
  // Config > Repos, from the engine: the rows (`GET /repos` with the running
  // count from `GET /issues`), read when shown and on `repo` and `ticket`
  // frames; and each repo's GitHub-side setup, which nothing asks for on its
  // own: the head's **Health check** (#1243) sends one
  // `window.fleet.setupCheck("*")` (forced: each click reads GitHub) and opens
  // the overlay before the answer, and each report arrives as a `setup` frame
  // on the `*` connection the page holds while shown (applySetupFrame). The
  // last report per repo stays for the tile's webhook warning. No timer.
  const [repos, setRepos] = useState(null);
  const [setups, setSetups] = useState({});
  // The open overlay: { repos, setups, down, error } | null; `setups` starts empty at the click.
  const [health, setHealth] = useState(null);
  const [reposRefreshing, setReposRefreshing] = useState(false);
  const refreshRepos = useMemo(() => coalesce(async () => {
    setReposRefreshing(true);
    let r;
    try { r = await window.fleet.repos(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}`, repos: [] }; }
    setReposRefreshing(false);
    setRepos(r || { error: "no response from the main process", repos: [] });
  }), []);
  const startHealthCheck = useCallback(async (names) => {
    setHealth({ repos: names, setups: {}, down: false, error: null });
    let r;
    try { r = await window.fleet.setupCheck("*"); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    const error = !r ? "no response from the main process" : r.error ? String(r.error) : null;
    if (error) setHealth((h) => (h ? { ...h, error } : h));
  }, []);
  useEffect(() => {
    if (view !== "repos" || !(window.fleet && window.fleet.repos)) return undefined;
    refreshRepos();
  }, [refreshRepos, view]);
  useEffect(() => {
    const f = window.fleet;
    if (view !== "repos" || !(f && f.engineSubscribe && f.onEngineStream)) return undefined;
    let live = true;
    // Held for the overlay's frames; it asks nothing. A drop fails every step still unanswered.
    const off = f.onEngineStream((push) => {
      if (live && push && push.repo === "*" && push.state === "down") setHealth((h) => (h && !h.down ? { ...h, down: true } : h));
    });
    Promise.resolve().then(() => f.engineSubscribe("*")).catch(() => { /* the stream's own error */ });
    return () => { live = false; if (typeof off === "function") off(); f.engineUnsubscribe("*"); };
  }, [view]);
  // From the `*` connection only: the selected repo's connection carries the same frames for its repo.
  useEngineEvents(["setup"], ({ data }) => {
    setSetups((m) => applySetupFrame(m, data));
    setHealth((h) => (h ? { ...h, setups: applySetupFrame(h.setups, data) } : h));
  }, { where: (_d, fr) => fr.subscription === "*" });
  const refreshReposShown = useCallback(() => (viewNow.current === "repos" ? refreshRepos() : undefined), [refreshRepos]);
  useRefetchOn(["repo", "ticket"], refreshReposShown);
  const reposResult = useMemo(() => (repos ? {
    ...repos,
    repos: (repos.repos || []).map((r) => ({ ...r, setup: (setups[r.fullName.toLowerCase()] || {}).setup || null })),
  } : null), [repos, setups]);
  useEffect(() => {
    if (view !== "repos" || reposRefreshing || !repos) return;
    const state = repos.error
      ? { kind: "error", message: String(repos.error) }
      : { kind: "repos", message: `${(repos.repos || []).length} repo(s)` };
    const id = requestAnimationFrame(() => setTimeout(() => window.fleet.rendered(state), 50));
    return () => cancelAnimationFrame(id);
  }, [repos, reposRefreshing, view]);
  // Saved First mate chats (#598): the list and the open chat, from
  // useSavedChats above, which also refetches them on the catch-up.
  const [savedId, setSavedId] = useState(() => (lastSel && lastSel.kind === "saved-chat" ? String(lastSel.id || "") : null));
  const { savedChats, refreshSavedChats, savedChat } = useSavedChats(view, savedId);
  const [ccId, setCcId] = useState(() => (lastSel && lastSel.kind === "claude-session" ? String(lastSel.id || "") : null));
  const { claudeSessions, claudeSession } = useClaudeSessions(view, ccId);
  const [tree, setTree] = useState(null);
  const refreshTree = useCallback(async () => {
    let r;
    try { r = await window.fleet.ticketTree(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}`, groups: [] }; }
    if (r && !r.error && Array.isArray(r.issues)) {
      const issues = withoutRemoved(r.issues);
      if (issues !== r.issues) r = { ...r, ...treeOf(issues, r.repo), issues };
    }
    setTree(r || { error: "no response from the main process", groups: [] });
  }, []);
  useEffect(() => {
    if (!(window.fleet && window.fleet.ticketTree)) return undefined;
    refreshTree();
  }, [refreshTree]);
  // The board and the tree re-read on a `ticket` frame with no row (a
  // comment, a pull request, a reopen) and on `incident` (an open or a
  // resolve carries no board row and starts no activity, so no `flow` frame
  // follows it; the grouping puts an issue with an open incident in
  // needs-you). A `flow`, `ticket` or `created` frame's board row is applied
  // in place to both, the tree's grouping re-run, and nothing is re-read for
  // it: the engine read the row by number, while `GET /issues` lags a new
  // issue. A `ticket` `closed` frame drops the row from both, from Needs, and
  // adds it to an opened Done group, again with no read of `GET /issues`,
  // which lags a close as well; useBoardRows remembers the number for
  // REMOVED_KEEP_MS (engine-events.js), and each read here filters against it.
  const refreshBoard = useCallback(() => Promise.all([refreshIssuesShown(), refreshTree()]), [refreshIssuesShown, refreshTree]);
  const issuesNow = useRef(issues); issuesNow.current = issues;
  const treeNow = useRef(tree); treeNow.current = tree;
  // This listener has no repo filter (it must hear a detail's second
  // connection too), so each apply is guarded by the selection: a row of
  // another repo than the selected one is not the board's nor the tree's.
  useBoardRows((row) => {
    setIssues((prev) => {
      if (!prev || prev.error || !Array.isArray(prev.issues)) return prev;
      const issues = applyBoardRow(prev.issues, row, selRepo);
      return issues === prev.issues ? prev : { ...prev, issues };
    });
    setTree((prev) => {
      if (!prev || prev.error || !Array.isArray(prev.issues)) return prev;
      const issues = applyBoardRow(prev.issues, row, prev.repo);
      if (issues === prev.issues) return prev;
      return { ...treeOf(issues, prev.repo), issues };
    });
  }, refreshBoard, (repo, number, frame) => {
    // The row as the board or the tree held it, for the Done group (the frame carries none).
    const same = (i) => i && Number(i.number) === Number(number) && String(i.repo || "").toLowerCase() === String(repo).toLowerCase();
    const held = [issuesNow.current && issuesNow.current.issues, treeNow.current && treeNow.current.issues]
      .map((l) => (Array.isArray(l) ? l.find(same) : null)).find(Boolean);
    const closedAt = (frame && frame.data && frame.data.at) || new Date().toISOString();
    const closed = held ? { repo: held.repo, number: held.number, title: held.title, url: held.url, closedAt, stateReason: null, closedBy: [], parent: null } : null;
    if (closed) rememberRemoved(repo, number, Date.now(), closed);
    setIssues((prev) => {
      if (!prev || prev.error || !Array.isArray(prev.issues)) return prev;
      const issues = dropBoardRow(prev.issues, repo, number, selRepo);
      return issues === prev.issues ? prev : { ...prev, issues };
    });
    setTree((prev) => {
      if (!prev || prev.error || !Array.isArray(prev.issues)) return prev;
      const issues = dropBoardRow(prev.issues, repo, number, prev.repo);
      if (issues === prev.issues) return prev;
      return { ...treeOf(issues, prev.repo), issues };
    });
    // Needs is read for the selected repo only, so a close of another repo matches none of its rows.
    setNeeds((prev) => {
      if (!prev || prev.error || !Array.isArray(prev.rows)) return prev;
      const rows = withoutRemoved(prev.rows, "ticket");
      return rows === prev.rows ? prev : { ...prev, rows };
    });
    if (closed) setDone((d) => (d && !d.loading ? withClosed(d, [closed]) : d));
  });
  // The tree's "Done" group: null until it is first opened, then the last
  // answer (with its `limit`); only asked for again on open, "more" or ⟳.
  const [done, setDone] = useState(null);
  // Only the latest read lands, so a slow answer to an earlier read never replaces a later one.
  const doneSeq = useRef(0);
  const loadDone = useCallback(async (limit) => {
    const seq = ++doneSeq.current;
    setDone((d) => ({ ...(d || { buckets: [] }), loading: true }));
    let r;
    try { r = await window.fleet.doneTickets(limit); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}`, buckets: [] }; }
    if (seq !== doneSeq.current) return;
    // The closed listing may not hold an issue a `ticket` `closed` frame just closed: it is added from the frame's remembered row.
    setDone(r ? withClosed(r, removedClosed(r.repo)) : { error: "no response from the main process", buckets: [] });
  }, []);
  // An opened Done group re-reads on a close, as ⟳ would; the board row above already put the issue in it.
  const doneNow = useRef(done); doneNow.current = done;
  const refreshDoneShown = useCallback(() => {
    const d = doneNow.current;
    return d && !d.loading ? loadDone(d.limit) : undefined;
  }, [loadDone]);
  useRefetchOn(["ticket"], refreshDoneShown, { where: closedIssue });
  // The Proposals branch (#660): the engine's docs on each repo's default
  // branch, which the engine does not observe, so this read keeps a timer
  // (PROPOSALS_REFRESH_MS, 5 min); what the engine does know arrives at once:
  // a proposal flow's `ended` and a `ticket` `pr` re-read it. Merged with the
  // tree's proposal tickets and the decision Needs below.
  const [proposalDocs, setProposalDocs] = useState(null);
  const refreshProposals = useCallback(async () => {
    if (!(window.fleet && window.fleet.proposals)) return;
    let r;
    try { r = await window.fleet.proposals(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}`, docs: [], failures: [] }; }
    setProposalDocs(r || { error: "no response from the main process", docs: [], failures: [] });
  }, []);
  useEffect(() => {
    refreshProposals();
    const t = setInterval(refreshProposals, PROPOSALS_REFRESH_MS);
    return () => clearInterval(t);
  }, [refreshProposals]);
  useRefetchOn(["flow", "ticket"], refreshProposals,
    { where: (d) => (d.kind === "proposal" && (d.state === "ended" || d.state === "cancelled")) || d.action === "pr" });
  // The proposal the view shows: a row id (src/proposal-list.js proposalId).
  const [proposalSel, setProposalSel] = useState(() => (lastSel && lastSel.kind === "proposal" ? String(lastSel.id || "") : null));
  // The Repos branch file the view shows (#674): `owner/name:path` and the tree's commit it was picked at.
  const [repoFileSel, setRepoFileSel] = useState(() => (lastSel && lastSel.kind === "repo-file"
    ? { id: String(lastSel.id || ""), ref: lastSel.ref ? String(lastSel.ref) : null } : null));
  const refreshAll = useCallback(() => {
    refreshTree();
    refreshProposals();
    if (done && !done.loading) loadDone(done.limit);
  }, [refreshTree, refreshProposals, done, loadDone]);
  // Analytics' Grafana dashboards: the config's list, read once; which one is open.
  const [grafana, setGrafana] = useState(null);
  const [grafanaIdx, setGrafanaIdx] = useState(0);
  useEffect(() => {
    if (!(window.fleet && window.fleet.grafanaDashboards)) return;
    window.fleet.grafanaDashboards()
      .then((r) => setGrafana(r || { error: "no response from the main process", dashboards: [] }))
      .catch((e) => setGrafana({ error: `IPC failed: ${e && e.message ? e.message : e}`, dashboards: [] }));
  }, []);
  // The "needs you" view (stage 3a): its own IPC call, read at start for the
  // badge, on `needs` and `ticket` frames (the two GitHub-backed kinds are made
  // of labels, which arrive as `ticket`), and on demand.
  const [needs, setNeeds] = useState(null);
  const [needsRefreshing, setNeedsRefreshing] = useState(false);
  const refreshNeeds = useMemo(() => coalesce(async () => {
    setNeedsRefreshing(true);
    let r;
    try { r = await window.fleet.needsYou(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}`, rows: [] }; }
    setNeedsRefreshing(false);
    // GET /needs' authorize rows come from the same lagging open-issue listing as GET /issues.
    if (r && Array.isArray(r.rows)) r = { ...r, rows: withoutRemoved(r.rows, "ticket") };
    setNeeds(r || { error: "no response from the main process", rows: [] });
  }), []);
  useEffect(() => {
    refreshNeeds();                       // populate the tab's count before first open
  }, [refreshNeeds]);
  useRefetchOn(["needs", "ticket"], refreshNeeds);
  // The HITLP server's tasks, open and decided (src/needs-you.js
  // fetchHitlpTasks), read once for the window: HITLP Requests, Closed, the
  // explorer badge and the urgent popup. A `task` event on the server's
  // GET /events arrives as a `needs` frame (main's pushSurfaceNeeds).
  const [hitlp, setHitlp] = useState(null);
  const [hitlpRefreshing, setHitlpRefreshing] = useState(false);
  const refreshHitlp = useMemo(() => coalesce(async () => {
    if (!(window.fleet && window.fleet.hitlpTasks)) return;
    setHitlpRefreshing(true);
    let r;
    try { r = await window.fleet.hitlpTasks(); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}`, open: [], closed: [] }; }
    setHitlpRefreshing(false);
    setHitlp(r || { error: "no response from the main process", open: [], closed: [] });
  }), []);
  useEffect(() => { refreshHitlp(); }, [refreshHitlp]);
  useRefetchOn(["needs"], refreshHitlp);
  // The GitHub login main resolved (preload login(), read-only), for the
  // "Needs <login>" toggle and the board's "asked by me"; null until it answers.
  const [login, setLogin] = useState(null);
  useEffect(() => {
    let live = true;
    if (window.fleet.login) window.fleet.login().then((l) => { if (live) setLogin(typeof l === "string" && l ? l : null); }, () => {});
    return () => { live = false; };
  }, []);
  // Config > frakworks > Sign in to the engine (#2206): main's `{ signedIn,
  // login, name, error }` (never a token), read when the page is shown and
  // after a sign-in or sign-out; the Needs toggle's login is re-read then too.
  const [signInStatus, setSignInStatus] = useState(null);
  const refreshSignIn = useMemo(() => coalesce(async () => {
    let r;
    try { r = await window.fleet.signInStatus(); }
    catch (e) { r = { signedIn: false, login: null, name: null, error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setSignInStatus(r || { signedIn: false, login: null, name: null, error: "no response from the main process" });
    if (window.fleet.login) window.fleet.login().then((l) => setLogin(typeof l === "string" && l ? l : null), () => {});
  }), []);
  useEffect(() => {
    if (view !== "frakworks" || !(window.fleet && window.fleet.signInStatus)) return undefined;
    refreshSignIn();
  }, [refreshSignIn, view]);
  // Config > frakworks > Judge teach token (#2436): the same shape for the
  // first-mate token the judge's teach buttons send.
  const [teachStatus, setTeachStatus] = useState(null);
  const refreshTeach = useMemo(() => coalesce(async () => {
    let r;
    try { r = await window.fleet.teachTokenStatus(); }
    catch (e) { r = { signedIn: false, login: null, name: null, error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setTeachStatus(r || { signedIn: false, login: null, name: null, error: "no response from the main process" });
  }), []);
  useEffect(() => {
    if (view !== "frakworks" || !(window.fleet && window.fleet.teachTokenStatus)) return undefined;
    refreshTeach();
  }, [refreshTeach, view]);
  // Config > frakworks > HITLP server (#2487): main's `{ signedIn, url, error }`
  // for the surface URL and credential the Needs view's HITLP rows use.
  const [surfaceStatus, setSurfaceStatus] = useState(null);
  const refreshSurface = useMemo(() => coalesce(async () => {
    let r;
    try { r = await window.fleet.hitlpSurfaceStatus(); }
    catch (e) { r = { signedIn: false, url: null, error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setSurfaceStatus(r || { signedIn: false, url: null, error: "no response from the main process" });
  }), []);
  useEffect(() => {
    if (view !== "frakworks" || !(window.fleet && window.fleet.hitlpSurfaceStatus)) return undefined;
    refreshSurface();
  }, [refreshSurface, view]);
  // Which view the window opens on (AGENT_ARMY_VIEW in the main process), so a
  // headless run can prove a view.
  useEffect(() => {
    let live = true;
    window.fleet.initialView().then((v) => {
      const k = migrateSel({ kind: v }).kind;
      if (live && VIEWS.some((x) => x.id === k)) setView(k);
    });
    return () => { live = false; };
  }, []);
  // The needs view's own evidence line, reported after a painted frame.
  useEffect(() => {
    if (view !== "needs" || needsRefreshing || !needs) return;
    const state = needs.error
      ? { kind: "error", message: String(needs.error) }
      : { kind: "needs", message: `${(needs.rows || []).length} row(s) waiting` };
    const id = requestAnimationFrame(() => setTimeout(() => window.fleet.rendered(state), 50));
    return () => cancelAnimationFrame(id);
  }, [needs, needsRefreshing, view]);

  // The inverse entry point (stage 3, #1337): one action on the ticket detail
  // view and one on a "needs you" row opens a pane pinned to that ticket.
  // Main decides *which* pane — reusing one already pinned to it, taking an
  // unpinned one otherwise, and saying so when the two-pane cap leaves none
  // rather than repinning a pane that is busy with another ticket.
  // A new selection refetches every view that follows it (the first answer
  // is what they already loaded with).
  const shownRepo = useRef(undefined);
  useEffect(() => {
    if (!selection) return;
    if (shownRepo.current === undefined || shownRepo.current === selRepo) { shownRepo.current = selRepo; return; }
    shownRepo.current = selRepo;
    setIssueSel(null);
    refreshIssues();
    refreshNeeds();
    refreshTree();
    refreshProposals();
    if (done) loadDone(done.limit);
  }, [selection, selRepo, refreshIssues, refreshNeeds, refreshTree, refreshProposals, done, loadDone]);

  // A HITLP task decided in HITLP Requests is muted there at once
  // (`hitlpSent`) until the server's `task` event refetches the list, and
  // comes back with the error if the server refuses it (`hitlpFailed`).
  const [hitlpSent, setHitlpSent] = useState(() => new Set());
  const [hitlpFailed, setHitlpFailed] = useState({});
  const onHitlpSent = useCallback((id) => {
    setHitlpSent((s) => new Set(s).add(id));
    setHitlpFailed(({ [id]: _, ...f }) => f);
  }, []);
  const onHitlpFailed = useCallback((id, error) => {
    setHitlpSent((s) => { const n = new Set(s); n.delete(id); return n; });
    setHitlpFailed((f) => ({ ...f, [id]: error }));
  }, []);
  // The open tasks not decided here yet: the badge and the popup's feed.
  const hitlpOpen = useMemo(() => ((hitlp && hitlp.open) || []).filter((r) => !hitlpSent.has(r.actionId)), [hitlp, hitlpSent]);
  const needsCount = needsTicketCount(needs && needs.rows);
  const proposalRows = useMemo(() => toProposalRows({ docs: (proposalDocs && proposalDocs.docs) || [],
    board: (tree && tree.proposals) || [], needs: (needs && needs.rows) || [] }), [proposalDocs, tree, needs]);
  const proposals = proposalDocs
    ? { rows: proposalRows, error: proposalDocs.error || null, failures: proposalDocs.failures || [] } : null;
  // What the work area shows, as the explorer names it.
  const sel = ticket != null ? { kind: "ticket", id: ticket.repo ? ticketId(ticket.repo, ticket.number) : String(ticket.number) }
    : view === "grafana" ? { kind: "grafana", id: grafanaIdx }
    : view === "saved-chat" ? { kind: "saved-chat", id: savedId }
    : view === "claude-session" ? { kind: "claude-session", id: ccId }
    : view === "proposal" ? { kind: "proposal", id: proposalSel }
    : view === "repo-file" ? { kind: "repo-file", id: repoFileSel && repoFileSel.id, ref: repoFileSel && repoFileSel.ref }
    : { kind: view };
  const selKey = JSON.stringify(sel);
  useEffect(() => { saveSel(sel); }, [selKey]);
  const screenChat = screenChatOf({ sel, ticket, issueSel, about: chatTarget,
    proposalRow: view === "proposal" ? findProposal(proposalRows, proposalSel) : null,
    repoFile: repoFileRead, grafana, savedChats, repo: selRepo });
  const onChatAboutScreen = screenChat.about ? () => onChatAbout(screenChat.about) : null;
  const onSelect = useCallback((picked) => {
    const item = resolveSelection(picked, proposalRows, selRepo);
    setIssueSel(null);
    if (item.kind === "ticket") { setTicket(parseTicketRef(item.id, selRepo)); return; }
    setTicket(null);
    if (item.kind === "grafana") setGrafanaIdx(Number(item.id));
    if (item.kind === "saved-chat") setSavedId(String(item.id));
    if (item.kind === "claude-session") setCcId(String(item.id));
    if (item.kind === "proposal") setProposalSel(String(item.id));
    if (item.kind === "repo-file") setRepoFileSel({ id: String(item.id), ref: item.ref ? String(item.ref) : null });
    setView(item.kind);
    if (item.kind === "needs") refreshNeeds();
    if (item.kind === "hitlp-requests" || item.kind === "hitlp-closed") refreshHitlp();
  }, [refreshNeeds, refreshHitlp, selRepo, proposalRows]);
  // A ticket link on the Jev decisions page (#553), the Doctor page (#706), the Friction notes page (#1046) and the Refactoring page (#1781) opens the ticket detail,
  // in whichever target repo it is (#577).
  const onJevTicket = useCallback((repo, n) => {
    onSelect({ kind: "ticket", id: repo ? ticketId(repo, n) : Number(n) });
  }, [onSelect]);
  // A Needs you row whose proposal waits on a decision opens the proposal view
  // (#660); every other row opens the issue detail, as before.
  const onNeedsTicket = useCallback((repo, number) => {
    const row = findProposal(proposalRows, proposalIdOf(repo, number));
    if (row && row.decision) { onSelect({ kind: "proposal", id: row.id }); return; }
    setIssueSel({ repo, number: Number(number), from: "needs" });
  }, [proposalRows, onSelect]);
  // A ticket link in HITLP Requests or Closed opens the issue detail; back returns there.
  const onHitlpTicket = useCallback((repo, number) => {
    setIssueSel({ repo, number: Number(number), from: view });
  }, [view]);
  // Explorer and First mate widths are set by the two splitters (#361) and
  // kept per viewer; the work area takes whatever the window has left.
  const [leftW, setLeftW] = useState(() => readWidth(EXPLORER_KEY));
  const [rightW, setRightW] = useState(() => readWidth(FIRST_MATE_KEY));
  const widths = useRef({ left: leftW, right: rightW });
  widths.current = { left: leftW, right: rightW };
  const total = () => (typeof window !== "undefined" ? window.innerWidth : 1100);
  const setSide = (side, w) => {
    if (side === "left") setLeftW(clampWidth(w, total(), widths.current.right));
    else setRightW(clampWidth(w, total(), widths.current.left));
  };
  const persist = () => { saveWidth(EXPLORER_KEY, widths.current.left); saveWidth(FIRST_MATE_KEY, widths.current.right); };
  const splitter = (side) => (
    <Splitter side={side} width={side === "left" ? leftW : rightW}
              onDrag={(start, dx) => setSide(side, dragWidth(start, dx, side))}
              onEnd={persist}
              onReset={() => { setSide(side, DEFAULT_WIDTH); saveWidth(side === "left" ? EXPLORER_KEY : FIRST_MATE_KEY, DEFAULT_WIDTH); }} />
  );
  // The ticket detail (#802), one screen for a ticket however it was opened:
  // a Tickets tree row (`ticket`), or the Issues board, Needs you, a
  // proposal, a saved chat or the First mate (`issueSel`). Only Back differs.
  const detail = (ref, onBack) => (
    <TicketScreen repo={ref.repo} number={ref.number} board={issues} needs={needs} selected={selRepo}
                  intake={(treeEntryOf(tree, ref) || {}).intake || null}
                  onRefreshBoard={refreshIssues} onRefreshNeeds={refreshNeeds} onChatTarget={setChatTarget}
                  onBack={onBack} onChatAbout={onChatAbout}
                  proposal={findProposal(proposalRows, proposalIdOf(ref.repo, ref.number))} onSelect={onSelect} />
  );
  // The desktop's preload gives window.firstMate; the web version (src/web/) does not.
  const hasFirstMate = typeof window !== "undefined" && Boolean(window.firstMate);
  const { preview, openPreview, onCancel: onCancelPreview, onUpdate: onUpdatePreview } = useUpdatePreview();
  const { version, updating, onRestart } = useVersion(openPreview);
  const { engineUpdate, engineResult, engineBusy, onUpdateEngine } = useEngineUpdate(openPreview);
  // The event stream's state, for the bar and the stale line above the work area.
  const stream = useStreamState();
  // An engine update in flight: the overlay, and "restarting" instead of red in the bar and the views.
  const { progress: updateProgress, restarting, onClose: onCloseUpdate } = useUpdateProgress();
  // What the last issues read learned about the engine, for the bar.
  const engine = issues ? { ok: !issues.error, error: issues.error, url: version && version.engineUrl } : { url: version && version.engineUrl };
  return (
    <div className="frame">
    <TitleBar selection={selection} open={switcherOpen} onToggle={setSwitcherOpen} onPick={onPickRepo}
              onManage={() => onSelect({ kind: "repos" })} notice={repoNotice} />
    <div className="shell" style={{ "--explorer-w": `${leftW}px`, "--first-mate-w": `${rightW}px` }}>
      <Explorer sel={sel} onSelect={onSelect} tree={tree} needsCount={needsCount}
                hitlpOpen={hitlp && !hitlp.error ? hitlpOpen.length : null} hitlpClosed={hitlp && !hitlp.error ? (hitlp.closed || []).length : null}
                onRefreshTree={refreshAll} done={done} onLoadDone={loadDone} grafana={grafana}
                savedChats={savedChats} claudeSessions={claudeSessions} proposals={proposals} repos={selection && selection.repos}
                browseRepos={selection ? (selRepo ? [selRepo] : selection.repos) : null} />
      {splitter("left")}
      <main className="work">
        <StaleLine state={stream} restarting={restarting} />
        {ticket != null && ticket.repo
          ? detail(ticket, () => setTicket(null))
          : ticket != null
          ? <div className="fleet-root"><div className={selection ? "err" : "nmEmpty"}>{selection
              ? `#${ticket.number}: All repos is selected, so this ticket has no repo. Pick one in the switcher.`
              : "loading…"}</div></div>
          : issueSel
          ? detail(issueSel, () => { setView(issueSel.from || "tickets"); setIssueSel(null); })
          : view === "grafana"
          ? <GrafanaScreen index={grafanaIdx} dashboard={grafana && (grafana.dashboards || [])[grafanaIdx]} />
          : view === "needs"
          ? <div className="fleet-root"><NeedsYou result={needs || { rows: [], loading: true }}
              onRefresh={refreshNeeds} refreshing={needsRefreshing} restarting={restarting}
              onOpenTicket={onNeedsTicket} /></div>
          : view === "hitlp-requests"
          ? <div className="fleet-root"><HitlpRequests result={hitlp || { loading: true }} onRefresh={refreshHitlp} refreshing={hitlpRefreshing}
              onOpenTicket={onHitlpTicket} sent={hitlpSent} failed={hitlpFailed} onSent={onHitlpSent} onFailed={onHitlpFailed} onDecided={refreshHitlp} /></div>
          : view === "hitlp-closed"
          ? <div className="fleet-root"><HitlpClosed result={hitlp || { loading: true }} onRefresh={refreshHitlp} refreshing={hitlpRefreshing}
              onOpenTicket={onHitlpTicket} /></div>
          : view === "accounts"
          ? <div className="fleet-root"><Accounts result={accounts || { accounts: [], loading: true }}
              onRefresh={refreshAccounts} refreshing={accountsRefreshing}
              onAction={(a) => window.fleet.accountAction(a)} showUsage={false} /></div>
          : view === "account-usage"
          ? <div className="fleet-root"><Accounts result={accounts || { accounts: [], loading: true }}
              onRefresh={refreshAccounts} refreshing={accountsRefreshing}
              onRefreshUsage={(a) => window.fleet.accountAction(a)} /></div>
          : view === "account-consumption"
          ? <Consumption />
          : view === "repos"
          ? <div className="fleet-root"><Repos result={reposResult || { repos: [], loading: true }}
              onRefresh={refreshRepos} refreshing={reposRefreshing}
              onAction={(a) => window.fleet.repoAction(a)}
              onLoadEngineAccess={() => window.fleet.engineAccess()} onEngineAccessAction={(a) => window.fleet.engineAccessAction(a)}
              onLoadDoctor={(since) => Promise.all([window.fleet.doctorRuns(since), window.fleet.doctorBugs(since)])
                .then(([runs, bugs]) => ({ runs, bugs }))}
              onHealthCheck={startHealthCheck} checking={!!health} />
              <HealthCheckOverlay check={health} onAction={(a) => window.fleet.repoAction(a)} onClose={() => setHealth(null)} /></div>
          : view === "palettes"
          ? <div className="fleet-root"><PalettesView /></div>
          : view === "jobs"
          ? <Jobs key={selRepo || ""} repo={selRepo} />
          : view === "frakworks"
          ? <div className="fleet-root"><FirstMateModelScreen tiers={tiers}>
              <SignInPanel result={signInStatus || { loading: true }} onRefresh={refreshSignIn} web={window.fleet.platform === "web"}
                onSignIn={(token) => window.fleet.signIn(token)} onSignOut={() => window.fleet.signOut()} />
              <RefinerTierPanel result={refinerTier || { loading: true }}
                onRefresh={refreshRefinerTier}
                tiers={tiers} onAction={(a) => window.fleet.accountAction(a)} />
              {window.fleet.platform !== "web" && window.fleet.engineUrlSetting && <EngineUrlPanel onLoad={() => window.fleet.engineUrlSetting()}
                onSave={(url) => window.fleet.setEngineUrlSetting(url)} />}
              {window.fleet.platform !== "web" && window.fleet.teachTokenStatus && <SignInPanel result={teachStatus || { loading: true }} onRefresh={refreshTeach}
                title="Judge teach token" hint={TEACH_TOKEN_HINT} action="Save" signedInText="Set for"
                onSignIn={(token) => window.fleet.teachTokenSet(token)} onSignOut={() => window.fleet.teachTokenClear()} />}
              {window.fleet.hitlpSurfaceStatus && <SignInPanel result={surfaceStatus || { loading: true }} onRefresh={refreshSurface} web={window.fleet.platform === "web"}
                title="HITLP server" hint={window.fleet.platform === "web" ? SURFACE_HINT_WEB : SURFACE_HINT} action="Save" signedInText="Deciding on" urlField="https://…/surface/v1" placeholder="surface credential"
                onSignIn={(credential, url) => window.fleet.hitlpSurfaceSet(url, credential)} onSignOut={() => window.fleet.hitlpSurfaceClear()} />}
              <SmithHostingPanel result={smithHosting || { loading: true }} onRefresh={refreshSmithHosting}
                onAction={(a) => window.fleet.accountAction(a)} />
              {window.fleet.firstMateHosting && <FirstMateHostingPanel result={firstMateHosting || { loading: true }} onRefresh={refreshFirstMateHosting}
                onAction={(a) => window.fleet.accountAction(a)} />}
              <RepoNamePanel />
              {window.fleet.platform !== "web" && window.fleet.screenshotSetting && <ScreenshotSettingPanel onLoad={() => window.fleet.screenshotSetting()}
                onSave={(on) => window.fleet.setScreenshotSetting(on)} />}
              <FrakpanelTilesPanel onLoad={() => window.fleet.frakpanelTiles()}
                onAction={(a) => window.fleet.accountAction(a)} />
              <TiersPanel tiers={tiers} security={securityTier} check={checkTier}
                onRefresh={() => { refreshTiers(); refreshSecurityTier(); refreshCheckTier(); }}
                onAction={(a) => window.fleet.accountAction(a)} />
              <EnvLinesPanel result={envCheck || { loading: true }} onRefresh={refreshEnvCheck}
                onRemove={(keys) => window.fleet.envRemove(keys)} />
            </FirstMateModelScreen></div>
          : view === "claude-session"
          ? <ClaudeCodeSession key={ccId} result={claudeSession} onHandoff={(id) => window.fleet.claudeSessionHandoff(id)} />
          : view === "saved-chat"
          ? <SavedChat key={savedId} result={savedChat} onOpenTicket={(repo, number) => { setTicket(null); setIssueSel({ repo, number: Number(number) }); }}
              onRestore={(id) => window.firstMate.restoreSaved(id)}
              onHandoff={(id) => window.fleet.savedChatHandoff(id)}
              onDelete={async (id) => {
                const r = await window.fleet.deleteSavedChat(id);
                if (r && r.ok) { setSavedId(null); setView("tickets"); }
                return r;
              }} />
          : view === "proposal"
          ? <ProposalView row={findProposal(proposalRows, proposalSel)} loading={!proposalDocs || !tree || !needs}
              onSelect={onSelect} onChatAbout={onChatAbout}
              onOpenIssue={(repo, number) => setIssueSel({ repo, number: Number(number), from: "proposal" })} />
          : view === "repo-file"
          ? (repoFileSel && repoFileSel.id
            ? <RepoFileView key={`${repoFileSel.id}@${repoFileSel.ref || ""}`} id={repoFileSel.id} commit={repoFileSel.ref}
                             onChatAbout={onChatAbout} fleet={repoFileFleet} />
            : <div className="fleet-root"><div className="nmEmpty">Pick a file under Repos.</div></div>)
          : view === "jev"
          ? <JevDecisions onOpenTicket={onJevTicket} />
          : view === "doctor"
          ? <DoctorRuns onOpenTicket={onJevTicket} />
          : view === "friction"
          ? <FrictionNotes onOpenTicket={onJevTicket} />
          : view === "builds"
          ? <Builds key={selRepo || ""} repo={selRepo} />
          : view === "hitlp-producer"
          ? <HitlpProducer repo={selRepo} />
          : view === "ssh-verdicts"
          ? <SshVerdicts key={selRepo || ""} repo={selRepo} />
          : view === "refactoring"
          ? <RefactorRunsPage key={selRepo || ""} repo={selRepo} repos={selection && selection.repos} onOpenTicket={onJevTicket} />
          : view === "tickets"
          ? <div className="fleet-root"><Tickets key={selRepo || ""} result={issues || { issues: [], loading: true }}
              needs={needs && needs.rows} onOpenTicket={(n, repo) => setIssueSel({ repo, number: Number(n) })}
              restarting={restarting} login={login}
              tiers={tiers}
              target={selRepo} repos={selection && selection.repos}
              onSubmitIntake={(ask, repo) => window.fleet.startIntake(ask, repo)}
              onIntakeEvents={(cb) => window.fleet.onEngineEvent(cb)}
              onHoldRepo={(repo) => { window.fleet.engineSubscribe(repo); return () => window.fleet.engineUnsubscribe(repo); }} /></div>
          : null}
      </main>
      {/* First mate is a Claude session on the desktop's machine: the web version has no window.firstMate and no strip. */}
      {hasFirstMate && splitter("right")}
      {hasFirstMate && <FirstMateStrip selection={selection} kickStarter={kickStarter} onKickStarter={setKickStarter}
                      onOpenFile={(k) => onSelect({ kind: "repo-file", id: repoFileIdOf(k.repo, k.path), ref: k.commit })}
                      onChatAboutScreen={onChatAboutScreen} chatAboutScreenOff={screenChat.off || null}
                      onOpenTicket={(repo, number) => { setView("tickets"); setTicket(null); setIssueSel({ repo, number: Number(number) }); }} />}
    </div>
    <StatusBar version={version} engine={engine} updating={updating} onRestart={onRestart} stream={stream} restarting={restarting}
               engineUpdate={engineUpdate} engineResult={engineResult} engineBusy={engineBusy} onUpdateEngine={onUpdateEngine} />
    {/* An urgent HITLP task pops up over whichever view is shown (#2196), from the shared HITLP task list. */}
    <HitlpPopup rows={hitlpOpen} login={login} onDecided={refreshHitlp} />
    <UpdatePreview preview={preview && preview.model} snapshot={preview && preview.snapshot} onUpdate={onUpdatePreview} onCancel={onCancelPreview}
      onOpenTicket={(repo, number) => { onCancelPreview(); setView("tickets"); setTicket(null); setIssueSel({ repo, number: Number(number) }); }} />
    <UpdateOverlay progress={updateProgress} onClose={onCloseUpdate}
      onOpenTicket={(repo, number) => { setView("tickets"); setTicket(null); setIssueSel({ repo, number: Number(number) }); }} />
    </div>
  );
};

// The renderer's uncaught errors go to main's error log (#985,
// <userData>/errors.log) through window.fleet.reportError.
if (typeof window !== "undefined" && window.fleet && window.fleet.reportError) {
  window.addEventListener("error", (ev) => {
    const err = ev.error;
    window.fleet.reportError({ message: (err && err.message) || ev.message || "error",
      stack: err && err.stack, context: ev.filename ? `${ev.filename}:${ev.lineno}:${ev.colno}` : "error" });
  });
  window.addEventListener("unhandledrejection", (ev) => {
    const r = ev.reason;
    window.fleet.reportError({ message: r && r.message != null ? r.message : String(r),
      stack: r && r.stack, context: "unhandledrejection" });
  });
}

// The window's app: the desktop mounts it on #root at load; the web version
// (src/web/index.jsx) mounts it itself once the page is signed in.
export const mount = (el) => {
  applyPalette(loadPaletteId());
  createRoot(el).render(<App />);
};

if (typeof document !== "undefined" && document.getElementById("root")) mount(document.getElementById("root"));
