// answer-config.js — reading `agent-army.config.json`, the app's one config
// file (in Electron's userData; `agent-army.config.example.json` beside this
// tree is the checked-in template; a deployment's filled copy is the
// deployment's own). src/grafana-config.js
// and src/first-mate-tier.js read their blocks through `readConfigFile`.
//
// The name is stage 4's (#1339): this module also loaded and validated the
// Keycloak sign-in and tickets-MCP values for answering a pinned question
// from a Chats pane. That went with the Chats tab (#634); what is left is the
// reader. **Nothing here reads, stores or accepts a credential**: a config
// file that carries something secret-shaped is refused whole rather than
// used (`FORBIDDEN_KEYS`), so a secret never sits in it in plaintext.
"use strict";

const fs = require("node:fs");
const path = require("node:path");

// A config file is public configuration. If one of these appears in it, the
// file is being used as a credential store — refuse the whole file rather than
// silently ignoring the key and leaving a secret sitting in plaintext.
const FORBIDDEN_KEYS = Object.freeze([
  "client_secret", "clientSecret", "secret", "password", "pat", "token",
  "refresh_token", "access_token",
]);

const CONFIG_BASENAME = "agent-army.config.json";

/** Read the JSON config file, if there is one. `{ value }` / `{ error }`. */
function readConfigFile(file, { readFileSync = fs.readFileSync } = {}) {
  let raw;
  try { raw = readFileSync(file, "utf8"); }
  catch (e) { return e && e.code === "ENOENT" ? { value: {} } : { error: `${file}: ${e.message}` }; }
  let parsed;
  try { parsed = JSON.parse(raw); }
  catch (e) { return { error: `${file} is not valid JSON: ${e.message}` }; }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return { error: `${file} must contain a JSON object` };
  }
  const bad = FORBIDDEN_KEYS.filter((k) => k in parsed);
  if (bad.length) {
    return {
      error: `${file} contains ${bad.join(", ")}. Everything in this file is public ` +
        "configuration, so no credential belongs in this file. Remove the key.",
    };
  }
  return { value: parsed };
}

// Where the file lives, given Electron's userData directory. A function rather
// than a constant because `app.getPath` is not available in a unit test.
const configPath = (userDataDir) => path.join(String(userDataDir || "."), CONFIG_BASENAME);

module.exports = { FORBIDDEN_KEYS, CONFIG_BASENAME, readConfigFile, configPath };
