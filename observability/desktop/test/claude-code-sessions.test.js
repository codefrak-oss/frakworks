// Saved Chats > Claude Code (#1708): session discovery, bounded parsing,
// bucketing and the debounced watch, src/claude-code-sessions.js.
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const cc = require("../src/claude-code-sessions");

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "cc-sessions-"));
const line = (o) => JSON.stringify(o) + "\n";
const write = (root, dir, id, lines) => {
  fs.mkdirSync(path.join(root, dir), { recursive: true });
  const f = path.join(root, dir, `${id}.jsonl`);
  fs.writeFileSync(f, lines.join(""));
  return f;
};

test("parseHead: session id, cwd, first typed user message; tool results, meta and bad lines skipped", () => {
  const h = cc.parseHead([
    "not json",
    line({ type: "user", isMeta: true, sessionId: "s1", cwd: "/w/app", timestamp: "2026-10-08T09:00:00Z", message: { content: "meta" } }),
    line({ type: "user", message: { content: [{ type: "tool_result", content: "x" }] } }),
    line({ type: "user", message: { content: "<command-name>/clear</command-name>" } }),
    line({ type: "user", message: { content: [{ type: "text", text: "  fix   the\nbug " }] } }),
    '{"type":"user","cut',
  ].join("\n"));
  assert.deepEqual(h, { sessionId: "s1", cwd: "/w/app", title: "fix the bug", firstAt: "2026-10-08T09:00:00Z" });
});

test("parseTail: the last valid timestamp, a cut first line skipped", () => {
  assert.equal(cc.parseTail('mp":"x"}\n' + line({ timestamp: "2026-10-08T10:00:00Z" }) + line({ type: "x" })), "2026-10-08T10:00:00Z");
  assert.equal(cc.parseTail("garbage"), null);
});

test("listSessions: a missing projects dir is an empty list", () => {
  assert.deepEqual(cc.listSessions({ root: path.join(tmp(), "nope") }), []);
});

test("listSessions: rows newest first, large files read only at head and tail, unchanged files cached", () => {
  const root = tmp();
  write(root, "-w-a", "a", [line({ type: "user", sessionId: "a", cwd: "/w/a", timestamp: "2026-10-01T00:00:00Z", message: { content: "old one" } })]);
  const big = [line({ type: "user", sessionId: "b", cwd: "/w/b", timestamp: "2026-10-02T00:00:00Z", message: { content: "big one" } })];
  for (let i = 0; i < 3000; i++) big.push(line({ type: "assistant", timestamp: "2026-10-02T00:00:01Z", message: { content: "x".repeat(100) } }));
  big.push(line({ type: "assistant", timestamp: "2026-10-07T12:00:00Z", message: { content: "last" } }));
  const bigFile = write(root, "-w-b", "b", big);
  assert.ok(fs.statSync(bigFile).size > cc.HEAD_BYTES + cc.TAIL_BYTES);
  let bytes = 0;
  const spy = { ...fs, readSync: (...a) => { const n = fs.readSync(...a); bytes += n; return n; } };
  const cache = new Map();
  const rows = cc.listSessions({ root, fs: spy, cache });
  assert.deepEqual(rows.map((r) => [r.id, r.project, r.title, r.lastAt]), [
    ["b", "b", "big one", "2026-10-07T12:00:00.000Z"],
    ["a", "a", "old one", "2026-10-01T00:00:00.000Z"],
  ]);
  assert.ok(bytes <= 2 * cc.HEAD_BYTES + cc.TAIL_BYTES, `read ${bytes} bytes`);
  bytes = 0;
  cc.listSessions({ root, fs: spy, cache });
  assert.equal(bytes, 0);
  fs.appendFileSync(bigFile, line({ timestamp: "2026-10-08T01:00:00Z" }));
  assert.equal(cc.listSessions({ root, fs: spy, cache })[0].lastAt, "2026-10-08T01:00:00.000Z");
});

