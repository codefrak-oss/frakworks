// engine-update-progress.js — what the Update engine overlay shows (#1053):
// the update's five steps, each ticked by the one event that proves it, and
// the last raw lines of the source that step is following. It watches the
// update; it never runs it (src/engine-update.js's runner does, unchanged).
//
// The steps and their signals, each read as it arrives, never on a timer:
//   pull       the runner's log (<userData>/engine-update.log), followed with
//              fs.watch from the offset last read; ticked when the status
//              file's `done` lists `pull` (or the log's `== up:` header shows
//              the runner went on). A failed pull fails it with the status
//              file's `detail`, and the running engine was left as it is.
//   replace    `docker events` for containers of the engine's compose project
//              and service, a stream from `--since` the update's start (a
//              relaunch replays what it missed); ticked on a `start` event,
//              which names the new container. A failed `up` fails it.
//   starting   `docker logs -f <new container>`; the Flyway lines are kept as
//              they arrive, ticked on Spring's "Started … in N seconds" line.
//   answering  GET /version and the running container's digest (`verify`,
//              engine-update.js's own reads), asked once on the "Started"
//              line, and the update's own result (its /version wait and
//              check); ticked when the commit is the available build's and
//              the digest is the one available before the update.
//   runs       GET /metrics, asked once after `answering`: the live runs
//              before against after.
// A source that never sends its event leaves its step pending: nothing is
// ticked from elapsed time.
//
// `createUpdateProgress({ dir, docker, project, since, before, spawn, watch,
// verify, metrics, onChange })` follows one update; `result(r)` hands it the
// update's `{ ok, steps, build }`; `close()` stops every source. main.js
// pushes `state()` to the window as `engine:update-progress` on every
// `onChange`. The view file (VIEW_FILE) keeps `before` across a relaunch.
"use strict";

const { STATUS_FILE, LOG_FILE, buildLabel } = require("./engine-update");

const VIEW_FILE = "engine-update-view.json";
const SERVICE = "engine";
const TAIL = 6;
const NOTES = 12;

const STEPS = [
  { id: "pull", label: "Pulling the image", source: "engine-update.log" },
  { id: "replace", label: "Replacing the container", source: "docker events" },
  { id: "starting", label: "Engine starting", source: "docker logs -f" },
  { id: "answering", label: "Engine answering", source: "GET /version" },
  { id: "runs", label: "Live runs followed again", source: "GET /metrics" },
];
// The runner's and the updater's step names, as the overlay's steps.
const RUNNER_STEP = { pull: "pull", up: "replace", "wait for engine": "answering" };

const EVENT_ACTIONS = new Set(["create", "kill", "die", "destroy", "rename", "start", "stop"]);
const STARTED = /\bStarted \S+ in [\d.]+ seconds?\b/;
const FLYWAY = /flyway|Migrating schema|Successfully applied|Current version of schema|Schema .* is up to date/i;
/** A Flyway line's message, without Spring's timestamp, level, thread and logger: what follows the first " : ". */
const flywayNote = (line) => { const m = / : (.*)$/.exec(line); return (m ? m[1] : line).trim(); };

/** One `docker events --format '{{json .}}'` line as `{ action, id, name }`, or null for one the overlay does not follow. */
function parseDockerEvent(line) {
  let e;
  try { e = JSON.parse(line); } catch (err) { return null; }
  if (!e || (e.Type && e.Type !== "container")) return null;
  const action = String(e.Action || e.status || "").split(":")[0].trim();
  if (!EVENT_ACTIONS.has(action)) return null;
  const id = String(e.id || (e.Actor && e.Actor.ID) || "");
  const name = (e.Actor && e.Actor.Attributes && e.Actor.Attributes.name) || "";
  return { action, id, name };
}

/** The `docker events` arguments: the engine's compose project and service, from `since` (ms). */
function eventArgs(project, since) {
  const a = ["events", "--format", "{{json .}}", "--filter", "type=container", "--filter", `label=com.docker.compose.service=${SERVICE}`];
  if (project) a.push("--filter", `label=com.docker.compose.project=${project}`);
  if (since) a.push("--since", String(Math.floor(since / 1000) - 1));
  return a;
}

/** Whether `/version`'s commit and the container's digest are the build that was available before the update. */
function isAvailableBuild(seen, available) {
  if (!seen || !available || !available.digest) return false;
  if (seen.digest !== available.digest) return false;
  return !available.commit || seen.commit === available.commit;
}

