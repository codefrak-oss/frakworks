// name-dev-electron.js against a stand-in Electron.app and a stand-in plutil /
// codesign: it renames to productName, re-signs, and does nothing twice.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { nameDevElectron } = require("../name-dev-electron");

function fixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "name-dev-"));
  fs.writeFileSync(path.join(dir, "package.json"), JSON.stringify({ build: { productName: "frakworks" } }));
  const contents = path.join(dir, "node_modules", "electron", "dist", "Electron.app", "Contents");
  fs.mkdirSync(contents, { recursive: true });
  fs.writeFileSync(path.join(contents, "Info.plist"), "");
  const keys = { CFBundleName: "Electron", CFBundleDisplayName: "Electron" };
  const calls = [];
  const exec = (cmd, args) => {
    calls.push([cmd, ...args]);
    if (cmd === "plutil" && args[0] === "-extract") return { status: 0, stdout: keys[args[1]] + "\n" };
    if (cmd === "plutil" && args[0] === "-replace") { keys[args[1]] = args[3]; return { status: 0 }; }
    return { status: 0 };
  };
  return { dir, keys, calls, exec };
}

test("renames the dev Electron.app to productName and re-signs it, once", () => {
  const f = fixture();
  assert.deepEqual(nameDevElectron({ desktopDir: f.dir, platform: "darwin", exec: f.exec }), { changed: true, name: "frakworks" });
  assert.deepEqual(f.keys, { CFBundleName: "frakworks", CFBundleDisplayName: "frakworks" });
  const sign = f.calls.find((c) => c[0] === "codesign");
  assert.deepEqual(sign.slice(0, 5), ["codesign", "--force", "--deep", "--sign", "-"]);
  assert.match(sign[5], /dist\/frakworks\.app$/);
  const dist = path.join(f.dir, "node_modules", "electron", "dist");
  assert.deepEqual(fs.readdirSync(dist), ["frakworks.app"], "the folder is renamed: the Dock reads it");
  assert.equal(fs.readFileSync(path.join(f.dir, "node_modules", "electron", "path.txt"), "utf8"),
    "frakworks.app/Contents/MacOS/Electron");
  assert.ok(f.calls.some((c) => /lsregister$/.test(c[0]) && c[1] === "-f"), "Launch Services is told the new name");

  f.calls.length = 0;
  assert.equal(nameDevElectron({ desktopDir: f.dir, platform: "darwin", exec: f.exec }).why, "already named");
  assert.equal(f.calls.filter((c) => c[0] === "codesign" || c[1] === "-replace").length, 0, "nothing rewritten or re-signed");
});

test("does nothing off macOS or without an Electron.app, and a failed step throws", () => {
  const f = fixture();
  assert.equal(nameDevElectron({ desktopDir: f.dir, platform: "linux", exec: f.exec }).changed, false);
  assert.equal(f.calls.length, 0);
  assert.match(nameDevElectron({ desktopDir: fs.mkdtempSync(path.join(os.tmpdir(), "x-")), platform: "darwin", exec: f.exec }).why ?? "",
    /productName|Electron\.app/);
  const failing = (cmd, args) => cmd === "codesign" ? { status: 1, stderr: "denied" } : f.exec(cmd, args);
  assert.throws(() => nameDevElectron({ desktopDir: f.dir, platform: "darwin", exec: failing }), /codesign: denied/);
});
