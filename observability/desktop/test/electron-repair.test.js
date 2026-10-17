"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { repairElectron } = require("../repair-electron");

const temporary = (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "electron-repair-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  return root;
};

test("repair is a no-op when path.txt names an existing executable", (t) => {
  const root = temporary(t);
  const executable = "Electron.app/Contents/MacOS/Electron";
  fs.mkdirSync(path.join(root, "node_modules/electron/dist", path.dirname(executable)), { recursive: true });
  fs.writeFileSync(path.join(root, "node_modules/electron/dist", executable), "binary");
  fs.writeFileSync(path.join(root, "node_modules/electron/path.txt"), `${executable}\n`);
  let extracted = false;
  assert.equal(repairElectron({ desktopDir: root, cacheDir: path.join(root, "missing"), platform: "darwin", arch: "arm64", extract: () => { extracted = true; } }).changed, false);
  assert.equal(extracted, false);
});

test("repair chooses the newest matching zip, writes path.txt, and is idempotent", (t) => {
  const root = temporary(t), cache = path.join(root, "cache");
  fs.mkdirSync(cache);
  const oldZip = path.join(cache, "electron-v39.7.0-darwin-arm64.zip");
  const newZip = path.join(cache, "electron-v39.8.10-darwin-arm64.zip");
  fs.writeFileSync(oldZip, "old"); fs.writeFileSync(newZip, "new");
  fs.utimesSync(oldZip, new Date(1), new Date(1));
  fs.utimesSync(newZip, new Date(2), new Date(2));
  let selected;
  const extract = (zip, destination) => {
    selected = zip;
    const executable = path.join(destination, "Electron.app/Contents/MacOS/Electron");
    fs.mkdirSync(path.dirname(executable), { recursive: true });
    fs.writeFileSync(executable, "binary");
    return { status: 0 };
  };
  assert.equal(repairElectron({ desktopDir: root, cacheDir: cache, platform: "darwin", arch: "arm64", extract }).changed, true);
  assert.equal(selected, newZip);
  assert.equal(fs.readFileSync(path.join(root, "node_modules/electron/path.txt"), "utf8"), "Electron.app/Contents/MacOS/Electron",
    "no trailing newline: Electron spawns the file's contents as read");
  const before = fs.statSync(path.join(root, "node_modules/electron/path.txt")).mtimeMs;
  assert.equal(repairElectron({ desktopDir: root, cacheDir: cache, platform: "darwin", arch: "arm64", extract }).changed, false);
  assert.equal(fs.statSync(path.join(root, "node_modules/electron/path.txt")).mtimeMs, before);
});

test("a missing cache archive fails without creating node_modules", (t) => {
  const root = temporary(t), cache = path.join(root, "cache");
  fs.mkdirSync(cache);
  assert.throws(() => repairElectron({ desktopDir: root, cacheDir: cache, platform: "darwin", arch: "arm64" }), /electron-v\*-darwin-arm64\.zip/);
  assert.equal(fs.existsSync(path.join(root, "node_modules")), false);
});