/** "3 live runs before, 3 after"; an unknown count is "?". */
function liveRunsText(before, after) {
  const n = (x) => (x == null ? "?" : String(x));
  return `${n(before)} live run${before === 1 ? "" : "s"} before, ${n(after)} after`;
}

/** A line splitter for a stream: `push(chunk)` calls `onLine` once per complete line. */
function lines(onLine) {
  let buf = "";
  return (chunk) => {
    buf += String(chunk);
    let i;
    while ((i = buf.search(/\r?\n|\r/)) >= 0) {
      const line = buf.slice(0, i);
      buf = buf.slice(i + (buf[i] === "\r" && buf[i + 1] === "\n" ? 2 : 1));
      if (line.trim()) onLine(line);
    }
  };
}

function createUpdateProgress({
  dir, docker = "docker", project = null, since = Date.now(), before = {}, resumed = false,
  spawn = (...a) => require("node:child_process").spawn(...a),
  watch = (d, cb) => require("node:fs").watch(d, cb),
  verify = async () => ({ error: "no verify" }), metrics = async () => ({ error: "no metrics" }),
  onChange = () => {}, log = () => {},
}) {
  const fs = require("node:fs");
  const path = require("node:path");
  const statusFile = path.join(dir, STATUS_FILE);
  const logFile = path.join(dir, LOG_FILE);
  const steps = STEPS.map((s) => ({ ...s, state: "pending", detail: "", tail: [], notes: [] }));
  const step = (id) => steps.find((s) => s.id === id);
  const p = { startedAt: since, resumed, before, ended: null, newContainer: null };
  let closed = false;
  const children = [];
  let watcher = null;

  const changed = () => { if (!closed) onChange(state()); };
  const push = (id, line) => {
    const s = step(id);
    s.tail.push(line);
    if (s.tail.length > TAIL) s.tail.splice(0, s.tail.length - TAIL);
  };
  const running = (id) => {
    const s = step(id);
    if (s.state === "pending") s.state = "running";
  };
  const end = (ok) => {
    if (p.ended) return;
    if (ok) {
      p.ended = { ok: true, text: `engine updated to ${buildLabel(p.build)}` };
    } else {
      const f = steps.find((s) => s.state === "failed");
      p.ended = { ok: false, text: `${f ? f.label : "The update"} failed`, detail: f ? f.detail : "",
        leftAsIs: !!(f && f.id === "pull") };
    }
    stop();
  };
  const done = (id, detail) => {
    const s = step(id);
    if (s.state === "done" || s.state === "failed" || p.ended) return;
    s.state = "done";
    if (detail != null) s.detail = detail;
    const i = steps.indexOf(s);
    if (steps[i + 1] && steps[i + 1].state === "pending") running(steps[i + 1].id);
    if (id === "starting") ask();
    if (id === "answering") { running("runs"); readRuns(); }
    if (id === "runs") end(true);
  };
  const fail = (id, detail) => {
    const s = step(id);
    if (p.ended || s.state === "done") return;
    s.state = "failed";
    s.detail = detail || "";
    end(false);
  };

  // --- pull: the runner's log and status file -------------------------------
  let offset = 0;
  let logStep = null;
  const onLogLine = (line) => {
    const h = /^== (\w[\w ]*?):/.exec(line);
    if (h) { logStep = h[1]; if (logStep === "up") done("pull"); return; }
    if (/^== /.test(line)) return;
    if (logStep === "pull" || logStep == null) push("pull", line);
  };
  const splitLog = lines(onLogLine);
  const readLog = () => {
    let buf;
    try {
      const size = fs.statSync(logFile).size;
      if (size < offset) offset = 0;
      if (size === offset) return false;
      const fd = fs.openSync(logFile, "r");
      try {
        buf = Buffer.alloc(size - offset);
        fs.readSync(fd, buf, 0, buf.length, offset);
      } finally { fs.closeSync(fd); }
      offset = size;
    } catch (e) { return false; }
    splitLog(buf.toString("utf8"));
    return true;
  };
  const readStatus = () => {
    let s;
    try { s = JSON.parse(fs.readFileSync(statusFile, "utf8")); } catch (e) { return false; }
    if (!s) return false;
    if (s.step === "pull" && s.code == null) running("pull");
    if ((s.done || []).includes("pull")) done("pull");
    if (s.code != null && s.code !== 0) {
      const id = RUNNER_STEP[s.failedStep || s.step] || current();
      fail(id, s.detail || `exit ${s.code}`);
    }
    return true;
  };
  const current = () => (steps.find((s) => s.state === "running") || steps.find((s) => s.state === "pending") || steps[0]).id;
  const onFile = (name) => {
    if (closed) return;
    let moved = false;
    if (!name || String(name) === LOG_FILE) moved = readLog() || moved;
    if (!name || String(name) === STATUS_FILE) moved = readStatus() || moved;
    if (moved) changed();
  };

  // --- replace: docker events -------------------------------------------------
  const follow = (args, onLine, what) => {
    let ch;
    try { ch = spawn(docker, args, { stdio: ["ignore", "pipe", "pipe"], windowsHide: true }); } catch (e) {
      log(`engine update progress: ${what}: ${(e && e.message) || e}`);
      return null;
    }
    children.push(ch);
    const split = lines(onLine);
    if (ch.stdout) ch.stdout.on("data", split);
    if (ch.stderr) ch.stderr.on("data", lines(onLine));
    ch.on("error", (e) => log(`engine update progress: ${what}: ${(e && e.message) || e}`));
    return ch;
  };
  const onEvent = (line) => {
    if (closed) return;
    const e = parseDockerEvent(line);
    if (!e) return;
    push("replace", `${e.action} ${e.name || "?"} ${e.id.slice(0, 12)}`);
    if (step("replace").state === "pending") running("replace");
    if (e.action === "start" && !p.newContainer) {
      p.newContainer = e.id;
      done("replace", `new container ${e.name || e.id.slice(0, 12)}`);
      running("starting");
      follow(["logs", "-f", e.id], onContainerLine, `docker logs -f ${e.id.slice(0, 12)}`);
    }
    changed();
  };

  // --- starting: the new container's log ---------------------------------------
  const onContainerLine = (line) => {
    if (closed) return;
    push("starting", line);
    const s = step("starting");
    if (FLYWAY.test(line)) {
      s.notes.push(flywayNote(line));
      if (s.notes.length > NOTES) s.notes.splice(0, s.notes.length - NOTES);
    }
    if (STARTED.test(line)) done("starting", (STARTED.exec(line) || [""])[0]);
    changed();
  };

  // --- answering: /version and the digest ---------------------------------------
  const judge = (seen, source) => {
    if (closed || p.ended || step("answering").state === "done") return;
    const a = p.before.available || {};
    push("answering", `${source}: ${seen.commit ? `commit ${String(seen.commit).slice(0, 12)}` : "commit ?"} ${seen.digest ? seen.digest.slice(0, 19) : "digest ?"}`);
    if (isAvailableBuild(seen, a)) {
      p.build = { commit: seen.commit, digest: seen.digest };
      done("answering", `${buildLabel(p.build)} on ${seen.digest.slice(0, 19)}`);
    } else if (seen.digest && a.digest && seen.digest !== a.digest) {
      fail("answering", `the engine runs ${seen.digest}, not ${a.digest} that was available before the update`);
    } else if (seen.commit && a.commit && seen.digest && seen.commit !== a.commit) {
      fail("answering", `the engine answers commit ${seen.commit}, not ${a.commit}`);
    }
  };
  const ask = () => {
    running("answering");
    Promise.resolve(verify()).then((r) => {
      if (r && r.error && !r.digest && !r.commit) { if (!closed) { push("answering", `GET /version: ${r.error}`); changed(); } return; }
      judge(r || {}, "GET /version");
      changed();
    }, (e) => { if (!closed) { push("answering", `GET /version: ${(e && e.message) || e}`); changed(); } });
  };

  // --- runs: /metrics -------------------------------------------------------------
  const readRuns = () => {
    Promise.resolve(metrics()).then((r) => {
      if (closed) return;
      if (!r || r.error) fail("runs", `GET /metrics: ${(r && r.error) || "no answer"}`);
      else { push("runs", `GET /metrics: ${r.value} smith_run_active`); done("runs", liveRunsText(p.before.liveRuns, r.value)); }
      changed();
    }, (e) => { fail("runs", `GET /metrics: ${(e && e.message) || e}`); changed(); });
  };

  /** The update's own end, `{ ok, steps, build }` (update()'s or a resumed one's onResult). */
  const result = (r) => {
    if (closed || p.ended || !r) return;
    p.result = { ok: !!r.ok };
    if (r.ok) {
      const b = r.build || {};
      judge(b, "the update's check");
      // The update's wait for /version is over: an answer that could not be judged is the step's failure, not a wait.
      if (!p.ended && step("answering").state !== "done") {
        fail("answering", `the update ended, but its check read commit ${b.commit || "?"} digest ${b.digest || "?"}, not the available ${buildLabel(p.before.available)} (${(p.before.available && p.before.available.digest) || "digest ?"})`);
      }
    } else {
      const f = (r.steps || []).find((s) => !s.ok);
      const id = (f && RUNNER_STEP[f.step]) || current();
      fail(id, (f && f.detail) || "the update failed");
    }
    changed();
  };

  const stop = () => {
    if (watcher) { try { watcher.close(); } catch (e) { /* closed */ } watcher = null; }
    for (const ch of children.splice(0)) { try { ch.kill(); } catch (e) { /* gone */ } }
  };
  const close = () => { stop(); closed = true; };

  const state = () => ({
    startedAt: p.startedAt, resumed: p.resumed, before: p.before,
    inFlight: !p.ended, ended: p.ended, newContainer: p.newContainer,
    steps: steps.map((s) => ({ ...s, tail: [...s.tail], notes: [...s.notes] })),
  });

  // Attach every source, then read what is already there (a relaunch's resume).
  running("pull");
  try { watcher = watch(dir, (_ev, name) => onFile(name)); } catch (e) { log(`engine update progress: cannot watch ${dir}: ${(e && e.message) || e}`); }
  follow(eventArgs(project, since), onEvent, "docker events");
  readLog();
  readStatus();

  return { state, result, close, onFile };
}

