// app-update.test.js — the packaged build's self-update (src/app-update.js)
// against a fake electron-updater, and the status bar's packaged phrase: the
// renderer's copy (status-bar.jsx `packagedText`) agrees with `describeUpdate`
// for every state, and only a downloaded update lights Restart.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const EventEmitter = require("node:events");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { createUpdater, restartPackaged, describeUpdate, updateTitle, CHECK_MS, FEED_URL } = require("../src/app-update");
const { StatusBar, versionText, versionTitle } = require("../dist/status-bar.cjs");

class FakeAutoUpdater extends EventEmitter {
  constructor() { super(); this.feed = null; this.checks = 0; this.installed = 0; this.next = null; }
  setFeedURL(o) { this.feed = o; }
  async checkForUpdates() {
    this.checks++;
    this.emit("checking-for-update");
    if (this.next === "error") { this.emit("error", new Error("ENOTFOUND releases.example")); return null; }
    if (!this.next) { this.emit("update-not-available", {}); return null; }
    this.emit("update-available", { version: this.next });
    if (this.autoDownload) this.emit("update-downloaded", { version: this.next, releaseNotes: this.notes });
    return { updateInfo: { version: this.next } };
  }
  quitAndInstall() { this.installed++; }
}

function fakeApp() {
  const calls = [];
  return { calls, getVersion: () => "0.1.42", relaunch: () => calls.push("relaunch"), exit: (c) => calls.push(`exit ${c}`) };
}

test("packaged Restart relaunches the app as is when the feed has nothing newer, or errs", async () => {
  for (const next of [null, "error"]) {
    const au = new FakeAutoUpdater(); au.next = next;
    const app = fakeApp();
    const r = await restartPackaged({ updater: createUpdater({ autoUpdater: au, feed: "http://releases.example/" }), app });
    assert.equal(r.ok, true);
    assert.equal(r.steps[0].step, "relaunch");
    assert.deepEqual(app.calls, ["relaunch", "exit 0"]);
    assert.equal(au.installed, 0);
    assert.equal(au.checks, 1);
  }
});

test("packaged Restart installs a downloaded update and does not relaunch itself", async () => {
  for (const pre of [true, false]) {
    const au = new FakeAutoUpdater(); au.next = "0.1.43";
    const u = createUpdater({ autoUpdater: au });
    if (pre) await u.check();
    const app = fakeApp();
    const r = await restartPackaged({ updater: u, app });
    assert.deepEqual(r, { ok: true, steps: [{ step: "install update", ok: true, detail: "" }] });
    assert.deepEqual(app.calls, []);
    assert.equal(au.installed, 1);
  }
});

test("the updater asks the generic feed, downloads on its own, and installs only what is downloaded", async () => {
  const au = new FakeAutoUpdater();
  const logs = [];
  const u = createUpdater({ autoUpdater: au, feed: "http://releases.example/", log: (m) => logs.push(m), now: () => 1700000000000 });
  assert.deepEqual(au.feed, { provider: "generic", url: "http://releases.example/" });
  // Without a configured feed the build's baked-in URL stands: setFeedURL is not called.
  const bare = new FakeAutoUpdater();
  createUpdater({ autoUpdater: bare });
  assert.equal(bare.feed, null);
  assert.equal(au.autoDownload, true);
  assert.equal(au.autoInstallOnAppQuit, false);
  assert.equal(u.CHECK_MS, CHECK_MS);
  // Nothing newer: running, checked, nothing to install.
  let st = await u.check();
  assert.equal(au.checks, 1);
  assert.deepEqual(st, { feed: "http://releases.example/", commit: null, checking: false, checkedAt: 1700000000000, available: null, downloaded: null, error: null });
  assert.equal(u.install(), false);
  assert.equal(au.installed, 0);
  // A newer build: available, then downloaded in the same check; install relaunches.
  au.next = "0.1.43";
  st = await u.check();
  assert.equal(st.available, null);
  assert.deepEqual(st.downloaded, { version: "0.1.43", commit: null });
  assert.deepEqual(logs, ["update available: v0.1.43", "update downloaded: v0.1.43"]);
  assert.equal(u.install(), true);
  assert.equal(au.installed, 1);
  // A feed error is reported, not thrown, and clears on the next good check.
  const au2 = new FakeAutoUpdater(); au2.next = "error";
  const u2 = createUpdater({ autoUpdater: au2 });
  st = await u2.check();
  assert.equal(st.error, "ENOTFOUND releases.example");
  au2.next = null;
  st = await u2.check();
  assert.equal(st.error, null);
});

