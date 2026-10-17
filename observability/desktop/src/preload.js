// preload.js — the only bridge between the renderer and the main process.
// contextIsolation is on and nodeIntegration is off, so the renderer sees
// only JSON-returning methods:
// window.fleet.fetchTicket(repo, n) for ticket detail, window.fleet.fetchTranscript(n,
// since) for the live transcript pane (src/transcript-data.js), and
// window.fleet.needsYou() for the prioritized queue. None hands the renderer a
// module, path, process, credential, or write operation.
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("fleet", {
  // The renderer's uncaught errors (#985): `{ message, stack, context }`, main
  // writes it to <userData>/errors.log as `source: "renderer"`. Write-only, one way.
  reportError: (e) => ipcRenderer.send("log:error", {
    message: e && e.message != null ? String(e.message) : String(e),
    stack: e && e.stack != null ? String(e.stack) : null,
    context: e && e.context != null ? String(e.context) : null,
  }),
  fetchTicket: (repo, ticket) => ipcRenderer.invoke("ticket:fetch", String(repo), Number(ticket)),
  // `since` is the cursor from the previous read — a `_time` string, never a
  // query. The renderer cannot ask VictoriaLogs anything of its own.
  // `run` (optional) is the live run a `session` `started` frame named,
  // `{ filename, host }`: main then skips the gauge and the session query.
  fetchTranscript: (ticket, since, run) => ipcRenderer.invoke("transcript:fetch", ticket, since,
    run && typeof run === "object" ? { filename: run.filename == null ? null : String(run.filename), host: run.host == null ? null : String(run.host) } : null),
  // The live tail of a run's transcript (src/transcript-tail.js): `token` is
  // the renderer's name for one tail, `run` the `{ filename, host }` a
  // `session` `started` named, `since` the cursor; main builds the query.
  // `onTranscriptTail` hears every tail's pushed frames, `{ token, events,
  // cursor }` or `{ token, state, error }`, and returns an unsubscribe.
  openTranscriptTail: (token, ticket, run, since) => ipcRenderer.invoke("transcript:tail-open", String(token), Number(ticket),
    run && typeof run === "object" ? { filename: run.filename == null ? null : String(run.filename), host: run.host == null ? null : String(run.host) } : null,
    since == null ? null : String(since)),
  endTranscriptTail: (token) => ipcRenderer.invoke("transcript:tail-end", String(token)),
  closeTranscriptTail: (token) => ipcRenderer.invoke("transcript:tail-close", String(token)),
  onTranscriptTail: (cb) => {
    const h = (_e, frame) => cb(frame);
    ipcRenderer.on("transcript:tail", h);
    return () => ipcRenderer.removeListener("transcript:tail", h);
  },
  // One doctor run's transcript: the ticket and the run's `smithRunId` from
  // doctorHistory, which name its one log file; no query of the renderer's.
  doctorTranscript: (ticket, runId) => ipcRenderer.invoke("transcript:doctor", Number(ticket), String(runId)),
  // AGENT_ARMY_TICKET=<owner/name#n> (or <n>, in the selected repo) opens
  // straight onto that ticket's detail view. A
  // value, not a method: the renderer has no env of its own, and this is how
  // a headless run (AGENT_ARMY_ONCE=1) can photograph the detail view.
  startTicket: process.env.AGENT_ARMY_TICKET || null,
  // AGENT_ARMY_ISSUE=<owner/repo>#<n> opens the Issues board's detail of that
  // issue, the same way.
  startIssue: process.env.AGENT_ARMY_ISSUE || null,
  // The "needs you" rows (stage 3a): main runs `gh` and ranks; the renderer
  // only draws. Read-only — there is no write verb on this bridge.
  needsYou: () => ipcRenderer.invoke("needs:fetch"),
  // The HITLP server's tasks, `{ open, closed, error, notSet, host }`: HITLP
  // Requests, Closed, the explorer badge and the urgent popup share this one read.
  hitlpTasks: () => ipcRenderer.invoke("hitlp:tasks"),
  // The logged-in GitHub login main resolved (or null). Read-only: writes still name their login in main.
  login: () => ipcRenderer.invoke("login:get"),
  // Config > frakworks > Sign in to the engine (#2206): the token goes inward
  // once and is kept by main, encrypted at rest; the renderer gets back
  // `{ login, name }` or `{ error }`, and status answers `{ signedIn, login,
  // name, error }`. Nothing here reads the token back out.
  signIn: (token) => ipcRenderer.invoke("engine:sign-in", String(token == null ? "" : token)),
  signOut: () => ipcRenderer.invoke("engine:sign-out"),
  signInStatus: () => ipcRenderer.invoke("engine:sign-in-status"),
  // Config > frakworks > Judge teach token (#2436): a first-mate token, kept by
  // main as the sign-in token is; the judge's teach buttons send it through
  // teachJudge(taskId, allow|deny, note), which answers { ok, line } or { error }.
  teachTokenSet: (token) => ipcRenderer.invoke("engine:teach-token-set", String(token == null ? "" : token)),
  teachTokenClear: () => ipcRenderer.invoke("engine:teach-token-clear"),
  teachTokenStatus: () => ipcRenderer.invoke("engine:teach-token-status"),
  teachJudge: (taskId, decision, note) => ipcRenderer.invoke("engine:judge-teach", String(taskId), String(decision), note == null ? null : String(note)),
  // The explorer's ticket groups — read-only, like needsYou.
  ticketTree: () => ipcRenderer.invoke("tree:tickets"),
  // Its "Done" group, read only when opened: the newest `limit` closed issues.
  doneTickets: (limit) => ipcRenderer.invoke("tree:done", Number(limit)),
  // The engine's issues board (src/engine-data.js): read-only, and the engine's
  // address is main's — the renderer cannot name a host.
  issues: () => ipcRenderer.invoke("engine:issues"),
  // One flow's steps, variables and questions, for the issue detail view.
  flow: (instance) => ipcRenderer.invoke("engine:flow", instance),
  // Config > frakworks > HITLP server (#2487): the standalone HITLP server's
  // surface URL and the person's surface credential, kept by main; status
  // answers { signedIn, url, error }, never the credential. Decide an open
  // task there — `answered` with the answer (a JSON value, copied), `approved`
  // or `rejected` with an optional reason and the payloadDigest shown.
  hitlpSurfaceSet: (url, credential) => ipcRenderer.invoke("hitlp:surface-set", String(url == null ? "" : url), String(credential == null ? "" : credential)),
  hitlpSurfaceClear: () => ipcRenderer.invoke("hitlp:surface-clear"),
  hitlpSurfaceStatus: () => ipcRenderer.invoke("hitlp:surface-status"),
  hitlpSurfaceGet: (taskId) => ipcRenderer.invoke("hitlp:surface-get", String(taskId == null ? "" : taskId)),
  hitlpSurfaceDecide: (taskId, outcome, answer, reason, payloadDigest) => ipcRenderer.invoke("hitlp:surface-decide", String(taskId), String(outcome),
    answer === undefined ? null : JSON.parse(JSON.stringify(answer)), reason == null ? null : String(reason), payloadDigest == null ? null : String(payloadDigest)),
  // A file attached to a flow's next answer ({ name, mime, content } with
  // base64 content), POST /intake/<id>/attachments; main supplies the login.
  uploadAttachment: (intake, file) => ipcRenderer.invoke("engine:upload-attachment", String(intake),
    { name: String(file && file.name), mime: String((file && file.mime) || ""), content: String(file && file.content) }),
  // The Issues view's "New intake" composer: starts an intake with the ask
  // text verbatim and, in All repos, the repo picked (#577;
  // otherwise main files in the selected repo). Who it submits as is main's
  // to resolve (the same login retryIncident uses), never the renderer's.
  startIntake: (ask, repo) => ipcRenderer.invoke("engine:start-intake", String(ask),
    repo ? String(repo) : null),
  // Its tier picker's rows: the engine's tier map (GET /tiers, read in main),
  // `[{ tier, model, effort }]` or `{ error }`. Written with accountAction's
  // `tier` op (Config > frakworks > Tiers).
  tiers: () => ipcRenderer.invoke("engine:tiers"),
  // Each tier's account right now (GET /tiers/accounts), asked when Analytics > AI Accounts opens or its tile is refreshed.
  tierAccounts: () => ipcRenderer.invoke("engine:tier-accounts"),
  // The tier every security review runs at (GET /security/tier): `{ securityTier, error }`.
  // Written with accountAction's `security-tier` op.
  securityTier: () => ipcRenderer.invoke("engine:security-tier"),
  // The tier every post-deployment check run runs at (GET /check/tier): `{ checkTier, error }`.
  // Written with accountAction's `check-tier` op.
  checkTier: () => ipcRenderer.invoke("engine:check-tier"),
  // The process view (src/renderer/process.jsx): an issue's process instances,
  // one instance's call tree, and a definition's diagram XML. Three reads of
  // the engine's /camunda endpoints; the renderer names an issue URL, an
  // instance id and a definition id, each checked in main before it is sent.
  processInstances: (issueUrl) => ipcRenderer.invoke("engine:process-instances", String(issueUrl)),
  processTree: (instance) => ipcRenderer.invoke("engine:process-tree", String(instance)),
  processXml: (definitionId) => ipcRenderer.invoke("engine:process-xml", String(definitionId)),
  // The coding card's Summarize button: the engine's LLM summary of the ticket's
  // running Smith, { summary: { done, now, ... } } or { error }.
  // The review tile's prediction bar (#1746): `repo` is owner/name.
  predictReview: (repo, ticket) => ipcRenderer.invoke("engine:predict-review", String(repo), Number(ticket)),
  summarize: (owner, repoName, n) => ipcRenderer.invoke("engine:summarize", String(owner), String(repoName), Number(n)),
  // The issue detail's read of an issue off the board (#548): GET
  // /ticket/{owner}/{repo}/{n}, { ticket } or { error }.
  ticketDetail: (owner, repoName, n) => ipcRenderer.invoke("engine:ticket", String(owner), String(repoName), Number(n)),
  // The issue detail's Pitch view (#563): GET /ticket/{owner}/{repo}/{n}/pitch,
  // { pitch: { kind, path, prUrl, content, options, awaitingChoice, awaitingDecision } } or { error }.
  ticketPitch: (owner, repoName, n) => ipcRenderer.invoke("engine:ticket-pitch", String(owner), String(repoName), Number(n)),
  // The explorer's Proposals branch (#660): GET /proposals for the selected
  // repo, { docs, failures } or { error, docs: [] }; and a doc's page with no
  // ticket, GET /proposals/{owner}/{repo}/{slug}/page, { pitch } or { error }.
  proposals: () => ipcRenderer.invoke("engine:proposals"),
  proposalPage: (owner, repoName, slug) => ipcRenderer.invoke("engine:proposal-page", String(owner), String(repoName), String(slug)),
  // The explorer's Repos branch (#674): GET /repos/{owner}/{repo}/tree, one
  // repo's whole tree at its default branch, { tree: { commit, truncated,
  // entries } } or { error }; and one file's bytes at the tree's commit,
  // GET /repos/{owner}/{repo}/file, { file: { path, size, contentType, bytes } } or { error }.
  repoTree: (owner, repoName) => ipcRenderer.invoke("engine:repo-tree", String(owner), String(repoName)),
  repoFile: (owner, repoName, path, ref) => ipcRenderer.invoke("engine:repo-file", String(owner), String(repoName), String(path),
    ref ? String(ref) : null),
  // The ticket detail's Timeline (src/renderer/timeline.jsx): the engine's
  // not-yet-shipped /ticket/{owner}/{repo}/{n}/timeline (#91).
  ticketTimeline: (owner, repoName, n) => ipcRenderer.invoke("engine:ticket-timeline", String(owner), String(repoName), Number(n)),
  // The Jev decisions page and the ticket detail's Jev section (#553):
  // GET /jev/calls with a filter ({ useCase, repo, issue, action, outcome,
  // since, limit, offset }), one call by id, the summary from `since`.
  jevCalls: (filter) => ipcRenderer.invoke("engine:jev-calls", filter && typeof filter === "object" ? { ...filter } : {}),
  jevCall: (id) => ipcRenderer.invoke("engine:jev-call", String(id)),
  jevSummary: (since) => ipcRenderer.invoke("engine:jev-summary", since == null ? null : String(since)),
  // The Analytics > SSH verdicts page (#2439): GET /jev/ssh-verdicts.
  sshVerdicts: (repo) => ipcRenderer.invoke("engine:ssh-verdicts", repo == null ? null : String(repo)),
  // The Engine > Work items view (#2586): a repo's items (with `importable`, the
  // Import action's probe), one item, a reply and an import of a GitHub issue.
  workItems: (repo) => ipcRenderer.invoke("engine:work-items", repo == null ? null : String(repo)),
  workItem: (id) => ipcRenderer.invoke("engine:work-item", String(id)),
  workItemReply: (id, text) => ipcRenderer.invoke("engine:work-item-reply", String(id), String(text == null ? "" : text)),
  workItemImport: (repo, issue) => ipcRenderer.invoke("engine:work-item-import", String(repo), Number(issue)),
  // The Analytics > Friction notes page (#1046): GET /friction/summary with
  // { since, repo } and GET /friction/notes with { since, repo, kind, source, limit }.
  frictionSummary: (filter) => ipcRenderer.invoke("engine:friction-summary", filter && typeof filter === "object" ? { ...filter } : {}),
  frictionNotes: (filter) => ipcRenderer.invoke("engine:friction-notes", filter && typeof filter === "object" ? { ...filter } : {}),
  // Its groups (#1098): GET /friction/groups with the same filters; and its two
  // writes, a note pulled out of its group and a group merged into another,
  // each as main's login, never the renderer's to say.
  frictionGroups: (filter) => ipcRenderer.invoke("engine:friction-groups", filter && typeof filter === "object" ? { ...filter } : {}),
  frictionPullOut: (noteId) => ipcRenderer.invoke("engine:friction-pull-out", String(noteId)),
  // Not two things (#1112): Jev's mark on a note cleared, as main's login.
  frictionNotTwoThings: (noteId) => ipcRenderer.invoke("engine:friction-not-two-things", String(noteId)),
  frictionMerge: (groupId, into) => ipcRenderer.invoke("engine:friction-merge", Number(groupId), Number(into)),
  frictionFix: (groupId, fix) => ipcRenderer.invoke("engine:friction-fix", Number(groupId), fix),
  // Its one write: retry an incident, named by its id. Who it retries as is
  // main's (the signed-in engine token's login), never the renderer's to say.
  retryIncident: (incidentId) => ipcRenderer.invoke("engine:retry-incident", String(incidentId)),
  // The issue detail's Cancel: cancel a root flow with the reason typed. Who
  // it cancels as is main's, as for a retry.
  cancelFlow: (instanceId, reason) => ipcRenderer.invoke("engine:cancel-flow", String(instanceId), String(reason)),
  // The ticket view's Fix and Propose a fix: the ticket's open incident and its
  // latest runs, and the two writes that start a Smith run on the incident. Who
  // it runs as is main's, as for a retry.
  incidentFixes: (issueUrl) => ipcRenderer.invoke("engine:incident-fixes", String(issueUrl)),
  // The issue detail's Doctor section: the ticket's doctor runs, read-only.
  doctorHistory: (issueUrl) => ipcRenderer.invoke("engine:doctor-history", String(issueUrl)),
  // The Analytics > Doctor page (#706): the doctor's runs across tickets since `since`.
  // Analytics > AI Accounts > Account consumption (#1934): GET /accounts/consumption?window=.
  consumption: (window) => ipcRenderer.invoke("engine:consumption", String(window)),
  doctorRuns: (since) => ipcRenderer.invoke("engine:doctor-runs", String(since)),
  // The Analytics > Builds page (#1935): a target repo's workflow runs, each with its area and trigger.
  ciRuns: (repo, limit, workflow, branch) => ipcRenderer.invoke("engine:ci-runs", String(repo), Number(limit), workflow == null ? null : String(workflow), branch == null ? null : String(branch)),
  // Utils > HITLP producer (#1947): a human.ask / human.approve call or a tasks/get, sent by main to `url`
  // with the pasted requester `token` as its credential (#2489); never the app's own.
  hitlpCall: (url, op, token) => ipcRenderer.invoke("hitlp:call", String(url), op && typeof op === "object" ? JSON.parse(JSON.stringify(op)) : null,
    token == null ? "" : String(token)),
  // Config > Repos' Edit… modal's Doctor tab (#1001): the doctor's bugs in every repo since `since`.
  doctorBugs: (since) => ipcRenderer.invoke("engine:doctor-bugs", String(since)),
  fixIncident: (incidentId) => ipcRenderer.invoke("engine:incident-fix", String(incidentId)),
  proposeIncidentFix: (incidentId) => ipcRenderer.invoke("engine:incident-propose", String(incidentId)),
  // The engine's AI accounts and their plan meters: read-only, never a token.
  accounts: () => ipcRenderer.invoke("engine:accounts"),
  // Its writes — one plain object, checked again in main; a credential goes
  // in here and never comes back out.
  accountAction: (action) => ipcRenderer.invoke("engine:account-action", action),
  // The intake refiner's tier; set with accountAction({ op: "refiner-tier", tier }).
  refinerTier: () => ipcRenderer.invoke("engine:refiner-tier"),
  // Whether the engine hosts frakpanel tiles; set with accountAction({ op: "frakpanel-tiles", enabled }).
  frakpanelTiles: () => ipcRenderer.invoke("engine:frakpanel-tiles"),
  // "Let First mate screenshot this app" (#100105): this machine's setting, kept by main under userData.
  screenshotSetting: () => ipcRenderer.invoke("screenshot:setting"),
  setScreenshotSetting: (allowed) => ipcRenderer.invoke("screenshot:set-setting", allowed === true),
  // Where new Smith runs start (docker or kubernetes); set with accountAction({ op: "smith-hosting", hosting }).
  smithHosting: () => ipcRenderer.invoke("engine:smith-hosting"),
  // Where a new First mate conversation runs (local or kubernetes); set with accountAction({ op: "first-mate-hosting", hosting }).
  firstMateHosting: () => ipcRenderer.invoke("engine:first-mate-hosting"),
  // Config > frakworks > .env (#912, src/env-lines.js): the deployment's .env
  // keys .env.example does not declare, `{ unused, missing, file }` or
  // `{ error }`, key names only; and their removal, key names only (main
  // takes no path, refuses a key the last check did not report unused, and
  // asks in a native confirm first).
  envCheck: () => ipcRenderer.invoke("env:check"),
  envRemove: (keys) => ipcRenderer.invoke("env:remove", Array.isArray(keys) ? keys.map(String) : []),
  // Config > Jobs (#1228): one repo's custom jobs, and the writes (register,
  // run now, delete), each as main's login.
  jobs: (repo) => ipcRenderer.invoke("engine:jobs", repo ? String(repo) : null),
  jobRegister: (job) => ipcRenderer.invoke("engine:job-register", job),
  jobExecute: (id) => ipcRenderer.invoke("engine:job-execute", String(id)),
  jobDelete: (id) => ipcRenderer.invoke("engine:job-delete", String(id)),
  // Config > Repos, Edit… > Refactoring (#1399): one repo's refactoring config, and its save as main's login.
  refactorConfig: (repo) => ipcRenderer.invoke("engine:refactor-config", repo ? String(repo) : null),
  // ... and its job's recent runs (#1653): what each found, filed, or why it did nothing.
  jobRuns: (id, limit) => ipcRenderer.invoke("engine:job-runs", id ? String(id) : null, Number.isInteger(limit) ? limit : 10),
  saveRefactorConfig: (repo, patch) => ipcRenderer.invoke("engine:refactor-save", repo ? String(repo) : null, patch),
  // Config > Repos > Configure First Mate (#1801): a repo's First mate MCP servers (no secret values),
  // a save and a delete as main's login, and a test (a stdio server's run here, by main).
  mcpServers: (repo) => ipcRenderer.invoke("engine:mcp-servers", repo ? String(repo) : null),
  saveMcpServer: (repo, name, body) => ipcRenderer.invoke("engine:mcp-server-save", repo ? String(repo) : null, name ? String(name) : null, body),
  deleteMcpServer: (repo, name) => ipcRenderer.invoke("engine:mcp-server-delete", repo ? String(repo) : null, name ? String(name) : null),
  testMcpServer: (repo, name) => ipcRenderer.invoke("engine:mcp-server-test", repo ? String(repo) : null, name ? String(name) : null),
  // Config > Repos: the target repos (with running counts),
  // the trusted logins, and the writes, one plain object checked again in
  // main. Who a write goes as is main's, as for a retry.
  repos: () => ipcRenderer.invoke("engine:repos"),
  // Asks the engine to check the repos' GitHub-side setup (`*` or one owner/name); answers { queued, fresh } or
  // { error } at once, and each report arrives as a `setup` frame on the event stream.
  setupCheck: (repo) => ipcRenderer.invoke("engine:setup-check", String(repo || "*")),
  engineAccess: () => ipcRenderer.invoke("engine:engine-access"),
  engineAccessAction: (action) => ipcRenderer.invoke("engine:engine-access-action", action),
  repoAction: (action) => ipcRenderer.invoke("engine:repo-action", action),
  // Saved First mate chats (#598), kept by the engine: the list (no
  // transcripts), one chat with its transcript and main's summary and
  // resumability, and a delete, by id only. `onSavedChatsChanged` fires after
  // a save or a delete so the explorer reloads its list.
  savedChats: () => ipcRenderer.invoke("engine:saved-chats"),
  savedChat: (id) => ipcRenderer.invoke("engine:saved-chat", String(id)),
  deleteSavedChat: (id) => ipcRenderer.invoke("engine:delete-saved-chat", String(id)),
  // Continue in terminal (#819) for a saved chat: its id only. Main builds the
  // command, copies it and answers `{ ok, command, mode }`.
  savedChatHandoff: (id) => ipcRenderer.invoke("savedChat:handoff", String(id)),
  onSavedChatsChanged: (cb) => {
    const h = () => cb();
    ipcRenderer.on("savedChats:changed", h);
    return () => ipcRenderer.removeListener("savedChats:changed", h);
  },
  // Saved Chats > Claude Code (#1708): local Claude Code sessions, read only.
  // The list's three folders, one session's transcript and its resume
  // command (copied), by session id only; `onClaudeSessionsChanged` fires on
  // main's debounced file watch.
  claudeSessions: () => ipcRenderer.invoke("claudeSessions:list"),
  claudeSession: (id) => ipcRenderer.invoke("claudeSessions:read", String(id)),
  claudeSessionHandoff: (id) => ipcRenderer.invoke("claudeSessions:handoff", String(id)),
  onClaudeSessionsChanged: (cb) => {
    const h = () => cb();
    ipcRenderer.on("claudeSessions:changed", h);
    return () => ipcRenderer.removeListener("claudeSessions:changed", h);
  },
  // Which tab the window opens on (AGENT_ARMY_VIEW), read in main: a sandboxed
  // preload has no environment of its own to read it from.
  initialView: () => ipcRenderer.invoke("app:initial-view"),
  // Explorer > Analytics' Grafana public dashboards (#332): the configured
  // list, `{ error, dashboards: [{ label, url, error }] }`, and a probe of
  // one of them, named by its index in that list — never by a URL, so the
  // renderer cannot make main fetch a host of its choosing.
  grafanaDashboards: () => ipcRenderer.invoke("grafana:dashboards"),
  grafanaProbe: (index) => ipcRenderer.invoke("grafana:probe", Number(index)),
  // The engine's builds in progress (GET /builds/active), for the Build gate strip.
  activeBuilds: () => ipcRenderer.invoke("builds:active"),
  // The engine's event stream (src/engine-events.js),
  // held in main. `onEngineEvent` hears each frame, `{ type, data, subscription }`;
  // `onEngineStream` the connection's state, `{ repo, state: "live" |
  // "catching-up" | "down", since, lastFrameAt }`; both return an unsubscribe,
  // the `repo.onChanged` pattern. `engineSnapshotted` is the renderer's word
  // that its snapshot reads have returned after a `catching-up`, on which main
  // releases the queued frames; `engineStreamState` the current state for a
  // window opened later; `engineSubscribe`/`engineUnsubscribe` hold a second
  // connection for a ticket detail open on another repo, by owner/name only,
  // checked in main. The renderer never names a host or a query.
  onEngineEvent: (cb) => {
    const h = (_e, frame) => cb(frame);
    ipcRenderer.on("engine:event", h);
    return () => ipcRenderer.removeListener("engine:event", h);
  },
  onEngineStream: (cb) => {
    const h = (_e, state) => cb(state);
    ipcRenderer.on("engine:stream", h);
    return () => ipcRenderer.removeListener("engine:stream", h);
  },
  engineSnapshotted: (repo) => ipcRenderer.invoke("engine:snapshotted", repo ? String(repo) : null),
  engineStreamState: () => ipcRenderer.invoke("engine:stream-state"),
  engineSubscribe: (repo) => ipcRenderer.invoke("engine:subscribe", String(repo)),
  engineUnsubscribe: (repo) => ipcRenderer.invoke("engine:unsubscribe", String(repo)),
  // Lets the renderer tell the main process what it drew, so a headless run
  // (AGENT_ARMY_ONCE=1) can log evidence and exit.
  rendered: (state) => ipcRenderer.send("fleet:rendered", state),
});

