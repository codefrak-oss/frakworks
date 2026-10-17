// hitlp-surface.js — the desktop as a HITLP surface (#2487; codefrak-oss/hitlp
// spec/hitlp.md §7.8): the standalone HITLP server's surface API, read and
// answered with the signed-in person's surface credential. The engine no longer
// serves HITLP tasks (#2485); the Needs view's `ask` and `approve` rows and the
// urgent popup come from here.
//
// The base URL (ending `/surface/v1`) and the credential are taken once in
// Config > frakworks > HITLP server, verified with `GET /tasks` (a 401 or 403
// stores nothing), and kept by a *keeper*: in main, `safeStorageKeeper`
// (encrypted with Electron's `safeStorage` in `<userData>/hitlp-surface.bin`,
// 0600); in the web version (src/web/fleet-web.js, #2489), `storageKeeper`
// (one JSON value in the tab's sessionStorage). The credential leaves this
// module only in the `Authorization: Bearer` header of its own requests:
// `status()`, `set()` and `clear()` answer the URL alone, which is all the
// renderer ever sees.
//
// Calls: `GET /tasks`, `GET /tasks/{id}`, `POST /tasks/{id}/decision` and
// `GET /events` (SSE, `event: task`), which `subscribe(onTask)` holds open with
// a capped backoff, as src/engine-events.js holds the engine's. Everything is
// `fetch` and `body.getReader()`, so the same code runs in main and in a
// browser; in a browser the HITLP server is another origin and must allow the
// page's (CORS), which the network-failure message names.
"use strict";

const { parseSse } = require("./engine-events");

const FILE = "hitlp-surface.bin";
/** The sessionStorage key the web version keeps `{ url, credential }` under. */
const HITLP_SURFACE_KEY = "frakworks.hitlpSurface";
const TIMEOUT_MS = 15 * 1000;
const BACKOFF_MIN_MS = 1000;
const BACKOFF_MAX_MS = 30 * 1000;
const OUTCOMES = ["answered", "approved", "rejected"];

const text = (e) => (e && e.message) || String(e);

/** The base URL without trailing slashes, or null when it is not an http(s) URL. */
const normalizeUrl = (raw) => {
  const s = typeof raw === "string" ? raw.trim().replace(/\/+$/, "") : "";
  if (!s) return null;
  try {
    const u = new URL(s);
    return u.protocol === "http:" || u.protocol === "https:" ? s : null;
  } catch { return null; }
};

/** The task list out of a `GET /tasks` body: a bare array or `{ tasks }`. */
const tasksOf = (v) => (Array.isArray(v) ? v : v && Array.isArray(v.tasks) ? v.tasks : []);

const typeOk = (t, v) => {
  switch (t) {
    case "string": return typeof v === "string";
    case "number": return typeof v === "number" && Number.isFinite(v);
    case "integer": return Number.isInteger(v);
    case "boolean": return typeof v === "boolean";
    case "null": return v === null;
    case "array": return Array.isArray(v);
    case "object": return !!v && typeof v === "object" && !Array.isArray(v);
    default: return true;
  }
};

/**
 * Why `answer` does not fit `schema` (a JSON Schema's `type`, `enum`,
 * `required`, `properties` and `items`, the parts an ask's `responseSchema`
 * uses), or null when it fits or there is no schema. The server checks again;
 * this keeps a bad answer from leaving the app.
 */
function schemaProblem(schema, answer, at = "the answer") {
  if (!schema || typeof schema !== "object") return null;
  if (Array.isArray(schema.enum) && !schema.enum.some((e) => JSON.stringify(e) === JSON.stringify(answer))) {
    return `${at} must be one of ${schema.enum.map((e) => JSON.stringify(e)).join(", ")}`;
  }
  if (schema.type !== undefined) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!types.some((t) => typeOk(t, answer))) return `${at} must be ${types.join(" or ")}`;
  }
  if (answer && typeof answer === "object" && !Array.isArray(answer)) {
    for (const k of Array.isArray(schema.required) ? schema.required : []) {
      if (!(k in answer)) return `${at} needs "${k}"`;
    }
    for (const [k, s] of Object.entries(schema.properties || {})) {
      if (k in answer) { const p = schemaProblem(s, answer[k], `${at}.${k}`); if (p) return p; }
    }
  }
  if (Array.isArray(answer) && schema.items && typeof schema.items === "object") {
    for (let i = 0; i < answer.length; i++) { const p = schemaProblem(schema.items, answer[i], `${at}[${i}]`); if (p) return p; }
  }
  return null;
}

