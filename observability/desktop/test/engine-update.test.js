// engine-update.test.js — the Update engine button (src/engine-update.js)
// against stand-in docker, registry and engine calls: the digest comparison,
// the status phrase and level, the live-run wording, the confirm, the success
// and failure reports, and that nothing but update() ever runs compose. The
// update runs in the detached runner (#1040): its spawn, its status file,
// the resume after the app goes away, the stale file and the Created backstop.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { previewModel } = require("../src/update-preview");
const {
  createEngineUpdater, parseRepoDigest, compareDigests, countLiveRuns, liveRunsPhrase, describeEngineUpdate,
  engineUpdateTitle, reportUpdate, localEngine, remoteEngine, CHECK_MS, RUNNER, STATUS_FILE, LOG_FILE, runner,
} = require("../src/engine-update");

// The registry is a deployment value (src/deployment-config.js); the tests
// hand this one to createEngineUpdater the way main hands the configured one.
const REGISTRY = "registry.example:5000";
const REF = `${REGISTRY}/frakworks-engine:main`;

const D = (c) => `sha256:${c.repeat(64)}`;
const OLD = D("a");
const NEW = D("b");
const OLD_SHA = "1111111aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const NEW_SHA = "2222222bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const ENGINE = "http://127.0.0.1:8090";
const METRICS = [
  "# HELP smith_run_active 1 while a Smith run is live",
  "# TYPE smith_run_active gauge",
  'smith_run_active{host="engine",issue="12",model="claude-opus-5-5",kind="job",repo="a/b"} 1',
  'smith_run_active{host="engine",issue="13",model="claude-opus-5-5",kind="review",repo="a/b"} 1',
  "# TYPE smith_fleet_last_scrape_timestamp_seconds gauge",
  'smith_fleet_last_scrape_timestamp_seconds{host="engine"} 1.7e9',
  "",
].join("\n");

// ---------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------

test("parseRepoDigest picks the registry repo's digest and nothing else", () => {
  const repo = `${REGISTRY}/frakworks-engine`;
  assert.equal(parseRepoDigest([`other.example:5000/frakworks-engine@${NEW}`, `${REGISTRY}/frakworks-engine@${OLD}`], repo), OLD);
  assert.equal(parseRepoDigest([`${REGISTRY}/frakworks-smith@${OLD}`], repo), null);
  assert.equal(parseRepoDigest([`localhost:5000/frakworks-engine@${OLD}`]), OLD, "the default repo is the localhost registry");
  assert.equal(parseRepoDigest([]), null);
  assert.equal(parseRepoDigest(null), null);
});

test("compareDigests: same, differs, and unknown when either side is missing", () => {
  assert.equal(compareDigests(OLD, OLD), "same");
  assert.equal(compareDigests(OLD, NEW), "differs");
  assert.equal(compareDigests(null, NEW), "unknown");
  assert.equal(compareDigests(OLD, null), "unknown");
  assert.equal(compareDigests("sha256:short", NEW), "unknown");
});

test("countLiveRuns counts smith_run_active samples only", () => {
  assert.equal(countLiveRuns(METRICS), 2);
  assert.equal(countLiveRuns(METRICS.split("\n").filter((l) => !l.includes('issue="13"')).join("\n")), 1);
  const three = METRICS + 'smith_run_active{host="engine",issue="14",model="m",kind="job",repo="a/b"} 1\n';
  assert.equal(countLiveRuns(three), 3);
  assert.equal(countLiveRuns("# HELP smith_run_active x\n# TYPE smith_run_active gauge\nsmith_fleet_last_scrape_timestamp_seconds{host=\"engine\"} 1\n"), 0);
  assert.equal(countLiveRuns(""), 0);
});

test("liveRunsPhrase: zero, one, many and unknown, each naming SmithTelemetryResume", () => {
  assert.match(liveRunsPhrase(0), /^No Smith runs are live; a live run would survive the engine restart/);
  assert.match(liveRunsPhrase(1), /^1 Smith run is live; it survives the engine restart/);
  assert.match(liveRunsPhrase(3), /^3 Smith runs are live; they survive the engine restart/);
  assert.match(liveRunsPhrase(null, "engine /metrics: ECONNREFUSED"), /unknown \(engine \/metrics: ECONNREFUSED\); live runs survive/);
  for (const n of [0, 1, 3, null]) assert.match(liveRunsPhrase(n), /SmithTelemetryResume/);
});

const state = (over = {}) => ({
  ref: REF, checking: false, checkedAt: 1700000000000, updating: null,
  running: { commit: OLD_SHA, digest: OLD, image: REF, project: { name: "frakworks", workingDir: "/Users/u/frakworks", configFiles: ["/Users/u/frakworks/compose.yaml"] } },
  runningError: null,
  available: { commit: NEW_SHA, digest: NEW }, availableError: null,
  liveRuns: 2, liveRunsError: null,
  ...over,
});

