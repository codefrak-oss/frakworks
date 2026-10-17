// claude-code-sessions.js — the explorer's Saved Chats > Claude Code folder
// (#1708): the local Claude Code sessions on disk,
// `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl`, listed by last
// activity and bucketed into Recent 15, Today, Last month and By directory (last 24
// hours).
//
// Read only: nothing under ~/.claude is ever written. A listing stats every
// file and reads only a bounded head (cwd, session id, first user message)
// and tail (last timestamp) of a file whose size or mtime moved since the
// last listing; an unchanged file comes from the cache. A missing projects
// directory is an empty list, never an error.

const nodeFs = require("node:fs");
const path = require("node:path");
const os = require("node:os");

const HEAD_BYTES = 64 * 1024;
const TAIL_BYTES = 16 * 1024;
const TITLE_MAX = 120;
const RECENT_MAX = 15;
const LAST_MONTH_DAYS = 30;
const TRANSCRIPT_CAP = 300;
const DAY_MS = 24 * 60 * 60 * 1000;

const projectsDir = (home = os.homedir()) => path.join(home, ".claude", "projects");

const parseLine = (line) => {
  const s = line.trim();
  if (!s) return null;
  try { const o = JSON.parse(s); return o && typeof o === "object" ? o : null; } catch (e) { return null; }
};

/** A message's plain text: a string content, or its `text` parts joined; "" for tool results only. */
const textOf = (message) => {
  const c = message && message.content;
  if (typeof c === "string") return c;
  if (!Array.isArray(c)) return "";
  return c.filter((p) => p && p.type === "text" && typeof p.text === "string").map((p) => p.text).join("\n");
};

/** A user entry that a person typed: not meta, not a tool result, not a slash command's wrapper. */
const typedUserText = (o) => {
  if (!o || o.type !== "user" || o.isMeta || o.isSidechain) return "";
  const t = textOf(o.message).trim();
  if (!t || t.startsWith("<command-") || t.startsWith("<local-command") || t.startsWith("Caveat:")) return "";
  return t;
};

const oneLine = (t, max = TITLE_MAX) => {
  const s = String(t).replace(/\s+/g, " ").trim();
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
};

/**
 * What the head of a session file says: `{ sessionId, cwd, title, firstAt }`.
 * A cut last line (the head ends mid-line) and malformed lines are skipped.
 */
const parseHead = (text) => {
  const out = { sessionId: null, cwd: null, title: null, firstAt: null };
  for (const line of String(text).split("\n")) {
    const o = parseLine(line);
    if (!o) continue;
    if (!out.sessionId && typeof o.sessionId === "string") out.sessionId = o.sessionId;
    if (!out.cwd && typeof o.cwd === "string") out.cwd = o.cwd;
    if (!out.firstAt && typeof o.timestamp === "string") out.firstAt = o.timestamp;
    if (!out.title && o.type === "summary" && typeof o.summary === "string") out.title = oneLine(o.summary);
    if (!out.title) { const t = typedUserText(o); if (t) out.title = oneLine(t); }
    if (out.sessionId && out.cwd && out.title && out.firstAt) break;
  }
  return out;
};

/** The last timestamp in the tail of a session file, or null. A cut first line is skipped. */
const parseTail = (text) => {
  const lines = String(text).split("\n");
  for (let i = lines.length - 1; i >= 0; i--) {
    const o = parseLine(lines[i]);
    if (o && typeof o.timestamp === "string" && !Number.isNaN(Date.parse(o.timestamp))) return o.timestamp;
  }
  return null;
};

const readRange = (fsImpl, file, start, length) => {
  const fd = fsImpl.openSync(file, "r");
  try {
    const buf = Buffer.alloc(length);
    const n = fsImpl.readSync(fd, buf, 0, length, start);
    return buf.subarray(0, n).toString("utf8");
  } finally { fsImpl.closeSync(fd); }
};

/** One session's row from its file and stat, reading at most HEAD_BYTES + TAIL_BYTES. */
const readSessionMeta = (file, st, fsImpl = nodeFs) => {
  const size = st.size;
  const head = readRange(fsImpl, file, 0, Math.min(size, HEAD_BYTES));
  const tail = size > HEAD_BYTES ? readRange(fsImpl, file, Math.max(0, size - TAIL_BYTES), Math.min(size, TAIL_BYTES)) : head;
  const h = parseHead(head);
  const lastAt = parseTail(tail);
  const id = path.basename(file, ".jsonl");
  const lastMs = lastAt ? Date.parse(lastAt) : st.mtimeMs;
  return {
    id,
    sessionId: h.sessionId || id,
    cwd: h.cwd,
    project: h.cwd ? path.basename(h.cwd) : path.basename(path.dirname(file)),
    title: h.title || "(no message)",
    firstAt: h.firstAt,
    lastAt: new Date(Math.max(lastMs, 0)).toISOString(),
    lastMs,
    file,
    size,
  };
};

