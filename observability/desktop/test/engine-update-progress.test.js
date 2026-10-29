// engine-update-progress.test.js — the Update engine overlay (#1053) against
// stand-in streams and reads, no real docker: each of the five steps is
// ticked only by its own event, and a source that never sends it leaves the
// step pending; the failure views (a failed pull, an engine that never
// answers /version); the resume at the status file's step; the quit prompt
// only while an update is in flight; the overlay's drawing; and the status
// bar's "restarting for update" against red with no update in flight.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { EventEmitter } = require("node:events");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const {
  createUpdateProgress, createQuitGuard, parseDockerEvent, eventArgs, isAvailableBuild, liveRunsText, writeView, readView, clearView,
} = require("../src/engine-update-progress");
const { STATUS_FILE, LOG_FILE } = require("../src/engine-update");
const { UpdateOverlay, overlayTitle } = require("../dist/update-overlay.cjs");
const { StatusBar, engineDot } = require("../dist/status-bar.cjs");
const ev = require("../dist/engine-events.cjs");

const D = (c) => `sha256:${c.repeat(64)}`;
const OLD = D("a");
const NEW = D("b");
const NEW_SHA = "2222222bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const BEFORE = { available: { commit: NEW_SHA, digest: NEW }, running: { digest: OLD }, liveRuns: 3 };
const NEW_ID = "c0ffee000000111122223333444455556666777788889999aaaabbbbccccdddd";
const tick = () => new Promise((r) => setImmediate(r));

/** The stand-ins: userData, the watcher, docker's two streams, /version and /metrics. */
const rig = ({ verify, metrics, status, log } = {}) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "uo-"));
  if (status) fs.writeFileSync(path.join(dir, STATUS_FILE), JSON.stringify(status));
  if (log != null) fs.writeFileSync(path.join(dir, LOG_FILE), log);
  let onWatch = null;
  const children = [];
  const spawn = (cmd, args) => {
    const ch = new EventEmitter();
    ch.stdout = new EventEmitter();
    ch.stderr = new EventEmitter();
    ch.killed = false;
    ch.kill = () => { ch.killed = true; };
    ch.cmd = cmd; ch.args = args;
    children.push(ch);
    return ch;
  };
  const asked = { verify: 0, metrics: 0 };
  const changes = [];
  const p = createUpdateProgress({
    dir, docker: "/opt/docker", project: "frakworks", since: 1700000000000, before: BEFORE, resumed: !!status,
    spawn, watch: (d, cb) => { onWatch = cb; return { close() {} }; },
    verify: async () => { asked.verify++; return verify ? verify() : { commit: NEW_SHA, digest: NEW }; },
    metrics: async () => { asked.metrics++; return metrics ? metrics() : { value: 3 }; },
    onChange: (s) => changes.push(s),
  });
  const events = () => children.find((c) => c.args[0] === "events");
  const logs = () => children.find((c) => c.args[0] === "logs");
  return {
    p, dir, children, asked, changes, events, logs,
    stateOf: (id) => p.state().steps.find((s) => s.id === id).state,
    writeStatus: (s) => { fs.writeFileSync(path.join(dir, STATUS_FILE), JSON.stringify(s)); onWatch("change", STATUS_FILE); },
    appendLog: (text) => { fs.appendFileSync(path.join(dir, LOG_FILE), text); onWatch("change", LOG_FILE); },
    event: (action, id = NEW_ID, name = "frakworks-engine-1") =>
      events().stdout.emit("data", JSON.stringify({ Type: "container", Action: action, id, Actor: { ID: id, Attributes: { name } } }) + "\n"),
    containerLine: (line) => logs().stdout.emit("data", line + "\n"),
  };
};

test("parseDockerEvent keeps the container actions the overlay shows and drops the rest", () => {
  assert.deepEqual(parseDockerEvent(JSON.stringify({ Type: "container", Action: "start", id: "abc", Actor: { Attributes: { name: "e-1" } } })),
    { action: "start", id: "abc", name: "e-1" });
  assert.equal(parseDockerEvent(JSON.stringify({ Type: "container", Action: "exec_start: sh", id: "abc" })), null);
  assert.equal(parseDockerEvent(JSON.stringify({ Type: "network", Action: "connect" })), null);
  assert.equal(parseDockerEvent("not json"), null);
  for (const a of ["create", "kill", "die", "destroy", "rename", "start"]) assert.equal(parseDockerEvent(JSON.stringify({ Action: a, id: "x" })).action, a);
});