test("describeUpdate says the one thing that matters, and the renderer's copy agrees", () => {
  const v = (update) => ({ packaged: true, version: "0.1.42", update });
  const cases = [
    [v({ checking: true, checkedAt: null }), { text: "v0.1.42 · checking…", level: "muted" }],
    [v({ checking: false, checkedAt: 1 }), { text: "v0.1.42 · running", level: "ok" }],
    [v({ checkedAt: 1, error: "ENOTFOUND releases.example" }), { text: "v0.1.42 · running · feed: ENOTFOUND releases.example", level: "muted" }],
    [v({ checkedAt: 1, available: { version: "0.1.43" } }), { text: "v0.1.42 · downloading v0.1.43…", level: "muted" }],
    [v({ checkedAt: 1, downloaded: { version: "0.1.43" } }), { text: "v0.1.42 · v0.1.43 ready", level: "stale" }],
  ];
  for (const [input, want] of cases) {
    assert.deepEqual(describeUpdate(input), want);
    assert.deepEqual(versionText(input), want);
  }
  assert.equal(describeUpdate({ branch: "main", sha: "abc" }), null);
  const FEED = "http://releases.example/";
  const title = versionTitle(v({ feed: FEED, checkedAt: 1700000000000, downloaded: { version: "0.1.43" } }));
  assert.equal(title, updateTitle(v({ feed: FEED, checkedAt: 1700000000000, downloaded: { version: "0.1.43" } })));
  assert.match(title, /running {2}v0\.1\.42\nfeed {5}http:\/\/releases\.example\/\nchecked {2}2023-11-14T22:13:20\.000Z\ndownloaded v0\.1\.43/);
  // No feed configured: the title says so rather than printing an empty line (src/deployment-config.js).
  assert.equal(FEED_URL, null, "the tree carries no feed URL; release_feed in agent-army.config.json names it");
  assert.match(versionTitle(v({ checkedAt: 1 })), /feed {5}\(build default\)/);
  assert.equal(versionTitle(v({ checkedAt: 1 })), updateTitle(v({ checkedAt: 1 })));
});

test("the packaged bar lights the button only for a downloaded update, and offers to install it", () => {
  const draw = (update) => renderToStaticMarkup(React.createElement(StatusBar, {
    version: { packaged: true, version: "0.1.42", update }, engine: { ok: true, url: "http://engine.test" }, updating: null }));
  const running = draw({ checkedAt: 1 });
  assert.doesNotMatch(running, /sbButton lit/);
  assert.match(running, /Check the release feed for a newer build, then relaunch/);
  assert.match(running, /⬇/);
  const ready = draw({ checkedAt: 1, downloaded: { version: "0.1.43" } });
  assert.match(ready, /sbButton lit/);
  assert.match(ready, /Install v0\.1\.43 and relaunch/);
  assert.match(ready, /sb-stale/);
});

