// The runner's network check (#1222): after `up`, the project's engine
// container is inspected; a `{}` network set recreates it once more
// (`reattach`), and a second `{}` fails the update.
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { RUNNER, STATUS_FILE, LOG_FILE, reportUpdate, createEngineUpdater } = require("../src/engine-update.js");

/** Runs the real runner against a fake docker whose inspects answer `answers` in turn. */
function runWith(answers) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "engine-update-net-"));
  const calls = path.join(dir, "calls.txt"), n = path.join(dir, "n");
  answers.forEach((a, i) => fs.writeFileSync(path.join(dir, `answer${i}`), a));
  const docker = path.join(dir, "docker");
  fs.writeFileSync(docker, [
    "#!/bin/sh",
    `echo "$*" >> ${JSON.stringify(calls)}`,
    "case \"$1\" in",
    "  ps) echo abc123; exit 0 ;;",
    `  inspect) i=$(cat ${JSON.stringify(n)} 2>/dev/null || echo 0); echo $((i+1)) > ${JSON.stringify(n)}; cat ${JSON.stringify(dir)}/answer$i; exit 0 ;;`,
    "esac",
    "exit 0",
    "",
  ].join("\n"), { mode: 0o755 });
  const up = ["compose", "-p", "frakworks", "up", "-d", "--no-deps", "engine"];
  const cfg = { docker, cwd: dir, status: path.join(dir, STATUS_FILE), log: path.join(dir, LOG_FILE), project: "frakworks",
    steps: [{ step: "pull", args: ["compose", "-p", "frakworks", "pull", "engine"], timeoutMs: 10000 }, { step: "up", args: up, timeoutMs: 10000 }],
    reattach: { step: "reattach", service: "engine", args: up, timeoutMs: 10000 } };
  const fd = fs.openSync(cfg.log, "w");
  const r = spawnSync(process.execPath, ["-e", RUNNER, JSON.stringify(cfg)], { stdio: ["ignore", fd, fd] });
  fs.closeSync(fd);
  return {
    code: r.status,
    status: JSON.parse(fs.readFileSync(cfg.status, "utf8")),
    log: fs.readFileSync(cfg.log, "utf8"),
    calls: fs.readFileSync(calls, "utf8").trim().split("\n"),
  };
}

const ATTACHED = JSON.stringify({ frakworks_default: { NetworkID: "n1", IPAddress: "172.18.0.5" } });

test("a container with a network: no second up, the status as before", () => {
  const r = runWith([ATTACHED]);
  assert.equal(r.code, 0);
  assert.deepEqual(r.status.done, ["pull", "up"]);
  assert.equal(r.calls.filter((c) => / up /.test(c)).length, 1);
  assert.ok(r.calls.includes("ps -a -q --filter label=com.docker.compose.project=frakworks --filter label=com.docker.compose.service=engine"));
  assert.ok(r.calls.includes("inspect --format {{json .NetworkSettings.Networks}} abc123"));
});

test("no network after up: the up runs again as reattach, logged and recorded", () => {
  const r = runWith(["{}", ATTACHED]);
  assert.equal(r.code, 0, r.log);
  assert.equal(r.calls.filter((c) => / up /.test(c)).length, 2);
  assert.deepEqual(r.status.done, ["pull", "up", "reattach"]);
  assert.equal(r.status.failedStep, null);
  assert.match(r.log, /== the engine container abc123 came up with no network; recreating it/);
  assert.match(r.log, /== reattach: /);
});

test("no network twice: a failed update on reattach naming the empty network set", () => {
  const r = runWith(["{}", "{}"]);
  assert.notEqual(r.code, 0);
  assert.equal(r.status.failedStep, "reattach");
  assert.match(r.status.detail, /no network again \(\.NetworkSettings\.Networks is \{\}\)/);
  assert.deepEqual(r.status.done, ["pull", "up", "reattach"]);
  assert.equal(reportUpdate({ ok: false, steps: [{ step: "reattach", ok: false, detail: r.status.detail }] }).text, "engine reattach failed");
});

// #100066: GET /version needs the token like every other engine read
// (BearerTokenFilter exempts neither it nor /engine/update); a remote
// deploy must see the target commit as soon as the authed poll names it,
// not time out while the engine is already on the new build.
const NEW_SHA2 = "2".repeat(40);
const OLD_SHA2 = "1".repeat(40);
const NEW_DIGEST2 = `sha256:${"b".repeat(64)}`;

function remoteWorld({ waitMs = 5000, pollMs = 1000 } = {}) {
  const CLUSTER = "https://engine.k8s.example";
  const asked = [];
  let posted = false;
  const fetch = async (url, opts = {}) => {
    const authed = !!(opts.headers && opts.headers.authorization);
    asked.push(`${opts.method || "GET"} ${url} auth=${authed}`);
    let status = 200, body;
    if (url === `${CLUSTER}/version`) {
      if (!authed) { status = 401; body = "no Authorization header"; }
      else body = { version: "0.0.1", commit: posted ? NEW_SHA2 : OLD_SHA2 };
    } else if (url === `${CLUSTER}/metrics`) body = "";
    else if (url === `${CLUSTER}/engine/update` && opts.method === "POST") { posted = true; body = {}; }
    else if (url === `${CLUSTER}/engine/update`) {
      body = { mode: "kubernetes", selfUpdate: true, running: OLD_SHA2, image: "ghcr.io/o/frakworks-engine:main",
        latest: { commit: NEW_SHA2, digest: NEW_DIGEST2 }, behind: 1, updateAvailable: true, openSessions: 0 };
    } else throw new Error(`unexpected fetch ${url}`);
    return { ok: status === 200, status, text: async () => (typeof body === "string" ? body : JSON.stringify(body)), headers: { get: () => null } };
  };
  let t = 0;
  const updater = createEngineUpdater({
    run: async () => { throw new Error("must not run docker"); }, fetch, engineUrl: CLUSTER,
    now: () => (t += 1000), sleep: async () => {}, waitMs, pollMs,
    auth: () => ({ authorization: "Bearer tok" }),
  });
  return { CLUSTER, asked, updater };
}

test("remote deploy: an authed GET /version sees the target commit well before waitMs, no version failure", async () => {
  const w = remoteWorld({ waitMs: 180000, pollMs: 2000 });
  await w.updater.check();
  const r = await w.updater.update();
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.deepEqual(r.build, { commit: NEW_SHA2, digest: NEW_DIGEST2 });
  assert.ok(!r.steps.some((s) => s.step === "version" && !s.ok), "no fail(\"version\", …) step");
  // Far fewer polls than the 180s/2s cap would allow if the loop had to wait it out.
  assert.ok(w.asked.filter((a) => a.startsWith("GET") && a.includes("/version")).length < 10);
  assert.ok(w.asked.every((a) => !a.includes("/version") || a.endsWith("auth=true")), "every GET /version carries the token");
});

test("check(): the running commit comes from a /version that answers only with the token", async () => {
  const w = remoteWorld();
  const s = await w.updater.check();
  assert.equal(s.remote, true);
  assert.equal(s.running && s.running.commit, OLD_SHA2);
  assert.ok(w.asked.some((a) => a === `GET ${w.CLUSTER}/version auth=true`));
  assert.ok(!w.asked.some((a) => a.includes("/version") && a.endsWith("auth=false")));
});