test("docker events is a stream filtered to the engine's compose project and service, from the update's start", () => {
  const a = eventArgs("frakworks", 1700000000000);
  assert.equal(a[0], "events");
  assert.ok(a.includes("label=com.docker.compose.service=engine"));
  assert.ok(a.includes("label=com.docker.compose.project=frakworks"));
  assert.equal(a[a.indexOf("--since") + 1], "1699999999");
  assert.ok(!a.includes("--until"), "a stream, not a one-shot read");
});

test("the whole update: each step ticked by its own event, in order, ending on the new build", async () => {
  const r = rig({ log: "" });
  assert.deepEqual(r.p.state().steps.map((s) => s.state), ["running", "pending", "pending", "pending", "pending"]);
  assert.equal(r.events().cmd, "/opt/docker", "docker is the binary docker-bin resolved");
  // pull: the log's lines show while it runs; the status file's done list ticks it.
  r.appendLog("== pull: docker compose pull engine\n engine Pulling \n 3f2a Downloading [=====>   ] 12MB/40MB\n");
  assert.equal(r.stateOf("pull"), "running");
  assert.match(r.p.state().steps[0].tail.join("\n"), /Downloading/);
  r.writeStatus({ pid: 1, step: "up", done: ["pull"], code: null });
  assert.equal(r.stateOf("pull"), "done");
  assert.equal(r.stateOf("replace"), "running");
  // replace: docker events as they arrive, ticked on the new container's start.
  for (const a of ["create", "kill", "die", "destroy", "rename"]) r.event(a);
  assert.equal(r.stateOf("replace"), "running");
  assert.deepEqual(r.p.state().steps[1].tail.slice(-2), [`destroy frakworks-engine-1 ${NEW_ID.slice(0, 12)}`, `rename frakworks-engine-1 ${NEW_ID.slice(0, 12)}`]);
  r.event("start");
  assert.equal(r.stateOf("replace"), "done");
  assert.equal(r.stateOf("starting"), "running");
  assert.deepEqual(r.logs().args, ["logs", "-f", NEW_ID], "the new container's log, followed");
  // starting: Flyway lines kept, ticked on Spring's Started line.
  r.containerLine("INFO o.f.core.internal.command.DbMigrate : Migrating schema \"public\" to version \"88 - add view\"");
  r.containerLine("INFO o.f.core.internal.command.DbMigrate : Successfully applied 1 migration to schema \"public\"");
  assert.equal(r.stateOf("starting"), "running");
  assert.equal(r.asked.verify, 0, "/version is not asked before the Started line");
  assert.deepEqual(r.p.state().steps[2].notes, ["Migrating schema \"public\" to version \"88 - add view\"", "Successfully applied 1 migration to schema \"public\""],
    "the Flyway lines, without Spring's prefix");
  r.containerLine("INFO org.frakworks.engine.EngineApplication : Started EngineApplication in 14.2 seconds (process running for 15.1)");
  assert.equal(r.stateOf("starting"), "done");
  await tick(); await tick();
  // answering, then runs.
  assert.equal(r.asked.verify, 1);
  assert.equal(r.stateOf("answering"), "done");
  assert.equal(r.asked.metrics, 1);
  assert.equal(r.stateOf("runs"), "done");
  const st = r.p.state();
  assert.equal(st.steps[4].detail, "3 live runs before, 3 after");
  assert.deepEqual(st.ended, { ok: true, text: "engine updated to sha-2222222" });
  assert.equal(st.inFlight, false);
  assert.ok(r.children.every((c) => c.killed), "the streams stop at the end");
});