test("describeEngineUpdate: lit only when both digests are known and differ", () => {
  assert.deepEqual(describeEngineUpdate(state()), { text: "engine sha-1111111 · main sha-2222222", level: "stale", lit: true, reason: "" });
  assert.deepEqual(describeEngineUpdate(state({ available: { commit: OLD_SHA, digest: OLD } })),
    { text: "engine sha-1111111 · main", level: "ok", lit: false, reason: "" });
  const checking = describeEngineUpdate(state({ checkedAt: null, checking: true }));
  assert.equal(checking.lit, false);
  assert.equal(checking.level, "muted");
  assert.equal(describeEngineUpdate(null).lit, false);
  // Every unknown is `bad` with the reason, and never lit.
  const cases = {
    registry: state({ available: null, availableError: "registry unreachable (x): ECONNREFUSED" }),
    "no container": state({ running: { commit: OLD_SHA }, runningError: "no running compose \"engine\" container publishes port 8090 on this Docker host" }),
    "local build": state({ running: { commit: null, digest: null, image: "frakworks-engine:local" }, runningError: "the engine runs frakworks-engine:local, not registry.example:5000/frakworks-engine:main" }),
    "remote engine": state({ running: null, runningError: "the engine at http://engine.example:8090 is not on this machine" }),
    "digest missing": state({ available: { commit: NEW_SHA, digest: null } }),
  };
  for (const [name, s] of Object.entries(cases)) {
    const d = describeEngineUpdate(s);
    assert.equal(d.lit, false, name);
    assert.equal(d.level, "bad", name);
    assert.ok(d.reason, name);
  }
  assert.equal(describeEngineUpdate(cases.registry).text, "engine sha-1111111 · main unknown: registry unreachable (x): ECONNREFUSED");
  assert.equal(describeEngineUpdate(cases["no container"]).text, "engine sha-1111111 · build unknown");
  const busy = describeEngineUpdate(state({ updating: "pull" }));
  assert.equal(busy.lit, false);
  assert.match(busy.text, /updating: pull…/);
});

test("engineUpdateTitle names both builds, the registry reference and the last check", () => {
  const t = engineUpdateTitle(state());
  assert.match(t, new RegExp(`running +${OLD_SHA} ${OLD}`));
  assert.match(t, new RegExp(`available ${NEW_SHA} ${NEW}`));
  assert.match(t, /registry +registry\.example:5000\/frakworks-engine:main/);
  assert.match(t, /checked +2023-11-14T22:13:20\.000Z/);
  assert.match(t, /live runs 2/);
  assert.match(engineUpdateTitle(state({ available: null, availableError: "registry answered 404" })), /error +registry answered 404/);
});