test("electron-builder.js publishes to the generic feed and signs the arm64 dmg and zip; the workflow builds on the mac runner and publishes by rsync", () => {
  const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "package.json"), "utf8"));
  assert.ok(pkg.dependencies["electron-updater"]);
  assert.equal(pkg.build, undefined, "the build config is electron-builder.js's alone");
  pkg.build = require("../electron-builder.js");
  assert.deepEqual(pkg.build.publish, [{ provider: "generic", url: "http://releases.example/", channel: "latest" }]);
  assert.deepEqual(pkg.build.mac.target.map((t) => t.target), ["dmg", "zip"]);
  assert.equal(pkg.build.mac.identity, undefined, "the identity comes from CSC_NAME in CI, never from package.json");
  const wf = fs.readFileSync(path.join(__dirname, "..", "..", "..", ".github", "workflows", "desktop-release.yml"), "utf8");
  assert.match(wf, /runs-on: \[self-hosted, macOS, desktop-build\]/);
  assert.match(wf, /observability\/desktop\/\*\*/);
  assert.match(wf, /npm run build:renderer\n\s+npx electron-builder --mac --arm64 --publish never/, "the renderer bundle is built before packaging; npm test does not build it");
  assert.match(wf, /asar list .*app\.asar/, "the package is checked for the renderer bundle");
  assert.ok(pkg.build.files.includes("!dist/*.cjs"), "the test bundles stay out of the app");
  assert.match(wf, /FEED_HOST: \$\{\{ secrets\.RELEASE_HOST \}\}/, "the feed host is an Actions secret, a deployment value masked in the logs");
  assert.match(wf, /CSC_NAME: \$\{\{ secrets\.CSC_NAME \}\}/);
  assert.match(wf, /CSC_KEYCHAIN: \$\{\{ secrets\.CSC_KEYCHAIN \}\}/);
  assert.doesNotMatch(wf, /@[a-z0-9.-]+\.(net|com|org)\b/, "no email in the workflow");
  assert.equal(pkg.build.publish[0].url, "http://releases.example/", "package.json's publish URL is a placeholder; release_feed in agent-army.config.json is the real one");
  assert.match(wf, /rsync .*"publish@\$\{FEED_HOST\}:\$\{FEED_DIR\}\/"/);
  assert.match(wf, /latest-mac\.yml/);
  assert.match(wf, /FEED_KEEP: 5/);
  assert.match(wf, /- name: prune\n\s+run: \|\n\s+ssh "publish@\$\{FEED_HOST\}" "cd \$\{FEED_DIR\} && ls frakworks-\*-arm64\.dmg .*sort -V \| head -n -\$\{FEED_KEEP\}/, "old versions are pruned after publish, newest kept");
  // The mac release job alone: the web-image job (#2400) runs on the Linux image runners and may read its restart secret.
  const release = wf.slice(wf.indexOf("\n  release:"), wf.indexOf("\n  web-image:") === -1 ? undefined : wf.indexOf("\n  web-image:"));
  assert.doesNotMatch(release, /secrets\./, "the runner signs with its own keychain and pushes with its own key; no GitHub secret");
  assert.match(wf, /security unlock-keychain -p "\$\(cat ~\/\.build-keychain-password\)" "\$CSC_KEYCHAIN"/);
});

test("the commit pair (#100091): the stamped build's commit and the feed's incoming one, null when unstamped", async () => {
  const SHA = "0123456789abcdef0123456789abcdef01234567", NEXT = "f".repeat(40);
  const au = new FakeAutoUpdater();
  const u = createUpdater({ autoUpdater: au, commit: SHA });
  au.next = "0.1.43";
  au.notes = NEXT;
  await u.check();
  assert.equal(u.state().commit, SHA);
  assert.deepEqual(u.state().downloaded, { version: "0.1.43", commit: NEXT });
  const bare = new FakeAutoUpdater();
  const v = createUpdater({ autoUpdater: bare });
  bare.next = "0.1.43";
  bare.notes = "Some release notes";
  await v.check();
  assert.equal(v.state().commit, null, "an unstamped build has no commit");
  assert.equal(v.state().downloaded.commit, null, "release notes that are not a sha give none");
  const { stampedCommit } = require("../src/app-update");
  assert.equal(stampedCommit([{ version: "0.1.43", note: NEXT }]), NEXT, "electron-updater's array form");
});

test("electron-builder.js stamps GITHUB_SHA into the app's package.json and latest-mac.yml", () => {
  const file = require.resolve("../electron-builder.js");
  const load = (sha) => {
    delete require.cache[file];
    const was = process.env.GITHUB_SHA;
    if (sha) process.env.GITHUB_SHA = sha; else delete process.env.GITHUB_SHA;
    try { return require(file); } finally { if (was === undefined) delete process.env.GITHUB_SHA; else process.env.GITHUB_SHA = was; delete require.cache[file]; }
  };
  const SHA = "a".repeat(40);
  const c = load(SHA);
  assert.deepEqual(c.extraMetadata, { commit: SHA });
  assert.deepEqual(c.releaseInfo, { releaseNotes: SHA });
  const local = load(null);
  assert.deepEqual(local.extraMetadata, { commit: null });
  assert.equal(local.releaseInfo, undefined);
});