test("a source that never sends its event leaves its step pending", async () => {
  // No `pull` in done: pull stays running, nothing after it moves.
  const a = rig({ log: "" });
  a.appendLog("== pull: docker compose pull engine\n layer Downloading\n");
  assert.equal(a.stateOf("pull"), "running");
  assert.equal(a.stateOf("replace"), "pending");
  // Events, but never a start: replace stays running, starting pending, no docker logs.
  const b = rig({ log: "" });
  b.writeStatus({ pid: 1, step: "up", done: ["pull"], code: null });
  b.event("create"); b.event("kill"); b.event("die");
  assert.equal(b.stateOf("replace"), "running");
  assert.equal(b.stateOf("starting"), "pending");
  assert.equal(b.logs(), undefined);
  // The container's log, but never "Started": /version is never asked.
  const c = rig({ log: "" });
  c.writeStatus({ pid: 1, step: "up", done: ["pull"], code: null });
  c.event("start");
  c.containerLine("INFO Flyway Community Edition by Redgate");
  await tick();
  assert.equal(c.stateOf("starting"), "running");
  assert.equal(c.stateOf("answering"), "pending");
  assert.equal(c.asked.verify, 0);
  // /version answers an old build: answering is not ticked; metrics never asked.
  const d = rig({ log: "", verify: () => ({ commit: NEW_SHA, digest: null, error: "no container" }) });
  d.writeStatus({ pid: 1, step: "up", done: ["pull"], code: null });
  d.event("start");
  d.containerLine("Started EngineApplication in 9.1 seconds");
  await tick(); await tick();
  assert.equal(d.stateOf("answering"), "running");
  assert.equal(d.asked.metrics, 0);
  assert.equal(d.stateOf("runs"), "pending");
  // /metrics never answers: runs stays running.
  const e = rig({ log: "", metrics: () => new Promise(() => {}) });
  e.writeStatus({ pid: 1, step: "up", done: ["pull"], code: null });
  e.event("start");
  e.containerLine("Started EngineApplication in 9.1 seconds");
  await tick(); await tick();
  assert.equal(e.stateOf("answering"), "done");
  assert.equal(e.stateOf("runs"), "running");
  for (const x of [a, b, c, d, e]) assert.equal(x.p.state().inFlight, true);
});

test("a failed pull: the step fails with the status file's detail, and the running engine was left as it is", () => {
  const r = rig({ log: "" });
  r.appendLog("== pull: docker compose pull engine\nError response from daemon: manifest unknown\n== pull failed: exit 1: Error response from daemon: manifest unknown\n");
  r.writeStatus({ pid: 1, step: "pull", done: [], code: 1, failedStep: "pull", detail: "exit 1: Error response from daemon: manifest unknown" });
  const st = r.p.state();
  assert.equal(st.steps[0].state, "failed");
  assert.equal(st.steps[0].detail, "exit 1: Error response from daemon: manifest unknown");
  assert.equal(st.ended.ok, false);
  assert.equal(st.ended.leftAsIs, true);
  assert.equal(st.inFlight, false);
  assert.deepEqual(st.steps.slice(1).map((s) => s.state), ["pending", "pending", "pending", "pending"]);
  const html = renderToStaticMarkup(React.createElement(UpdateOverlay, { progress: st, onClose() {} }));
  assert.match(html, /Pulling the image failed: exit 1: Error response from daemon: manifest unknown/);
  assert.match(html, /The running engine was left as it is\./);
  assert.match(html, />Close</);
  assert.match(html, /data-step="pull" data-state="failed"/);
});

test("an engine that never answers /version: the update's wait fails the Engine answering step", async () => {
  const r = rig({ log: "", verify: () => ({ error: "connect ECONNREFUSED 127.0.0.1:8090" }) });
  r.writeStatus({ pid: 1, step: "up", done: ["pull"], code: null });
  r.event("start");
  r.containerLine("Started EngineApplication in 9.1 seconds");
  await tick(); await tick();
  assert.equal(r.stateOf("answering"), "running", "a refused /version is shown, not guessed at");
  assert.match(r.p.state().steps[3].tail.join("\n"), /ECONNREFUSED/);
  r.p.result({ ok: false, steps: [{ step: "pull", ok: true }, { step: "up", ok: true },
    { step: "wait for engine", ok: false, detail: "the engine did not answer /version within 180s: connect ECONNREFUSED 127.0.0.1:8090" }] });
  const st = r.p.state();
  assert.equal(st.steps[3].state, "failed");
  assert.match(st.steps[3].detail, /did not answer \/version within 180s/);
  assert.equal(st.ended.ok, false);
  assert.equal(st.ended.leftAsIs, false);
  const html = renderToStaticMarkup(React.createElement(UpdateOverlay, { progress: st, onClose() {} }));
  assert.match(html, /Engine answering failed/);
  assert.doesNotMatch(html, /left as it is/);
  assert.match(html, />Close</);
});

