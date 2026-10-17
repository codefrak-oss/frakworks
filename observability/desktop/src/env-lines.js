// env-lines.js — Config > frakworks > .env (#912): the deployment's root
// `.env` lines nothing reads, and their removal after a confirm.
//
// The check: every key in `.env` must be declared in `.env.example`, the
// tracked list of what a deployment sets. A key in `.env` that is not in
// `.env.example` is **unused**; a key in `.env.example` missing from `.env` is
// **missing**. The engine cannot see the host's `.env` (its container gets only
// what compose passes), so the check runs here, in main, and in
// scripts/local-up.sh, which applies the same key rule in python3.
//
// Only key names are read out of either file, compared, returned and logged.
// A value never leaves this module: not in a result, not in an error, not over
// IPC. `removeLines` copies the lines it keeps byte for byte without reading
// them as anything but text.
//
// Where the `.env` is: in a checkout run, the checkout's root; in a packaged
// build, the compose project directory the engine runs from, the
// `com.docker.compose.project.working_dir` label src/engine-update.js already
// reads off the running engine container (`projectDir`). No second discovery.
"use strict";

const nodeFs = require("node:fs");
const path = require("node:path");

// A line that sets a key: the key is the text before the first `=`.
const KEY_LINE = /^([A-Za-z_][A-Za-z0-9_]*)=/;

/** The keys `text` sets, in file order, each once. Blank and `#` lines never match. */
function envKeys(text) {
  const seen = new Set();
  for (const line of String(text || "").split(/\r?\n/)) {
    const m = KEY_LINE.exec(line);
    if (m) seen.add(m[1]);
  }
  return [...seen];
}

/** `{ unused, missing }`: keys of `.env` not in `.env.example`, and the reverse, each in its file's order. */
function compareEnv(envText, exampleText) {
  const env = envKeys(envText);
  const example = envKeys(exampleText);
  const declared = new Set(example), present = new Set(env);
  return { unused: env.filter((k) => !declared.has(k)), missing: example.filter((k) => !present.has(k)) };
}

/**
 * `text` without the lines whose key is in `keys`: `{ text, removed }`, the
 * count of lines dropped. Every other line keeps its bytes, its line ending
 * included, and a last line with no newline stays without one.
 */
function removeLines(text, keys) {
  const drop = new Set(keys);
  let removed = 0;
  const kept = String(text).split(/(?<=\n)/).filter((line) => {
    const m = KEY_LINE.exec(line);
    if (m && drop.has(m[1])) { removed++; return false; }
    return true;
  });
  return { text: kept.join(""), removed };
}

/**
 * Whether `keys` may be removed: `{ keys }` (deduplicated) or `{ error }`.
 * Refused: an empty list, a non-key, a key in `declared` (the keys
 * `.env.example` declares, read again just before), and a key that was not
 * in `last.unused`, the check the user saw.
 */
function checkRemoval(keys, last, declared) {
  if (!Array.isArray(keys) || !keys.length) return { error: "no keys to remove" };
  if (!last || !Array.isArray(last.unused)) return { error: "no check to remove from; check .env first" };
  const out = [];
  for (const k of keys) {
    if (typeof k !== "string" || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(k)) return { error: "a key to remove is not a key name" };
    if (declared.includes(k)) return { error: `${k} is declared in .env.example; it is not removed` };
    if (!last.unused.includes(k)) return { error: `${k} was not an unused line in the last check` };
    if (!out.includes(k)) out.push(k);
  }
  return { keys: out };
}

/**
 * Where `.env` is, from main: `{ dir }` or `{ error }`. A checkout run uses
 * `repoDir`; a packaged build the engine container's compose working
 * directory, from src/engine-update.js's `state()` (`state.running.project`).
 */
function projectDir({ packaged, repoDir, state }) {
  if (!packaged) return repoDir ? { dir: repoDir } : { error: "no checkout directory" };
  const p = state && state.running && state.running.project;
  if (p && p.workingDir) return { dir: p.workingDir };
  const why = (state && state.runningError) || "the engine container was not found";
  return { error: `the compose project directory is unknown: ${why}` };
}

const readErr = (what, file, e) => `could not read ${what} (${file}): ${(e && e.code) || "error"}`;

/**
 * The check for `dirResult` (projectDir's answer): `{ unused, missing, file }`
 * or `{ error }`. Never throws; never carries a value.
 */
function checkEnv(dirResult, fs = nodeFs) {
  if (!dirResult || dirResult.error) return { error: (dirResult && dirResult.error) || "no project directory" };
  const file = path.join(dirResult.dir, ".env");
  const exampleFile = path.join(dirResult.dir, ".env.example");
  let env, example;
  try { env = fs.readFileSync(file, "utf8"); } catch (e) { return { error: readErr(".env", file, e) }; }
  try { example = fs.readFileSync(exampleFile, "utf8"); } catch (e) { return { error: readErr(".env.example", exampleFile, e) }; }
  return { ...compareEnv(env, example), file };
}

/**
 * Removes `keys` from `last.file`, after the confirm: reads `.env.example`
 * again, refuses what checkRemoval refuses, rewrites the file in place (same
 * path, same inode, its mode set back) and re-runs the check. The file is read
 * and written as latin1, one char per byte, so the lines kept are the same
 * bytes whatever their encoding. `{ removed, check }` or `{ error }`; never throws.
 */
function removeEnv({ keys, last, fs = nodeFs }) {
  if (!last || !last.file) return { error: "no check to remove from; check .env first" };
  const file = last.file;
  const dir = { dir: path.dirname(file) };
  const exampleFile = path.join(dir.dir, ".env.example");
  let declared;
  try { declared = envKeys(fs.readFileSync(exampleFile, "utf8")); } catch (e) { return { error: readErr(".env.example", exampleFile, e) }; }
  const ok = checkRemoval(keys, last, declared);
  if (ok.error) return { error: ok.error };
  try {
    const mode = fs.statSync(file).mode & 0o7777;
    const r = removeLines(fs.readFileSync(file, "latin1"), ok.keys);
    fs.writeFileSync(file, r.text, "latin1");
    fs.chmodSync(file, mode);
    return { removed: r.removed, check: checkEnv(dir, fs) };
  } catch (e) {
    return { error: `could not rewrite .env (${file}): ${(e && e.code) || "error"}` };
  }
}

/** The native confirm's text, `{ message, detail }`: the file and the exact keys. */
function confirmText(file, keys) {
  const n = keys.length;
  return {
    message: `Remove ${n} unused ${n === 1 ? "line" : "lines"} from .env?`,
    detail: [
      `File: ${file}`,
      "",
      "Lines to delete (by key):",
      ...keys.map((k) => `  ${k}`),
      "",
      "Every other line stays as it is.",
      "Removing a line does not revoke a token or password it held; revoke the credential where it was issued.",
    ].join("\n"),
  };
}

module.exports = { envKeys, compareEnv, removeLines, checkRemoval, projectDir, checkEnv, removeEnv, confirmText, KEY_LINE };