test("the update preview states the live-run count, that they survive, and both builds (#100091; was confirmText)", () => {
  const s = state();
  const m = previewModel({ kind: "engine", running: s.running, incoming: s.available, list: { changes: [] }, liveRuns: s.liveRuns, sessions: null });
  assert.equal(m.title, "Update the engine?");
  assert.match(m.lines[0], /2 Smith runs are live; they survive the engine restart \(SmithTelemetryResume/);
  assert.equal(m.header.running, "sha-1111111");
  assert.equal(m.header.incoming, "sha-2222222");
});

test("reportUpdate: success names the new build, failure the step and its error, a check nothing", () => {
  assert.deepEqual(reportUpdate({ ok: true, steps: [], build: { commit: NEW_SHA, digest: NEW } }),
    { text: "engine updated to sha-2222222", level: "ok", detail: `commit ${NEW_SHA}\ndigest ${NEW}` });
  assert.deepEqual(reportUpdate({ ok: false, steps: [{ step: "pull", ok: false, detail: "exit 1: manifest unknown" }] }),
    { text: "engine pull failed", level: "bad", detail: "exit 1: manifest unknown" });
  assert.equal(reportUpdate({ checked: true }), null);
  assert.equal(reportUpdate(null), null);
  assert.deepEqual(reportUpdate({ started: "abc123" }), { text: "engine started (left in Created)", level: "ok", detail: "docker start abc123" });
});

test("localEngine: only an engine on this machine, and its port", () => {
  assert.deepEqual(localEngine("http://127.0.0.1:8090"), { port: "8090" });
  assert.deepEqual(localEngine("http://127.0.0.1:9000"), { port: "9000" });
  assert.match(localEngine("http://engine.example:8090").error, /not on this machine/);
});

// ---------------------------------------------------------------------------
// The updater, against stand-ins
// ---------------------------------------------------------------------------

// A stand-in world: the registry's :main digest, the running container's
// digest, and scripted docker results. `calls` records every docker argv the
// app runs itself (`run`); the compose steps run in the real detached runner
// against `fakeDocker`, a shell script standing in for docker, which appends
// its argv to `composeLog` and, on a good `up`, flips the world to the new
// build. `dir` is a temp userData.
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { EventEmitter } = require("node:events");

function world({ registryDigest = NEW, runningDigest = OLD, image = REF, pull = { code: 0 }, up = { code: 0 },
  registryDown = false, noContainer = false, engineUp = true, created = null, dir = null, opts = {} } = {}) {
  const w = { calls: [], registryDigest, runningDigest, engineUp, commit: OLD_SHA, results: [], logs: [], created };
  w.dir = dir || fs.mkdtempSync(path.join(os.tmpdir(), "engine-update-"));
  w.composeLog = path.join(w.dir, "compose-calls.txt");
  w.upped = path.join(w.dir, "upped");
  w.fakeDocker = path.join(w.dir, "docker");
  // The compose project's directory: the runner runs docker in it.
  w.proj = path.join(w.dir, "frakworks");
  fs.mkdirSync(w.proj, { recursive: true });
  w.base = ["compose", "-p", "frakworks", "--project-directory", w.proj,
    "-f", path.join(w.proj, "compose.yaml"), "-f", path.join(w.proj, "engine", "compose.build.yaml")];
  const step = (r, onOk) => `printf '%s\n' ${JSON.stringify(r.stdout || "")}; printf '%s\n' ${JSON.stringify(r.stderr || "")} >&2; ` +
    `${r.code === 0 && onOk ? `${onOk}; ` : ""}exit ${r.code}`;
  fs.writeFileSync(w.fakeDocker, [
    "#!/bin/sh",
    `echo "$*" >> ${JSON.stringify(w.composeLog)}`,
    'case " $* " in',
    `  *" pull "*) ${step(pull)} ;;`,
    `  *" up "*) ${step(up, `touch ${JSON.stringify(w.upped)}`)} ;;`,
    "esac",
    "exit 1",
    "",
  ].join("\n"), { mode: 0o755 });
  const upped = () => fs.existsSync(w.upped);
  const index = { manifests: [
    { digest: D("c"), platform: { os: "linux", architecture: "amd64" } },
    { digest: D("e"), platform: { os: "unknown", architecture: "unknown" } },
  ] };
  const resp = (status, body, headers = {}) => ({
    ok: status < 300, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)),
    headers: { get: (h) => headers[h.toLowerCase()] || null },
  });
  w.fetch = async (url, opts = {}) => {
    if (url.startsWith("http://registry.example:5000/")) {
      if (registryDown) throw new Error("ECONNREFUSED");
      if (url.endsWith("/manifests/main")) {
        assert.match(opts.headers.accept, /manifest\.list\.v2\+json/);
        return resp(200, index, { "docker-content-digest": w.registryDigest });
      }
      if (url.endsWith(`/manifests/${D("c")}`)) return resp(200, { config: { digest: D("d") } });
      if (url.endsWith(`/blobs/${D("d")}`)) return resp(200, { config: { Labels: { "org.opencontainers.image.revision": NEW_SHA } } });
      return resp(404, "");
    }
    if (url === `${ENGINE}/version`) {
      if (!w.engineUp) throw new Error("ECONNREFUSED");
      return resp(200, { version: "0.0.1", commit: upped() ? NEW_SHA : w.commit });
    }
    if (url === `${ENGINE}/metrics`) return resp(200, METRICS);
    throw new Error(`unexpected fetch ${url}`);
  };
  const labels = {
    "com.docker.compose.service": "engine",
    "com.docker.compose.project": "frakworks",
    "com.docker.compose.project.working_dir": w.proj,
    "com.docker.compose.project.config_files": `${path.join(w.proj, "compose.yaml")},${path.join(w.proj, "engine", "compose.build.yaml")}`,
  };
  w.run = async (cmd, args) => {
    w.calls.push([cmd, ...args]);
    const [a0, a1] = args;
    if (a0 === "ps" && args.includes("-a")) {
      assert.ok(args.includes("status=created"), "the backstop asks only for Created containers");
      return { code: 0, stdout: w.created ? `${w.created.id}\n` : "", stderr: "" };
    }
    if (a0 === "ps") return noContainer ? { code: 0, stdout: "\n", stderr: "" } : { code: 0, stdout: "c0ffee\n", stderr: "" };
    if (a0 === "inspect" && w.created && a1 === w.created.id) {
      return { code: 0, stderr: "", stdout: JSON.stringify([{ Id: `${w.created.id}0000`, State: { Status: "created" }, Config: { Image: image,
        Labels: { ...labels, "com.docker.compose.project": w.created.project || "frakworks" } },
        HostConfig: { PortBindings: { "8080/tcp": [{ HostIp: "", HostPort: w.created.port || "8090" }] } } }]) };
    }
    if (a0 === "inspect") {
      return { code: 0, stderr: "", stdout: JSON.stringify([{ Image: "sha256:imageid", Config: { Image: image, Labels: labels } }]) };
    }
    if (a0 === "image" && a1 === "inspect") {
      return { code: 0, stderr: "", stdout: JSON.stringify([`registry.example:5000/frakworks-engine@${upped() ? w.registryDigest : w.runningDigest}`]) };
    }
    if (a0 === "start" && w.created && a1.startsWith(w.created.id)) { w.created = null; noContainer = false; return { code: 0, stdout: `${a1}\n`, stderr: "" }; }
    return { code: 1, stdout: "", stderr: `unexpected docker ${args.join(" ")}` };
  };
  w.make = (more = {}) => createEngineUpdater({ run: w.run, fetch: w.fetch, engineUrl: ENGINE, docker: w.fakeDocker, registry: REGISTRY,
    now: () => 1700000000000, existsFn: () => true, sleep: async () => {}, pollMs: 1, waitMs: 3, dir: w.dir,
    log: (m) => w.logs.push(m), onResult: (r) => w.results.push(r), ...opts, ...more });
  w.updater = w.make();
  // Only compose's own calls: the runner's post-up network check (ps, inspect) is not one.
  w.composeCalls = () => (fs.existsSync(w.composeLog) ? fs.readFileSync(w.composeLog, "utf8").trim().split("\n").filter((l) => l.startsWith("compose ")) : []);
  w.dockerCompose = () => w.calls.filter((c) => c[1] === "compose");
  w.status = () => (fs.existsSync(path.join(w.dir, STATUS_FILE)) ? JSON.parse(fs.readFileSync(path.join(w.dir, STATUS_FILE), "utf8")) : null);
  w.writeStatus = (s) => fs.writeFileSync(path.join(w.dir, STATUS_FILE), JSON.stringify(s));
  return w;
}

// A stand-in for child_process.spawn: records what it was asked, never runs
// anything; the test plays the runner by writing the status file and
// emitting `exit`.
function standInSpawn() {
  const sp = { calls: [], children: [] };
  sp.spawn = (cmd, args, opts) => {
    const child = new EventEmitter();
    child.pid = 424242;
    child.unrefed = false;
    child.killed = false;
    child.unref = () => { child.unrefed = true; };
    child.kill = () => { child.killed = true; };
    sp.calls.push({ cmd, args, opts });
    sp.children.push(child);
    return child;
  };
  return sp;
}

