// The renderer's navigation contract: which screen wins, the detail view's
// headless evidence line, and the channels the preload bridge exposes.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { classifyTicket } = require("../dist/index.cjs");

const src = (f) => fs.readFileSync(path.join(__dirname, "..", "src", f), "utf8");

test("engine:start-intake authorizes as the resolved login, like First mate's submit_ask, and posts nothing with no login (#1765)", () => {
  const main = src("main.js");
  const handler = main.match(/ipcMain\.handle\("engine:start-intake",[\s\S]*?\n\}\);/)[0];
  assert.match(handler, /const by = await signedInLogin\(\);/);
  assert.match(handler, /if \(!by\) return \{ error:.*no GitHub login to file as/);
  assert.match(handler, /submitIntake\(ask, by, \{ repo: target, authorize: true \}\)/);
  // the no-login branch returns before submitIntake is ever reached
  const noLoginIdx = handler.indexOf("if (!by)");
  const submitIdx = handler.indexOf("submitIntake(");
  assert.ok(noLoginIdx > -1 && submitIdx > noLoginIdx, "the no-login check must come before the submitIntake call");
});

test("a First mate or Saved Chat ticket click clears the explorer ticket too, so the issue view wins over a ticket left open (#677)", () => {
  const index = src(path.join("renderer", "index.jsx"));
  assert.match(index, /onOpenTicket=\{\(repo, number\) => \{ setView\("tickets"\); setTicket\(null\); setIssueSel\(\{ repo, number: Number\(number\) \}\); \}\}/,
    "FirstMateStrip's onOpenTicket must clear ticket, or the ticket ternary keeps winning");
  assert.match(index, /onOpenTicket=\{\(repo, number\) => \{ setTicket\(null\); setIssueSel\(\{ repo, number: Number\(number\) \}\); \}\}/,
    "SavedChat's onOpenTicket must clear ticket too");
});

test("classifyTicket reports what a headless run drew", () => {
  const doc = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "fixture-ticket.json"), "utf8"));
  assert.deepEqual(classifyTicket({ doc }),
    { kind: "ticket", message: "#1281: 6 comments, 0 PRs" });
  // one dead source is still a usable screen, and says which
  const partial = { ...doc, prs_error: "linked PR lookup failed: HTTP 503" };
  assert.deepEqual(classifyTicket({ doc: partial }),
    { kind: "ticket", message: "#1281: 6 comments, 0 PRs — linked PR lookup failed: HTTP 503" });
  // both dead is the error state
  const dead = { ...doc, issue_error: "issue_error: down", prs_error: "prs_error: down" };
  assert.deepEqual(classifyTicket({ doc: dead }), { kind: "error", message: "issue_error: down" });
  assert.equal(classifyTicket({ error: "IPC failed" }).kind, "error");
  assert.deepEqual(classifyTicket({ doc: null }), { kind: "error", message: "no ticket data" });
});

test("AGENT_ARMY_TICKET is a deep link into the detail view", () => {
  assert.match(src("preload.js"), /startTicket: process\.env\.AGENT_ARMY_TICKET \|\| null/);
  // and the renderer only honours a real ticket number, `n` or `owner/name#n`
  assert.match(src(path.join("renderer", "index.jsx")),
    /parseTicketRef\(typeof window !== "undefined" && window\.fleet && window\.fleet\.startTicket\)/);
  assert.match(src(path.join("renderer", "repo-switcher.jsx")),
    /Number\.isInteger\(n\) && n > 0 \? \{ repo: fallbackRepo \|\| null, number: n \} : null/);
});

test("with AGENT_ARMY_VIEW unset, main names no view, so the selection remembered across a restart stands", () => {
  assert.match(src("main.js"), /ipcMain\.handle\("app:initial-view", \(\) => process\.env\.AGENT_ARMY_VIEW \|\| null\)/);
});

