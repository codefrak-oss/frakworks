// claude-session.js — the main-process pieces First mate (src/first-mate.js)
// runs its long-lived
// `claude -p --input-format stream-json --output-format stream-json --verbose`
// child with: the tool policy, the settings and MCP-config files, the argv,
// and `ClaudeChild`, the process wrapper.
//
// Written for the two Chats panes (issue #1326), which were
// removed in #634; First mate reused these pieces and keeps them.
//
// Factored from the *behaviour* of `mobile-bridge/server.py`'s `ClaudeSession`
// (the mobile bridge was removed from this repo on 2026-10-02; git history has it)
// — `_start` (long-lived process, `--resume` from a saved id, a reader over
// stdout), `_read` (save `session_id`, match the replayed user uuid against
// the turn that is waiting, deliver its `result`), `ask` (one uuid-tagged user
// event, wait for that turn's result) and `stop` (kill, forget the process,
// keep the resume id). Nothing here imports, spawns or HTTP-calls that server.
// The tool policy is enforced in the argv and a generated settings file, from
// exported constants: "a text prompt saying read-only is insufficient".
"use strict";

const { EventEmitter } = require("node:events");
const { spawn: nodeSpawn } = require("node:child_process");
const { describeSpawnError } = require("./claude-bin");
const { DEFAULT_FIRST_MATE_TIER } = require("./first-mate-tier");
const { resolveTier } = require("./engine-tiers");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const readline = require("node:readline");

// ---------------------------------------------------------------------------
// Tool policy
// ---------------------------------------------------------------------------

// The read-only base of First mate's permissions. Read-only verbs and
// read-only shell only: no `Write`, `Edit`, `NotebookEdit`, no `Task`, no MCP
// server at all — `mcp__bot-tickets` files tickets and `mcp__mdr` captured
// thoughts, so both are writes even though neither touches the filesystem.
//
// One exception (issue #290): filing an ask
// (`POST /intake`), as `FIRST_MATE_FILING_TOOLS` below — a named
// tool on the engine's frakworks MCP server (`POST /mcp/frakworks`,
// engine/README.md "Engine-served MCP servers"; issue #541). That write
// is allowed because it only starts an engine intake: a refining
// Smith and the user's review of the PR still gate everything after it, and it
// creates no file, commit or issue itself. It is narrowed to named tools on
// a server with no other tools, which a `Bash(curl …)` rule cannot be. The
// same server's other three tools only read: `read_transcript` (issue #352), a
// ticket's Smith transcript from the transcript pane's source,
// `read_needs` (issue #476), the engine's `GET /needs`, and `read_ticket_pr`
// (issue #742), a ticket's PR files, one file's text or its diff. This list is
// unchanged by it. Its sixth, `approve` (issue #799), authorizes an intake
// issue as the login the bearer token proves, with the same
// trusted-login check as `POST /intake`'s `authorize`: what the user's own
// `authorized` label does, which the app's `gh` cannot write. Its seventh,
// `reauthorize` (issue #850), does the same for a ticket whose blocker closed
// without a merge (a `reauthorize` need), as re-labelling it would.
//
// And (issue #663) `act_on_incident` on the engine's diagnostics server
// (`POST /mcp/frakworks-engine-diagnostics`): retry a ticket's open incident
// or cancel its running flow, what the process view's buttons do. The engine
// acts as the login the config's bearer token proves and applies
// the same trusted-login check as `POST /camunda/incidents/<id>/retry` and
// `DELETE /intake/<id>`, so it grants nothing the user could not do by hand.
//
// The diagnostics server's read-only tools are read tools here too:
// read_friction_notes, list_jobs, read_job, read_job_runs (a job's run
// history, issue #1660), read_job_attempts, read_tier_accounts,
// read_review_predictions (issue #1770) and read_public_url (issue #2253).
//
// A second exception (issue #339): file edits inside First mate's own Claude
// Code auto-memory directory and nowhere else, as one path-scoped `Edit` rule
// (`firstMateMemoryRule`, below).
//
// Frozen because the IPC surface is *not* allowed to extend it: the argv
// builder below takes no allow-list parameter.
// The First mate prompt and its static allow/deny lists live in
// shared/first-mate-prompt.json (shared with the iPhone app, ios/README.md), so other
// clients read the same file. Packaged, electron-builder puts it in resources
// (package.json build.extraResources); in dev and tests it is read from the repo.
const firstMatePromptPath = () => (process.resourcesPath && fs.existsSync(path.join(process.resourcesPath, "first-mate-prompt.json")))
  ? path.join(process.resourcesPath, "first-mate-prompt.json")
  : path.join(__dirname, "..", "..", "..", "shared", "first-mate-prompt.json");
