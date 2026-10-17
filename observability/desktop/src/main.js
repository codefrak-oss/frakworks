// main.js — Electron main process for the frakworks desktop app. One window,
// the IPC handlers test/navigation.test.js lists ("ticket:fetch" one ticket's detail document, the
// engine's reads and writes, First mate's channels), and the engine's event
// stream (src/engine-events.js), held here and pushed to the window as
// "engine:event" and "engine:stream".
//
// Environment:
//   TICKET_DATA_TIMEOUT_SECS per-request timeout for the detail view's
//                           VictoriaLogs and `gh` calls (default 10)
//   AGENT_ARMY_ONCE=1       headless proof mode: log what the first render drew,
//                           write AGENT_ARMY_SCREENSHOT if set, then quit
//   AGENT_ARMY_ISSUE        <owner/repo>#<n>: open the Issues board's detail of
//                           that issue (the engine's view of its flow)
//   AGENT_ARMY_VIEW         which tab the window opens on: a view's id (VIEWS in
//                           src/renderer/index.jsx), e.g. needs; unset, the
//                           last one shown, or issues
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { app, BrowserWindow, WebContentsView, Menu, ipcMain, shell, safeStorage, dialog, clipboard } = require("electron");

// The app's icon, also electron-builder's app icon.
const APP_ICON = path.join(__dirname, "..", "build", "icon.png");
// The packaged app was "Agent Army" before it was "frakworks"; keep its
// userData (config, First mate) where it already is.
if (app.getName() === "frakworks") {
  app.setPath("userData", path.join(app.getPath("appData"), "Agent Army"));
}

// The persisted error log (#985, src/error-log.js): <userData>/errors.log,
// error level only, the last 72 entries. The uncaught-exception handler only
// records. It listens on "uncaughtExceptionMonitor", not "uncaughtException":
// a monitor observes without changing behaviour, whereas any
// "uncaughtException" listener makes Node treat the exception as handled and
// Electron then shows no dialog and does not exit. So a crash stays a crash.
const { createErrorLog } = require("./error-log");
const errorLog = createErrorLog({ file: path.join(app.getPath("userData"), "errors.log") });
process.on("uncaughtExceptionMonitor", (err) => {
  errorLog.append({ source: "main", error: err, context: "uncaughtException" });
});
process.on("unhandledRejection", (reason) => {
  errorLog.append({ source: "main", error: reason, context: "unhandledRejection" });
});
app.on("child-process-gone", (_e, d) => {
  errorLog.append({ source: "main", message: `child process gone (${d.type}${d.name ? ` ${d.name}` : ""}): ${d.reason}, exit code ${d.exitCode}`,
    context: "child-process-gone" });
});
const watchRenderer = (contents, what) => contents.on("render-process-gone", (_e, d) => {
  errorLog.append({ source: "main", message: `${what} renderer gone: ${d.reason}, exit code ${d.exitCode}`, context: "render-process-gone" });
});
ipcMain.on("log:error", (_e, entry) => {
  const x = entry && typeof entry === "object" ? entry : { message: entry };
  errorLog.append({ source: "renderer", message: String(x.message == null ? "" : x.message).slice(0, 4000),
    stack: x.stack == null ? null : String(x.stack).slice(0, 16000), context: x.context == null ? null : String(x.context).slice(0, 200) });
});
const { fetchTicketData } = require("./ticket-data");
const { fetchTranscript, fetchDoctorTranscript, SINCE_RE } = require("./transcript-data");
const { openTail } = require("./transcript-tail");
const { callHitlp } = require("./hitlp-client");
const { fetchNeeds, fetchHitlpTasks, fetchHitlpTask } = require("./needs-you");
const { createHitlpSurface, FILE: HITLP_SURFACE_FILE } = require("./hitlp-surface");
const repoSelection = require("./repo-selection");
const { fetchTicketTree, fetchDoneTickets } = require("./ticket-tree");
const { trustSystemCAs, fetchIssues, fetchFlow, fetchAccounts, fetchTierAccounts, pickAccount, fetchRefinerTier, fetchSmithHosting, fetchFirstMateHosting, fetchFrakpanelTiles, fetchSecurityTier, fetchCheckTier, accountAction, engineUrl, fetchProcessInstances, fetchProcessTree, fetchProcessXml, retryIncident, cancelFlow, fetchIncidentFixes, fetchDoctorHistory, fetchDoctorRuns, fetchDoctorBugs, fetchConsumption, fetchCiRuns, startIncidentFix, startIncidentPropose, teachJudge, uploadAttachment, submitIntake, fetchTicketTimeline, summarizeTicket, predictReview, fetchTicketDetail, fetchChanges, fetchTicketPitch, fetchDesignDeck, fetchProposals, fetchProposalPage, fetchRepoTree, fetchRepoFile, fetchJevCalls, fetchJevCall, fetchJevSummary, fetchSshVerdicts, fetchFrictionSummary, fetchFrictionNotes, fetchFrictionGroups, frictionPullOut, frictionNotTwoThings, frictionMerge, frictionFix, fetchRepoRows, requestSetupCheck, repoAction, fetchEngineAccess, engineAccessAction, fetchRepoNames, fetchSavedChats, fetchSavedChat, saveChat, deleteSavedChat, fetchJobs, registerJob, executeJob, deleteJob, fetchRefactorConfig, saveRefactorConfig, fetchJobRuns, fetchActiveBuilds, fetchRepoMcpServers, saveRepoMcpServer, deleteRepoMcpServer, testRepoMcpServer, fetchRepoMcpConfig, fetchLineage } = require("./engine-data");
const { probeStdio } = require("./mcp-stdio-probe");
const { savedChatView, resumability } = require("./saved-chats");
const claudeCode = require("./claude-code-sessions");
const { handoffPlan } = require("./first-mate-handoff");
const { loadTiers } = require("./engine-tiers");
const { resolveClaudeBin } = require("./claude-bin");
const appVersion = require("./app-version");
const appUpdate = require("./app-update");
const engineUpdate = require("./engine-update");
const updatePreview = require("./update-preview");
// An https ENGINE_URL (an engine on a cluster) is signed by a CA the system trusts, not Node's bundled list.
try { if (!trustSystemCAs()) console.log("[engine] this Node cannot add the system's CAs; an https ENGINE_URL needs a public CA"); }
catch (e) { console.log(`[engine] system CAs not added: ${(e && e.message) || e}`); }
const updateProgress = require("./engine-update-progress");
const updateChanges = require("./engine-update-changes");
const envLines = require("./env-lines");
const { resolveDockerBin } = require("./docker-bin");
const { FirstMateManager, APP_REPO } = require("./first-mate");
const { EngineEventStream, FLEET } = require("./engine-events");
const { createEngineToken, TOKEN_FILE } = require("./engine-token");
const { setBearer, fetchWhoAmI, authHeaders, getJson, sendJson, SIGN_IN_PROMPT } = require("./engine-data");
const { viewList, viewRead, viewImport, replyToWorkItem } = require("./work-items");
const { createFreshCode, savedChatDir } = require("./fresh-code");
const { readItermFont } = require("./iterm-font");
const { detectIterm, HANDOFF_DIR_NAME } = require("./first-mate-iterm");
const { configPath, readConfigFile } = require("./answer-config");
const { loadDeployment } = require("./deployment-config");
const { loadGrafana, pageToLoad, probe, isExternalUrl } = require("./grafana-config");
const { appMenuTemplate } = require("./app-menu");
const { parseAppLink } = require("./app-link");

const ONCE = process.env.AGENT_ARMY_ONCE === "1";
// The frakworks checkout First mate may read at its absolute path (the user's
// branch and uncommitted changes; #960), and the legacy cwd of a conversation
// from before fresh code: the checkout this file sits in, three levels up from
// src/. A packaged build (src/app-update.js) has no checkout around it, so it
// is AGENT_ARMY_REPO_DIR (the user's frakworks checkout) or, unset, their home
// directory. First mate runs in the fresh clone of its repo (FRESH_DIR), not here.
const REPO_DIR = app.isPackaged
  ? (process.env.AGENT_ARMY_REPO_DIR || app.getPath("home"))
  : path.resolve(__dirname, "..", "..", "..");
// The checkout the prompt names, or null when the packaged build has none.
const CHECKOUT_DIR = app.isPackaged && !process.env.AGENT_ARMY_REPO_DIR ? null : REPO_DIR;
// First mate's fresh clones (#960, src/fresh-code.js): one detached clone per
// target repo, `<owner>/<name>/`, and `fresh.json`, under userData.
const FRESH_DIR = path.join(app.getPath("userData"), "first-mate", "fresh");
// The `claude` those children are spawned as (src/claude-bin.js): the
// operator's AGENT_ARMY_CLAUDE_BIN, else the first one on this process's PATH,
// the login shell's PATH (which a Finder/Dock launch does not inherit) or the
// usual install dirs. Resolved once, here, so a widened PATH is in place
// before First mate's child is started.
const CLAUDE_BIN = resolveClaudeBin();
console.log(`[claude] ${CLAUDE_BIN.command} (${CLAUDE_BIN.source})`);

// ---------------------------------------------------------------------------
// The repo switcher (#577): one target repo
// (`owner/name`) or All repos (null). Every view but a ticket's own detail
// reads through the selection held here; src/repo-selection.js persists it in
// userData and computes the first default. "repo:changed" tells the window.
// ---------------------------------------------------------------------------
let selectedRepo = null;
let selectionNotice = null;
let selectionReady = null;
let selectionWin = null;
const selectionFile = () => path.join(app.getPath("userData"), repoSelection.FILE_NAME);
const ensureSelection = () => (selectionReady ||= (async () => {
  const r = await fetchRepoNames();
  const persisted = repoSelection.load(selectionFile());
  const res = repoSelection.resolveSelection({ persisted, repos: r.repos });
  selectedRepo = res.repo;
  selectionNotice = res.notice;
  console.log(`[repo] selection: ${repoSelection.titleFor(selectedRepo)}` +
    `${res.fresh ? " (first default)" : ""}${res.notice ? ` — ${res.notice}` : ""}${r.error ? ` (/repos: ${r.error})` : ""}`);
  showSelection();
  engineEvents.setSelected(selectedRepo);
})());
const currentRepo = async () => { await ensureSelection(); return selectedRepo; };
const showSelection = () => {
  if (selectionWin && !selectionWin.isDestroyed()) {
    selectionWin.setTitle(repoSelection.titleFor(selectedRepo));
  }
};
// A repo a pin or a ticket read names, checked against GET /repos:
// `{ repo }` or `{ error }`.
const targetRepo = async (repo) => {
  const c = repoSelection.checkChoice(repo, (await fetchRepoNames()).repos);
  if (c.error) return c;
  return c.repo ? c : { error: "a ticket names a repo" };
};

// The selection, the target repos and each one's needs count (the dropdown's
// badges), and a notice once when a persisted repo was dropped.
ipcMain.handle("repo:get", async () => {
  await ensureSelection();
  const r = await fetchRepoNames();
  const notice = selectionNotice;
  selectionNotice = null;
  return { repo: selectedRepo, title: repoSelection.titleFor(selectedRepo),
    repos: r.repos || [], needs: r.needs, error: r.error || null, notice };
});
// A new selection: a target repo or null (All repos). Anything GET /repos
// does not list is refused and changes nothing.
ipcMain.handle("repo:set", async (_e, repo) => {
  await ensureSelection();
  // Only the list is checked: /needs is slow and its badges are not wanted here (#1693).
  const c = repoSelection.checkChoice(repo, (await fetchRepoNames({ needs: false })).repos);
  if (c.error) {
    console.log(`[repo] set ${JSON.stringify(repo)}: refused — ${c.error}`);
    return { ok: false, error: c.error };
  }
  selectedRepo = c.repo;
  try { repoSelection.save(selectionFile(), selectedRepo); }
  catch (e) { console.log(`[repo] could not save the selection: ${e.message}`); errorLog.append({ source: "main", error: e, context: "[repo]" }); }
  console.log(`[repo] set: ${repoSelection.titleFor(selectedRepo)}`);
  showSelection();
  const title = repoSelection.titleFor(selectedRepo);
  if (selectionWin && !selectionWin.isDestroyed()) {
    selectionWin.webContents.send("repo:changed", { repo: selectedRepo, title });
  }
  // The stream follows the selection: its subscription is reopened on the new
  // repo (`*` in All repos) after the window has heard of the change.
  engineEvents.setSelected(selectedRepo);
  return { ok: true, repo: selectedRepo, title };
});

// ---------------------------------------------------------------------------
// The engine's event stream (src/engine-events.js): one connection for the selected repo, one more per
// repo a ticket detail is open on, each frame pushed to every window as
// "engine:event" and the connection's state as "engine:stream". Four invokes:
// the renderer's "I have snapshotted" after a `catching-up` (the renderer's
// catch-up, src/renderer/engine-events.js, runs every mounted view's refetch
// and answers when the last has returned), the current state for a window
// opened later, and a detail's subscribe/unsubscribe by owner/name.
// ---------------------------------------------------------------------------
const engineEvents = new EngineEventStream();

