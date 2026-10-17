// engine-update.js — the status bar's Update engine button: which engine build
// is running, which one the registry has, and the one click that pulls and
// recreates the engine. The engine's counterpart of src/app-update.js.
//
// The engine is published as <registry>/frakworks-engine:main on every
// merge that touches engine/ (.github/workflows/images.yml, engine/README.md
// "Images"), and until this button nothing said a newer one was there. The
// registry is a deployment value: the
// `registry` key of agent-army.config.json (deploymentConfig in
// src/deployment-config.js), `localhost:5000` when unset. It
// never acts on its own (the user, 2026-09-26: no restart while they watch
// runs): the interval only checks; pull and up run from a click, after a
// confirm in main.
//
// The comparison is by digest, which is exact:
//   - available: the registry's `Docker-Content-Digest` for the `:main`
//     manifest (list), GET /v2/frakworks-engine/manifests/main. Its config
//     blob's `org.opencontainers.image.revision` label is the commit, for a
//     person to read (engine/Dockerfile, GIT_SHA).
//   - running: the engine container, found by its compose service label and
//     the port ENGINE_URL names; its image's RepoDigests entry for the
//     registry repo. `commit` is the engine's own answer on GET /version.
// An image cannot hold its own digest (the digest is computed after the
// build), so the engine reports its commit and Docker reports the digest.
// Either digest unknown is an error state with the reason, never a guess.
//
// How the update finds the compose project without a checkout (a packaged
// build has none): the running container's own compose labels,
// `com.docker.compose.project`, `.project.working_dir` and
// `.project.config_files`. The update is those plus
//   docker compose pull engine          (a failure stops here: nothing changed)
//   docker compose up -d --no-deps engine
// then a wait for /version to answer. `--no-deps`: only the engine is
// recreated, never the services it depends on.
//
// The two compose steps run in one detached process, the runner (#1040): the
// app's own binary as Node (ELECTRON_RUN_AS_NODE, `-e RUNNER`, nothing
// shipped beside it), `detached` and `unref()`ed, its stdout and stderr on
// <userData>/engine-update.log, never on a pipe the app holds. It writes
// <userData>/engine-update.json at each step and at its end: its pid, the
// step (`pull`, `up`), the steps done, and at the end the exit code and the
// failing step's last output line. On 2026-10-05 an app restart during `up`
// took the compose children with it and left the new engine container in
// Created; now quitting the app leaves the runner going.
//   - The app that started it hears the end from the child's `exit` event,
//     then waits for /version and checks, and update() resolves to the
//     `{ ok, steps, build }` reportUpdate reads.
//   - A relaunched app finds the status file on its first check(): a live pid
//     with no exit code is resumed (`updating` is its step, a click is
//     refused, `fs.watch` on userData says when it ends; `onResult` carries
//     the report, as no click awaits it); an exit code is reported; a dead pid
//     with none is a failed update with the log's last line, never in flight.
//     The file is removed once reported.
//   - The backstop, on every check() with no update in flight: a compose
//     "engine" container in state Created that publishes ENGINE_URL's port
//     (and belongs to the engine's compose project, when one is known) is
//     started with `docker start`, logged, and reported "engine started (left
//     in Created)". The detached runner dies with a logout, a reboot or
//     Docker stopping; this is what picks up after it.
//
// Live runs are the `smith_run_active` samples on the engine's GET /metrics
// (engine/README.md, "Transcripts and the live-run gauge"); they survive the
// restart (`SmithTelemetryResume`), and the confirm says so.
//
// `createEngineUpdater({ run, fetch, engineUrl, dir, spawn, isPidAlive, watch })`
// takes docker, HTTP, the process and the watcher as arguments so the tests
// hand in stand-ins; main.js passes the real ones. `onResume` hears a
// relaunch take up an update in flight, and `verify` and `liveRuns` are the
// one-shot reads the progress overlay asks on an event
// (src/engine-update-progress.js, #1053). `changes({ running, available })`
// is what the update brings (src/engine-update-changes.js, #1398): read once
// per commit pair while the digests differ, kept on `state().changes`.
// The pure helpers are what src/renderer/status-bar.jsx mirrors.
"use strict";
const { changesLines } = require("./engine-update-changes");

// How often the registry and the running engine are asked: every 30 s. One
// manifest GET on the LAN and two local docker calls.
const CHECK_MS = 30000;
const REGISTRY = "localhost:5000";
const REPO = "frakworks-engine";
const TAG = "main";
const REF = `${REGISTRY}/${REPO}:${TAG}`;
const SERVICE = "engine";
const REVISION_LABEL = "org.opencontainers.image.revision";
// Manifest lists (images.yml publishes an index, amd64 only) and single manifests,
// OCI and Docker, so the digest asked for is the one `docker pull` records.
const INDEX_ACCEPT = [
  "application/vnd.oci.image.index.v1+json",
  "application/vnd.docker.distribution.manifest.list.v2+json",
  "application/vnd.oci.image.manifest.v1+json",
  "application/vnd.docker.distribution.manifest.v2+json",
].join(", ");
const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "::1", "[::1]"]);

const DIGEST = /^sha256:[0-9a-f]{64}$/;

// ---------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------

/** The digest in `repoDigests` (an image's RepoDigests) for `repo` (`registry/name`), or null. */
function parseRepoDigest(repoDigests, repo = `${REGISTRY}/${REPO}`) {
  for (const d of Array.isArray(repoDigests) ? repoDigests : []) {
    const at = String(d).lastIndexOf("@");
    if (at < 0) continue;
    const digest = String(d).slice(at + 1);
    if (String(d).slice(0, at) === repo && DIGEST.test(digest)) return digest;
  }
  return null;
}

