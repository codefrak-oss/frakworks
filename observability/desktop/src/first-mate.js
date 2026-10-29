// first-mate.js — the main-process adapter for "First mate" (issue #100): one
// persistent, resumable Claude Code pane docked on every screen.
//
// What it reuses from claude-session.js, verbatim, rather than reinventing (the
// prompt and static allow/deny lists come from shared/first-mate-prompt.json):
//   * `ClaudeChild` — the same long-lived-process wrapper (spawn, stdout line
//     reader, `write`, `kill`);
//   * `buildFirstMateArgv` — First mate's argv, with
//     `firstMateAllowedTools(cwd)` as its `--allowedTools`;
//   * `chatSettingsObject`/`writeChatSettings` — the one settings-document
//     builder, called with `firstMateAllowedTools(cwd)` (the read tools, the
//     frakworks server's tools below and the memory rule) and `FIRST_MATE_DENIED_TOOLS`,
//     so no `ListAgents`/`SendMessage` and no other MCP tool reach this pane;
//   * `src/pane-events.js`'s `TurnLedger` — the "which result answers which
//     turn" bookkeeping, even though First mate has only one possible sender
//     (it holds no peer tools, so nothing SendMessage can reach it with).
//
// What it deliberately does *not* have, because the pane's identity is fixed
// (issue #100, acceptance criterion 3): rename and pin. Issue #185 relaxed
// that rule for reset only: `startNewConversation` drops the Claude session so
// the next turn starts fresh. Issue #257 changed what happens to the display
// history on reset: instead of a "New conversation started" marker row kept
// inline, the closed conversation is archived (`archiveCurrentConversation`,
// `first-mate-store.js`) into a bounded, read-only `conversations` list, and
// `history` resets to `[]`; the Filed list is unaffected either way. The tool
// policy is unchanged by it. Issue #486: an archived conversation keeps its
// Claude session id, and `resumeConversation` makes it live again. Issue #598:
// "Save for later" stores the live conversation in the engine (`savePayload`,
// sent by main.js), and `restoreSavedChat` makes a saved chat the live one,
// from this machine or another (src/saved-chats.js says how). Issue #820:
// "Open in Claude Code ⇗" hands the live conversation to a Claude Code session
// in a new iTerm2 window (`openInIterm`, src/first-mate-iterm.js), leaving the
// conversation here as it was. It sits beside Continue in terminal (#819),
// which copies a command instead (`handoffPayload`, src/first-mate-handoff.js).
//
// The tool policy: read-only, with one write (issue #290). First mate may file
// an ask (`POST /intake`) through `submit_ask` on the engine's frakworks MCP server (`POST /mcp/frakworks`, issue #541),
// passed with `--mcp-config` (`--strict-mcp-config` keeps every other server out). Why
// this one: "file a ticket for X" is a concierge's most basic order, and an
// intake changes nothing by itself — a refining Smith and the user's review
// still gate everything after it. Why this way: a named tool on a server
// with no others can be allowed exactly; a `Bash(curl …)` rule cannot be
// narrowed to one URL. The other write (issue #339): First mate may edit files
// in its own Claude Code auto-memory directory, `~/.claude/projects/<slug of
// cwd>/memory/`, through one `Edit(...)` rule from `firstMateAllowedTools(cwd)`,
// so it can keep memory files and `MEMORY.md` across conversations. For that its
// deny list is `FIRST_MATE_DENIED_TOOLS` (`CHAT_DENIED_TOOLS` without the bare
// `Write`/`Edit`, which would beat the allow), and `buildFirstMateArgv` passes
// `--setting-sources ""` so no user, project or local settings file can add a
// write rule; a write anywhere else is refused. The MCP config (the engine's URL, and the desktop's
// engine token as its `Authorization` header) is written before the first child starts, from
// `engineEnvFn`, which main.js wires to the signed-in token. A `submit_ask` result seen in the stream is
// recorded in the Filed list exactly as `fileTicket` records a button filing.
// The `File:` card and button stay as the fallback when the tool fails.
//
// First mate also reads (issue #352): `read_transcript` on the same server
// returns a ticket's newest Smith session, live or ended; the engine reads it
// from its own VictoriaMetrics and VictoriaLogs (engine.telemetry.*, issue
// #541), a port of the transcript pane's reader. It writes nothing.
//
// The model and effort are not fixed (issue #231): each turn, `send` resolves
// First mate's tier in main from agent-army.config.json's `first_mate` key
// (src/first-mate-tier.js: the standing tier). When that tier differs from the running child's, the child is
// ended and restarted with `--resume` on the new pair before the turn is
// written. A Fable turn whose result says the Fable limit is reached is
// retried once at tier 5, without touching the saved setting. There is no
// usage pre-check: that failure is the only Fable handling.
//
// A filing is announced in the chat (issue #927; the engine's event stream,
// since #971): the manager takes `intake`
// frames from the stream main holds (src/engine-events.js, `engineEvents`,
// injectable; no IPC, both are main), and a `filed` or `triage` frame whose
// `intakeId` is a `session.filed` entry not yet announced pushes one
// `Filed as #<n>` system row into the conversation that filed it (archived or
// live), exactly once. On every connect's `hello` (so at launch, and after the
// app was closed while the intake ran) it reads `GET /intake/<id>` (`fetchFlow`)
// once per unannounced entry under FILED_WATCH_MS old, which is the snapshot
// the stream's catch-up asks of every consumer; an ended intake found that way
// is announced the same way. No timer: there is no poll of the flow, armed or
// re-armed, under any condition. The engine's own "Telling the session that
// filed it" (#610) cannot serve this pane: it `SendMessage`s a named Claude
// Code session, and First mate's child has no name and no inbox between turns.
//
// First mate runs from fresh code (#960, built in part 2 of #973): each conversation has a `cwd`, stored with it
// (`first-mate-store.js`), which is the fresh clone of the repo selected when
// it started (`<freshDir>/<owner>/<name>`, kept by src/fresh-code.js, which
// main passes as `freshCode`), or `<freshDir>` itself in All repos. A resumed,
// popped or restored conversation starts in its stored cwd; a conversation
// from before the build has `cwd: null` and keeps the legacy `cwd` option
// (main's REPO_DIR). `_startChild` runs the child there, with
// `firstMateAllowedTools(cwd)` so the memory rule is the clone's slug, and
// passes the clone's `CLAUDE.md` and `AGENTS.md` (`readRepoInstructions`) for
// the prompt; when either file's blob changed since the child started, `send`
// restarts the child with `--resume` before the turn, as a tier change does.
// A first turn in a repo whose clone is not there yet waits for it, with a
// notice on the strip; after that, never. The handoff, "Open in Claude Code"
// and the saved-chat resumability all use the conversation's cwd.
"use strict";

const { EventEmitter } = require("node:events");
const crypto = require("node:crypto");
const os = require("node:os");
const fs = require("node:fs");
const path = require("node:path");

const {
  ClaudeChild, buildFirstMateArgv, writeChatSettings, writeFrakworksMcpConfig,
  firstMateAllowedTools, FIRST_MATE_DENIED_TOOLS, FIRST_MATE_FILING_TOOLS, ENGINE_MCP_SERVERS,
  FIRST_MATE_TURN_TIMEOUT_MS, INTERRUPTED_TEXT, assistantText,
} = require("./claude-session");
const { TurnLedger, userMessageUuids } = require("./pane-events");
const { normalizeFileLine } = require("./file-line");
const {
  FIRST_MATE_HISTORY_LIMIT, FILED_LIMIT, saveFirstMate, loadFirstMate, freshSession,
  archiveCurrentConversation, capTotal, stickyRepo,
} = require("./first-mate-store");
const { resolveTier: defaultResolveTier } = require("./engine-tiers");
const { validateImages, buildContent } = require("./first-mate-images");
const {
  submitIntake: defaultSubmitIntake, fetchFlow: defaultFetchFlow, engineUrl,
  startFirstMateSession, sendFirstMateMessage, endFirstMateSession,
} = require("./engine-data");
const { RemoteSession } = require("./first-mate-remote");
const { chatTitle, seedPreamble, sessionFileExists: defaultSessionFileExists } = require("./saved-chats");
const itermMod = require("./first-mate-iterm");
const firstMateTier = require("./first-mate-tier");
const { FALLBACK_TIER, isFableLimit } = firstMateTier;

// After this many turns with nothing filed, the strip shows the "Long
// thread." hint (acceptance criterion 9). A named constant, like the timeout.
const UNFILED_TURN_HINT = 3;

// The window of the one-shot read on `hello` (#927, #971): an unannounced
// filing older than this is not read again.
const FILED_WATCH_MS = 24 * 60 * 60 * 1000;
// The `intake` frame states that carry the refined ticket (engine/README.md, "Events").
const FILED_STATES = ["filed", "triage"];
// The states that end an intake: `ended` (`closeIntake`, with `ticket` when
// one was filed), `cancelled` (never with one).
const ENDED_STATES = ["ended", "cancelled"];

// The repo a ticket reference without one names (src/renderer/ticket-refs.js,
// via ticket-refs.js's FRAMEWORK_REPO): a ticket here is announced as a bare
// `#<n>`, one anywhere else as `<owner>/<name>#<n>`, so the pill opens it.
const APP_REPO = "codefrak-oss/frakworks";
const TICKET_URL = /^https:\/\/github\.com\/([\w.-]+\/[\w.-]+)\/issues\/\d+/;

/** The `Filed as …` row's text for ticket `n` at `url`. */
function filedRowText(n, url) {
  const m = TICKET_URL.exec(String(url || ""));
  const repo = m ? m[1] : APP_REPO;
  return `Filed as ${repo === APP_REPO ? "" : repo}#${n}`;
}