const at = (iso, id = iso) => ({ id, lastMs: Date.parse(iso) });

test("bucket: recent 15 capped and newest first; today by local date; last month 30 days rolling", () => {
  const now = new Date(2026, 9, 8, 12, 0, 0);
  const local = (d, h) => new Date(2026, 9, d, h).toISOString();
  const rows = [];
  for (let i = 0; i < 20; i++) rows.push(at(new Date(now.getTime() - i * 3600e3 * 30).toISOString(), `r${i}`));
  const b = cc.bucket(rows.reverse(), now);
  assert.equal(b.recent15.length, 15);
  assert.equal(b.recent15[0].id, "r0");
  assert.equal(b.recent15[14].id, "r14");
  const t = cc.bucket([at(local(8, 0)), at(local(7, 23)), at(local(8, 11))], now).today.map((r) => r.id);
  assert.deepEqual(t, [local(8, 11), local(8, 0)]);
  const m = cc.bucket([at(new Date(now - 29.9 * 864e5).toISOString(), "in"), at(new Date(now - 30.1 * 864e5).toISOString(), "out")], now);
  assert.deepEqual(m.lastMonth.map((r) => r.id), ["in"]);
});

test("readTranscript and resumeCommand", () => {
  const root = tmp();
  const f = write(root, "p", "s", [
    line({ type: "user", message: { content: "hi" } }),
    line({ type: "assistant", message: { content: [{ type: "text", text: "hello" }, { type: "tool_use" }] } }),
    line({ type: "user", message: { content: [{ type: "tool_result" }] } }),
  ]);
  assert.deepEqual(cc.readTranscript(f), { rows: [{ role: "user", text: "hi" }, { role: "assistant", text: "hello" }], hidden: 0 });
  assert.equal(cc.resumeCommand({ id: "s", sessionId: "s", cwd: "/w/it's" }), "cd '/w/it'\\''s' && claude --resume 's'");
});

test("createSessionWatcher: a burst of events is one change; a missing root is polled until it appears", () => {
  const timers = [];
  const intervals = [];
  const handlers = [];
  let exists = false;
  const fakeFs = {
    watch: (dir, _o, h) => { if (!exists) throw new Error("ENOENT"); handlers.push(h); return { on() {}, close() {} }; },
    readdirSync: () => [],
  };
  let changes = 0;
  const w = cc.createSessionWatcher({ root: "/x", fs: fakeFs, recursive: true, onChange: () => changes++,
    setTimeout: (f) => { const t = { f, live: true }; timers.push(t); return t; }, clearTimeout: (t) => { t.live = false; },
    setInterval: (f) => { intervals.push(f); return 1; }, clearInterval: () => {} });
  assert.equal(handlers.length, 0);
  exists = true;
  intervals[0]();
  assert.equal(handlers.length, 1);
  handlers[0](); handlers[0](); handlers[0]();
  timers.filter((t) => t.live).forEach((t) => t.f());
  assert.equal(changes, 1);
  w.close();
});

// Continue in terminal (#1937): iTerm2 when it is here, else the copied command.
const fakeIterm = (available, opened = { ok: true }) => {
  const calls = [];
  return {
    calls,
    detectIterm: () => ({ available, why: available ? null : "iTerm2 is not installed" }),
    writeHandoff: (dir, plan) => { calls.push(["write", dir, plan]); return { launcherPath: `${dir}/l.sh`, seedPath: null }; },
    openInIterm: async (p, o) => { calls.push(["open", p, o]); return opened; },
  };
};

test("handoff: with iTerm2, a resume launcher without fork in the row's cwd, nothing copied", async () => {
  const iterm = fakeIterm(true);
  const copied = [];
  const r = await cc.handoff({ id: "s-1", sessionId: "s-1", cwd: "/w/p" }, { dir: "/u/first-mate/iterm", claude: "/bin/claude", copy: (t) => copied.push(t), iterm });
  assert.deepEqual(r, { ok: true, mode: "iterm" });
  assert.deepEqual(iterm.calls[0], ["write", "/u/first-mate/iterm", { mode: "resume", resumeId: "s-1", fork: false, claude: "/bin/claude", cwd: "/w/p" }]);
  assert.deepEqual(iterm.calls[1], ["open", "/u/first-mate/iterm/l.sh", { seedPath: null }]);
  assert.deepEqual(copied, []);
});