const readPair = (v) => {
  if (!v || typeof v !== "object" || !normalizeUrl(v.url) || typeof v.credential !== "string" || !v.credential) throw new Error("the store holds no URL and credential");
  return { url: normalizeUrl(v.url), credential: v.credential };
};

/**
 * Main's keeper (#2487): `{ url, credential }` encrypted with Electron's
 * `safeStorage` (injected in tests) in `file` (0600). `fs` is Node's,
 * `platform` `process.platform`. `load()` answers the pair, null when there is
 * no file, or `{ error }`; `save`/`clear` answer `{ error }` or nothing;
 * `problem()` says why nothing can be stored on this machine, or null.
 */
function safeStorageKeeper({ safeStorage, file, fs = require("node:fs"), platform = process.platform } = {}) {
  if (!safeStorage) throw new Error("safeStorageKeeper needs safeStorage");
  if (!file) throw new Error("safeStorageKeeper needs the store's path");
  return {
    problem() {
      if (typeof safeStorage.isEncryptionAvailable !== "function" || !safeStorage.isEncryptionAvailable()) {
        return "this machine cannot encrypt the credential at rest (Electron's safeStorage has no keychain here); not stored";
      }
      if (platform === "linux" && typeof safeStorage.getSelectedStorageBackend === "function"
          && safeStorage.getSelectedStorageBackend() === "basic_text") {
        return "this machine would keep the credential in plain text (safeStorage backend basic_text); not stored";
      }
      return null;
    },
    load() {
      let bytes;
      try { bytes = fs.readFileSync(file); }
      catch (e) {
        if (e && e.code === "ENOENT") return null;
        return { error: `could not read the saved HITLP server: ${text(e)}` };
      }
      try { return readPair(JSON.parse(safeStorage.decryptString(bytes))); }
      catch (e) { return { error: `the saved HITLP server could not be read (${text(e)}); set it again` }; }
    },
    save(url, credential) {
      try { fs.writeFileSync(file, safeStorage.encryptString(JSON.stringify({ url, credential })), { mode: 0o600 }); }
      catch (e) { return { error: `could not save the credential: ${text(e)}` }; }
      return undefined;
    },
    clear() {
      try { fs.unlinkSync(file); }
      catch (e) { if (!(e && e.code === "ENOENT")) return { error: `could not remove the saved credential: ${text(e)}` }; }
      return undefined;
    },
  };
}

/**
 * The web version's keeper (#2489): one JSON string under `key` in `storage`,
 * a Web Storage object (the tab's sessionStorage: gone with the tab, never in
 * a URL or the repo). `problem()` is null: the page is the person's own.
 */
function storageKeeper(storage, key = HITLP_SURFACE_KEY) {
  if (!storage) throw new Error("storageKeeper needs a Storage");
  return {
    problem: () => null,
    load() {
      let raw;
      try { raw = storage.getItem(key); } catch (e) { return { error: `could not read the saved HITLP server: ${text(e)}` }; }
      if (raw == null || raw === "") return null;
      try { return readPair(JSON.parse(raw)); }
      catch (e) { return { error: `the saved HITLP server could not be read (${text(e)}); set it again` }; }
    },
    save(url, credential) {
      try { storage.setItem(key, JSON.stringify({ url, credential })); }
      catch (e) { return { error: `could not save the credential: ${text(e)}` }; }
      return undefined;
    },
    clear() {
      try { storage.removeItem(key); }
      catch (e) { return { error: `could not remove the saved credential: ${text(e)}` }; }
      return undefined;
    },
  };
}

/** The page's origin when this runs in a browser, null in main. */
const pageOrigin = () => (typeof window !== "undefined" && window.location && window.location.origin ? window.location.origin : null);

/**
 * Why a thrown fetch failed. In a browser the failure is opaque (`TypeError:
 * Failed to fetch`), so the message names the likely causes, CORS among them.
 */
const unreachable = (base, e, origin) => {
  const why = text(e);
  if (!origin || !(e instanceof TypeError)) return `HITLP server unreachable at ${base}: ${why}`;
  return `the browser could not reach ${base} (${why}): unreachable, or it does not allow this page's origin: it must answer the preflight and send Access-Control-Allow-Origin for ${origin} and Access-Control-Allow-Headers: authorization, content-type`;
};

/**
 * `keeper` holds the URL and credential (`safeStorageKeeper` or
 * `storageKeeper`); main's call passes `safeStorage` and `file` (with `fs` and
 * `platform`) instead and gets the safeStorage keeper built here. `fetch` is
 * the global one; `log(line)` the console; `setTimer`/`clearTimer` the
 * reconnect's timers; `origin` the page's own when in a browser (for the
 * network-failure message), null in main.
 */