test("check() fills the state from the registry, docker and the engine, and never runs compose", async () => {
  const w = world();
  assert.equal(w.updater.CHECK_MS, CHECK_MS);
  const s = await w.updater.check();
  assert.deepEqual(s.available, { digest: NEW, commit: NEW_SHA });
  assert.equal(s.running.digest, OLD);
  assert.equal(s.running.commit, OLD_SHA);
  assert.deepEqual(s.running.project, { name: "frakworks", workingDir: w.proj,
    configFiles: [path.join(w.proj, "compose.yaml"), path.join(w.proj, "engine", "compose.build.yaml")] });
  assert.equal(s.liveRuns, 2);
  assert.equal(s.checkedAt, 1700000000000);
  assert.equal(describeEngineUpdate(s).lit, true);
  assert.deepEqual(w.calls[0], [w.fakeDocker, "ps", "-a", "--filter", "label=com.docker.compose.service=engine", "--filter", "status=created", "--format", "{{.ID}}"]);
  assert.deepEqual(w.calls[1], [w.fakeDocker, "ps", "--filter", "label=com.docker.compose.service=engine", "--filter", "publish=8090", "--format", "{{.ID}}"]);
  await w.updater.check();
  assert.deepEqual(w.dockerCompose(), [], "a check never pulls, recreates or restarts");
  assert.deepEqual(w.composeCalls(), []);
  assert.ok(!w.calls.some((c) => c[1] === "start"), "nothing in Created: nothing started");
  assert.deepEqual(w.results, []);
});

test("check(): registry down, no container, a local build, a remote engine are errors, not guesses", async () => {
  let w = world({ registryDown: true });
  let s = await w.updater.check();
  assert.match(s.availableError, /registry unreachable .*ECONNREFUSED/);
  assert.equal(describeEngineUpdate(s).lit, false);
  w = world({ noContainer: true });
  s = await w.updater.check();
  assert.match(s.runningError, /no running compose "engine" container publishes port 8090/);
  assert.equal(s.running.commit, OLD_SHA, "the engine's own commit is still shown");
  assert.equal(describeEngineUpdate(s).level, "bad");
  w = world({ image: "frakworks-engine:local" });
  s = await w.updater.check();
  assert.match(s.runningError, /runs frakworks-engine:local, not registry\.example:5000\/frakworks-engine:main/);
  assert.equal(describeEngineUpdate(s).lit, false);
});

// A remote engine's GET /engine/update and POST /engine/update, as the engine answers them.
const clusterWorld = ({ mode = "kubernetes", latest = { digest: NEW, commit: NEW_SHA }, behind = 3, postStatus = 200, switchAfter = 1 } = {}) => {
  const CLUSTER = "https://engine.k8s.example";
  const asked = [];
  let commit = OLD_SHA, versions = 0, posted = false;
  const fetch = async (url, opts = {}) => {
    asked.push(`${opts.method || "GET"} ${url}`);
    let status = 200, body;
    if (url === `${CLUSTER}/version`) {
      if (posted && ++versions > switchAfter) commit = NEW_SHA;
      body = { version: "0.0.1", commit };
    } else if (url === `${CLUSTER}/metrics`) body = METRICS;
    else if (url === `${CLUSTER}/engine/update` && opts.method === "POST") {
      posted = true; status = postStatus; body = postStatus === 200 ? { namespace: "frakworks", deployment: "engine" } : "the engine is not in a cluster";
    } else if (url === `${CLUSTER}/engine/update`) {
      body = { mode, selfUpdate: mode === "kubernetes", running: commit, image: "ghcr.io/o/frakworks-engine:main",
        latest: mode === "kubernetes" ? latest : null, behind: mode === "kubernetes" && commit !== NEW_SHA ? behind : 0,
        updateAvailable: mode === "kubernetes" && !!latest && commit !== latest.commit, openSessions: 1,
        error: mode === "kubernetes" ? null : "the engine is not in a cluster and cannot replace its own container: its host updates it" };
    } else throw new Error(`unexpected fetch ${url}`);
    return { ok: status === 200, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)), headers: { get: () => null } };
  };
  let t = 0;
  const updater = createEngineUpdater({ run: async () => { throw new Error("must not run docker"); }, fetch, engineUrl: CLUSTER, registry: REGISTRY,
    now: () => (t += 1000), sleep: async () => {}, waitMs: 20000, pollMs: 1000, auth: () => ({ authorization: "Bearer tok" }) });
  return { CLUSTER, asked, updater };
};

test("an engine not on this machine: its own GET /engine/update lights the button, naming the build and how far behind; no docker, no registry", async () => {
  const w = clusterWorld();
  const s = await w.updater.check();
  assert.equal(s.remote, true);
  assert.equal(s.running.commit, OLD_SHA);
  assert.deepEqual(w.asked.sort(), [`GET ${w.CLUSTER}/engine/update`, `GET ${w.CLUSTER}/metrics`, `GET ${w.CLUSTER}/version`], "only the engine is asked");
  assert.deepEqual(describeEngineUpdate(s), { text: "engine sha-1111111 · main sha-2222222 · 3 commits behind", level: "stale", lit: true, remote: true, reason: "" });
  assert.match(engineUpdateTitle(s), /Click to deploy main/);
  assert.doesNotMatch(engineUpdateTitle(s), /cluster rollout/);
  const m = previewModel({ kind: "engine", running: s.running, incoming: s.engine.latest, behind: s.engine.behind, list: { changes: [] },
    liveRuns: s.liveRuns, sessions: s.engine.openSessions, remote: true });
  assert.equal(m.header.incoming, "sha-2222222");
  assert.equal(m.header.behind, "(3 commits behind)");
  assert.match(m.lines.join("\n"), /2 Smith runs are live/);
  assert.match(m.lines.join("\n"), /1 first-mate session is open/);
  assert.match(m.footer, /about 45 s/);
  assert.doesNotMatch(m.footer, /docker|kubectl|Deployment/i);
  assert.equal(remoteEngine("http://127.0.0.1:8090"), false);
  assert.equal(remoteEngine("https://engine.k8s.example"), true);
});