const loadFirstMatePrompt = (file = firstMatePromptPath()) => JSON.parse(fs.readFileSync(file, "utf8"));
const FIRST_MATE_SHARED = loadFirstMatePrompt();

const CHAT_READ_TOOLS = Object.freeze([
  "Read", "Grep", "Glob", "WebSearch", "WebFetch",
  "Bash(git log:*)", "Bash(git status:*)", "Bash(git diff:*)",
  "Bash(git show:*)", "Bash(git ls-remote:*)",
  "Bash(gh issue view:*)", "Bash(gh issue list:*)",
  "Bash(gh pr view:*)", "Bash(gh pr list:*)",
  "mcp__frakworks-engine-diagnostics__read_friction_notes",
  "mcp__frakworks-engine-diagnostics__list_jobs",
  "mcp__frakworks-engine-diagnostics__read_job",
  "mcp__frakworks-engine-diagnostics__read_job_runs",
  "mcp__frakworks-engine-diagnostics__read_job_attempts",
  "mcp__frakworks-engine-diagnostics__read_tier_accounts",
  "mcp__frakworks-engine-diagnostics__read_review_predictions",
  "mcp__frakworks-engine-diagnostics__read_public_url",
]);

// Belt and braces: the deny list in the generated settings file. `--allowedTools`
// is already an allow-list, so a write tool is refused by omission; these names
// make the refusal explicit and survive anyone later widening the allow-list by
// accident. Deny beats allow in Claude Code's permission resolution.
const CHAT_DENIED_TOOLS = Object.freeze([
  "Write", "Edit", "MultiEdit", "NotebookEdit", "Task",
  "Bash(git push:*)", "Bash(git commit:*)", "Bash(gh pr create:*)",
  "Bash(gh pr merge:*)", "Bash(gh issue create:*)", "Bash(gh issue comment:*)",
  "Bash(gh api:*)", "Bash(rm:*)",
]);

// Every tool name that writes something, for the test that reads the
// constructed argv and settings back. A name is "a write tool" here if using
// it changes a file, a repository, a ticket or a remembered thought.
const WRITE_TOOL_MARKERS = Object.freeze([
  "Write", "Edit", "NotebookEdit", "mcp__", "git push", "git commit",
  "gh pr create", "gh pr merge", "gh issue create", "gh issue comment", "gh api",
]);

