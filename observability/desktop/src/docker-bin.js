// Which `docker` the Update engine button runs (src/engine-update.js).
//
// The same problem src/claude-bin.js solves for `claude`: a packaged build
// launched from Finder or the Dock has launchd's PATH, not the login shell's,
// so the `docker` Docker Desktop, Colima or Homebrew put on the user's PATH is
// invisible to it. The same rules, first hit wins:
//   1. AGENT_ARMY_DOCKER_BIN, verbatim.
//   2. An executable `docker` on the process's own PATH.
//   3. One on the login shell's PATH (claude-bin's `loginShellPath`), which is
//      merged into process.env.PATH so `docker compose` finds its plugin and
//      credential helpers too.
//   4. The usual install locations: Homebrew, /usr/local/bin, Docker
//      Desktop's ~/.docker/bin and app bundle, ~/.colima/bin.
//   5. `docker` — and the ENOENT that follows says so.
"use strict";

const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const { findOnPath, loginShellPath, mergePath, isExecutable, defaultExists } = require("./claude-bin");

const NAME = "docker";

function dockerFallbackDirs(home) {
  return [
    "/opt/homebrew/bin",
    "/usr/local/bin",
    path.join(home, ".docker", "bin"),
    "/Applications/Docker.app/Contents/Resources/bin",
    path.join(home, ".colima", "bin"),
  ];
}

/**
 * Resolve the docker binary: `{ command, source, searched }`, the shape of
 * claude-bin's `resolveClaudeBin`; `source` is "env", "path", "login-shell",
 * "fallback" or "unresolved". Widens `env.PATH` in place the same way.
 */
function resolveDockerBin({
  env = process.env, home = os.homedir(), existsFn = defaultExists, execFn = execFileSync, askShell = true,
} = {}) {
  if (env.AGENT_ARMY_DOCKER_BIN) return { command: env.AGENT_ARMY_DOCKER_BIN, source: "env", searched: env.PATH || "" };

  let hit = findOnPath(env.PATH, existsFn, NAME);
  if (hit) return { command: hit, source: "path", searched: env.PATH || "" };

  let searched = env.PATH || "";
  if (askShell) {
    const shellPath = loginShellPath({ shell: env.SHELL, execFn });
    if (shellPath) {
      searched = mergePath(env.PATH, shellPath);
      env.PATH = searched;
      hit = findOnPath(shellPath, existsFn, NAME);
      if (hit) return { command: hit, source: "login-shell", searched };
    }
  }

  for (const dir of dockerFallbackDirs(home)) {
    const candidate = path.join(dir, NAME);
    if (isExecutable(candidate, existsFn)) {
      env.PATH = mergePath(env.PATH, dir);
      return { command: candidate, source: "fallback", searched: env.PATH };
    }
  }
  return { command: NAME, source: "unresolved", searched };
}

module.exports = { resolveDockerBin, dockerFallbackDirs };