test("Config has a frakworks row right after AI Accounts and Repos, and it is a remembered view (#616)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const { VIEWS, migrateSel } = require("../dist/index.cjs");
  const html = renderToStaticMarkup(React.createElement(Explorer, { sel: { kind: "frakworks" }, onSelect: () => {} }));
  assert.match(html, />AI Accounts<\/span><\/div><div class="exRow"[^>]*><span class="exChev"><\/span><span class="exLabel">Repos<\/span><\/div><div class="exRow"[^>]*><span class="exChev"><\/span><span class="exLabel">Jobs<\/span><\/div><div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">frakworks</);
  assert.doesNotMatch(html, /First mate model/);
  assert.ok(VIEWS.some((v) => v.id === "frakworks"));
  assert.ok(!VIEWS.some((v) => v.id === "first-mate-model"));
  const index = src(path.join("renderer", "index.jsx"));
  assert.match(index, /view === "frakworks"\s*\? <div className="fleet-root"><FirstMateModelScreen tiers=\{tiers\}>/);
  // A selection remembered as the old row opens the frakworks page.
  assert.deepEqual(migrateSel({ kind: "first-mate-model" }), { kind: "frakworks" });
  assert.deepEqual(migrateSel({ kind: "repos" }), { kind: "repos" });
  assert.equal(migrateSel(null), null);
  assert.match(index, /const loadSel = \(\) => \{ try \{ return migrateSel\(/);
});

test("Config has no Intake refiner tier row: it is on the frakworks page, polled only while that page is shown (#617)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const { VIEWS, migrateSel } = require("../dist/index.cjs");
  const html = renderToStaticMarkup(React.createElement(Explorer, { sel: { kind: "frakworks" }, onSelect: () => {} }));
  assert.doesNotMatch(html, /Intake refiner tier/);
  assert.ok(!VIEWS.some((v) => v.id === "refiner-tier"));
  const index = src(path.join("renderer", "index.jsx"));
  assert.doesNotMatch(index, /view === "refiner-tier"/);
  assert.doesNotMatch(src(path.join("renderer", "explorer.jsx")), /kind: "refiner-tier"/);
  assert.match(index, /<FirstMateModelScreen tiers=\{tiers\}>\s*<SignInPanel [^]*?\/>\s*<RefinerTierPanel /);
  // No timer: the tier is read when the page is shown and re-read on the stream's `setting` `intake.tier` frame (#976).
  assert.match(index, /if \(view !== "frakworks" \|\| !\(window\.fleet && window\.fleet\.refinerTier\)\) return undefined;\s*refreshRefinerTier\(\);\s*\}, \[refreshRefinerTier, view\]\);/);
  assert.match(index, /useRefetchOn\(\["setting"\], refreshRefinerTierShown, \{ where: \(d\) => d\.name === "intake\.tier" \}\)/);
  assert.doesNotMatch(index, /ACCOUNTS_REFRESH_MS/);
  // The Tickets tree refetches on `incident` as the board does, through one useBoardRows (#1048): an incident's open
  // carries no board row and no `flow` frame follows it; a `ticket` frame refetches only when it carries no row and
  // is no issue's close, which drops the row itself (#1065).
  assert.match(index, /const refreshBoard = useCallback\(\(\) => Promise\.all\(\[refreshIssuesShown\(\), refreshTree\(\)\]\)/);
  assert.match(index, /useBoardRows\(\(row\) => \{[\s\S]*?\}, refreshBoard, \(repo, number, frame\) => \{/);
  assert.match(src(path.join("renderer", "engine-events.js")), /useRefetchOn\(\["ticket", "incident"\], refetch, \{ where: \(d\) => !carriedRow\(d\) && !closedIssue\(d\) \}\)/);
  // A selection (or AGENT_ARMY_VIEW) naming the old row opens the frakworks page.
  assert.deepEqual(migrateSel({ kind: "refiner-tier" }), { kind: "frakworks" });
  assert.match(index, /const k = migrateSel\(\{ kind: v \}\)\.kind;/);
});

test("Config has a Repos row right after AI Accounts, and it is a remembered view (#567)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const { VIEWS } = require("../dist/index.cjs");
  const html = renderToStaticMarkup(React.createElement(Explorer, { sel: { kind: "repos" }, onSelect: () => {} }));
  assert.match(html, />AI Accounts<\/span><\/div><div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">Repos</);
  assert.ok(VIEWS.some((v) => v.id === "repos"));
  const index = src(path.join("renderer", "index.jsx"));
  assert.match(index, /view === "repos"\s*\? <div className="fleet-root"><Repos /);
});

test("the renderer sends First mate tier numbers only, never a model or effort", () => {
  const index = src(path.join("renderer", "index.jsx"));
  const code = index.replace(/^\s*\/\/.*$/gm, "");
  const calls = [...code.matchAll(/window\.firstMate\.(setTier|setOverride|clearOverride)\(/g)].map((m) => m[1]);
  assert.deepEqual(calls, ["setTier"]);
  assert.ok(code.includes("window.firstMate.setTier(Number(n))"));
  for (const f of ["first-mate.jsx", "first-mate-model.jsx"]) {
    assert.doesNotMatch(src(path.join("renderer", f)).replace(/^\s*\/\/.*$/gm, ""), /window\.\w/, f);
  }
});

test("the renderer reaches main only through preload, every invoke channel accounted for", () => {
  const preload = src("preload.js");
  assert.match(preload, /fetchTicket: \(repo, ticket\) => ipcRenderer\.invoke\("ticket:fetch", String\(repo\), Number\(ticket\)\)/);
  // stage 4 (#1283) added the third: the live transcript pane's poll
  assert.match(preload, /fetchTranscript: \(ticket, since, run\) => ipcRenderer\.invoke\("transcript:fetch", ticket, since,/);
  // stage 3a (#1282) added the "needs you" rows and the initial-view lookup
  assert.match(preload, /needsYou: \(\) => ipcRenderer\.invoke\("needs:fetch"\)/);
  assert.match(preload, /initialView: \(\) => ipcRenderer\.invoke\("app:initial-view"\)/);
  // The exhaustive half, as the set of channel names rather than a bare count:
  // a stage that adds a channel gets told which one is unaccounted for instead
  // of `6 !== 5` (reviewer note on PR #1307). The Chats tab's `chat:*`
  // channels and stage 4's `auth:*` and `answer:*` went with it in #634.
  // The explorer added `tree:tickets`, a read-only
  // `gh` listing of open issues and claim refs, and later `tree:done`, its
  // closed issues, read only when the Done group is opened. The move onto the engine added
  // `engine:issues`, a read of the engine's issues board (src/engine-data.js),
  // and Config > AI Accounts `engine:accounts`, a read that never carries a token,
  // and its writes `engine:account-action` (add, credential, enable, remove).
  // The status bar added `app:version` (a read of the checkout's HEAD against
  // the commit the app started on, local git only) and `app:restart`, the
  // app's one local write: a rebuild of what is on disk, then a relaunch —
  // taking nothing from the renderer. The issue detail added `engine:flow`, a
  // read of one flow (GET /intake/<id>) whose id is checked before it is sent.
  // The process view added the three `engine:process-*` reads of the engine's
  // /camunda endpoints — an issue's instances, one instance's call tree, a
  // definition's diagram XML — each argument checked before it goes in a URL —
  // and `engine:retry-incident`, its one write: an incident id, checked in
  // main; who it retries as is main's, not the renderer's. (The ask card's
  // `engine:answer-question` and the Pitch view's `engine:choose-option` went
  // in #2471: a wait is decided on the HITLP server.) The Issues view's "New
  // intake" composer (#128) added `engine:start-intake`, a write: an
  // ask (verbatim), submitted and authorized (#1765) as the
  // same login — refused with no post to the engine when no login resolves;
  // #146 added `engine:tiers`, its tier picker's
  // rows: the engine's tier map (GET /tiers), read in main. Issue #100 added the five `firstMate:*`
  // channels -- the persistent strip's own list/send/cancel/file and a
  // move-to-chats that went with the Chats tab (#634), none of which is a rename, reset or pin control, checked in
  // detail by test/first-mate-ipc.test.js.
  // Issue #185 added a sixth, `firstMate:new` (reset only; still no rename or pin).
  // The Timeline widget (#101) added `engine:ticket-timeline`, a read of the
  // engine's GET /ticket/{owner}/{repo}/{n}/timeline (#205) — owner, repo and
  // ticket number all checked before they go in a URL.
  // Issue #231 added two for First mate's own tier: `firstMate:tier` (read)
  // and `firstMate:set-tier`, carrying a tier number only, validated in main.
  // (Its timed override and the `firstMate:set-override`/`firstMate:clear-override`
  // channels were removed in #1751.)
  // Issue #265 added the two read-only History channels, `firstMate:conversations`
  // (the past-conversation list) and `firstMate:conversation` (one conversation's
  // messages, by id) — no write path, checked in detail by test/first-mate-ipc.test.js.
  // Issue #100105 added `screenshot:setting` and `screenshot:set-setting`, the
  // "Let First mate screenshot this app" setting's read and its one boolean write.
  // Issue #332 added `grafana:dashboards` (the configured list, read-only) and
  // `grafana:probe`, which takes a dashboard's index and never a URL.
  // Issue #366 added `firstMate:font`, a no-argument read of the default
  // iTerm2 profile's font, done in main (src/iterm-font.js).
  // Issue #380 added the ticket view's Fix and Propose a fix: `engine:incident-fixes`,
  // a read by issue URL, and `engine:incident-fix` and `engine:incident-propose`,
  // two writes that take an incident id checked in main and run as the retry's login.
  // The issue detail's Cancel (#420) added `engine:cancel-flow`, a write: an
  // instance id and a reason, both checked in main, sent as the retry's login.
  // Issue #423 added `engine:summarize` (renamed in #881), the coding card's Summarize: owner, repo and
  // ticket number checked in main, then POST /ticket/{owner}/{repo}/{n}/summarize,
  // which writes nothing and answers only a summary.
  // Issue #1746 added `engine:predict-review`, the review tile's prediction bar:
  // owner/name and ticket number checked in main, then POST
  // /ticket/{owner}/{repo}/{n}/predict-review, chained by the renderer while a review runs.
  // Issue #469 added the status bar's Update engine button: `engine:update-state`,
  // a read of the running and available engine builds, and `engine:update`, a
  // write that takes nothing — main checks again and asks in a native confirm
  // before it pulls and recreates the engine container.
  // Issue #548 added `engine:ticket`, the issue detail's read of an issue off
  // the board: GET /ticket/{owner}/{repo}/{n}, owner, repo and number checked
  // in main before they go in a URL.
  // Issue #553 added three reads for the Jev decisions page and the ticket
  // detail's Jev section: `engine:jev-calls` (a filter, every field checked in
  // src/engine-data.js), `engine:jev-call` (a call id, a UUID) and
  // `engine:jev-summary` (a since).
  // Issue #563 added `engine:ticket-pitch`, the Pitch view's read of a
  // proposal's pitch: GET /ticket/{owner}/{repo}/{n}/pitch, owner, repo and
  // number checked in main before they go in a URL.
  // Issue #567 added Config > Repos: `engine:repos` and `engine:repo-access`,
  // reads of the target repos (with their setup checks) and one repo's grants,
  // and `engine:repo-action`, its writes (add, remove, check, grant, revoke,
  // labels), checked in src/engine-data.js and sent as the retry's login.
  // Issue #577 added the repo switcher: `repo:get`, a read of the selection,
  // the target repos and their needs counts, and `repo:set`, which takes a
  // repo (or null for All repos) checked in main against GET /repos and
  // writes only the app's own userData file. `ticket:fetch` now carries the
  // ticket's repo too, checked the same way.
  // Issue #598 added saved First mate chats: `engine:saved-chats` and
  // `engine:saved-chat` (an id), reads of the engine's /saved-chats, and
  // `engine:delete-saved-chat` (an id); `firstMate:save`, which takes nothing
  // (main builds the body from the live conversation), and
  // `firstMate:restore-saved`, an id main reads back from the engine.
  // Issue #820 added `firstMate:handoff`, "Open in Claude Code ⇗": no
  // arguments; main picks the session, the `claude` and the directory.
  // Issue #601 added `firstMate:pop`, "Back to previous conversation": no
  // arguments, the manager picks the conversation from its own archive.
  // Issue #660 added `engine:proposals` (the Proposals branch's docs, no
  // arguments: main adds the selected repo) and `engine:proposal-page`
  // (owner, name, slug; engine-data checks them before any read).
  // Issue #674 added the Repos branch's two reads: `engine:repo-tree` (owner,
  // name: one repo's whole tree at its default branch) and `engine:repo-file`
  // (owner, name, path, the tree's commit), each checked in engine-data first.
  // Issue #706 added `engine:doctor-runs` (a since, an ISO instant checked in
  // engine-data), the Analytics > Doctor page's read of GET /doctor/runs.
  // Issue #703 added find in page: `find:start` (a string main caps, and the
  // forward/findNext flags) and `find:stop`, answered only for the find bar's
  // own view, with `find:update` sent back to it (src/renderer/find.jsx).
  // Issue #819 added Continue in terminal: `firstMate:handoff`, which takes
  // nothing (main hands off the live conversation), and `savedChat:handoff`,
  // a saved chat's id; main builds the command and copies it.
  // Issue #705 added `transcript:doctor` (a ticket number and a doctor run's
  // smithRunId, both checked in transcript-data first): one doctor run's log.
  // Issue #912 added Config > frakworks > .env: `env:check`, the key names of
  // the deployment's .env against .env.example, and `env:remove`, key names
  // only; main refuses one the last check did not report unused and asks in
  // a native confirm before it rewrites the file.
  // Issue #971 (the engine's event stream in main) added `engine:snapshotted`,
  // `engine:stream-state`, `engine:subscribe` and `engine:unsubscribe`: the
  // renderer's snapshot acknowledgement, the state for a window opened later,
  // and a ticket detail's second subscription by owner/name.
  // Issue #965 replaced `engine:repo-setups` (the timed setup reads) with
  // `engine:setup-check`: a request whose results come back on the stream.
  // Issue #1001 added `engine:doctor-bugs`, Config > Repos' Doctor files bugs
  // modal's read of GET /doctor/bugs since an ISO instant checked in main.
  // Issue #1006 added the transcript's live tail: `transcript:tail-open` (the
  // renderer's token, a ticket number, the run `{ filename, host }` a `session`
  // frame named and the cursor, each checked in main), `transcript:tail-end`
  // and `transcript:tail-close` (the token); its lines come back on
  // `transcript:tail`. The query is main's.
  // Issue #1046 added `engine:friction-summary` and `engine:friction-notes`
  // (a since, repo, kind, source and limit, each checked in engine-data), the
  // Analytics > Friction notes page's reads of GET /friction/summary and /notes.
  // Issue #1098 added `engine:friction-groups` (GET /friction/groups, the page's
  // group rows) and its two writes, `engine:friction-pull-out` and
  // `engine:friction-merge`, each as main's login. Issue #1112 added
  // `engine:friction-not-two-things`, the mark cleared, as main's login too.
  // Issue #1053 added the Update engine overlay: `engine:update-progress-state`,
  // the progress for a window opened (or relaunched) during an update, and
  // `engine:update-progress-close`, its Close, refused while it runs; the
  // progress itself comes back on `engine:update-progress`.
  // Issue #1228 added Config > Jobs: `engine:jobs` (a repo, refused in
  // engine-data without one) and `engine:job-register`, `engine:job-execute`
  // and `engine:job-delete`, each sent as main's login.
  // Issue #1327 added engine-wide grants to Config > Repos: `engine:engine-access`
  // and `engine:engine-access-action`, the latter sent as main's login. Issue
  // #1345 made it one list of trusted logins and removed `engine:repo-access`.
  // Issue #1338 added Config > frakworks > frakpanel tiles: `engine:frakpanel-tiles`.
  // Issue #1348 added Config > frakworks > Smith hosting: `engine:smith-hosting`.
  // Issue #2521 added Config > frakworks > First mate hosting: `engine:first-mate-hosting`.
  // Issue #1389 added the Build gate strip's read: `builds:active`.
  // Issue #1708 added Saved Chats > Claude Code: `claudeSessions:handoff`, `claudeSessions:list`, `claudeSessions:read`.
  // Issue #1730 added the AskCard's attachments: `engine:upload-attachment`.
  // Issue #1801 added Config > Repos' Configure First Mate modal: `engine:mcp-servers`,
  // `engine:mcp-server-save` and `engine:mcp-server-delete` (as main's login) and
  // `engine:mcp-server-test` (a stdio server's run by main; no secret reaches the renderer).
  // Issue #1935 added Analytics > Builds: `engine:ci-runs` (a repo and limit checked in engine-data).
  // Issue #1947 added Utils > HITLP producer: `hitlp:call` (main's MCP client sends it to the server the user named).
  // Issue #2173 added the Needs view's HITLP rows: `engine:hitlp-decide`; #2487 removed it for
  // `hitlp:surface-decide` (the HITLP server's surface API) and Config's HITLP server,
  // `hitlp:surface-set`, `-clear` and `-status` (the URL back, never the credential).
  // Issue #100070 added the ticket detail's inline task: `hitlp:surface-get` (one task by id, never the credential).
  // Issue #2206 added Config > frakworks > Sign in to the engine: `engine:sign-in` (the token goes inward once),
  // `engine:sign-out` and `engine:sign-in-status` (login and token name back; never the token).
  // Issue #100091 added the update preview modal: `app:update-preview` and `engine:update-preview` (its model),
  // `app:snapshot` (one capture of the window for its blur) and `engine:update-run` (its Update; was `engine:update`).
  // Issue #2586 added Engine > Work items: `engine:work-items` (a repo, with the
  // Import probe), `engine:work-item`, `engine:work-item-reply` and `engine:work-item-import`.
  // Issue #100114 added `engine:design-deck` (a confirm-mode feature's design deck).
  const EXPECTED_CHANNELS = ["app:initial-view", "app:restart", "app:snapshot", "app:take-link", "app:update-preview", "app:version", "builds:active", "claudeSessions:handoff", "claudeSessions:list", "claudeSessions:read", "engine:account-action", "engine:accounts", "engine:cancel-flow", "engine:check-tier", "engine:ci-runs", "engine:consumption", "engine:delete-saved-chat", "engine:design-deck", "engine:doctor-bugs", "engine:doctor-history", "engine:doctor-runs", "engine:engine-access", "engine:engine-access-action", "engine:first-mate-hosting", "engine:flow", "engine:frakpanel-tiles", "engine:friction-fix", "engine:friction-groups", "engine:friction-merge", "engine:friction-not-two-things", "engine:friction-notes", "engine:friction-pull-out", "engine:friction-summary", "engine:incident-fix", "engine:incident-fixes", "engine:incident-propose", "engine:issues", "engine:jev-call", "engine:jev-calls", "engine:jev-summary", "engine:job-delete", "engine:job-execute", "engine:job-register", "engine:job-runs", "engine:jobs", "engine:judge-teach", "engine:mcp-server-delete", "engine:mcp-server-save", "engine:mcp-server-test", "engine:mcp-servers",
     "engine:predict-review", "engine:process-instances", "engine:process-tree", "engine:process-xml", "engine:proposal-page", "engine:proposals", "engine:refactor-config", "engine:refactor-save", "engine:refiner-tier", "engine:repo-action", "engine:repo-file", "engine:repo-tree", "engine:repos", "engine:retry-incident", "engine:saved-chat", "engine:saved-chats", "engine:security-tier", "engine:setup-check", "engine:sign-in", "engine:sign-in-status", "engine:sign-out", "engine:smith-hosting", "engine:snapshotted", "engine:ssh-verdicts", "engine:start-intake", "engine:stream-state", "engine:subscribe", "engine:summarize", "engine:teach-token-clear", "engine:teach-token-set", "engine:teach-token-status", "engine:ticket", "engine:ticket-pitch", "engine:ticket-timeline", "engine:tier-accounts", "engine:tiers", "engine:unsubscribe", "engine:update-preview", "engine:update-progress-close", "engine:update-progress-state", "engine:update-run", "engine:update-state", "engine:upload-attachment", "engine:work-item", "engine:work-item-import", "engine:work-item-reply", "engine:work-items",
     "env:check", "env:remove", "find:start", "find:stop", "firstMate:cancel", "firstMate:conversation", "firstMate:conversations",
     "firstMate:file", "firstMate:font", "firstMate:handoff", "firstMate:list", "firstMate:new", "firstMate:open-in-iterm", "firstMate:pop", "firstMate:restore-saved", "firstMate:resume", "firstMate:save", "firstMate:send",
     "firstMate:set-repo", "firstMate:set-tier", "firstMate:tier",
     "grafana:dashboards", "grafana:probe", "hitlp:call", "hitlp:surface-clear", "hitlp:surface-decide", "hitlp:surface-get", "hitlp:surface-set", "hitlp:surface-status", "hitlp:tasks", "login:get", "needs:fetch", "repo:get", "repo:set", "savedChat:handoff", "screenshot:set-setting", "screenshot:setting", "ticket:fetch",
     "transcript:doctor", "transcript:fetch", "transcript:tail-close", "transcript:tail-end", "transcript:tail-open", "tree:done", "tree:tickets"];
  assert.deepEqual(
    [...preload.matchAll(/ipcRenderer\.invoke\("([^"]+)"/g)].map((m) => m[1]).sort(),
    EXPECTED_CHANNELS,
    `every channel the renderer can reach is one of the ${EXPECTED_CHANNELS.length} listed above`);
  // no module, path or process handed across the bridge
  assert.doesNotMatch(preload, /exposeInMainWorld\([^)]*require\(/);
  const main = src("main.js");
  assert.match(main, /ipcMain\.handle\("ticket:fetch"/, "the second main-process handler");
  assert.match(main, /ipcMain\.handle\("transcript:fetch"/, "the third main-process handler");
  assert.match(main, /ipcMain\.handle\("find:start"/);
  assert.match(main, /ipcMain\.handle\("find:stop"/);
  assert.match(main, /view\.webContents\.send\("find:update"/);
  assert.match(preload, /ipcRenderer\.on\("find:update", h\)/);
  assert.match(main, /contextIsolation: true/);
  assert.match(main, /nodeIntegration: false/);
  assert.match(main, /sandbox: true/);
  // the detail view's data never comes from the renderer's own fs/net
  const ticket = src(path.join("renderer", "ticket.jsx"));
  assert.doesNotMatch(ticket, /require\(|node:|fetch\(/);
});

test("Analytics has AI Accounts (#640) then a Jev decisions row, and it is a remembered view (#553)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const { VIEWS } = require("../dist/index.cjs");
  const html = renderToStaticMarkup(React.createElement(Explorer, { sel: { kind: "jev" }, onSelect: () => {} }));
  assert.match(html, /<span class="exLabel">Account consumption<\/span><\/div><div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">Jev decisions</);
  assert.ok(VIEWS.some((v) => v.id === "jev"));
  const index = src(path.join("renderer", "index.jsx"));
  assert.match(index, /view === "jev"\s*\? <JevDecisions onOpenTicket=\{onJevTicket\} \/>/);
  // a ticket link on the page selects kind "ticket" for the framework's own tickets
  assert.match(index, /onSelect\(\{ kind: "ticket", id: repo \? ticketId\(repo, n\) : Number\(n\) \}\)/);
});

test("Analytics > AI Accounts is a branch of Account balances then Account consumption, each a remembered view (#1934)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const { VIEWS } = require("../dist/index.cjs");
  for (const kind of ["account-usage", "account-consumption"]) {
    const html = renderToStaticMarkup(React.createElement(Explorer, { sel: { kind }, onSelect: () => {} }));
    assert.match(html, /<div class="exRow exBranch"[^>]*><span class="exChev">▾<\/span><span class="exLabel">AI Accounts<\/span><\/div><div class="exRow( on)?"[^>]*><span class="exChev"><\/span><span class="exLabel">Account balances<\/span><\/div><div class="exRow( on)?"[^>]*><span class="exChev"><\/span><span class="exLabel">Account consumption</);
    assert.match(html, new RegExp(`<div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">${kind === "account-usage" ? "Account balances" : "Account consumption"}<`));
    assert.ok(VIEWS.some((v) => v.id === kind));
  }
  const index = src(path.join("renderer", "index.jsx"));
  assert.match(index, /view === "account-usage"\s*\? <div className="fleet-root"><Accounts /);
  assert.match(index, /view === "account-consumption"\s*\? <Consumption \/>/);
});

test("Analytics has a Builds row after the Grafana rows that opens the Builds page over engine:ci-runs (#1935)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const { VIEWS } = require("../dist/index.cjs");
  const picked = [];
  const html = renderToStaticMarkup(React.createElement(Explorer, { sel: { kind: "builds" }, onSelect: (i) => picked.push(i) }));
  assert.match(html, /<div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">Builds<\/span>/);
  assert.ok(VIEWS.some((v) => v.id === "builds" && v.label === "Builds" && v.branch === "Analytics"));
  const explorer = src(path.join("renderer", "explorer.jsx"));
  assert.match(explorer, /<GrafanaRows[^\n]*\/>\n\s*<Row depth=\{1\} sel=\{sel\} item=\{\{ kind: "builds" \}\}/);
  const index = src(path.join("renderer", "index.jsx"));
  assert.match(index, /view === "builds"\s*\? <Builds key=\{selRepo \|\| ""\} repo=\{selRepo\} \/>/);
  const preload = src("preload.js");
  assert.match(preload, /ciRuns: \(repo, limit, workflow, branch\) => ipcRenderer\.invoke\("engine:ci-runs", String\(repo\), Number\(limit\)/);
  const main = src("main.js");
  assert.match(main, /ipcMain\.handle\("engine:ci-runs", async \(_e, repo, limit, workflow, branch\) => \{\n  const r = await fetchCiRuns\(repo, limit, workflow, branch\);/);
  const builds = src(path.join("renderer", "builds.jsx"));
  assert.doesNotMatch(builds, /require\(|node:|fetch\(/);
  assert.match(builds, /window\.fleet\.ciRuns\(/);
});

test("Analytics has a Doctor row after Jev decisions that opens the Doctor page over engine:doctor-runs (#706)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const { VIEWS } = require("../dist/index.cjs");
  const picked = [];
  const html = renderToStaticMarkup(React.createElement(Explorer, { sel: { kind: "doctor" }, onSelect: (i) => picked.push(i) }));
  assert.match(html, />Jev decisions<\/span><\/div><div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">Doctor</);
  assert.ok(VIEWS.some((v) => v.id === "doctor" && v.label === "Doctor"));
  const explorer = src(path.join("renderer", "explorer.jsx"));
  assert.match(explorer, /const ANALYTICS_KINDS = \[[^\]]*"doctor"/);
  const index = src(path.join("renderer", "index.jsx"));
  assert.match(index, /view === "doctor"\s*\? <DoctorRuns onOpenTicket=\{onJevTicket\} \/>/);
  const preload = src("preload.js");
  assert.match(preload, /doctorRuns: \(since\) => ipcRenderer\.invoke\("engine:doctor-runs", String\(since\)\)/);
  const main = src("main.js");
  assert.match(main, /ipcMain\.handle\("engine:doctor-runs", async \(_e, since\) => \{\n  const r = await fetchDoctorRuns\(since\);/);
  // the page reads the engine only through the bridge
  const doctor = src(path.join("renderer", "doctor.jsx"));
  assert.doesNotMatch(doctor, /require\(|node:|fetch\(/);
  assert.match(doctor, /window\.fleet\.doctorRuns\(/);
});

test("Analytics has a Refactoring row after Friction notes that opens the Refactoring page (#1781)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const { VIEWS } = require("../dist/index.cjs");
  const html = renderToStaticMarkup(React.createElement(Explorer, { sel: { kind: "refactoring" }, onSelect: () => {} }));
  assert.match(html, />Friction notes<\/span><\/div><div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">Refactoring</);
  assert.ok(VIEWS.some((v) => v.id === "refactoring" && v.label === "Refactoring" && v.branch === "Analytics"));
  const explorer = src(path.join("renderer", "explorer.jsx"));
  assert.match(explorer, /const ANALYTICS_KINDS = \[[^\]]*"refactoring"/);
  const index = src(path.join("renderer", "index.jsx"));
  assert.match(index, /view === "refactoring"\s*\? <RefactorRunsPage [^>]*onOpenTicket=\{onJevTicket\} \/>/);
  const runs = src(path.join("renderer", "refactor-runs.jsx"));
  assert.doesNotMatch(runs, /require\(|node:|fetch\(/);
  assert.match(runs, /fleet\.jobRuns\(/);
  assert.match(runs, /useRefetchOn\(\["job"\]/);
});

test("Analytics has an SSH verdicts row after Refactoring that opens the SSH verdicts page over engine:ssh-verdicts (#2439)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const { VIEWS } = require("../dist/index.cjs");
  const html = renderToStaticMarkup(React.createElement(Explorer, { sel: { kind: "ssh-verdicts" }, onSelect: () => {} }));
  assert.match(html, />Refactoring<\/span><\/div><div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">SSH verdicts</);
  assert.ok(VIEWS.some((v) => v.id === "ssh-verdicts" && v.label === "SSH verdicts" && v.branch === "Analytics"));
  const explorer = src(path.join("renderer", "explorer.jsx"));
  assert.match(explorer, /const ANALYTICS_KINDS = \[[^\]]*"ssh-verdicts"/);
  const index = src(path.join("renderer", "index.jsx"));
  assert.match(index, /view === "ssh-verdicts"\s*\? <SshVerdicts [^>]*\/>/);
  const page = src(path.join("renderer", "ssh-verdicts.jsx"));
  assert.doesNotMatch(page, /require\(|node:|fetch\(/);
  assert.match(page, /window\.fleet\.sshVerdicts\(/);
});

test("Utils comes after Analytics and before HITLP Requests, with a HITLP producer row that opens its page over hitlp:call (#1947)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const { VIEWS } = require("../dist/index.cjs");
  const html = renderToStaticMarkup(React.createElement(Explorer, { sel: { kind: "hitlp-producer" }, onSelect: () => {} }));
  assert.match(html, /<span class="exLabel">Analytics<\/span><\/div><div class="exRow exBranch"[^>]*><span class="exChev">▾<\/span><span class="exLabel">Utils<\/span><\/div><div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">HITLP producer<\/span><\/div><div class="exRow exBranch"[^>]*><span class="exChev">.<\/span><span class="exLabel">HITLP Requests</);
  assert.ok(VIEWS.some((v) => v.id === "hitlp-producer" && v.label === "HITLP producer" && v.branch === "Utils"));
  const index = src(path.join("renderer", "index.jsx"));
  // #2489: no engine URL prefill; the pasted requester token is the call's only credential.
  assert.match(index, /view === "hitlp-producer"\s*\? <HitlpProducer repo=\{selRepo\} \/>/);
  const preload = src("preload.js");
  assert.match(preload, /hitlpCall: \(url, op, token\) => ipcRenderer\.invoke\("hitlp:call", String\(url\)/);
  const main = src("main.js");
  assert.match(main, /ipcMain\.handle\("hitlp:call", async \(_e, url, op, token\) => \{\n[^]*?const r = await callHitlp\(url, op, requesterToken \? \{ headers: \{ authorization: `Bearer \$\{requesterToken\}` \} \} : \{\}\);/);
  const page = src(path.join("renderer", "hitlp-producer.jsx"));
  assert.doesNotMatch(page, /require\(|node:|fetch\(/);
  assert.match(page, /window\.fleet\.hitlpCall\(/);
});

test("Config and Analytics start collapsed unless the restored page is under one of them (#596)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const branch = (html, label) => {
    const m = html.match(new RegExp(`<span class="exChev">(.)</span><span class="exLabel">${label}<`));
    return m && m[1] === "▾" ? "open" : "closed";
  };
  const cases = [
    [null, "closed", "closed"], [{ kind: "tickets" }, "closed", "closed"], [{ kind: "needs" }, "closed", "closed"],
    [{ kind: "ticket", number: 1 }, "closed", "closed"], [{ kind: "saved-chat", id: "x" }, "closed", "closed"],
    [{ kind: "accounts" }, "open", "closed"], [{ kind: "repos" }, "open", "closed"], [{ kind: "frakworks" }, "open", "closed"],
    [{ kind: "jev" }, "closed", "open"], [{ kind: "doctor" }, "closed", "open"], [{ kind: "account-usage" }, "closed", "open"], [{ kind: "account-consumption" }, "closed", "open"], [{ kind: "grafana", id: "g" }, "closed", "open"],
    [{ kind: "builds" }, "closed", "open"],
    [{ kind: "hitlp-producer" }, "closed", "closed", "open"],
    [{ kind: "hitlp-requests" }, "closed", "closed"], [{ kind: "hitlp-closed" }, "closed", "closed"],
  ];
  for (const [sel, config, analytics, utils = "closed"] of cases) {
    const html = renderToStaticMarkup(React.createElement(Explorer, { sel, onSelect: () => {} }));
    assert.equal(branch(html, "Config"), config, `Config for ${JSON.stringify(sel)}`);
    assert.equal(branch(html, "Analytics"), analytics, `Analytics for ${JSON.stringify(sel)}`);
    assert.equal(branch(html, "Utils"), utils, `Utils for ${JSON.stringify(sel)}`);
    assert.equal(branch(html, "HITLP Requests"), "open", `HITLP Requests for ${JSON.stringify(sel)}`);
    assert.equal(branch(html, "Tickets"), "open");
    // #100029: the Engine branch is gone. #100064: the Tickets heading is the Tickets pane, and Needs you its first row.
    assert.doesNotMatch(html, /<span class="exLabel">Engine</);
    assert.match(html, /<span class="exLabel">Tickets<\/span>(<span class="exCount">[^<]*<\/span>)?<\/div><div class="exRow[^"]*"[^>]*><span class="exChev"><\/span><span class="exLabel">Needs you</);
  }
  // A selection (or AGENT_ARMY_VIEW) naming Engine > Issues or Engine > Work items opens Tickets > Tickets.
  const { migrateSel } = require("../dist/index.cjs");
  assert.deepEqual(migrateSel({ kind: "issues" }), { kind: "tickets" });
  assert.deepEqual(migrateSel({ kind: "work-items" }), { kind: "tickets" });
});

test("Saved Chats is its own branch, open by default: one row per chat, newest first as the engine lists them (#598)", () => {
  const { Explorer } = require("../dist/explorer.cjs");
  const chats = [
    { id: "a0000000-0000-4000-8000-000000000001", title: "Newest chat", savedAt: "2026-09-27T10:00:00Z" },
    { id: "a0000000-0000-4000-8000-000000000002", title: "Older chat", savedAt: "2026-09-20T10:00:00Z" },
  ];
  const draw = (savedChats, sel = null) => renderToStaticMarkup(React.createElement(Explorer, { sel, onSelect: () => {}, savedChats }));
  const html = draw({ chats }, { kind: "saved-chat", id: chats[1].id });
  assert.match(html, /<span class="exChev">▾<\/span><span class="exLabel">Saved Chats<\/span><span class="exCount">2<\/span>/);
  assert.ok(html.indexOf("Newest chat") < html.indexOf("Older chat"));
  assert.match(html, /<div class="exRow on"[^>]*><span class="exChev"><\/span><span class="exLabel">Older chat/);
  assert.match(draw({ chats: [] }), /Saved Chats<\/span><span class="exCount">0<\/span><\/div><div class="exEmpty"[^>]*>none</);
  assert.match(draw({ error: "engine unreachable", chats: [] }), /<div class="exErr"[^>]*>engine unreachable</);
  const index = src(path.join("renderer", "index.jsx"));
  assert.match(index, /view === "saved-chat" \? \{ kind: "saved-chat", id: savedId \}/);
  assert.match(index, /if \(item\.kind === "saved-chat"\) setSavedId\(String\(item\.id\)\)/);
  assert.match(index, /window\.fleet\.onSavedChatsChanged/);
});

// Sign in to the engine (#2206): the Sign in section
// on Config > frakworks, its three channels, and the login resolution order.
test("Config > frakworks mounts the Sign in section first, read only while that page is shown (#2206)", () => {
  const index = src(path.join("renderer", "index.jsx"));
  assert.match(index, /import \{ SignInPanel \} from "\.\/sign-in\.jsx";/);
  assert.match(index, /<SignInPanel result=\{signInStatus \|\| \{ loading: true \}\} onRefresh=\{refreshSignIn\} web=\{window\.fleet\.platform === "web"\}\s*onSignIn=\{\(token\) => window\.fleet\.signIn\(token\)\} onSignOut=\{\(\) => window\.fleet\.signOut\(\)\} \/>/);
  assert.match(index, /if \(view !== "frakworks" \|\| !\(window\.fleet && window\.fleet\.signInStatus\)\) return undefined;\s*refreshSignIn\(\);\s*\}, \[refreshSignIn, view\]\);/);
  // the Needs toggle's login is re-read with the status, so it follows a sign-in or sign-out
  assert.match(index, /setSignInStatus\(r \|\| \{[^}]*\}\);\s*if \(window\.fleet\.login\) window\.fleet\.login\(\)\.then/);
  const preload = src("preload.js");
  assert.match(preload, /signIn: \(token\) => ipcRenderer\.invoke\("engine:sign-in", String\(token == null \? "" : token\)\)/);
  assert.match(preload, /signOut: \(\) => ipcRenderer\.invoke\("engine:sign-out"\)/);
  assert.match(preload, /signInStatus: \(\) => ipcRenderer\.invoke\("engine:sign-in-status"\)/);
  const main = src("main.js");
  assert.match(main, /ipcMain\.handle\("engine:sign-in", async \(_e, token\) => \{\n  const r = await engineToken\.signIn\(/);
  assert.match(main, /ipcMain\.handle\("engine:sign-out", \(\) => \{\n  const r = engineToken\.signOut\(\);/);
  assert.match(main, /ipcMain\.handle\("engine:sign-in-status", \(\) => engineToken\.status\(\)\);/);
  // both rewrite First mate's config at its next start
  assert.match(main, /if \(!r\.error && firstMate\) firstMate\.invalidateMcpConfig\(\);/);
  // login:get answers the signed-in login, from the bearer token alone; after a
  // restart the loaded token is resolved with GET /whoami (status) on first use
  assert.match(main, /const signedInLogin = async \(\) => \{\n  if \(!engineToken\.login\(\) && engineToken\.signedIn\(\)\) await engineToken\.status\(\);\n  return engineToken\.login\(\) \|\| null;\n\};/);
  assert.doesNotMatch(main, /ENGINE_LOGIN|retryLogin/);
  assert.match(main, /ipcMain\.handle\("login:get", \(\) => signedInLogin\(\)\);/);
  // every engine call carries the bearer: engine-data's hook is set once, from the keeper, and the token is loaded before the window
  assert.match(main, /setBearer\(\(\) => engineToken\.bearer\(\)\);/);
  assert.match(main, /engineToken\.load\(\);\n  const win = createWindow\(\);/);
  // First mate's config gets the token from main only
  assert.match(main, /ENGINE_TOKEN: engineToken\.signedIn\(\) \? engineToken\.bearer\(\)\.slice\("Bearer "\.length\) : undefined,/);
});

test("an issue row at the post-deploy check shows its state phrase on the board it opens from", () => {
  const { Issues } = require("../dist/issues.cjs");
  const issue = { number: 5, repo: "o/r", title: "t", url: "https://github.com/o/r/issues/5", kind: "chore", labels: ["chore"],
    flow: { flow: "chore", outcome: "started", ended: false, stage: "call-post-deploy-check", step: "wake at" } };
  const html = renderToStaticMarkup(React.createElement(Issues, { result: { issues: [issue] }, onOpenTicket: () => {} }));
  assert.match(html, /verifying, next check scheduled/);
});