// The repo switcher (#577): the selection (a target repo or null, All repos)
// with the target repos and their needs counts, a new selection (main refuses
// anything GET /repos does not list), and "repo:changed" when it moves.
contextBridge.exposeInMainWorld("repo", {
  get: () => ipcRenderer.invoke("repo:get"),
  set: (repo) => ipcRenderer.invoke("repo:set", repo ? String(repo) : null),
  onChanged: (cb) => {
    const h = (_e, sel) => cb(sel);
    ipcRenderer.on("repo:changed", h);
    return () => ipcRenderer.removeListener("repo:changed", h);
  },
});

// First mate (issue #100): the persistent strip docked on every screen. Its
// channels, none of them rename/pin — its identity is fixed by design, not by
// renderer discipline. `send`/`file` take a string/number and nothing else;
// the tier calls (#231) take tier numbers and a duration key only. Which model
// and effort a tier means, the tool policy and the repository the File button
// posts to are all main-process configuration.
contextBridge.exposeInMainWorld("firstMate", {
  list: () => ipcRenderer.invoke("firstMate:list"),
  // The History control (#265): a read-only list of past conversations and one
  // conversation's messages, by id. No write path — main resolves both
  // straight from FirstMateManager's own archive.
  conversations: () => ipcRenderer.invoke("firstMate:conversations"),
  conversation: (id) => ipcRenderer.invoke("firstMate:conversation", String(id)),
  // Issue #486: make past conversation `id` the live one. The id only.
  resume: (id) => ipcRenderer.invoke("firstMate:resume", String(id)),
  // Issue #598: Save for later (no arguments: main builds the body from the
  // live conversation) and restore saved chat `id` into the strip.
  save: () => ipcRenderer.invoke("firstMate:save"),
  restoreSaved: (id) => ipcRenderer.invoke("firstMate:restore-saved", String(id)),
  // Continue in terminal (#819): no arguments; main hands off the live
  // conversation, copies what to paste and answers what it copied.
  handoff: () => ipcRenderer.invoke("firstMate:handoff"),
  // Issue #601: back to the newest resumable past conversation. No arguments;
  // main picks it from its own archive.
  pop: () => ipcRenderer.invoke("firstMate:pop"),
  // Issue #820: open the live conversation in Claude Code in a new iTerm2
  // window. No arguments: which session, which `claude` and which directory
  // are main's alone.
  openInIterm: () => ipcRenderer.invoke("firstMate:open-in-iterm"),
  // Pasted images (#368) cross as plain data only, `{ media_type, data }`
  // with `data` a base64 string; main re-checks both and the limits.
  send: (text, images) => ipcRenderer.invoke("firstMate:send", String(text),
    (Array.isArray(images) ? images : []).map((im) => ({ media_type: String(im && im.media_type), data: String(im && im.data) }))),
  cancel: () => ipcRenderer.invoke("firstMate:cancel"),
  // Issue #185: start a new conversation. No arguments.
  startNew: () => ipcRenderer.invoke("firstMate:new"),
  // The File button: the tier the selector is on when it is clicked. Which
  // ask text is filed, and its `Tier: n (model effort)` header, are built in
  // main from the pane's own bound `File:` card — never from anything else
  // the renderer could pass here. `repo` is the card's pick in All repos
  // (#577), checked against GET /repos in main.
  file: (tier, repo) => ipcRenderer.invoke("firstMate:file", Number(tier), repo ? String(repo) : null),
  // First mate's own tier (#231): read it, or set the standing tier. Main
  // validates it against its own tables.
  tierState: () => ipcRenderer.invoke("firstMate:tier"),
  // The chat's own repo (#2343), or null for "No repo yet".
  setRepo: (repo) => ipcRenderer.invoke("firstMate:set-repo", repo ? String(repo) : null),
  setTier: (tier) => ipcRenderer.invoke("firstMate:set-tier", Number(tier)),
  // The strip's terminal font (#366): `{ family, size, source }` from the
  // default iTerm2 profile, read in main; `family: null` means the fallback.
  font: () => ipcRenderer.invoke("firstMate:font"),
  onUpdate: (cb) => {
    const h = (_e, snap) => cb(snap);
    ipcRenderer.on("firstMate:update", h);
    return () => ipcRenderer.removeListener("firstMate:update", h);
  },
  onStream: (cb) => {
    const h = (_e, chunk) => cb(chunk);
    ipcRenderer.on("firstMate:stream", h);
    return () => ipcRenderer.removeListener("firstMate:stream", h);
  },
});