// First mate's tools on its two servers (issues #290, #352, #476, #663), named as
// Claude Code names an MCP server's tools: `mcp__<server>__<tool>`. The first
// server is the engine's `POST /mcp/frakworks` (issue #541) and lists exactly
// the tools First mate has on it; the engine's FrakworksMcpTest asserts that. The
// first two file (submit_ask first: first-mate.js's SUBMIT_TOOL); the next
// four, read_transcript, read_needs, read_ticket_pr and read_ticket_runs
// (issue #1162), only read; approve
// authorizes an intake (issue #799), reauthorize a ticket whose blocker closed
// without a merge (issue #850); note_friction notes what constrained an answer
// (issue #968), stamped with the login. read_repos, add_repo, edit_repo and
// remove_repo (issue #1021) read, add, change and remove the engine's target
// repos (GET, POST, PATCH and DELETE /repos), the writes with the login; a
// repo's token and webhook secret have no tool. close_ticket (issue #1170)
// closes a ticket with no running flow as the outcome it names, completed or
// not_planned (required, no default); comment_ticket (issue
// #1627) posts the user's comment on a ticket. The last two are the
// diagnostics server's tools: act_on_incident (issue #663), and
// read_doctor_history (issue #655), read-only, which moved there in #658.
// cancel_intake and amend_intake (issue #1173) cancel an intake, or add the
// user's follow-up to one still refining, as the login. amend_ticket (issue
// #1362) adds the user's late information to a ticket already filed.
// read_ci_runs (issue #1629) reads a target repo's Actions runs and their
// median duration, read-only.
// search_tickets (issue #1625), read-only, finds a target repo's tickets by
// their words.
// read_access, add_access and remove_access (issue #1636) read and change the
// engine's trusted logins (GET, POST and DELETE /access), as the login, which
// remove_access refuses to remove.
// read_ticket (issue #1735) reads a ticket's title, state, labels and body;
// list_private_docs and read_private_doc read a target repo's private
// documents. All three only read. read_repo_mcp_servers, add_, edit_,
// remove_ and test_repo_mcp_server (issue #1801) manage the MCP servers a
// target repo's First mate gets beside it (engine/README.md, "First
// mate's MCP servers").
const FRAKWORKS_MCP_SERVER = "frakworks";
const DIAGNOSTICS_MCP_SERVER = "frakworks-engine-diagnostics";
const FIRST_MATE_FILING_TOOLS = Object.freeze([
  `mcp__${FRAKWORKS_MCP_SERVER}__submit_ask`,
  `mcp__${FRAKWORKS_MCP_SERVER}__read_transcript`,
  `mcp__${FRAKWORKS_MCP_SERVER}__read_needs`,
  `mcp__${FRAKWORKS_MCP_SERVER}__read_ticket_pr`,
  `mcp__${FRAKWORKS_MCP_SERVER}__read_ticket_runs`,
  `mcp__${FRAKWORKS_MCP_SERVER}__approve`,
  `mcp__${FRAKWORKS_MCP_SERVER}__reauthorize`,
  `mcp__${FRAKWORKS_MCP_SERVER}__note_friction`,
  `mcp__${FRAKWORKS_MCP_SERVER}__read_repos`,
  `mcp__${FRAKWORKS_MCP_SERVER}__add_repo`,
  `mcp__${FRAKWORKS_MCP_SERVER}__edit_repo`,
  `mcp__${FRAKWORKS_MCP_SERVER}__remove_repo`,
  `mcp__${FRAKWORKS_MCP_SERVER}__read_access`,
  `mcp__${FRAKWORKS_MCP_SERVER}__add_access`,
  `mcp__${FRAKWORKS_MCP_SERVER}__remove_access`,
  `mcp__${FRAKWORKS_MCP_SERVER}__cancel_intake`,
  `mcp__${FRAKWORKS_MCP_SERVER}__amend_intake`,
  `mcp__${FRAKWORKS_MCP_SERVER}__amend_ticket`,
  `mcp__${FRAKWORKS_MCP_SERVER}__close_ticket`,
  `mcp__${FRAKWORKS_MCP_SERVER}__read_ci_runs`,
  `mcp__${FRAKWORKS_MCP_SERVER}__comment_ticket`,
  `mcp__${FRAKWORKS_MCP_SERVER}__search_tickets`,
  `mcp__${FRAKWORKS_MCP_SERVER}__read_ticket`,
  `mcp__${FRAKWORKS_MCP_SERVER}__list_private_docs`,
  `mcp__${FRAKWORKS_MCP_SERVER}__read_private_doc`,
  `mcp__${FRAKWORKS_MCP_SERVER}__read_repo_mcp_servers`,
  `mcp__${FRAKWORKS_MCP_SERVER}__add_repo_mcp_server`,
  `mcp__${FRAKWORKS_MCP_SERVER}__edit_repo_mcp_server`,
  `mcp__${FRAKWORKS_MCP_SERVER}__remove_repo_mcp_server`,
  `mcp__${FRAKWORKS_MCP_SERVER}__test_repo_mcp_server`,
  `mcp__${DIAGNOSTICS_MCP_SERVER}__act_on_incident`,
  `mcp__${DIAGNOSTICS_MCP_SERVER}__read_doctor_history`,
]);

