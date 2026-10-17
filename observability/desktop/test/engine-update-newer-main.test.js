// #2464: the engine restarts on a newer :main than the checked target.
const test = require("node:test");
const assert = require("node:assert/strict");
const { createEngineUpdater } = require("../src/engine-update");
const OLD = "1111111aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", A = "2222222bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", B = "3333333ccccccccccccccccccccccccccccccccc";
const U = "https://engine.k8s.example";

async function deploy(after, mainAfter) {
  let commit = OLD, posted = false;
  const fetch = async (url, opts = {}) => {
    let body;
    if (url === `${U}/version`) { if (posted) commit = after; body = { version: "0.0.1", commit }; }
    else if (url === `${U}/metrics`) body = "";
    else if (url === `${U}/engine/update` && opts.method === "POST") { posted = true; body = {}; }
    else if (url === `${U}/engine/update`) body = { mode: "kubernetes", selfUpdate: true, running: commit, image: "x:main",
      latest: { digest: "sha256:" + "b".repeat(64), commit: posted ? mainAfter : A }, behind: 1, updateAvailable: commit !== A, openSessions: 0, error: null };
    else throw new Error(`unexpected fetch ${url}`);
    return { ok: true, status: 200, text: async () => (typeof body === "string" ? body : JSON.stringify(body)), headers: { get: () => null } };
  };
  let t = 0;
  const up = createEngineUpdater({ run: async () => { throw new Error("no docker"); }, fetch, engineUrl: U, registry: "r:5000",
    now: () => (t += 1000), sleep: async () => {}, waitMs: 20000, pollMs: 1000, auth: () => ({}) });
  await up.check();
  return up.update();
}

test("target A, engine comes up on B, now :main: succeeds naming B", async () => {
  const r = await deploy(B, B);
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.build.commit, B);
});

test("target A, engine reports a commit that is not :main: fails at once, naming it (#2585)", async () => {
  for (const main of [A, OLD]) {
    const r = await deploy(B, main);
    assert.equal(r.ok, false, JSON.stringify(r));
    assert.match(r.steps.at(-1).detail, /GET \/version named 3333333, not /);
  }
});

test("target A, engine reports A: succeeds naming A", async () => {
  const r = await deploy(A, A);
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.build.commit, A);
});