/**
 * The ticket an `ended` frame carries, as `_filedEnded`'s `{ticket, ticketUrl}`:
 * `closeIntake` publishes `ticket` but no `ticketUrl`, so the URL is built from
 * the frame's `repo`. `{}` for a frame with no integer ticket (`cancelled`, or
 * an intake that ended without filing).
 */
function endedFrameTicket(data) {
  if (!Number.isInteger(data.ticket) || typeof data.repo !== "string" || !/^[\w.-]+\/[\w.-]+$/.test(data.repo)) return {};
  return { ticket: data.ticket, ticketUrl: `https://github.com/${data.repo}/issues/${data.ticket}` };
}

/** Whether filed entry `f` is still unannounced at `now`: no ticket, not ended, under a day old. */
const isUnannounced = (f, now) => !f.ended_at && !f.announced_at && now - (Number(f.at) || 0) < FILED_WATCH_MS;

// The filing tool whose results land in the Filed list.
const SUBMIT_TOOL = FIRST_MATE_FILING_TOOLS[0];

// Open in Claude Code (#820): the seed's first line, in place of the saved
// chats preamble's, and the row the strip keeps after an iTerm2 handoff.
const ITERM_SEED_HEAD = "[Handed off from First mate in the frakworks desktop app. " +
  "The conversation so far, for context; continue from it:]";
// ... for a conversation that ran in a session pod (#2523), seeded only.
const ITERM_POD_SEED_HEAD = "[Handed off from First mate in the frakworks desktop app. It ran in a pod on the cluster, " +
  "where its session file stays, so this is a new session; the conversation so far, for context; continue from it:]";
const ITERM_ROW = "Handed off to Claude Code in iTerm2.";
// A conversation in a session pod (#2521, #2523): each desktop-only feature's
// behaviour there is observability/desktop/README.md's "First mate" table.
const REMOTE_TIER_NOTE = "applies to the next conversation";
const REMOTE_CANCEL_ROW = "Cancelled here: the pod finishes the turn on its own, and its answer is not shown.";
const REMOTE_EXPIRED_ROW = "The session pod expired (404): a new session starts in this strip with the next message.";
const ITERM_BUSY = "First mate is still working. Wait for it, or cancel.";
const ITERM_EMPTY = "No conversation to hand off yet.";

/** The transcript a seeded iTerm2 session is given first: `seedPreamble` with its opening line reworded. */
function itermSeed(history, { pod = false } = {}) {
  return seedPreamble({ title: null, messages: history }, undefined, { opening: pod ? ITERM_POD_SEED_HEAD : ITERM_SEED_HEAD });
}

// Where the engine is, when main passes no `engineEnvFn` (tests): the
// Engine URL setting as src/engine-data.js holds it.
// `ENGINE_TOKEN` (#2206) is the desktop's engine token, which only main's
// `engineEnvFn` supplies: it goes into the 0600 MCP config's `Authorization`
// header and nowhere else, never into the child's environment or a log line.
const envEngineEnv = () => ({ engineUrl: engineUrl() });

// A tool_result block's text, whether `content` is a string or text blocks.
const toolResultText = (block) => {
  const c = block && block.content;
  if (typeof c === "string") return c;
  if (Array.isArray(c)) return c.filter((b) => b && b.type === "text").map((b) => b.text || "").join("");
  return "";
};

// The repo line each First mate turn starts with (#577), so a filing names
// the right repo. Since #2343 it is the chat's own repo (`session.repo`, the
// chip in the pane's header), not the switcher's: a chat started in All repos
// has none until the user or a kick-starter sets it, and the line then tells
// First mate not to assume one. `changedFrom` is the repo the user changed it
// from since the last turn (null: "No repo yet").
function repoLine(repo, { changedFrom } = {}) {
  const changed = changedFrom === undefined ? ""
    : ` The user changed this chat's repo from ${changedFrom || "none"} to ${repo || "none"}.`;
  return repo
    ? `[Chat repo: ${repo}.${changed} File and read there unless the user names another; always pass \`repo\`: no tool has a default repo.]`
    : `[No repo chosen for this chat yet (All repos).${changed} Do not assume one: ask the user which repo before any repo-scoped tool call, and always pass \`repo\`.]`;
}

function withRepoLine(text, line) {
  if (!line) return text;
  return text ? `${line}\n\n${text}` : line;
}

const REPO_RE = /^[\w.-]+\/[\w.-]+$/;

