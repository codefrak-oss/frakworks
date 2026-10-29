// First mate (issue #100): the third, singular pane. Everything here runs
// against test/fake-claude-child.js, the same fake used by
// test/chat-session.test.js — a real child process speaking stream-json over
// a real pipe. No test in this file invokes the real `claude` binary.
"use strict";
// Config > frakworks > Engine URL, as main sets it at launch (src/engine-url-setting.js).
const TEST_ENGINE_URL = "http://engine.test";
require("../src/engine-data").setEngineUrl(TEST_ENGINE_URL);
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const {
  FIRST_MATE_TURN_TIMEOUT_MS,
  buildFirstMateArgv, CHAT_READ_TOOLS, CHAT_DENIED_TOOLS, WRITE_TOOL_MARKERS,
  FIRST_MATE_FILING_TOOLS, FIRST_MATE_ALLOWED_TOOLS, FRAKWORKS_MCP_SERVER, DIAGNOSTICS_MCP_SERVER,
  FIRST_MATE_SYSTEM_PROMPT, FIRST_MATE_DENIED_TOOLS, claudeProjectSlug, firstMateMemoryDir,
  firstMateMemoryRule, firstMateAllowedTools, chatSettingsObject,
} = require("../src/claude-session");
const { normalizeFileLine, FILE_MIN_TIER, FILE_MAX_TIER } = require("../src/file-line");
const { FirstMateManager, UNFILED_TURN_HINT } = require("../src/first-mate");
const { makeFakeChild } = require("./fake-claude-child");

// ---------------------------------------------------------------------------
// Issue #601: popConversation, "Back to previous conversation" in one click.
// ---------------------------------------------------------------------------

const state = (m) => JSON.stringify({ h: m.session.history, c: m.session.conversations, s: m.session.claude_session_id,
  g: m.session.session_generation, n: m.session.next_conversation_id, t: m.session.turns_since_filed });

test("popConversation with two past conversations resumes the newest resumable one", async () => {
  const dir = tmpdir();
  const dump = path.join(dir, "argv.json");
  const m = fm({ argvDump: dump }, { dir });
  await m.send("live question");
  const liveSid = m.session.claude_session_id;
  // The archive is newest-first.
  m.session.conversations.push(past(8, "sess-newer", ["newer q", "newer a"]), past(5, "sess-older", ["older q", "older a"]));
  const gen = m.session.session_generation;
  m.rt.recovery = { kind: "child_exited", message: "x", session_id: null };
  m.rt.fileCard = { tier: 5, ask: "a", turn_id: "t" };
  m.rt.fileError = "boom";
  assert.equal(m.snapshot().can_pop, true);
  let updates = 0;
  m.on("update", () => { updates += 1; });
  assert.deepEqual(m.popConversation(), { ok: true });
  assert.equal(updates, 1);
  assert.equal(m.rt.child, null, "the running child is ended");
  assert.equal(m.session.session_generation, gen + 1);
  assert.equal(m.session.turns_since_filed, 0);
  assert.equal(m.rt.recovery, null);
  assert.equal(m.rt.fileCard, null);
  assert.equal(m.rt.fileError, null);
  assert.equal(m.rt.notice, "Back to the previous conversation.");
  assert.equal(m.snapshot().notice, "Back to the previous conversation.");
  assert.equal(m.session.claude_session_id, "sess-newer");
  assert.deepEqual(m.session.history.map((h) => h.text), ["newer q", "newer a"]);
  assert.deepEqual(m.session.conversations.map((c) => c.claude_session_id), [liveSid, "sess-older"]);
  const saved = JSON.parse(fs.readFileSync(path.join(dir, "first-mate.json"), "utf8")).session;
  assert.equal(saved.claude_session_id, "sess-newer");

  fs.rmSync(dump);
  await m.send("carry on");
  const argv = JSON.parse(fs.readFileSync(dump, "utf8"));
  assert.equal(argv[argv.indexOf("--resume") + 1], "sess-newer");
  m.stop();
});

test("popConversation skips a newer past conversation that has no session id", async () => {
  const m = fm({ sessionId: "sess-live" });
  await m.send("live question");
  m.session.conversations.push(past(9, null, ["legacy q", "legacy a"]), past(6, "sess-old", ["old q", "old a"]));
  assert.equal(m.popConversation().ok, true);
  assert.equal(m.session.claude_session_id, "sess-old");
  assert.deepEqual(m.session.history.map((h) => h.text), ["old q", "old a"]);
  assert.deepEqual(m.session.conversations.map((c) => c.claude_session_id), ["sess-live", null], "the legacy one stays put");
  m.stop();
});

test("popConversation with nothing resumable refuses and changes nothing", async () => {
  const dir = tmpdir();
  const m = fm({ sessionId: "sess-live" }, { dir });
  await m.send("live question");
  for (const archive of [[], [past(4, null, ["legacy q", "legacy a"])]]) {
    m.session.conversations = archive;
    assert.equal(m.snapshot().can_pop, false);
    const before = state(m);
    const file = fs.readFileSync(path.join(dir, "first-mate.json"), "utf8");
    let updates = 0;
    const onUpdate = () => { updates += 1; };
    m.on("update", onUpdate);
    const r = m.popConversation();
    m.off("update", onUpdate);
    assert.equal(r.ok, false);
    assert.equal(typeof r.error, "string");
    assert.equal(state(m), before);
    assert.equal(fs.readFileSync(path.join(dir, "first-mate.json"), "utf8"), file, "nothing saved");
    assert.equal(updates, 0);
  }
  m.stop();
});

test("after a pop the conversation left is conversations[0] and resumable, and a second pop comes back to it", async () => {
  const m = fm({ sessionId: "sess-live" });
  await m.send("left question");
  m.session.conversations.push(past(2, "sess-prev", ["prev q", "prev a"]));
  assert.equal(m.popConversation().ok, true);
  const left = m.session.conversations[0];
  assert.equal(left.claude_session_id, "sess-live");
  assert.equal(left.messages[0].text, "left question");
  assert.equal(m.getConversation(left.id).resumable, true);
  assert.equal(m.listConversations()[0].first_message, "left question");
  assert.equal(m.snapshot().can_pop, true);
  // The swap: pressing it again returns to the conversation just left.
  assert.equal(m.popConversation().ok, true);
  assert.equal(m.session.claude_session_id, "sess-live");
  assert.equal(m.session.history[0].text, "left question");
  assert.equal(m.session.conversations[0].claude_session_id, "sess-prev");
  m.stop();
});

test("resumeConversation keeps its own notice after the #601 refactor", async () => {
  const m = fm();
  m.session.conversations.push(past(3, "sess-old", ["q", "a"]));
  assert.equal(m.resumeConversation(3).ok, true);
  assert.equal(m.rt.notice, "Resumed a past conversation.");
  m.stop();
});

const tmpdir = () => fs.mkdtempSync(path.join(os.tmpdir(), "first-mate-test-"));
const settle = (ms = 40) => new Promise((r) => setTimeout(r, ms));

const fm = (config = {}, over = {}) => {
  const dir = over.dir || tmpdir();
  const { command, launchArgs } = makeFakeChild(path.join(dir, "bin"), config);
  return new FirstMateManager({
    dir, command, launchArgs,
    submitIntakeFn: async (ask) => ({ ok: true, status: 202, intakeId: "intake-1", askSeen: ask }),
    resolveTierFn: (t) => (t >= 1 && t <= 10 ? { model: "sonnet", effort: "medium" } : null),
    // The filed watch (#927) never reaches an engine here.
    fetchFlowFn: async () => ({ error: "no engine in tests" }),
    ...over,
  }).init();
};

// ---------------------------------------------------------------------------
// Acceptance criterion 1 (#100), as #231 changed it: the model/effort pair is
// main's per-turn resolution of the tier, passed in; --effort rides alongside
// --model the way mobile-bridge/server.py's `_start` does.
// ---------------------------------------------------------------------------

test("First mate's argv carries the resolved --model/--effort", () => {
  const argv = buildFirstMateArgv({ model: "opus", effort: "medium" });
  const at = argv.indexOf("--model");
  assert.equal(argv[at + 1], "opus");
  const eat = argv.indexOf("--effort");
  assert.equal(argv[eat + 1], "medium");
  // right after --model, mirroring mobile-bridge/server.py's `_start`.
  assert.equal(eat, at + 2);
});

test("the comments no longer say First mate's model is fixed", () => {
  const cs = fs.readFileSync(path.join(__dirname, "..", "src", "claude-session.js"), "utf8");
  const fmSrc = fs.readFileSync(path.join(__dirname, "..", "src", "first-mate.js"), "utf8");
  for (const [name, text] of [["claude-session.js", cs], ["first-mate.js", fmSrc]]) {
    assert.doesNotMatch(text, /fixed, hard-coded/, name);
    assert.doesNotMatch(text, /always runs at that pair/, name);
    assert.doesNotMatch(text, /Model, effort[^.]*unchanged/, name);
  }
});

test("First mate's tool policy is the read list, the frakworks tools and the memory rule, with no peer tools", () => {
  const argv = buildFirstMateArgv({ cwd: "/r" });
  const at = argv.indexOf("--allowedTools");
  assert.equal(argv[at + 1], [...CHAT_READ_TOOLS, ...FIRST_MATE_FILING_TOOLS, firstMateMemoryRule("/r")].join(","));
  for (const peerTool of ["ListAgents", "SendMessage"]) assert.ok(!argv[at + 1].includes(peerTool), peerTool);
  assert.ok(argv.includes("--strict-mcp-config"));
  // No --name: First mate mints no stable peer address, since it is never
  // ListAgents-visible.
  assert.ok(!argv.includes("--name"));
});