// The read tools plus the two engine servers' tools, no peer tools. First mate's whole
// allow-list adds one path-scoped rule to this, `firstMateAllowedTools(cwd)`
// below.
// From the shared file; a test checks it equals CHAT_READ_TOOLS + FIRST_MATE_FILING_TOOLS.
const FIRST_MATE_ALLOWED_TOOLS = Object.freeze([...FIRST_MATE_SHARED.allowedTools]);

// First mate's second exception (issue #339): it may write its own Claude Code
// auto-memory, and nothing else on disk. Claude Code keeps that memory in
// `~/.claude/projects/<slug>/memory/`, where the slug is the project's absolute
// path with every non-alphanumeric character turned into `-` (the user's
// checkout `~/dev/frakworks` on a Mac gives `-Users-<you>-dev-frakworks`).
// The rule is an `Edit(path)` rule because Claude Code 2.1.282 refuses
// `Write(path)` rules at startup; an `Edit` path rule scopes every
// file-editing tool, `Write` included. Why allow it: without it First mate
// forgets what the user taught it at the end of every conversation, and the
// memory directory is outside every repo, so nothing written there reaches a
// commit, a ticket or a host.
const claudeProjectSlug = (dir) => path.resolve(dir || process.cwd()).replace(/[^A-Za-z0-9]/g, "-");
const firstMateMemoryDir = (cwd) => `~/.claude/projects/${claudeProjectSlug(cwd)}/memory/`;
const firstMateMemoryRule = (cwd) => `Edit(${firstMateMemoryDir(cwd)}**)`;
// The repo's own MCP servers (#1801), each allowed whole: `mcp__<name>__*`.
const repoMcpServerRules = (names = []) => names.map((n) => `mcp__${n}__*`);
const firstMateAllowedTools = (cwd, repoServers = []) => [...FIRST_MATE_ALLOWED_TOOLS, firstMateMemoryRule(cwd), ...repoMcpServerRules(repoServers)];

// First mate's deny list: `CHAT_DENIED_TOOLS` without the bare `Write` and
// `Edit`. Deny beats allow, so a bare `Write` or `Edit` here would refuse the
// memory rule too. Writes anywhere else are still refused: by omission from
// the allow-list, and because First mate starts with `--setting-sources ""`
// (`buildFirstMateArgv`), so no user, project or local settings file can add
// an allow rule of its own. `MultiEdit`, `NotebookEdit`, `Task` and the Bash
// write rules stay denied.
const FIRST_MATE_DENIED_TOOLS = Object.freeze([...FIRST_MATE_SHARED.deniedTools]);

// The settings document handed to the child with `--settings`. No hooks, no
// MCP config, no environment: only the permission block. `defaultMode: "default"`
// keeps Claude Code asking rather than bypassing — and since a pane has no way
// to answer a prompt, an unlisted tool simply does not run.
//
// `allowedTools` and `deniedTools` default to the read-only lists
// (`CHAT_READ_TOOLS`, `CHAT_DENIED_TOOLS`). First mate passes
// `firstMateAllowedTools(cwd)` and `FIRST_MATE_DENIED_TOOLS`.
const chatSettingsObject = ({ allowedTools = CHAT_READ_TOOLS, deniedTools = CHAT_DENIED_TOOLS } = {}) => ({
  permissions: {
    defaultMode: "default",
    allow: [...allowedTools],
    deny: [...deniedTools],
  },
  // Nothing here may reach for another MCP server, and an empty list says
  // so explicitly.
  enableAllProjectMcpServers: false,
  enabledMcpjsonServers: [],
});

// Written once per app run into a temp dir. Returns the path; the argv builder
// takes it as an argument so a test can assert on the file's contents.
function writeChatSettings(dir, { allowedTools, deniedTools } = {}) {
  const target = dir || fs.mkdtempSync(path.join(os.tmpdir(), "agent-army-chat-"));
  fs.mkdirSync(target, { recursive: true });
  const p = path.join(target, "chat-settings.json");
  fs.writeFileSync(p, `${JSON.stringify(chatSettingsObject({ allowedTools, deniedTools }), null, 2)}\n`, { mode: 0o600 });
  return p;
}