test("handoff: a refused open is Not opened's reason", async () => {
  const iterm = fakeIterm(true, { ok: false, error: "macOS refused automation of iTerm2." });
  const r = await cc.handoff({ id: "s", cwd: "/w" }, { dir: "/d", copy: () => assert.fail("copied"), iterm });
  assert.deepEqual(r, { ok: false, error: "macOS refused automation of iTerm2." });
});

test("handoff: without iTerm2 (or without a cwd) the command is copied, as before", async () => {
  for (const [available, row] of [[false, { id: "s", sessionId: "s", cwd: "/w" }], [true, { id: "s", sessionId: "s", cwd: null }]]) {
    const iterm = fakeIterm(available);
    const copied = [];
    const r = await cc.handoff(row, { dir: "/d", copy: (t) => copied.push(t), iterm });
    const command = cc.resumeCommand(row);
    assert.deepEqual(r, { ok: true, command, mode: "resume" });
    assert.deepEqual(copied, [command]);
    assert.deepEqual(iterm.calls, []);
  }
});

// The view's drawn states.
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const view = require("../dist/index.cjs");
const draw = (session, handoff) => renderToStaticMarkup(React.createElement(view.ClaudeCodeSessionView,
  { result: { session: { id: "s", title: "T", rows: [], ...session } }, handoff, onHandoff: () => {}, onDismissHandoff: () => {} }));

test("ClaudeCodeSessionView: the iTerm2 title, Opening…, the opened notice and Not opened", () => {
  assert.match(draw({ iterm: true }, null), new RegExp(`title="${view.CC_ITERM_TITLE}"[^>]*>Continue in terminal<`));
  assert.match(draw({ iterm: true }, { busy: true }), /disabled=""[^>]*>Opening…</);
  assert.match(draw({ iterm: true }, { ok: true, mode: "iterm" }), new RegExp(view.CC_ITERM_NOTICE.replace(".", "\\.")));
  const err = draw({ iterm: true }, { error: "macOS refused automation of iTerm2." });
  assert.match(err, /Not opened: macOS refused automation of iTerm2\./);
  assert.doesNotMatch(err, /disabled/);
});

test("ClaudeCodeSessionView: without iTerm2 it copies, and a copy fallback after a click shows the command", () => {
  assert.match(draw({ iterm: false }, null), new RegExp(`title="${view.CC_COPY_TITLE}"`));
  const copied = draw({ iterm: true }, { ok: true, mode: "resume", command: "cd '/w' && claude --resume 's'" });
  assert.match(copied, /Copied a command/);
  assert.match(copied, /claude --resume/);
});

test("ccHandoffAnswer: iterm, copy and error answers", async () => {
  assert.deepEqual(await view.ccHandoffAnswer(async () => ({ ok: true, mode: "iterm" })), { ok: true, mode: "iterm" });
  assert.deepEqual(await view.ccHandoffAnswer(async () => ({ ok: true, mode: "resume", command: "c" })), { ok: true, command: "c", mode: "resume" });
  assert.deepEqual(await view.ccHandoffAnswer(async () => ({ ok: false, error: "x" })), { error: "x" });
});