test("deploy: POST /engine/update, \"deploying\" through the gap, then GET /version names the new commit", async () => {
  const w = clusterWorld({ switchAfter: 2 });
  await w.updater.check();
  const pending = w.updater.update();
  assert.equal(w.updater.state().updating, "deploying");
  assert.match(describeEngineUpdate(w.updater.state()).text, /updating: deploying…/);
  const r = await pending;
  assert.deepEqual(r, { ok: true, steps: [{ step: "deploy", ok: true }, { step: "version", ok: true }], build: { commit: NEW_SHA, digest: NEW } });
  assert.ok(w.asked.includes(`POST ${w.CLUSTER}/engine/update`));
  assert.equal(w.updater.state().updating, null);
  assert.deepEqual(reportUpdate(r).text, "engine updated to sha-2222222");
});

test("deploy: a refused POST and a /version that never moves are failures naming their step", async () => {
  let w = clusterWorld({ postStatus: 409 });
  await w.updater.check();
  let r = await w.updater.update();
  assert.equal(r.ok, false);
  assert.equal(r.steps[0].step, "deploy");
  assert.match(r.steps[0].detail, /409/);
  w = clusterWorld({ switchAfter: 1000 });
  await w.updater.check();
  r = await w.updater.update();
  assert.equal(r.ok, false);
  assert.equal(r.steps[r.steps.length - 1].step, "version");
});

test("a remote engine not in a cluster says its host updates it, and the button is not lit", async () => {
  const w = clusterWorld({ mode: "host" });
  const s = await w.updater.check();
  const d = describeEngineUpdate(s);
  assert.equal(d.lit, false);
  assert.equal(d.text, "engine sha-1111111 · its host updates it");
  const r = await w.updater.update();
  assert.equal(r.ok, false);
  assert.ok(!w.asked.some((a) => a.startsWith("POST")), "nothing is posted");
});

test("a remote engine whose registry lookup failed names the error on the bar, and a click fails its check with it", async () => {
  const err = "registry: Unrecognized SSL message, plaintext connection?";
  const s = state({ remote: true, running: { commit: OLD_SHA }, available: null,
    engine: { mode: "kubernetes", selfUpdate: true, latest: null, updateAvailable: false, error: err } });
  assert.deepEqual(describeEngineUpdate(s), { text: `engine sha-1111111 · main unknown: ${err}`, level: "bad", lit: false, remote: true, reason: err });
  assert.match(engineUpdateTitle(s), /Unrecognized SSL message, plaintext connection\?/);
  const long = "registry: " + "x".repeat(200);
  const d = describeEngineUpdate(state({ remote: true, running: { commit: OLD_SHA }, available: null, engine: { selfUpdate: true, latest: null, error: long } }));
  assert.equal(d.text, `engine sha-1111111 · main unknown: ${long.slice(0, 80)}…`);
  assert.equal(d.reason, long);
  const w = clusterWorld({ latest: null });
  await w.updater.check();
  w.updater.state().engine.error = err;
  const r = await w.updater.update();
  assert.deepEqual(r.steps, [{ step: "check", ok: false, detail: err }]);
  assert.ok(!w.asked.some((a) => a.startsWith("POST")), "nothing is posted");
  assert.deepEqual(reportUpdate(r), { text: `engine check failed: ${err}`, level: "bad", detail: err });
});

test("a remote engine that does not answer GET /engine/update is an error, not a guess", () => {
  const d = describeEngineUpdate(state({ remote: true, engine: null, engineError: "engine answered 404 for /engine/update" }));
  assert.equal(d.lit, false);
  assert.equal(d.level, "bad");
  assert.match(d.reason, /404/);
});

test("update(): one detached runner, its output on the log under userData; the parent reports from its exit event", async () => {
  const w = world();
  const sp = standInSpawn();
  const u = w.make({ spawn: sp.spawn, execPath: "/Applications/frakworks.app/Contents/MacOS/frakworks" });
  await u.check();
  const pending = u.update();
  assert.equal(sp.calls.length, 1, "one child for both steps");
  const { cmd, args, opts } = sp.calls[0];
  assert.equal(cmd, "/Applications/frakworks.app/Contents/MacOS/frakworks");
  assert.equal(args[0], "-e");
  assert.equal(args[1], RUNNER);
  const cfg = JSON.parse(args[2]);
  assert.deepEqual(cfg.steps.map((s) => [s.step, s.args]), [["pull", [...w.base, "pull", "engine"]], ["up", [...w.base, "up", "-d", "--no-deps", "engine"]]]);
  assert.equal(cfg.docker, w.fakeDocker);
  assert.equal(cfg.status, path.join(w.dir, STATUS_FILE));
  assert.equal(cfg.log, path.join(w.dir, LOG_FILE));
  assert.equal(opts.detached, true);
  assert.equal(opts.env.ELECTRON_RUN_AS_NODE, "1");
  assert.equal(opts.stdio[0], "ignore");
  assert.equal(typeof opts.stdio[1], "number", "stdout is the log file, not a pipe");
  assert.equal(typeof opts.stdio[2], "number", "stderr is the log file, not a pipe");
  assert.ok(fs.existsSync(cfg.log));
  assert.equal(sp.children[0].unrefed, true);
  assert.equal(u.state().updating, "pull");
  assert.deepEqual(w.dockerCompose(), [], "compose never runs through run");
  // The runner moves on and ends; the parent never polls the file for the end.
  w.writeStatus({ pid: 424242, step: "up", done: ["pull"], code: null });
  fs.writeFileSync(w.upped, "");
  w.writeStatus({ pid: 424242, step: "up", done: ["pull", "up"], code: 0, failedStep: null, detail: null });
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(u.state().updating !== null, true, "still updating until the exit event");
  sp.children[0].emit("exit", 0, null);
  const r = await pending;
  assert.equal(r.ok, true);
  assert.deepEqual(r.steps.map((s) => s.step), ["pull", "up", "wait for engine"]);
  assert.deepEqual(r.build, { commit: NEW_SHA, digest: NEW });
  assert.equal(reportUpdate(r).text, "engine updated to sha-2222222");
  assert.equal(u.state().updating, null);
  assert.equal(w.status(), null, "the status file is cleared once reported");
  assert.deepEqual(w.results, [], "the click's own result goes back to the click, not onResult");
});

