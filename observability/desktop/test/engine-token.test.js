// Sign in to the engine (#2206):
// src/engine-token.js against a fake safeStorage and an in-memory fs. The
// token is stored encrypted and never answered, logged or handed to the
// renderer; a 401/403 from GET /whoami is the engine's text, and after a
// sign-in it is shown while the header keeps being sent.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { createEngineToken, TOKEN_FILE } = require("../src/engine-token");

const TOKEN = "frk_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const FILE = "/userData/" + TOKEN_FILE;

// A reversible "encryption" the test can see through: bytes are the token reversed.
const fakeSafeStorage = (over = {}) => ({
  isEncryptionAvailable: () => true,
  getSelectedStorageBackend: () => "kwallet",
  encryptString: (s) => Buffer.from([...s].reverse().join("")),
  decryptString: (b) => [...b.toString()].reverse().join(""),
  ...over,
});

const memFs = () => {
  const files = new Map();
  const enoent = (p) => Object.assign(new Error(`ENOENT: ${p}`), { code: "ENOENT" });
  return {
    files,
    readFileSync: (p) => { if (!files.has(p)) throw enoent(p); return files.get(p).bytes; },
    writeFileSync: (p, bytes, opts) => { files.set(p, { bytes: Buffer.from(bytes), mode: opts && opts.mode }); },
    unlinkSync: (p) => { if (!files.has(p)) throw enoent(p); files.delete(p); },
  };
};

const ok = { login: "someone", tokenId: "t-1", name: "desktop on the laptop" };
const whoamiOf = (answer) => {
  const calls = [];
  const fn = async (t) => { calls.push(t); return typeof answer === "function" ? answer(t) : answer; };
  fn.calls = calls;
  return fn;
};

const make = ({ storage = fakeSafeStorage(), whoami = whoamiOf(ok), fsImpl = memFs(), platform = "darwin" } = {}) => {
  const logs = [];
  const keeper = createEngineToken({ safeStorage: storage, file: FILE, fs: fsImpl, whoami, log: (l) => logs.push(l), platform });
  return { keeper, logs, fsImpl, whoami };
};

test("signIn verifies with GET /whoami, stores the encrypted bytes (not the token) mode 0600, and answers login and name only", async () => {
  const { keeper, logs, fsImpl, whoami } = make();
  const r = await keeper.signIn(`  ${TOKEN}\n`);
  assert.deepEqual(r, { login: "someone", name: "desktop on the laptop" });
  assert.deepEqual(whoami.calls, [TOKEN], "the trimmed token is what whoami sees");
  const f = fsImpl.files.get(FILE);
  assert.ok(f, "the file is written");
  assert.equal(f.mode, 0o600);
  assert.ok(!f.bytes.toString().includes(TOKEN), "the file does not hold the token in clear");
  assert.equal(keeper.bearer(), `Bearer ${TOKEN}`);
  assert.equal(keeper.login(), "someone");
  assert.equal(keeper.signedIn(), true);
  assert.deepEqual(logs, ["[engine] sign in as @someone (desktop on the laptop)"]);
  for (const l of logs) assert.ok(!l.includes(TOKEN), `no log line carries the token: ${l}`);
});

test("signIn with a 401 or 403 stores nothing and answers the engine's text", async () => {
  for (const status of [401, 403]) {
    const text = status === 401 ? "no such token" : "@someone is not a trusted login";
    const { keeper, fsImpl, logs } = make({ whoami: whoamiOf({ error: `engine answered ${status} for GET /whoami: ${text}`, status }) });
    const r = await keeper.signIn(TOKEN);
    assert.deepEqual(r, { error: `engine answered ${status} for GET /whoami: ${text}` });
    assert.equal(fsImpl.files.size, 0, "nothing written");
    assert.equal(keeper.bearer(), null);
    assert.equal(keeper.signedIn(), false);
    for (const l of logs) assert.ok(!l.includes(TOKEN));
  }
});

test("signIn refuses an empty or non-frk_ string before any request", async () => {
  const { keeper, whoami, fsImpl } = make();
  assert.match((await keeper.signIn("")).error, /paste the token/);
  assert.match((await keeper.signIn("   ")).error, /paste the token/);
  assert.match((await keeper.signIn("ghp_abc")).error, /starts with frk_/);
  assert.match((await keeper.signIn(42)).error, /paste the token/);
  assert.equal(whoami.calls.length, 0);
  assert.equal(fsImpl.files.size, 0);
});

test("signIn refuses when safeStorage cannot encrypt, or would keep plain text on Linux, without writing", async () => {
  const a = make({ storage: fakeSafeStorage({ isEncryptionAvailable: () => false }) });
  assert.match((await a.keeper.signIn(TOKEN)).error, /cannot encrypt the token at rest/);
  assert.equal(a.fsImpl.files.size, 0);
  assert.equal(a.whoami.calls.length, 0, "no request either");
  const b = make({ storage: fakeSafeStorage({ getSelectedStorageBackend: () => "basic_text" }), platform: "linux" });
  assert.match((await b.keeper.signIn(TOKEN)).error, /plain text .*basic_text/);
  assert.equal(b.fsImpl.files.size, 0);
  // the same backend answer on another platform is not consulted
  const c = make({ storage: fakeSafeStorage({ getSelectedStorageBackend: () => "basic_text" }), platform: "darwin" });
  assert.deepEqual(await c.keeper.signIn(TOKEN), { login: "someone", name: "desktop on the laptop" });
});