// Issue #290: the one write path. Argv, the settings file FirstMateManager
// writes and the --mcp-config document, read back and checked together.
test("argv, settings and MCP config allow exactly CHAT_READ_TOOLS, the filing tools and the memory rule, and nothing else writes", async () => {
  assert.deepEqual([...FIRST_MATE_FILING_TOOLS],
    ["mcp__frakworks__submit_ask", "mcp__frakworks__read_transcript",
      "mcp__frakworks__read_needs", "mcp__frakworks__read_ticket_pr", "mcp__frakworks__read_ticket_runs", "mcp__frakworks__approve", "mcp__frakworks__reauthorize",
      "mcp__frakworks__note_friction",
      "mcp__frakworks__read_repos", "mcp__frakworks__add_repo", "mcp__frakworks__edit_repo", "mcp__frakworks__remove_repo",
      "mcp__frakworks__read_access", "mcp__frakworks__add_access", "mcp__frakworks__remove_access",
      "mcp__frakworks__cancel_intake", "mcp__frakworks__amend_intake", "mcp__frakworks__amend_ticket",
      "mcp__frakworks__close_ticket", "mcp__frakworks__read_ci_runs", "mcp__frakworks__comment_ticket", "mcp__frakworks__search_tickets",
      "mcp__frakworks__read_ticket", "mcp__frakworks__list_private_docs", "mcp__frakworks__read_private_doc",
      "mcp__frakworks__read_repo_mcp_servers", "mcp__frakworks__add_repo_mcp_server", "mcp__frakworks__edit_repo_mcp_server",
      "mcp__frakworks__remove_repo_mcp_server", "mcp__frakworks__test_repo_mcp_server",
      "mcp__frakworks-engine-diagnostics__act_on_incident", "mcp__frakworks-engine-diagnostics__read_doctor_history"]);
  // #2484: the engine serves no HITLP, so First mate allows no mcp__hitlp__* tool.
  assert.ok(FIRST_MATE_ALLOWED_TOOLS.every((t) => !t.startsWith("mcp__hitlp__")));
  assert.deepEqual([...FIRST_MATE_ALLOWED_TOOLS], [...CHAT_READ_TOOLS, ...FIRST_MATE_FILING_TOOLS]);

  const dir = tmpdir();
  const cwd = path.join(dir, "repo");
  const expected = [...CHAT_READ_TOOLS, ...FIRST_MATE_FILING_TOOLS, firstMateMemoryRule(cwd)];
  const dump = path.join(dir, "argv.json");
  fs.mkdirSync(cwd);
  const m = fm({ argvDump: dump }, {
    dir, cwd,
    engineEnvFn: async () => ({ engineUrl: "http://127.0.0.1:9/", EXTRA: "dropped" }),
  });
  await m.send("hello");
  const argv = JSON.parse(fs.readFileSync(dump, "utf8"));
  m.stop();

  // argv
  assert.equal(argv[argv.indexOf("--allowedTools") + 1], expected.join(","));
  assert.ok(argv.includes("--strict-mcp-config"));
  assert.ok(!argv.includes("--name"));
  assert.equal(argv.filter((a) => a === "--mcp-config").length, 1);
  const mcpPath = argv[argv.indexOf("--mcp-config") + 1];
  assert.equal(mcpPath, m.mcpConfigPath);
  assert.equal(argv[argv.indexOf("--settings") + 1], m.settingsPath);

  // settings, as FirstMateManager wrote them
  const settings = JSON.parse(fs.readFileSync(m.settingsPath, "utf8"));
  assert.deepEqual(settings.permissions.allow, expected);
  assert.deepEqual(settings.permissions.deny, [...FIRST_MATE_DENIED_TOOLS]);
  assert.deepEqual(settings.enabledMcpjsonServers, []);
  assert.equal(settings.enableAllProjectMcpServers, false);

  assert.ok(expected.includes("mcp__frakworks-engine-diagnostics__act_on_incident"));

  // the --mcp-config document: the engine's two servers, the login as each one's header (issues #541, #663, #2159)
  const cfg = JSON.parse(fs.readFileSync(mcpPath, "utf8"));
  assert.equal(DIAGNOSTICS_MCP_SERVER, "frakworks-engine-diagnostics");
  assert.deepEqual(cfg.mcpServers, {
    [FRAKWORKS_MCP_SERVER]: {
      type: "http", url: "http://127.0.0.1:9/mcp/frakworks", headers: {},
    },
    [DIAGNOSTICS_MCP_SERVER]: {
      type: "http", url: "http://127.0.0.1:9/mcp/frakworks-engine-diagnostics", headers: {},
    },
  });
  assert.equal(require("../src/claude-session").FRAKWORKS_LOGIN_HEADER, undefined);
  assert.equal((fs.statSync(mcpPath).mode & 0o777), 0o600);

  // no write marker outside the filing tools and the one memory rule
  const memoryRule = firstMateMemoryRule(cwd);
  for (const allow of [argv[argv.indexOf("--allowedTools") + 1].split(","), settings.permissions.allow]) {
    assertOnlyMemoryWrite(allow, memoryRule);
    for (const w of ["MultiEdit", "NotebookEdit", "Task", "Bash(git push:*)", "Bash(git commit:*)",
      "Bash(gh issue create:*)", "Bash(gh api:*)"]) {
      assert.ok(!allow.includes(w), `${w} is allowed`);
      assert.ok(settings.permissions.deny.includes(w), `${w} is not denied`);
    }
  }
  // no settings file but ours may add a rule (issue #339, finding security-1-1)
  assert.equal(argv[argv.indexOf("--setting-sources") + 1], "");
});

test("First mate's prompt retries and cancels with act_on_incident, by ticket and repo or actionId, a cancel with the user's reason", () => {
  const p = FIRST_MATE_SYSTEM_PROMPT;
  assert.match(p, /act_on_incident/);
  assert.match(p, /ticket number and repo/);
  assert.match(p, /actionId of a read_needs incident row/);
  assert.match(p, /needs a reason from the user/);
});

