// fleet-web.js — `window.fleet`, `window.repo` and `window.app` for the web
// version (observability/desktop/README.md, "Web version"): the bridge
// src/preload.js gives the Electron renderer, built in the page instead, so
// the same renderer (src/renderer/*.jsx) runs in a browser unchanged.
//
// Where preload asks main over IPC, this calls the same fetchers main calls
// (src/engine-data.js and its neighbours) straight from the page. The page is
// served by the engine (`<engine>/web/`), so every engine request goes to its
// own origin: no CORS, no engine address of the page's choosing. The bearer is
// the engine token the user pastes on the sign-in screen (the desktop's Config
// > frakworks > Sign in, the same token): kept in sessionStorage for the tab's
// life, never in the repo, never in a URL. Writes go as the login the token
// proves (GET /whoami), as main's `signedInLogin` resolves it.
//
// The page's second origin is the HITLP server (#2489; src/hitlp-surface.js
// with a sessionStorage keeper): Config > frakworks > HITLP server takes its
// surface URL and the person's surface credential, and the Needs view's ask
// and approve rows, their decisions and the `GET /events` subscription go to
// it cross-origin with that credential, as main does on the desktop. It must
// allow the page's origin (CORS: the preflight answered, Access-Control-
// Allow-Origin for it, -Headers authorization and content-type). The engine
// token never goes there, and the surface credential never to the engine.
//
// What only the desktop can do (First mate with hosting `local`, Continue in
// terminal, Open in iTerm2, the transcript read from VictoriaLogs, local
// Claude Code sessions, the .env check, Grafana's local config, the HITLP
// producer's calls to any host, a stdio MCP server's test, the judge teach
// token) answers `{ error: OMITTED }`: every member preload has is here
// (test/web-fleet.test.js holds the parity), so the renderer's own `{ error }`
// handling draws it. `fleet.platform` is "web" for the views that hide such a
// feature outright. `window.firstMate` (#100104) is src/web/first-mate-web.js:
// with hosting `kubernetes` a conversation runs in a session pod driven with
// the page's own bearer, kept in localStorage per login.
"use strict";

const engineData = require("../engine-data");
const { createHitlpSurface, storageKeeper } = require("../hitlp-surface");
const { fetchTicketData } = require("../ticket-data");
const workItems = require("../work-items");
const { fetchNeeds, fetchHitlpTasks, fetchHitlpTask } = require("../needs-you");
const { fetchTicketTree, fetchDoneTickets } = require("../ticket-tree");
const { loadTiers } = require("../engine-tiers");
const { EngineEventStream, FLEET } = require("../engine-events");
const { sameRepo } = require("../repo-names");
const { parseAppLink } = require("../app-link");
const { createWebFirstMate } = require("./first-mate-web");

const { PAGE_ENV } = engineData;

/** What a desktop-only member answers in the browser. */
const OMITTED = "not in the web version: this needs the desktop app (observability/desktop/README.md, \"Web version\")";
const omitted = () => Promise.resolve({ error: OMITTED });

const TOKEN_KEY = "frakworks.engineToken";
const KEEP_KEY = "frakworks.keepSignedIn";
const REPO_KEY = "frakworks.repo";
const ALL_REPOS = "All repos";
const REPO_FULL = /^[\w.-]+\/[\w.-]+$/;

/** `#/ticket/<owner>/<name>/<n>`, the web version's address of a ticket's detail, as the renderer's `{ repo, number }`. */
const parseTicketHash = (hash) => {
  const m = /^#\/ticket\/([\w.-]+)\/([\w.-]+)\/(\d+)\/?$/.exec(String(hash || ""));
  return m ? parseAppLink(`frakworks://ticket/${m[1]}/${m[2]}/${m[3]}`) : null;
};
/** The hash a `frakworks://ticket/…` link opens in the web version, or null for anything else. */
const hashOfAppLink = (url) => {
  const t = parseAppLink(url);
  return t ? `#/ticket/${t.repo}/${t.number}` : null;
};