/**
 * Every session under `root`, newest activity first. `cache` (a Map of file
 * → { size, mtimeMs, row }) spares re-reading a file whose stat did not move.
 */
const listSessions = ({ root = projectsDir(), fs: fsImpl = nodeFs, cache = new Map() } = {}) => {
  let dirs;
  try { dirs = fsImpl.readdirSync(root, { withFileTypes: true }); } catch (e) { cache.clear(); return []; }
  const seen = new Set();
  const rows = [];
  for (const d of dirs) {
    if (!d.isDirectory()) continue;
    const dir = path.join(root, d.name);
    let files;
    try { files = fsImpl.readdirSync(dir); } catch (e) { continue; }
    for (const f of files) {
      if (!f.endsWith(".jsonl")) continue;
      const file = path.join(dir, f);
      let st;
      try { st = fsImpl.statSync(file); } catch (e) { continue; }
      if (!st.isFile()) continue;
      seen.add(file);
      const hit = cache.get(file);
      if (hit && hit.size === st.size && hit.mtimeMs === st.mtimeMs) { rows.push(hit.row); continue; }
      let row;
      try { row = readSessionMeta(file, st, fsImpl); } catch (e) { continue; }
      cache.set(file, { size: st.size, mtimeMs: st.mtimeMs, row });
      rows.push(row);
    }
  }
  for (const k of [...cache.keys()]) if (!seen.has(k)) cache.delete(k);
  return rows.sort((a, b) => b.lastMs - a.lastMs);
};

/**
 * `{ cwd, project, sessions }` groups of newest-first `sessions`, one per cwd
 * (a session without one groups under its project), in the order of each
 * group's newest session.
 */
const byDirectory = (sorted) => {
  const groups = new Map();
  for (const s of sorted) {
    const key = s.cwd || s.project;
    if (!groups.has(key)) groups.set(key, { cwd: key, project: s.project, sessions: [] });
    groups.get(key).sessions.push(s);
  }
  return [...groups.values()];
};

/**
 * The four folders, each newest first: Recent 15 (the 15 most recently
 * active), Today (last activity on `now`'s local calendar date), Last month
 * (last activity within the 30 days before `now`, rolling) and By directory
 * (last activity within the 24 hours before `now`, rolling, grouped by cwd).
 */
const bucket = (sessions, now = new Date()) => {
  const sorted = [...(sessions || [])].sort((a, b) => b.lastMs - a.lastMs);
  const nowMs = now.getTime();
  const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const dayEnd = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1).getTime();
  return {
    recent15: sorted.slice(0, RECENT_MAX),
    today: sorted.filter((s) => s.lastMs >= dayStart && s.lastMs < dayEnd),
    lastMonth: sorted.filter((s) => s.lastMs >= nowMs - LAST_MONTH_DAYS * DAY_MS && s.lastMs <= nowMs + DAY_MS),
    byDirectory: byDirectory(sorted.filter((s) => s.lastMs >= nowMs - DAY_MS && s.lastMs <= nowMs + DAY_MS)),
  };
};

/** A row for the renderer: no file path. */
const publicRow = ({ file, lastMs, ...rest }) => rest;

/**
 * One session's transcript for the read-only view: `{ rows: [{ role, text }],
 * hidden }`, its last TRANSCRIPT_CAP user and assistant text messages.
 */
const readTranscript = (file, fsImpl = nodeFs) => {
  const rows = [];
  for (const line of fsImpl.readFileSync(file, "utf8").split("\n")) {
    const o = parseLine(line);
    if (!o || o.isSidechain) continue;
    if (o.type === "user") { const t = typedUserText(o); if (t) rows.push({ role: "user", text: t }); }
    else if (o.type === "assistant") { const t = textOf(o.message).trim(); if (t) rows.push({ role: "assistant", text: t }); }
  }
  const hidden = Math.max(0, rows.length - TRANSCRIPT_CAP);
  return { rows: rows.slice(hidden), hidden };
};