/** "same", "differs", or "unknown" when either digest is missing. */
function compareDigests(running, available) {
  if (!running || !available || !DIGEST.test(running) || !DIGEST.test(available)) return "unknown";
  return running === available ? "same" : "differs";
}

/** How many `smith_run_active{...} 1` samples a /metrics body has; HELP/TYPE and other series are not counted. */
function countLiveRuns(metricsText) {
  let n = 0;
  for (const line of String(metricsText || "").split("\n")) {
    if (/^smith_run_active(\{[^}]*\})?\s+1(\.0+)?(\s|$)/.test(line.trim())) n++;
  }
  return n;
}

/** The confirm's sentence about live runs; `n` null: the count could not be read (`error`). */
function liveRunsPhrase(n, error) {
  const survive = "(SmithTelemetryResume: the engine follows them again when it starts)";
  if (n == null) return `The live Smith run count is unknown${error ? ` (${error})` : ""}; live runs survive an engine restart ${survive}.`;
  if (n === 0) return `No Smith runs are live; a live run would survive the engine restart ${survive}.`;
  if (n === 1) return `1 Smith run is live; it survives the engine restart ${survive}.`;
  return `${n} Smith runs are live; they survive the engine restart ${survive}.`;
}

/** The confirm's sentence about first-mate sessions open on the engine; null: unknown. */
function sessionsPhrase(n) {
  if (n == null) return "The open first-mate session count is unknown.";
  if (n === 0) return "No first-mate sessions are open.";
  return `${n} first-mate session${n === 1 ? " is" : "s are"} open; ${n === 1 ? "it loses" : "they lose"} the engine for the restart's gap.`;
}

/** ` · N commits behind` from the engine's status, or "" when it does not know. */
function behindPhrase(e) {
  if (!e || e.behind == null) return "";
  return ` · ${e.behind} commit${e.behind === 1 ? "" : "s"} behind`;
}

const short = (s) => (s || "").slice(0, 7);
/** A build's short name: `sha-<7>` from its commit, else the digest's first 12 hex, else "?". */
function buildLabel(b) {
  if (b && b.commit) return `sha-${short(b.commit)}`;
  if (b && b.digest) return b.digest.slice(7, 19);
  return "?";
}

/**
 * The status bar's engine-build phrase and the button's state, from `state()`:
 * `{ text, level, lit, reason }`. `level` is the bar's colour class; `lit` is
 * true only when both digests are known and differ.
 */
/** An error as the bar's text carries it: at most 80 characters, cut with `…`; the hover keeps it whole. */
function cutError(e) {
  const t = String(e);
  return t.length > 80 ? `${t.slice(0, 80)}…` : t;
}

function describeEngineUpdate(s) {
  if (!s) return { text: "engine build…", level: "muted", lit: false, reason: "" };
  if (s.updating) return { text: `engine ${buildLabel(s.running)} · updating: ${s.updating}…`, level: "muted", lit: false, reason: "" };
  if (!s.checkedAt) return { text: "engine build checking…", level: "muted", lit: false, reason: "" };
  const run = `engine ${buildLabel(s.running)}`;
  if (s.remote) return describeEngineDeploy(s, run);
  if (s.runningError) return { text: `${run} · build unknown`, level: "bad", lit: false, reason: s.runningError };
  if (s.availableError) return { text: `${run} · main unknown: ${cutError(s.availableError)}`, level: "bad", lit: false, reason: s.availableError };
  const cmp = compareDigests(s.running && s.running.digest, s.available && s.available.digest);
  if (cmp === "unknown") return { text: `${run} · digest unknown`, level: "bad", lit: false, reason: "a digest is missing; not guessing" };
  if (cmp === "same") return { text: `${run} · main`, level: "ok", lit: false, reason: "" };
  return { text: `${run} · main ${buildLabel(s.available)}`, level: "stale", lit: true, reason: "" };
}

/**
 * An engine not on this machine: the engine's own GET /engine/update says
 * what runs, what `:main` is, and whether it can update itself (a cluster's
 * engine updates itself). The app knows nothing of how.
 */
function describeEngineDeploy(s, run) {
  const e = s.engine;
  if (!e) return { text: `${run} · deploy status unknown`, level: "bad", lit: false, remote: true, reason: s.engineError || "no answer from GET /engine/update" };
  if (!e.selfUpdate) return { text: `${run} · its host updates it`, level: "muted", lit: false, remote: true, reason: e.error || "" };
  if (!e.latest || !e.latest.commit) return { text: e.error ? `${run} · main unknown: ${cutError(e.error)}` : `${run} · main unknown`, level: "bad", lit: false, remote: true, reason: e.error || "the engine could not read the registry's :main" };
  if (!e.updateAvailable) return { text: `${run} · main`, level: "ok", lit: false, remote: true, reason: "" };
  return { text: `${run} · main ${buildLabel(e.latest)}${behindPhrase(e)}`, level: "stale", lit: true, remote: true, reason: "" };
}