/**
 * The three bridges over `storage` (the token, sessionStorage) and `prefs`
 * (the repo selection, localStorage). `win` is the page's window, for the
 * hash and its event; all injectable for tests.
 */
function createWebFleet({ storage, prefs, persist, win = null, stream = null, firstMateFactory = createWebFirstMate } = {}) {
  const session = storage || memoryStorage();
  const keep = prefs || memoryStorage();
  // Keep me signed in (README, "iPad"): the token and the HITLP surface pair go
  // to `persist` (localStorage) instead, so a home-screen launch, a new tab
  // that shares no sessionStorage, comes back signed in. The flag sits in prefs.
  const device = persist || keep;
  const kept = () => { try { return keep.getItem(KEEP_KEY) === "1"; } catch { return false; } };
  const active = () => (kept() ? device : session);
  const store = {
    getItem: (k) => active().getItem(k),
    setItem: (k, v) => active().setItem(k, v),
    removeItem: (k) => { session.removeItem(k); device.removeItem(k); },
  };
  const token = () => { try { return store.getItem(TOKEN_KEY) || null; } catch { return null; } };
  engineData.setBearer(() => (token() ? `Bearer ${token()}` : null));

  // The login the token proves, resolved once per token (main's signedInLogin).
  let who = null;
  const signedInLogin = async () => {
    if (!token()) return null;
    if (!who) {
      const r = await engineData.fetchWhoAmI(token(), { env: PAGE_ENV });
      if (!r.error) who = { login: r.login, name: r.name };
    }
    return who ? who.login : null;
  };

  // Listeners by channel, the IPC pushes preload subscribes to.
  const listeners = new Map();
  const on = (channel, cb) => {
    if (!listeners.has(channel)) listeners.set(channel, new Set());
    listeners.get(channel).add(cb);
    return () => listeners.get(channel).delete(cb);
  };
  const send = (channel, payload) => { for (const cb of [...(listeners.get(channel) || [])]) cb(payload); };
  const never = () => () => {};

  // The engine's event stream, held in the page as main holds it.
  const events = stream || new EngineEventStream({ env: PAGE_ENV, send, log: (line) => console.log(`[events] ${line}`) });

  // The HITLP server's surface API (#2489), its URL and credential in the same
  // sessionStorage as the token; a `task` event on its GET /events refetches
  // the shared HITLP task list through a `needs` frame (main's pushSurfaceNeeds).
  const surface = createHitlpSurface({ keeper: storageKeeper(store), log: (line) => console.log(line) });
  surface.load();
  const pushSurfaceNeeds = () => send("engine:event", { type: "needs", data: { source: "hitlp" } });
  const resubscribeSurface = () => { if (surface.configured()) surface.subscribe(() => pushSurfaceNeeds()); else surface.unsubscribe(); };
  resubscribeSurface();

  // The repo selection (main's repo-selection.js, kept in localStorage).
  let selected;
  const loadSelection = () => { try { const r = keep.getItem(REPO_KEY); return r && REPO_FULL.test(r) ? r : null; } catch { return null; } };
  const currentRepo = async () => {
    if (selected === undefined) {
      selected = loadSelection();
      if (selected) {
        const names = await engineData.fetchRepoNames({ needs: false, env: PAGE_ENV });
        if (!names.error && !(names.repos || []).some((r) => sameRepo(r, selected))) selected = null;
      }
      events.setSelected(selected);
    }
    return selected;
  };
  const titleFor = (repo) => (repo ? repo : ALL_REPOS);
  const withLogin = (fn) => async (...args) => fn(...args, await signedInLogin());

  const fleet = {
    platform: "web",
    reportError: (e) => console.error("[renderer]", e && e.message, e && e.stack, e && e.context),
    fetchTicket: async (repo, ticket) => {
      const names = await engineData.fetchRepoNames({ needs: false, env: PAGE_ENV });
      const found = (names.repos || []).find((r) => sameRepo(r, String(repo)));
      if (!found) {
        const why = names.error || `${repo} is not a target repo`;
        return Object.assign(await fetchTicketData(Number(ticket), { repo: null, env: PAGE_ENV }), { repo, issue_error: why, prs_error: why });
      }
      return fetchTicketData(Number(ticket), { repo: found, env: PAGE_ENV });
    },
    fetchTranscript: omitted,
    openTranscriptTail: omitted,
    endTranscriptTail: omitted,
    closeTranscriptTail: omitted,
    onTranscriptTail: never,
    doctorTranscript: omitted,
    startTicket: null,
    startIssue: null,
    needsYou: async () => fetchNeeds({ repo: await currentRepo() }),
    hitlpTasks: async () => fetchHitlpTasks({ surface: () => surface.list(), configured: surface.configured(), url: surface.status().url }),
    login: () => signedInLogin(),
    signIn: async (raw, { keep: keepIt = false } = {}) => {
      const candidate = String(raw == null ? "" : raw).trim();
      if (!candidate) return { error: "no token to sign in with" };
      const r = await engineData.fetchWhoAmI(candidate, { env: PAGE_ENV });
      if (r.error) return { error: r.error };
      store.removeItem(TOKEN_KEY);
      if (keepIt) keep.setItem(KEEP_KEY, "1"); else keep.removeItem(KEEP_KEY);
      store.setItem(TOKEN_KEY, candidate);
      who = { login: r.login, name: r.name };
      return { login: r.login, name: r.name };
    },
    // The tab's identity goes with the token: the HITLP server's credential too.
    signOut: async () => { store.removeItem(TOKEN_KEY); who = null; surface.clear(); keep.removeItem(KEEP_KEY); resubscribeSurface(); return { ok: true }; },
    signInStatus: async () => {
      if (!token()) return { signedIn: false, login: null, name: null, error: null };
      const r = await engineData.fetchWhoAmI(token(), { env: PAGE_ENV });
      if (r.error) return { signedIn: true, login: who ? who.login : null, name: who ? who.name : null, error: r.error };
      who = { login: r.login, name: r.name };
      return { signedIn: true, login: r.login, name: r.name, error: null };
    },
    ticketTree: async () => fetchTicketTree({ repo: await currentRepo() }),
    doneTickets: async (limit) => fetchDoneTickets({ repo: await currentRepo(), limit }),
    issues: async () => { const repo = await currentRepo(); return engineData.fetchIssues(repo ? { repo } : {}); },
    flow: (instance) => engineData.fetchFlow(instance),
    // The judge teach token (#2436) is the desktop's, kept by main: the teach buttons stay disabled here.
    teachTokenSet: omitted,
    teachTokenClear: omitted,
    teachTokenStatus: () => Promise.resolve({ signedIn: false, login: null, name: null, error: OMITTED }),
    // A screenshot is of the desktop app's window (#100105): the web page has none to give.
    screenshotSetting: () => Promise.resolve({ allowed: false, error: OMITTED }),
    setScreenshotSetting: () => Promise.resolve({ allowed: false, error: OMITTED }),
    teachJudge: omitted,
    // Config > frakworks > HITLP server (#2489): verified with GET /tasks, kept in
    // sessionStorage; status answers the URL alone, never the credential.
    hitlpSurfaceSet: async (url, credential) => {
      const r = await surface.set(String(url == null ? "" : url), String(credential == null ? "" : credential));
      if (!r.error) { resubscribeSurface(); pushSurfaceNeeds(); }
      return r;
    },
    hitlpSurfaceClear: async () => { const r = surface.clear(); resubscribeSurface(); pushSurfaceNeeds(); return r; },
    hitlpSurfaceStatus: async () => surface.status(),
    hitlpSurfaceGet: async (taskId) => fetchHitlpTask({ surface: (id) => surface.get(id), configured: surface.configured(), id: String(taskId == null ? "" : taskId) }),
    hitlpSurfaceDecide: (taskId, outcome, answer, reason, payloadDigest) => surface.decide(String(taskId), { outcome: String(outcome),
      answer: answer === undefined ? null : JSON.parse(JSON.stringify(answer)), reason: reason == null ? null : String(reason),
      payloadDigest: payloadDigest == null ? null : String(payloadDigest) }),
    uploadAttachment: (intake, file) => withLogin(engineData.uploadAttachment)(String(intake),
      { name: String(file && file.name), mime: String((file && file.mime) || ""), content: String(file && file.content) }),
    startIntake: async (ask, repo) => {
      const target = (typeof repo === "string" && repo) || await currentRepo();
      if (!target) return { error: "All repos is selected: pick the repo to file in" };
      const by = await signedInLogin();
      if (!by) return { error: "no GitHub login to file as (sign in to the engine)" };
      return engineData.submitIntake(String(ask), by, { repo: target, authorize: true });
    },
    tiers: async () => { const r = await loadTiers(); return r.error ? { error: r.error } : r.tiers; },
    tierAccounts: () => engineData.fetchTierAccounts(),
    securityTier: async () => { const t = await engineData.fetchSecurityTier(); return { securityTier: t.securityTier || null, error: t.error || null }; },
    checkTier: async () => { const t = await engineData.fetchCheckTier(); return { checkTier: t.checkTier || null, error: t.error || null }; },
    processInstances: (issueUrl) => engineData.fetchProcessInstances(String(issueUrl)),
    processTree: (instance) => engineData.fetchProcessTree(String(instance)),
    processXml: (definitionId) => engineData.fetchProcessXml(String(definitionId)),
    predictReview: (repo, ticket) => engineData.predictReview(String(repo), Number(ticket)),
    summarize: (owner, repoName, n) => engineData.summarizeTicket(String(owner), String(repoName), Number(n)),
    ticketDetail: (owner, repoName, n) => engineData.fetchTicketDetail(String(owner), String(repoName), Number(n)),
    ticketPitch: (owner, repoName, n) => engineData.fetchTicketPitch(String(owner), String(repoName), Number(n)),
    designDeck: (owner, repoName, n) => engineData.fetchDesignDeck(String(owner), String(repoName), Number(n)),
    proposals: async () => { const repo = await currentRepo(); return engineData.fetchProposals(repo ? { repo } : {}); },
    proposalPage: (owner, repoName, slug) => engineData.fetchProposalPage(String(owner), String(repoName), String(slug)),
    repoTree: (owner, repoName) => engineData.fetchRepoTree(String(owner), String(repoName)),
    repoFile: (owner, repoName, path, ref) => engineData.fetchRepoFile(String(owner), String(repoName), String(path), ref ? String(ref) : null),
    ticketTimeline: (owner, repoName, n) => engineData.fetchTicketTimeline(String(owner), String(repoName), Number(n)),
    jevCalls: (filter) => engineData.fetchJevCalls(filter && typeof filter === "object" ? { ...filter } : {}),
    jevCall: (id) => engineData.fetchJevCall(String(id)),
    jevSummary: (since) => engineData.fetchJevSummary(since == null ? undefined : String(since)),
    sshVerdicts: (repo) => engineData.fetchSshVerdicts(repo == null ? undefined : String(repo)),
    workItems: (repo) => workItems.viewList(repo == null ? null : String(repo)),
    workItem: (id) => workItems.viewRead(String(id)),
    workItemReply: (id, text) => workItems.replyToWorkItem(String(id), String(text == null ? "" : text)),
    workItemImport: (repo, issue) => workItems.viewImport(String(repo), Number(issue)),
    frictionSummary: (filter) => engineData.fetchFrictionSummary(filter && typeof filter === "object" ? { ...filter } : {}),
    frictionNotes: (filter) => engineData.fetchFrictionNotes(filter && typeof filter === "object" ? { ...filter } : {}),
    frictionGroups: (filter) => engineData.fetchFrictionGroups(filter && typeof filter === "object" ? { ...filter } : {}),
    frictionPullOut: (noteId) => withLogin(engineData.frictionPullOut)(String(noteId)),
    frictionNotTwoThings: (noteId) => withLogin(engineData.frictionNotTwoThings)(String(noteId)),
    frictionMerge: (groupId, into) => withLogin(engineData.frictionMerge)(Number(groupId), Number(into)),
    frictionFix: (groupId, fix) => withLogin(engineData.frictionFix)(Number(groupId), fix),
    retryIncident: (incidentId) => withLogin(engineData.retryIncident)(String(incidentId)),
    cancelFlow: async (instanceId, reason) => engineData.cancelFlow(String(instanceId), await signedInLogin(), String(reason)),
    incidentFixes: (issueUrl) => engineData.fetchIncidentFixes(String(issueUrl)),
    doctorHistory: (issueUrl) => engineData.fetchDoctorHistory(String(issueUrl)),
    consumption: (window) => engineData.fetchConsumption(String(window)),
    doctorRuns: (since) => engineData.fetchDoctorRuns(String(since)),
    ciRuns: (repo, limit, workflow, branch) => engineData.fetchCiRuns(String(repo), Number(limit), workflow == null ? null : String(workflow), branch == null ? null : String(branch)),
    hitlpCall: omitted,
    doctorBugs: (since) => engineData.fetchDoctorBugs(String(since)),
    fixIncident: (incidentId) => withLogin(engineData.startIncidentFix)(String(incidentId)),
    proposeIncidentFix: (incidentId) => withLogin(engineData.startIncidentPropose)(String(incidentId)),
    accounts: () => engineData.fetchAccounts(),
    accountAction: (action) => engineData.accountAction(action),
    refinerTier: async () => { const t = await engineData.fetchRefinerTier(); return { refinerTier: t.refinerTier || null, error: t.error || null }; },
    frakpanelTiles: async () => { const t = await engineData.fetchFrakpanelTiles(); return { frakpanelTiles: t.frakpanelTiles || null, error: t.error || null }; },
    smithHosting: async () => { const t = await engineData.fetchSmithHosting(); return { smithHosting: t.smithHosting || null, error: t.error || null }; },
    firstMateHosting: async () => { const t = await engineData.fetchFirstMateHosting(); return { firstMateHosting: t.firstMateHosting || null, error: t.error || null }; },
    envCheck: omitted,
    envRemove: omitted,
    jobs: (repo) => engineData.fetchJobs(repo ? String(repo) : null),
    jobRegister: (job) => withLogin(engineData.registerJob)(job),
    jobExecute: (id) => withLogin(engineData.executeJob)(String(id)),
    jobDelete: (id) => withLogin(engineData.deleteJob)(String(id)),
    refactorConfig: (repo) => engineData.fetchRefactorConfig(repo ? String(repo) : null),
    jobRuns: (id, limit) => engineData.fetchJobRuns(id ? String(id) : null, Number.isInteger(limit) ? limit : 10),
    saveRefactorConfig: (repo, patch) => withLogin(engineData.saveRefactorConfig)(repo ? String(repo) : null, patch),
    mcpServers: (repo) => engineData.fetchRepoMcpServers(repo ? String(repo) : null),
    saveMcpServer: (repo, name, body) => withLogin(engineData.saveRepoMcpServer)(repo ? String(repo) : null, name ? String(name) : null, body),
    deleteMcpServer: (repo, name) => withLogin(engineData.deleteRepoMcpServer)(repo ? String(repo) : null, name ? String(name) : null),
    // An http or sse server is tested by the engine; a stdio one runs on the desktop's machine, so not here.
    testMcpServer: async (repo, name) => {
      const config = await engineData.fetchRepoMcpConfig(repo ? String(repo) : null);
      const entry = config.servers && config.servers[name];
      if (entry && entry.type === "stdio") return { reached: false, tools: [], error: OMITTED, local: true };
      const r = await engineData.testRepoMcpServer(repo ? String(repo) : null, name ? String(name) : null);
      return { reached: Boolean(r.reached), tools: r.tools || [], error: r.error || null, local: false };
    },
    repos: () => engineData.fetchRepoRows(),
    setupCheck: (repo) => engineData.requestSetupCheck(String(repo || "*"), { force: true }),
    engineAccess: () => engineData.fetchEngineAccess(),
    engineAccessAction: (action) => withLogin(engineData.engineAccessAction)(action),
    repoAction: (action) => withLogin(engineData.repoAction)(action),
    // Saved chats are First mate's, and Claude Code sessions are local files: desktop only.
    savedChats: omitted,
    savedChat: omitted,
    deleteSavedChat: omitted,
    savedChatHandoff: omitted,
    onSavedChatsChanged: never,
    claudeSessions: omitted,
    claudeSession: omitted,
    claudeSessionHandoff: omitted,
    onClaudeSessionsChanged: never,
    initialView: async () => null,
    grafanaDashboards: omitted,
    grafanaProbe: omitted,
    activeBuilds: () => engineData.fetchActiveBuilds(),
    onEngineEvent: (cb) => on("engine:event", cb),
    onEngineStream: (cb) => on("engine:stream", cb),
    engineSnapshotted: async (repo) => events.snapshotted(typeof repo === "string" && repo ? repo : undefined),
    engineStreamState: async () => { await currentRepo(); return events.streamState(); },
    engineSubscribe: async (repo) => events.subscribe(String(repo)),
    engineUnsubscribe: async (repo) => events.unsubscribe(String(repo)),
    rendered: () => {},
  };

  const repo = {
    get: async () => {
      const current = await currentRepo();
      const r = await engineData.fetchRepoNames({ env: PAGE_ENV });
      return { repo: current, title: titleFor(current), repos: r.repos || [], needs: r.needs, error: r.error || null, notice: null };
    },
    set: async (want) => {
      await currentRepo();
      const r = await engineData.fetchRepoNames({ needs: false, env: PAGE_ENV });
      let next = null;
      if (want) {
        if (!REPO_FULL.test(String(want))) return { ok: false, error: `not an owner/name repo: ${JSON.stringify(want)}` };
        if (!Array.isArray(r.repos)) return { ok: false, error: "the target repos could not be read, so no repo can be chosen" };
        next = r.repos.find((x) => sameRepo(x, String(want)));
        if (!next) return { ok: false, error: `${want} is not a target repo` };
      }
      selected = next;
      try { if (next) keep.setItem(REPO_KEY, next); else keep.removeItem(REPO_KEY); } catch { /* the choice still holds for this page */ }
      const title = titleFor(next);
      send("repo:changed", { repo: next, title });
      events.setSelected(next);
      return { ok: true, repo: next, title };
    },
    onChanged: (cb) => on("repo:changed", cb),
  };

  // A ticket link (`frakworks://ticket/…` in the renderer, `#/ticket/…` in the
  // address bar) opens its detail through the renderer's own app-link path.
  const app = {
    takeLink: async () => {
      const t = win ? parseTicketHash(win.location.hash) : null;
      if (t && win.history && win.history.replaceState) win.history.replaceState(null, "", `${win.location.pathname}${win.location.search}`);
      return t;
    },
    onLink: (cb) => on("app:link", cb),
  };
  if (win && win.addEventListener) {
    win.addEventListener("hashchange", () => send("app:link"));
    if (win.document) {
      win.document.addEventListener("click", (ev) => {
        const a = ev.target && ev.target.closest ? ev.target.closest("a[href^='frakworks:']") : null;
        const hash = a && hashOfAppLink(a.getAttribute("href"));
        if (!hash) return;
        ev.preventDefault();
        win.location.hash = hash;
      }, true);
    }
  }

  // Pair a phone (README, "Pair a phone"): web only, so not on `fleet`, whose
  // members are preload's. Mints as the login the page's token proves.
  const pairing = {
    publicUrl: () => engineData.fetchPublicUrl({ env: PAGE_ENV }),
    login: () => signedInLogin(),
    mint: async (name) => engineData.mintPhoneToken(await signedInLogin(), String(name), { env: PAGE_ENV }),
    tokens: async () => {
      const r = await engineData.fetchTokens({ env: PAGE_ENV });
      return { error: r.error || null, tokens: r.tokens.filter((t) => t && t.purpose === "first-mate" && !t.revokedAt) };
    },
    revoke: (id) => engineData.revokeToken(String(id), { env: PAGE_ENV }),
  };

  // First mate (#100104): src/web/first-mate-web.js over the page's bearer and
  // event stream, built once the login is known (its store is keyed by it).
  // Every member preload's firstMate block has waits for that, and the two
  // listeners are kept here so a subscription before the first `list` holds.
  let fm = null;
  let fmLogin = null;
  const fmUpdate = new Set();
  const fmStream = new Set();
  const firstMateFor = async () => {
    const login = await signedInLogin();
    if (!login) return null;
    if (fm && fmLogin === login) return fm;
    if (fm) fm.stop();
    fmLogin = login;
    fm = firstMateFactory({
      storage: device, login,
      bearer: () => (token() ? `Bearer ${token()}` : null),
      engineEvents: events,
      repo: () => (selected === undefined ? loadSelection() : selected),
      submitIntake: (ask, target) => fleet.startIntake(ask, target),
    });
    fm.onUpdate((snap) => { for (const cb of [...fmUpdate]) cb(snap); });
    fm.onStream((chunk) => { for (const cb of [...fmStream]) cb(chunk); });
    return fm;
  };
  const notSignedIn = { error: engineData.SIGN_IN_PROMPT };
  const fmCall = (name, ...args) => firstMateFor().then((m) => (m ? m[name](...args) : notSignedIn));
  const firstMate = {
    list: async () => { const m = await firstMateFor(); return m ? m.list() : null; },
    conversations: (...a) => fmCall("conversations", ...a).then((r) => (Array.isArray(r) ? r : [])),
    conversation: (id) => fmCall("conversation", String(id)),
    resume: (id) => fmCall("resume", String(id)),
    save: () => fmCall("save"),
    restoreSaved: () => omitted(),
    handoff: () => omitted(),
    pop: () => fmCall("pop"),
    openInIterm: () => omitted(),
    send: (text, images) => fmCall("send", String(text), (Array.isArray(images) ? images : []).map((im) => ({ media_type: String(im && im.media_type), data: String(im && im.data) }))),
    cancel: () => fmCall("cancel"),
    startNew: () => fmCall("startNew"),
    file: (tier, target) => fmCall("file", Number(tier), target ? String(target) : null),
    tierState: () => fmCall("tierState"),
    setRepo: (r) => fmCall("setRepo", r ? String(r) : null),
    setTier: (tier) => fmCall("setTier", Number(tier)),
    font: async () => ({ family: null, size: null, source: null }),
    onUpdate: (cb) => { fmUpdate.add(cb); return () => fmUpdate.delete(cb); },
    onStream: (cb) => { fmStream.add(cb); return () => fmStream.delete(cb); },
  };
  const stopFirstMate = () => { if (fm) { fm.stop(); fm = null; fmLogin = null; } };
  const signOutBase = fleet.signOut;
  fleet.signOut = async () => { stopFirstMate(); return signOutBase(); };

  return { fleet, repo, app, pairing, firstMate, signedIn: () => Boolean(token()), FLEET };
}

/** A Storage stand-in for tests and for a browser that refuses sessionStorage. */
function memoryStorage() {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k) };
}

module.exports = { createWebFleet, OMITTED, TOKEN_KEY, KEEP_KEY, parseTicketHash, hashOfAppLink, memoryStorage };