// First mate's `--mcp-config` document (issues #290, #541, #663, #2159): exactly
// two servers, both the engine's over streamable HTTP (engine/README.md,
// "Engine-served MCP servers"): frakworks at `<engineUrl>/mcp/frakworks`,
// frakworks-engine-diagnostics at `<engineUrl>/mcp/frakworks-engine-diagnostics`,
// whose act_on_incident acts as the login (the engine serves no HITLP since
// #2484). Each has one header at most,
// `Authorization: Bearer <token>`, when the desktop is signed in to the engine
// (`token`, main's, from src/engine-token.js): the token is the only way the
// engine learns who is acting (engine/README.md, "Tokens"), and
// without one the engine refuses every call. The engine reads the transcript
// itself, so nothing about VictoriaLogs is passed.
const AUTHORIZATION_HEADER = "Authorization";

//
// Since #1801 the repo's own servers (`repoServers`, the engine's
// `GET /repos/{o}/{n}/mcp-servers/config` `mcpServers`, secrets opened) are
// added beside the two; neither can be replaced by one.
const ENGINE_MCP_SERVERS = Object.freeze([FRAKWORKS_MCP_SERVER, DIAGNOSTICS_MCP_SERVER]);
function frakworksMcpConfigObject({ engineUrl = "http://localhost:8090", token = undefined, repoServers = null } = {}) {
  const base = String(engineUrl).replace(/\/+$/, "");
  const bearer = typeof token === "string" && token.trim() ? `Bearer ${token.trim()}` : "";
  const headers = bearer ? { [AUTHORIZATION_HEADER]: bearer } : {};
  const server = (name) => ({ type: "http", url: `${base}/mcp/${name}`, headers: { ...headers } });
  const own = Object.fromEntries(Object.entries(repoServers || {}).filter(([name]) => !ENGINE_MCP_SERVERS.includes(name)));
  return { mcpServers: { ...Object.fromEntries(ENGINE_MCP_SERVERS.map((name) => [name, server(name)])), ...own } };
}

// Written next to First mate's settings file, mode 0600. Returns the path.
function writeFrakworksMcpConfig(dir, opts = {}) {
  const target = dir || fs.mkdtempSync(path.join(os.tmpdir(), "agent-army-frakworks-"));
  fs.mkdirSync(target, { recursive: true });
  const p = path.join(target, "frakworks-mcp.json");
  fs.writeFileSync(p, `${JSON.stringify(frakworksMcpConfigObject(opts), null, 2)}\n`, { mode: 0o600 });
  return p;
}

// First mate's `--append-system-prompt`, verbatim, from shared/first-mate-prompt.json
// (its `prompt` lines, joined) (issue #100). One pane, one
// job: take in a request and either answer it on the spot or file it — never
// a multi-step investigation of its own. Since issue #290 it files the ticket
// itself through `submit_ask` (the engine's frakworks MCP server), without asking for
// confirmation; the `File:` line is only the fallback for when that tool
// fails, so the user can still file with the button once the engine is back.
// The tier rule is CLAUDE.md §"Filing tickets": a tier the user names binds,
// otherwise none is passed and the refiner picks. It always passes
// `authorize: true` (issue #369): the user authorizes an ask by asking, so
// the intake starts refining at once instead of waiting for a label. Issue
// #603: after filing in auto mode (the default when the user names none),
// it says a Smith will pick any open point and proceed, never that a
// question about it is coming. Issue #797: feedback on a proposal under
// discussion is a revise, not a new ticket: the user decides the proposal's
// open decision on the HITLP server, and First mate says where, never `submit_ask`.
// Issue #968: when something it lacked shaped an answer, it notes it with
// `note_friction` in the same turn and says so in one sentence of the reply.
const FIRST_MATE_SYSTEM_PROMPT = FIRST_MATE_SHARED.prompt.join("");