const shellQuote = (s) => `'${String(s).replace(/'/g, "'\\''")}'`;

/** Continue in terminal's command: `cd <cwd> && claude --resume <id>`. */
const resumeCommand = (row) => {
  const resume = `claude --resume ${shellQuote(row.sessionId || row.id)}`;
  return row.cwd ? `cd ${shellQuote(row.cwd)} && ${resume}` : resume;
};

/**
 * Continue in terminal (#1937): with iTerm2 here (`detectIterm`, checked on
 * every click) and the row's cwd known, a new iTerm2 window that cds there and
 * runs `claude --resume <id>`, without `--fork-session` (src/first-mate-iterm.js;
 * the launcher under `dir` deletes itself, a failed open removes it):
 * `{ ok: true, mode: "iterm" }` or `{ ok: false, error }`. Otherwise
 * `copy(command)` with resumeCommand's text: `{ ok: true, command, mode: "resume" }`.
 */
async function handoff(row, { dir, claude = "claude", copy, iterm = require("./first-mate-iterm") } = {}) {
  const detected = iterm.detectIterm();
  if (!detected.available || !row.cwd) {
    const command = resumeCommand(row);
    copy(command);
    return { ok: true, command, mode: "resume" };
  }
  let paths;
  try {
    paths = iterm.writeHandoff(dir, { mode: "resume", resumeId: row.sessionId || row.id, fork: false, claude, cwd: row.cwd });
  } catch (e) {
    return { ok: false, error: `could not write the launcher: ${e.message}` };
  }
  const r = await iterm.openInIterm(paths.launcherPath, { seedPath: paths.seedPath });
  return r && r.ok ? { ok: true, mode: "iterm" } : { ok: false, error: (r && r.error) || "iTerm2 did not open the window" };
}

/**
 * Watches `root` and calls `onChange()` once per burst of events, `debounceMs`
 * after the last. Recursive where the platform has it (macOS, Windows), else a
 * watch on the root and on each project dir, re-armed as dirs appear. A
 * missing root is polled every `pollMs` until it exists. `close()` stops it.
 */
const createSessionWatcher = ({ root = projectsDir(), onChange, fs: fsImpl = nodeFs, debounceMs = 300, pollMs = 5000,
  recursive = process.platform === "darwin" || process.platform === "win32",
  setTimeout: setT = setTimeout, clearTimeout: clearT = clearTimeout, setInterval: setI = setInterval, clearInterval: clearI = clearInterval } = {}) => {
  const watchers = new Map();
  let timer = null;
  let poll = null;
  let closed = false;
  const fire = () => {
    if (closed) return;
    if (timer) clearT(timer);
    timer = setT(() => { timer = null; if (!closed) { if (!recursive) armDirs(); onChange(); } }, debounceMs);
  };
  const watch = (dir, opts) => {
    if (watchers.has(dir)) return true;
    try {
      const w = fsImpl.watch(dir, opts, () => fire());
      if (w && w.on) w.on("error", () => { try { w.close(); } catch (e) { /* gone */ } watchers.delete(dir); if (dir === root) waitForRoot(); fire(); });
      watchers.set(dir, w);
      return true;
    } catch (e) { return false; }
  };
  const armDirs = () => {
    let dirs = [];
    try { dirs = fsImpl.readdirSync(root, { withFileTypes: true }); } catch (e) { return; }
    for (const d of dirs) if (d.isDirectory()) watch(path.join(root, d.name), {});
  };
  const arm = () => {
    if (!watch(root, recursive ? { recursive: true } : {})) return false;
    if (!recursive) armDirs();
    return true;
  };
  const waitForRoot = () => {
    if (poll || closed) return;
    poll = setI(() => { if (arm()) { clearI(poll); poll = null; fire(); } }, pollMs);
  };
  if (!arm()) waitForRoot();
  return {
    close() {
      closed = true;
      if (timer) clearT(timer);
      if (poll) clearI(poll);
      for (const w of watchers.values()) { try { w.close(); } catch (e) { /* gone */ } }
      watchers.clear();
    },
  };
};

module.exports = {
  HEAD_BYTES, TAIL_BYTES, RECENT_MAX, LAST_MONTH_DAYS,
  projectsDir, parseHead, parseTail, readSessionMeta, listSessions, bucket, publicRow,
  readTranscript, resumeCommand, handoff, createSessionWatcher,
};