/** The view file: `before` and the update's start, so a relaunch shows what the click saw. */
function writeView(dir, view) {
  const fs = require("node:fs");
  const path = require("node:path");
  try { fs.writeFileSync(path.join(dir, VIEW_FILE), JSON.stringify(view)); } catch (e) { /* the overlay still runs; a relaunch shows "?" */ }
}
function readView(dir) {
  const fs = require("node:fs");
  const path = require("node:path");
  try { return JSON.parse(fs.readFileSync(path.join(dir, VIEW_FILE), "utf8")); } catch (e) { return null; }
}
function clearView(dir) {
  const fs = require("node:fs");
  const path = require("node:path");
  try { fs.unlinkSync(path.join(dir, VIEW_FILE)); } catch (e) { /* gone already */ }
}

/**
 * The quit prompt: `confirm(what, win)` resolves true at once when no update is in
 * flight, else asks `ask(dialog options, win)` (Electron's showMessageBox) and
 * resolves true only on Quit anyway. `what` is "quit", "close" or "restart".
 *
 * It also holds whether the app is on its way out (`quitting()`), so a quit the
 * user already answered is not asked again: `beforeQuit(e, win, quit)` is
 * before-quit's gate, and `restart(win, act)` asks once, then runs `act` with the
 * quit already allowed (quitAndInstall's quit passes before-quit unasked), and
 * clears it again when `act` did not quit (`{ ok: false }`, nothing to install).
 */
