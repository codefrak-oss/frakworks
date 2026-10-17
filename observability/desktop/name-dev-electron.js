#!/usr/bin/env node
// name-dev-electron.js — `postinstall`: on macOS, rename the development
// Electron.app in node_modules to package.json's `productName`, so the menu
// bar, the Dock and Cmd-Tab say "frakworks" instead of "Electron". The menu
// bar reads the bundle's Info.plist and the Dock its folder name; neither is
// app.setName()'s to change at runtime. A packaged build (electron-builder)
// is named properly already.
//
// Editing Info.plist breaks the bundle's signature, so it is re-signed ad hoc.
// The Keychain then sees a new app: expect one "Electron Safe Storage" prompt
// after this runs (and after an Electron upgrade). README, "The app's name".
//
// Idempotent, and never fails an install: anything it cannot do is a warning.
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const LSREGISTER = "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister";
const run = (cmd, args) => spawnSync(cmd, args, { encoding: "utf8" });

function nameDevElectron({ desktopDir = __dirname, platform = process.platform, exec = run } = {}) {
  if (platform !== "darwin") return { changed: false, why: "not macOS" };
  const pkg = path.join(desktopDir, "package.json");
  const name = fs.existsSync(pkg) ? JSON.parse(fs.readFileSync(pkg, "utf8")).build?.productName : undefined;
  if (!name) return { changed: false, why: "package.json has no build.productName" };
  const electronDir = path.join(desktopDir, "node_modules", "electron");
  const stock = path.join(electronDir, "dist", "Electron.app");
  const app = path.join(electronDir, "dist", `${name}.app`);
  if (!fs.existsSync(app) && !fs.existsSync(stock)) return { changed: false, why: `no ${stock}` };
  const plist = path.join(app, "Contents", "Info.plist");

  let changed = false;
  // The Dock labels an app by its bundle's folder name, whatever the plist
  // says, so the folder is renamed too; path.txt is how electron's index.js
  // finds the binary (no trailing newline, as repair-electron.js writes it).
  if (!fs.existsSync(app)) {
    fs.renameSync(stock, app);
    fs.writeFileSync(path.join(electronDir, "path.txt"), `${name}.app/Contents/MacOS/Electron`);
    changed = true;
  }
  const read = (key) => (exec("plutil", ["-extract", key, "raw", "-o", "-", plist]).stdout || "").trim();
  if (read("CFBundleName") !== name || read("CFBundleDisplayName") !== name) {
    for (const key of ["CFBundleName", "CFBundleDisplayName"]) {
      const r = exec("plutil", ["-replace", key, "-string", name, plist]);
      if (r.status !== 0) throw new Error(`plutil ${key}: ${(r.stderr || "").trim()}`);
    }
    const s = exec("codesign", ["--force", "--deep", "--sign", "-", app]);
    if (s.status !== 0) throw new Error(`codesign: ${(s.stderr || "").trim()}`);
    changed = true;
  }
  if (!changed) return { changed: false, why: "already named" };
  // Launch Services caches a bundle's name by path; without this the menu bar
  // keeps saying "Electron" after the plist says otherwise. Best effort.
  exec(LSREGISTER, ["-f", app]);
  return { changed: true, name };
}

if (require.main === module) {
  try {
    const r = nameDevElectron();
    console.log(r.changed ? `[name-dev-electron] the dev app is now "${r.name}.app" (re-signed ad hoc; expect one Keychain prompt)`
      : `[name-dev-electron] nothing to do: ${r.why}`);
  } catch (e) {
    console.warn(`[name-dev-electron] skipped: ${e.message}`);
  }
}

module.exports = { nameDevElectron };