test("load restores the token from the file; a missing file is signed out; an undecryptable one is an error and signed out", async () => {
  const fsImpl = memFs();
  const a = make({ fsImpl });
  await a.keeper.signIn(TOKEN);
  const b = make({ fsImpl });
  assert.deepEqual(b.keeper.load(), { signedIn: true });
  assert.equal(b.keeper.bearer(), `Bearer ${TOKEN}`);
  assert.equal(b.keeper.login(), null, "the login is known after the next whoami");
  const s = await b.keeper.status();
  assert.deepEqual(s, { signedIn: true, login: "someone", name: "desktop on the laptop", error: null });
  assert.equal(b.keeper.login(), "someone");
  const c = make();
  assert.deepEqual(c.keeper.load(), { signedIn: false });
  assert.deepEqual(await c.keeper.status(), { signedIn: false, login: null, name: null, error: null });
  const broken = memFs();
  broken.writeFileSync(FILE, Buffer.from("garbage"), { mode: 0o600 });
  const d = make({ fsImpl: broken, storage: fakeSafeStorage({ decryptString: () => { throw new Error("bad key"); } }) });
  const l = d.keeper.load();
  assert.equal(l.signedIn, false);
  assert.match(l.error, /could not be decrypted \(bad key\); sign in again/);
  assert.equal(d.keeper.bearer(), null);
  assert.match((await d.keeper.status()).error, /could not be decrypted/);
});

test("signOut removes the file and clears memory", async () => {
  const { keeper, fsImpl, logs } = make();
  await keeper.signIn(TOKEN);
  assert.deepEqual(keeper.signOut(), { ok: true });
  assert.equal(fsImpl.files.size, 0);
  assert.equal(keeper.bearer(), null);
  assert.equal(keeper.login(), null);
  assert.deepEqual(await keeper.status(), { signedIn: false, login: null, name: null, error: null });
  assert.equal(logs[logs.length - 1], "[engine] sign out (@someone, desktop on the laptop)");
  assert.deepEqual(keeper.signOut(), { ok: true }, "signing out twice is fine");
});

test("status carries a later 401 as the engine's text while signedIn stays true and bearer still answers", async () => {
  let answer = ok;
  const { keeper } = make({ whoami: whoamiOf(() => answer) });
  await keeper.signIn(TOKEN);
  answer = { error: "engine answered 401 for GET /whoami: no such token", status: 401 };
  const s = await keeper.status();
  assert.deepEqual(s, { signedIn: true, login: "someone", name: "desktop on the laptop", error: "engine answered 401 for GET /whoami: no such token" });
  assert.equal(keeper.bearer(), `Bearer ${TOKEN}`, "the header keeps being sent; the app keeps working with the login header");
  assert.equal(keeper.lastError(), s.error);
  answer = { ...ok, name: "renamed" };
  assert.deepEqual(await keeper.status(), { signedIn: true, login: "someone", name: "renamed", error: null });
});

test("no return value other than bearer() carries the token", async () => {
  const { keeper } = make();
  const r = await keeper.signIn(TOKEN);
  const s = await keeper.status();
  const o = keeper.signOut();
  for (const v of [r, s, o, keeper.load(), keeper.login(), keeper.lastError()]) {
    assert.ok(!JSON.stringify(v ?? null).includes(TOKEN), `${JSON.stringify(v)} carries the token`);
  }
});

test("createEngineToken refuses to start without safeStorage, a file or whoami", () => {
  assert.throws(() => createEngineToken({ file: FILE, whoami: async () => ok }), /safeStorage/);
  assert.throws(() => createEngineToken({ safeStorage: fakeSafeStorage(), whoami: async () => ok }), /file/);
  assert.throws(() => createEngineToken({ safeStorage: fakeSafeStorage(), file: FILE }), /whoami/);
});

// ---- the renderer never sees the token ------------------------------------

const SRC = path.join(__dirname, "..", "src");
const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap((d) =>
  (d.isDirectory() ? walk(path.join(dir, d.name)) : [path.join(dir, d.name)]));

test("no renderer module mentions engine-token, bearer() or Authorization; preload forwards the token inward only", () => {
  const renderer = walk(path.join(SRC, "renderer")).filter((f) => /\.(jsx?|tsx?|css|html)$/.test(f));
  assert.ok(renderer.length > 10);
  for (const f of renderer) {
    const text = fs.readFileSync(f, "utf8");
    for (const bad of ["engine-token", "bearer()", "Authorization", "ENGINE_TOKEN", "safeStorage.decrypt"]) {
      assert.ok(!text.includes(bad), `${path.relative(SRC, f)} mentions ${bad}`);
    }
  }
  const preload = fs.readFileSync(path.join(SRC, "preload.js"), "utf8");
  // the one channel a token crosses, inward, as the argument of engine:sign-in
  assert.match(preload, /signIn: \(token\) => ipcRenderer\.invoke\("engine:sign-in", String\(token == null \? "" : token\)\)/);
  assert.doesNotMatch(preload, /bearer|engine-token|ENGINE_TOKEN|Authorization/);
  // main answers only what the keeper's status, signIn and signOut answer
  const main = fs.readFileSync(path.join(SRC, "main.js"), "utf8");
  assert.doesNotMatch(main, /webContents\.send\([^)]*(bearer|ENGINE_TOKEN)/);
  assert.doesNotMatch(main, /console\.log\([^\n]*(engineToken\.bearer|ENGINE_TOKEN)/, "no log line names the token");
});
