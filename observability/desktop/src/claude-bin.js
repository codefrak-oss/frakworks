// Which `claude` First mate spawns.
//
// A packaged build launched from Finder or the Dock inherits launchd's PATH
// (`/usr/bin:/bin:/usr/sbin:/sbin`), not the login shell's, so the `claude`
// the user installed to `~/.local/bin` is invisible and `spawn("claude")`
// fails with ENOENT before the child ever starts: First mate answered every
// turn with "exited before answering (spawn claude ENOENT)". Under `npm start`
// from a terminal the shell's PATH is inherited and it just works, which is
// why the bug only showed up with the feed-delivered app.
//
// Resolution, first hit wins:
//   1. AGENT_ARMY_CLAUDE_BIN, verbatim — the operator's word.
//   2. An executable `claude` on the process's own PATH.
//   3. An executable `claude` on the login shell's PATH (`$SHELL -ilc 'printf $PATH'`,
//      one interactive login shell, once at startup). That PATH is also merged
//      into process.env.PATH so the child's own `gh`, `git` and friends resolve.
//   4. The usual install locations: ~/.local/bin, ~/.claude/local, Homebrew,
//      /usr/local/bin.
//   5. `claude` — and the ENOENT that follows names the PATH it searched
//      (`describeSpawnError`), so the next reader does not rediscover this file.
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const NAME = "claude";

function isExecutable(file, existsFn) {
  try { return Boolean(existsFn(file)); } catch (e) { return false; }
}

function defaultExists(file) {
  fs.accessSync(file, fs.constants.X_OK);
  return fs.statSync(file).isFile();
}

function findOnPath(pathVar, existsFn, name = NAME) {
  for (const dir of String(pathVar || "").split(path.delimiter)) {
    if (!dir) continue;
    const candidate = path.join(dir, name);
    if (isExecutable(candidate, existsFn)) return candidate;
  }
  return null;
}

// The login shell's PATH, or null when the shell cannot be asked (no $SHELL,
// a shell that errors, a two-second hang). Never throws: a bad shell rc must
// not take the app down at startup.
function loginShellPath({ shell, execFn = execFileSync } = {}) {
  if (!shell) return null;
  try {
    const out = execFn(shell, ["-ilc", "printf '%s' \"$PATH\""], {
      encoding: "utf8", timeout: 2000, stdio: ["ignore", "pipe", "ignore"],
    });
    const p = String(out).trim().split("\n").pop();   // an rc that echoes gets its noise skipped
    return p && p.includes("/") ? p : null;
  } catch (e) {
    return null;
  }
}

function mergePath(base, extra) {
  const seen = new Set();
  const out = [];
  for (const dir of [...String(extra || "").split(path.delimiter), ...String(base || "").split(path.delimiter)]) {
    if (dir && !seen.has(dir)) { seen.add(dir); out.push(dir); }
  }
  return out.join(path.delimiter);
}

function fallbackDirs(home) {
  return [
    path.join(home, ".local", "bin"),
    path.join(home, ".claude", "local"),
    "/opt/homebrew/bin",
    "/usr/local/bin",
  ];
}

/**
 * Resolve the claude binary. Returns `{ command, source, searched }`:
 * `command` is what to spawn, `source` says which rule chose it ("env",
 * "path", "login-shell", "fallback", "unresolved") and `searched` is the
 * PATH the lookup covered, for the error message when nothing was found.
 * When the login shell's PATH or a fallback dir was used, `env.PATH` is
 * widened in place so the spawned child sees it too.
 */
function resolveClaudeBin({
  env = process.env, home = os.homedir(), existsFn = defaultExists, execFn = execFileSync,
  askShell = true,
} = {}) {
  if (env.AGENT_ARMY_CLAUDE_BIN) return { command: env.AGENT_ARMY_CLAUDE_BIN, source: "env", searched: env.PATH || "" };

  let hit = findOnPath(env.PATH, existsFn);
  if (hit) return { command: hit, source: "path", searched: env.PATH || "" };

  let searched = env.PATH || "";
  if (askShell) {
    const shellPath = loginShellPath({ shell: env.SHELL, execFn });
    if (shellPath) {
      searched = mergePath(env.PATH, shellPath);
      env.PATH = searched;
      hit = findOnPath(shellPath, existsFn);
      if (hit) return { command: hit, source: "login-shell", searched };
    }
  }

  for (const dir of fallbackDirs(home)) {
    const candidate = path.join(dir, NAME);
    if (isExecutable(candidate, existsFn)) {
      env.PATH = mergePath(env.PATH, dir);
      return { command: candidate, source: "fallback", searched: env.PATH };
    }
  }
  return { command: NAME, source: "unresolved", searched };
}

/** The line a spawn error should carry instead of a bare "spawn claude ENOENT". */
function describeSpawnError(error, command, env = process.env) {
  if (!error) return "";
  if (error.code !== "ENOENT") return error.message;
  return `${error.message}: no ${command} found on PATH (${env.PATH || ""}); ` +
    "install Claude Code or set AGENT_ARMY_CLAUDE_BIN to its full path";
}

module.exports = { resolveClaudeBin, describeSpawnError, findOnPath, loginShellPath, mergePath, fallbackDirs, isExecutable, defaultExists };