// The status bar (src/app-version.js). `version()` reads the checkout's HEAD
// and compares it with the commit the app started on — local git, nothing
// fetched. `restart()` is the app's one local write — a rebuild of what is
// on disk, then a relaunch — and takes nothing from the renderer: which
// directory is main's alone.
//
// The Update engine button (src/engine-update.js): `engineUpdate()` reads the
// running and available engine builds; `engineUpdatePreview()` is the click:
// main checks again and answers the update preview modal's model (#100091);
// `updateEngine()` is the modal's Update, and takes nothing either — main
// pulls and recreates the engine. `updatePreview()` is Restart's modal for a
// downloaded desktop update (null: none), `snapshot()` the one window
// capture the modal blurs behind it. Which project,
// which docker and which image are main's alone. `onEngineUpdateResult`
// hears the reports no click awaits: the end of an update a relaunched app
// resumed, and an engine container left in Created that main started.
// `onEngineUpdateProgress` hears the update overlay's progress (#1053), pushed
// by main as each line and answer arrives, null once the overlay is closed;
// `engineUpdateProgress()` is the one read a window makes when it loads (a
// relaunch's resumed update), never on a timer; `closeEngineUpdateProgress()`
// is the overlay's Close, refused while the update is in flight.
//
// Find in page (#703): the find bar (src/renderer/find.jsx) is its own view
// with this preload. `find` searches the window's page for a string (main
// caps its length); `stopFind` clears the highlights and hides the bar;
// `onFind` hears `find:update`, `{ open: true }` on Cmd-F or a found-in-page
// count `{ active, matches }`. Main answers only the bar's own webContents.
contextBridge.exposeInMainWorld("app", {
  version: () => ipcRenderer.invoke("app:version"),
  restart: () => ipcRenderer.invoke("app:restart"),
  engineUpdate: () => ipcRenderer.invoke("engine:update-state"),
  updatePreview: () => ipcRenderer.invoke("app:update-preview"),
  snapshot: () => ipcRenderer.invoke("app:snapshot"),
  engineUpdatePreview: () => ipcRenderer.invoke("engine:update-preview"),
  updateEngine: () => ipcRenderer.invoke("engine:update-run"),
  onEngineUpdateResult: (cb) => {
    const h = (_e, r) => cb(r);
    ipcRenderer.on("engine:update-result", h);
    return () => ipcRenderer.removeListener("engine:update-result", h);
  },
  engineUpdateProgress: () => ipcRenderer.invoke("engine:update-progress-state"),
  closeEngineUpdateProgress: () => ipcRenderer.invoke("engine:update-progress-close"),
  onEngineUpdateProgress: (cb) => {
    const h = (_e, p) => cb(p);
    ipcRenderer.on("engine:update-progress", h);
    return () => ipcRenderer.removeListener("engine:update-progress", h);
  },
  find: (text, opts) => ipcRenderer.invoke("find:start", String(text),
    { forward: !(opts && opts.forward === false), findNext: !!(opts && opts.findNext) }),
  stopFind: () => ipcRenderer.invoke("find:stop"),
  // A frakworks://ticket/… link opened outside the app (src/app-link.js):
  // `takeLink` answers its `{ repo, number }` once, then null; `onLink` says
  // another one arrived. The renderer never sees the URL.
  takeLink: () => ipcRenderer.invoke("app:take-link"),
  onLink: (cb) => {
    const h = () => cb();
    ipcRenderer.on("app:link", h);
    return () => ipcRenderer.removeListener("app:link", h);
  },
  onFind: (cb) => {
    const h = (_e, u) => cb(u);
    ipcRenderer.on("find:update", h);
    return () => ipcRenderer.removeListener("find:update", h);
  },
});