test("First mate's prompt sends proposal feedback to the HITLP server as a revise, never submit_ask (#797)", () => {
  const p = FIRST_MATE_SYSTEM_PROMPT;
  assert.match(p, /Discuss proposal <owner>\/<repo>#<n>/);
  assert.match(p, /never call submit_ask: tell the user to decide the proposal's open decision on the HITLP server as a revise/);
  assert.match(p, /shows no open decision/);
  assert.match(p, /file nothing unless the user then asks for a ticket/);
  assert.match(p, /decide #783 on the HITLP server as a revise/);
  assert.doesNotMatch(p, /answer_question/);
});

test("the MCP config has no login header when no login is known", async () => {
  const m = fm({}, { engineEnvFn: async () => ({ engineUrl: "http://127.0.0.1:9" }) });
  await m.send("hello");
  m.stop();
  const servers = JSON.parse(fs.readFileSync(m.mcpConfigPath, "utf8")).mcpServers;
  assert.deepEqual(servers, {
    [FRAKWORKS_MCP_SERVER]: { type: "http", url: "http://127.0.0.1:9/mcp/frakworks", headers: {} },
    [DIAGNOSTICS_MCP_SERVER]: { type: "http", url: "http://127.0.0.1:9/mcp/frakworks-engine-diagnostics", headers: {} },
  });
});

// ---------------------------------------------------------------------------
// Issue #339: First mate may edit files in its own auto-memory directory, and
// nowhere else.
// ---------------------------------------------------------------------------

// The narrowed "no write marker" check: every write marker is refused in
// `allow` except the frakworks tools and exactly one `memoryRule`.
function assertOnlyMemoryWrite(allow, memoryRule) {
  assert.equal(allow.filter((t) => t === memoryRule).length, 1, `exactly one ${memoryRule}`);
  const rest = allow.filter((t) => !FIRST_MATE_FILING_TOOLS.includes(t) && !CHAT_READ_TOOLS.includes(t) && t !== memoryRule);
  for (const marker of WRITE_TOOL_MARKERS) {
    assert.ok(!rest.some((t) => t.includes(marker)), `${marker} is allowed`);
  }
}

test("cwd /Users/user/dev/frakworks gives the memory directory Claude Code derives for it", () => {
  const cwd = "/Users/user/dev/frakworks";
  assert.equal(claudeProjectSlug(cwd), "-Users-user-dev-frakworks");
  assert.equal(firstMateMemoryDir(cwd), "~/.claude/projects/-Users-user-dev-frakworks/memory/");
  assert.equal(firstMateMemoryRule(cwd), "Edit(~/.claude/projects/-Users-user-dev-frakworks/memory/**)");
  // every non-alphanumeric character becomes `-`, not only the slashes
  assert.equal(claudeProjectSlug("/home/a.b/my_repo"), "-home-a-b-my-repo");
  const argv = buildFirstMateArgv({ cwd });
  const allow = argv[argv.indexOf("--allowedTools") + 1].split(",");
  assert.deepEqual(allow, firstMateAllowedTools(cwd));
  assert.ok(allow.includes("Edit(~/.claude/projects/-Users-user-dev-frakworks/memory/**)"));
  assert.equal(argv[argv.indexOf("--setting-sources") + 1], "");
});

test("First mate's deny list drops only the bare Write and Edit; the base list keeps both", () => {
  assert.ok(!FIRST_MATE_DENIED_TOOLS.includes("Write"));
  assert.ok(!FIRST_MATE_DENIED_TOOLS.includes("Edit"));
  assert.ok(CHAT_DENIED_TOOLS.includes("Write"));
  assert.ok(CHAT_DENIED_TOOLS.includes("Edit"));
  assert.deepEqual([...FIRST_MATE_DENIED_TOOLS], CHAT_DENIED_TOOLS.filter((t) => t !== "Write" && t !== "Edit"));
  for (const t of ["MultiEdit", "NotebookEdit", "Task", "Bash(git push:*)", "Bash(rm:*)"]) {
    assert.ok(FIRST_MATE_DENIED_TOOLS.includes(t), t);
  }
  // chatSettingsObject's defaults are the base lists (the Chats panes that
  // used them went in #634; First mate always passes its own)
  assert.deepEqual(chatSettingsObject().permissions.deny, [...CHAT_DENIED_TOOLS]);
  assert.deepEqual(chatSettingsObject().permissions.allow, [...CHAT_READ_TOOLS]);
});

test("the narrowed write check allows only the one memory rule", () => {
  const rule = firstMateMemoryRule("/Users/user/dev/frakworks");
  const ok = [...FIRST_MATE_ALLOWED_TOOLS, rule];
  assertOnlyMemoryWrite(ok, rule);
  for (const bad of [
    "Write", "Edit",
    "Write(~/.claude/projects/-Users-user-dev-frakworks/memory/**)",
    "Edit(~/.claude/projects/-Users-matt-dev-other/memory/**)",
    "Edit(/Users/user/dev/frakworks/**)",
    rule,
  ]) {
    assert.throws(() => assertOnlyMemoryWrite([...ok, bad], rule), bad);
  }
  assert.throws(() => assertOnlyMemoryWrite([...FIRST_MATE_ALLOWED_TOOLS], rule), "missing rule");
});

// ---------------------------------------------------------------------------
// Acceptance criterion 4 (#100), as #290 changed it: First mate files itself.
// ---------------------------------------------------------------------------

test("the appended system prompt tells First mate to file with submit_ask, and no longer claims read-only", () => {
  const argv = buildFirstMateArgv({});
  const prompt = argv[argv.indexOf("--append-system-prompt") + 1];
  assert.equal(prompt, FIRST_MATE_SYSTEM_PROMPT);
  assert.match(prompt, /You can file tickets yourself/);
  assert.match(prompt, /submit_ask tool without asking for confirmation/);
  assert.match(prompt, /is the user's own sentences verbatim, unchanged, followed by one short paragraph headed Context: in your own words that names what the user's pronouns and shorthand refer to \(the repo, ticket, product or file under discussion\) and nothing more: no restating, paraphrase, expansion, summary or interpretation of the ask/);
  assert.match(prompt, /join the user's own sentences in order, each unchanged/);
  assert.match(prompt, /submit_ask, ask: "The retry button submits twice\."\)/);
  assert.match(prompt, /Pass tier only if the user named a tier/);
  assert.match(prompt, /never pick one yourself/);
  assert.match(prompt, /reply with the intake id and status/);
  assert.doesNotMatch(prompt, /answer_question/);
  assert.match(prompt, /what a ticket's Smith is doing or did, read it with read_transcript/);
  // Issue #476, #2471: read /needs through the tool, not curl; a wait is decided on the HITLP server.
  assert.match(prompt, /every such wait is a HITLP task the user decides on the HITLP server's decision page/);
  assert.match(prompt, /use read_needs, never curl/);
  // Issue #1173: a follow-up to an intake still refining amends it; never a second submit_ask.
  assert.match(prompt, /adds to an ask whose intake is still refining, send their added words verbatim with amend_intake \(the intake id\), never a second submit_ask/);
  // Issue #1362: a late addition to a filed ticket amends it; never a second submit_ask, never a cancel.
  assert.match(prompt, /adds to a ticket already filed \(a Smith may be working it\), send their added words verbatim with amend_ticket \(the ticket and its repo\), never a second submit_ask and never a cancel/);
  assert.match(prompt, /cancel it with cancel_intake/);
  // Issue #742: a ticket's PR through the tool, not gh or git.
  assert.match(prompt, /read a ticket's PR or proposal[^.]*use read_ticket_pr \(the ticket number\), never gh or git/);
  // Issue #1021: target repos are read, added, changed and removed through the tools; their secrets through none.
  assert.match(prompt, /target repos or read one's settings, use read_repos; when the user asks to add, change or remove a target repo, do it with add_repo, edit_repo or remove_repo \(the repo as owner\/name, always\), never curl/);
  assert.match(prompt, /token and webhook secret have no tool: the user sets them in Config > Repos/);
  // Issue #850: "re-authorize 724" goes through the tool, not a label.
  assert.match(prompt, /says "re-authorize #N"[^.]*re-authorize it with reauthorize \(the ticket number[^.]*never gh issue edit or a label/);
  assert.match(prompt, /Only if submit_ask fails[^.]*File: <tier> \| <ask>/);
  assert.match(prompt, /lack a tool for something the engine likely already does or holds, strongly consider filing a ticket with submit_ask to add that tool to your frakworks MCP server, rather than working around it with raw curl/);
  // Issue #545: an ask waits on an earlier one by its intake, never a guessed ticket number.
  assert.match(prompt, /name it in the ask as "Blocked by intake <intake id>" \(or its intake issue #N\); the engine resolves it to that intake's ticket, even if it has not been filed yet\. Never write a guessed ticket number/);
  // Issue #603: auto mode never promises a coming question about an open point.
  assert.match(prompt, /Auto is the feature mode when the user names none; in it the Smith settles any open point itself and proceeds/);
  assert.match(prompt, /after filing in auto mode, never tell the user that the refiner or a Smith will ask them about an open point \(such as where a control goes in the UI\) — say instead that a Smith will pick it and proceed/);
  assert.match(prompt, /Say the opposite, that a question is coming, only when the user named confirm in the ask/);
  assert.match(prompt, /A Smith will pick where the button goes and proceed/);
  assert.doesNotMatch(prompt, /read-only/i);
  assert.doesNotMatch(prompt, /by design/i);
  // Voice and 50-word cap kept from #100 / #291.
  assert.match(prompt, /at most 50 words/);
  assert.match(prompt, /warm, plain-spoken concierge/);
});

test("a submit_ask the child makes lands in the Filed list, like a button filing, tier null when none named", async () => {
  const m = fm({ fileTool: { result: { intakeId: "abcd1234-0000", status: 202, tier: null, ask: "wire the retry state" } } });
  const r = await m.send("file a ticket to wire the retry state");
  assert.equal(r.ok, true);
  const snap = m.snapshot();
  assert.equal(snap.filed.length, 1);
  assert.deepEqual(snap.filed[0], { intake_id: "abcd1234-0000", tier: null, ask: "wire the retry state", at: snap.filed[0].at,
    conversation_key: m.session.conversation_key, ticket: null, ticket_url: null, announced_at: null, ended_at: null });
  assert.equal(typeof snap.filed[0].at, "number");
  assert.equal(snap.turns_since_filed, 0);
  assert.match(snap.history[snap.history.length - 1].text, /file a ticket/, "the turn still resolves with its reply");
  // persisted, like fileTicket's
  const again = fm({}, { dir: m.dir });
  assert.equal(again.snapshot().filed[0].intake_id, "abcd1234-0000");
  m.stop();
});

test("a submit_ask's ticket is recorded at filing; the Filed as row comes only when the refined ticket differs (#100086)", async () => {
  const url = "https://github.com/acme/widgets/issues/100123";
  const m = fm({ fileTool: { result: { intakeId: "abcd1234-0009", status: 202, tier: null, ask: "x", repo: "acme/widgets", ticket: 100123, ticketUrl: url } } });
  await m.send("file x");
  const f = m.snapshot().filed[0];
  assert.equal(f.ticket, 100123);
  assert.equal(f.ticket_url, url);
  const rows = () => m.session.history.filter((h) => h.role === "system" && /Filed as/.test(h.text)).length;
  m._onIntakeFrame({ intakeId: "abcd1234-0009", state: "filed", ticket: 100123, ticketUrl: url });
  assert.equal(rows(), 0, "the reply already named it");
  assert.ok(m.snapshot().filed[0].announced_at);
  m.session.filed[0].announced_at = null; m.session.filed[0].ended_at = null;
  m._onIntakeFrame({ intakeId: "abcd1234-0009", state: "filed", ticket: 7, ticketUrl: "https://github.com/acme/other/issues/7" });
  assert.equal(rows(), 1, "filed in another repo: the row names the ticket there");
  assert.equal(m.snapshot().filed[0].ticket, 7);
  m.stop();
});

test("a submit_ask with a named tier records that tier", async () => {
  const m = fm({ fileTool: { result: { intakeId: "abcd1234-0001", status: 202, tier: 5, ask: "x" } } });
  await m.send("file x at tier 5");
  assert.equal(m.snapshot().filed[0].tier, 5);
  m.stop();
});

test("a note_friction the child makes is a tool-using turn like any other: its reply shows, nothing is filed, no error (#968)", async () => {
  const m = fm({
    fileTool: { name: "mcp__frakworks__note_friction",
      input: { kind: "tooling", summary: "no tool reads CI job logs", evidence: "CI job log for #812", repo: "acme/widgets" },
      result: { ok: true, id: "5f0c1d2e-0000" } },
    replyPrefix: "noted",
  });
  assert.ok(firstMateAllowedTools("/r").includes("mcp__frakworks__note_friction"));
  const r = await m.send("why did 812 fail");
  assert.equal(r.ok, true);
  const snap = m.snapshot();
  assert.equal(snap.filed.length, 0);
  assert.equal(snap.file_error, null);
  assert.equal(snap.error, null);
  assert.equal(snap.history[snap.history.length - 1].text, "noted: why did 812 fail");
  m.stop();
});

test("First mate's prompt notes what constrained an answer with note_friction and says so in one sentence (#968)", () => {
  const p = FIRST_MATE_SYSTEM_PROMPT;
  assert.match(p, /When something you lacked shaped your answer \(a tool you do not have; a repo, file, ticket, transcript or engine state you could not read; a tool call that failed or was refused; an instruction you had to read twice\), call note_friction once for it in that same turn/);
  assert.match(p, /the exact evidence \(the tool, error text or missing thing\), and the selected repo \(the repo line ahead of the message\) as repo when one is selected/);
  assert.match(p, /say in your reply, in one sentence, what constrained the answer and that you noted it/);
  assert.match(p, /Never ask before noting, and never stop or widen your work for it; if note_friction fails, say so in one clause and go on/);
  assert.match(p, /the note records that it happened, the ticket fixes it/);
  // the old rule stays
  assert.match(p, /strongly consider filing a ticket with submit_ask to add that tool/);
  // the example: a constrained answer with the note, within the 50-word limit
  const ex = p.split("User: Why did #812's CI fail?\n")[1];
  assert.ok(ex, "the constrained-answer example is there");
  assert.match(ex, /notes with note_friction, kind tooling/);
  const reply = ex.replace(/^First mate: \([^)]*\)\s*/, "");
  assert.match(reply, /I can't read CI job logs[^.]*I noted that\./);
  assert.ok(reply.trim().split(/\s+/).length <= 50, `${reply.trim().split(/\s+/).length} words`);
});

test("an isError submit_ask result records nothing", async () => {
  const m = fm({ fileTool: { isError: true, result: { error: "engine unreachable" } } });
  await m.send("file a ticket for y");
  assert.equal(m.snapshot().filed.length, 0);
  assert.equal(m.snapshot().turns_since_filed, 1);
  m.stop();
});

// ---------------------------------------------------------------------------
// Acceptance criterion 5: the File: carrier line.
// ---------------------------------------------------------------------------

test("normalizeFileLine parses a well-formed File: line and keeps the ask visible", () => {
  const r = normalizeFileLine("Sounds like a real bug.\nFile: 4 | the retry button double-submits");
  assert.deepEqual(r.file, { tier: 4, ask: "the retry button double-submits" });
  // Unlike Options:, the marker line is not stripped: the ask text still shows.
  assert.equal(r.reply, "Sounds like a real bug.\nthe retry button double-submits");
});

test("normalizeFileLine ignores a malformed or misplaced File: line, falling back like Options: does", () => {
  assert.equal(normalizeFileLine("just a normal reply").file, null);
  assert.equal(normalizeFileLine("just a normal reply").reply, "just a normal reply");
  // Out of tier range
  assert.equal(normalizeFileLine("File: 11 | too high a tier").file, null);
  assert.equal(normalizeFileLine("File: 0 | too low a tier").file, null);
  // Not the last non-empty line
  const mid = normalizeFileLine("File: 3 | stale marker\none more line after it");
  assert.equal(mid.file, null);
  assert.doesNotMatch(mid.reply, /^File:/m);
  // No body at all
  assert.equal(normalizeFileLine("File:").file, null);
  assert.equal(FILE_MIN_TIER, 1);
  assert.equal(FILE_MAX_TIER, 10);
});

// ---------------------------------------------------------------------------
// Send / receive, and the File: card landing in the snapshot
// ---------------------------------------------------------------------------

test("a plain reply becomes history; a File: reply becomes the file card, ask text intact", async () => {
  const m = fm();
  const r1 = await m.send("what does the retry button do");
  assert.equal(r1.ok, true);
  assert.equal(r1.text, "echo: what does the retry button do");
  assert.equal(m.snapshot().file, null);

  const r2 = await m.send("say Got it.\nFile: 6 | wire up the retry button's error state");
  assert.equal(r2.ok, true);
  assert.equal(r2.text, "Got it.\nwire up the retry button's error state");
  const snap = m.snapshot();
  assert.deepEqual(snap.file, { tier: 6, ask: "wire up the retry button's error state", turn_id: snap.file.turn_id });
  m.stop();
});

test("fileTicket posts Tier: n (model effort) — prescribed by the user; do not re-judge as the first line", async () => {
  const m = fm();
  await m.send("say Ready.\nFile: 6 | wire up the retry button's error state");
  const r = await m.fileTicket(6);
  assert.equal(r.ok, true);
  assert.equal(r.intake_id, "intake-1");
  const snap = m.snapshot();
  assert.equal(snap.file, null, "the card is replaced once filed");
  assert.deepEqual(snap.filed[0], { intake_id: "intake-1", tier: 6, ask: "wire up the retry button's error state", at: snap.filed[0].at,
    conversation_key: m.session.conversation_key, ticket: null, ticket_url: null, announced_at: null, ended_at: null });
  assert.equal(snap.turns_since_filed, 0);
  m.stop();
});

test("fileTicket files in the selected repo, a picked repo over it, and refuses in All repos (#577)", async () => {
  const calls = [];
  let selected = null;
  const m = fm({}, { submitIntakeFn: async (ask, repo) => { calls.push([ask.split("\n")[0], repo]); return { ok: true, intakeId: `i-${calls.length}` }; } });
  await m.send("say Ready.\nFile: 6 | a thing");
  m.repoFn = () => selected;
  const none = await m.fileTicket(6);
  assert.equal(none.ok, false);
  assert.match(none.error, /All repos is selected/);
  assert.equal(calls.length, 0, "nothing is submitted without a repo");
  const picked = await m.fileTicket(6, "o/picked");
  assert.equal(picked.ok, true);
  assert.deepEqual(calls[0][1], "o/picked");
  await m.send("say Ready.\nFile: 6 | another");
  selected = "o/selected";
  assert.equal((await m.fileTicket(6)).ok, true);
  assert.equal(calls[1][1], "o/selected");
  m.stop();
});

test("repoLine names the chat's repo, or says not to assume one in All repos (#2343)", () => {
  const { repoLine } = require("../src/first-mate");
  assert.match(repoLine("o/r"), /Chat repo: o\/r\./);
  assert.match(repoLine(null), /No repo chosen for this chat yet \(All repos\)\. Do not assume one: ask the user which repo/);
  assert.match(repoLine("o/b", { changedFrom: "o/a" }), /changed this chat's repo from o\/a to o\/b/);
  assert.match(repoLine("o/b", { changedFrom: null }), /from none to o\/b/);
});

test("kickStarterRepo reads the repo a ticket, proposal or file kick-starter names (#2343)", () => {
  const { kickStarterRepo } = require("../src/first-mate");
  assert.equal(kickStarterRepo("Discuss ticket o/n#12: x"), "o/n");
  assert.equal(kickStarterRepo("Discuss proposal o/n#3"), "o/n");
  assert.equal(kickStarterRepo("Discuss file o/n:src/a.js"), "o/n");
  assert.equal(kickStarterRepo("hello o/n"), null);
});

// The fake child echoes nothing we read here; what matters is the turn text
// handed to it, which _turnRepoLine builds.
test("All repos: a new chat has no repo, the turn says not to assume one, the chip sets it (#2343)", async () => {
  let selected = null;
  const m = fm({}, { repoFn: () => selected });
  m.startNewConversation();
  assert.equal(m.chatRepo(), null);
  assert.equal(m.snapshot().chat_repo, null);
  assert.equal(m.snapshot().chat_repo_on, true);
  assert.match(m._turnRepoLine(), /No repo chosen for this chat yet/);
  await m.send("say Ready.");
  assert.equal(m.setChatRepo("bad repo").ok, false);
  assert.equal(m.setChatRepo("o/n").ok, true);
  assert.equal(m.snapshot().chat_repo, "o/n");
  assert.match(m._turnRepoLine(), /Chat repo: o\/n\. The user changed this chat's repo from none to o\/n/);
  await m.send("say Ready.");
  assert.match(m._turnRepoLine(), /^\[Chat repo: o\/n\. File/, "the change is said once");
  selected = "o/other";
  assert.equal(m.chatRepo(), "o/n", "the switcher does not move a chat's repo");
  m.setChatRepo("o/m");
  assert.match(m._turnRepoLine(), /from o\/n to o\/m/);
  assert.equal(m.savePayload().body.repo, "o/m");
  m.setChatRepo(null);
  assert.match(m._turnRepoLine(), /No repo chosen.*from o\/n to none/);
  m.stop();
});

test("All repos: a ticket kick-starter sets the chat's repo; one repo selected names it (#2343)", async () => {
  let selected = null;
  const m = fm({}, { repoFn: () => selected });
  m.startNewConversation();
  await m.send("Discuss ticket o/k#5: thing");
  assert.equal(m.chatRepo(), "o/k");
  await m.send("Discuss ticket o/z#6");
  assert.equal(m.chatRepo(), "o/k", "a chosen repo is not overridden");
  selected = "o/one";
  m.startNewConversation();
  assert.equal(m.chatRepo(), "o/one");
  assert.match(m._turnRepoLine(), /^\[Chat repo: o\/one\. File and read there/);
  m.stop();
});

test("fileTicket failure leaves the card actionable for a retry", async () => {
  const m = fm({}, { submitIntakeFn: async () => ({ error: "engine unreachable" }) });
  await m.send("say Ready.\nFile: 2 | a small thing");
  const r = await m.fileTicket(2);
  assert.equal(r.ok, false);
  assert.match(r.error, /engine unreachable/);
  const snap = m.snapshot();
  assert.ok(snap.file, "the card is still there to retry");
  assert.equal(snap.file_error, "engine unreachable");
  m.stop();
});

test("fileTicket refuses a tier with no (model, effort) in the engine's tier map", async () => {
  const m = fm({}, { resolveTierFn: () => null });
  await m.send("say Ready.\nFile: 2 | a small thing");
  const r = await m.fileTicket(2);
  assert.equal(r.ok, false);
  assert.match(r.error, /tier map is not loaded/);
  m.stop();
});

// ---------------------------------------------------------------------------
// Acceptance criterion 10: the ~90s timeout, a named constant — fired here with a small injected value so
// the test does not sleep 90s.
// ---------------------------------------------------------------------------

test("FIRST_MATE_TURN_TIMEOUT_MS is 90s by default", () => {
  assert.equal(FIRST_MATE_TURN_TIMEOUT_MS, 90000);
});

test("a turn that never answers times out and recovers, on the pane's own (short, injected) timeout", async () => {
  const m = fm({ hang: true }, { turnTimeoutMs: 30 });
  const r = await m.send("HANG please");
  // Same shape a cancel resolves with (the timeout kills the child the same
  // way): the caller's promise settles, and the recovery state is what
  // distinguishes "timed out" from an ordinary cancel.
  assert.equal(r.ok, true);
  assert.equal(r.interrupted, true);
  const snap = m.snapshot();
  assert.equal(snap.recovery.kind, "timeout");
  assert.match(snap.recovery.message, /No answer after/);
  m.stop();
});

// ---------------------------------------------------------------------------
// Acceptance criterion 9: the 3-unfiled-turn hint.
// ---------------------------------------------------------------------------

test("the unfiled-turn hint appears after three turns with nothing filed, and clears on filing", async () => {
  assert.equal(UNFILED_TURN_HINT, 3);
  const m = fm();
  assert.equal(m.snapshot().unfiled_hint, false);
  await m.send("one");
  await m.send("two");
  assert.equal(m.snapshot().unfiled_hint, false);
  await m.send("three");
  assert.equal(m.snapshot().turns_since_filed, 3);
  assert.equal(m.snapshot().unfiled_hint, true);
  await m.send("say Okay.\nFile: 3 | a real ask");
  await m.fileTicket(3);
  assert.equal(m.snapshot().turns_since_filed, 0);
  assert.equal(m.snapshot().unfiled_hint, false);
  m.stop();
});

// ---------------------------------------------------------------------------
// Issue #185: "Start new conversation", the one relaxation of #100's
// fixed-identity rule.
// ---------------------------------------------------------------------------

const withoutResume = (argv) => {
  const at = argv.indexOf("--resume");
  return at >= 0 ? [...argv.slice(0, at), ...argv.slice(at + 2)] : argv;
};

test("startNewConversation drops the session, bumps the generation, zeroes the counter, keeps Filed, and archives history", async () => {
  const dir = tmpdir();
  const m = fm({ sessionId: "sess-1" }, { dir });
  await m.send("say Ready.\nFile: 6 | wire up the retry button's error state");
  await m.fileTicket(6);
  await m.send("one more thing");
  assert.equal(m.session.claude_session_id, "sess-1");
  assert.equal(m.session.turns_since_filed, 1);
  const gen = m.session.session_generation;
  const historyLen = m.session.history.length;
  assert.deepEqual(m.startNewConversation(), { ok: true });
  assert.equal(m.session.claude_session_id, null);
  assert.equal(m.session.session_generation, gen + 1);
  assert.equal(m.session.turns_since_filed, 0);
  assert.equal(m.session.filed.length, 1);
  assert.equal(m.session.history.length, 0, "history is archived, not marked inline");
  assert.equal(m.snapshot().history.length, 0);
  assert.equal(m.session.conversations.length, 1);
  assert.equal(m.session.conversations[0].messages.length, historyLen);
  assert.equal(m.snapshot().notice, "New conversation started.");
  const saved = JSON.parse(fs.readFileSync(path.join(dir, "first-mate.json"), "utf8"));
  const s = saved.session || saved;
  assert.equal(s.claude_session_id, null);
  assert.equal(s.session_generation, gen + 1);
  assert.equal(s.turns_since_filed, 0);
  assert.equal(s.filed.length, 1);
  assert.equal(s.history.length, 0);
  assert.equal(s.conversations.length, 1);
  m.stop();
});

test("startNewConversation on an empty history archives nothing, and stays empty until the next turn's exchange", async () => {
  const m = fm();
  assert.deepEqual(m.startNewConversation(), { ok: true });
  assert.equal(m.session.conversations.length, 0);
  assert.equal(m.snapshot().history.length, 0);
  await m.send("hello");
  assert.equal(m.snapshot().history.length, 2, "the next turn's exchange lands in history");
  m.stop();
});

test("after startNewConversation the next send spawns without --resume, with the same model, effort and tools", async () => {
  const dir = tmpdir();
  const dump = path.join(dir, "argv.json");
  const m = fm({ sessionId: "sess-1", argvDump: dump }, { dir });
  await m.send("hello");
  const before = JSON.parse(fs.readFileSync(dump, "utf8"));
  m.startNewConversation();
  fs.rmSync(dump);
  await m.send("hello again");
  const after = JSON.parse(fs.readFileSync(dump, "utf8"));
  assert.ok(!after.includes("--resume"), "a fresh session, not a resumed one");
  assert.deepEqual(after, withoutResume(before));
  for (const flag of ["--model", "--effort", "--allowedTools"]) {
    assert.equal(after[after.indexOf(flag) + 1], before[before.indexOf(flag) + 1], flag);
  }
  m.stop();
});

test("startNewConversation mid-turn kills the child and the pending send settles", async () => {
  const m = fm({ hang: true });
  const pending = m.send("take your time");
  await settle();
  const child = m.rt.child;
  assert.ok(child, "a turn is in flight");
  m.startNewConversation();
  const r = await pending;
  assert.equal(r.interrupted, true);
  assert.equal(m.rt.child, null);
  assert.equal(m.rt.busy, false);
  m.stop();
});

// ---------------------------------------------------------------------------
// Issue #257: the conversations archive, capTotal, and the read-only
// listConversations()/getConversation() manager accessors.
// ---------------------------------------------------------------------------

test("capTotal keeps history plus every conversation bounded across many conversations of turns", async () => {
  const m = fm();
  for (let round = 0; round < 5; round += 1) {
    for (let i = 0; i < 20; i += 1) await m.send(`round ${round} turn ${i}`);
    m.startNewConversation();
  }
  const total = m.session.history.length +
    m.session.conversations.reduce((n, c) => n + c.messages.length, 0);
  assert.ok(total <= 200, `total ${total} stayed within FIRST_MATE_HISTORY_LIMIT`);
  m.stop();
});

test("listConversations projects id, started_at and the first user message per past conversation", async () => {
  const m = fm();
  await m.send("first question");
  await m.send("second question");
  m.startNewConversation();
  await m.send("third question");
  m.startNewConversation();
  const list = m.listConversations();
  assert.equal(list.length, 2);
  // newest-first
  assert.equal(list[0].first_message, "third question");
  assert.equal(list[1].first_message, "first question");
  for (const c of list) {
    assert.equal(typeof c.id, "number");
    assert.equal(typeof c.started_at, "number");
  }
  m.stop();
});

test("listConversations falls back to the first message of any role when a conversation has no user-role message", () => {
  const m = fm();
  m.session.conversations = [
    { id: 1, started_at: 5, messages: [{ role: "system", text: "recovery notice", at: 5 }] },
    { id: 2, started_at: 1, messages: [] },
  ];
  const list = m.listConversations();
  assert.equal(list[0].first_message, "recovery notice");
  assert.equal(list[1].first_message, null, "an empty conversation has no first message");
  m.stop();
});

test("getConversation returns one conversation's full messages, or null for an unknown id", async () => {
  const m = fm();
  await m.send("only question");
  m.startNewConversation();
  const list = m.listConversations();
  const full = m.getConversation(list[0].id);
  assert.equal(full.id, list[0].id);
  assert.equal(full.messages.length, 2);
  assert.equal(full.messages[0].text, "only question");
  assert.equal(m.getConversation(999999), null);
  m.stop();
});

// ---------------------------------------------------------------------------
// Issue #486: resumeConversation, a past conversation made live again.
// ---------------------------------------------------------------------------

const past = (id, sid, texts) => ({
  id, started_at: id, claude_session_id: sid,
  messages: texts.map((text, i) => ({ role: i % 2 ? "assistant" : "user", text, at: id * 10 + i })),
});
// The ticket's count: the archive plus the live conversation.
const countAll = (s) => s.conversations.length + 1;

test("startNewConversation archives the closed conversation with its session id", async () => {
  const m = fm({ sessionId: "sess-1" });
  await m.send("hello");
  m.startNewConversation();
  assert.equal(m.session.conversations[0].claude_session_id, "sess-1");
  assert.equal(m.session.claude_session_id, null);
  assert.equal(m.getConversation(String(m.session.conversations[0].id)).resumable, true);
  m.stop();
});

test("after resumeConversation(id) the next turn spawns with --resume <that conversation's stored session id>", async () => {
  const dir = tmpdir();
  const dump = path.join(dir, "argv.json");
  const m = fm({ argvDump: dump }, { dir });
  await m.send("live question");
  const liveSid = m.session.claude_session_id;
  assert.ok(liveSid);
  m.session.conversations.push(past(7, "sess-old", ["old question", "old answer"]));
  const gen = m.session.session_generation;
  m.rt.recovery = { kind: "child_exited", message: "x", session_id: null };
  m.rt.fileCard = { tier: 5, ask: "a", turn_id: "t" };
  m.rt.fileError = "boom";
  let updates = 0;
  m.on("update", () => { updates += 1; });
  assert.deepEqual(m.resumeConversation("7"), { ok: true });
  assert.equal(updates, 1);
  assert.equal(m.rt.child, null, "the running child is ended");
  assert.equal(m.session.session_generation, gen + 1);
  assert.equal(m.rt.recovery, null);
  assert.equal(m.rt.fileCard, null);
  assert.equal(m.rt.fileError, null);
  assert.equal(m.session.claude_session_id, "sess-old");
  assert.deepEqual(m.session.history.map((h) => h.text), ["old question", "old answer"]);
  assert.ok(!m.session.conversations.some((c) => c.id === 7), "it left the archive");
  assert.equal(m.session.conversations[0].claude_session_id, liveSid, "the live one was archived with its id");
  assert.equal(m.session.conversations[0].messages[0].text, "live question");
  const saved = JSON.parse(fs.readFileSync(path.join(dir, "first-mate.json"), "utf8")).session;
  assert.equal(saved.claude_session_id, "sess-old");
  assert.equal(saved.history.length, 2);

  fs.rmSync(dump);
  await m.send("carry on");
  const argv = JSON.parse(fs.readFileSync(dump, "utf8"));
  assert.equal(argv[argv.indexOf("--resume") + 1], "sess-old");
  assert.deepEqual(m.session.history.map((h) => h.role), ["user", "assistant", "user", "assistant"]);
  m.stop();
});

test("resumeConversation mid-turn kills the child and the pending send settles", async () => {
  const m = fm({ hang: true });
  m.session.conversations.push(past(3, "sess-old", ["q", "a"]));
  const pending = m.send("take your time");
  await settle();
  assert.ok(m.rt.child, "a turn is in flight");
  assert.equal(m.resumeConversation(3).ok, true);
  const r = await pending;
  assert.equal(r.interrupted, true);
  assert.equal(m.rt.busy, false);
  m.stop();
});

test("resumeConversation refuses an unknown id and a conversation with no stored session id, changing nothing", async () => {
  const dir = tmpdir();
  const m = fm({ sessionId: "sess-live" }, { dir });
  await m.send("live question");
  m.session.conversations.push(past(4, null, ["legacy question", "legacy answer"]));
  const before = JSON.stringify({ h: m.session.history, c: m.session.conversations, s: m.session.claude_session_id,
    g: m.session.session_generation });
  for (const id of [4, "4", 999, "nope"]) {
    const r = m.resumeConversation(id);
    assert.equal(r.ok, false, String(id));
    assert.equal(typeof r.error, "string");
    assert.equal(JSON.stringify({ h: m.session.history, c: m.session.conversations, s: m.session.claude_session_id,
      g: m.session.session_generation }), before, String(id));
  }
  assert.match(m.resumeConversation(4).error, /archived before resume existed/);
  assert.equal(m.getConversation(4).resumable, false);
  m.stop();
});

test("resumeConversation never loses a conversation, and capTotal still holds", async () => {
  const m = fm();
  // A full archive: 199 past messages plus a 1-message live history.
  m.session.conversations = [past(2, "sess-b", Array.from({ length: 99 }, (_, i) => `b${i}`)),
    past(1, "sess-a", Array.from({ length: 100 }, (_, i) => `a${i}`))];
  m.session.next_conversation_id = 3;
  m.session.history = [{ role: "user", text: "live", at: 1 }];
  m.session.claude_session_id = "sess-live";
  const n = countAll(m.session);
  assert.equal(m.resumeConversation(1).ok, true);
  assert.equal(countAll(m.session), n);
  const total = () => m.session.history.length + m.session.conversations.reduce((k, c) => k + c.messages.length, 0);
  assert.ok(total() <= 200);
  assert.equal(m.session.history.length, 100, "the resumed conversation is whole");

  // With an empty live history nothing is archived: one fewer.
  m.session.history = [];
  const n2 = countAll(m.session);
  assert.equal(m.resumeConversation(2).ok, true);
  assert.equal(countAll(m.session), n2 - 1);
  assert.equal(m.session.conversations.length, 1);
  assert.ok(total() <= 200);
  m.stop();
});

test("resuming a session that no longer exists takes the resume_failed recovery path", async () => {
  const m = fm({ dieOnResume: true });
  m.session.conversations.push(past(5, "sess-gone", ["q", "a"]));
  assert.equal(m.resumeConversation(5).ok, true);
  const r = await m.send("are you there?");
  assert.equal(r.ok, false);
  assert.equal(m.snapshot().recovery.kind, "resume_failed");
  assert.match(m.snapshot().recovery.message, /Could not resume First mate's conversation: the session sess-gone/);
  m.stop();
});

// ---------------------------------------------------------------------------
// Issue #231: the tier is resolved per turn in main. These run against the
// engine's map as V42 seeds it, through src/engine-tiers.js (main loads it
// from GET /tiers), not the injected resolver above.
// ---------------------------------------------------------------------------
require("../src/engine-tiers").setTiers(require("./seeded-tiers").SEEDED_TIERS);

const argvLines = (file) => fs.existsSync(file)
  ? fs.readFileSync(file, "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l)) : [];
const flag = (argv, f) => (argv.indexOf(f) >= 0 ? argv[argv.indexOf(f) + 1] : null);

// A manager on the real tier table, with a config file and a movable clock.
const fmTier = (config = {}, over = {}) => {
  const dir = over.dir || tmpdir();
  const argvLog = path.join(dir, "argv.log");
  const { command, launchArgs } = makeFakeChild(path.join(dir, "bin"), { argvLog, ...config });
  const clock = { t: 1_000_000 };
  const m = new FirstMateManager({
    dir, command, launchArgs,
    configFile: path.join(dir, "agent-army.config.json"),
    submitIntakeFn: async () => ({ ok: true, intakeId: "intake-1" }),
    now: () => clock.t,
    ...over,
  }).init();
  return { m, dir, clock, argvLog, configFile: path.join(dir, "agent-army.config.json") };
};

test("a changed tier restarts the child with --resume and the new --model/--effort, once, with an uncached notice", async () => {
  const { m, argvLog } = fmTier({ sessionId: "sess-t" });
  await m.send("one");
  let log = argvLines(argvLog);
  assert.equal(log.length, 1);
  assert.equal(flag(log[0], "--model"), "fable");
  assert.equal(flag(log[0], "--effort"), "low");
  assert.ok(!log[0].includes("--resume"));
  assert.deepEqual(m.snapshot().tier, { tier: 8, model: "fable", effort: "low", source: "standing" });

  assert.equal(m.setStandingTier(5).ok, true);
  const r = await m.send("two");
  assert.equal(r.text, "echo: two");
  log = argvLines(argvLog);
  assert.equal(log.length, 2, "the child was restarted");
  assert.equal(flag(log[1], "--resume"), "sess-t");
  assert.equal(flag(log[1], "--model"), "opus");
  assert.equal(flag(log[1], "--effort"), "medium");
  assert.match(m.snapshot().tier_notice, /uncached/);
  assert.equal(m.snapshot().tier.tier, 5);

  await m.send("three");
  assert.equal(argvLines(argvLog).length, 2, "same tier, no restart");
  assert.equal(m.snapshot().tier_notice, null, "the notice is one-time");
  m.stop();
});

test("a Fable-limit result is retried once at tier 5 with a notice, and the config file is untouched", async () => {
  const { m, argvLog, configFile } = fmTier({ sessionId: "sess-f", fableLimited: true });
  fs.writeFileSync(configFile, JSON.stringify({ tickets_url: "https://oracle.example/tickets/mcp", first_mate: { tier: 8 } }));
  const before = fs.readFileSync(configFile);
  const r = await m.send("hello there");
  assert.equal(r.ok, true);
  assert.equal(r.text, "echo: hello there");
  const log = argvLines(argvLog);
  assert.equal(log.length, 2);
  assert.equal(flag(log[0], "--model"), "fable");
  assert.equal(flag(log[1], "--resume"), "sess-f");
  assert.equal(flag(log[1], "--model"), "opus");
  assert.equal(flag(log[1], "--effort"), "medium");
  const snap = m.snapshot();
  assert.equal(snap.history.filter((h) => h.role === "user").length, 1, "one user row");
  assert.ok(!snap.history.some((h) => /fable limit/i.test(h.text)), "no Fable-limit reply row");
  assert.match(snap.tier_notice, /Fable limit/);
  assert.equal(snap.tier.source, "fallback");
  assert.equal(snap.tier.tier, 5);
  assert.deepEqual(fs.readFileSync(configFile), before, "saved settings unchanged");
  assert.equal(m.tierState().standing, 8);
  m.stop();
});

test("a second Fable-limit failure is not retried", async () => {
  const { m, argvLog, configFile } = fmTier();
  const limit = "You've reached your Fable limit. Switch to another model to continue.";
  const r = await m.send(`say ${limit}`);
  assert.equal(r.ok, true);
  assert.equal(r.text, limit);
  assert.equal(argvLines(argvLog).length, 2, "exactly two starts");
  assert.ok(!fs.existsSync(configFile), "nothing was written");
  m.stop();
});

test("setStandingTier rejects out-of-range, non-integer and non-number tiers", () => {
  const { m, configFile } = fmTier();
  for (const bad of [0, 11, 2.5, NaN, "8"]) {
    assert.equal(m.setStandingTier(bad).ok, false, String(bad));
  }
  assert.ok(!fs.existsSync(configFile), "no refused call wrote anything");
  m.stop();
});

test("no usage pre-check: First mate makes no /accounts/usage call and no engine account matching", () => {
  for (const f of ["first-mate.js", "first-mate-tier.js"]) {
    const text = fs.readFileSync(path.join(__dirname, "..", "src", f), "utf8");
    assert.doesNotMatch(text, /accounts\/usage/, f);
    assert.doesNotMatch(text, /\bfetch\(/, f);
    assert.doesNotMatch(text, /fetchAccounts|accountUsage|fetchUsage/i, f);
  }
});

// ---------------------------------------------------------------------------
// Saved chats (#598): savePayload and restoreSavedChat
// ---------------------------------------------------------------------------

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

test("savePayload is null on an empty history, else the live conversation under one id that stays across saves", async () => {
  const m = fm({ sessionId: "sess-save" }, { hostname: () => "laptop", repoFn: () => "o/r" });
  assert.equal(m.savePayload(), null);
  assert.equal(m.snapshot().can_save, false);
  await m.send("what is on today\nsecond line");
  assert.equal(m.snapshot().can_save, true);
  const p = m.savePayload();
  assert.match(p.id, UUID);
  assert.equal(m.snapshot().saved_chat_id, p.id);
  assert.equal(p.body.title, "what is on today");
  assert.equal(p.body.claudeSessionId, "sess-save");
  assert.equal(p.body.savedFrom, "laptop");
  assert.equal(p.body.repo, "o/r");
  assert.equal(p.body.tier, 8);
  assert.ok(!Number.isNaN(Date.parse(p.body.startedAt)));
  assert.deepEqual(p.body.messages.map((x) => x.role), ["user", "assistant"]);
  await m.send("more");
  const q = m.savePayload();
  assert.equal(q.id, p.id, "a second save updates the first");
  assert.equal(q.body.messages.length, 4);
  m.startNewConversation();
  assert.equal(m.session.saved_chat_id, null, "a new conversation is a new save");
  assert.equal(m.session.conversations[0].saved_chat_id, p.id);
  m.stop();
});

// Continue in terminal (#819): the live conversation as a saved chat's shape.
test("handoffPayload is null while empty or busy, and carries savePayload's id and body", async () => {
  const m = fm({ sessionId: "sess-hand" }, { hostname: () => "laptop", repoFn: () => "o/r" });
  assert.equal(m.handoffPayload(), null);
  assert.equal(m.snapshot().can_handoff, false);
  await m.send("hand this off");
  assert.equal(m.snapshot().can_handoff, true);
  const h = m.handoffPayload();
  assert.match(h.id, UUID);
  assert.equal(m.session.saved_chat_id, h.id, "minted and kept on the session");
  // `cwd` (#960) is the conversation's, for main's hand-off; it is not in the saved-chat body.
  assert.deepEqual(Object.keys(h).sort(),
    ["claudeSessionId", "cwd", "id", "messages", "repo", "savedFrom", "startedAt", "tier", "title"]);
  assert.equal(h.cwd, m.cwd, "the legacy cwd with no freshDir");
  assert.equal(h.claudeSessionId, "sess-hand");
  assert.equal(h.title, "hand this off");
  const p = m.savePayload();
  assert.equal(p.id, h.id, "the same id Save for later uses");
  assert.deepEqual({ id: p.id, ...p.body, cwd: m.conversationCwd() }, m.handoffPayload());
  m.rt.busy = true;
  assert.equal(m.handoffPayload(), null);
  assert.equal(m.snapshot().can_handoff, false);
  m.rt.busy = false;
  m.stop();
});

const savedChat = (over = {}) => ({
  id: "0b7c9a1e-3f2d-4c5b-9a8e-1d2c3b4a5f60", title: "Saved one", claudeSessionId: "sess-saved",
  messages: [{ role: "user", text: "saved question", at: 10 }, { role: "assistant", text: "saved answer", at: 11 }],
  ...over,
});

test("restoring a resumable saved chat archives the live one and the next turn --resumes its session", async () => {
  const dir = tmpdir();
  const dump = path.join(dir, "argv.json");
  const input = path.join(dir, "input.jsonl");
  const m = fm({ argvDump: dump, inputLog: input }, { dir });
  await m.send("live question");
  const liveSid = m.session.claude_session_id;
  const gen = m.session.session_generation;
  assert.deepEqual(m.restoreSavedChat(savedChat(), { resumable: true }), { ok: true, resumed: true });
  assert.equal(m.rt.child, null);
  assert.equal(m.session.session_generation, gen + 1);
  assert.equal(m.session.conversations[0].claude_session_id, liveSid);
  assert.equal(m.session.conversations[0].messages[0].text, "live question");
  assert.deepEqual(m.session.history.map((h) => h.text), ["saved question", "saved answer"]);
  assert.equal(m.session.saved_chat_id, savedChat().id);
  assert.equal(m.session.seed, null);
  fs.rmSync(dump); fs.rmSync(input);
  await m.send("carry on");
  const argv = JSON.parse(fs.readFileSync(dump, "utf8"));
  assert.equal(argv[argv.indexOf("--resume") + 1], "sess-saved");
  assert.doesNotMatch(fs.readFileSync(input, "utf8"), /Restored saved chat/);
  m.stop();
});

test("restoring a chat saved on another machine starts a fresh session, seeded on its first turn only", async () => {
  const dir = tmpdir();
  const dump = path.join(dir, "argv.json");
  const input = path.join(dir, "input.jsonl");
  const m = fm({ argvDump: dump, inputLog: input }, { dir });
  assert.deepEqual(m.restoreSavedChat(savedChat(), { resumable: false }), { ok: true, resumed: false });
  assert.equal(m.session.claude_session_id, null);
  assert.match(m.session.seed, /^\[Restored saved chat "Saved one"/);
  assert.match(m.snapshot().notice || "", /another machine/);
  await m.send("pick it up");
  const argv = JSON.parse(fs.readFileSync(dump, "utf8"));
  assert.equal(argv.indexOf("--resume"), -1);
  const first = fs.readFileSync(input, "utf8");
  assert.match(first, /Restored saved chat/);
  assert.match(first, /saved question/);
  assert.match(first, /pick it up/);
  assert.equal(m.session.seed, null, "the seed is spent once the turn is answered");
  const shown = m.session.history.map((h) => h.text);
  assert.deepEqual(shown.slice(0, 3), ["saved question", "saved answer", "pick it up"]);
  // (the fake child echoes what it was sent, so only the user's rows are checked)
  assert.ok(!m.session.history.some((h) => h.role === "user" && /Restored saved chat/.test(h.text)),
    "the preamble is never the user's row");
  fs.rmSync(input);
  await m.send("and again");
  assert.doesNotMatch(fs.readFileSync(input, "utf8"), /Restored saved chat/);
  // saving again updates the same saved chat
  assert.equal(m.savePayload().id, savedChat().id);
  m.stop();
});

test("restoreSavedChat refuses a chat with no messages, changing nothing", async () => {
  const m = fm();
  await m.send("keep me");
  const before = JSON.stringify(m.session);
  assert.equal(m.restoreSavedChat(savedChat({ messages: [] })).ok, false);
  assert.equal(m.restoreSavedChat(null).ok, false);
  assert.equal(JSON.stringify(m.session), before);
  m.stop();
});

// ---------------------------------------------------------------------------
// Open in Claude Code ⇗ (#820): handoffPlan, handoff, can_open_in_iterm, the sweep.
// ---------------------------------------------------------------------------

const { itermSeed, ITERM_SEED_HEAD, ITERM_ROW } = require("../src/first-mate");
const ITERM = { available: true, why: null, app: "/Applications/iTerm.app" };
const NO_ITERM = { available: false, why: "iTerm2 is not installed (looked in /Applications and ~/Applications).", app: null };

// A manager with iTerm2 "present", a fake osascript and a real writeHandoff
// into its own dir. `opened` collects what openInIterm was asked to run.
// The cwd must exist (the fake child is spawned in it): a "repo dir" under tmp.
const fmIterm = (over = {}, { opened = [], open = async () => ({ ok: true }), exists = () => true } = {}) => {
  const dir = over.dir || tmpdir();
  const cwd = path.join(dir, "repo dir");
  fs.mkdirSync(cwd, { recursive: true });
  return fm({ sessionId: over.sessionId || "sess-h" }, {
    dir, cwd,
    iterm: ITERM,
    sessionFileExistsFn: (c, id) => exists(c, id),
    openInItermFn: async (launcherPath, opts) => { opened.push({ launcherPath, ...opts }); return open(launcherPath, opts); },
    ...over,
  });
};

test("handoffPlan picks resume when the session file is here, seed otherwise, through the injected sessionFileExistsFn", async () => {
  const seen = [];
  const m = fmIterm({ sessionId: "sess-plan" }, { exists: (cwd, id) => { seen.push([cwd, id]); return id === "sess-plan"; } });
  await m.send("first question");
  assert.deepEqual(m.itermPlan(), { mode: "resume", resumeId: "sess-plan" });
  assert.deepEqual(seen, [[m.cwd, "sess-plan"]]);
  assert.match(m.cwd, /repo dir$/);
  m.session.claude_session_id = "sess-elsewhere";
  const plan = m.itermPlan();
  assert.equal(plan.mode, "seed");
  assert.match(plan.seed, /^\[Handed off from First mate in the frakworks desktop app\. The conversation so far, for context; continue from it:\]\n\nThe user: first question/);
  m.session.claude_session_id = null;
  assert.equal(m.itermPlan().mode, "seed", "no session id at all seeds too");
  m.stop();
});

test("itermSeed is seedPreamble with its first line reworded, the rest untouched", () => {
  const history = [{ role: "user", text: "q", at: 1 }, { role: "assistant", text: "a", at: 2 }];
  const s = itermSeed(history);
  assert.equal(s.split("\n")[0], ITERM_SEED_HEAD);
  assert.doesNotMatch(s, /Restored saved chat/);
  assert.match(s, /\n\nThe user: q\n\nFirst mate: a\n\n\[End of the restored conversation/);
});

test("openInIterm() writes the launcher, opens it in iTerm2, adds the system row, and keeps the session id and history", async () => {
  const opened = [];
  const dir = tmpdir();
  const m = fmIterm({ dir, sessionId: "sess-keep" }, { opened });
  await m.send("hand me over");
  const before = { sid: m.session.claude_session_id, history: m.session.history.map((h) => h.text), gen: m.session.session_generation };
  assert.equal(m.snapshot().can_open_in_iterm, true);
  let updates = 0;
  m.on("update", () => { updates += 1; });
  assert.deepEqual(await m.openInIterm(), { ok: true, mode: "resume" });
  assert.equal(updates, 1);
  assert.equal(opened.length, 1);
  assert.equal(path.dirname(opened[0].launcherPath), path.join(dir, "iterm"));
  assert.equal(opened[0].seedPath, null);
  const script = fs.readFileSync(opened[0].launcherPath, "utf8");
  assert.ok(script.includes(`cd '${m.cwd}' || exit 1`), "cds to the manager's cwd, quoted (it has a space)");
  assert.match(script, /--resume 'sess-keep' --fork-session/);
  assert.doesNotMatch(script, /--worktree/);
  assert.match(script, new RegExp(`exec '${m.command.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}'`), "the manager's own claude command");
  assert.equal(m.session.claude_session_id, before.sid, "the session id is untouched");
  assert.equal(m.session.session_generation, before.gen);
  const rows = m.session.history;
  assert.deepEqual(rows.slice(0, -1).map((h) => h.text), before.history);
  assert.deepEqual({ role: rows.at(-1).role, text: rows.at(-1).text }, { role: "system", text: ITERM_ROW });
  assert.equal(ITERM_ROW, "Handed off to Claude Code in iTerm2.");
  const saved = JSON.parse(fs.readFileSync(path.join(dir, "first-mate.json"), "utf8")).session;
  assert.equal(saved.history.at(-1).text, ITERM_ROW);
  assert.equal(m.rt.child && m.rt.child.alive, true, "the running child is left alone");
  // Save for later still works on the same id afterwards.
  assert.equal(m.savePayload().body.claudeSessionId, "sess-keep");
  m.stop();
});

test("openInIterm() in seed mode writes the seed beside the launcher, whose rm -f names it", async () => {
  const opened = [];
  const m = fmIterm({}, { opened, exists: () => false });
  await m.send("seed me");
  assert.deepEqual(await m.openInIterm(), { ok: true, mode: "seed" });
  const { launcherPath, seedPath } = opened[0];
  assert.ok(seedPath && seedPath.endsWith(".md"));
  assert.equal(path.dirname(seedPath), path.dirname(launcherPath));
  const seed = fs.readFileSync(seedPath, "utf8");
  assert.match(seed, /^\[Handed off from First mate/);
  assert.match(seed, /The user: seed me/);
  const script = fs.readFileSync(launcherPath, "utf8");
  assert.ok(script.includes(`rm -f -- "$0" '${seedPath}'`), "the launcher deletes the seed");
  assert.doesNotMatch(script, /--resume/);
  m.stop();
});

test("openInIterm() is refused, changing nothing, while a turn runs, with no history, and without iTerm2", async () => {
  const opened = [];
  const empty = fmIterm({}, { opened });
  assert.equal(empty.snapshot().can_open_in_iterm, false);
  assert.deepEqual(await empty.openInIterm(), { ok: false, error: "No conversation to hand off yet." });
  empty.stop();

  const busy = fmIterm({ ...{} }, { opened });
  busy.session.history.push({ role: "user", text: "x", at: 1 });
  busy.rt.busy = true;
  assert.equal(busy.snapshot().can_open_in_iterm, false);
  assert.deepEqual(await busy.openInIterm(), { ok: false, error: "First mate is still working. Wait for it, or cancel." });
  busy.rt.busy = false;
  busy.stop();

  let detects = 0;
  const none = fmIterm({ iterm: NO_ITERM, detectItermFn: () => { detects += 1; return NO_ITERM; } }, { opened });
  await none.send("hi");
  const snap = none.snapshot();
  assert.equal(snap.can_open_in_iterm, false);
  assert.deepEqual(snap.iterm, { available: false, why: NO_ITERM.why });
  const before = JSON.stringify(none.session);
  assert.deepEqual(await none.openInIterm(), { ok: false, error: NO_ITERM.why });
  assert.equal(detects, 1, "looked again on the click");
  assert.equal(JSON.stringify(none.session), before);
  assert.equal(opened.length, 0, "nothing was opened in any refused case");
  none.stop();
});

test("iTerm2 installed during the run is seen on the click", async () => {
  const opened = [];
  const m = fmIterm({ iterm: NO_ITERM, detectItermFn: () => ITERM }, { opened });
  await m.send("hi");
  assert.equal(m.snapshot().can_open_in_iterm, false);
  assert.deepEqual(await m.openInIterm(), { ok: true, mode: "resume" });
  assert.deepEqual(m.snapshot().iterm, { available: true, why: null });
  assert.equal(m.snapshot().can_open_in_iterm, true);
  m.stop();
});

test("a refused osascript comes back as its error with no row added, and a manager default is Linux: not available", async () => {
  const m = fmIterm({}, { open: async () => ({ ok: false, error: "macOS refused automation of iTerm2. Allow it in System Settings > Privacy & Security > Automation." }) });
  await m.send("hi");
  const n = m.session.history.length;
  const r = await m.openInIterm();
  assert.equal(r.ok, false);
  assert.match(r.error, /refused automation/);
  assert.equal(m.session.history.length, n);
  m.stop();
  // Without an injected `iterm`, detection runs here: this sandbox is not a Mac.
  const plain = fm();
  assert.equal(plain.snapshot().iterm.available, process.platform === "darwin" ? plain.iterm.available : false);
  if (process.platform !== "darwin") assert.equal(plain.snapshot().iterm.why, "Only on macOS: iTerm2 is a Mac app.");
  plain.stop();
});

test("init() sweeps the handoff dir of launchers older than an hour", () => {
  const dir = tmpdir();
  const handoffDir = path.join(dir, "iterm");
  fs.mkdirSync(handoffDir);
  const old = path.join(handoffDir, "old.sh"), fresh = path.join(handoffDir, "fresh.sh");
  fs.writeFileSync(old, "x"); fs.writeFileSync(fresh, "x");
  const ago = new Date(Date.now() - 2 * 3600000);
  fs.utimesSync(old, ago, ago);
  const swept = [];
  const m = new FirstMateManager({ dir, command: "true", iterm: NO_ITERM,
    sweepHandoffDirFn: (d, ms) => { swept.push([d, ms]); return require("../src/first-mate-iterm").sweepHandoffDir(d, ms); } }).init();
  assert.deepEqual(swept, [[handoffDir, 3600000]]);
  assert.deepEqual(fs.readdirSync(handoffDir), ["fresh.sh"]);
  // A sweep that throws never stops init.
  const m2 = new FirstMateManager({ dir, command: "true", iterm: NO_ITERM, sweepHandoffDirFn: () => { throw new Error("boom"); } }).init();
  assert.ok(m2.session);
  m.stop(); m2.stop();
});

// ---------------------------------------------------------------------------
// #815: Escape / Stop in the strip calls cancel() while busy; the turn ends
// interrupted, busy clears, and the next send runs in a fresh session.
// ---------------------------------------------------------------------------

test("cancel() during a busy turn: interrupted row, not busy, next send starts a new session", async () => {
  const { INTERRUPTED_TEXT } = require("../src/claude-session");
  const m = fm({ hang: true });
  const gen = m.snapshot().session_generation;
  const pending = m.send("HANG please");
  assert.equal(m.snapshot().busy, true);
  m.cancel();
  const r = await pending;
  assert.equal(r.interrupted, true);
  assert.equal(r.text, INTERRUPTED_TEXT);
  const snap = m.snapshot();
  assert.equal(snap.busy, false);
  assert.ok(snap.session_generation > gen);
  assert.ok(snap.history.some((h) => h.text === INTERRUPTED_TEXT));
  m.stop();
});

// ---------------------------------------------------------------------------
// First mate's account (engine/README.md, "First mate's account"): each child
// start asks the engine which account a Smith on that model would get, and
// runs on its long-lived token.
// ---------------------------------------------------------------------------

// The real spawn, with the options each child was started with kept.
const spawnRecorder = () => {
  const spawned = [];
  const spawnFn = (command, argv, options) => { spawned.push(options); return require("node:child_process").spawn(command, argv, options); };
  return { spawned, spawnFn };
};

test("the child runs on the engine's picked account: its token in the env, its name in the snapshot", async () => {
  const { spawned, spawnFn } = spawnRecorder();
  const asked = [];
  const saved = { key: process.env.ANTHROPIC_API_KEY };
  process.env.ANTHROPIC_API_KEY = "sk-ant-api-own";
  try {
    const m = fm({ sessionId: "sess-acct" }, {
      spawnFn,
      pickAccountFn: async (model) => { asked.push(model); return { ok: true, account: "anthropic-primary", envVar: "CLAUDE_CODE_OAUTH_TOKEN", token: "sk-ant-oat01-picked" }; },
    });
    const r = await m.send("hello");
    assert.equal(r.text, "echo: hello");
    assert.deepEqual(asked, ["sonnet"], "asked once, for the tier's model");
    assert.equal(spawned.length, 1);
    assert.equal(spawned[0].env.CLAUDE_CODE_OAUTH_TOKEN, "sk-ant-oat01-picked");
    assert.equal(spawned[0].env.ANTHROPIC_API_KEY, undefined, "no other Anthropic credential beside it");
    assert.equal(spawned[0].env.PATH, process.env.PATH, "the rest of the environment is ours");
    const snap = m.snapshot();
    assert.equal(snap.account, "anthropic-primary");
    assert.equal(snap.account_notice, null);
    assert.ok(!JSON.stringify(snap).includes("sk-ant-oat01-picked"), "the token never reaches the renderer");
    await m.send("again");
    assert.deepEqual(asked, ["sonnet"], "a running child is not re-picked");
    m.stop();
  } finally {
    if (saved.key === undefined) delete process.env.ANTHROPIC_API_KEY; else process.env.ANTHROPIC_API_KEY = saved.key;
  }
});

test("with no engine account the child falls back to this machine's own login, and says so", async () => {
  const { spawned, spawnFn } = spawnRecorder();
  const m = fm({ sessionId: "sess-own" }, {
    spawnFn,
    pickAccountFn: async () => ({ error: "engine answered 503 for POST /accounts/pick: no usable anthropic account" }),
  });
  const r = await m.send("hello");
  assert.equal(r.text, "echo: hello");
  assert.equal(spawned[0].env, undefined, "the child inherits our environment");
  assert.equal(m.snapshot().account, null);
  assert.match(m.snapshot().account_notice, /No engine account.*503.*own Claude login/);
  m.stop();
});

test("without a pick function nothing is asked and the child inherits the environment", async () => {
  const { spawned, spawnFn } = spawnRecorder();
  const m = fm({ sessionId: "sess-none" }, { spawnFn });
  await m.send("hello");
  assert.equal(spawned[0].env, undefined);
  assert.equal(m.snapshot().account, null);
  assert.equal(m.snapshot().account_notice, null);
  m.stop();
});

// ---------------------------------------------------------------------------
// First mate runs from fresh code (#960, part 2 of #973): the conversation's
// cwd, the prompt's fresh-code paragraph and instruction sections, the
// unchanged tool lists, the restart on a changed CLAUDE.md, the wait for a
// missing clone. The clone keeper is a stub here; src/fresh-code.js has its own tests.
// ---------------------------------------------------------------------------

const { freshCodeParagraph, instructionSection, freshRepoOf } = require("../src/claude-session");

// A clone keeper stub: `ready` names the repos whose clones exist; `instructions`
// is what readRepoInstructions answers per dir; `cloneDelayMs` makes ensureClone wait.
function fakeFresh(freshDir, { ready = [], instructions = {}, cloneDelayMs = 20, cloneError = null } = {}) {
  const f = {
    freshDir, ready: new Set(ready), instructions, calls: [], cloning: new Set(),
    status(repo) { return this.cloning.has(repo) ? "cloning" : this.ready.has(repo) ? "ready" : "missing"; },
    async ensureClone(repo) {
      this.calls.push(["ensureClone", repo]);
      this.cloning.add(repo);
      await new Promise((r) => setTimeout(r, cloneDelayMs));
      this.cloning.delete(repo);
      if (cloneError) return { error: cloneError };
      this.ready.add(repo);
      fs.mkdirSync(path.join(freshDir, ...repo.split("/")), { recursive: true });
      return { commit: "c".repeat(40) };
    },
    readRepoInstructions(dir) { return (this.instructions[dir] || []).map((i) => ({ ...i })); },
  };
  return f;
}

// A manager on fresh code: `freshDir` under tmp, the clones of `ready` made as plain dirs.
const fmFresh = (config = {}, over = {}, fresh = {}) => {
  const dir = tmpdir();
  const freshDir = path.join(dir, "fresh");
  for (const repo of fresh.ready || []) fs.mkdirSync(path.join(freshDir, ...repo.split("/")), { recursive: true });
  fs.mkdirSync(freshDir, { recursive: true });
  const freshCode = fakeFresh(freshDir, fresh);
  const checkout = path.join(dir, "checkout");
  fs.mkdirSync(checkout);
  const m = fm(config, { dir, cwd: checkout, freshCode, freshDir, checkoutDir: checkout, repoFn: () => "o/n", ...over });
  return { m, dir, freshDir, freshCode, checkout };
};

test("a new conversation's cwd is the selected repo's clone, and the child spawns there with the clone's memory rule", async () => {
  const { m, dir, freshDir } = fmFresh({ argvDump: null }, {}, { ready: ["o/n"] });
  const argvDump = path.join(dir, "argv.json");
  const cwdDump = path.join(dir, "cwd.txt");
  const fake = makeFakeChild(path.join(dir, "bin2"), { argvDump, cwdDump });
  m.command = fake.command; m.launchArgs = fake.launchArgs;
  assert.equal(m.session.cwd, null, "nothing stored before the first turn");
  await m.send("hello");
  const clone = path.join(freshDir, "o", "n");
  assert.equal(m.session.cwd, clone);
  assert.equal(m.conversationCwd(), clone);
  assert.equal(fs.readFileSync(cwdDump, "utf8"), fs.realpathSync(clone), "the child runs in the clone");
  const argv = JSON.parse(fs.readFileSync(argvDump, "utf8"));
  const allow = argv[argv.indexOf("--allowedTools") + 1].split(",");
  assert.ok(allow.includes(firstMateMemoryRule(clone)), "the memory rule is the clone's slug");
  assert.ok(!allow.includes(firstMateMemoryRule(m.cwd)), "not the checkout's");
  // The settings file was rewritten for the clone's cwd.
  const settings = JSON.parse(fs.readFileSync(m.settingsPath, "utf8"));
  assert.ok(settings.permissions.allow.includes(firstMateMemoryRule(clone)));
  assert.deepEqual(settings.permissions.deny, [...FIRST_MATE_DENIED_TOOLS]);
  const saved = JSON.parse(fs.readFileSync(path.join(dir, "first-mate.json"), "utf8")).session;
  assert.equal(saved.cwd, clone, "stored with the session");
  assert.equal(m.handoffPayload().cwd, clone);
  assert.equal(m.inUse(clone), true);
  assert.equal(m.inUse(path.join(freshDir, "o", "other")), false);
  m.stop();
});

test("in All repos a new conversation runs in <freshDir> itself, with no instruction section", async () => {
  const { m, dir, freshDir } = fmFresh({}, { repoFn: () => null });
  const argvDump = path.join(dir, "argv.json");
  const fake = makeFakeChild(path.join(dir, "bin2"), { argvDump });
  m.command = fake.command; m.launchArgs = fake.launchArgs;
  await m.send("hello");
  assert.equal(m.session.cwd, freshDir);
  const argv = JSON.parse(fs.readFileSync(argvDump, "utf8"));
  const prompt = argv[argv.indexOf("--append-system-prompt") + 1];
  assert.ok(prompt.startsWith(FIRST_MATE_SYSTEM_PROMPT), "the system prompt is unchanged and first");
  assert.match(prompt, /holds a fresh git clone of every target repo/);
  assert.doesNotMatch(prompt, /\n## /, "no instruction section at the root");
  m.stop();
});

test("startNewConversation moves to the then-selected repo's clone; resume, pop and a legacy conversation use their stored cwd", async () => {
  let selected = "o/n";
  const { m, freshDir, checkout } = fmFresh({}, { repoFn: () => selected }, { ready: ["o/n", "x/y"] });
  await m.send("first");
  const cloneN = path.join(freshDir, "o", "n");
  const cloneY = path.join(freshDir, "x", "y");
  assert.equal(m.session.cwd, cloneN);
  selected = "x/y";
  m.startNewConversation();
  assert.equal(m.session.cwd, cloneY);
  assert.equal(m.session.conversations[0].cwd, cloneN, "the archived one keeps its cwd");
  await m.send("second");
  // Pop: back to the first, in its cwd.
  assert.equal(m.popConversation().ok, true);
  assert.equal(m.session.cwd, cloneN);
  assert.equal(m.session.conversations[0].cwd, cloneY);
  // Resume by id: the second again.
  const second = m.session.conversations[0];
  assert.equal(m.resumeConversation(second.id).ok, true);
  assert.equal(m.session.cwd, cloneY);
  // A conversation archived before the build (cwd null) runs in the legacy cwd.
  m.session.conversations.push({ ...past(42, "sess-legacy", ["old q", "old a"]), cwd: null });
  assert.equal(m.resumeConversation(42).ok, true);
  assert.equal(m.session.cwd, null);
  assert.equal(m.conversationCwd(), checkout);
  assert.equal(m.inUse(cloneY), true, "archived conversations count as in use");
  assert.equal(m.inUse(cloneN), true);
  m.stop();
});

test("restoreSavedChat takes the cwd main found the session in, else the selected repo's clone", async () => {
  const { m, freshDir, checkout } = fmFresh({}, {}, { ready: ["o/n"] });
  const chat = { id: "11111111-1111-4111-8111-111111111111", title: "t", claudeSessionId: "sess-saved", messages: [{ role: "user", text: "q", at: 1 }] };
  assert.equal(m.restoreSavedChat(chat, { resumable: true, cwd: checkout }).ok, true);
  assert.equal(m.session.cwd, checkout);
  assert.equal(m.restoreSavedChat({ ...chat, id: "22222222-2222-4222-8222-222222222222" }, { resumable: false }).ok, true);
  assert.equal(m.session.cwd, path.join(freshDir, "o", "n"));
  m.stop();
});

test("the prompt carries the fresh-code paragraph, with and without the checkout, and one section per instruction file headed by path and commit", async () => {
  const { m, dir, freshDir, freshCode } = fmFresh({}, {}, { ready: ["o/n"] });
  const clone = path.join(freshDir, "o", "n");
  freshCode.instructions[clone] = [
    { repo: "o/n", file: "CLAUDE.md", path: path.join(clone, "CLAUDE.md"), commit: "a".repeat(40), blob: "b1", text: "# Rules\nAlways say ahoy." },
    { repo: "o/n", file: "AGENTS.md", path: path.join(clone, "AGENTS.md"), commit: "a".repeat(40), blob: "b2", text: "Be brief." },
  ];
  const argvDump = path.join(dir, "argv.json");
  const fake = makeFakeChild(path.join(dir, "bin2"), { argvDump });
  m.command = fake.command; m.launchArgs = fake.launchArgs;
  await m.send("hello");
  const argv = JSON.parse(fs.readFileSync(argvDump, "utf8"));
  const prompt = argv[argv.indexOf("--append-system-prompt") + 1];
  assert.ok(prompt.startsWith(`${FIRST_MATE_SYSTEM_PROMPT}\n\n`));
  const para = freshCodeParagraph({ cwd: clone, freshDir, checkoutDir: m.checkoutDir });
  assert.ok(prompt.includes(para));
  assert.match(para, /fresh git clone of o\/n at its default branch's head, detached/);
  assert.ok(para.includes(`${freshDir}/<owner>/<name>/`));
  assert.ok(para.includes(path.join(freshDir, "fresh.json")));
  assert.ok(para.includes(`is at ${m.checkoutDir}`), "the user's checkout is named");
  assert.match(para, /read_ticket_pr/);
  assert.match(para, /git show <commit>:<path>/);
  assert.ok(prompt.endsWith(`\n\n## o/n/CLAUDE.md @ ${"a".repeat(40)}\n\n# Rules\nAlways say ahoy.\n\n## o/n/AGENTS.md @ ${"a".repeat(40)}\n\nBe brief.`));
  // Packaged with no AGENT_ARMY_REPO_DIR: no checkout sentence.
  const none = freshCodeParagraph({ cwd: clone, freshDir, checkoutDir: null });
  assert.doesNotMatch(none, /own checkout/);
  assert.equal(freshRepoOf(clone, freshDir), "o/n");
  assert.equal(freshRepoOf(freshDir, freshDir), null);
  assert.equal(instructionSection({ repo: "o/n", file: "CLAUDE.md", commit: "abc", text: "x" }), "## o/n/CLAUDE.md @ abc\n\nx");
  // Without a freshDir (every caller before #960) the prompt is exactly the system prompt.
  const plain = buildFirstMateArgv({ cwd: "/r" });
  assert.equal(plain[plain.indexOf("--append-system-prompt") + 1], FIRST_MATE_SYSTEM_PROMPT);
  m.stop();
});

test("CHAT_READ_TOOLS allows the diagnostics server's read-only tools and none of its write tools", () => {
  for (const tool of [
    "mcp__frakworks-engine-diagnostics__read_friction_notes",
    "mcp__frakworks-engine-diagnostics__list_jobs",
    "mcp__frakworks-engine-diagnostics__read_job",
    "mcp__frakworks-engine-diagnostics__read_job_runs",
    "mcp__frakworks-engine-diagnostics__read_job_attempts",
    "mcp__frakworks-engine-diagnostics__read_tier_accounts",
    "mcp__frakworks-engine-diagnostics__read_review_predictions",
    "mcp__frakworks-engine-diagnostics__read_public_url",
  ]) {
    assert.ok(CHAT_READ_TOOLS.includes(tool), `${tool} should be in CHAT_READ_TOOLS`);
  }
  for (const tool of [
    "mcp__frakworks-engine-diagnostics__register_job",
    "mcp__frakworks-engine-diagnostics__run_job",
    "mcp__frakworks-engine-diagnostics__delete_job",
    "mcp__frakworks-engine-diagnostics__sync_friction_fixes",
  ]) {
    assert.ok(!CHAT_READ_TOOLS.includes(tool), `${tool} should not be in CHAT_READ_TOOLS`);
  }
});

test("CHAT_READ_TOOLS, FIRST_MATE_FILING_TOOLS, FIRST_MATE_DENIED_TOOLS and --setting-sources \"\" are byte-for-byte unchanged", () => {
  assert.deepEqual([...CHAT_READ_TOOLS], [
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
  assert.deepEqual([...FIRST_MATE_FILING_TOOLS], [
    "mcp__frakworks__submit_ask", "mcp__frakworks__read_transcript",
    "mcp__frakworks__read_needs", "mcp__frakworks__read_ticket_pr", "mcp__frakworks__read_ticket_runs", "mcp__frakworks__approve",
    "mcp__frakworks__reauthorize", "mcp__frakworks__note_friction",
    "mcp__frakworks__read_repos", "mcp__frakworks__add_repo", "mcp__frakworks__edit_repo", "mcp__frakworks__remove_repo",
      "mcp__frakworks__read_access", "mcp__frakworks__add_access", "mcp__frakworks__remove_access",
    "mcp__frakworks__cancel_intake", "mcp__frakworks__amend_intake", "mcp__frakworks__amend_ticket",
    "mcp__frakworks__close_ticket", "mcp__frakworks__read_ci_runs", "mcp__frakworks__comment_ticket", "mcp__frakworks__search_tickets",
      "mcp__frakworks__read_ticket", "mcp__frakworks__list_private_docs", "mcp__frakworks__read_private_doc",
      "mcp__frakworks__read_repo_mcp_servers", "mcp__frakworks__add_repo_mcp_server", "mcp__frakworks__edit_repo_mcp_server",
      "mcp__frakworks__remove_repo_mcp_server", "mcp__frakworks__test_repo_mcp_server",
    "mcp__frakworks-engine-diagnostics__act_on_incident", "mcp__frakworks-engine-diagnostics__read_doctor_history",
  ]);
  assert.deepEqual([...FIRST_MATE_DENIED_TOOLS], [
    "MultiEdit", "NotebookEdit", "Task",
    "Bash(git push:*)", "Bash(git commit:*)", "Bash(gh pr create:*)",
    "Bash(gh pr merge:*)", "Bash(gh issue create:*)", "Bash(gh issue comment:*)",
    "Bash(gh api:*)", "Bash(rm:*)",
  ]);
  const argv = buildFirstMateArgv({ cwd: "/fresh/o/n", freshDir: "/fresh", checkoutDir: "/c", instructions: [{ file: "CLAUDE.md", text: "x" }] });
  const at = argv.indexOf("--setting-sources");
  assert.equal(argv[at + 1], "");
  assert.equal(argv[argv.indexOf("--allowedTools") + 1], firstMateAllowedTools("/fresh/o/n").join(","));
  assert.ok(argv.includes("--strict-mcp-config"));
  // Nothing before --append-system-prompt changed shape: the same flags, in the same order, as without fresh code.
  const before = buildFirstMateArgv({ cwd: "/fresh/o/n" });
  const flags = (a) => a.slice(0, a.indexOf("--append-system-prompt"));
  assert.deepEqual(flags(argv), flags(before));
});

test("a changed CLAUDE.md blob between turns restarts the child once with --resume and a notice; an unchanged one does not", async () => {
  const { m, dir, freshDir, freshCode } = fmFresh({}, {}, { ready: ["o/n"] });
  const clone = path.join(freshDir, "o", "n");
  const file = { repo: "o/n", file: "CLAUDE.md", path: path.join(clone, "CLAUDE.md"), commit: "a".repeat(40), blob: "b1", text: "v1" };
  freshCode.instructions[clone] = [file];
  const argvLog = path.join(dir, "argv.log");
  const fake = makeFakeChild(path.join(dir, "bin2"), { argvLog, sessionId: "sess-fresh" });
  m.command = fake.command; m.launchArgs = fake.launchArgs;
  await m.send("one");
  const child1 = m.rt.child;
  await m.send("two");
  assert.equal(m.rt.child, child1, "unchanged: the same child");
  assert.equal(m.rt.tierNotice, null);
  freshCode.instructions[clone] = [{ ...file, blob: "b2", commit: "b".repeat(40), text: "v2" }];
  await m.send("three");
  assert.notEqual(m.rt.child, child1, "changed: restarted");
  assert.equal(m.rt.tierNotice, `CLAUDE.md of o/n changed at ${"b".repeat(40)}: the conversation was re-read on the new text.`);
  const starts = fs.readFileSync(argvLog, "utf8").trim().split("\n").map((l) => JSON.parse(l));
  assert.equal(starts.length, 2);
  assert.equal(starts[0].includes("--resume"), false);
  assert.equal(starts[1][starts[1].indexOf("--resume") + 1], "sess-fresh");
  assert.ok(starts[1][starts[1].indexOf("--append-system-prompt") + 1].endsWith("\n\nv2"), "the new text");
  const child2 = m.rt.child;
  await m.send("four");
  assert.equal(m.rt.child, child2, "once");
  assert.equal(m.rt.tierNotice, null);
  m.stop();
});

test("a missing clone makes the first turn wait with a notice, then run once the clone is ready; a failed clone refuses the turn", async () => {
  const { m, freshDir, freshCode } = fmFresh({}, {}, { ready: [], cloneDelayMs: 60 });
  const notices = [];
  m.on("update", (s) => { if (s.notice) notices.push([s.notice, s.busy]); });
  const p = m.send("hello");
  await settle(10);
  assert.deepEqual(notices[0], ["Waiting for the fresh clone of o/n…", true]);
  assert.deepEqual(freshCode.calls, [["ensureClone", "o/n"]]);
  assert.equal(m.rt.child, null, "no child before the clone");
  const second = await m.send("too soon");
  assert.equal(second.busy, true, "held busy meanwhile");
  const r = await p;
  assert.equal(r.ok, true);
  assert.match(r.text, /echo: [\s\S]*hello$/);
  assert.equal(m.session.cwd, path.join(freshDir, "o", "n"));
  assert.equal(m.rt.notice, null);
  // After that, never: the second turn starts no clone.
  await m.send("again");
  assert.equal(freshCode.calls.length, 1);
  m.stop();

  const bad = fmFresh({}, {}, { ready: [], cloneError: "clone: 502 Bad Gateway" });
  const refused = await bad.m.send("hello");
  assert.deepEqual(refused, { ok: false, refused: true, error: "The fresh clone of o/n is not available: clone: 502 Bad Gateway" });
  assert.equal(bad.m.rt.busy, false);
  assert.equal(bad.m.session.history.length, 0, "nothing was written");
  bad.m.stop();
});

test("a cancel while the first turn waits for the clone wins", async () => {
  const { m } = fmFresh({}, {}, { ready: [], cloneDelayMs: 60 });
  const p = m.send("hello");
  await settle(10);
  m.cancel();
  const r = await p;
  assert.equal(r.interrupted, true);
  assert.equal(m.rt.child, null);
  m.stop();
});

test("itermPlan and openInIterm use the conversation's cwd", async () => {
  const seen = [];
  const { m, freshDir } = fmFresh({ sessionId: "sess-plan" }, {
    sessionFileExistsFn: (cwd, id) => { seen.push(cwd); return id === "sess-plan"; },
    iterm: { available: true, app: "iTerm2" },
    writeHandoffFn: (d, plan) => { seen.push(plan.cwd); return { launcherPath: "/l", seedPath: null }; },
    openInItermFn: async () => ({ ok: true }),
  }, { ready: ["o/n"] });
  await m.send("hello");
  const clone = path.join(freshDir, "o", "n");
  assert.deepEqual(m.itermPlan(), { mode: "resume", resumeId: "sess-plan" });
  assert.equal((await m.openInIterm()).ok, true);
  assert.deepEqual([...new Set(seen)], [clone]);
  m.stop();
});
