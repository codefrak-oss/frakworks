#!/usr/bin/env node
"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const executableFor = (platform) => platform === "darwin"
  ? "Electron.app/Contents/MacOS/Electron"
  : platform === "win32" ? "electron.exe" : "electron";

function zipFiles(root) {
  if (!fs.existsSync(root)) return [];
  return fs.readdirSync(root, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(".zip"))
    .map((entry) => path.join(entry.parentPath || entry.path, entry.name));
}

function repairElectron({
  desktopDir = __dirname,
  cacheDir = process.env.ELECTRON_CACHE || path.join(os.homedir(), "Library", "Caches", "electron"),
  platform = process.platform,
  arch = process.arch,
  extract = (zip, destination) => spawnSync("ditto", ["-x", "-k", zip, destination], { encoding: "utf8" }),
} = {}) {
  const electronDir = path.join(desktopDir, "node_modules", "electron");
  const distDir = path.join(electronDir, "dist");
  const pathFile = path.join(electronDir, "path.txt");
  const existing = fs.existsSync(pathFile) ? fs.readFileSync(pathFile, "utf8").trim() : "";
  if (existing && fs.existsSync(path.join(distDir, existing))) {
    return { changed: false, executable: existing };
  }

  const suffix = `-${platform}-${arch}.zip`;
  const matches = zipFiles(cacheDir)
    .filter((file) => path.basename(file).startsWith("electron-v") && file.endsWith(suffix))
    .map((file) => ({ file, mtime: fs.statSync(file).mtimeMs }))
    .sort((a, b) => b.mtime - a.mtime);
  if (!matches.length) {
    throw new Error(`no cached Electron archive matching electron-v*${suffix} under ${cacheDir}`);
  }

  fs.mkdirSync(distDir, { recursive: true });
  const result = extract(matches[0].file, distDir);
  if (result.status !== 0) {
    throw new Error(`ditto failed for ${matches[0].file}: ${(result.stderr || result.error || "unknown error").toString().trim()}`);
  }
  const executable = executableFor(platform);
  if (!fs.existsSync(path.join(distDir, executable))) {
    throw new Error(`archive ${matches[0].file} did not contain ${executable}`);
  }
  // No trailing newline: Electron's index.js spawns path.txt's contents as
  // read, and `…/Electron\n` is ENOENT.
  fs.writeFileSync(pathFile, executable);
  return { changed: true, archive: matches[0].file, executable };
}

if (require.main === module) {
  try {
    const result = repairElectron();
    console.log(result.changed
      ? `Repaired Electron from ${result.archive}; wrote ${result.executable} to node_modules/electron/path.txt.`
      : `Electron is already installed at node_modules/electron/dist/${result.executable}.`);
  } catch (error) {
    console.error(`Electron repair failed: ${error.message}`);
    process.exitCode = 1;
  }
}

module.exports = { repairElectron, executableFor };