function createQuitGuard({ inFlight, ask }) {
  let quitting = false;
  const text = (what) => ({
    type: "warning", buttons: ["Quit anyway", "Stay"], defaultId: 1, cancelId: 1,
    message: "The engine update is still running",
    detail: `It continues without the app: ${what === "restart" ? "relaunching" : what === "close" ? "closing the window" : "quitting"} leaves the update going, and the next launch picks it up again.`,
  });
  const guard = {
    needed: () => !!inFlight(),
    text,
    confirm: async (what = "quit", ctx = null) => {
      if (!inFlight()) return true;
      const r = await ask(text(what), ctx);
      return !!r && r.response === 0;
    },
    quitting: () => quitting,
    markQuitting: () => { quitting = true; },
    beforeQuit(e, win, quit) {
      if (!quitting && inFlight()) {
        e.preventDefault();
        guard.confirm("quit", win).then((ok) => { if (ok) { quitting = true; quit(); } });
        return false;
      }
      quitting = true;
      return true;
    },
    async restart(win, act) {
      if (!(await guard.confirm("restart", win))) return { cancelled: true };
      quitting = true;
      let r;
      try { r = await act(); } finally { if (!(r && r.ok)) quitting = false; }
      return r;
    },
  };
  return guard;
}

module.exports = {
  createUpdateProgress, createQuitGuard, parseDockerEvent, eventArgs, isAvailableBuild, liveRunsText,
  writeView, readView, clearView, STEPS, VIEW_FILE, STARTED, FLYWAY, flywayNote,
};