// The `owner/name` whose fresh clone `cwd` is (`<freshDir>/<owner>/<name>`), or null.
function freshRepoOf(cwd, freshDir) {
  if (!cwd || !freshDir) return null;
  const rel = path.relative(path.resolve(freshDir), path.resolve(cwd)).split(path.sep);
  return rel.length === 2 && !rel[0].startsWith("..") && rel[0] && rel[1] ? rel.join("/") : null;
}

// The fresh-code paragraph of First mate's prompt (#960): what the cwd is (a
// fresh clone of the conversation's repo at its default branch's head,
// detached; `<freshDir>/` itself in All repos), where every target repo's
// clone is, `fresh.json`, the user's own checkout (omitted when `checkoutDir`
// is null: a packaged build with AGENT_ARMY_REPO_DIR unset), `read_ticket_pr`
// for a PR, and how a "Discuss file … @ <commit>" kick-starter is answered.
// The prompt's "Lineage file:" line (shared/first-mate-prompt.json, "Lineage:").
function lineageFileLine(file) {
  return `Lineage file: ${file} (open it with the Read tool, never Bash).`;
}

function freshCodeParagraph({ cwd, freshDir, checkoutDir } = {}) {
  const repo = freshRepoOf(cwd, freshDir);
  const where = repo
    ? `Your working directory is a fresh git clone of ${repo} at its default branch's head, detached (no local ` +
      "branch, read-only): relative paths, Read, Grep and Glob, and git log, git show and git diff all describe " +
      "that repo at that commit, whatever branch the user has checked out elsewhere."
    : "Your working directory holds a fresh git clone of every target repo, one per <owner>/<name> subdirectory, " +
      "each at its default branch's head, detached and read-only. It is not itself a repository: read the repo " +
      "the question is about under its subdirectory, and read that repo's CLAUDE.md there when the question turns to it.";
  const checkout = checkoutDir
    ? ` The user's own checkout of frakworks, with their branch and uncommitted changes, is at ${checkoutDir}: read it ` +
      "there (by absolute path) for a question about their work in progress, never for what is on main."
    : "";
  return `${where} Every target repo's fresh clone is ${freshDir}/<owner>/<name>/, readable by absolute path, and ` +
    `${path.join(freshDir, "fresh.json")} gives each one's commit and when it was fetched.${checkout} A pull request's ` +
    "files, one file's text or its diff come from read_ticket_pr, never from the clone. A \"Discuss file " +
    "<owner>/<repo>:<path> @ <commit>\" kick-starter is answered from that repo's clone; when fresh.json's commit " +
    "differs from the kick-starter's and the commit is in the clone's history, read the file as it was with " +
    "git show <commit>:<path>.";
}

// One instruction file of the cwd's repo (`CLAUDE.md`, `AGENTS.md`, from
// src/fresh-code.js's `readRepoInstructions`), as a section headed by its
// path and commit. Its text is the repo's committers' instruction to Claude
// Code; First mate's tools are bounded by its allow and deny lists, not by its
// prompt, so the section cannot widen what it may do.
function instructionSection({ repo, file, path: p, commit, text } = {}) {
  const head = repo ? `${repo}/${file}` : (p || file || "instructions");
  return `## ${head} @ ${commit || "HEAD"}\n\n${String(text || "")}`;
}