test("bucket: byDirectory holds the last 24 hours rolling, grouped by cwd, newest group and session first", () => {
  const now = new Date("2026-10-09T12:00:00Z");
  const ago = (h) => now.getTime() - h * 36e5;
  const row = (id, h, cwd, project = cwd ? path.basename(cwd) : "p") => ({ id, lastMs: ago(h), cwd, project });
  const rows = [row("a1", 5, "/w/a"), row("b1", 1, "/w/b"), row("a2", 2, "/w/a"), row("in", 23, "/w/c"), row("out", 25, "/w/d"), row("n", 3, null, "enc-dir")];
  const b = cc.bucket(rows, now);
  assert.deepEqual(b.byDirectory.map((g) => [g.cwd, g.project, g.sessions.map((s) => s.id)]),
    [["/w/b", "b", ["b1"]], ["/w/a", "a", ["a2", "a1"]], ["enc-dir", "enc-dir", ["n"]], ["/w/c", "c", ["in"]]]);
  assert.ok(!b.byDirectory.some((g) => g.sessions.some((s) => s.id === "out")));
  const local = new Date(2026, 9, 9, 12);
  const t = cc.bucket([{ id: "y", lastMs: new Date(2026, 9, 8, 20).getTime(), cwd: "/w" }], local);
  assert.deepEqual(t.today, []);
  assert.deepEqual(t.byDirectory.map((g) => g.sessions.map((s) => s.id)), [["y"]]);
});

// Saved Chats > Claude Code's By directory folder.
const explorer = require("../dist/explorer.cjs");
// Expands function components so a test can find a row's click handler.
const expand = (el) => {
  if (Array.isArray(el)) return el.map(expand);
  if (!el || typeof el !== "object") return el;
  if (typeof el.type === "function") return expand(el.type(el.props));
  const kids = el.props && el.props.children;
  return kids === undefined ? el : { ...el, props: { ...el.props, children: expand(kids) } };
};
const findAll = (el, pred, out = []) => {
  if (Array.isArray(el)) { el.forEach((e) => findAll(e, pred, out)); return out; }
  if (!el || typeof el !== "object") return out;
  if (pred(el)) out.push(el);
  if (el.props) findAll(el.props.children, pred, out);
  return out;
};

test("ClaudeCodeBranch: By directory draws its folder, one branch per directory with counts, and rows that select the session", () => {
  const s = (id, cwd) => ({ id, title: `t-${id}`, cwd, project: path.basename(cwd), lastAt: "2026-10-09T10:00:00Z" });
  const sessions = { recent15: [], today: [], lastMonth: [], byDirectory: [
    { cwd: "/w/alpha", project: "alpha", sessions: [s("a1", "/w/alpha"), s("a2", "/w/alpha")] },
    { cwd: "/w/beta", project: "beta", sessions: [s("b1", "/w/beta")] },
  ] };
  const open = { cc: true, "cc:byDirectory": true, "cc:byDirectory:/w/alpha": true, "cc:byDirectory:/w/beta": true };
  const picked = [];
  const props = { sessions, open, toggle: () => {}, sel: null, onSelect: (i) => picked.push(i) };
  const html = renderToStaticMarkup(React.createElement(explorer.ClaudeCodeBranch, props));
  assert.match(html, /Last month<\/span>.*By directory \(last 24 hours\)<\/span><span class="exCount">3</);
  assert.match(html, /title="\/w\/alpha"[^>]*>.*?alpha<\/span><span class="exCount">2</);
  assert.match(html, /title="\/w\/beta"[^>]*>.*?beta<\/span><span class="exCount">1</);
  const rows = findAll(expand(React.createElement(explorer.ClaudeCodeBranch, props)),
    (e) => e.type === "div" && typeof e.props.title === "string" && e.props.title.startsWith("t-b1"));
  rows[0].props.onClick();
  assert.deepEqual(picked, [{ kind: "claude-session", id: "b1" }]);
  const closed = renderToStaticMarkup(React.createElement(explorer.ClaudeCodeBranch, { ...props, open: { cc: true, "cc:byDirectory": true }, sessions: { ...sessions, byDirectory: [] } }));
  assert.match(closed, /By directory \(last 24 hours\)<\/span><span class="exCount">0<\/span><\/div><div class="exEmpty"[^>]*>none</);
});