test("update(): the real runner pulls, then ups, in the compose project from the container's labels", async () => {
  const w = world({ pull: { code: 0, stdout: "Pulled" }, up: { code: 0, stdout: "Container frakworks-engine-1 Started" } });
  await w.updater.check();
  const r = await w.updater.update();
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.deepEqual(r.steps.map((s) => s.step), ["pull", "up", "wait for engine"]);
  assert.deepEqual(r.build, { commit: NEW_SHA, digest: NEW });
  assert.deepEqual(w.composeCalls(), [[...w.base, "pull", "engine"].join(" "), [...w.base, "up", "-d", "--no-deps", "engine"].join(" ")]);
  const logText = fs.readFileSync(path.join(w.dir, LOG_FILE), "utf8");
  assert.match(logText, /Pulled/);
  assert.match(logText, /Container frakworks-engine-1 Started/);
  assert.equal(describeEngineUpdate(w.updater.state()).lit, false, "after the update the digests match");
  assert.equal(w.status(), null);
});

test("update(): a failed pull reports 'pull' with its last line and never runs up", async () => {
  const w = world({ pull: { code: 18, stderr: "Error response from daemon: manifest unknown" } });
  await w.updater.check();
  const r = await w.updater.update();
  assert.equal(r.ok, false);
  assert.deepEqual(r.steps, [{ step: "pull", ok: false, detail: "exit 18: Error response from daemon: manifest unknown" }]);
  assert.deepEqual(w.composeCalls(), [[...w.base, "pull", "engine"].join(" ")]);
  assert.ok(!w.composeCalls().some((c) => / up /.test(c)), "a failed pull leaves the running engine untouched");
  assert.ok(!fs.existsSync(w.upped));
  assert.equal(w.updater.state().updating, null);
  assert.equal(reportUpdate(r).text, "engine pull failed");
});

test("update(): a failed up reports 'up'; an engine that never answers reports the wait", async () => {
  let w = world({ up: { code: 1, stderr: "port is already allocated" } });
  await w.updater.check();
  let r = await w.updater.update();
  assert.equal(r.ok, false);
  assert.deepEqual(r.steps.map((s) => [s.step, s.ok]), [["pull", true], ["up", false]]);
  assert.equal(r.steps[1].detail, "exit 1: port is already allocated");
  w = world();
  await w.updater.check();
  w.engineUp = false;
  r = await w.updater.update();
  assert.equal(r.ok, false);
  assert.equal(r.steps[r.steps.length - 1].step, "wait for engine");
});