test("the update's own result ticks Engine answering only on the available build and digest", async () => {
  const ok = rig({ log: "" });
  ok.p.result({ ok: true, steps: [], build: { commit: NEW_SHA, digest: NEW } });
  await tick(); await tick();
  assert.equal(ok.stateOf("answering"), "done");
  assert.equal(ok.p.state().ended.ok, true);
  const wrong = rig({ log: "" });
  wrong.p.result({ ok: true, steps: [], build: { commit: "9999999", digest: OLD } });
  assert.equal(wrong.stateOf("answering"), "failed");
  assert.match(wrong.p.state().steps[3].detail, new RegExp(OLD));
  assert.equal(isAvailableBuild({ commit: NEW_SHA, digest: NEW }, BEFORE.available), true);
  assert.equal(isAvailableBuild({ commit: NEW_SHA, digest: OLD }, BEFORE.available), false);
  assert.equal(isAvailableBuild({ commit: "x", digest: NEW }, BEFORE.available), false);
  assert.equal(isAvailableBuild({ commit: "x", digest: NEW }, { digest: NEW, commit: null }), true, "no commit label: the digest decides");
  assert.equal(liveRunsText(3, 3), "3 live runs before, 3 after");
  assert.equal(liveRunsText(1, 1), "1 live run before, 1 after");
  assert.equal(liveRunsText(null, 2), "? live runs before, 2 after");
});

test("resumed on relaunch: the overlay opens at the status file's step with earlier steps ticked and the sources attached", () => {
  const r = rig({
    status: { pid: 42, step: "up", done: ["pull"], code: null, startedAt: "2023-11-14T22:13:20.000Z" },
    log: "== pull: docker compose pull engine\n engine Pulled\n== up: docker compose up -d --no-deps engine\n Container frakworks-engine-1 Recreate\n",
  });
  const st = r.p.state();
  assert.equal(st.resumed, true);
  assert.deepEqual(st.steps.map((s) => s.state), ["done", "running", "pending", "pending", "pending"]);
  assert.match(st.steps[0].tail.join("\n"), /engine Pulled/, "the log is read from where the update is");
  const a = r.events().args;
  assert.equal(a[a.indexOf("--since") + 1], "1699999999", "docker events replays from the update's start");
  // The replayed start event attaches the container log.
  r.event("start");
  assert.deepEqual(r.logs().args, ["logs", "-f", NEW_ID]);
  // Resumed during the pull: only pull running.
  const p = rig({ status: { pid: 42, step: "pull", done: [], code: null }, log: "== pull: docker compose pull engine\n" });
  assert.deepEqual(p.p.state().steps.map((s) => s.state), ["running", "pending", "pending", "pending", "pending"]);
});

test("the view file keeps what the click saw across a relaunch", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "uo-view-"));
  assert.equal(readView(dir), null);
  writeView(dir, { before: BEFORE, since: 5, project: "frakworks" });
  assert.deepEqual(readView(dir), { before: BEFORE, since: 5, project: "frakworks" });
  clearView(dir);
  assert.equal(readView(dir), null);
});