// Sign in to the engine (#2206):
// the one bearer token, kept safeStorage-encrypted under userData by
// src/engine-token.js. `load()` runs once the app is ready (safeStorage needs
// it); from then on every engine call in src/engine-data.js and the event
// stream carry `Authorization: Bearer` while signed in. The token never
// reaches the renderer or a log line: the three channels below answer the
// login and the token's name only.
const engineToken = createEngineToken({
  safeStorage,
  file: path.join(app.getPath("userData"), TOKEN_FILE),
  whoami: (token) => fetchWhoAmI(token),
  log: (line) => console.log(line),
});
setBearer(() => engineToken.bearer());
ipcMain.handle("engine:sign-in", async (_e, token) => {
  const r = await engineToken.signIn(typeof token === "string" ? token : "");
  if (!r.error && firstMate) firstMate.invalidateMcpConfig();
  desktopChannel.restart();
  return r;
});
ipcMain.handle("engine:sign-out", () => {
  const r = engineToken.signOut();
  if (firstMate) firstMate.invalidateMcpConfig();
  desktopChannel.restart();
  return r;
});
ipcMain.handle("engine:sign-in-status", () => engineToken.status());

// "Let First mate screenshot this app" (#100105, src/screenshot.js): off until
// the user turns it on, persisted under userData. While signed in the desktop
// holds its own channel to the engine (src/desktop-channel.js), over which the
// engine asks for a capture when First mate calls screenshot_desktop; the
// capture is the focused window's (else the first), whole or one pane.
const screenshot = require("./screenshot");
const { DesktopChannel } = require("./desktop-channel");
const screenshotFile = () => path.join(app.getPath("userData"), screenshot.FILE_NAME);
let screenshotAllowed = false;
async function capturePane(pane) {
  const win = BrowserWindow.getFocusedWindow() || BrowserWindow.getAllWindows().find((w) => !w.isDestroyed());
  if (!win) return { error: "no app window is open" };
  let rect = null;
  if (pane) {
    const { bounds, view } = await win.webContents.executeJavaScript(screenshot.boundsScript());
    const r = screenshot.paneRect(pane, bounds, view);
    if (r.error) return r;
    rect = r.rect;
  }
  const img = rect ? await win.webContents.capturePage(rect) : await win.webContents.capturePage();
  return screenshot.encode({ png: () => img.toPNG(), jpeg: (q) => img.toJPEG(q) });
}
const desktopChannel = new DesktopChannel({
  engineUrl: () => engineUrl(), headers: () => authHeaders(), allowed: () => screenshotAllowed, capture: capturePane,
});
ipcMain.handle("screenshot:setting", () => ({ allowed: screenshotAllowed }));
ipcMain.handle("screenshot:set-setting", (_e, allowed) => {
  screenshotAllowed = screenshot.save(screenshotFile(), allowed === true);
  console.log(`[screenshot] ${screenshot.SETTING_LABEL}: ${screenshotAllowed ? "on" : "off"}`);
  desktopChannel.report({ allowed: screenshotAllowed });
  return { allowed: screenshotAllowed };
});