test("update() refuses when the digests match or are unknown, and starts no runner", async () => {
  const sp = standInSpawn();
  let w = world({ registryDigest: OLD, opts: { spawn: sp.spawn } });
  await w.updater.check();
  let r = await w.updater.update();
  assert.equal(r.ok, false);
  assert.match(r.steps[0].detail, /already runs main's build/);
  w = world({ registryDown: true, opts: { spawn: sp.spawn } });
  await w.updater.check();
  r = await w.updater.update();
  assert.equal(r.ok, false);
  assert.match(r.steps[0].detail, /registry unreachable/);
  w = world({ opts: { spawn: sp.spawn } });
  r = await w.updater.update();   // never checked: nothing known
  assert.equal(r.ok, false);
  assert.deepEqual(sp.calls, []);
});

test("update() refuses a compose project whose directory is not on this machine", async () => {
  const sp = standInSpawn();
  const w = world();
  const u = w.make({ existsFn: () => false, spawn: sp.spawn });
  await u.check();
  const r = await u.update();
  assert.equal(r.ok, false);
  assert.equal(r.steps[0].step, "find project");
  assert.ok(r.steps[0].detail.endsWith(`${w.proj} is not on this machine`), r.steps[0].detail);
  assert.deepEqual(sp.calls, []);
});

// ---------------------------------------------------------------------------
// The app going away mid-update, the relaunch, and the backstop (#1040)
// ---------------------------------------------------------------------------

test("the app goes away mid-update: a relaunched updater resumes from the status file, refuses a click, and reports the end", async () => {
  const w = world();
  const sp = standInSpawn();
  const watchers = [];
  const watch = (d, cb) => { assert.equal(d, w.dir); watchers.push(cb); return { close() { watchers.splice(watchers.indexOf(cb), 1); } }; };
  const first = w.make({ spawn: sp.spawn, watch });
  await first.check();
  first.update();   // never resolved here: the app quits
  // The runner is on its second step when the app goes away. Nothing in the
  // updater kills it, and it keeps writing its status file.
  w.writeStatus({ pid: 424242, step: "up", done: ["pull"], code: null, project: "frakworks" });
  assert.equal(sp.children[0].killed, false, "the runner is not killed");
  // The relaunch: a new updater over the same userData, the runner alive.
  const alive = new Set([424242]);
  const second = w.make({ spawn: sp.spawn, watch, isPidAlive: (pid) => alive.has(pid) });
  const s = await second.check();
  assert.equal(s.updating, "up");
  assert.equal(describeEngineUpdate(second.state()).text, "engine sha-1111111 · updating: up…");
  assert.ok(!w.calls.some((c) => c[1] === "start"), "an update in flight: the backstop stays out");
  const refused = await second.update();
  assert.deepEqual(refused, { ok: false, steps: [{ step: "check", ok: false, detail: "an update is already running (up)" }] });
  assert.equal(sp.calls.length, 1, "no second runner");
  assert.ok(w.logs.some((m) => /resuming the update in flight \(pid 424242, up\)/.test(m)));
  // The runner ends; the second updater hears it through fs.watch.
  fs.writeFileSync(w.upped, "");
  w.writeStatus({ pid: 424242, step: "up", done: ["pull", "up"], code: 0, failedStep: null, detail: null, project: "frakworks" });
  alive.delete(424242);
  const reported = new Promise((resolve) => { const t = setInterval(() => { if (w.results.length) { clearInterval(t); resolve(w.results[0]); } }, 1); });
  for (const cb of [...watchers]) cb("rename", STATUS_FILE);
  const r = await reported;
  assert.equal(r.ok, true);
  assert.deepEqual(r.steps.map((x) => x.step), ["pull", "up", "wait for engine"]);
  assert.equal(reportUpdate(r).text, "engine updated to sha-2222222");
  assert.equal(second.state().updating, null);
  assert.equal(w.status(), null);
});

test("a stale status file whose pid is dead is a failed update, not one in flight", async () => {
  const w = world({ noContainer: true });
  w.writeStatus({ pid: 99999, step: "up", done: ["pull"], code: null, project: "frakworks" });
  fs.writeFileSync(path.join(w.dir, LOG_FILE), "== up: docker compose up\nContainer frakworks-engine-1 Recreated\n");
  const u = w.make({ isPidAlive: () => false });
  const s = await u.check();
  assert.equal(s.updating, null, "never in flight");
  assert.equal(w.results.length, 1);
  assert.deepEqual(w.results[0], { ok: false, steps: [{ step: "pull", ok: true, detail: "" }, { step: "up", ok: false, detail: "Container frakworks-engine-1 Recreated" }] });
  assert.equal(reportUpdate(w.results[0]).text, "engine up failed");
  assert.equal(w.status(), null, "the file is cleared");
  const r = await u.update();
  assert.ok(!/already running/.test(r.steps[0].detail), "the button is usable again");
});

test("the backstop: an engine container left in Created is started on launch, logged and reported", async () => {
  const w = world({ noContainer: true, created: { id: "abc123" } });
  const s = await w.updater.check();
  const start = w.calls.find((c) => c[1] === "start");
  assert.deepEqual(start, [w.fakeDocker, "start", "abc1230000"]);
  assert.ok(w.logs.some((m) => /started the engine container abc1230000, left in Created/.test(m)));
  assert.deepEqual(w.results, [{ started: "abc1230000" }]);
  assert.equal(reportUpdate(w.results[0]).text, "engine started (left in Created)");
  assert.equal(s.runningError, null, "the normal check after it finds the running container");
  assert.equal(s.running.digest, OLD);
  // Another project's, or another port's, Created container is not ours.
  for (const created of [{ id: "def456", port: "9999" }]) {
    const o = world({ noContainer: true, created });
    await o.updater.check();
    assert.ok(!o.calls.some((c) => c[1] === "start"), JSON.stringify(created));
  }
  const o = world();
  await o.updater.check();   // the running container names the project
  o.created = { id: "def456", project: "other" };
  await o.updater.check();
  assert.ok(!o.calls.some((c) => c[1] === "start"), "another compose project's container stays");
});

test("a dead stale runner, then the backstop: the 2026-10-05 restart is picked up on relaunch", async () => {
  const w = world({ noContainer: true, created: { id: "abc123" } });
  w.writeStatus({ pid: 99999, step: "up", done: ["pull"], code: null, project: "frakworks" });
  const u = w.make({ isPidAlive: () => false });
  const s = await u.check();
  assert.deepEqual(w.results.map((r) => reportUpdate(r).text), ["engine up failed", "engine started (left in Created)"]);
  assert.equal(s.runningError, null);
});

test("the runner itself: each step only after the last exited 0, the status file at each step and the end", async () => {
  const w = world({ pull: { code: 0, stdout: "pulled" }, up: { code: 3, stderr: "boom" } });
  const { spawnSync } = require("node:child_process");
  const cfg = { docker: w.fakeDocker, cwd: w.dir, status: path.join(w.dir, STATUS_FILE), log: path.join(w.dir, LOG_FILE), project: "frakworks",
    steps: [{ step: "pull", args: ["compose", "pull", "engine"], timeoutMs: 10000 }, { step: "up", args: ["compose", "up", "-d", "engine"], timeoutMs: 10000 }] };
  const fd = fs.openSync(cfg.log, "w");
  const r = spawnSync(process.execPath, ["-e", RUNNER, JSON.stringify(cfg)], { stdio: ["ignore", fd, fd] });
  fs.closeSync(fd);
  assert.equal(r.status, 3);
  const st = w.status();
  assert.equal(typeof st.pid, "number");
  assert.equal(st.step, "up");
  assert.deepEqual(st.done, ["pull"]);
  assert.equal(st.code, 3);
  assert.equal(st.failedStep, "up");
  assert.equal(st.detail, "exit 3: boom");
  assert.equal(runner.length, 1);
});

// ---------------------------------------------------------------------------
// The status bar (dist/status-bar.cjs, built by `npm test`)
// ---------------------------------------------------------------------------

const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { StatusBar, engineText, engineTitle, engineReport } = require("../dist/status-bar.cjs");

const STATES = {
  differs: state(),
  same: state({ available: { commit: OLD_SHA, digest: OLD } }),
  checking: state({ checkedAt: null, checking: true }),
  updating: state({ updating: "up" }),
  registry: state({ available: null, availableError: "registry unreachable" }),
  container: state({ running: null, runningError: "no running compose \"engine\" container" }),
  digest: state({ available: { commit: NEW_SHA, digest: null } }),
  remote: state({ remote: true, running: { commit: OLD_SHA }, available: null,
    engine: { mode: "kubernetes", selfUpdate: true, latest: { commit: NEW_SHA, digest: NEW }, behind: 3, updateAvailable: true, openSessions: 0 } }),
  remoteSame: state({ remote: true, running: { commit: NEW_SHA }, available: null,
    engine: { mode: "kubernetes", selfUpdate: true, latest: { commit: NEW_SHA, digest: NEW }, behind: 0, updateAvailable: false } }),
  remoteHost: state({ remote: true, running: { commit: NEW_SHA }, available: null, engine: { mode: "host", selfUpdate: false, error: "its host updates it" } }),
  deploying: state({ remote: true, updating: "deploying" }),
  none: null,
};

test("the bar's engine phrase, hover and report agree with engine-update.js for every state", () => {
  for (const [name, s] of Object.entries(STATES)) {
    assert.deepEqual(engineText(s), describeEngineUpdate(s), name);
    assert.equal(engineTitle(s), engineUpdateTitle(s), name);
  }
  for (const r of [null, { checked: true }, { cancelled: true }, { started: "abc123" }, { ok: true, steps: [], build: { commit: NEW_SHA, digest: NEW } },
    { ok: false, steps: [{ step: "pull", ok: true }, { step: "up", ok: false, detail: "boom" }] }]) {
    assert.deepEqual(engineReport(r), reportUpdate(r));
  }
});

test("the Update engine button is lit only on differs; unknown is sb-bad; a failure names its step", () => {
  const draw = (engineUpdate, extra = {}) => renderToStaticMarkup(React.createElement(StatusBar, {
    version: { packaged: true, version: "0.1.42", update: { checkedAt: 1 } }, engine: { ok: true, url: ENGINE }, updating: null,
    engineUpdate, ...extra }));
  const lit = draw(STATES.differs);
  assert.match(lit, /sbButton lit"[^>]*>⇪ Update engine</);
  assert.match(lit, /engine sha-1111111 · main sha-2222222/);
  assert.match(lit, /registry +registry\.example:5000\/frakworks-engine:main/);
  const same = draw(STATES.same);
  assert.doesNotMatch(same, /lit"[^>]*>⇪ Update engine/);
  assert.match(same, /engine sha-1111111 · main</);
  const unknown = draw(STATES.registry);
  assert.doesNotMatch(unknown, /lit"[^>]*>⇪ Update engine/);
  assert.match(unknown, /sbEngineBuild sb-bad[^>]*>engine sha-1111111 · main unknown: registry unreachable/);
  assert.match(unknown, /error +registry unreachable/);
  const failed = draw(STATES.differs, { engineResult: { ok: false, steps: [{ step: "pull", ok: false, detail: "manifest unknown" }] } });
  assert.match(failed, /class="sbErr" title="manifest unknown">engine pull failed/);
  const ok = draw(STATES.same, { engineResult: { ok: true, steps: [], build: { commit: NEW_SHA, digest: NEW } } });
  assert.match(ok, /engine updated to sha-2222222/);
  const busy = draw(STATES.updating);
  assert.match(busy, /disabled=""[^>]*>up…</);
  // A remote engine: the same button, lit from the engine's own status.
  const cluster = draw(STATES.remote);
  assert.match(cluster, /sbEngineBuild sb-stale[^>]*>engine sha-1111111 · main sha-2222222 · 3 commits behind</);
  assert.match(cluster, /sbButton lit"[^>]*>⇪ Update engine</);
  assert.doesNotMatch(cluster, /cluster rollout/);
  assert.doesNotMatch(draw(STATES.remoteSame), /lit"[^>]*>⇪ Update engine/);
  assert.match(draw(STATES.remoteHost), /its host updates it/);
  assert.match(draw(STATES.deploying), /disabled=""[^>]*>deploying…</);
});

// #2585: the engine comes up on a build the app did not expect; the deploy ends promptly.
{
const { previewModel } = require("../src/update-preview");
const { createEngineUpdater: create2585, describeEngineUpdate: describe2585 } = require("../src/engine-update");
const OLD = "1".repeat(40), NEW = "2".repeat(40), OTHER = "3".repeat(40);
const URL_ = "https://engine.k8s.example";
test("deploy: an engine up on an unexpected build ends the deploy, not the full wait (#2585)", async () => {
  let commit = OLD, posted = false, clock = 1000, polls = 0;
  const fetch = async (url, opts = {}) => {
    let body;
    if (url === `${URL_}/version`) { if (posted) { polls++; commit = OTHER; } body = { version: "x", commit }; }
    else if (url === `${URL_}/metrics`) body = "";
    else if (opts.method === "POST") { posted = true; body = {}; }
    else body = { mode: "kubernetes", selfUpdate: true, running: commit, latest: posted ? null : { digest: "sha256:" + "b".repeat(64), commit: NEW },
      behind: 1, updateAvailable: commit !== NEW, openSessions: 0, error: posted ? "registry: lookup failed" : null };
    return { ok: true, status: 200, text: async () => JSON.stringify(body), headers: { get: () => null } };
  };
  const seen = [];
  const u = create2585({ run: async () => { throw new Error("no docker"); }, fetch, engineUrl: URL_, registry: "r:5000",
    now: () => clock, sleep: async (ms) => { clock += ms; seen.push(describe2585(u.state()).text); }, waitMs: 180000, pollMs: 2000 });
  await u.check();
  const r = await u.update();
  // The engine answered /version with its new (running) build from the first poll on...
  assert.ok(polls >= 1);
  // ...yet the bar said "deploying" at every poll for the full 180 s:
  const stuck = seen.filter((t) => /deploying/.test(t)).length;
  console.log(`polls=${polls} bar-said-deploying=${stuck} times over ${clock / 1000}s; result ok=${r.ok}`);
  assert.ok(stuck <= 2, `bar kept saying "deploying" for ${stuck} polls (${clock / 1000}s) after the engine was up on ${OTHER.slice(0, 7)}`);
  assert.equal(r.ok, false);
  assert.match(r.steps.at(-1).detail, /GET \/version named 3333333/);
});
}