test("the quit prompt appears only while an update is in flight; Stay cancels, Quit anyway proceeds", async () => {
  let flying = false;
  const asked = [];
  let answer = 1;
  const g = createQuitGuard({ inFlight: () => flying, ask: async (opts, win) => { asked.push({ opts, win }); return { response: answer }; } });
  for (const what of ["quit", "close", "restart"]) assert.equal(await g.confirm(what), true);
  assert.equal(asked.length, 0, "no prompt with no update in flight");
  assert.equal(g.needed(), false);
  flying = true;
  assert.equal(g.needed(), true);
  answer = 1;
  assert.equal(await g.confirm("quit", "win"), false, "Stay cancels");
  answer = 0;
  assert.equal(await g.confirm("restart"), true, "Quit anyway proceeds");
  assert.equal(await g.confirm("close"), true);
  assert.equal(asked.length, 3);
  const o = asked[0].opts;
  assert.equal(asked[0].win, "win");
  assert.deepEqual(o.buttons, ["Quit anyway", "Stay"]);
  assert.equal(o.cancelId, 1);
  assert.match(o.message, /still running/);
  assert.match(o.detail, /continues without the app/);
  // main.js wires the guard to all three paths.
  const main = fs.readFileSync(path.join(__dirname, "..", "src", "main.js"), "utf8");
  assert.match(main, /app\.on\("before-quit", \(e\) => \{\s*if \(!quitGuard\.beforeQuit\(e, /);
  assert.match(main, /win\.on\("close", \(e\) => \{[\s\S]*?quitGuard\.confirm\("close", win\)/);
  assert.match(main, /ipcMain\.handle\("app:restart", \(e\) => quitGuard\.restart\(/);
});

test("Restart while an update is in flight prompts exactly once, and its quit passes before-quit", async () => {
  const asked = [];
  let answer = 0;
  const g = createQuitGuard({ inFlight: () => true, ask: async (opts) => { asked.push(opts); return { response: answer }; } });
  // before-quit as main.js wires it: prevented and asked unless the guard lets it through.
  const quits = [];
  const beforeQuit = () => {
    const e = { prevented: false, preventDefault() { this.prevented = true; } };
    g.beforeQuit(e, null, () => quits.push("quit"));
    return e;
  };
  // Packaged Restart: quitAndInstall quits from inside the action.
  let seen = null;
  const r = await g.restart(null, async () => { seen = beforeQuit(); return { ok: true }; });
  assert.equal(r.ok, true);
  assert.equal(asked.length, 1, "one prompt for Restart");
  assert.equal(seen.prevented, false, "the install's quit is not prevented");
  await tick();
  assert.equal(asked.length, 1, "before-quit did not ask again");
  assert.equal(g.quitting(), true);
});

test("Restart that does not quit, or that the user stays on, leaves the next quit asking", async () => {
  const asked = [];
  let answer = 0;
  const g = createQuitGuard({ inFlight: () => true, ask: async (opts) => { asked.push(opts); return { response: answer }; } });
  // Nothing to install: the action returns not ok, the app keeps running.
  assert.equal((await g.restart(null, async () => ({ ok: false, steps: [] }))).ok, false);
  assert.equal(g.quitting(), false);
  answer = 1;
  assert.deepEqual(await g.restart(null, async () => { throw new Error("not run"); }), { cancelled: true }, "Stay cancels");
  assert.equal(g.quitting(), false);
  assert.equal(asked.length, 2);
  // Cmd+Q now asks; Quit anyway quits once and the second before-quit passes.
  answer = 0;
  const quits = [];
  const e = { prevented: false, preventDefault() { this.prevented = true; } };
  assert.equal(g.beforeQuit(e, null, () => quits.push("quit")), false);
  assert.equal(e.prevented, true);
  await tick(); await tick();
  assert.deepEqual(quits, ["quit"]);
  assert.equal(asked.length, 3);
  const e2 = { prevented: false, preventDefault() { this.prevented = true; } };
  assert.equal(g.beforeQuit(e2, null, () => quits.push("again")), true);
  assert.equal(e2.prevented, false);
  assert.equal(asked.length, 3);
});

test("the overlay draws the steps, the current step's raw lines, and Close only at the end", () => {
  const r = rig({ log: "" });
  r.appendLog("== pull: docker compose pull engine\n 3f2a Downloading 12MB/40MB\n");
  let st = r.p.state();
  assert.equal(overlayTitle(st), "Updating the engine");
  let html = renderToStaticMarkup(React.createElement(UpdateOverlay, { progress: st }));
  for (const label of ["Pulling the image", "Replacing the container", "Engine starting", "Engine answering", "Live runs followed again"]) assert.ok(html.includes(label), label);
  assert.match(html, /aria-modal="true"/);
  assert.match(html, /<pre class="uoTail">[^<]*Downloading 12MB\/40MB/);
  assert.doesNotMatch(html, />Close</, "no Close while the update runs");
  st = { ...st, ended: { ok: true, text: "engine updated to sha-2222222" }, inFlight: false };
  html = renderToStaticMarkup(React.createElement(UpdateOverlay, { progress: st }));
  assert.match(html, /engine updated to sha-2222222/);
  assert.match(html, />Close</);
  assert.equal(renderToStaticMarkup(React.createElement(UpdateOverlay, { progress: null })), "");
});

test("the running step's mark carries the class the CSS spins; pending/done/failed steps don't", () => {
  const r = rig({ log: "" });
  const st = r.p.state();
  assert.equal(st.steps[0].state, "running");
  const html = renderToStaticMarkup(React.createElement(UpdateOverlay, { progress: st }));
  assert.match(html, /<li class="uoStep uo-running" data-step="pull" data-state="running">[\s\S]*?class="uoMark"/);
  for (const other of st.steps.slice(1)) {
    assert.equal(other.state, "pending");
    assert.ok(!html.includes(`data-step="${other.id}" data-state="running"`), `${other.id} is not running`);
  }
  r.writeStatus({ pid: 1, step: "up", done: ["pull"], code: null });
  const doneSt = r.p.state();
  assert.equal(doneSt.steps[0].state, "done");
  const doneHtml = renderToStaticMarkup(React.createElement(UpdateOverlay, { progress: doneSt }));
  assert.match(doneHtml, /<li class="uoStep uo-done" data-step="pull" data-state="done">/, "the finished pull step is no longer uo-running");
});

test("the connectivity indicator: restarting for update, muted, during an update; red when the engine is down with none", () => {
  const down = { repo: "*", state: "down", since: 1000, lastFrameAt: 1000 };
  const engine = { ok: false, error: "fetch failed", url: "http://engine.test" };
  const html = (restarting) => renderToStaticMarkup(React.createElement(StatusBar, { version: null, engine, stream: down, now: 5000, restarting }));
  const during = html(true);
  assert.match(during, /class="sbEngine sb-muted"[^>]*>engine ◌ restarting for update engine.test/);
  assert.match(during, /class="sbStream sb-muted"[^>]*>stream ◌ restarting for update/);
  assert.doesNotMatch(during, /sb-bad/);
  const after = html(false);
  assert.match(after, /class="sbEngine sb-bad"[^>]*>engine ○ engine.test/);
  assert.match(after, /class="sbStream sb-bad"[^>]*>stream ○ down/);
  assert.equal(engineDot(engine, false).level, "bad");
  assert.equal(engineDot({ ok: true, url: "http://engine.test" }, false).text, "engine ● engine.test");
  // The stale line, and the stream's phrase, outside the bar.
  assert.equal(ev.staleText(down, true), "engine restarting for update; the views catch up when it is back");
  assert.match(ev.staleText(down, false), /^stale since/);
  assert.equal(ev.streamText({ state: "live" }, 0, true).text, "stream ● live", "a live stream is shown as live");
  assert.equal(ev.streamText(down, 5000, false).level, "bad");
});

test("the Issues and Needs views draw an engine-unreachable error as the restart during an update, and as a failure without one", () => {
  const { Issues } = require("../dist/issues.cjs");
  const { NeedsYou } = require("../dist/needs-you.cjs");
  const err = { error: "engine unreachable: fetch failed" };
  const issues = (restarting) => renderToStaticMarkup(React.createElement(Issues, { result: err, restarting }));
  const needs = (restarting) => renderToStaticMarkup(React.createElement(NeedsYou, { result: err, restarting }));
  for (const h of [issues(true), needs(true)]) {
    assert.match(h, /class="nmEmpty restarting"/);
    assert.match(h, /restarting for update; this view reloads when the engine is back/);
    assert.doesNotMatch(h, /class="err"/);
  }
  for (const h of [issues(false), needs(false)]) {
    assert.match(h, /class="err">engine unreachable: fetch failed/);
    assert.doesNotMatch(h, /restarting/);
  }
});

// #1398: what the update brings rides on `before` and stays in the end state.
test("the overlay lists the update's PRs during and after it, or says why they are missing", () => {
  const changes = { changes: [{ sha: "c", title: "Engine: a thing", pr: 7, prUrl: "https://github.com/o/r/pull/7",
    tickets: [{ n: 5, repo: "o/r", url: "frakworks://ticket/o/r/5" }] }] };
  const steps = [{ id: "pull", label: "Pull", source: "", state: "done", detail: "", tail: [], notes: [] }];
  for (const ended of [null, { ok: true, text: "engine updated to sha-2222222" }]) {
    const html = renderToStaticMarkup(React.createElement(UpdateOverlay, { progress: { steps, before: { changes }, ended } }));
    assert.match(html, /What this update brings/);
    assert.match(html, /href="https:\/\/github.com\/o\/r\/pull\/7"/);
    assert.match(html, /href="frakworks:\/\/ticket\/o\/r\/5"/);
  }
  const missing = renderToStaticMarkup(React.createElement(UpdateOverlay, { progress: { steps, before: { changes: { error: "no network" } }, ended: null } }));
  assert.match(missing, /The list of PRs is missing: no network/);
});
