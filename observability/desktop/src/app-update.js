// app-update.js — the packaged app's version and its self-update, the
// counterpart of src/app-version.js for a build that does not run out of a
// checkout. A packaged app (`app.isPackaged`) has no HEAD to compare: its
// version is `package.json`'s, stamped by CI (0.2.<run number>), and "newer
// code exists" means the feed's `latest-mac.yml` names a higher one.
//
// The feed is electron-updater's generic provider: a static directory over
// plain HTTP on the LAN holding the DMG, the zip, its blockmap and
// latest-mac.yml. Its URL is a deployment value:
// the `release_feed` key of agent-army.config.json (src/deployment-config.js);
// unset, the `build.publish` URL baked into the app (package.json's
// placeholder), which resolves nowhere and shows as a feed error in the status
// bar. electron-updater does not require https for `generic`, and
// on macOS it serves the downloaded zip to Squirrel.Mac from a localhost http
// server, so the feed's scheme never reaches Squirrel. What Squirrel does
// require is a matching code signature: the update must be signed by the
// same identity as the running app.
//
// Behaviour, mirroring the checkout flow's shape so the status bar draws both
// the same way: a check every CHECK_MS (`checkForUpdates`, which also
// downloads when `autoDownload` is on), state kept here and reported through
// `app:version` as `update`; a downloaded update lights Restart; Restart then
// installs and relaunches (`quitAndInstall`); with nothing downloaded, Restart
// asks the feed once more and relaunches the app as is. Nothing installs on its own.
//
// The commit pair (#100091): electron-builder.js stamps the build's commit
// from CI's GITHUB_SHA into the packaged package.json (`commit`, which main
// hands in here) and into the feed's latest-mac.yml as its `releaseNotes`,
// which electron-updater's update-downloaded info carries. `state()` reports
// both, `commit` and `downloaded.commit`; null when the build was not stamped.
//
// `createUpdater({ autoUpdater })` takes electron-updater's instance so the
// tests can hand in a fake; main.js passes the real one. The pure helpers
// (`describeUpdate`) are what src/renderer/status-bar.jsx mirrors.
"use strict";

// How often the feed is asked: every 10 s (the user, 2026-09-26; was a minute,
// app-version's FETCH_MS register). One small GET of latest-mac.yml on the LAN.
const CHECK_MS = 10000;
// No feed configured: electron-updater keeps the URL baked in at build time.
const FEED_URL = null;

/**
 * The status bar's phrase for a packaged app, from `app:version`'s reply:
 * { packaged: true, version, update: { checking, available, downloaded,
 * error, checkedAt } }. `level` is the bar's colour class: "stale" lights
 * the button (an update is downloaded and waits for Restart).
 */
function describeUpdate(v) {
  if (!v || !v.packaged) return null;
  const base = `v${v.version}`;
  const u = v.update || {};
  if (u.downloaded) return { text: `${base} · v${u.downloaded.version} ready`, level: "stale" };
  if (u.available) return { text: `${base} · downloading v${u.available.version}…`, level: "muted" };
  if (u.error) return { text: `${base} · running · feed: ${u.error}`, level: "muted" };
  if (u.checking && !u.checkedAt) return { text: `${base} · checking…`, level: "muted" };
  return { text: `${base} · running`, level: "ok" };
}

/** The phrase's hover: the feed, the last check, and the versions seen. */
function updateTitle(v) {
  if (!v || !v.packaged) return "";
  const u = v.update || {};
  const lines = [`running  v${v.version}`, `feed     ${u.feed || "(build default)"}`];
  if (u.checkedAt) lines.push(`checked  ${new Date(u.checkedAt).toISOString()}`);
  if (u.available) lines.push(`available v${u.available.version}`);
  if (u.downloaded) lines.push(`downloaded v${u.downloaded.version}`);
  if (u.error) lines.push(`error    ${u.error}`);
  return lines.join("\n");
}

/** A stamped commit: a 40-hex sha (a string, or electron-updater's `[{ note }]` form), else null. */
function stampedCommit(v) {
  const t = Array.isArray(v) ? (v[0] && v[0].note) : v;
  const s = typeof t === "string" ? t.trim().toLowerCase() : "";
  return /^[0-9a-f]{40}$/.test(s) ? s : null;
}

/**
 * Wraps electron-updater's autoUpdater. `state()` is what `app:version`
 * reports; `check()` asks the feed once; `install()` is Restart's action:
 * true when an update was downloaded and quitAndInstall was called, false
 * (with the state) when nothing is ready; Restart then relaunches the app as
 * is (`restartPackaged`).
 */
function createUpdater({ autoUpdater, feed = FEED_URL, commit = null, log = () => {}, now = () => Date.now() }) {
  const st = { feed, commit: stampedCommit(commit), checking: false, checkedAt: null, available: null, downloaded: null, error: null };
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = false;
  autoUpdater.allowDowngrade = false;
  if (feed) autoUpdater.setFeedURL({ provider: "generic", url: feed });
  autoUpdater.on("checking-for-update", () => { st.checking = true; });
  autoUpdater.on("update-available", (info) => { st.available = { version: info.version }; st.error = null; log(`update available: v${info.version}`); });
  autoUpdater.on("update-not-available", () => { st.available = null; st.error = null; });
  autoUpdater.on("update-downloaded", (info) => { st.downloaded = { version: info.version, commit: stampedCommit(info.releaseNotes) }; st.available = null; log(`update downloaded: v${info.version}`); });
  autoUpdater.on("error", (e) => { st.error = (e && e.message) || String(e); log(`update error: ${st.error}`); });
  const check = async () => {
    if (st.checking) return st;
    st.checking = true;
    try { await autoUpdater.checkForUpdates(); }
    catch (e) { st.error = e.message; }
    finally { st.checking = false; st.checkedAt = now(); }
    return st;
  };
  const install = () => {
    if (!st.downloaded) return false;
    log(`installing v${st.downloaded.version} and relaunching`);
    autoUpdater.quitAndInstall(false, true);
    return true;
  };
  return { state: () => ({ ...st }), check, install, CHECK_MS };
}

/**
 * Restart in a packaged build: install a downloaded update; else ask the feed
 * once more and install what that downloaded; else relaunch the app as is
 * (`app.relaunch()` + `app.exit(0)`, which skips before-quit).
 */
async function restartPackaged({ updater, app }) {
  const installed = { ok: true, steps: [{ step: "install update", ok: true, detail: "" }] };
  if (updater.install()) return installed;
  const st = await updater.check();
  if (updater.install()) return installed;
  const r = { ok: true, steps: [{ step: "relaunch", ok: true, detail: st.error ? `feed: ${st.error}` : `no newer build on ${st.feed} (running v${app.getVersion()})` }] };
  app.relaunch();
  app.exit(0);
  return r;
}

module.exports = { createUpdater, stampedCommit, restartPackaged, describeUpdate, updateTitle, CHECK_MS, FEED_URL };