// The argv for First mate's one child (issue #100). Its `--allowedTools` is
// `firstMateAllowedTools(cwd)` (the read tools, its frakworks and diagnostics tools and the
// memory rule, no `ListAgents`/`SendMessage`), and it takes no allow-list
// parameter: a caller cannot widen or narrow it. `--replay-user-messages` is
// the bridge's trick, and the reason a turn can be matched to its result: the
// child echoes each user message back with its uuid before working. Its one
// `--mcp-config` is the file `writeFrakworksMcpConfig` wrote (issue #290), with
// its two servers, frakworks and frakworks-engine-diagnostics (issue #663);
// `--strict-mcp-config` stays, so no other MCP server on the machine loads. `model`
// and `effort` are the pair main resolved for this turn's tier
// (src/first-mate-tier.js via src/first-mate.js), never renderer input; they
// default to tier 8's pair. No `--name` — First mate is never
// `ListAgents`-visible, so it has no stable peer address to mint.
//
// `cwd` is the directory the child runs in: the conversation's, the fresh
// clone of its repo (#960, src/fresh-code.js; main's REPO_DIR for a
// conversation from before that build); the memory rule's path is derived from
// it (issue #339). `--setting-sources ""`
// loads no user, project or local settings file, so the permission rules are
// exactly `--allowedTools` and the `--settings` file: with the bare
// `Write`/`Edit` denies gone, a `~/.claude/settings.json` allowing `Write`
// would otherwise let First mate write anywhere. `--settings` still loads, and
// auto-memory stays on. The cost: Claude Code reads the repo's `CLAUDE.md`
// only when project settings load, so the child does not load it itself; main
// reads the clone's copy (and `AGENTS.md`) and passes it as `instructions`,
// appended here as one section per file headed by its path and commit.
//
// `freshDir` and `checkoutDir` (#960): when `freshDir` is given, the prompt
// gains `freshCodeParagraph` (what the cwd is, where every clone is,
// `fresh.json`, the user's checkout at `checkoutDir` when one is named, which
// tool reads a PR). Without `freshDir` the prompt is as it was before #960.
//
// `model`/`effort` are main's per-turn resolution of First mate's tier
// (src/first-mate-tier.js's `resolveEffective`, the engine's tier map via
// src/engine-tiers.js); `src/first-mate.js` `_startChild` passes that pair every
// time. When no pair is passed, the fallback is `DEFAULT_FIRST_MATE_TIER`'s
// row in that map, never a hard-coded model or effort string.
//
// `lineageFile` (#2496): the literal path of the conversation's read-only
// lineage file, appended as the "Lineage file:" line the shared prompt tells
// the model to open with the Read tool (a shell read of FM_LINEAGE_FILE is
// refused by the permission check). None: no line, no lineage to pass.
function buildFirstMateArgv({ resumeId, settingsPath, model, effort, mcpConfigPath, cwd, freshDir, checkoutDir, instructions, repoServers = [], lineageFile } = {}) {
  const fallback = (!model || !effort) ? (resolveTier(DEFAULT_FIRST_MATE_TIER) || {}) : {};
  const argv = [
    "-p",
    "--input-format", "stream-json",
    "--output-format", "stream-json",
    "--verbose",
    "--replay-user-messages",
    "--model", String(model || fallback.model),
    "--effort", String(effort || fallback.effort),
    "--allowedTools", firstMateAllowedTools(cwd, repoServers).join(","),
    "--strict-mcp-config",
    "--setting-sources", "",
  ];
  if (mcpConfigPath) argv.push("--mcp-config", String(mcpConfigPath));
  let prompt = FIRST_MATE_SYSTEM_PROMPT;
  if (freshDir) prompt += `\n\n${freshCodeParagraph({ cwd, freshDir, checkoutDir })}`;
  for (const i of Array.isArray(instructions) ? instructions : []) prompt += `\n\n${instructionSection(i)}`;
  if (lineageFile) prompt += `\n\n${lineageFileLine(lineageFile)}`;
  argv.push("--append-system-prompt", prompt);
  if (settingsPath) argv.push("--settings", settingsPath);
  if (resumeId) argv.push("--resume", String(resumeId));
  return argv;
}

// ---------------------------------------------------------------------------
// One child process
// ---------------------------------------------------------------------------

// Emits: "event" (one parsed stream-json object), "exit" ({ code, signal }).
// Knows nothing about turns or generations — that is the caller's job.
class ClaudeChild extends EventEmitter {
  // `env`, when given, is the child's whole environment (First mate's picked
  // account, src/first-mate.js `_childEnv`); without it the child inherits ours.
  constructor({ command, launchArgs, argv, cwd, spawnFn, env } = {}) {
    super();
    this.env = env || null;
    this.command = command || "claude";
    this.argv = [...(launchArgs || []), ...(argv || [])];
    this.cwd = cwd;
    this.spawnFn = spawnFn || nodeSpawn;
    this.proc = null;
    this.sawResult = false;
    this.exited = false;
  }

