// engine-url-setting.js — Config > frakworks > Engine URL (#100144): where
// this install's engine is, e.g. https://engine.k8s.example or the local
// stack's http://localhost:<port>. A small JSON file under userData, like
// the screenshot setting (src/screenshot.js); there is no default and no
// environment variable: unset, the app makes no engine call and every engine
// view says to enter one (engine-data.js's NO_ENGINE_PROMPT).
"use strict";

const fs = require("node:fs");
const path = require("node:path");

const FILE_NAME = "engine-url.json";

/** `raw` as an engine base URL (http or https, no credentials, no query, no trailing slash), or `{ error }`. */
function parse(raw) {
  const v = typeof raw === "string" ? raw.trim() : "";
  if (!v) return { error: "enter the engine's URL, e.g. https://engine.example" };
  let u;
  try { u = new URL(v); } catch { return { error: `not a URL: ${JSON.stringify(v)}` }; }
  if (u.protocol !== "http:" && u.protocol !== "https:") return { error: `not an http(s) URL: ${JSON.stringify(v)}` };
  if (u.username || u.password) return { error: "the engine URL carries no credentials: sign in under Config > frakworks > Sign in" };
  if (u.search || u.hash) return { error: "the engine URL has no query or fragment" };
  return { url: v.replace(/\/+$/, "") };
}

/** The saved URL, or null when the file is missing, unreadable or holds no valid URL. */
function load(file) {
  try { const r = parse(JSON.parse(fs.readFileSync(file, "utf8")).url); return r.url || null; } catch { return null; }
}

/** Saves `raw` (or clears the setting for null/""). `{ url }` (null when cleared) or `{ error }`. */
function save(file, raw) {
  if (raw == null || (typeof raw === "string" && !raw.trim())) {
    fs.rmSync(file, { force: true });
    return { url: null };
  }
  const r = parse(raw);
  if (r.error) return r;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify({ url: r.url }) + "\n");
  return { url: r.url };
}

module.exports = { FILE_NAME, parse, load, save };