/** The button's hover: both builds, the registry reference, the last check, the live runs and the error. */
function engineUpdateTitle(s) {
  if (!s) return "";
  const b = (x) => (x ? `${x.commit || "commit ?"} ${x.digest || "digest ?"}` : "?");
  const d = describeEngineUpdate(s);
  if (d.remote) {
    const e = s.engine || {};
    const lines = [`running   ${b(s.running)}`, `available ${b(e.latest)}`, `image     ${e.image || "?"}`,
      `mode      ${e.mode || "?"}`, `checked   ${s.checkedAt ? new Date(s.checkedAt).toISOString() : "not yet"}`];
    if (e.behind != null) lines.push(`behind    ${e.behind} commits`);
    if (s.liveRuns != null) lines.push(`live runs ${s.liveRuns}`);
    if (e.openSessions != null) lines.push(`sessions  ${e.openSessions} first-mate`);
    if (d.reason) lines.push(`error     ${d.reason}`);
    lines.push(d.lit ? "Click to deploy main: the engine restarts itself (asks first)" : "Click to check now");
    return lines.join("\n");
  }
  const lines = [
    `running   ${b(s.running)}`,
    `available ${b(s.available)}`,
    `registry  ${s.ref || REF}`,
    `checked   ${s.checkedAt ? new Date(s.checkedAt).toISOString() : "not yet"}`,
  ];
  if (s.liveRuns != null) lines.push(`live runs ${s.liveRuns}`);
  if (d.reason) lines.push(`error     ${d.reason}`);
  if (d.lit) lines.push(...changesLines(s.changes));
  lines.push(d.lit ? "Click to pull main and recreate the engine (asks first)" : "Click to check the registry now");
  return lines.join("\n");
}

/**
 * The line drawn next to the button after a click: `{ text, level, detail }`
 * from update()'s `{ ok, steps, build }`; null for a click that only checked.
 * `{ started }` is the backstop's start of a container left in Created.
 */
function reportUpdate(r) {
  if (r && r.started) return { text: "engine started (left in Created)", level: "ok", detail: `docker start ${r.started}` };
  if (!r || r.ok == null) return null;
  if (r.ok) {
    const b = r.build || {};
    return { text: `engine updated to ${buildLabel(b)}`, level: "ok", detail: `commit ${b.commit || "?"}\ndigest ${b.digest || "?"}` };
  }
  const failed = (r.steps || []).find((s) => !s.ok);
  const text = !failed ? "engine update failed" : failed.step === "check" && failed.detail ? `engine check failed: ${cutError(failed.detail)}` : `engine ${failed.step} failed`;
  return { text, level: "bad", detail: failed ? failed.detail : "" };
}

/** Whether ENGINE_URL is a URL whose host is not this machine: an engine the app cannot docker-compose-update, deployed through its own POST /engine/update. */
function remoteEngine(engineUrl) {
  try { return !LOCAL_HOSTS.has(new URL(engineUrl).hostname); } catch (e) { return false; }
}

/** Whether ENGINE_URL is on this machine (and so on the Docker host `docker` talks to), and its port. */
function localEngine(engineUrl) {
  let u;
  try { u = new URL(engineUrl); } catch (e) { return { error: `ENGINE_URL ${engineUrl} is not a URL` }; }
  if (!LOCAL_HOSTS.has(u.hostname)) return { error: `the engine at ${engineUrl} is not on this machine; the button only updates a local engine` };
  return { port: u.port || (u.protocol === "https:" ? "443" : "80") };
}

// ---------------------------------------------------------------------------
// The runner: the detached process that runs the compose steps
// ---------------------------------------------------------------------------

const STATUS_FILE = "engine-update.json";
const LOG_FILE = "engine-update.log";

/**
 * The detached runner's body, run as `<app binary> -e RUNNER <cfg JSON>` with
 * ELECTRON_RUN_AS_NODE, so it is self-contained: `cfg` is `{ docker, cwd,
 * status, log, project, steps: [{ step, args, timeoutMs }], reattach? }`. Each step runs
 * only if the one before exited 0; docker's output goes to the runner's own
 * stdout and stderr, which are the log file. The status file is written (a
 * temp file renamed over it) at each step and at the end; the runner exits
 * with the failing step's code, 0 when every step passed.
 */