// Config > frakworks > Judge teach token (#2436): a second engine token, of
// purpose `first-mate`, kept the same way in its own file. Only the judge's
// Allow and teach / Deny and teach (`POST /judge/teach`) send it; the engine
// refuses any other token there, so the sign-in token cannot stand in.
const TEACH_TOKEN_FILE = "judge-teach-token.bin";
const teachToken = createEngineToken({
  safeStorage,
  file: path.join(app.getPath("userData"), TEACH_TOKEN_FILE),
  whoami: (token) => fetchWhoAmI(token),
  log: (line) => console.log(line.replace("[engine] sign in", "[engine] judge teach token")),
});
// First mate in a session pod (#100103): the session API is driven with the
// sign-in bearer, the same `Authorization` value every other engine call
// carries. The desktop no longer mints or keeps a `first-mate` purpose token
// of its own; a `first-mate-token.bin` left by an earlier version is deleted
// at launch, never read.
const firstMateBearer = async () => (engineToken.signedIn() ? engineToken.bearer() : { error: SIGN_IN_PROMPT });
ipcMain.handle("engine:teach-token-set", (_e, token) => teachToken.signIn(typeof token === "string" ? token : ""));
ipcMain.handle("engine:teach-token-clear", () => teachToken.signOut());
ipcMain.handle("engine:teach-token-status", () => teachToken.status());
ipcMain.handle("engine:judge-teach", async (_e, taskId, decision, note) => {
  const r = await teachJudge(taskId, decision, note, teachToken.signedIn() ? teachToken.bearer() : null);
  console.log(`[engine] judge teach ${taskId} ${decision}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});
// Config > frakworks > HITLP server (#2487): the standalone HITLP server's
// surface API (src/hitlp-surface.js), its URL and the signed-in person's
// surface credential kept safeStorage-encrypted in their own file. HITLP
// Requests, Closed and the urgent popup read and decide there; the
// credential never reaches the renderer or a log line. A `task` event on its
// `GET /events` pushes a `needs` frame, which refetches the shared task list.
const hitlpSurface = createHitlpSurface({
  safeStorage,
  file: path.join(app.getPath("userData"), HITLP_SURFACE_FILE),
  log: (line) => console.log(line),
});
const pushSurfaceNeeds = () => {
  for (const w of BrowserWindow.getAllWindows()) if (!w.isDestroyed()) w.webContents.send("engine:event", { type: "needs", data: { source: "hitlp" } });
};
const resubscribeSurface = () => { if (hitlpSurface.configured()) hitlpSurface.subscribe(() => pushSurfaceNeeds()); else hitlpSurface.unsubscribe(); };
ipcMain.handle("hitlp:surface-set", async (_e, url, credential) => {
  const r = await hitlpSurface.set(typeof url === "string" ? url : "", typeof credential === "string" ? credential : "");
  if (!r.error) { resubscribeSurface(); pushSurfaceNeeds(); }
  return r;
});
ipcMain.handle("hitlp:surface-clear", () => {
  const r = hitlpSurface.clear();
  resubscribeSurface(); pushSurfaceNeeds();
  return r;
});
ipcMain.handle("hitlp:surface-status", () => hitlpSurface.status());
// Decide an open ask or approve task as the surface credential's person; the
// payloadDigest is the one the card showed. The answer is not logged.
ipcMain.handle("hitlp:surface-decide", async (_e, taskId, outcome, answer, reason, payloadDigest) => {
  const r = await hitlpSurface.decide(taskId, { outcome, answer, reason, payloadDigest });
  console.log(`[hitlp-surface] decide ${taskId} ${outcome}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});
ipcMain.handle("engine:snapshotted", (_e, repo) => engineEvents.snapshotted(typeof repo === "string" && repo ? repo : undefined));
ipcMain.handle("engine:stream-state", () => engineEvents.streamState());
ipcMain.handle("engine:subscribe", (_e, repo) => engineEvents.subscribe(String(repo)));
ipcMain.handle("engine:unsubscribe", (_e, repo) => engineEvents.unsubscribe(String(repo)));

// ---------------------------------------------------------------------------
// First mate's fresh clones (#960; src/fresh-code.js): the stream's second main-side consumer. One refcounted
// `subscribe("*")` on the stream above (shared with the selection's own `*` in
// All repos, so no second connection is opened): a `push` frame with
// `default: true` moves that repo's clone, a `repo` frame adds or sweeps one,
// and the `*` connection's `hello` is the snapshot (one fetch per repo
// `GET /repos` lists, a clone where missing). Frames on a selected repo's own
// connection are skipped by their `subscription`, so a push is handled once.
// No timer: the clones stay as they are while the stream is down.
// ---------------------------------------------------------------------------
const freshCode = createFreshCode({ freshDir: FRESH_DIR, engineUrl: engineUrl(), bearer: () => engineToken.bearer(), log: (line) => console.log(`[fresh-code] ${line}`) });
engineEvents.subscribe(FLEET);
engineEvents.on("frame", (f) => {
  if (!f || f.subscription !== FLEET || !f.data) return;
  // The tier map (src/engine-tiers.js): an edit, from this app or the API, is re-read.
  if (f.type === "setting" && f.data.name === "tiers") loadTiers();
  if (f.type === "push") freshCode.onPush(f.data);
  else if (f.type === "repo" && f.data.state === "added") freshCode.ensureClone(f.data.repo);
  else if (f.type === "repo" && f.data.state === "removed") freshCode.onRepoRemoved(f.data.repo, { inUse: (d) => Boolean(firstMate && firstMate.inUse(d)) });
});
engineEvents.on("hello", async (h) => {
  if (!h || h.repo !== FLEET) return;
  // The tier map's snapshot: main's copy, read on each (re)connect.
  loadTiers().then((t) => { if (t.error) console.log(`[engine-tiers] hello: /tiers: ${t.error}`); });
  const r = await fetchRepoNames();
  if (r.repos) await freshCode.onHello(r.repos, { inUse: (d) => Boolean(firstMate && firstMate.inUse(d)) });
  else console.log(`[fresh-code] hello: /repos: ${r.error}`);
});
// The one-time copy of the frakworks checkout's Claude project directory
// (memory and session files) to the frakworks clone's slug, leaving the
// source; recorded in fresh.json so it runs once, and never over an existing target.
freshCode.migrateProjectDir(REPO_DIR, path.join(FRESH_DIR, ...APP_REPO.split("/")));

// The cwd a saved chat's session is found in (#960): the clone of the chat's
// repo (the clones' root for All repos), or the legacy checkout when the chat
// was saved before fresh code and its session file is still only there. The
// chat's `repo` comes from the engine's saved-chat body: `savedChatDir` turns
// only a plain `owner/name` into a path under FRESH_DIR, anything else (a `..`
// segment, a third `/`) is the clones' root, never a path outside it.
function savedChatCwd(chat) {
  const fresh = savedChatDir(FRESH_DIR, chat && chat.repo);
  if (resumability(chat, fresh).resumable) return fresh;
  if (resumability(chat, REPO_DIR).resumable) return REPO_DIR;
  return fresh;
}

// Never throws: fetchTicketData folds every failure into the document, so the
// renderer's `.err` block is what a dead `gh` looks like (#1281). Lifecycle,
// cost and run history moved to the engine's own timeline (#101/#91, read via
// "engine:ticket-timeline" below) and are no longer part of this document.
// A ticket is `owner/name#n` (#577): the renderer sends its repo with it,
// and main refuses one GET /repos does not list, as it does for a pin.
ipcMain.handle("ticket:fetch", async (_e, repo, ticket) => {
  const t = await targetRepo(repo);
  const d = t.error
    ? Object.assign(await fetchTicketData(ticket, { repo: null }), { repo, issue_error: t.error, prs_error: t.error })
    : await fetchTicketData(ticket, { repo: t.repo });
  const errs = ["issue_error", "prs_error"].filter((k) => d[k]);
  console.log(`[fleet] ticket ${d.ticket}: ${d.issue ? d.issue.comments.length : 0} comments, ${d.prs.length} PRs` +
    (errs.length ? ` — ${errs.map((k) => d[k]).join(" | ")}` : ""));
  return d;
});

// Also never throws: fetchTranscript folds every failure into `error`, so the
// pane's `.err` block is what a dead VictoriaLogs looks like and the rest of
// the detail view keeps its data (#1283). Deliberately quiet: it is
// read on each open, catch-up and reconnect, so it logs only a first read or a failure.
// `run` is the live run the renderer heard of in a `session` `started` frame
// (`{ filename, host }`), or null: with it, no gauge read and no session query.
ipcMain.handle("transcript:fetch", async (_e, ticket, since, run) => {
  const d = await fetchTranscript(ticket, { base: deployment.logsUrl, vm: deployment.metricsUrl, since: since || null, run: run && typeof run === "object" ? run : null });
  if (d.error || !since) {
    console.log(`[fleet] transcript ${d.ticket}: ${d.live ? "live" : "ended"} on ${d.host || "?"}, ` +
      `${d.events.length} events${d.error ? ` — ${d.error}` : ""}`);
  }
  return d;
});

// The live tail of a run's transcript (src/transcript-tail.js, #1006): one open
// VictoriaLogs connection per (window, `token`), the token the renderer's own
// so its frames can be matched before the open has returned. Lines and the
// connection's state are pushed on `transcript:tail` as `{ token, ...frame }`.
// `end` is the run's `session` `ended` (the tail is dropped after its grace
// period and a last read, and the call resolves then); `close` drops it now.
// A window that goes away takes its tails with it. The renderer passes the run
// the engine named and the cursor; the query is main's.
const transcriptTails = new Map();
const tailKey = (sender, token) => `${sender.id}:${token}`;
const tailedSenders = new WeakSet();
ipcMain.handle("transcript:tail-open", (e, token, ticket, run, since) => {
  const n = Number(ticket);
  const filename = run && typeof run.filename === "string" && run.filename ? run.filename : null;
  if (!Number.isInteger(n) || n <= 0 || !filename || typeof token !== "string") return { error: "not a live run to tail" };
  const sender = e.sender;
  const key = tailKey(sender, token);
  if (transcriptTails.has(key)) transcriptTails.get(key).close();
  const tail = openTail({
    base: deployment.logsUrl,
    ticket: n, filename, host: typeof run.host === "string" && run.host ? run.host : null,
    since: SINCE_RE.test(String(since ?? "")) ? String(since) : null,
    onFrame: (frame) => {
      if (frame.state === "lapsed") console.log(`[fleet] transcript ${n}: ${frame.error}`);
      if (!sender.isDestroyed()) sender.send("transcript:tail", { token, ...frame });
    },
  });
  transcriptTails.set(key, tail);
  if (!tailedSenders.has(sender)) {
    tailedSenders.add(sender);
    sender.once("destroyed", () => {
      for (const [k, t] of transcriptTails) if (k.startsWith(`${sender.id}:`)) { t.close(); transcriptTails.delete(k); }
    });
  }
  return { ok: true };
});
ipcMain.handle("transcript:tail-end", async (e, token) => {
  const key = tailKey(e.sender, token);
  const tail = transcriptTails.get(key);
  if (!tail) return { ok: true };
  await tail.end();
  if (transcriptTails.get(key) === tail) transcriptTails.delete(key);
  return { ok: true };
});
ipcMain.handle("transcript:tail-close", (e, token) => {
  const key = tailKey(e.sender, token);
  const tail = transcriptTails.get(key);
  if (tail) { tail.close(); transcriptTails.delete(key); }
  return { ok: true };
});

// One doctor run's transcript, for the ticket detail's Doctor section
// (src/renderer/flow.jsx): its log file in VictoriaLogs, read once per ask.
// Never throws, like transcript:fetch.
ipcMain.handle("transcript:doctor", async (_e, ticket, runId) => {
  const d = await fetchDoctorTranscript(ticket, runId, { base: deployment.logsUrl });
  if (d.error) console.log(`[fleet] doctor transcript ${d.ticket} ${d.runId}: ${d.error}`);
  return d;
});

// The explorer's "Tickets" branch: the engine's open issues of the selected
// repo, grouped by their state there; in All repos, grouped by repo first.
ipcMain.handle("tree:tickets", async () => {
  const r = await fetchTicketTree({ repo: await currentRepo() });
  if (r.error) console.log(`[tree] ${r.error}`);
  return r;
});
// Its "Done" group: closed issues, asked for only when the group is opened;
// `limit` is clamped in fetchDoneTickets.
ipcMain.handle("tree:done", async (_e, limit) => {
  const r = await fetchDoneTickets({ repo: await currentRepo(), limit });
  if (r.error) console.log(`[tree] ${r.error}`);
  return r;
});

// The "needs you" view: the engine's GET /needs (src/needs-you.js). Read-only.
ipcMain.handle("needs:fetch", async () => {
  const r = await fetchNeeds({ repo: await currentRepo() });
  console.log(`[needs] fetch: ${r.error ? `error: ${r.error}` : `${r.rows.length} row(s) waiting`}`);
  return r;
});

// The HITLP server's tasks, open and decided, read once for the window: HITLP
// Requests, Closed, the explorer badge and the urgent popup (src/needs-you.js
// fetchHitlpTasks). Read-only; deciding is hitlp:surface-decide.
ipcMain.handle("hitlp:tasks", async () => {
  const r = await fetchHitlpTasks({ surface: () => hitlpSurface.list(), configured: hitlpSurface.configured(), url: hitlpSurface.status().url });
  if (r.error && !r.notSet) console.log(`[hitlp-surface] tasks: ${r.error}`);
  return r;
});
// One task by id, for a ticket's inline task view (src/renderer/hitlp-task.jsx
// TicketTask): `{ row, error, notSet }`, never the credential.
ipcMain.handle("hitlp:surface-get", async (_e, taskId) => {
  const r = await fetchHitlpTask({ surface: (id) => hitlpSurface.get(id), configured: hitlpSurface.configured(), id: typeof taskId === "string" ? taskId : "" });
  if (r.error && !r.notSet) console.log(`[hitlp-surface] get ${taskId}: ${r.error}`);
  return r;
});

// The issues board, from the engine (src/engine-data.js): the first read that
// goes to the engine rather than the old fleet's sources, and the view the
// window opens on. Never throws; a dead engine is an `error` the view draws.
ipcMain.handle("engine:issues", async () => {
  const repo = await currentRepo();
  const r = await fetchIssues(repo ? { repo } : {});
  console.log(`[engine] issues (${engineUrl()}): ${r.error ? `error: ${r.error}` : `${r.issues.length} issue(s)` +
    (r.failures.length ? `, ${r.failures.length} repo(s) failed` : "")}`);
  return r;
});

// One flow's detail from the engine (src/engine-data.js fetchFlow), for the
// issue detail view the Issues board opens.
ipcMain.handle("engine:flow", async (_e, instance) => {
  const r = await fetchFlow(instance);
  console.log(`[engine] flow ${instance}: ${r.error ? `error: ${r.error}` : `${r.flow.steps.length} step(s), ${r.flow.ended ? "ended" : "running"}`}`);
  return r;
});

// The process view (src/renderer/process.jsx): the engine's /camunda reads,
// for the ticket and issue details. Read-only, like every engine read above.
ipcMain.handle("engine:process-instances", async (_e, issueUrl) => {
  const r = await fetchProcessInstances(issueUrl);
  console.log(`[engine] process instances for ${issueUrl}: ${r.error ? `error: ${r.error}` : `${r.instances.length} instance(s)`}`);
  return r;
});
ipcMain.handle("engine:process-tree", async (_e, instance) => {
  const r = await fetchProcessTree(instance);
  console.log(`[engine] process tree ${instance}: ${r.error ? `error: ${r.error}` : `${r.tree.nodes.length} process(es)`}`);
  return r;
});
ipcMain.handle("engine:process-xml", async (_e, definitionId) => {
  const r = await fetchProcessXml(definitionId);
  console.log(`[engine] process xml ${definitionId}: ${r.error ? `error: ${r.error}` : `${r.xml.length} chars`}`);
  return r;
});

// The issue detail's read of an issue the board has no row for (#548): a
// closed ticket that /needs still lists. GET /ticket/{owner}/{repo}/{n}
// (src/engine-data.js fetchTicketDetail). Read-only.
ipcMain.handle("engine:ticket", async (_e, owner, repo, n) => {
  const r = await fetchTicketDetail(owner, repo, n);
  console.log(`[engine] ticket ${owner}/${repo}#${n}: ${r.error ? `error: ${r.error}` : r.ticket.state || "?"}`);
  return r;
});

// The issue detail's Pitch view (src/renderer/pitch.jsx, #563): a proposal's
// pitch page or rendered doc, GET /ticket/{owner}/{repo}/{n}/pitch
// (src/engine-data.js fetchTicketPitch). Read-only.
ipcMain.handle("engine:ticket-pitch", async (_e, owner, repo, n) => {
  const r = await fetchTicketPitch(owner, repo, n);
  console.log(`[engine] ticket pitch ${owner}/${repo}#${n}: ${r.error ? `error: ${r.error}` : `${r.pitch.kind} ${r.pitch.path}`}`);
  return r;
});

// A confirm-mode feature's design deck (src/renderer/design-deck.jsx, #100114):
// GET /ticket/{owner}/{repo}/{n}/design-deck (src/engine-data.js fetchDesignDeck). Read-only.
ipcMain.handle("engine:design-deck", async (_e, owner, repo, n) => {
  const r = await fetchDesignDeck(owner, repo, n);
  if (r.error && !r.none) console.log(`[engine] design deck ${owner}/${repo}#${n}: error: ${r.error}`);
  return r;
});

// The explorer's Proposals branch (src/renderer/proposals.jsx, #660): the
// proposal docs on the selected repo's default branch (every target repo's in
// All repos), GET /proposals (src/engine-data.js fetchProposals). Read-only.
ipcMain.handle("engine:proposals", async () => {
  const repo = await currentRepo();
  const r = await fetchProposals(repo ? { repo } : {});
  console.log(`[engine] proposals: ${r.error ? `error: ${r.error}` : `${r.docs.length} doc(s)` +
    (r.failures.length ? `, ${r.failures.length} repo(s) failed` : "")}`);
  return r;
});

// The proposal view's page for a doc with no ticket (#660):
// GET /proposals/{owner}/{repo}/{slug}/page (src/engine-data.js fetchProposalPage). Read-only.
ipcMain.handle("engine:proposal-page", async (_e, owner, repo, slug) => {
  const r = await fetchProposalPage(owner, repo, slug);
  console.log(`[engine] proposal page ${owner}/${repo}:${slug}: ${r.error ? `error: ${r.error}` : `${r.pitch.kind} ${r.pitch.path}`}`);
  return r;
});

// The explorer's Repos branch (src/renderer/repo-files.jsx, #674): one target
// repo's whole tree at its default branch, read once when its node is first
// opened (and again on the branch's ⟳), GET /repos/{owner}/{repo}/tree
// (src/engine-data.js fetchRepoTree). Read-only; the engine reads GitHub.
ipcMain.handle("engine:repo-tree", async (_e, owner, repo) => {
  const r = await fetchRepoTree(owner, repo);
  console.log(`[engine] repo tree ${owner}/${repo}: ${r.error ? `error: ${r.error}` :
    `${r.tree.entries.length} entr${r.tree.entries.length === 1 ? "y" : "ies"} at ${r.tree.commit.slice(0, 7)}${r.tree.truncated ? ", truncated" : ""}`}`);
  return r;
});

// The Repos branch's file view (#674): one file's bytes at the tree's commit,
// GET /repos/{owner}/{repo}/file (src/engine-data.js fetchRepoFile), fetched
// when the file is clicked. Read-only.
ipcMain.handle("engine:repo-file", async (_e, owner, repo, path, ref) => {
  const r = await fetchRepoFile(owner, repo, path, ref || null);
  console.log(`[engine] repo file ${owner}/${repo}:${path}: ${r.error ? `error: ${r.error}` : `${r.file.size} bytes`}`);
  return r;
});

// The ticket detail's Timeline (src/renderer/timeline.jsx): the engine's
// not-yet-shipped /ticket/.../timeline (#91). Read-only, like the /camunda
// reads above.
ipcMain.handle("engine:ticket-timeline", async (_e, owner, repo, n) => {
  const r = await fetchTicketTimeline(owner, repo, n);
  console.log(`[engine] ticket timeline ${owner}/${repo}#${n}: ` +
    (r.error ? `error: ${r.error}` : r.notFound ? "not found" : `${r.timeline.steps.length} step(s), ${r.timeline.waits.length} wait(s)`));
  return r;
});

// The Jev decisions page and the ticket detail's Jev section
// (src/renderer/jev.jsx, #553): GET /jev/calls, /jev/calls/{id} and
// /jev/summary, every argument checked in src/engine-data.js before it goes in
// a URL. Read-only.
ipcMain.handle("engine:jev-calls", async (_e, filter) => {
  const r = await fetchJevCalls(filter && typeof filter === "object" ? filter : {});
  console.log(`[engine] jev calls: ${r.error ? `error: ${r.error}` : `${r.calls.length} of ${r.total}`}`);
  return r;
});

ipcMain.handle("engine:jev-call", async (_e, id) => {
  const r = await fetchJevCall(id);
  if (r.error) console.log(`[engine] jev call ${id}: error: ${r.error}`);
  return r;
});

ipcMain.handle("engine:jev-summary", async (_e, since) => {
  const r = await fetchJevSummary(since == null ? undefined : since);
  console.log(`[engine] jev summary: ${r.error ? `error: ${r.error}` : `${r.summary.totals.calls} call(s)`}`);
  return r;
});

// The Analytics > SSH verdicts page (src/renderer/ssh-verdicts.jsx, #2439):
// GET /jev/ssh-verdicts, the repo checked in src/engine-data.js. Read-only.
ipcMain.handle("engine:ssh-verdicts", async (_e, repo) => {
  const r = await fetchSshVerdicts(repo == null ? undefined : repo);
  console.log(`[engine] ssh verdicts: ${r.error ? `error: ${r.error}` : `${r.verdicts.length} verdict(s)`}`);
  return r;
});

// Tickets > Tickets (src/renderer/work-items.jsx, #2586, #100029): a repo's
// work items with whether Import can be offered, one item with its
// conversation, a reply and an import, each checked in src/work-items.js and
// sent with main's bearer token, never a login the renderer names.
ipcMain.handle("engine:work-items", async (_e, repo) => {
  const r = await viewList(repo == null ? null : String(repo));
  console.log(`[engine] work items ${repo}: ${r.error ? `error: ${r.error}` : `${r.items.length} item(s)`}`);
  return r;
});

ipcMain.handle("engine:work-item", async (_e, id) => viewRead(String(id)));

ipcMain.handle("engine:work-item-reply", async (_e, id, text) => {
  const r = await replyToWorkItem(String(id), typeof text === "string" ? text : "");
  console.log(`[engine] work item reply ${id}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});

ipcMain.handle("engine:work-item-import", async (_e, repo, issue) => {
  const r = await viewImport(repo == null ? null : String(repo), Number(issue));
  console.log(`[engine] work item import ${repo}#${issue}: ${r.error ? `error: ${r.error}` : r.unavailable ? "unavailable (404)" : "ok"}`);
  return r;
});

// The Analytics > Friction notes page (src/renderer/friction.jsx, #1046): GET
// /friction/summary and /friction/notes, every argument checked in
// src/engine-data.js before it goes in a URL. Read-only.
ipcMain.handle("engine:friction-summary", async (_e, filter) => {
  const r = await fetchFrictionSummary(filter && typeof filter === "object" ? filter : {});
  console.log(`[engine] friction summary: ${r.error ? `error: ${r.error}` : `${r.summary.total} note(s)`}`);
  return r;
});

ipcMain.handle("engine:friction-notes", async (_e, filter) => {
  const r = await fetchFrictionNotes(filter && typeof filter === "object" ? filter : {});
  console.log(`[engine] friction notes: ${r.error ? `error: ${r.error}` : `${r.notes.length} note(s)`}`);
  return r;
});

// The page's group rows (#1098): GET /friction/groups with the notes' filters.
ipcMain.handle("engine:friction-groups", async (_e, filter) => {
  const r = await fetchFrictionGroups(filter && typeof filter === "object" ? filter : {});
  console.log(`[engine] friction groups: ${r.error ? `error: ${r.error}` : `${r.groups.length} group(s)`}`);
  return r;
});

// The page's two writes (#1098): a note pulled out of its group, and a group
// merged into another, each as the same login as a retry (`signedInLogin`,
// below); the engine refuses a Smith sandbox and needs no repo access.
ipcMain.handle("engine:friction-pull-out", async (_e, noteId) => {
  const by = await signedInLogin();
  const r = await frictionPullOut(String(noteId), by);
  console.log(`[engine] friction pull out ${noteId} as ${by || "(no login)"}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});

// Not two things (#1112): the mark cleared, as the same login.
ipcMain.handle("engine:friction-not-two-things", async (_e, noteId) => {
  const by = await signedInLogin();
  const r = await frictionNotTwoThings(String(noteId), by);
  console.log(`[engine] friction not two things ${noteId} as ${by || "(no login)"}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});

ipcMain.handle("engine:friction-merge", async (_e, groupId, into) => {
  const by = await signedInLogin();
  const r = await frictionMerge(groupId, into, by);
  console.log(`[engine] friction merge g${groupId} into g${into} as ${by || "(no login)"}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});

ipcMain.handle("engine:friction-fix", async (_e, groupId, fix) => {
  const by = await signedInLogin();
  const r = await frictionFix(groupId, fix, by);
  console.log(`[engine] friction fix g${groupId} ${fix ? `by ${fix.repo}#${fix.issue}` : "cleared"} as ${by || "(no login)"}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});

// The coding card's Summarize button (src/renderer/detail.jsx): the engine
// summarizes the running Smith's session with an LLM. A POST because it spends
// a model call; it changes nothing on the ticket or the run.
ipcMain.handle("engine:summarize", async (_e, owner, repo, n) => {
  const r = await summarizeTicket(owner, repo, n);
  console.log(`[engine] summarize ${owner}/${repo}#${n}: ${r.error ? `error: ${r.error}` : `run ${r.summary.runId}`}`);
  return r;
});

// The review tile's prediction bar (src/renderer/timeline.jsx, #1746): Jev's
// call on the ticket's live review run. A POST because it may spend a Jev call;
// the renderer chains these back-to-back while the review runs.
ipcMain.handle("engine:predict-review", async (_e, repo, n) => {
  const r = await predictReview(repo, n);
  if (r.error) console.log(`[engine] predict-review ${repo}#${n}: error: ${r.error}`);
  return r;
});

// The process view's one write: retry an incident, as the login the engine
// token proves (`GET /whoami`). The
// token is the only way the app names a login: a desktop that is not signed in
// has none, and its engine views show the Sign in prompt instead of acting.
// After a restart the token is loaded but not yet resolved to a login (only
// `status()` asks GET /whoami), so ask once here rather than refuse the write.
const signedInLogin = async () => {
  if (!engineToken.login() && engineToken.signedIn()) await engineToken.status();
  return engineToken.login() || null;
};
// The login, read-only, for the renderer's "Needs <login>" toggle and "asked by
// me" filter: the signed-in token's (GET /whoami); null when not signed in.
ipcMain.handle("login:get", () => signedInLogin());
ipcMain.handle("engine:retry-incident", async (_e, incidentId) => {
  const by = await signedInLogin();
  const r = await retryIncident(incidentId, by);
  console.log(`[engine] retry incident ${incidentId} as ${by}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});

// The issue detail's Cancel (src/renderer/cancel-flow.jsx): cancel a root
// flow with a reason, as the same login as a retry. The reason is not logged;
// the engine puts it on the issue.
ipcMain.handle("engine:cancel-flow", async (_e, instanceId, reason) => {
  const by = await signedInLogin();
  const r = await cancelFlow(instanceId, by, reason);
  const failed = r.failedWrites && r.failedWrites.length ? ` (${r.failedWrites.length} GitHub write(s) failed: ${r.failedWrites.join("; ")})` : "";
  console.log(`[engine] cancel flow ${instanceId} as ${by}: ${r.error ? `error: ${r.error}` : `ok${failed}`}`);
  return r;
});

// The issue detail's Doctor section (src/renderer/flow.jsx): the ticket's
// doctor runs, read-only.
ipcMain.handle("engine:doctor-history", async (_e, issueUrl) => {
  const r = await fetchDoctorHistory(issueUrl);
  if (r.error) console.log(`[engine] doctor history ${issueUrl}: error: ${r.error}`);
  return r;
});

// The Analytics > Doctor page (src/renderer/doctor.jsx, #706): the doctor's
// runs across tickets since the window's start, read-only.
ipcMain.handle("engine:doctor-runs", async (_e, since) => {
  const r = await fetchDoctorRuns(since);
  console.log(`[engine] doctor runs: ${r.error ? `error: ${r.error}` : `${r.runs.length} run(s)`}`);
  return r;
});

// Analytics > AI Accounts > Account consumption (src/renderer/consumption.jsx, #1934):
// what the fleet consumed over the chosen timeframe, read-only.
ipcMain.handle("engine:consumption", async (_e, window) => {
  const r = await fetchConsumption(String(window));
  console.log(`[engine] consumption ${window}: ${r.error ? `error: ${r.error}` : `${r.rows.length} row(s)`}`);
  return r;
});

// The Analytics > Builds page (src/renderer/builds.jsx, #1935): a target repo's
// workflow runs with their kinds, read-only; engine-data checks the repo and limit.
ipcMain.handle("engine:ci-runs", async (_e, repo, limit, workflow, branch) => {
  const r = await fetchCiRuns(repo, limit, workflow, branch);
  console.log(`[engine] ci runs ${repo}: ${r.error ? `error: ${r.error}` : `${r.runs.length} run(s)`}`);
  return r;
});

// Utils > HITLP producer (src/renderer/hitlp-producer.jsx, #1947): an MCP
// `tools/call` of human.ask or human.approve, or a `tasks/get`, sent from here
// to the HITLP server the user named (src/hitlp-client.js), never through the
// engine; the exact request and the raw answer go to the log and the renderer.
// The only credential sent is the requester token pasted on the page (#2489),
// as `Authorization: Bearer` to that URL: never the app's own sign-in or the
// HITLP surface credential, and no header at all without one. The log line
// carries no headers.
ipcMain.handle("hitlp:call", async (_e, url, op, token) => {
  const requesterToken = typeof token === "string" ? token.trim() : "";
  const r = await callHitlp(url, op, requesterToken ? { headers: { authorization: `Bearer ${requesterToken}` } } : {});
  console.log(`[hitlp] ${url} request: ${JSON.stringify(r.request)}`);
  console.log(`[hitlp] ${url} response: ${r.error ? `error: ${r.error}` : `${r.status} ${r.body}`}`);
  return r;
});

// Config > Repos' Edit… modal's Doctor tab (src/renderer/repos.jsx, #1001): the
// doctor's bugs in every repo since `since`, read-only; the modal keeps its repo's.
ipcMain.handle("engine:doctor-bugs", async (_e, since) => {
  const r = await fetchDoctorBugs(since);
  console.log(`[engine] doctor bugs: ${r.error ? `error: ${r.error}` : `${r.bugs.length} bug(s)`}`);
  return r;
});

// The ticket view's Fix and Propose a fix (src/renderer/incident-actions.jsx):
// a read of the ticket's incident and its latest runs, and two writes that each
// start a sandboxed Smith run on the incident, as the same login as a retry.
ipcMain.handle("engine:incident-fixes", async (_e, issueUrl) => {
  const r = await fetchIncidentFixes(issueUrl);
  if (r.error) console.log(`[engine] incident fixes ${issueUrl}: error: ${r.error}`);
  return r;
});
for (const [channel, start, what] of [["engine:incident-fix", startIncidentFix, "fix"], ["engine:incident-propose", startIncidentPropose, "propose a fix for"]]) {
  ipcMain.handle(channel, async (_e, incidentId) => {
    const by = await signedInLogin();
    const r = await start(incidentId, by);
    console.log(`[engine] ${what} incident ${incidentId} as ${by}: ${r.error ? `error: ${r.error}` : "ok"}`);
    return r;
  });
}

// A file attached to a flow's next answer (#1730): POST
// /intake/<id>/attachments, as the signed-in login. Only the name is logged.
ipcMain.handle("engine:upload-attachment", async (_e, intake, file) => {
  const by = await signedInLogin();
  const r = await uploadAttachment(intake, file, by);
  console.log(`[engine] attachment ${file && file.name} on ${intake} as ${by}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});

// The engine's tier map (GET /tiers, src/engine-tiers.js): the "New intake"
// composer's tier picker (src/renderer/issues.jsx), First mate's tier picker
// and Config > frakworks > Tiers, as rows sorted 1-10 with the model's short
// name. Read from the engine each time (and kept as main's copy), so the
// renderer's refetch on a `setting` frame sees an edit; `{ error }` when the
// engine cannot answer. Written with engine:account-action's `tier` op.
ipcMain.handle("engine:tiers", async () => {
  const r = await loadTiers();
  console.log(`[engine] tiers (${engineUrl()}): ${r.error ? `error: ${r.error}` : `${r.tiers.length} row(s)`}`);
  return r.error ? { error: r.error } : r.tiers;
});

// Each tier's account right now (GET /tiers/accounts, a peek that moves no
// rotation), for the "Which account would be used?" tile: one call per ask,
// made only when the renderer asks (view open, or its Refresh).
ipcMain.handle("engine:tier-accounts", async () => {
  const r = await fetchTierAccounts();
  console.log(`[engine] tier accounts (${engineUrl()}): ${r.error ? `error: ${r.error}` : `${r.rows.length} row(s)`}`);
  return r;
});

// The tier every security review runs at (GET /security/tier), for
// Config > frakworks > Tiers: `{ securityTier }` or `{ securityTier: null,
// error }`. Set with engine:account-action's `security-tier` op.
ipcMain.handle("engine:security-tier", async () => {
  const t = await fetchSecurityTier();
  return { securityTier: t.securityTier || null, error: t.error || null };
});

// The tier every post-deployment check run runs at (GET /check/tier), for the
// same section: `{ checkTier }` or `{ checkTier: null, error }`. Set with the `check-tier` op.
ipcMain.handle("engine:check-tier", async () => {
  const t = await fetchCheckTier();
  return { checkTier: t.checkTier || null, error: t.error || null };
});

// The Issues view's "New intake" composer (src/renderer/issues.jsx): starts an
// intake with the ask text verbatim (the renderer has already put the
// user's `Tier:` line first), authorizing as the same login the two writes
// above resolve — like First mate's submit_ask, `authorize: true` goes with
// `submittedBy` so the engine refuses before any process starts (400 missing
// submitter, 403 untrusted login) rather than filing an intake that waits for
// a human's `authorized` label. No login resolved means nothing is posted.
// `repo` (#577) is the composer's pick in All repos; otherwise the selected
// repo. In All repos with no pick nothing is sent.
ipcMain.handle("engine:start-intake", async (_e, ask, repo) => {
  const target = (typeof repo === "string" && repo) || await currentRepo();
  if (!target) return { error: "All repos is selected: pick the repo to file in" };
  const by = await signedInLogin();
  if (!by) return { error: "no GitHub login to file as (sign in to the engine: Config > Sign in)" };
  const r = await submitIntake(ask, by, { repo: target, authorize: true });
  console.log(`[engine] start intake as ${by}: ${r.error ? `error: ${r.error}` : `ok, ${r.intakeId}`}`);
  return r;
});

// The engine's AI accounts with their plan meters (src/engine-data.js), for
// the explorer's Config > AI Accounts; its writes are engine:account-action.
ipcMain.handle("engine:accounts", async () => {
  const r = await fetchAccounts();
  console.log(`[engine] accounts (${engineUrl()}): ${r.error ? `error: ${r.error}` : `${r.accounts.length} account(s)` +
    (r.usageError ? `, usage failed: ${r.usageError}` : "")}`);
  return r;
});

// The intake refiner's tier (GET /intake/tier), for Config > Intake refiner
// tier: `{ refinerTier }` or `{ refinerTier: null, error }`. Set with
// engine:account-action's `refiner-tier` op.
ipcMain.handle("engine:refiner-tier", async () => {
  const t = await fetchRefinerTier();
  console.log(`[engine] refiner tier (${engineUrl()}): ${t.error ? `error: ${t.error}` : t.refinerTier.tier}`);
  return { refinerTier: t.refinerTier || null, error: t.error || null };
});

// Whether the engine hosts frakpanel tiles (GET /frakpanel/tiles/enabled), for
// Config > frakworks: `{ frakpanelTiles }` or `{ frakpanelTiles: null, error }`.
// Set with engine:account-action's `frakpanel-tiles` op.
ipcMain.handle("engine:frakpanel-tiles", async () => {
  const t = await fetchFrakpanelTiles();
  console.log(`[engine] frakpanel tiles (${engineUrl()}): ${t.error ? `error: ${t.error}` : t.frakpanelTiles.enabled}`);
  return { frakpanelTiles: t.frakpanelTiles || null, error: t.error || null };
});

// Where new Smith runs start (GET /settings/smith-hosting), for Config >
// frakworks > Smith hosting: `{ smithHosting }` or `{ smithHosting: null,
// error }`. Set with engine:account-action's `smith-hosting` op.
ipcMain.handle("engine:smith-hosting", async () => {
  const t = await fetchSmithHosting();
  console.log(`[engine] smith hosting (${engineUrl()}): ${t.error ? `error: ${t.error}` : t.smithHosting.hosting}`);
  return { smithHosting: t.smithHosting || null, error: t.error || null };
});

// Config > AI Accounts' writes: add, set a credential, enable/disable, remove,
// and verify, which asks the engine to try the stored credential (src/engine-data.js accountAction validates each),
// and the tiers (`refiner-tier`, `tier`, `security-tier`, no account). A credential passes
// through to the engine once and is never logged, stored or sent back.
// Where a new First mate conversation runs (GET /settings/first-mate-hosting,
// #2521), for Config > frakworks > First mate hosting: `{ firstMateHosting }`
// or `{ firstMateHosting: null, error }`. Set with the `first-mate-hosting` op.
ipcMain.handle("engine:first-mate-hosting", async () => {
  const t = await fetchFirstMateHosting();
  console.log(`[engine] first mate hosting (${engineUrl()}): ${t.error ? `error: ${t.error}` : t.firstMateHosting.hosting}`);
  return { firstMateHosting: t.firstMateHosting || null, error: t.error || null };
});

ipcMain.handle("engine:account-action", async (_e, action) => {
  const r = await accountAction(action);
  const op = action && action.op;
  const name = op === "refiner-tier" || op === "security-tier" || op === "check-tier" ? `-> ${action.tier}`
    : op === "frakpanel-tiles" ? `-> ${action.enabled}`
    : op === "tier" ? `${action.tier} -> ${action.model} ${action.effort}`
    : op === "smith-hosting" || op === "first-mate-hosting" ? `-> ${action.hosting}` : action && action.name;
  // First mate's next turn resolves the edited row without waiting for the stream's frame.
  if (op === "tier" && r.ok) await loadTiers();
  console.log(`[engine] account ${op} ${name}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});

// Config > Jobs (src/renderer/jobs.jsx, #1228): the repo's custom jobs (GET
// /jobs?repo=, refused in engine-data without a repo) and the three writes,
// each as the signed-in login, which the bearer token names; the engine checks
// that login's `jobs` grant on the repo.
ipcMain.handle("engine:jobs", async (_e, repo) => {
  const r = await fetchJobs(repo);
  console.log(`[engine] jobs ${repo}: ${r.error ? `error: ${r.error}` : `${r.jobs.length} job(s)`}`);
  return r;
});
ipcMain.handle("engine:job-register", async (_e, job) => {
  const by = await signedInLogin();
  const r = await registerJob(job, by);
  console.log(`[engine] job register ${job && job.repo} as ${by || "(no login)"}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});
ipcMain.handle("engine:job-execute", async (_e, id) => {
  const by = await signedInLogin();
  const r = await executeJob(String(id), by);
  console.log(`[engine] job execute ${id} as ${by || "(no login)"}: ${r.error ? `error: ${r.error}` : `instance ${r.instance}`}`);
  return r;
});
ipcMain.handle("engine:job-delete", async (_e, id) => {
  const by = await signedInLogin();
  const r = await deleteJob(String(id), by);
  console.log(`[engine] job delete ${id} as ${by || "(no login)"}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});

// Config > Repos, Edit… > Refactoring (src/renderer/refactor-config.jsx, #1399):
// one repo's refactoring config (GET /repos/{o}/{n}/refactor) and its PUT, as
// the same login as a retry.
ipcMain.handle("engine:refactor-config", async (_e, repo) => {
  const r = await fetchRefactorConfig(repo);
  console.log(`[engine] refactor config ${repo}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});
// The refactoring job's run history (#1653): GET /jobs/{id}/runs.
ipcMain.handle("engine:job-runs", async (_e, id, limit) => {
  const r = await fetchJobRuns(id, limit);
  if (r.error) console.log(`[engine] job runs ${id}: error: ${r.error}`);
  return r;
});
// Config > Repos > Configure First Mate > MCP servers (src/renderer/first-mate-config.jsx,
// #1801): a repo's First mate MCP servers, their save and delete as main's
// login, and a test: the engine's for an http or sse server; a stdio one runs
// here, so main fetches its opened config and does the handshake itself. No
// secret value crosses to the renderer.
ipcMain.handle("engine:mcp-servers", async (_e, repo) => {
  const r = await fetchRepoMcpServers(repo);
  if (r.error) console.log(`[engine] mcp servers ${repo}: error: ${r.error}`);
  return r;
});
ipcMain.handle("engine:mcp-server-save", async (_e, repo, name, body) => {
  const by = await signedInLogin();
  const r = await saveRepoMcpServer(repo, name, body, by);
  console.log(`[engine] mcp server save ${repo} ${name} as ${by || "(no login)"}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});
ipcMain.handle("engine:mcp-server-delete", async (_e, repo, name) => {
  const by = await signedInLogin();
  const r = await deleteRepoMcpServer(repo, name, by);
  console.log(`[engine] mcp server delete ${repo} ${name} as ${by || "(no login)"}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});
ipcMain.handle("engine:mcp-server-test", async (_e, repo, name) => {
  const config = await fetchRepoMcpConfig(repo);
  const entry = config.servers && config.servers[name];
  const r = entry && entry.type === "stdio" ? await probeStdio(entry) : await testRepoMcpServer(repo, name);
  console.log(`[engine] mcp server test ${repo} ${name}: ${r.reached ? `reached, ${r.tools.length} tools` : `error: ${r.error}`}`);
  return { reached: Boolean(r.reached), tools: r.tools || [], error: r.error || null, local: Boolean(entry && entry.type === "stdio") };
});
ipcMain.handle("engine:refactor-save", async (_e, repo, patch) => {
  const by = await signedInLogin();
  const r = await saveRefactorConfig(repo, patch, by);
  console.log(`[engine] refactor save ${repo} as ${by || "(no login)"}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});

// Config > Repos (src/renderer/repos.jsx): the target repos, each with its
// running-flow count (src/engine-data.js fetchRepoRows; the setup check comes on the stream), one
// repo's grants, and the writes. Every write goes as the same login as a
// retry, resolved here and never taken from the renderer: adding a repo
// grants that login every kind, and is refused before anything is sent when
// there is none.
ipcMain.handle("engine:repos", async () => {
  const r = await fetchRepoRows();
  console.log(`[engine] repos (${engineUrl()}): ${r.error ? `error: ${r.error}` : `${r.repos.length} repo(s)` +
    (r.runningError ? `, running count failed: ${r.runningError}` : "")}`);
  return r;
});
// The GitHub-side setup check, asked for and not awaited: the engine answers
// 202 at once and each repo's report arrives as a `setup` frame on the event
// stream (engine/README.md, "A new target repo's GitHub setup"). Config >
// Repos' Health check asks for `*`, forced: each click reads GitHub. Create
// labels' re-check of one repo goes through engine:repo-action (`setup-check`).
ipcMain.handle("engine:setup-check", async (_e, repo) => {
  const r = await requestSetupCheck(typeof repo === "string" ? repo : "*", { force: true });
  console.log(`[engine] setup check ${repo}: ${r.error ? `error: ${r.error}` : `${r.queued.length} queued, ${r.fresh.length} fresh`}`);
  return r;
});
// The engine-wide list of trusted logins (engine/README.md, "Starting a flow
// from an issue"). A change goes as the signed-in login, like a repo's.
ipcMain.handle("engine:engine-access", async () => {
  const r = await fetchEngineAccess();
  if (r.error) console.log(`[engine] engine access: error: ${r.error}`);
  return r;
});
ipcMain.handle("engine:engine-access-action", async (_e, action) => {
  const by = await signedInLogin();
  const r = await engineAccessAction(action, by);
  const a = action || {};
  console.log(`[engine] engine access ${a.op} ${a.login} as ${by || "(no login)"}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});
ipcMain.handle("engine:repo-action", async (_e, action) => {
  const by = await signedInLogin();
  const r = await repoAction(action, by);
  const a = action || {};
  console.log(`[engine] repo ${a.op} ${a.repo} as ${by || "(no login)"}: ${r.error ? `error: ${r.error}` : "ok"}`);
  return r;
});

// null when unset, so the selection the renderer remembered across a restart stands.
ipcMain.handle("app:initial-view", () => process.env.AGENT_ARMY_VIEW || null);

// A `frakworks://ticket/<owner>/<name>/<n>` link opened outside the app
// (src/app-link.js). macOS sends `open-url`, before `ready` when the link
// started the app, so the ticket waits here until the renderer takes it: on
// its mount, and again whenever main says `app:link`. One path for a cold
// start and a running app, and no link is lost to a window still loading.
let pendingLink = null;
ipcMain.handle("app:take-link", () => { const link = pendingLink; pendingLink = null; return link; });
app.on("open-url", (e, url) => {
  e.preventDefault();
  const ticket = parseAppLink(url);
  if (!ticket) { console.log(`[app] ignored link ${String(url).slice(0, 200)}: not frakworks://ticket/<owner>/<name>/<n>`); return; }
  pendingLink = ticket;
  if (!app.isReady()) return;
  const [win] = BrowserWindow.getAllWindows();
  if (!win) { createWindow(); return; }
  if (win.isMinimized()) win.restore();
  win.show(); win.focus();
  win.webContents.send("app:link");
});

// ---------------------------------------------------------------------------
// First mate (issue #100)
// ---------------------------------------------------------------------------
//
// One pane: a single, non-renameable, non-resettable session, docked on
// every screen from the renderer's shared shell. The renderer gets
// its firstMate:* channels and none of them is rename — see src/preload.js. Four of them
// (#231) read and set First mate's tier: tier numbers and a duration key in,
// validated in the manager against the engine's tier map; the model and effort are
// resolved here in main, per turn, never sent by the renderer.
let firstMate = null;

function registerFirstMateIpc(win) {
  firstMate = new FirstMateManager({
    dir: path.join(app.getPath("userData"), "first-mate"),
    command: CLAUDE_BIN.command,
    // The legacy cwd of a conversation from before fresh code (#960); a new
    // one runs in the selected repo's clone under FRESH_DIR.
    cwd: REPO_DIR,
    freshCode,
    freshDir: FRESH_DIR,
    checkoutDir: CHECKOUT_DIR,
    // Files in the repo fileTicket names (#577), from the card or the selection.
    submitIntakeFn: (ask, repo) => submitIntake(ask, undefined, repo ? { repo } : {}),
    repoFn: () => selectedRepo,
    // The account a Smith on that model would get, and its long-lived token
    // (engine/README.md, "First mate's account"). Main-process only.
    pickAccountFn: (model) => pickAccount(model),
    // The session's HITLP lineage (engine/README.md, "Lineage"), asked once per conversation with the signed-in token.
    lineageFn: (ask) => fetchLineage(ask),
    // The repo's own MCP servers (#1801), secrets opened, for frakworks-mcp.json (0600) only.
    mcpServersFn: (repo) => fetchRepoMcpConfig(repo),
    // A turn that finds no copy of the tier map reads it once more (src/engine-tiers.js).
    loadTiersFn: () => loadTiers(),
    configFile: configPath(app.getPath("userData")),
    // Where First mate's MCP server is (issue #541: the engine's
    // /mcp/frakworks) and the engine token (#2206), sent as the servers'
    // Authorization header from the 0600 config only: main to first-mate.js,
    // never the child's env or a log. The token names First mate's login.
    engineEnvFn: async () => ({
      ENGINE_URL: engineUrl(),
      ENGINE_TOKEN: engineToken.signedIn() ? engineToken.bearer().slice("Bearer ".length) : undefined,
    }),
    // Open in Claude Code (#820): whether iTerm2 is here, read once at
    // startup (the manager looks again on a click when it was absent).
    iterm: detectIterm(),
    // "Filed as #<n>" (#927, #971): the stream's `intake` frames, and one read
    // of each unannounced filing on every connect's hello. No IPC: both are main.
    engineEvents,
    // Where a new conversation runs (#2521), read as each one starts, and the
    // sign-in bearer for a session pod, main-only.
    hostingFn: () => fetchFirstMateHosting(),
    bearer: firstMateBearer,
  }).init();
  console.log(`[first-mate] open in iTerm2: ${firstMate.iterm.available ? firstMate.iterm.app : `unavailable — ${firstMate.iterm.why}`}`);
  const send = (channel, payload) => {
    if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
  };
  firstMate.on("update", (snap) => send("firstMate:update", snap));
  firstMate.on("stream", (chunk) => send("firstMate:stream", chunk));

  ipcMain.handle("firstMate:list", () => firstMate.snapshot());
  // The strip's terminal font (#366): the default iTerm2 profile's `Normal
  // Font`, read once from the defaults domain; `{ family, size, source }`,
  // with `family: null` meaning the CSS fallback stack. No arguments.
  let itermFont = null;
  ipcMain.handle("firstMate:font", () => (itermFont ||= readItermFont()));
  // The History control (#265): read-only projections of past conversations
  // Part 1 (#257) archives — a list of `{id, started_at, first_message}` and
  // one conversation's full messages. Plain reads, like `firstMate:list`
  // above; nothing here writes to the archive.
  ipcMain.handle("firstMate:conversations", () => firstMate.listConversations());
  ipcMain.handle("firstMate:conversation", (_e, id) => firstMate.getConversation(String(id)));
  // Issue #486: resume a past conversation, by id only. The manager archives
  // the live one and refuses an unknown or pre-#486 conversation.
  ipcMain.handle("firstMate:resume", (_e, id) => {
    const r = firstMate.resumeConversation(String(id));
    console.log(`[first-mate] resume conversation ${id}: ${r.ok ? "resumed" : `refused — ${r.error}`}`);
    return r;
  });
  // Issue #601: back to the previous conversation. No arguments: the manager
  // picks the newest past conversation with a session id, or refuses.
  ipcMain.handle("firstMate:pop", () => {
    const r = firstMate.popConversation();
    console.log(`[first-mate] back to previous conversation: ${r.ok ? "resumed" : `refused — ${r.error}`}`);
    return r;
  });
  // Images (#368) arrive as plain `{ media_type, data }` data; the manager
  // re-checks type, size and count and refuses anything else.
  ipcMain.handle("firstMate:send", async (_e, text, images) => {
    const r = await firstMate.send(String(text == null ? "" : text), images == null ? [] : images);
    console.log(`[first-mate] send: ${r.ok ? "answered" : `refused — ${r.error}`}`);
    return r;
  });
  ipcMain.handle("firstMate:cancel", () => firstMate.cancel());
  ipcMain.handle("firstMate:new", () => firstMate.startNewConversation());
  // The File button (acceptance criteria 6-8): a tier 1-10 in, an engine
  // POST /intake out, through src/engine-data.js's submitIntake — the same
  // unauthenticated engine-call path the other engine: handlers use.
  // `repo` is the card's pick in All repos, checked against GET /repos.
  ipcMain.handle("firstMate:file", async (_e, tier, repo) => {
    await ensureSelection();
    let target = null;
    if (typeof repo === "string" && repo) {
      const t = await targetRepo(repo);
      if (t.error) return { ok: false, error: t.error };
      target = t.repo;
    }
    const r = await firstMate.fileTicket(Number(tier), target);
    console.log(`[first-mate] file tier ${tier}: ${r.ok ? `intake ${r.intake_id}` : `refused — ${r.error}`}`);
    return r;
  });
  // First mate's tier (#231): the standing tier. Only a number arrives here;
  // the manager rejects a tier outside 1-10 or one the tier map cannot resolve.
  ipcMain.handle("firstMate:tier", () => firstMate.tierState());
  // The chat's repo (#2343): the chip in First mate's header. `null` is "No repo yet".
  ipcMain.handle("firstMate:set-repo", (_e, repo) => firstMate.setChatRepo(repo == null ? null : String(repo)));
  ipcMain.handle("firstMate:set-tier", (_e, tier) => {
    const r = firstMate.setStandingTier(Number(tier));
    console.log(`[first-mate] standing tier ${tier}: ${r.ok ? "saved" : `refused — ${r.error}`}`);
    return r;
  });
  // Saved chats (#598): the engine keeps them (GET/PUT/DELETE /saved-chats).
  // Save for later takes nothing from the renderer: the body is the live
  // conversation's, built by the manager. Whether a saved chat resumes here
  // is decided in main, from ~/.claude/projects (src/saved-chats.js).
  ipcMain.handle("engine:saved-chats", async () => {
    const r = await fetchSavedChats();
    if (r.error) console.log(`[engine] saved chats: error: ${r.error}`);
    return r;
  });
  ipcMain.handle("engine:saved-chat", async (_e, id) => {
    const r = await fetchSavedChat(String(id));
    if (r.error) { console.log(`[engine] saved chat ${id}: error: ${r.error}`); return r; }
    const chat = savedChatView(r.chat, savedChatCwd(r.chat));
    return { chat, error: null };
  });
  ipcMain.handle("engine:delete-saved-chat", async (_e, id) => {
    const r = await deleteSavedChat(String(id));
    console.log(`[engine] delete saved chat ${id}: ${r.ok ? "deleted" : `refused — ${r.error}`}`);
    if (r.ok) send("savedChats:changed", null);
    return r.ok ? { ok: true } : { ok: false, error: r.error };
  });
  ipcMain.handle("firstMate:save", async () => {
    const payload = firstMate.savePayload();
    if (!payload) return { ok: false, error: "there is nothing to save yet" };
    const r = await saveChat(payload.id, payload.body);
    console.log(`[first-mate] save for later ${payload.id}: ${r.ok ? (r.created ? "saved" : "updated") : `refused — ${r.error}`}`);
    if (!r.ok) return { ok: false, error: r.error };
    send("savedChats:changed", null);
    return { ok: true, created: r.created, id: payload.id };
  });
  // Open in Claude Code ⇗ (#820): no arguments. The manager decides resume or
  // seed from its own session, writes the launcher under userData and asks
  // iTerm2 through osascript; which `claude` and which directory are main's.
  ipcMain.handle("firstMate:open-in-iterm", async () => {
    const r = await firstMate.openInIterm();
    console.log(`[first-mate] open in Claude Code in iTerm2: ${r.ok ? `opened (${r.mode})` : `refused — ${r.error}`}`);
    return r;
  });
  ipcMain.handle("firstMate:restore-saved", async (_e, id) => {
    const r = await fetchSavedChat(String(id));
    if (r.error) return { ok: false, error: r.error };
    const cwd = savedChatCwd(r.chat);
    const { resumable } = resumability(r.chat, cwd);
    const done = firstMate.restoreSavedChat(r.chat, { resumable, cwd });
    console.log(`[first-mate] restore saved chat ${id}: ${done.ok ? (done.resumed ? "resumes its session" : "fresh session, seeded") : `refused — ${done.error}`}`);
    return done;
  });
  // Continue in terminal (#819):
  // the live conversation (no arguments) or a saved chat (its id only). Main
  // decides resume or seed, writes the hand-off file on the seeded path and
  // puts the command on the clipboard; the renderer sends no path, command
  // or transcript, and nothing reads the terminal session back.
  ipcMain.handle("firstMate:handoff", () => {
    const chat = firstMate.handoffPayload();
    if (!chat) return { ok: false, error: firstMate.rt.busy ? "wait for the reply to finish" : "there is nothing to hand off yet" };
    return handOff("live conversation", chat);
  });
  ipcMain.handle("savedChat:handoff", async (_e, id) => {
    const r = await fetchSavedChat(String(id));
    if (r.error) return { ok: false, error: r.error };
    return handOff(`saved chat ${id}`, r.chat);
  });

  // Saved Chats > Claude Code (#1708): the local Claude Code sessions under
  // ~/.claude/projects, read only. The renderer names a session by its id
  // alone; main maps it to the file from its own last listing. A debounced
  // watch pushes `claudeSessions:changed` when a session file is created or
  // appended.
  const ccCache = new Map();
  let ccRows = [];
  const ccList = () => {
    ccRows = claudeCode.listSessions({ cache: ccCache });
    const b = claudeCode.bucket(ccRows, new Date());
    return { recent15: b.recent15.map(claudeCode.publicRow), today: b.today.map(claudeCode.publicRow), lastMonth: b.lastMonth.map(claudeCode.publicRow),
      byDirectory: b.byDirectory.map((g) => ({ ...g, sessions: g.sessions.map(claudeCode.publicRow) })) };
  };
  const ccFind = (id) => ccRows.find((r) => r.id === String(id)) || (ccList(), ccRows.find((r) => r.id === String(id)));
  ipcMain.handle("claudeSessions:list", () => {
    try { return ccList(); } catch (e) { return { error: e.message, recent15: [], today: [], lastMonth: [] }; }
  });
  ipcMain.handle("claudeSessions:read", (_e, id) => {
    const row = ccFind(id);
    if (!row) return { error: `no Claude Code session ${id}` };
    // `iterm`: whether Continue in terminal opens iTerm2 (#1937), for its title.
    const iterm = Boolean(row.cwd && detectIterm().available);
    try { return { session: { ...claudeCode.publicRow(row), ...claudeCode.readTranscript(row.file), iterm } }; }
    catch (e) { return { error: e.message }; }
  });
  ipcMain.handle("claudeSessions:handoff", (_e, id) => {
    const row = ccFind(id);
    if (!row) return { ok: false, error: `no Claude Code session ${id}` };
    return claudeCode.handoff(row, { dir: path.join(app.getPath("userData"), "first-mate", HANDOFF_DIR_NAME),
      claude: firstMate ? firstMate.command : "claude", copy: (t) => clipboard.writeText(t) });
  });
  const ccWatch = claudeCode.createSessionWatcher({ onChange: () => send("claudeSessions:changed", null) });
  app.on("before-quit", () => ccWatch.close());
}

// One press of Continue in terminal: `{ ok, command, mode }` or `{ ok: false,
// error }`. The hand-off file is `<userData>/first-mate/handoff/<id>.md`,
// rewritten on every press and kept at 0600 (the chmod covers a rewrite of a
// file that already existed, which writeFileSync's mode does not).
function handOff(what, chat) {
  try {
    // The `cd` is the conversation's cwd (#960): the live one's stored cwd
    // (`handoffPayload` carries it), a saved chat's the clone its session is in.
    const cwd = chat.cwd || savedChatCwd(chat);
    const { resumable } = resumability(chat, cwd);
    const plan = handoffPlan(chat, { cwd, dir: path.join(app.getPath("userData"), "first-mate", "handoff"), resumable });
    if (plan.file) {
      fs.mkdirSync(path.dirname(plan.file), { recursive: true });
      fs.writeFileSync(plan.file, plan.text, { mode: 0o600 });
      fs.chmodSync(plan.file, 0o600);
    }
    clipboard.writeText(plan.command);
    console.log(`[first-mate] continue in terminal, ${what}: ${plan.mode === "resume" ? "resume a fork" : `seeded from ${plan.file}`}`);
    return { ok: true, command: plan.command, mode: plan.mode };
  } catch (e) {
    console.log(`[first-mate] continue in terminal, ${what}: refused — ${e.message}`);
    errorLog.append({ source: "main", error: e, context: "[first-mate]" });
    return { ok: false, error: e.message };
  }
}

// ---------------------------------------------------------------------------
// Grafana public dashboards under Analytics (issue #332)
// ---------------------------------------------------------------------------
//
// Read once from agent-army.config.json by src/grafana-config.js, apart from
// First mate's tier, so a missing or broken Grafana block costs these rows and
// nothing else. The renderer names a dashboard by its index in this list and
// never by URL: every URL here, and the origin the page's CSP allows in
// `frame-src`, comes from the config file in main.
let grafana = null;
const grafanaConfig = () => {
  if (!grafana) {
    grafana = loadGrafana({ file: configPath(app.getPath("userData")) });
    const good = grafana.dashboards.filter((d) => d.url).length;
    console.log(`[grafana] ${grafana.error ? `not configured: ${grafana.error}`
      : `${good} dashboard(s)${grafana.origin ? ` on ${grafana.origin}` : ""}` +
        (good < grafana.dashboards.length ? `, ${grafana.dashboards.length - good} malformed` : "")}`);
  }
  return grafana;
};
ipcMain.handle("grafana:dashboards", () => {
  const g = grafanaConfig();
  return { error: g.error, dashboards: g.dashboards.map((d) => ({ label: d.label || null, url: d.url || null, error: d.error || null, builds: d.builds === true })) };
});
// The builds in progress for the Build gate strip (#1389): GET /builds/active.
ipcMain.handle("builds:active", () => fetchActiveBuilds());
ipcMain.handle("grafana:probe", async (_e, index) => {
  const d = grafanaConfig().dashboards[Number(index)];
  if (!d || !d.url) return { ok: false, error: (d && d.error) || `no Grafana dashboard ${Number(index)} is configured` };
  const r = await probe(d.url);
  if (!r.ok) console.log(`[grafana] ${d.label}: ${r.error}`);
  return { ...r, url: d.url };
});

// Links leave the app for the system browser, and only http(s) ones: a
// framed Grafana page is unauthenticated plain http on the LAN, and whoever
// can tamper with it must not get the OS to open `file:` or custom schemes.
const openOutside = (url) => {
  if (isExternalUrl(url)) shell.openExternal(url);
  else console.log(`[app] refused to open ${String(url).slice(0, 200)}: only http and https leave the app`);
};

function createWindow() {
  const win = new BrowserWindow({
    width: 1100, height: 960, minWidth: 820,
    // The title strip (src/renderer/title-bar.jsx) draws the repo switcher
    // where the title was, right of the traffic lights (#577). macOS only.
    title: repoSelection.titleFor(selectedRepo),
    titleBarStyle: "hiddenInset",
    icon: APP_ICON,
    backgroundColor: "#14161b",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  // Ticket links open in the browser, not in the app window.
  watchRenderer(win.webContents, "window");
  win.webContents.setWindowOpenHandler(({ url }) => { openOutside(url); return { action: "deny" }; });
  win.webContents.on("will-navigate", (e, url) => { e.preventDefault(); openOutside(url); });
  // A pitch frame (src/renderer/pitch.jsx) is an `about:srcdoc` frame whose
  // links open as popups (its `<base target="_blank">`, so the handler above);
  // a link that would still load in the frame goes to the browser too.
  win.webContents.on("will-frame-navigate", (e) => {
    if (e.isMainFrame || !e.frame || e.frame.url !== "about:srcdoc" || String(e.url).startsWith("about:srcdoc")) return;
    e.preventDefault();
    openOutside(e.url);
  });
  // With a Grafana origin configured, a copy of index.html whose CSP allows
  // exactly that origin in `frame-src` (src/grafana-config.js framePage).
  let page = path.join(__dirname, "renderer", "index.html");
  try {
    page = pageToLoad({ rendererDir: path.join(__dirname, "renderer"), origin: grafanaConfig().origin,
      outDir: path.join(app.getPath("userData"), "renderer-page") });
  } catch (e) {
    console.log(`[grafana] could not write the framing page, dashboards will not load: ${e.message}`);
    errorLog.append({ source: "main", error: e, context: "[grafana]" });
  }
  win.loadFile(page);
  // Closing the window while an engine update is in flight asks first (#1053).
  let closeConfirmed = false;
  win.on("close", (e) => {
    if (closeConfirmed || quitGuard.quitting() || !quitGuard.needed()) return;
    e.preventDefault();
    quitGuard.confirm("close", win).then((ok) => { if (ok && !win.isDestroyed()) {
      closeConfirmed = true;
      // Where closing the last window quits, the user has already said Quit anyway.
      if (process.platform !== "darwin") quitGuard.markQuitting();
      win.close();
    } });
  });
  selectionWin = win;
  showSelection();
  addFindBar(win);
  return win;
}

// ---------------------------------------------------------------------------
// Find in page (#703). Edit > Find (CmdOrCtrl+F, src/app-menu.js) shows the
// find bar, src/renderer/find.jsx, over the window's top right. The bar is its
// own WebContentsView, not an element in the page: findInPage matched an
// in-page bar's own input and took its focus and caret on every search, so
// keys typed fast were lost. Its webPreferences are the window's.
//
// The bar calls `find:start` (text, { forward, findNext }) and `find:stop`
// through window.app (src/preload.js); main runs findInPage / stopFindInPage
// on the window's page, which searches every frame in it, the pitch's
// sandboxed srcdoc frame too. It sends the bar `find:update`: `{ open: true }`
// on Cmd-F, and `{ active, matches }` from each found-in-page. Only the bar's
// own webContents may drive it.
// ---------------------------------------------------------------------------
const FIND_BAR = { width: 400, height: 40, right: 16, top: 36 };
const FIND_TEXT_MAX = 500;
const findBars = new Map(); // window's webContents id -> { win, view, ready }

function addFindBar(win) {
  const view = new WebContentsView({
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  const bar = { win, view, ready: false, pending: [] };
  const id = win.webContents.id;
  findBars.set(id, bar);
  view.setBackgroundColor("#00000000");
  view.setVisible(false);
  win.contentView.addChildView(view);
  watchRenderer(view.webContents, "find bar");
  view.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  view.webContents.on("will-navigate", (e) => e.preventDefault());
  view.webContents.on("did-finish-load", () => {
    bar.ready = true;
    for (const u of bar.pending.splice(0)) view.webContents.send("find:update", u);
  });
  view.webContents.loadFile(path.join(__dirname, "renderer", "find.html"));
  const place = () => {
    if (win.isDestroyed()) return;
    const [w] = win.getContentSize();
    view.setBounds({ x: Math.max(0, w - FIND_BAR.width - FIND_BAR.right), y: FIND_BAR.top,
      width: Math.min(FIND_BAR.width, w), height: FIND_BAR.height });
  };
  place();
  for (const ev of ["resize", "maximize", "unmaximize", "enter-full-screen", "leave-full-screen"]) win.on(ev, place);
  win.webContents.on("found-in-page", (_e, r) => sendFind(bar, { active: r.activeMatchOrdinal, matches: r.matches }));
  win.on("closed", () => {
    findBars.delete(id);
    if (!view.webContents.isDestroyed()) view.webContents.close();
  });
}

function sendFind(bar, update) {
  if (bar.view.webContents.isDestroyed()) return;
  if (bar.ready) bar.view.webContents.send("find:update", update);
  else bar.pending.push(update);
}

function openFind(win) {
  const bar = win && !win.isDestroyed() && findBars.get(win.webContents.id);
  if (!bar) return;
  bar.view.setVisible(true);
  bar.view.webContents.focus();
  sendFind(bar, { open: true });
}

// The bar that sent this, or null: the page itself cannot drive find.
const findBarOf = (sender) => [...findBars.values()].find((b) => b.view.webContents === sender) || null;

ipcMain.handle("find:start", (e, text, opts) => {
  const bar = findBarOf(e.sender);
  if (!bar || bar.win.isDestroyed()) return { error: "no find bar" };
  const t = String(text == null ? "" : text).slice(0, FIND_TEXT_MAX);
  if (!t) { bar.win.webContents.stopFindInPage("clearSelection"); return { requestId: null }; }
  const o = opts && typeof opts === "object" ? opts : {};
  return { requestId: bar.win.webContents.findInPage(t, { forward: o.forward !== false, findNext: o.findNext === true }) };
});
ipcMain.handle("find:stop", (e) => {
  const bar = findBarOf(e.sender);
  if (!bar || bar.win.isDestroyed()) return { error: "no find bar" };
  bar.win.webContents.stopFindInPage("clearSelection");
  bar.view.setVisible(false);
  bar.win.webContents.focus();
  return { ok: true };
});

// ---------------------------------------------------------------------------
// The status bar: the commit the app started on against the checkout's HEAD
// now (local git, every poll) and against origin/main (fetched every FETCH_MS,
// src/app-version.js), and the one write the app makes: a fast-forward pull
// when the checkout is behind origin/main, a rebuild, then a relaunch.
// ---------------------------------------------------------------------------
let repoDir = null;
let running = null;   // HEAD when this process started: the code that is running
let lastCmp = null;
let lastFetch = null; // { at } or { error }: the last `git fetch origin main`
let fetching = false;
let pullFailedAt = null; // origin/main's sha when a pull last failed: the next Restart skips the pull
const ensureRepoDir = async () => {
  if (repoDir === null) repoDir = (await appVersion.repoRoot()) || "";
  return repoDir;
};
async function fetchOrigin() {
  if (fetching || !(await ensureRepoDir())) return;
  fetching = true;
  try {
    lastFetch = await appVersion.fetchOrigin({ repoDir });
    if (lastFetch.error) console.log(`[app] fetch origin/main failed: ${lastFetch.error}`);
  } finally { fetching = false; }
}
// The packaged build (src/app-update.js): no checkout, no git; its version is
// package.json's and "newer" is what the feed says. `updater` is null in a
// checkout, where the git flow below runs as before.
// The registry, the release feed and the collector's URLs are this deployment's (src/deployment-config.js).
const deployment = loadDeployment({ file: configPath(app.getPath("userData")) });
for (const e of deployment.errors) console.log(`[config] ${e}`);
console.log(`[config] registry ${deployment.registry || "(default localhost:5000)"}, release feed ${deployment.releaseFeed || "(build default)"}`);
const updater = app.isPackaged
  ? appUpdate.createUpdater({ autoUpdater: require("electron-updater").autoUpdater, log: (m) => console.log(`[app] ${m}`),
      commit: require("../package.json").commit || null,
      ...(deployment.releaseFeed ? { feed: deployment.releaseFeed } : {}) })
  : null;
async function version() {
  if (updater) return { packaged: true, version: app.getVersion(), update: updater.state(), engineUrl: engineUrl() };
  await ensureRepoDir();
  const v = await appVersion.readVersion({ repoDir });
  if (v.error) return { ...v, engineUrl: engineUrl() };
  if (running === null) running = v.sha;
  lastCmp = await appVersion.compareRunning({ repoDir, running, head: v.sha, remote: true,
    fetchError: lastFetch && lastFetch.error ? lastFetch.error : null });
  return { ...v, running, cmp: lastCmp, engineUrl: engineUrl() };
}
ipcMain.handle("app:version", () => version());
// An engine update in flight asks first (#1053): it runs on without the app.
// Once the user said Quit anyway, the quit this starts (quitAndInstall's) is
// not asked again in before-quit (quitGuard.restart).
// Restart with a downloaded desktop update first opens the update preview
// modal (#100091): `app:update-preview` answers its model, the running and
// incoming versions and commits (src/app-update.js's stamp) and the engine's
// list between them; null when nothing is downloaded (Restart goes straight on).
ipcMain.handle("app:update-preview", async () => {
  const u = updater ? updater.state() : null;
  if (!u || !u.downloaded) return null;
  const list = await updatePreview.withCap(listChangesFor({ running: u.commit, available: u.downloaded.commit,
    noRunning: "this build was not stamped with its commit", noAvailable: "the incoming build was not stamped with its commit" }));
  return updatePreview.previewModel({ kind: "desktop", running: { version: app.getVersion() }, incoming: u.downloaded, list });
});
// The modal's blur (#100091): one capture of the window, blurred once in the
// renderer, so nothing re-blurs while a card animates behind it (#1110).
ipcMain.handle("app:snapshot", async (e) => {
  const w = e && BrowserWindow.fromWebContents(e.sender);
  if (!w || w.isDestroyed()) return null;
  try { return (await w.webContents.capturePage()).toDataURL(); } catch (err) { return null; }
});
ipcMain.handle("app:restart", (e) => quitGuard.restart(e && BrowserWindow.fromWebContents(e.sender), restartApp));
async function restartApp() {
  if (updater) {
    // Packaged: install what is downloaded and relaunch; with nothing ready,
    // ask the feed once more, then relaunch the app as it is.
    return appUpdate.restartPackaged({ updater, app });
  }
  const v = await version();
  const origin = lastCmp && lastCmp.origin && !lastCmp.origin.error ? lastCmp.origin : null;
  // Behind origin/main: fast-forward first — unless that already failed for
  // this origin/main, when a second click rebuilds what is on disk instead.
  const doPull = !!(origin && origin.behind > 0 && origin.sha !== pullFailedAt);
  console.log(`[app] restart: checkout ${v.short || "?"}, running ${(running || "?").slice(0, 7)}${v.dirty ? " (dirty)" : ""}` +
    (origin && origin.behind ? `, behind origin/main ${origin.behind}${doPull ? ", pulling" : ", not pulling (failed before)"}` : ""));
  const r = await appVersion.rebuild({ appDir: path.join(__dirname, ".."), lockMoved: !!(lastCmp && lastCmp.lockMoved),
    pull: doPull ? { repoDir, running } : null });
  for (const s of r.steps) console.log(`[app]   ${s.ok ? "ok " : "FAILED"} ${s.step}${s.ok ? "" : `: ${s.detail}`}`);
  const failedPull = r.steps.find((s) => s.step === "pull" && !s.ok);
  if (failedPull) {
    pullFailedAt = origin.sha;
    failedPull.detail += "\nClick Restart again to rebuild what is on disk and relaunch, without pulling.";
  }
  if (!r.ok) return r;
  app.relaunch();
  app.exit(0);
  return r;
}

// ---------------------------------------------------------------------------
// The status bar's Update engine button (src/engine-update.js): the running
// engine's build against the registry's frakworks-engine:main, checked every
// CHECK_MS in both the checkout and the packaged build. The interval only
// checks; the pull and the recreate run from `engine:update`, a click, and
// only after the confirm below says yes. They run in a detached runner with
// its log and status file in userData, so an app that quits or relaunches
// mid-update leaves it going and picks it up again (#1040). What no click
// awaits (a resumed update's end, an engine left in Created and started) is
// pushed to the windows as `engine:update-result`.
// ---------------------------------------------------------------------------
// What an update brings (#1398, #100091): git in the checkout the app runs
// from gives the range, the engine's GET /repos/{owner}/{name}/changes the
// ticket titles; a packaged build, with no checkout, takes the whole list
// from the engine.
async function listChangesFor({ running, available, ...why }) {
  const dir = app.isPackaged ? null : await ensureRepoDir();
  const git = dir ? (args) => engineUpdate.execRun("git", args, { cwd: dir, timeoutMs: 30000, env: { ...process.env, GIT_TERMINAL_PROMPT: "0" } })
    .then((r) => { if (r.error || r.code !== 0) throw new Error(r.error || (r.stderr || "").trim() || `exit ${r.code}`); return r.stdout; }) : null;
  const remote = (from, to) => fetchChanges(updateChanges.OWNER_REPO, from, to);
  return updateChanges.listChanges({ running, available, git, remote, ...why });
}
const DOCKER_BIN = resolveDockerBin();
console.log(`[engine] docker: ${DOCKER_BIN.command} (${DOCKER_BIN.source})`);
const engineUpdater = engineUpdate.createEngineUpdater({
  run: engineUpdate.execRun, engineUrl: engineUrl(), docker: DOCKER_BIN.command,
  dir: app.getPath("userData"), auth: () => authHeaders(),
  log: (m) => console.log(`[engine] ${m}`),
  onResult: (r) => {
    for (const s of r.steps || []) console.log(`[engine]   ${s.ok ? "ok " : "FAILED"} ${s.step}${s.ok ? "" : `: ${s.detail}`}`);
    if (progress) progress.result(r);
    for (const w of BrowserWindow.getAllWindows()) w.webContents.send("engine:update-result", r);
  },
  // A relaunch took up an update in flight: the overlay opens at the status file's step.
  // What the update brings: git in the checkout the app runs from (#1398).
  changes: ({ running, available }) => listChangesFor({ running, available }),
  onResume: (s) => startProgress({ resumed: true, since: Date.parse(s.startedAt) || Date.now(), project: s.project }),
  ...(deployment.registry ? { registry: deployment.registry } : {}),
});

// ---------------------------------------------------------------------------
// The Update engine overlay (#1053, src/engine-update-progress.js): from the
// click (after the confirm) or a relaunch's resume, main follows the update's
// sources (the runner's log and status file, `docker events`, `docker logs
// -f`, one /version and one /metrics read on their events) and pushes the
// whole progress to the windows as `engine:update-progress` on every change;
// the renderer never asks on a timer. `engine:update-progress-state` is the
// one read a window makes when it loads; `engine:update-progress-close` is
// the overlay's Close, taken only once the update has ended. While an update
// is in flight, Cmd+Q, closing the window and Restart ask first (quitGuard).
// ---------------------------------------------------------------------------
const USER_DATA = app.getPath("userData");
let progress = null;
const sendProgress = (p) => { for (const w of BrowserWindow.getAllWindows()) w.webContents.send("engine:update-progress", p); };
function startProgress({ resumed = false, since = Date.now(), project = null, before = null }) {
  if (progress) return progress;
  const view = before ? { before, since, project } : updateProgress.readView(USER_DATA) || {};
  if (before) updateProgress.writeView(USER_DATA, view);
  const st = engineUpdater.state();
  progress = updateProgress.createUpdateProgress({
    dir: USER_DATA, docker: DOCKER_BIN.command, project: project || view.project || null,
    since: view.since || since, resumed,
    before: view.before || { available: st.available, running: st.running, liveRuns: st.liveRuns, changes: st.changes },
    verify: () => engineUpdater.verify(), metrics: () => engineUpdater.liveRuns(),
    log: (m) => console.log(`[engine] ${m}`),
    onChange: (p) => {
      if (p.ended) { updateProgress.clearView(USER_DATA); console.log(`[engine] update overlay: ${p.ended.text}${p.ended.detail ? `: ${p.ended.detail}` : ""}`); }
      sendProgress(p);
    },
  });
  console.log(`[engine] update overlay: following the update${resumed ? " (resumed)" : ""}`);
  sendProgress(progress.state());
  return progress;
}
const updateInFlight = () => !!(engineUpdater.state().updating || (progress && progress.state().inFlight));
const quitGuard = updateProgress.createQuitGuard({
  inFlight: updateInFlight,
  ask: (opts, win) => (win && !win.isDestroyed() ? dialog.showMessageBox(win, opts) : dialog.showMessageBox(opts)),
});
ipcMain.handle("engine:update-progress-state", () => (progress ? progress.state() : null));
ipcMain.handle("engine:update-progress-close", () => {
  if (progress && progress.state().inFlight) return { error: "the update is still running" };
  if (progress) progress.close();
  progress = null;
  sendProgress(null);
  return { ok: true };
});
ipcMain.handle("engine:update-state", () => engineUpdater.state());
// The click (#100091): `engine:update-preview` checks again and, when the
// button is lit, answers the update preview modal's model
// (src/update-preview.js) with what the update brings, read within
// PREVIEW_MS or said missing; the modal's Update is `engine:update-run`.
ipcMain.handle("engine:update-preview", async () => {
  // An update in flight (this app's, or one a relaunch resumed) is refused
  // with its step, before the check that would only find nothing to light.
  if (engineUpdater.state().updating) return { result: await engineUpdater.update() };
  const st = await engineUpdater.check();
  if (!engineUpdate.describeEngineUpdate(st).lit) return { checked: true };
  const incoming = st.remote ? st.engine.latest : st.available;
  const list = await updatePreview.withCap(listChangesFor({ running: st.running && st.running.commit, available: incoming && incoming.commit }));
  return { preview: updatePreview.previewModel({ kind: "engine", running: st.running, incoming, behind: st.engine ? st.engine.behind : null,
    list, liveRuns: st.liveRuns, liveRunsError: st.liveRunsError, sessions: st.engine ? st.engine.openSessions : null, remote: !!st.remote }) };
});
ipcMain.handle("engine:update-run", async () => {
  if (engineUpdater.state().updating) return engineUpdater.update();
  const st = engineUpdater.state();
  if (!engineUpdate.describeEngineUpdate(st).lit) return { checked: true };
  // An engine not on this machine deploys itself (POST /engine/update); the
  // overlay follows docker, so it is not opened for one.
  if (st.remote) {
    console.log(`[engine] deploy: ${st.running && st.running.commit} -> ${st.engine.latest.commit}, ${st.liveRuns == null ? "?" : st.liveRuns} live run(s)`);
    const r = await engineUpdater.update();
    for (const s of r.steps) console.log(`[engine]   ${s.ok ? "ok " : "FAILED"} ${s.step}${s.ok ? "" : `: ${s.detail}`}`);
    return r;
  }
  console.log(`[engine] update: ${st.running.digest} -> ${st.available.digest}, ${st.liveRuns == null ? "?" : st.liveRuns} live run(s)`);
  const since = Date.now();
  const pending = engineUpdater.update();
  // update() spawns the runner before its first await: `updating` says it started.
  if (engineUpdater.state().updating) {
    if (progress) { progress.close(); progress = null; }
    startProgress({ since, project: st.running.project && st.running.project.name,
      before: { available: st.available, running: { commit: st.running.commit, digest: st.running.digest }, liveRuns: st.liveRuns, changes: st.changes } });
  }
  const r = await pending;
  if (progress) progress.result(r);
  for (const s of r.steps) console.log(`[engine]   ${s.ok ? "ok " : "FAILED"} ${s.step}${s.ok ? "" : `: ${s.detail}`}`);
  return r;
});

// ---------------------------------------------------------------------------
// Config > frakworks > .env (#912, src/env-lines.js): the deployment's `.env`
// keys that `.env.example` does not declare. The `.env` is the checkout's in a
// checkout run, else the one in the engine's compose project directory, which
// the Update engine button already reads off the container. Only key names
// cross IPC. `env:remove` takes keys, never a path: it refuses any key the
// last check did not report unused, asks in a native confirm naming the file
// and the keys, then rewrites the file in place.
// ---------------------------------------------------------------------------
let lastEnvCheck = null;
const envDir = async () => {
  if (!app.isPackaged) return envLines.projectDir({ packaged: false, repoDir: REPO_DIR });
  let st = engineUpdater.state();
  if (!st.checkedAt) st = await engineUpdater.check();
  return envLines.projectDir({ packaged: true, state: st });
};
const runEnvCheck = async () => {
  let r;
  try { r = envLines.checkEnv(await envDir()); } catch (e) {
    r = { error: (e && e.message) || String(e) };
    errorLog.append({ source: "main", error: e, context: "env:check" });
  }
  lastEnvCheck = r.error ? null : r;
  console.log(`[env] check: ${r.error ? `error: ${r.error}` : `${r.file}: ${r.unused.length} unused, ${r.missing.length} missing`}`);
  return r;
};
ipcMain.handle("env:check", () => runEnvCheck());
ipcMain.handle("env:remove", async (e, keys) => {
  const last = lastEnvCheck;
  if (!last) return { error: "no check to remove from; check .env first" };
  const list = Array.isArray(keys) ? keys.map(String) : [];
  // Refuse before asking: the confirm only ever names keys that may go.
  const pre = envLines.checkRemoval(list, last, []);
  if (pre.error) return { error: pre.error };
  const c = envLines.confirmText(last.file, pre.keys);
  const win = BrowserWindow.fromWebContents(e.sender);
  const { response } = await dialog.showMessageBox(win, {
    type: "warning", buttons: ["Remove", "Cancel"], defaultId: 1, cancelId: 1,
    message: c.message, detail: c.detail,
  });
  if (response !== 0) return { cancelled: true };
  const r = envLines.removeEnv({ keys: pre.keys, last });
  if (r.error) { console.log(`[env] remove: error: ${r.error}`); return { error: r.error }; }
  console.log(`[env] removed ${r.removed} line(s) from ${last.file}: ${pre.keys.join(" ")}`);
  lastEnvCheck = r.check.error ? null : r.check;
  return { removed: r.removed, check: r.check };
});

app.whenReady().then(() => {
  // A missing icon costs the dock icon, not the window: setIcon throws, and
  // before the window exists that left a running app with nothing on screen.
  if (process.platform === "darwin" && !app.isPackaged) {
    try { app.dock.setIcon(APP_ICON); } catch (e) {
      console.log(`[app] no dock icon: ${e.message}`);
      errorLog.append({ source: "main", error: e, context: "[app]" });
    }
  }
  Menu.setApplicationMenu(Menu.buildFromTemplate(appMenuTemplate(process.platform, {
    onFind: (_item, bw) => openFind(bw || BrowserWindow.getFocusedWindow() || selectionWin),
    onShowErrorLog: () => { errorLog.ensure(); shell.showItemInFolder(errorLog.file); },
  })));
  // The saved engine token, before the first engine read (safeStorage needs the app ready).
  hitlpSurface.load();
  resubscribeSurface();
  teachToken.load();
  fs.rm(path.join(app.getPath("userData"), "first-mate-token.bin"), { force: true }, () => {});
  engineToken.load();
  const win = createWindow();
  screenshotAllowed = screenshot.load(screenshotFile());
  desktopChannel.start();
  app.on("browser-window-focus", () => desktopChannel.report({ focused: true }));
  ensureSelection();
  if (updater) { updater.check(); setInterval(() => updater.check(), appUpdate.CHECK_MS); }
  else { fetchOrigin(); setInterval(fetchOrigin, appVersion.FETCH_MS); }
  engineUpdater.check(); setInterval(() => engineUpdater.check(), engineUpdate.CHECK_MS);
  registerFirstMateIpc(win);
  ipcMain.on("fleet:rendered", async (_e, state) => {
    console.log(`[fleet] rendered: ${state.kind}${state.message ? ` — ${state.message}` : ""}`);
    if (!ONCE) return;
    // The transcript pane fills from its own read, which resolves after
    // the ticket document does — so photographing the detail view the instant
    // it reports would catch the pane still saying "loading transcript…".
    // AGENT_ARMY_ONCE_DELAY_MS buys it that read (#1283).
    const settle = Number(process.env.AGENT_ARMY_ONCE_DELAY_MS || 0);
    if (settle > 0) await new Promise((r) => setTimeout(r, settle));
    if (process.env.AGENT_ARMY_SCREENSHOT) {
      const img = await win.webContents.capturePage();
      fs.writeFileSync(process.env.AGENT_ARMY_SCREENSHOT, img.toPNG());
      console.log(`[fleet] screenshot: ${process.env.AGENT_ARMY_SCREENSHOT}`);
    }
    app.quit();
  });
  app.on("activate", () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

// First mate's child is the only child process that outlives a request, so it
// is the only one that needs killing on the way out. Its metadata is already
// on disk after every turn. The engine update's runner is detached and is
// left alone: quitting mid-update leaves it running, and the next launch
// resumes it from its status file (src/engine-update.js).
// While an update is in flight, quitting asks first (quitGuard): Stay cancels
// the quit; Quit anyway proceeds as before, and the runner is left alone.
app.on("before-quit", (e) => {
  if (!quitGuard.beforeQuit(e, BrowserWindow.getFocusedWindow() || selectionWin, () => app.quit())) return;
  if (progress) progress.close();
  if (firstMate) firstMate.stop(); freshCode.close(); engineEvents.unsubscribe(FLEET); engineEvents.close();
});

app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit(); });
