// engine-token.js — the desktop's sign-in to the engine (#2206):
// one bearer token the engine minted
// (`POST /tokens`, engine/README.md "Tokens"), taken once in Config > frakworks,
// verified with `GET /whoami`, and kept encrypted with Electron's `safeStorage`
// in `<userData>/engine-token.bin`. Main process only.
//
// The token itself leaves this module through `bearer()` alone, for the
// `Authorization` header main and src/engine-data.js send; everything else
// (`signIn`, `status`, `load`) answers the login and the token's name, which
// is all the renderer ever sees (src/preload.js, src/renderer/sign-in.jsx). No
// log line here carries the token: logs name the login and the token's name.
"use strict";

const TOKEN_PREFIX = "frk_";
const TOKEN_FILE = "engine-token.bin";

const text = (e) => (e && e.message) || String(e);

/**
 * `safeStorage` is Electron's (injected in tests); `file` is the token file's
 * path; `fs` Node's; `whoami(token)` resolves to `{ login, tokenId, name }`,
 * `{ error, status }` or `{ error }` and never rejects (engine-data.js
 * `fetchWhoAmI`); `log(line)` is main's console. `platform` is `process.platform`.
 */
function createEngineToken({ safeStorage, file, fs = require("node:fs"), whoami, log = () => {}, platform = process.platform } = {}) {
  if (!safeStorage) throw new Error("createEngineToken needs safeStorage");
  if (!file) throw new Error("createEngineToken needs the token file's path");
  if (typeof whoami !== "function") throw new Error("createEngineToken needs whoami");
  // In memory only: the token, and what the engine said about it.
  let token = null;
  let who = null;        // { login, name, tokenId }
  let lastError = null;  // the engine's text from the last whoami that refused
  let loadError = null;  // why the file could not be read back, if it could not

  // Why the platform cannot keep the token encrypted, or null.
  const encryptionProblem = () => {
    if (typeof safeStorage.isEncryptionAvailable !== "function" || !safeStorage.isEncryptionAvailable()) {
      return "this machine cannot encrypt the token at rest (Electron's safeStorage has no keychain here); not stored";
    }
    if (platform === "linux" && typeof safeStorage.getSelectedStorageBackend === "function"
        && safeStorage.getSelectedStorageBackend() === "basic_text") {
      return "this machine would keep the token in plain text (safeStorage backend basic_text); not stored";
    }
    return null;
  };

  const tokenProblem = (t) => {
    if (typeof t !== "string" || !t.trim()) return "paste the token the engine minted";
    if (!t.trim().startsWith(TOKEN_PREFIX)) return `not an engine token: one starts with ${TOKEN_PREFIX}`;
    return null;
  };

  const whoamiProblem = (r) => {
    if (!r || r.error) return (r && r.error) || "no answer from the engine";
    return null;
  };

  /** Reads the file back into memory; a missing file is signed out. */
  function load() {
    token = null; who = null; lastError = null; loadError = null;
    let bytes;
    try { bytes = fs.readFileSync(file); }
    catch (e) {
      if (e && e.code === "ENOENT") return { signedIn: false };
      loadError = `could not read the saved token: ${text(e)}`;
      log(`[engine] sign in: ${loadError}`);
      return { signedIn: false, error: loadError };
    }
    try {
      const t = safeStorage.decryptString(bytes);
      if (tokenProblem(t)) throw new Error("the file does not hold an engine token");
      token = t.trim();
    } catch (e) {
      token = null;
      loadError = `the saved token could not be decrypted (${text(e)}); sign in again`;
      log(`[engine] sign in: ${loadError}`);
      return { signedIn: false, error: loadError };
    }
    return { signedIn: true };
  }

  /**
   * Takes a token, verifies it with `GET /whoami`, and keeps it: answers
   * `{ login, name }` or `{ error }` (the engine's text for a 401 or 403;
   * nothing is stored then).
   */
  async function signIn(raw) {
    const bad = tokenProblem(raw);
    if (bad) return { error: bad };
    const candidate = raw.trim();
    const enc = encryptionProblem();
    if (enc) return { error: enc };
    const r = await whoami(candidate);
    const refused = whoamiProblem(r);
    if (refused) { log(`[engine] sign in refused: ${refused}`); return { error: refused }; }
    try {
      fs.writeFileSync(file, safeStorage.encryptString(candidate), { mode: 0o600 });
    } catch (e) {
      return { error: `could not save the token: ${text(e)}` };
    }
    token = candidate;
    who = { login: r.login, name: r.name, tokenId: r.tokenId };
    lastError = null; loadError = null;
    log(`[engine] sign in as @${who.login} (${who.name})`);
    return { login: who.login, name: who.name };
  }

  /** Forgets the token: the file is removed and memory cleared. */
  function signOut() {
    const was = who;
    token = null; who = null; lastError = null; loadError = null;
    try { fs.unlinkSync(file); }
    catch (e) { if (!(e && e.code === "ENOENT")) return { error: `could not remove the saved token: ${text(e)}` }; }
    log(`[engine] sign out${was ? ` (@${was.login}, ${was.name})` : ""}`);
    return { ok: true };
  }

  /**
   * `{ signedIn, login, name, error }`, re-verified with `GET /whoami` when a
   * token is held: a token revoked or refused since sign-in shows the engine's
   * text as `error` while `signedIn` stays true and the header keeps being
   * sent (the app keeps working with the login header, as the ticket says).
   */
  async function status() {
    if (!token) return { signedIn: false, login: null, name: null, error: loadError };
    const r = await whoami(token);
    const refused = whoamiProblem(r);
    if (refused) {
      lastError = refused;
      return { signedIn: true, login: who ? who.login : null, name: who ? who.name : null, error: refused };
    }
    who = { login: r.login, name: r.name, tokenId: r.tokenId };
    lastError = null;
    return { signedIn: true, login: who.login, name: who.name, error: null };
  }

  /** The `Authorization` header's value, or null when signed out. Main and engine-data only. */
  const bearer = () => (token ? `Bearer ${token}` : null);

  /** The login the token resolved to, from the last whoami, or null. */
  const login = () => (token && who ? who.login : null);

  /** The engine's id for the token, from the last whoami, or null (main revokes the First mate token by it). */
  const tokenId = () => (token && who ? who.tokenId || null : null);

  return { load, signIn, signOut, status, bearer, login, tokenId, signedIn: () => Boolean(token), lastError: () => lastError };
}

module.exports = { createEngineToken, TOKEN_PREFIX, TOKEN_FILE };