function runner(cfg) {
  const fs = require("node:fs");
  const { spawn } = require("node:child_process");
  const s = { pid: process.pid, step: null, done: [], project: cfg.project || null, log: cfg.log,
    startedAt: new Date().toISOString(), code: null, failedStep: null, detail: null, endedAt: null };
  const write = () => {
    const tmp = `${cfg.status}.${process.pid}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(s));
    fs.renameSync(tmp, cfg.status);
  };
  const size = () => { try { return fs.statSync(cfg.log).size; } catch (e) { return 0; } };
  const lastLine = (from) => {
    try { return String(fs.readFileSync(cfg.log).subarray(from)).trim().split("\n").filter(Boolean).pop() || ""; } catch (e) { return ""; }
  };
  const end = (code, detail) => {
    s.code = code;
    s.detail = detail;
    if (code !== 0) s.failedStep = s.step;
    s.endedAt = new Date().toISOString();
    write();
    process.stdout.write(`== ${code === 0 ? "done" : `${s.step} failed: ${detail}`}\n`);
    process.exit(code);
  };
  // The network check (#1222): an `up` that recreates the engine has, on the
  // host's Docker Desktop, come up with `.NetworkSettings.Networks` `{}`; the
  // JVM then exits on `UnknownHostException: engine-db` and stays down. After
  // the steps, the project's one engine container is inspected; with no
  // network the `up` runs once more as `cfg.reattach.step`, and a second empty
  // set fails the update. An inspect that cannot answer (no or several
  // containers, docker failing) is logged and changes nothing.
  const capture = (args, cb) => {
    const ch = spawn(cfg.docker, args, { cwd: cfg.cwd, stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
    let out = "", err = "";
    ch.stdout.on("data", (d) => { out += d; });
    ch.stderr.on("data", (d) => { err += d; });
    ch.on("error", (e) => cb(e.message, null));
    ch.on("close", (code) => cb(code === 0 ? null : `exit ${code}: ${err.trim().split("\n").pop() || "no output"}`, out));
  };
  const networks = (cb) => {
    const r = cfg.reattach;
    capture(["ps", "-a", "-q", "--filter", `label=com.docker.compose.project=${cfg.project}`,
      "--filter", `label=com.docker.compose.service=${r.service}`], (e, out) => {
      if (e) return cb(`docker ps: ${e}`);
      const ids = String(out).split("\n").map((x) => x.trim()).filter(Boolean);
      if (ids.length !== 1) return cb(`${ids.length} ${r.service} containers in project ${cfg.project}; not guessing which to inspect`);
      capture(["inspect", "--format", "{{json .NetworkSettings.Networks}}", ids[0]], (e2, out2) => {
        if (e2) return cb(`docker inspect ${ids[0]}: ${e2}`);
        let n = null;
        try { n = JSON.parse(String(out2).trim()); } catch (x) { return cb(`docker inspect ${ids[0]}: no JSON`); }
        cb(null, ids[0], Object.keys(n || {}));
      });
    });
  };
  const check = (again) => {
    networks((e, id, names) => {
      if (e) { process.stdout.write(`== network check skipped: ${e}\n`); return end(0, null); }
      if (names.length) return end(0, null);
      if (again) {
        s.step = cfg.reattach.step;
        return end(1, `the ${cfg.reattach.service} container ${id} came up with no network again (.NetworkSettings.Networks is {})`);
      }
      process.stdout.write(`== the ${cfg.reattach.service} container ${id} came up with no network; recreating it\n`);
      run({ step: cfg.reattach.step, args: cfg.reattach.args, timeoutMs: cfg.reattach.timeoutMs }, () => check(true));
    });
  };
  const next = (i) => {
    if (i >= cfg.steps.length) return cfg.reattach ? check(false) : end(0, null);
    run(cfg.steps[i], () => next(i + 1));
  };
  const run = (step, then) => {
    s.step = step.step;
    write();
    process.stdout.write(`== ${step.step}: ${cfg.docker} ${step.args.join(" ")}\n`);
    const from = size();
    let settled = false, timedOut = false;
    const ch = spawn(cfg.docker, step.args, { cwd: cfg.cwd, stdio: ["ignore", "inherit", "inherit"], windowsHide: true });
    const timer = setTimeout(() => { timedOut = true; ch.kill(); }, step.timeoutMs);
    ch.on("error", (e) => { if (settled) return; settled = true; clearTimeout(timer); end(127, e.message); });
    ch.on("exit", (code, signal) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (code === 0) { s.done.push(step.step); return then(); }
      if (timedOut) return end(124, `timed out after ${step.timeoutMs / 1000}s`);
      end(code == null ? 1 : code, `exit ${code == null ? signal : code}: ${lastLine(from) || "no output"}`);
    });
  };
  next(0);
}
const RUNNER = `(${runner.toString()})(JSON.parse(process.argv[process.argv.length - 1]));`;

/** The real `isPidAlive`: signal 0 is delivered (or refused, EPERM) only to a process that exists. */
function pidAlive(pid) {
  try { process.kill(pid, 0); return true; } catch (e) { return !!(e && e.code === "EPERM"); }
}

// ---------------------------------------------------------------------------
// The updater
// ---------------------------------------------------------------------------

const firstLine = (s) => String(s || "").trim().split("\n").filter(Boolean).pop() || "";
const errText = (r) => (r.error ? r.error : `exit ${r.code}: ${firstLine(r.stderr) || firstLine(r.stdout) || "no output"}`);

/**
 * `run(cmd, args, { timeoutMs })` resolves to `{ code, stdout, stderr, error }`
 * and never rejects; it is only for one-shot reads (`docker ps`, `inspect`)
 * and the backstop's `docker start`. `fetch` is WHATWG fetch. `docker` is the
 * resolved binary (src/docker-bin.js). `dir` is userData, where the runner's
 * log and status file are. `spawn` is child_process.spawn and `execPath` the
 * binary the runner runs on; `isPidAlive` and `watch` (fs.watch) are how a
 * relaunched app follows a runner it did not start, and `onResult` hears the
 * reports no click awaits (a resumed update's end, the backstop's start).
 * `state()` is what `engine:update-state` reports; `check()` asks the
 * registry, docker and the engine once and never runs compose; `update()` is
 * the click's action and refuses unless the last check found the digests
 * different.
 */
function createEngineUpdater({
  run, fetch = globalThis.fetch, engineUrl, docker = "docker", registry = REGISTRY, repo = REPO, tag = TAG,
  now = () => Date.now(), log = () => {}, sleep = (ms) => new Promise((r) => setTimeout(r, ms)),
  existsFn = (p) => require("node:fs").existsSync(p), waitMs = 180000, pollMs = 2000, timeoutMs = 10000,
  dir = null, spawn = (...a) => require("node:child_process").spawn(...a), execPath = process.execPath, env = process.env,
  isPidAlive = pidAlive, watch = (d, cb) => require("node:fs").watch(d, cb), onResult = () => {}, onResume = () => {},
  changes = null, auth = () => ({}),
}) {
  const fs = require("node:fs");
  const path = require("node:path");
  const statusFile = dir ? path.join(dir, STATUS_FILE) : null;
  const logFile = dir ? path.join(dir, LOG_FILE) : null;
  const ref = `${registry}/${repo}:${tag}`;
  const st = {
    ref, checking: false, checkedAt: null, updating: null,
    available: null, availableError: null, running: null, runningError: null,
    liveRuns: null, liveRunsError: null, changes: null,
    // Not on this machine: /version, /metrics and /engine/update are read, no docker and no registry.
    remote: remoteEngine(engineUrl), engine: null, engineError: null,
  };
  // What the update brings (src/engine-update-changes.js), read once per
  // (running, available) commit pair while the digests differ.
  let changesKey = null;
  const readChanges = async () => {
    if (!changes || compareDigests(st.running && st.running.digest, st.available && st.available.digest) !== "differs") {
      changesKey = null; st.changes = null; return;
    }
    const running = (st.running && st.running.commit) || null, available = (st.available && st.available.commit) || null;
    const key = `${running}..${available}`;
    if (key === changesKey && st.changes) return;
    changesKey = key;
    try { st.changes = await changes({ running, available }); } catch (e) { st.changes = { error: (e && e.message) || String(e) }; }
  };

  const get = async (url, headers = {}, method = "GET") => {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), timeoutMs);
    try {
      const res = await fetch(url, { method, signal: ctl.signal, headers });
      const text = await res.text();
      return { ok: res.ok, status: res.status, text, header: (h) => (res.headers && res.headers.get ? res.headers.get(h) : null) };
    } catch (e) {
      return { error: e && e.name === "AbortError" ? `timed out after ${timeoutMs / 1000}s` : (e && e.message) || String(e) };
    } finally { clearTimeout(t); }
  };
  const json = (text) => { try { return JSON.parse(text); } catch (e) { return null; } };

  // The registry's :main: its digest, and the commit from its config's label.
  const readAvailable = async () => {
    const base = `http://${registry}/v2/${repo}`;
    const r = await get(`${base}/manifests/${tag}`, { accept: INDEX_ACCEPT });
    if (r.error) return { error: `registry unreachable (${ref}): ${r.error}` };
    if (!r.ok) return { error: `registry answered ${r.status} for ${ref}` };
    const digest = r.header("docker-content-digest");
    if (!digest || !DIGEST.test(digest)) return { error: `registry sent no Docker-Content-Digest for ${ref}` };
    return { value: { digest, commit: await readRevision(base, json(r.text)) } };
  };
  // Best effort: the commit is for reading; a miss is a null commit, not an error.
  const readRevision = async (base, m) => {
    if (m && Array.isArray(m.manifests)) {
      const pick = m.manifests.find((x) => x.platform && x.platform.os === "linux" && x.platform.architecture !== "unknown");
      if (!pick) return null;
      const r = await get(`${base}/manifests/${pick.digest}`, { accept: INDEX_ACCEPT });
      m = r.ok ? json(r.text) : null;
    }
    if (!m || !m.config || !m.config.digest) return null;
    const c = await get(`${base}/blobs/${m.config.digest}`);
    const cfg = c.ok ? json(c.text) : null;
    const labels = (cfg && cfg.config && cfg.config.Labels) || {};
    return labels[REVISION_LABEL] || null;
  };

  // The engine's compose project, once a container or a status file named it.
  let knownProject = null;

  // The engine container on this Docker host, its image's digest and compose project.
  const readRunning = async () => {
    const loc = localEngine(engineUrl);
    if (loc.error) return { error: loc.error };
    const ps = await run(docker, ["ps", "--filter", `label=com.docker.compose.service=${SERVICE}`,
      "--filter", `publish=${loc.port}`, "--format", "{{.ID}}"], { timeoutMs });
    if (ps.error || ps.code !== 0) return { error: `docker ps: ${errText(ps)}` };
    const ids = ps.stdout.split("\n").map((s) => s.trim()).filter(Boolean);
    if (!ids.length) return { error: `no running compose "${SERVICE}" container publishes port ${loc.port} on this Docker host` };
    if (ids.length > 1) return { error: `${ids.length} "${SERVICE}" containers publish port ${loc.port}; not guessing which` };
    const ins = await run(docker, ["inspect", ids[0]], { timeoutMs });
    const c = !ins.error && ins.code === 0 ? (json(ins.stdout) || [])[0] : null;
    if (!c) return { error: `docker inspect ${ids[0]}: ${ins.error || ins.code !== 0 ? errText(ins) : "no JSON"}` };
    const labels = (c.Config && c.Config.Labels) || {};
    const project = {
      name: labels["com.docker.compose.project"] || null,
      workingDir: labels["com.docker.compose.project.working_dir"] || null,
      configFiles: String(labels["com.docker.compose.project.config_files"] || "").split(",").filter(Boolean),
    };
    if (project.name) knownProject = project.name;
    const image = (c.Config && c.Config.Image) || "";
    const base = {
      containerId: ids[0], image, project,
      commit: null, digest: null,
    };
    if (image !== ref) return { value: base, error: `the engine runs ${image || "an unnamed image"}, not ${ref}; only a registry :${tag} engine is updated here` };
    const img = await run(docker, ["image", "inspect", c.Image, "--format", "{{json .RepoDigests}}"], { timeoutMs });
    if (img.error || img.code !== 0) return { value: base, error: `docker image inspect: ${errText(img)}` };
    base.digest = parseRepoDigest(json(img.stdout), `${registry}/${repo}`);
    if (!base.digest) return { value: base, error: `the engine's image has no ${registry}/${repo} digest (built locally?)` };
    return { value: base };
  };

  const readVersion = async () => {
    const r = await get(`${engineUrl}/version`, { accept: "application/json", ...auth() });
    const v = r.ok ? json(r.text) : null;
    return v ? { value: v } : { error: r.error || `engine answered ${r.status} for /version` };
  };

  // The engine's own update status (GET /engine/update): its mode, `:main`, how far behind.
  const readEngineStatus = async () => {
    const r = await get(`${engineUrl}/engine/update`, { accept: "application/json", ...auth() });
    const v = r.ok ? json(r.text) : null;
    return v ? { value: v } : { error: r.error || `engine answered ${r.status} for /engine/update` };
  };

  const readLiveRuns = async () => {
    const r = await get(`${engineUrl}/metrics`);
    if (r.error) return { error: `engine /metrics: ${r.error}` };
    if (!r.ok) return { error: `engine answered ${r.status} for /metrics` };
    return { value: countLiveRuns(r.text) };
  };

  // One check at a time: a second caller shares the one in flight, and
  // `fresh` (after an update) waits it out and starts its own.
  let inflight = null;
  const doCheck = async () => {
    st.checking = true;
    try {
      syncStatus();
      if (!st.updating) await startCreated();
      const none = async () => ({});
      const [a, r, v, l, e] = await Promise.all([st.remote ? none() : readAvailable(), st.remote ? none() : readRunning(),
        readVersion(), readLiveRuns(), readEngineStatus()]);
      st.engine = e.value || null;
      st.engineError = e.error || null;
      if (st.remote) {
        a.value = st.engine && st.engine.latest ? st.engine.latest : undefined;
        if (e.error) log(`engine update: ${e.error}`);
      }
      st.available = a.value || null;
      st.availableError = a.error || null;
      const commit = v.value ? v.value.commit || null : null;
      st.running = r.value || commit ? { ...(r.value || {}), commit } : null;
      st.runningError = r.error || null;
      st.liveRuns = l.error ? null : l.value;
      st.liveRunsError = l.error || null;
      if (a.error) log(`engine update: ${a.error}`);
      if (r.error) log(`engine update: ${r.error}`);
      if (!st.updating) await readChanges();
    } finally { st.checking = false; st.checkedAt = now(); }
    return { ...st };
  };
  const check = async ({ fresh = false } = {}) => {
    if (inflight && fresh) await inflight.catch(() => {});
    if (!inflight) inflight = doCheck().finally(() => { inflight = null; });
    return inflight;
  };

  // --- The runner's status file ---------------------------------------------
  // `tracking` is this instance's hold on an update: null, or `{ parent }`
  // (it spawned the runner and hears its exit) or `{ resumed }` (a relaunch
  // following the file), plus `finishing` once the end is being reported.
  let tracking = null;
  let watcher = null;
  const readStatus = () => {
    if (!statusFile) return null;
    try { return JSON.parse(fs.readFileSync(statusFile, "utf8")); } catch (e) { return null; }
  };
  const clearStatus = () => { if (statusFile) { try { fs.unlinkSync(statusFile); } catch (e) { /* gone already */ } } };
  const lastLogLine = () => { try { return firstLine(fs.readFileSync(logFile, "utf8")); } catch (e) { return ""; } };
  const stopWatch = () => { if (watcher) { try { watcher.close(); } catch (e) { /* closed */ } watcher = null; } };
  const startWatch = () => {
    if (watcher || !dir) return;
    try {
      watcher = watch(dir, (_ev, name) => { if (!name || String(name) === STATUS_FILE) onStatusChange(); });
    } catch (e) { log(`engine update: cannot watch ${dir}: ${(e && e.message) || e}`); }
  };
  const onStatusChange = () => {
    if (!tracking || tracking.finishing) return;
    const s = readStatus();
    if (!s) return;
    if (tracking.parent) { if (s.code == null && s.step) st.updating = s.step; return; }
    consider(s);
  };
  // `onResume(status)`: this instance took up an update a previous app started (the overlay opens on it).
  const resumed = (s) => { try { onResume(s); } catch (e) { log(`engine update: onResume: ${(e && e.message) || e}`); } };
  const report = (p) => p.then((r) => { try { onResult(r); } catch (e) { log(`engine update: onResult: ${(e && e.message) || e}`); } });
  // A status file this instance did not start the runner for.
  const consider = (s) => {
    if (s.project) knownProject = knownProject || s.project;
    if (s.code != null) {
      log(`engine update: the runner${s.pid ? ` (pid ${s.pid})` : ""} ended, exit ${s.code}`);
      tracking = { resumed: true, finishing: true };
      // A runner that ended well is still in flight until the engine answers: the overlay follows it.
      if (s.code === 0) resumed(s);
      return report(finish(s));
    }
    if (s.pid && isPidAlive(s.pid)) {
      if (!tracking) {
        tracking = { resumed: true };
        log(`engine update: resuming the update in flight (pid ${s.pid}, ${s.step || "starting"})`);
        startWatch();
        resumed(s);
      }
      st.updating = s.step || "pull";
      return null;
    }
    // Dead without an end: a failed update, never one in flight.
    const step = s.step || "update";
    log(`engine update: the runner${s.pid ? ` (pid ${s.pid})` : ""} is gone without finishing ${step}`);
    tracking = { resumed: true, finishing: true };
    return report(finish({ ...s, code: null, failedStep: step, detail: lastLogLine() || "the runner ended with no output" }));
  };
  // At every check: a status file nobody here is following yet, or a resumed
  // runner whose pid has gone without the watcher hearing an end.
  const syncStatus = () => {
    if (!statusFile || (tracking && (tracking.parent || tracking.finishing))) return;
    const s = readStatus();
    if (s) consider(s);
    else if (tracking) { stopWatch(); tracking = null; st.updating = null; }
  };

  // The end of the runner, from its status file: a failure is reported with
  // the failing step's last line; a success waits for /version and checks.
  const finish = async (s) => {
    stopWatch();
    const steps = (s.done || []).map((step) => ({ step, ok: true, detail: "" }));
    const fail = (step, detail) => {
      steps.push({ step, ok: false, detail });
      log(`engine update: ${step} failed: ${detail}`);
      st.updating = null; tracking = null; clearStatus();
      return { ok: false, steps };
    };
    if (s.code !== 0) return fail(s.failedStep || s.step || "update", s.detail || `exit ${s.code}: ${lastLogLine() || "no output"}`);
    st.updating = "wait for engine";
    let v = null;
    for (let waited = 0; ; waited += pollMs) {
      v = await readVersion();
      if (v.value || waited >= waitMs) break;
      await sleep(pollMs);
    }
    if (!v.value) return fail("wait for engine", `the engine did not answer /version within ${waitMs / 1000}s: ${v.error}`);
    steps.push({ step: "wait for engine", ok: true, detail: "" });
    st.updating = null; tracking = null; clearStatus();
    const after = await check({ fresh: true });
    const build = { commit: after.running && after.running.commit, digest: after.running && after.running.digest };
    log(`engine update: now ${build.commit || "?"} ${build.digest || "?"}`);
    return { ok: true, steps, build };
  };

  // --- The backstop: an engine container left in Created ----------------------
  const startCreated = async () => {
    const loc = localEngine(engineUrl);
    if (loc.error) return;
    const ps = await run(docker, ["ps", "-a", "--filter", `label=com.docker.compose.service=${SERVICE}`,
      "--filter", "status=created", "--format", "{{.ID}}"], { timeoutMs });
    if (ps.error || ps.code !== 0) { log(`engine update: docker ps -a: ${errText(ps)}`); return; }
    const ids = ps.stdout.split("\n").map((x) => x.trim()).filter(Boolean);
    if (!ids.length) return;
    const ins = await run(docker, ["inspect", ...ids], { timeoutMs });
    const all = !ins.error && ins.code === 0 ? json(ins.stdout) || [] : [];
    // The same identity readRunning uses, the published port, and the
    // engine's compose project when one is known.
    const mine = all.filter((c) => {
      const labels = (c.Config && c.Config.Labels) || {};
      if (knownProject && labels["com.docker.compose.project"] !== knownProject) return false;
      const binds = (c.HostConfig && c.HostConfig.PortBindings) || {};
      return Object.values(binds).some((bs) => (bs || []).some((b) => String(b.HostPort) === loc.port));
    });
    if (!mine.length) return;
    if (mine.length > 1) { log(`engine update: ${mine.length} "${SERVICE}" containers in Created publish port ${loc.port}; not guessing which to start`); return; }
    const id = String(mine[0].Id || ids[0]).slice(0, 12);
    const r = await run(docker, ["start", id], { timeoutMs: 60000 });
    if (r.error || r.code !== 0) {
      log(`engine update: docker start ${id} (left in Created) failed: ${errText(r)}`);
      onResult({ ok: false, steps: [{ step: "start", ok: false, detail: errText(r) }] });
      return;
    }
    log(`engine update: started the engine container ${id}, left in Created`);
    onResult({ started: id });
  };

  const compose = (p, ...args) => {
    const a = ["compose", "-p", p.name, "--project-directory", p.workingDir];
    for (const f of p.configFiles) a.push("-f", f);
    return [...a, ...args];
  };

  const update = async () => {
    const steps = [];
    const fail = (step, detail) => { steps.push({ step, ok: false, detail }); log(`engine update: ${step} failed: ${detail}`); return { ok: false, steps }; };
    syncStatus();
    if (st.updating) return fail("check", `an update is already running (${st.updating})`);
    if (st.remote) return deploy(steps, fail);
    const cmp = compareDigests(st.running && st.running.digest, st.available && st.available.digest);
    if (cmp !== "differs") {
      return fail("check", cmp === "same" ? "the engine already runs main's build" : describeEngineUpdate(st).reason || "a digest is unknown");
    }
    const p = st.running.project;
    if (!p.name || !p.workingDir || !p.configFiles.length) return fail("find project", "the engine container carries no compose project labels");
    if (!existsFn(p.workingDir)) return fail("find project", `the compose project's directory ${p.workingDir} is not on this machine`);
    if (!dir) return fail("update", "no directory for the update's log and status file");
    const cfg = {
      docker, cwd: p.workingDir, status: statusFile, log: logFile, project: p.name,
      steps: [
        { step: "pull", args: compose(p, "pull", SERVICE), timeoutMs: 600000 },
        { step: "up", args: compose(p, "up", "-d", "--no-deps", SERVICE), timeoutMs: 300000 },
      ],
      reattach: { step: "reattach", service: SERVICE, args: compose(p, "up", "-d", "--no-deps", SERVICE), timeoutMs: 300000 },
    };
    let child, fd = null;
    try {
      fs.mkdirSync(dir, { recursive: true });
      clearStatus();
      fd = fs.openSync(logFile, "w");
      child = spawn(execPath, ["-e", RUNNER, JSON.stringify(cfg)], {
        cwd: p.workingDir, detached: true, stdio: ["ignore", fd, fd], windowsHide: true,
        env: { ...env, ELECTRON_RUN_AS_NODE: "1" },
      });
    } catch (e) {
      return fail("update", `could not start the update runner: ${(e && e.message) || e}`);
    } finally { if (fd != null) fs.closeSync(fd); }
    child.unref();
    st.updating = "pull";
    tracking = { parent: true };
    log(`engine update: runner pid ${child.pid}, log ${logFile}`);
    startWatch();
    return new Promise((resolve) => {
      let heard = false;
      const ended = (code, error) => {
        if (heard) return;
        heard = true;
        tracking.finishing = true;
        const s = readStatus();
        const end = s && s.code != null ? s
          : { ...(s || {}), code: code == null ? 1 : code, failedStep: (s && s.step) || st.updating || "update",
            detail: error ? `could not start the update runner: ${error}` : `the runner exited ${code} without an end: ${lastLogLine() || "no output"}` };
        resolve(finish(end));
      };
      child.on("error", (e) => ended(null, (e && e.message) || String(e)));
      child.on("exit", (code) => ended(code));
    });
  };

  // When GET /version names another commit than the target, the engine's
  // GET /engine/update is read again: an images.yml run can publish a newer
  // :main between the check and the restart (#2464), and the engine restarts
  // onto that. A commit that is :main's now is accepted as the new target.
  const retarget = async (running) => {
    const e = await readEngineStatus();
    const latest = e.value && e.value.latest;
    return latest && latest.commit === running ? latest : null;
  };

  // A remote engine: POST /engine/update, then GET /version until it names
  // `:main`'s commit, re-read when it names another. `updating` is "deploying" through the gap.
  const deploy = async (steps, fail) => {
    const d = describeEngineUpdate(st);
    if (!d.lit) return fail("check", d.reason || "the engine already runs main's build");
    const target = st.engine.latest;
    const before = st.engine.running || null;
    st.updating = "deploying";
    try {
      const r = await get(`${engineUrl}/engine/update`, { accept: "application/json", ...auth() }, "POST");
      if (r.error || !r.ok) return fail("deploy", r.error || `engine answered ${r.status}: ${firstLine(r.text) || "no body"}`);
      steps.push({ step: "deploy", ok: true });
      log(`engine update: the engine is restarting onto ${buildLabel(target)}`);
      const deadline = now() + waitMs;
      while (now() < deadline) {
        await sleep(pollMs);
        const v = await readVersion();
        const running = v.value && v.value.commit;
        const now_ = running && (running === target.commit ? target : await retarget(running));
        if (now_) {
          steps.push({ step: "version", ok: true });
          if (now_ !== target) log(`engine update: the engine came up on ${buildLabel(now_)}, :main's build since ${buildLabel(target)}`);
          return { ok: true, steps, build: { commit: now_.commit, digest: now_.digest || null } };
        }
        // Any other commit than the one that ran before the deploy means the
        // engine restarted onto a build it was not sent to: the deploy is over, whatever
        // /engine/update says about :main (#2585). Keeping "deploying" until
        // the wait ran out left the bar on it for minutes.
        if (running && before && running !== before) {
          return fail("version", `GET /version named ${running.slice(0, 7)}, not ${buildLabel(target)} or :main's build`);
        }
      }
      return fail("version", `GET /version did not name ${buildLabel(target)} within ${waitMs / 1000}s`);
    } finally {
      st.updating = null;
      check({ fresh: true }).catch(() => {});
    }
  };

  // The running build as the overlay's "Engine answering" step reads it, once:
  // GET /version's commit and the engine container's digest, the same two
  // reads check() makes. Never retried here; the caller asks on an event.
  const verify = async () => {
    const [v, r] = await Promise.all([readVersion(), readRunning()]);
    const digest = r.value ? r.value.digest : null;
    return { commit: v.value ? v.value.commit || null : null, digest, error: v.error || (digest ? null : r.error) || null };
  };
  // GET /metrics once: `{ value }` live runs, or `{ error }`.
  const liveRuns = () => readLiveRuns();

  return { state: () => ({ ...st }), check, update, verify, liveRuns, CHECK_MS };
}

/** The real `run` for main.js, for one-shot reads: execFile, never rejects. */
function execRun(cmd, args, { timeoutMs = 60000, cwd, env = process.env } = {}) {
  const { execFile } = require("node:child_process");
  return new Promise((resolve) => {
    execFile(cmd, args, { timeout: timeoutMs, cwd, env, maxBuffer: 16 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err && typeof err.code !== "number") resolve({ code: null, stdout: String(stdout || ""), stderr: String(stderr || ""), error: err.killed ? `timed out after ${timeoutMs / 1000}s` : err.message });
      else resolve({ code: err ? err.code : 0, stdout: String(stdout || ""), stderr: String(stderr || "") });
    });
  });
}

module.exports = {
  createEngineUpdater, execRun, runner, RUNNER, STATUS_FILE, LOG_FILE, parseRepoDigest, compareDigests, countLiveRuns, liveRunsPhrase, buildLabel,
  describeEngineUpdate, engineUpdateTitle, reportUpdate, localEngine, remoteEngine, sessionsPhrase,
  CHECK_MS, REF, REGISTRY, REPO, REVISION_LABEL,
};