function createHitlpSurface({ keeper = null, safeStorage, file, fs, fetch = (...a) => globalThis.fetch(...a), log = () => {},
  platform, setTimer = setTimeout, clearTimer = clearTimeout, origin = pageOrigin() } = {}) {
  const store = keeper || safeStorageKeeper({ safeStorage, file, ...(fs ? { fs } : {}), ...(platform ? { platform } : {}) });
  let url = null;
  let credential = null;
  let lastError = null;

  /** One request to the surface API; `{ ok, status, value }` or `{ error, status }`, never rejects. */
  async function call(method, path, body, { base = url, cred = credential } = {}) {
    if (!base || !cred) return { error: "no HITLP server set: Config > frakworks > HITLP server" };
    const ctl = new AbortController();
    const t = setTimer(() => ctl.abort(), TIMEOUT_MS);
    try {
      const init = { method, signal: ctl.signal, headers: { authorization: `Bearer ${cred}`, accept: "application/json" } };
      if (body !== undefined) { init.headers["content-type"] = "application/json"; init.body = JSON.stringify(body); }
      const res = await fetch(`${base}${path}`, init);
      const raw = await res.text().catch(() => "");
      if (!res.ok) {
        let why = raw;
        try { const j = JSON.parse(raw); why = j.message || j.error || raw; } catch { /* plain text */ }
        const lead = res.status === 401 || res.status === 403 ? "the HITLP server refused the credential" : `the HITLP server answered ${res.status}`;
        return { error: `${lead} for ${method} ${path}${why ? `: ${String(why).slice(0, 200)}` : ""}`, status: res.status };
      }
      if (!raw) return { ok: true, status: res.status, value: null };
      try { return { ok: true, status: res.status, value: JSON.parse(raw) }; }
      catch (e) { return { error: `the HITLP server produced no JSON for ${method} ${path}: ${e.message}` }; }
    } catch (e) {
      if (e && e.name === "AbortError") return { error: `HITLP server unreachable at ${base}: timed out after ${TIMEOUT_MS / 1000}s` };
      return { error: unreachable(base, e, origin) };
    } finally { clearTimer(t); }
  }

  /** Reads the store back; an empty store is not set. */
  function load() {
    url = null; credential = null; lastError = null;
    const v = store.load();
    if (!v) return { signedIn: false };
    if (v.error) {
      lastError = v.error;
      log(`[hitlp-surface] ${lastError}`);
      return { signedIn: false, error: lastError };
    }
    url = v.url; credential = v.credential;
    return { signedIn: true, url };
  }

  /**
   * Takes a base URL and a credential, verifies them with `GET /tasks`, and
   * keeps them: `{ url }` or `{ error }` (nothing is stored on any error).
   */
  async function set(rawUrl, rawCredential) {
    const base = normalizeUrl(rawUrl);
    if (!base) return { error: "type the HITLP server's surface URL (https://…/surface/v1)" };
    const cred = typeof rawCredential === "string" ? rawCredential.trim() : "";
    if (!cred) return { error: "paste your surface credential" };
    const enc = store.problem();
    if (enc) return { error: enc };
    const r = await call("GET", "/tasks", undefined, { base, cred });
    if (r.error) { log(`[hitlp-surface] set refused: ${r.error}`); return { error: r.error }; }
    const saved = store.save(base, cred);
    if (saved && saved.error) return { error: saved.error };
    url = base; credential = cred; lastError = null;
    log(`[hitlp-surface] set ${base}`);
    return { url: base };
  }

  /** Forgets both: the store is emptied and memory cleared. */
  function clear() {
    const was = url;
    url = null; credential = null; lastError = null;
    const r = store.clear();
    if (r && r.error) return { error: r.error };
    log(`[hitlp-surface] cleared${was ? ` (${was})` : ""}`);
    return { ok: true };
  }

  /** `{ signedIn, url, error }`; never the credential. */
  const status = () => ({ signedIn: !!(url && credential), url, error: lastError });
  const configured = () => !!(url && credential);

  /** The open tasks, `GET /tasks`: `{ tasks }` or `{ error, tasks: [] }`; `{ tasks: [] }` when not set. */
  async function list() {
    if (!configured()) return { tasks: [] };
    const r = await call("GET", "/tasks");
    if (r.error) return { error: r.error, tasks: [] };
    return { tasks: tasksOf(r.value).filter((t) => t && typeof t === "object") };
  }

  /** One task, `GET /tasks/{id}`: `{ task }` or `{ error }`. */
  async function get(id) {
    const tid = typeof id === "string" ? id.trim() : "";
    if (!tid) return { error: "no HITLP task id" };
    const r = await call("GET", `/tasks/${encodeURIComponent(tid)}`);
    return r.error ? { error: r.error } : { task: r.value && r.value.task ? r.value.task : r.value };
  }

  /**
   * Decides task `id`, `POST /tasks/{id}/decision`: `answered` with `answer`
   * (checked against the task's `responseSchema` first), `approved` or
   * `rejected` with an optional `reason` and the `payloadDigest` the person was
   * shown. The body is the spec's (HITLP spec/schemas/surface/decide.request.schema.json):
   * `{ answer, reason? }` for an ask, `{ outcome: approve | reject, reason?, payloadDigest? }`
   * for an approve, and nothing else; the server records the channel from the surface's
   * kind. `{ ok }` or `{ error }`.
   */
  async function decide(id, { outcome, answer = null, reason = null, payloadDigest = null } = {}) {
    const tid = typeof id === "string" ? id.trim() : "";
    if (!tid) return { error: "no HITLP task id to decide" };
    if (!OUTCOMES.includes(outcome)) return { error: `not a HITLP outcome: ${JSON.stringify(outcome)} (one of ${OUTCOMES.join(", ")})` };
    const body = {};
    if (outcome === "answered") {
      const g = await get(tid);
      if (g.error) return { error: g.error };
      const bad = schemaProblem(g.task && g.task.responseSchema, answer);
      if (bad) return { error: bad };
      body.answer = answer;
    } else {
      body.outcome = outcome === "approved" ? "approve" : "reject";
      if (typeof payloadDigest === "string" && payloadDigest) body.payloadDigest = payloadDigest;
      if (reason !== undefined && reason !== null && String(reason).trim()) body.reason = String(reason).trim();
    }
    const r = await call("POST", `/tasks/${encodeURIComponent(tid)}/decision`, body);
    if (r.error && r.status === 404) return { error: "the HITLP server has no such task" };
    if (r.error && r.status === 409) return { error: "the task is already decided (or timed out)" };
    return r.error ? { error: r.error } : { ok: true };
  }

  // The `GET /events` subscription, at most one.
  let sub = null;

  /**
   * Holds `GET /events` open and calls `onTask(data)` on each `event: task`
   * (`data` parsed, or the raw string) and once on every (re)connect, since a
   * change while down was missed. Reconnects with a capped backoff. Returns a
   * function that stops it. A new call stops the previous one.
   */
  function subscribe(onTask) {
    unsubscribe();
    if (!configured()) return () => {};
    const me = { stopped: false, ctl: null, timer: null, delay: BACKOFF_MIN_MS };
    sub = me;
    const base = url, cred = credential;
    const again = () => {
      if (me.stopped) return;
      me.timer = setTimer(connect, me.delay);
      me.delay = Math.min(me.delay * 2, BACKOFF_MAX_MS);
    };
    async function connect() {
      if (me.stopped) return;
      me.ctl = new AbortController();
      try {
        const res = await fetch(`${base}/events`, { signal: me.ctl.signal, headers: { authorization: `Bearer ${cred}`, accept: "text/event-stream" } });
        if (!res.ok || !res.body) { log(`[hitlp-surface] events: answered ${res.status}`); again(); return; }
        me.delay = BACKOFF_MIN_MS;
        onTask(null);
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let rest = "";
        for (;;) {
          const { value, done } = await reader.read();
          if (done || me.stopped) break;
          const parsed = parseSse(rest + decoder.decode(value, { stream: true }));
          rest = parsed.rest;
          for (const f of parsed.frames) {
            if (f.type !== "task") continue;
            let data = f.data;
            try { data = JSON.parse(f.data); } catch { /* raw */ }
            onTask(data);
          }
        }
      } catch (e) {
        if (!me.stopped) log(`[hitlp-surface] events: ${me.ctl.signal.aborted ? text(e) : unreachable(`${base}/events`, e, origin)}`);
      }
      again();
    }
    connect();
    return () => { if (sub === me) unsubscribe(); };
  }

  function unsubscribe() {
    if (!sub) return;
    sub.stopped = true;
    if (sub.timer) clearTimer(sub.timer);
    if (sub.ctl) { try { sub.ctl.abort(); } catch { /* gone */ } }
    sub = null;
  }

  return { load, set, clear, status, configured, list, get, decide, subscribe, unsubscribe };
}

module.exports = { createHitlpSurface, safeStorageKeeper, storageKeeper, schemaProblem, normalizeUrl, tasksOf, FILE, HITLP_SURFACE_KEY, OUTCOMES, BACKOFF_MIN_MS, BACKOFF_MAX_MS };