  start() {
    const options = { cwd: this.cwd, stdio: ["pipe", "pipe", "ignore"] };
    if (this.env) options.env = this.env;
    this.proc = this.spawnFn(this.command, this.argv, options);
    const rl = readline.createInterface({ input: this.proc.stdout });
    rl.on("line", (line) => {
      if (!line.trim()) return;
      let event;
      try { event = JSON.parse(line); } catch (e) { return; }   // a non-JSON line is not ours
      if (event && event.type === "result") this.sawResult = true;
      this.emit("event", event);
    });
    // A child that cannot be started (no `claude` on PATH) surfaces as an exit,
    // the same shape as a child that died — one recovery path, not two. An
    // ENOENT names the PATH searched and the override, so the pane's message
    // says what to do rather than a bare "spawn claude ENOENT".
    this.proc.on("error", (e) => {
      if (this.exited) return;
      this.exited = true;
      this.emit("exit", { code: null, signal: null, error: describeSpawnError(e, this.command) });
    });
    this.proc.on("close", (code, signal) => {
      if (this.exited) return;
      this.exited = true;
      this.emit("exit", { code, signal, error: null });
    });
    return this;
  }

  get alive() { return Boolean(this.proc) && !this.exited; }

  write(obj) {
    if (!this.alive || !this.proc.stdin.writable) return false;
    this.proc.stdin.write(`${JSON.stringify(obj)}\n`);
    return true;
  }

  // `exited` is set here rather than left to the `close` event: the caller
  // usually drops our listeners on the way to killing us, so `close` may never
  // be heard, and a killed child that still reported `alive` would be written
  // to by the next turn.
  kill() {
    if (this.proc && !this.exited) {
      try { this.proc.kill("SIGKILL"); } catch (e) { /* already gone */ }
    }
    this.exited = true;
  }
}

// ---------------------------------------------------------------------------
// Turns
// ---------------------------------------------------------------------------

// First mate's turn timeout (issue #100): ~90s. This pane is a quick "answer
// or file it" exchange, not a long investigation (it is not allowed to start
// one; see FIRST_MATE_SYSTEM_PROMPT), so a stuck turn should surface quickly. A named constant, not a magic number, so the
// test asserting it fires can read it back rather than hard-coding 90000.
const FIRST_MATE_TURN_TIMEOUT_MS = Number(process.env.AGENT_ARMY_FIRST_MATE_TIMEOUT_SECS || 90) * 1000;

// The wording a cancelled turn carries. Deliberately does not say the work was
// undone: killing the child stops the conversation, it does not roll back a
// file the model already read or a command it already ran.
const INTERRUPTED_TEXT =
  "Interrupted. The session was stopped; anything it had already done still happened.";

// The text of an assistant event, ignoring tool-use blocks.
function assistantText(event) {
  const content = event && event.message && event.message.content;
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content.filter((b) => b && b.type === "text").map((b) => b.text || "").join("");
}

module.exports = {
  CHAT_READ_TOOLS, CHAT_DENIED_TOOLS, WRITE_TOOL_MARKERS, INTERRUPTED_TEXT,
  chatSettingsObject, writeChatSettings, assistantText, ClaudeChild,
  // First mate (issue #100).
  FIRST_MATE_SYSTEM_PROMPT, firstMatePromptPath, loadFirstMatePrompt,
  FIRST_MATE_TURN_TIMEOUT_MS, buildFirstMateArgv,
  // First mate runs from fresh code (#960).
  freshCodeParagraph, lineageFileLine, instructionSection, freshRepoOf,
  // First mate's filing path (issue #290).
  FRAKWORKS_MCP_SERVER, DIAGNOSTICS_MCP_SERVER, ENGINE_MCP_SERVERS, FIRST_MATE_FILING_TOOLS, FIRST_MATE_ALLOWED_TOOLS, repoMcpServerRules,
  AUTHORIZATION_HEADER, frakworksMcpConfigObject, writeFrakworksMcpConfig,
  // First mate's memory (issue #339).
  FIRST_MATE_DENIED_TOOLS, claudeProjectSlug, firstMateMemoryDir, firstMateMemoryRule, firstMateAllowedTools,
};