// The repo a kick-starter's text names (renderer/first-mate.jsx's
// `Discuss ticket o/n#1`, `Discuss proposal o/n#1`, `Discuss file o/n:path`),
// or null.
function kickStarterRepo(text) {
  const m = /^Discuss (?:ticket|proposal|file) ([\w.-]+\/[\w.-]+)[#:]/.exec(String(text || ""));
  return m ? m[1] : null;
}

class FirstMateManager extends EventEmitter {
  constructor({ dir, command, launchArgs, cwd, spawnFn, settingsPath, turnTimeoutMs,
                submitIntakeFn, resolveTierFn, loadTiersFn, now, configFile, mcpConfigPath, engineEnvFn, pickAccountFn, mcpServersFn,
                repoFn, reposFn, hostname, iterm, detectItermFn, openInItermFn, writeHandoffFn, sweepHandoffDirFn,
                sessionFileExistsFn, home, fetchFlowFn, engineEvents, freshCode, freshDir, checkoutDir, lineageFn,
                hostingFn, bearer, sessionApi } = {}) {
    super();
    // Where a new conversation runs (#2521): `hostingFn()` resolves to the
    // engine's `{ firstMateHosting: { hosting } }` or `{ error }` (main wires
    // src/engine-data.js's `fetchFirstMateHosting`), read when a conversation
    // starts its runtime, never at launch. Without one every conversation is
    // local. `bearer()` is main's: it resolves to the sign-in bearer's
    // `Authorization` value or `{ error }` when not signed in (#100103); the
    // token stays in main. `sessionApi` is the session API's { start, send,
    // end }, injectable for tests.
    this.hostingFn = hostingFn || null;
    this.bearer = bearer || null;
    this.sessionApi = sessionApi || { start: startFirstMateSession, send: sendFirstMateMessage, end: endFirstMateSession };
    // Fresh code (#960): the clone keeper, the clones' root and the user's own
    // checkout First mate may read (null when packaged with no
    // AGENT_ARMY_REPO_DIR). Without a `freshDir` (tests, a run with no stream)
    // every conversation keeps the legacy `cwd` and the prompt is as before.
    this.freshCode = freshCode || null;
    this.freshDir = freshDir || null;
    this.checkoutDir = checkoutDir === undefined ? (cwd || null) : checkoutDir;
    // Open in Claude Code (#820): iTerm2 as main detected it at startup (or
    // detected here), and the iTerm2 module's pieces, injectable for tests.
    this.detectItermFn = detectItermFn || itermMod.detectIterm;
    this.iterm = iterm || this.detectItermFn();
    this.openInItermFn = openInItermFn || itermMod.openInIterm;
    this.writeHandoffFn = writeHandoffFn || itermMod.writeHandoff;
    this.sweepHandoffDirFn = sweepHandoffDirFn || itermMod.sweepHandoffDir;
    this.sessionFileExistsFn = sessionFileExistsFn || defaultSessionFileExists;
    this.home = home || null;
    this.dir = dir;
    this.command = command || "claude";
    this.launchArgs = launchArgs || [];
    this.cwd = cwd;
    this.spawnFn = spawnFn;
    this.turnTimeoutMs = turnTimeoutMs || FIRST_MATE_TURN_TIMEOUT_MS;
    // The settings file is rewritten for a conversation whose cwd differs from
    // the one it was written for (the memory rule names the cwd's slug), only
    // when this manager wrote it itself.
    this.ownSettings = !settingsPath;
    this.settingsCwd = cwd;
    this.settingsPath = settingsPath ||
      writeChatSettings(dir ? path.join(dir, "first-mate") : null,
        { allowedTools: firstMateAllowedTools(cwd), deniedTools: FIRST_MATE_DENIED_TOOLS });
    // The filing server's `--mcp-config` (issue #290): given, or written by
    // `_ensureMcpConfig` before the first child starts, next to the settings.
    this.mcpConfigPath = mcpConfigPath || null;
    this.engineEnvFn = engineEnvFn || envEngineEnv;
    // The repo's own MCP servers (#1801): `mcpServersFn(repo)` resolves to
    // `{ servers }` (the engine's `mcpServers`, secrets opened) or `{ error }`.
    // The config is rewritten when a child starts for another repo than the
    // one it was written for; the servers' names go in the allow list.
    this.mcpServersFn = mcpServersFn || null;
    this.mcpConfigRepo = undefined;
    this.mcpConfigDirty = false;
    this.repoServerNames = [];
    this.settingsServers = "";
    this.submitIntakeFn = submitIntakeFn || defaultSubmitIntake;
    // `GET /intake/<id>` for the one-shot read on `hello` (#927, #971), and the reads in flight.
    this.fetchFlowFn = fetchFlowFn || defaultFetchFlow;
    this.filedReads = new Set();
    // The engine's event stream (src/engine-events.js): `"frame"` and `"hello"`.
    // main.js passes the stream it holds; without one nothing is announced
    // until the next launch gives one (tests, or a run with no stream).
    this.engineEvents = engineEvents || null;
    this._onFrame = (f) => { if (f && f.type === "intake") this._onIntakeFrame(f.data); };
    this._onHello = () => { this.catchUpFiled(); };
    // The engine's account pick (engine/README.md, "First mate's account"):
    // `(model) => { ok, account, envVar, token } | { error }`. main.js wires
    // src/engine-data.js's `pickAccount`; without one the child runs on this
    // machine's own Claude login, as before.
    this.pickAccountFn = pickAccountFn || null;
    // The conversation's HITLP lineage (engine/README.md, "Lineage"): `(ask) =>
    // { ok, lineage } | { error }`, main.js wires src/engine-data.js's
    // `fetchLineage`. Asked once per conversation, before its first child; the
    // text is written as it came to a read-only file the child is told of in
    // `FM_LINEAGE_FILE`, never parsed or rewritten here.
    this.lineageFn = lineageFn || null;
    this.resolveTierFn = resolveTierFn || defaultResolveTier;
    // Reloads the engine's tier map (main wires src/engine-tiers.js's `loadTiers`); none in tests.
    this.loadTiersFn = loadTiersFn || null;
    // The repo switcher's selection (#577): `owner/name`, or null for All
    // repos. When given, each turn carries a line naming it, and the File
    // button files there unless the card was given a repo.
    this.repoFn = repoFn || null;
    // The target repos (#100116), an array, or null while unknown: a sticky
    // last-used repo is only reused when it is listed here.
    this.reposFn = reposFn || null;
    this.now = now || (() => Date.now());
    // The host a saved chat says it was saved from (#598), display only.
    this.hostname = hostname || (() => os.hostname());
    // agent-army.config.json, where the tier setting lives. Without one the
    // standing default (tier 8) is used and nothing is written.
    this.configFile = configFile || null;
    this.session = freshSession();
    this.loadError = null;
    this.rt = this._freshRuntime();
  }

  init() {
    const { session, error } = loadFirstMate(this.dir);
    this.loadError = error;
    this.session = session;
    // A conversation not started yet runs where its repo is (#2363): one
    // saved before that fix (cwd at the clones' root) is repaired here.
    this._followChatRepo();
    // A launcher that never ran (#820) is swept once it is an hour old.
    if (this.dir) {
      try { this.sweepHandoffDirFn(this._itermDir(), itermMod.HANDOFF_SWEEP_MS); } catch { /* best effort */ }
    }
    // Filings whose ticket is not known yet are announced by the stream's
    // frames, and read once on each connect's hello.
    if (this.engineEvents) {
      this.engineEvents.on("frame", this._onFrame);
      this.engineEvents.on("hello", this._onHello);
    }
    return this;
  }

  _itermDir() { return path.join(this.dir, itermMod.HANDOFF_DIR_NAME); }

  _freshRuntime() {
    return {
      child: null, pending: null, busy: false, recovery: null, streaming: "", notice: null,
      ledger: new TurnLedger(), hadReader: false,
      // The live `File:` card, bound to the turn it came from. Cleared on the
      // next reply, on a successful file, or on cancel/new-turn.
      fileCard: null, fileBusy: false, fileError: null,
      // The running child's `{ tier, model, effort, source }`, and the
      // one-time notice a tier restart or a Fable fallback leaves.
      childEff: null, tierNotice: null,
      // The engine account the next child starts on, `{ name, envVar, token }`
      // (the token never leaves main), and the notice when the engine gave
      // none and the child fell back to this machine's own login.
      account: null, accountNotice: null,
      // The one line when the repo's own MCP servers could not be had (#1801).
      mcpNotice: null,
      // `submit_ask` tool_use ids seen in the stream, awaiting their result.
      filingCalls: new Set(),
      // Fresh code (#960): the cwd the running child was started in, and the
      // blobs of the instruction files it was given (`file=blob@…`).
      childCwd: null, childInstructions: null,
    };
  }

  // ---------------------------------------------------------------------------
  // Fresh code (#960): the conversation's cwd
  // ---------------------------------------------------------------------------

  /** The directory the conversation's child runs in: its stored cwd, or the legacy one. */
  conversationCwd() { return this.session.cwd || this.cwd; }

  // The cwd a conversation started now gets: the selected repo's clone, the
  // clones' root in All repos, or null (no freshDir: the legacy cwd stands).
  _freshCwd(repo = this.repoFn ? this.repoFn() : null) {
    if (!this.freshDir) return null;
    return repo ? path.join(this.freshDir, ...String(repo).split("/")) : this.freshDir;
  }

  // The `owner/name` whose clone `dir` is, or null (`<freshDir>` itself, the legacy cwd).
  _repoOfCwd(dir) {
    if (!this.freshDir || !dir) return null;
    const rel = path.relative(path.resolve(this.freshDir), path.resolve(dir)).split(path.sep);
    return rel.length === 2 && rel[0] && rel[1] && !rel[0].startsWith("..") ? rel.join("/") : null;
  }

  // Whether the conversation has had no turn yet: its cwd may still move.
  _unstarted() { return !this.session.history.length && !this.session.claude_session_id; }

  // Before its first turn a conversation's cwd follows the chat's repo
  // (#2363): the picker's repo is the one its MCP servers, instructions and
  // allow-list come from. Once started, the cwd stays.
  _followChatRepo() {
    if (!this.repoFn || !this.freshDir || !this._unstarted() || this.session.repo === undefined) return;
    this.session.cwd = this._freshCwd(this.session.repo);
  }

  /** The strip's warning when the chat's repo is not the one its cwd is a clone of (#2363), or null. */
  repoMismatch() {
    if (!this.repoFn || !this.freshDir || this._unstarted()) return null;
    const want = this.chatRepo();
    const have = this._repoOfCwd(this.conversationCwd());
    if (!want || want === have) return null;
    return `This conversation runs in ${have || "the clones' root"}, not ${want}: ` +
      `${want}'s MCP servers and instructions are not loaded. Start a new conversation to use them.`;
  }

  // The instruction files of `dir` for the prompt, and the key `send` compares.
  _instructions(dir) {
    if (!this.freshCode) return { list: [], key: "" };
    let list = [];
    try { list = this.freshCode.readRepoInstructions(dir) || []; } catch { list = []; }
    return { list, key: list.map((i) => `${i.file}=${i.blob}`).join("@") };
  }

  /** Whether `dir` is a conversation's cwd (live or archived) or a child runs in it: main's `onRepoRemoved` check. */
  inUse(dir) {
    const d = path.resolve(String(dir));
    const same = (p) => Boolean(p) && path.resolve(p) === d;
    if (same(this.session.cwd)) return true;
    if (this.session.conversations.some((c) => same(c.cwd))) return true;
    return Boolean(this.rt.child && this.rt.child.alive && same(this.rt.childCwd));
  }

  _save() {
    try { saveFirstMate(this.dir, this.session); } catch (e) { this.loadError = `first-mate.json unwritable: ${e.message}`; }
  }

  // `images` is a count only (#368): the base64 data goes to the child and is
  // never kept in history, so first-mate.json stays bounded.
  _push(role, text, images = 0) {
    const row = { role, text: String(text), at: this.now() };
    if (images > 0) row.images = images;
    this.session.history.push(row);
    capTotal(this.session);
  }

  // The chat's repo (#2343): `owner/name`, or null when none is chosen (or
  // with no `repoFn`). A conversation from before it (`session.repo` absent)
  // follows the switcher, as it did.
  chatRepo() {
    if (!this.repoFn) return null;
    return this.session.repo === undefined ? this.repoFn() || null : this.session.repo;
  }

  // A new chat's repo: the switcher's, or in All repos the last one the user
  // chose or sent a turn in (#100116), while `reposFn` still lists it as a
  // target repo; a repo no longer listed is forgotten. The chip shows it
  // tagged "last used" until the user picks one.
  _startChatRepo() {
    if (!this.repoFn) return;
    const picked = this.repoFn() || null;
    const sticky = picked ? null : stickyRepo(this.session, this.reposFn ? this.reposFn() : null);
    this.session.repo = picked || sticky;
    this.rt.repoSticky = Boolean(sticky);
    this.rt.repoChangedFrom = undefined;
  }

  _turnRepoLine() {
    if (!this.repoFn) return null;
    return repoLine(this.chatRepo(), { changedFrom: this.rt.repoChangedFrom });
  }

  // The chip in the pane's header (#2343): set or change the chat's repo, or
  // clear it to "No repo yet" with null. Mid-chat, the next turn's line says
  // it changed. Refused for anything but `owner/name` or null.
  setChatRepo(repo) {
    const next = repo == null || repo === "" ? null : String(repo);
    if (next !== null && !REPO_RE.test(next)) return { ok: false, error: `not a repo: ${repo}` };
    const prev = this.chatRepo();
    this.session.repo = next;
    this.rt.repoSticky = false;
    if (next) this.session.last_repo = next;
    this._followChatRepo();
    if (prev !== next && this.session.history.length && this.rt.repoChangedFrom === undefined) this.rt.repoChangedFrom = prev;
    if (this.rt.repoChangedFrom === next) this.rt.repoChangedFrom = undefined;
    this._save();
    this._emitUpdate();
    return { ok: true, repo: next };
  }

  snapshot() {
    const rt = this.rt;
    return {
      // The chat's repo (#2343), for the header's chip; `chat_repo_on` is
      // false without a repo switcher (no chip).
      chat_repo: this.chatRepo(),
      chat_repo_on: Boolean(this.repoFn),
      chat_repo_sticky: Boolean(this.rt.repoSticky && this.chatRepo()),
      version: 1,
      error: this.loadError || null,
      session_generation: this.session.session_generation,
      has_session: Boolean(this.session.claude_session_id),
      runtime: this.session.runtime || null,
      // A pod's conversation (#2523): the repo line, the `resuming…` state
      // while the engine thaws a frozen session, and its pod-status detail.
      remoteState: rt.remoteState || null,
      remote_detail: rt.remoteDetail || null,
      running: Boolean(rt.child && rt.child.alive),
      busy: rt.busy,
      recovery: rt.recovery,
      notice: rt.notice || this.repoMismatch(),
      repo_mismatch: this.repoMismatch(),
      streaming: rt.streaming,
      history: this.session.history.slice(-FIRST_MATE_HISTORY_LIMIT),
      file: rt.fileCard ? { ...rt.fileCard } : null,
      file_busy: rt.fileBusy,
      file_error: rt.fileError,
      filed: this.session.filed.slice(0, FILED_LIMIT),
      turns_since_filed: this.session.turns_since_filed,
      unfiled_hint: this.session.turns_since_filed >= UNFILED_TURN_HINT,
      tier: this._snapshotTier(),
      tier_notice: rt.tierNotice,
      // The engine account the child runs on (its name only), or null on this
      // machine's own login, with why.
      account: rt.account ? rt.account.name : null,
      account_notice: rt.accountNotice,
      // The repo's own MCP servers not loaded (#1801): the child has the engine's two only.
      mcp_notice: rt.mcpNotice,
      // Save for later (#598): enabled with a non-empty conversation.
      can_save: this.session.history.length > 0,
      saved_chat_id: this.session.saved_chat_id || null,
      // Continue in terminal (#819): off while empty or while a turn runs.
      can_handoff: this.session.history.length > 0 && !rt.busy,
      can_pop: Boolean(this._popTarget()),
      // Open in Claude Code (#820): a conversation, no running turn, iTerm2 here.
      can_open_in_iterm: this.session.history.length > 0 && !rt.busy && Boolean(this.iterm.available),
      iterm: { available: Boolean(this.iterm.available), why: this.iterm.available ? null : this.iterm.why || null },
    };
  }

  // ---------------------------------------------------------------------------
  // Open in Claude Code ⇗ (#820)
  // ---------------------------------------------------------------------------

  // How the terminal gets the conversation: the First mate session itself,
  // forked, when its file is on this machine; else the transcript as a seed.
  itermPlan() {
    const id = this._remote() ? null : this.session.claude_session_id;
    const opts = this.home ? { home: this.home } : undefined;
    if (id && this.sessionFileExistsFn(this.conversationCwd(), id, opts)) return { mode: "resume", resumeId: id };
    return { mode: "seed", seed: itermSeed(this.session.history, { pod: this._remote() }) };
  }

  // One click: write the launcher (and seed) under <dir>/iterm, ask iTerm2
  // to open a window running it, and keep one system row. The conversation
  // stays live and untouched: its session id and history are not changed.
  // Refused, changing nothing, while a turn runs, with nothing to hand off, or
  // without iTerm2 (re-detected first, so an install during the run is seen).
  async openInIterm() {
    const rt = this.rt;
    if (rt.busy) return { ok: false, error: ITERM_BUSY };
    if (!this.session.history.length) return { ok: false, error: ITERM_EMPTY };
    if (!this.iterm.available) {
      this.iterm = this.detectItermFn();
      if (!this.iterm.available) return { ok: false, error: this.iterm.why };
      this._emitUpdate();
    }
    const plan = this.itermPlan();
    let paths;
    try {
      paths = this.writeHandoffFn(this._itermDir(), { ...plan, claude: this.command, cwd: this.conversationCwd() });
    } catch (e) {
      return { ok: false, error: `could not write the launcher: ${e.message}` };
    }
    const r = await this.openInItermFn(paths.launcherPath, { seedPath: paths.seedPath });
    if (!r || !r.ok) return { ok: false, error: (r && r.error) || "iTerm2 did not open the window" };
    this._push("system", ITERM_ROW);
    this._save();
    this._emitUpdate();
    return { ok: true, mode: plan.mode };
  }

  // ---------------------------------------------------------------------------
  // The tier (issue #231)
  // ---------------------------------------------------------------------------

  // What the next turn runs at. Read from the file every time, so a standing
  // tier set elsewhere applies at the next turn.
  _effective() {
    return firstMateTier.resolveEffective(this.configFile, this.now(), this.resolveTierFn);
  }

  // The effective tier for the UI; while the Fable fallback's child is the
  // running one, that child's tier (source "fallback") instead.
  _snapshotTier() {
    const rt = this.rt;
    if (rt.child && rt.child.alive && rt.childEff && rt.childEff.source === "fallback") return { ...rt.childEff };
    return this._effective();
  }

  tierState() {
    const s = firstMateTier.readFirstMateSettings(this.configFile);
    return {
      standing: s.tier,
      effective: this._effective(),
      error: s.error,
    };
  }

  _afterTierWrite(r) {
    if (r.error) return { ok: false, error: r.error };
    this._emitUpdate();
    return { ok: true, ...this.tierState() };
  }

  setStandingTier(tier) {
    return this._afterTierWrite(firstMateTier.setStandingTier(this.configFile, tier, { resolveTierFn: this.resolveTierFn }));
  }

  _emitUpdate() { this.emit("update", this.snapshot()); }

  _killChild(why, { detach = false } = {}) {
    const rt = this.rt;
    if (rt.child) {
      rt.child.removeAllListeners();
      // A pod's session is only let go of (#2523): it stays on the engine,
      // which freezes it, so History, Back and a cancel go on in it.
      if ((detach || rt.child instanceof RemoteSession) && typeof rt.child.detach === "function") rt.child.detach();
      else rt.child.kill();
    }
    rt.child = null;
    rt.busy = false;
    rt.streaming = "";
    rt.remoteState = null;
    rt.remoteDetail = null;
    rt.ledger.clear();
    if (rt.pending) {
      const { resolve } = rt.pending;
      rt.pending = null;
      resolve({ ok: true, interrupted: true, text: INTERRUPTED_TEXT, why: why || null });
    }
  }

  cancel() {
    // A turn in progress ends with the interrupted row in the transcript,
    // as Escape in Claude Code leaves it (#815).
    const wasBusy = this.rt.busy;
    // A pod's turn (#2523) cannot be interrupted from here: the turn is marked
    // interrupted, and the next session drops its later lines.
    const remote = this._remote() && this.rt.child instanceof RemoteSession;
    this.session.session_generation += 1;
    this._killChild("cancelled");
    if (wasBusy) this._push("assistant", INTERRUPTED_TEXT);
    if (wasBusy && remote) {
      this.rt.drainRemote = true;
      this._push("system", REMOTE_CANCEL_ROW);
    }
    this._save();
    this._emitUpdate();
    return { ok: true };
  }

  // Issue #185: the next turn
  // starts a fresh `claude` session instead of `--resume`-ing this one.
  // Issue #257: the closed conversation is archived instead of marked inline,
  // so `history` (and snapshot().history) is empty right after this returns.
  startNewConversation() {
    const rt = this.rt;
    this._killChild("starting a new conversation");
    // Archived first, so the closed conversation keeps its session id (#486).
    archiveCurrentConversation(this.session);
    this.session.claude_session_id = null;
    this.session.saved_chat_id = null;
    this.session.conversation_key = null;
    this.session.seed = null;
    this.session.lineage = null;
    // Where it runs is read from the engine when its first turn starts (#2521).
    delete this.session.runtime;
    delete this.session.remote_session_id;
    // The new conversation runs in the then-selected repo's clone (#960).
    this.session.cwd = this._freshCwd();
    // Its repo (#2343) is the selected one, or none yet in All repos.
    this._startChatRepo();
    this.session.session_generation += 1;
    this.session.turns_since_filed = 0;
    rt.recovery = null;
    rt.fileCard = null;
    rt.fileError = null;
    rt.notice = "New conversation started.";
    this._save();
    this._emitUpdate();
    return { ok: true };
  }

  // Issue #486: make past conversation `id` the live one again. The current
  // one is archived with its session id (not discarded), `id` leaves the
  // archive, and the next turn `--resume`s its stored session. Nothing is
  // added or dropped, so capTotal's combined count is unchanged. A session
  // that no longer exists takes the ordinary `resume_failed` path in
  // `_onExit`. Refused, changing nothing, for an unknown id or a conversation
  // archived before #486 (no stored session id).
  resumeConversation(id) {
    const target = this.session.conversations.find((c) => c.id === Number(id));
    if (!target) return { ok: false, error: `there is no past conversation ${id}` };
    if (!target.claude_session_id && !(target.runtime === "kubernetes" && target.remote_session_id)) {
      return { ok: false, error: "this conversation was archived before resume existed, so it has no session to resume" };
    }
    return this._makeLive(target, "resuming a past conversation", "Resumed a past conversation.");
  }

  // Issue #601: "Back to previous conversation", one click. The newest past
  // conversation with a stored session id (the archive is newest-first, so the
  // first such one), or null. Pre-#486 conversations are skipped, as the
  // History panel's disabled Resume already does.
  _popTarget() {
    return this.session.conversations.find((c) => c.claude_session_id || (c.runtime === "kubernetes" && c.remote_session_id)) || null;
  }

  // A swap, not a discard: the live conversation is archived exactly as
  // resumeConversation archives it, so it becomes conversations[0] and a
  // second pop comes straight back to it. Refused, changing nothing, when
  // there is nothing to go back to.
  popConversation() {
    const target = this._popTarget();
    if (!target) return { ok: false, error: "there is no past conversation to go back to" };
    return this._makeLive(target, "going back to the previous conversation", "Back to the previous conversation.");
  }

  // resumeConversation's swap, shared with popConversation (#601).
  _makeLive(target, why, notice) {
    const rt = this.rt;
    this._killChild(why);
    archiveCurrentConversation(this.session);
    this.session.conversations = this.session.conversations.filter((c) => c !== target);
    this.session.history = target.messages;
    this.session.claude_session_id = target.claude_session_id;
    // Where it started (#2521): a pod's conversation goes on in its session.
    if (target.runtime) {
      this.session.runtime = target.runtime;
      this.session.remote_session_id = target.remote_session_id || null;
    } else {
      delete this.session.runtime;
      delete this.session.remote_session_id;
    }
    this.session.lineage = null;
    this.session.saved_chat_id = target.saved_chat_id || null;
    this.session.conversation_key = target.conversation_key || null;
    // Its stored cwd (#960); null on one from before the build means the legacy cwd.
    this.session.cwd = target.cwd || null;
    // Its repo (#2343); absent on one from before, which follows the switcher.
    this.session.repo = target.repo;
    this.rt.repoChangedFrom = undefined;
    this.session.seed = null;
    this.session.session_generation += 1;
    this.session.turns_since_filed = 0;
    capTotal(this.session);
    rt.recovery = null;
    rt.fileCard = null;
    rt.fileError = null;
    rt.notice = notice;
    this._save();
    this._emitUpdate();
    return { ok: true };
  }

  // Issue #598, "Save for later": the `PUT /saved-chats/<id>` body for the
  // live conversation, and its id. The id is minted the first time and kept
  // on the session (and archived and resumed with it), so saving the same
  // conversation again updates its save. Null with nothing to save.
  savePayload() {
    const history = this.session.history;
    if (!history.length) return null;
    if (!this.session.saved_chat_id) {
      this.session.saved_chat_id = crypto.randomUUID();
      this._save();
    }
    const eff = this._effective();
    return {
      id: this.session.saved_chat_id,
      body: {
        title: chatTitle(history),
        startedAt: new Date(Number(history[0].at) || this.now()).toISOString(),
        repo: this.chatRepo(),
        tier: eff && Number.isInteger(eff.tier) ? eff.tier : null,
        claudeSessionId: this.session.claude_session_id || null,
        // Where it ran (#2523), on a pod's chat only: its session file is in the pod.
        ...(this._remote() ? { runtime: "kubernetes" } : {}),
        savedFrom: this.hostname() || null,
        messages: history.slice(-FIRST_MATE_HISTORY_LIMIT).map((m) => {
          const row = { role: m.role, text: String(m.text), at: Number(m.at) || 0 };
          if (m.images > 0) row.images = m.images;
          return row;
        }),
      },
    };
  }

  // Issue #819, "Continue in terminal": the live conversation as a saved
  // chat's shape (`{ id, title, startedAt, repo, tier, claudeSessionId,
  // savedFrom, messages }`), for main to build the hand-off from. The id is
  // the one Save for later uses, minted the same way. Null while empty, and
  // while a turn runs: the session file would not yet hold the reply.
  // `cwd` (#960) is the conversation's, for the hand-off's `cd` and its
  // resumability check; it is main's and never part of the saved-chat body.
  handoffPayload() {
    if (this.rt.busy) return null;
    const p = this.savePayload();
    return p ? { id: p.id, ...p.body, cwd: this.conversationCwd() } : null;
  }

  // Issue #598: make saved chat `chat` (the engine's `GET /saved-chats/<id>`)
  // the live conversation. The live one is archived first with its session id,
  // exactly as resumeConversation does. `resumable` (main.js, from
  // src/saved-chats.js `sessionFileExists`) says the chat's Claude session is
  // on this machine: the next turn then `--resume`s it, and a resume that
  // still fails takes the ordinary `resume_failed` path. Otherwise the session
  // is on another machine: the next turn starts a fresh session whose first
  // message starts with the transcript (`seedPreamble`). `cwd` (#960) is the
  // directory main found the session in (the clone of the chat's repo, or the
  // legacy checkout); without one the restored conversation runs in the
  // selected repo's clone.
  restoreSavedChat(chat, { resumable = false, cwd = undefined } = {}) {
    const messages = chat && Array.isArray(chat.messages) ? chat.messages : [];
    if (!chat || !chat.id || !messages.length) return { ok: false, error: "that saved chat has no messages to restore" };
    const rt = this.rt;
    this._killChild("restoring a saved chat");
    archiveCurrentConversation(this.session);
    this.session.history = messages.map((m) => {
      const row = { role: String(m.role), text: String(m.text), at: Number(m.at) || 0 };
      if (m.images > 0) row.images = Math.floor(m.images);
      return row;
    });
    // A chat that ran in a pod (#2523) always seeds: its session file is there.
    const resumes = Boolean(resumable && chat.claudeSessionId && chat.runtime !== "kubernetes");
    this.session.claude_session_id = resumes ? String(chat.claudeSessionId) : null;
    this.session.seed = resumes ? null : seedPreamble(chat);
    // A restored chat runs where the hosting says when its next turn starts (#2521).
    delete this.session.runtime;
    delete this.session.remote_session_id;
    this.session.saved_chat_id = String(chat.id);
    this.session.lineage = null;
    this.session.conversation_key = null;
    if (this.repoFn) this.session.repo = typeof chat.repo === "string" && REPO_RE.test(chat.repo) ? chat.repo : null;
    // Without a stored cwd it runs in its own repo's clone (#2363), else the selected one.
    this.session.cwd = cwd === undefined ? this._freshCwd((this.repoFn && this.session.repo) || undefined) : (cwd || null);
    rt.repoChangedFrom = undefined;
    this.session.session_generation += 1;
    this.session.turns_since_filed = 0;
    capTotal(this.session);
    rt.recovery = null;
    rt.fileCard = null;
    rt.fileError = null;
    rt.notice = `Restored saved chat “${chat.title || chatTitle(this.session.history)}”.` + (resumes ? "" : chat.runtime === "kubernetes"
      ? " It ran in a pod on the cluster; the next turn starts a new conversation given this transcript."
      : chat.claudeSessionId
      ? " Its Claude session is on another machine; the next turn starts a fresh session given this transcript."
      : " It has no Claude session; the next turn starts a fresh session given this transcript.");
    this._save();
    this._emitUpdate();
    return { ok: true, resumed: resumes };
  }

  // Writes the MCP config once, with the engine URL and login main resolved. A failure
  // leaves the child without its filing server; the prompt's `File:` fallback
  // then still gives the user the button.
  //
  // With `mcpServersFn` (#1801) the repo's own servers are added beside the
  // engine's two; an engine that cannot hand them over leaves the two and a
  // one-line notice naming the repo, and the next child start asks again.
  _mcpStale(repo) { return Boolean(this.mcpServersFn && this.mcpConfigRepo !== repo) || this.mcpConfigDirty === true; }

  // A sign-in or sign-out (#2206): the next child start rewrites the config,
  // so the engine servers' `Authorization` header follows the token.
  invalidateMcpConfig() { this.mcpConfigDirty = true; }

  //
  // The repo's servers are asked for at every child start (#2361: one small
  // engine GET), so a server added, edited or removed for the same repo reaches
  // the next session; the file is rewritten only when the answer changed.
  async _ensureMcpConfig(repo = null) {
    let r;
    if (this.mcpServersFn && repo) {
      try { r = await this.mcpServersFn(repo); } catch (e) { r = { error: (e && e.message) || String(e) }; }
    }
    const answer = r && r.servers && typeof r.servers === "object" ? JSON.stringify(r.servers) : null;
    if (this.mcpConfigPath && !this._mcpStale(repo) && (r === undefined || (answer !== null && answer === this.mcpServersJson))) return this.mcpConfigPath;
    let repoServers = null;
    let loaded = true;
    this.rt.mcpNotice = null;
    if (this.mcpServersFn && repo) {
      if (r && r.servers && typeof r.servers === "object") repoServers = r.servers;
      else {
        loaded = false;
        this.rt.mcpNotice = `The MCP servers configured for ${repo} were not loaded (${(r && r.error) || "no answer"}); First mate has the engine's two only.`;
      }
    }
    try {
      const engineEnv = (await this.engineEnvFn()) || {};
      this.mcpConfigPath = writeFrakworksMcpConfig(path.dirname(this.settingsPath),
        { engineUrl: engineUrl(engineEnv), token: engineEnv.ENGINE_TOKEN || undefined, repoServers });
      this.mcpConfigRepo = loaded ? repo : undefined;
      this.mcpConfigDirty = false;
      this.mcpServersJson = loaded ? answer : undefined;
      this.repoServerNames = Object.keys(repoServers || {}).filter((n) => !ENGINE_MCP_SERVERS.includes(n));
    } catch (e) {
      this.loadError = `frakworks-mcp.json unwritable: ${e.message}`;
    }
    return this.mcpConfigPath;
  }

  // Asks the engine which account a Smith on this model would get, before a
  // child starts: each start is one pick, like a Smith run. A refusal or an
  // unreachable engine leaves the child on this machine's own login, and says so.
  async _ensureAccount(eff) {
    if (!this.pickAccountFn) return;
    const rt = this.rt;
    let r;
    try { r = await this.pickAccountFn(eff.model); } catch (e) { r = { error: (e && e.message) || String(e) }; }
    if (r && r.ok && r.token) {
      rt.account = { name: r.account, envVar: r.envVar, token: r.token };
      rt.accountNotice = null;
    } else {
      rt.account = null;
      rt.accountNotice = `No engine account for First mate (${(r && r.error) || "no answer"}); ` +
        "running on this machine's own Claude login.";
    }
  }

  // The child's environment on a picked account: ours, with the account's
  // long-lived token in the provider's variable and no other Anthropic
  // credential beside it, so the token is what the CLI authenticates with.
  _childEnv() {
    const a = this.rt.account;
    if (!a) return undefined;
    const env = { ...process.env };
    delete env.ANTHROPIC_API_KEY;
    delete env.ANTHROPIC_AUTH_TOKEN;
    env[a.envVar] = a.token;
    return env;
  }

  _needsLineage() { return Boolean(this.lineageFn && !this.session.lineage); }

  // The conversation's lineage, once: rooted at its first ask (the first user
  // row, or this turn's text in a new chat). An engine that cannot answer
  // leaves the child without one; the next child start asks again.
  async _ensureLineage(text) {
    if (!this._needsLineage()) return;
    const first = this.session.history.find((m) => m.role === "user");
    const ask = (first && first.text) || text;
    let r;
    try { r = await this.lineageFn(ask); } catch (e) { r = { error: (e && e.message) || String(e) }; }
    if (r && r.ok && typeof r.lineage === "string") this.session.lineage = r.lineage;
  }

  // The lineage, written as received to a read-only file beside the settings:
  // its path (the prompt's "Lineage file:" line, and `FM_LINEAGE_FILE` in
  // [env], undefined: ours), or null and [env] as it was with none.
  _withLineage(env) {
    const text = this.session.lineage;
    if (!text || !this.dir) return { env, file: null };
    try {
      const file = path.join(this.dir, "first-mate", "lineage.json");
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.rmSync(file, { force: true });
      fs.writeFileSync(file, text, { mode: 0o444 });
      return { env: { ...(env || process.env), FM_LINEAGE_FILE: file }, file };
    } catch (e) {
      this.loadError = `lineage.json unwritable: ${e.message}`;
      return { env, file: null };
    }
  }

  // The conversation runs in a session pod (#2521).
  _remote() { return this.session.runtime === "kubernetes"; }

  // Fixes where a conversation runs (#2521), once, as it starts its runtime:
  // no child alive, no pod session and no Claude session yet. An engine that
  // cannot answer runs it here, with a notice.
  async _settleRuntime() {
    const s = this.session;
    if (s.runtime || s.remote_session_id || s.claude_session_id) return s.runtime || "local";
    let hosting = "local";
    if (this.hostingFn) {
      let r;
      try { r = await this.hostingFn(); } catch (e) { r = { error: (e && e.message) || String(e) }; }
      if (r && r.firstMateHosting && r.firstMateHosting.hosting === "kubernetes") hosting = "kubernetes";
      else if (!r || r.error) this.rt.notice = `First mate hosting unavailable (${(r && r.error) || "no answer"}): this conversation runs on this machine.`;
    }
    s.runtime = hosting;
    if (hosting === "kubernetes") s.remote_session_id = null;
    return hosting;
  }

  _startRemote(eff, text = "") {
    const rt = this.rt;
    const bearer = this.bearer;
    // The lineage is the engine's (#2523): rooted at the conversation's first ask.
    const first = this.session.history.find((m) => m.role === "user");
    const child = new RemoteSession({
      repo: this.chatRepo(),
      ask: (first && first.text) || text || "",
      tier: eff.tier,
      drainUntilEcho: Boolean(rt.drainRemote),
      sessionId: this.session.remote_session_id || null,
      api: this.sessionApi,
      bearer: bearer ? () => bearer() : async () => null,
      engineEvents: this.engineEvents,
    });
    rt.child = child;
    rt.childEff = { ...eff };
    rt.childCwd = null;
    rt.childInstructions = null;
    rt.resumedWith = null;
    rt.drainRemote = false;
    const generation = this.session.session_generation;
    child.on("session", ({ id }) => {
      if (generation !== this.session.session_generation) return;
      this.session.remote_session_id = id;
      this._save();
    });
    child.on("event", (event) => this._onEvent(generation, event));
    child.on("exit", (info) => this._onExit(generation, info, child));
    child.start();
    rt.hadReader = true;
    return child;
  }

  _startChild(eff) {
    if (this._remote()) return this._startRemote(eff);
    const rt = this.rt;
    // The conversation's cwd (#960): the argv's memory rule, the settings
    // file's allow list and the child's directory all name it.
    const cwd = this.conversationCwd();
    const servers = this.repoServerNames.join("\n");
    if (this.ownSettings && this.dir && (cwd !== this.settingsCwd || servers !== this.settingsServers)) {
      try {
        writeChatSettings(path.join(this.dir, "first-mate"),
          { allowedTools: firstMateAllowedTools(cwd, this.repoServerNames), deniedTools: FIRST_MATE_DENIED_TOOLS });
        this.settingsCwd = cwd;
        this.settingsServers = servers;
      } catch (e) { this.loadError = `chat-settings.json unwritable: ${e.message}`; }
    }
    const instructions = this._instructions(cwd);
    const lineage = this._withLineage(this._childEnv());
    const argv = buildFirstMateArgv({
      resumeId: this.session.claude_session_id,
      settingsPath: this.settingsPath,
      mcpConfigPath: this.mcpConfigPath,
      model: eff.model,
      effort: eff.effort,
      cwd,
      freshDir: this.freshDir,
      checkoutDir: this.checkoutDir,
      instructions: instructions.list,
      repoServers: this.repoServerNames,
      lineageFile: lineage.file,
    });
    rt.childEff = { ...eff };
    rt.childCwd = cwd;
    rt.childInstructions = instructions.key;
    const child = new ClaudeChild({ command: this.command, launchArgs: this.launchArgs, argv, cwd, spawnFn: this.spawnFn, env: lineage.env });
    rt.child = child;
    rt.resumedWith = this.session.claude_session_id || null;
    const generation = this.session.session_generation;
    child.on("event", (event) => this._onEvent(generation, event));
    child.on("exit", (info) => this._onExit(generation, info, child));
    child.start();
    rt.hadReader = true;
    return child;
  }

  _onEvent(generation, event) {
    if (generation !== this.session.session_generation) return { dropped: "superseded generation" };
    const rt = this.rt;
    if (event && event.session_id && event.session_id !== this.session.claude_session_id) {
      this.session.claude_session_id = String(event.session_id);
      this._save();
    }
    const pending = rt.pending;
    // The engine thawing a frozen pod session (#2523): `resuming…` until the
    // next line of another type; a pod-status line is the tooltip's detail.
    if (event && event.type === "resuming") {
      rt.remoteState = "resuming…";
      this._emitUpdate();
      return { resuming: true };
    }
    if (event && event.type === "pod-status") {
      rt.remoteDetail = String(event.text || event.status || event.phase || "");
      this._emitUpdate();
      return { podStatus: true };
    }
    if (rt.remoteState) {
      rt.remoteState = null;
      rt.remoteDetail = null;
      this._emitUpdate();
    }
    this._noteFilingTools(event);
    // A pod session's first line (#2521): the MCP servers it had to drop.
    if (event && event.type === "system" && event.subtype === "first_line") {
      this._push("system", String(event.text));
      this._save();
      this._emitUpdate();
      return { firstLine: true };
    }
    if (event && event.type === "system" && event.subtype === "init") {
      rt.ledger.noteCapabilities(event.capabilities);
      return { init: true };
    }
    if (event && event.type === "user") {
      const kind = rt.ledger.noteUser(event.uuid, pending && pending.uuid);
      if (kind === "ours" && pending) pending.mine = true;
      return { turn: kind };
    }
    if (event && event.type === "assistant") {
      if (!pending) return { ignored: "no pending turn" };
      const named = userMessageUuids(event);
      if (named.length && (!pending || !pending.uuid || !named.includes(pending.uuid))) {
        return { ignored: "not the pending turn" };
      }
      const text = assistantText(event);
      if (text) { rt.streaming += text; this.emit("stream", { text }); }
      return { streamed: Boolean(text) };
    }
    if (event && event.type === "result") {
      const owner = rt.ledger.claimResult(event);
      const ours = owner.kind === "ours" && pending && pending.mine &&
        (owner.attributed !== "user-message-uuid" || owner.uuid === pending.uuid);
      if (!ours) return { ignored: "not this session's turn" };
      if (!this._remote() && !pending.retried && pending.eff && pending.eff.model === "fable" && isFableLimit(event.result)) {
        this._fableFallback(pending);
        return { retried: true };
      }
      const raw = event.result || rt.streaming || "The turn finished with nothing to say.";
      const { reply, file } = normalizeFileLine(raw);
      rt.pending = null;
      rt.busy = false;
      rt.streaming = "";
      rt.recovery = null;
      clearTimeout(pending.timer);
      this._push("assistant", reply);
      // The fresh session has had the restored transcript (#598).
      if (pending.seeded) this.session.seed = null;
      // A turn that filed through submit_ask is a filed turn, not an unfiled one.
      if (!pending.filed) this.session.turns_since_filed += 1;
      rt.fileCard = file ? { ...file, turn_id: pending.uuid } : null;
      rt.fileError = null;
      this._save();
      pending.resolve({ ok: true, text: reply, file: rt.fileCard });
      this._emitUpdate();
      return { resolved: true };
    }
    return { ignored: true };
  }

  // Issue #290: the child's own filings. An assistant `tool_use` for
  // submit_ask is noted by id; when its `tool_result` comes back (in a user
  // event) and is not an error, the ask lands in the Filed list, the same
  // shape `fileTicket` writes. The server replies `{intakeId, status, tier,
  // ask}`; tier is null unless the user named one. Nothing else in the stream
  // is touched, and the user event still goes on to the ledger as before.
  _noteFilingTools(event) {
    const content = event && event.message && Array.isArray(event.message.content) ? event.message.content : null;
    if (!content) return;
    const rt = this.rt;
    for (const block of content) {
      if (!block) continue;
      if (event.type === "assistant" && block.type === "tool_use" && block.name === SUBMIT_TOOL && block.id) {
        rt.filingCalls.add(block.id);
      } else if (event.type === "user" && block.type === "tool_result" && rt.filingCalls.has(block.tool_use_id)) {
        rt.filingCalls.delete(block.tool_use_id);
        if (block.is_error) continue;
        let r;
        try { r = JSON.parse(toolResultText(block)); } catch (e) { continue; }
        if (!r || typeof r.intakeId !== "string" || !r.intakeId || r.error) continue;
        const tier = Number.isInteger(r.tier) ? r.tier : null;
        this._recordFiled({ intake_id: r.intakeId, tier, ask: typeof r.ask === "string" ? r.ask : "", ticket: r.ticket, ticket_url: r.ticketUrl });
        if (rt.pending) rt.pending.filed = true;
        this._emitUpdate();
      }
    }
  }

  // One entry on the Filed list, newest first — the button's and the tool's.
  // It names the live conversation (#927), so its `Filed as #<n>` row lands
  // there when the stream's `filed` frame comes. `ticket` and `ticket_url` are
  // the ask item's, which POST /intake answers at filing (#100086): the reply
  // already names it, so the row only comes if the refined ticket differs.
  _recordFiled({ intake_id, tier, ask, ticket = null, ticket_url = null }) {
    if (!this.session.conversation_key) this.session.conversation_key = crypto.randomUUID();
    this.session.filed.unshift({
      intake_id, tier, ask, at: this.now(), conversation_key: this.session.conversation_key,
      ticket: Number.isInteger(ticket) ? ticket : null,
      ticket_url: Number.isInteger(ticket) && typeof ticket_url === "string" && ticket_url ? ticket_url : null, announced_at: null, ended_at: null,
    });
    if (this.session.filed.length > FILED_LIMIT) this.session.filed.length = FILED_LIMIT;
    this.session.turns_since_filed = 0;
    this._save();
  }

  // ---------------------------------------------------------------------------
  // "Filed as #<n>" (#927, #971): the stream's `intake` frames, and one read on hello
  // ---------------------------------------------------------------------------

  // One `intake` frame's data. `filed` and `triage` carry `ticket` and
  // `ticketUrl` the moment the file step sets them. `ended` (`closeIntake`)
  // carries `ticket` with no `ticketUrl`: for an entry still unannounced (the
  // `filed` frame was missed and the hello read landed before the flow ended)
  // it announces too, with the URL built from the frame's `repo`, so the
  // filing is never lost; `ended` without a ticket and `cancelled` end the
  // entry without a row. A frame for an intake that is not in
  // `session.filed`, or already announced, changes nothing.
  _onIntakeFrame(data) {
    if (!data || typeof data.intakeId !== "string") return;
    const entry = this.session.filed.find((f) => f.intake_id === data.intakeId);
    if (!entry || entry.announced_at || entry.ended_at) return;
    if (FILED_STATES.includes(data.state)) this._filedEnded(entry, { ticket: data.ticket, ticketUrl: data.ticketUrl });
    else if (data.state === "ended") this._filedEnded(entry, endedFrameTicket(data));
    else if (ENDED_STATES.includes(data.state)) this._filedEnded(entry, {});
  }

  // The snapshot the stream's connect asks of every consumer: one read of each
  // unannounced entry under FILED_WATCH_MS old, so an intake that ended while
  // the app was closed (or the stream was down) is still announced. A failed
  // read (engine down, 404) is left for the next hello, and nothing about it
  // reaches the chat. No timer follows it.
  async catchUpFiled() {
    const now = this.now();
    const due = this.session.filed.filter((f) => isUnannounced(f, now) && !this.filedReads.has(f.intake_id));
    await Promise.all(due.map(async (f) => {
      this.filedReads.add(f.intake_id);
      let r;
      try { r = await this.fetchFlowFn(f.intake_id); } catch { r = null; } finally { this.filedReads.delete(f.intake_id); }
      const flow = r && !r.error && r.flow;
      if (flow && flow.ended === true) this._filedEnded(f, flow.variables || {});
    }));
  }

  // The intake ended: with `ticket` and `ticketUrl`, push the row into the
  // filing conversation and record the ticket; without, only mark it done.
  // Re-checked here, so a frame and a read of the same intake push one row.
  _filedEnded(entry, vars) {
    if (entry.announced_at || entry.ended_at) return;
    const now = this.now();
    entry.ended_at = now;
    const same = entry.ticket === vars.ticket && entry.ticket_url === vars.ticketUrl;
    if (same) entry.announced_at = now;
    else if (Number.isInteger(vars.ticket) && typeof vars.ticketUrl === "string" && vars.ticketUrl) {
      const row = { role: "system", text: filedRowText(vars.ticket, vars.ticketUrl), at: now };
      this._filingConversation(entry.conversation_key).push(row);
      capTotal(this.session);
      entry.ticket = vars.ticket;
      entry.ticket_url = vars.ticketUrl;
      entry.announced_at = now;
    }
    this._save();
    this._emitUpdate();
  }

  // The messages of the conversation `key` names: the live one, or an archived
  // one (Start new conversation came first). An entry from before #927, or
  // one whose conversation is gone, is announced in the live conversation.
  _filingConversation(key) {
    if (!key || this.session.conversation_key === key) return this.session.history;
    const c = this.session.conversations.find((x) => x.conversation_key === key);
    return c ? c.messages : this.session.history;
  }

  _onExit(generation, info, child) {
    if (generation !== this.session.session_generation) return;
    const rt = this.rt;
    if (rt.child !== child) return;
    rt.child = null;
    const pending = rt.pending;
    rt.pending = null;
    rt.busy = false;
    rt.streaming = "";
    rt.ledger.clear();
    if (child instanceof RemoteSession) {
      // The pod's session ended or is not ours (404/409): the next turn starts
      // a new one. A refused bearer (401/403) drops nothing: the strip notice
      // says to sign in again under Config > frakworks > Sign in (#100103).
      if (info && (info.gone || info.auth)) this.session.remote_session_id = null;
      if (info && info.status === 404) this._push("system", REMOTE_EXPIRED_ROW);
      if (info && info.auth) rt.notice = info.error;
    }
    if (pending || !child.sawResult) {
      const resumed = Boolean(rt.resumedWith) && !child.sawResult;
      const detail = info && info.error ? ` (${info.error})` : "";
      rt.recovery = {
        kind: resumed ? "resume_failed" : "child_exited",
        message: resumed
          ? `Could not resume First mate's conversation: the session ${rt.resumedWith} ended without answering${detail}.`
          : `First mate's Claude session exited before answering${detail}.`,
        session_id: rt.resumedWith,
      };
      this._push("system", rt.recovery.message);
      this._save();
      if (pending) { clearTimeout(pending.timer); pending.resolve({ ok: false, error: rt.recovery.message }); }
    }
    this._emitUpdate();
  }

  // `images` (#368): pasted images as `{ media_type, data }`, re-checked here
  // against src/first-mate-images.js's type allowlist, size cap and count
  // whatever the renderer already checked. With images the text may be empty.
  // A refusal before the turn is written says `refused: true`, so the strip
  // can hand the draft and attachments back.
  async send(text, images) {
    const rt = this.rt;
    const checked = validateImages(images);
    if (!checked.ok) return { ok: false, refused: true, error: checked.error };
    const imgs = checked.images;
    if (typeof text !== "string" || (!text.trim() && !imgs.length)) return { ok: false, refused: true, error: "nothing to send" };
    if (rt.busy) {
      rt.notice = "First mate is still working on the previous message. Wait for it, or cancel.";
      this._emitUpdate();
      return { ok: false, busy: true, refused: true, error: rt.notice };
    }
    // A restored saved chat from another machine (#598): its first turn gives
    // the fresh session the transcript, ahead of what the user typed; only the
    // child sees it, the history row is what the user wrote.
    const seed = this.session.claude_session_id ? null : this.session.seed;
    // The chat's repo (#2343): a fresh chat takes the selected one, and a
    // kick-starter naming a repo sets it while none is chosen.
    if (this.repoFn) {
      if (this.session.repo === undefined && !this.session.history.length) this._startChatRepo();
      const kicked = kickStarterRepo(text);
      if (kicked && !this.chatRepo()) this.session.repo = kicked;
      if (this.chatRepo()) this.session.last_repo = this.chatRepo();
    }
    const body = withRepoLine(text.trim() ? text : "", this._turnRepoLine());
    rt.repoChangedFrom = undefined;
    const content = buildContent(seed ? `${seed}\n\n${body}` : body, imgs);
    rt.notice = null;
    rt.fileCard = null;
    rt.fileError = null;
    rt.tierNotice = null;
    // The first turn after the build (#960): a session with no cwd and no
    // conversation yet takes the selected repo's clone.
    if (!this.session.cwd && !this.session.history.length && !this.session.claude_session_id && this.freshDir) {
      this.session.cwd = this._freshCwd();
    }
    // ... and one whose repo is chosen runs in that repo's clone (#2363).
    this._followChatRepo();
    const mismatch = this.repoMismatch();
    if (mismatch) rt.notice = mismatch;
    const cwd = this.conversationCwd();
    let eff = this._effective();
    // The tier map is the engine's (src/engine-tiers.js): when main has no
    // copy yet (the engine was down at startup), read it once more now.
    if (!eff.model && this.loadTiersFn) {
      await this.loadTiersFn();
      eff = this._effective();
    }
    if (!rt.child || !rt.child.alive) {
      if (!this.session.runtime && this.hostingFn && !this.session.remote_session_id && !this.session.claude_session_id) {
        rt.busy = true;
        const generation = this.session.session_generation;
        await this._settleRuntime();
        rt.busy = false;
        if (generation !== this.session.session_generation) {
          return { ok: true, interrupted: true, text: INTERRUPTED_TEXT, why: "cancelled" };
        }
      }
    }
    const remote = this._remote();
    if (remote) {
      // A pod's conversation (#2521): no clone, MCP config, account, lineage
      // or tier restart here; the engine's session does those.
      if (!this.chatRepo()) {
        rt.notice = "Pick a repo for this chat: First mate runs in a pod on the cluster, which needs one.";
        this._emitUpdate();
        return { ok: false, refused: true, error: rt.notice };
      }
      if (!rt.child || !rt.child.alive) this._startRemote(eff, text);
      else if (rt.childEff && rt.childEff.tier !== eff.tier) {
        rt.tierNotice = `Tier ${eff.tier} (${eff.model} ${eff.effort}) ${REMOTE_TIER_NOTE}: this one runs on tier ${rt.childEff.tier} in its pod.`;
      }
    }
    if (!remote && rt.child && rt.child.alive && (!rt.childEff || rt.childEff.tier !== eff.tier)) {
      // Nothing is pending here (not busy), so ending the child resolves no turn.
      this._killChild("tier changed");
      if (this.session.claude_session_id) {
        rt.tierNotice = `Switched to tier ${eff.tier} (${eff.model} ${eff.effort}): ` +
          "this turn re-reads the conversation uncached on the new model.";
      }
    }
    // The cwd repo's CLAUDE.md or AGENTS.md changed since the child started
    // (#960): restart it on the same session so this turn has the new text.
    if (!remote && rt.child && rt.child.alive && this.freshCode) {
      const now = this._instructions(cwd);
      if (now.key !== rt.childInstructions) {
        this._killChild("instructions changed");
        const repo = this._repoOfCwd(cwd) || "the repo";
        const at = now.list.length ? now.list[0].commit : "its new commit";
        rt.tierNotice = `CLAUDE.md of ${repo} changed at ${at}: the conversation was re-read on the new text.`;
      }
    }
    if (!remote && (!rt.child || !rt.child.alive)) {
      const repo = this._repoOfCwd(cwd);
      const waits = Boolean(repo && this.freshCode && this.freshCode.status(repo) !== "ready");
      if (!this.mcpConfigPath || this._mcpStale(repo) || this.pickAccountFn || waits || this._needsLineage()) {
        // Held busy across the awaits so a second send is refused meanwhile;
        // a cancel in that window wins.
        rt.busy = true;
        const generation = this.session.session_generation;
        if (waits) {
          // The clone is there before the child (#960): the first turn in a
          // repo whose clone is missing waits for it, with the strip saying so.
          rt.notice = `Waiting for the fresh clone of ${repo}…`;
          this._emitUpdate();
          let r;
          try { r = await this.freshCode.ensureClone(repo); } catch (e) { r = { error: (e && e.message) || String(e) }; }
          if (generation !== this.session.session_generation) {
            return { ok: true, interrupted: true, text: INTERRUPTED_TEXT, why: "cancelled" };
          }
          rt.notice = null;
          if (!r || r.error || this.freshCode.status(repo) !== "ready") {
            rt.busy = false;
            rt.notice = `The fresh clone of ${repo} is not available: ${(r && r.error) || "not cloned"}`;
            this._emitUpdate();
            return { ok: false, refused: true, error: rt.notice };
          }
        }
        if (!this.mcpConfigPath || this._mcpStale(repo)) await this._ensureMcpConfig(repo);
        await this._ensureAccount(eff);
        await this._ensureLineage(text);
        if (generation !== this.session.session_generation) {
          return { ok: true, interrupted: true, text: INTERRUPTED_TEXT, why: "cancelled" };
        }
      }
      this._startChild(eff);
    }
    this._push("user", text, imgs.length);
    rt.busy = true;
    rt.streaming = "";
    this._save();
    this._emitUpdate();

    const r = await new Promise((resolve) => this._writeTurn({ content, eff, resolve, retried: false, seeded: Boolean(seed) }));
    this._emitUpdate();
    return r;
  }

  // Writes one turn to the running child and arms its timeout. Used by `send`
  // and once more by the Fable fallback, which carries the original caller's
  // `resolve` over so the one `send` promise settles with the retry's answer.
  // `content` is the user message's: a string, or image + text blocks (#368).
  _writeTurn({ content, eff, resolve, retried, seeded = false }) {
    const rt = this.rt;
    const uuid = crypto.randomUUID();
    const generation = this.session.session_generation;
    const timer = setTimeout(() => {
      if (rt.pending && rt.pending.uuid === uuid) {
        this._killChild("timed out");
        rt.recovery = {
          kind: "timeout",
          message: `No answer after ${Math.round(this.turnTimeoutMs / 1000)}s; First mate's session was stopped.`,
          session_id: this.session.claude_session_id,
        };
        this._emitUpdate();
      }
    }, this.turnTimeoutMs);
    if (timer.unref) timer.unref();
    rt.pending = { uuid, generation, mine: false, resolve, timer, content, eff, retried, seeded };
    const wrote = rt.child.write({ type: "user", uuid, message: { role: "user", content } });
    if (!wrote) {
      clearTimeout(timer);
      rt.busy = false;
      rt.pending = null;
      resolve({ ok: false, error: "First mate's session is not accepting input" });
    }
  }

  // The turn ran on Fable and its result says the Fable limit is reached:
  // do not show that as a reply. End the child (settling nothing), restart it
  // at tier 5 with --resume, and write the same content again, once. The user row
  // is already in history and is not pushed again; the saved tier is not
  // touched, so the next turn resolves it afresh (and restarts on Fable).
  _fableFallback(pending) {
    const rt = this.rt;
    clearTimeout(pending.timer);
    rt.pending = null;
    if (rt.child) { rt.child.removeAllListeners(); rt.child.kill(); }
    rt.child = null;
    rt.streaming = "";
    rt.ledger.clear();
    const pair = this.resolveTierFn(FALLBACK_TIER) || { model: "opus", effort: "medium" };
    const eff = { tier: FALLBACK_TIER, model: pair.model, effort: pair.effort, source: "fallback" };
    rt.tierNotice = `Fable limit reached; this turn was retried at tier ${FALLBACK_TIER} ` +
      `(${eff.model} ${eff.effort}). Your saved tier is unchanged.`;
    this._startChild(eff);
    this._emitUpdate();
    this._writeTurn({ content: pending.content, eff, resolve: pending.resolve, retried: true, seeded: pending.seeded });
  }

  // The File button (acceptance criteria 6-8): POST /intake with the tier
  // header, through `this.submitIntakeFn` — main.js wires this to
  // src/engine-data.js's `submitIntake`, the same unauthenticated engine-call
  // path the app's other engine calls use, not a second one.
  //
  // Keeps `fileCard` alive on failure (the usual retry
  // pattern): a retry re-sends the same ask at whatever tier is picked now.
  //
  // `repo` (#577) is where the ticket goes: the card's pick in All repos, else
  // the selected repo. With a `repoFn` and neither, nothing is filed.
  async fileTicket(tier, repo) {
    const rt = this.rt;
    if (!rt.fileCard) return { ok: false, error: "there is nothing to file" };
    const t = Number(tier);
    if (!Number.isInteger(t) || t < 1 || t > 10) return { ok: false, error: "pick a tier 1-10" };
    const resolved = this.resolveTierFn(t);
    if (!resolved) return { ok: false, error: `tier ${t} has no (model, effort): the engine's tier map is not loaded (is the engine up?)` };
    const target = repo || this.chatRepo() || null;
    if (this.repoFn && !target) return { ok: false, error: "All repos is selected and this chat has no repo: pick the repo to file in" };
    rt.fileBusy = true;
    rt.fileError = null;
    this._emitUpdate();
    const header = `Tier: ${t} (${resolved.model} ${resolved.effort}) — prescribed by the user; do not re-judge`;
    const ask = `${header}\n\n${rt.fileCard.ask}`;
    const r = await (target ? this.submitIntakeFn(ask, target) : this.submitIntakeFn(ask));
    rt.fileBusy = false;
    if (!r.ok) {
      rt.fileError = r.error || "filing failed";
      this._emitUpdate();
      return { ok: false, error: rt.fileError };
    }
    const filedAsk = rt.fileCard.ask;
    rt.fileCard = null;
    rt.fileError = null;
    this._recordFiled({ intake_id: r.intakeId, tier: t, ask: filedAsk, ticket: r.ticket, ticket_url: r.ticketUrl });
    this._emitUpdate();
    return { ok: true, intake_id: r.intakeId };
  }

  // Read-only projections of the archive (#257), for the History panel's IPC.
  // Neither mutates session.state nor calls _save().
  listConversations() {
    return this.session.conversations.map((c) => {
      const first = c.messages.find((m) => m.role === "user") || c.messages[0] || null;
      return { id: c.id, started_at: c.started_at, first_message: first ? first.text : null };
    });
  }

  // `resumable` (#486): whether the conversation has a stored session id for
  // resumeConversation. The id is matched as a number, since IPC sends a string.
  getConversation(id) {
    const c = this.session.conversations.find((c) => c.id === Number(id));
    return c ? { id: c.id, started_at: c.started_at, resumable: Boolean(c.claude_session_id), messages: c.messages } : null;
  }

  stop() {
    this._killChild("app closing", { detach: true });
    if (this.engineEvents) {
      this.engineEvents.removeListener("frame", this._onFrame);
      this.engineEvents.removeListener("hello", this._onHello);
    }
  }
}

module.exports = {
  REMOTE_TIER_NOTE, REMOTE_CANCEL_ROW, REMOTE_EXPIRED_ROW,
  FirstMateManager, UNFILED_TURN_HINT, FILED_WATCH_MS, APP_REPO, filedRowText, repoLine, kickStarterRepo, itermSeed,
  ITERM_SEED_HEAD, ITERM_POD_SEED_HEAD, ITERM_ROW, ITERM_BUSY, ITERM_EMPTY,
};
