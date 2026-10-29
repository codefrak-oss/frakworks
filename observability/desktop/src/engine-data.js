// engine-data.js — the main process's reads from the engine (engine/README.md).
// The first of the app's data sources that is not the old fleet's: one base
// URL, plain JSON over HTTP, no `gh`, no VictoriaMetrics, no credential — the
// engine holds each target repo's token and proxies GitHub reads for them.
//
// Same never-rejects contract as the other fetchers: every failure (engine
// down, a non-2xx, a body that is not JSON, a timeout) becomes `{ error }`,
// which the renderer draws in its `.err` block.
//
// Environment:
//   ENGINE_URL             the engine (default http://localhost:8090, the
//                          local stack's published port; or an https URL,
//                          an engine on a cluster, see trustSystemCAs)
//   ENGINE_TIMEOUT_SECS    per-request timeout (default 20)
"use strict";

const { credentialProblem } = require("./credential-rules");

const DEFAULT_ENGINE_URL = "http://localhost:8090";
const DEFAULT_TIMEOUT_SECS = 20;
/** A repo's child-work modes (engine/README.md, "Split features"), the values `childMode` takes. */
const CHILD_MODES = Object.freeze(["serial", "parallel", "live"]);

// In a browser page (the web version, src/web/) there is no `process`: the
// engine is the page's own origin, so every URL below is a relative path.
const PAGE_ENV = Object.freeze({ ENGINE_URL: "" });
const PROCESS_ENV = typeof process !== "undefined" && process.env ? process.env : PAGE_ENV;

const engineUrl = (env = PROCESS_ENV) => (env === PAGE_ENV ? "" : (env.ENGINE_URL || DEFAULT_ENGINE_URL)).replace(/\/+$/, "");
const timeoutMs = (env = PROCESS_ENV) => Number(env.ENGINE_TIMEOUT_SECS || DEFAULT_TIMEOUT_SECS) * 1000;

// The bearer token (#2206): main sets
// `setBearer(() => engineToken.bearer())` once, and every request below spreads
// `authHeaders()` into its headers, so each read and write carries
// `Authorization: Bearer <token>` while the desktop is signed in and nothing
// while it is not. The value is read per request, never kept here.
let bearerFn = () => null;
function setBearer(fn) { bearerFn = typeof fn === "function" ? fn : () => null; }
function authHeaders() {
  let v = null;
  try { v = bearerFn(); } catch { v = null; }
  return typeof v === "string" && v ? { authorization: v } : {};
}

// The engine refuses every request without a valid token (401): an engine view then shows the Sign
// in section's prompt, never a login of its own.
const SIGN_IN_PROMPT = "not signed in to the engine: sign in under Config > frakworks > Sign in";
const signInError = (status, what) => (status === 401 ? `${SIGN_IN_PROMPT} (engine answered 401 for ${what})` : null);

/**
 * Adds the system's trusted CAs (the macOS keychain, the OS store) to the ones
 * Node's fetch trusts, which are only its bundled Mozilla list: an
 * `ENGINE_URL` like https://engine.k8s.example, signed by a lab CA the machine
 * trusts, then works like any other. Returns whether it could; a Node without
 * `tls.setDefaultCACertificates` keeps the bundled list.
 */
function trustSystemCAs(tls = require("node:tls")) {
  if (typeof tls.setDefaultCACertificates !== "function" || typeof tls.getCACertificates !== "function") return false;
  tls.setDefaultCACertificates([...new Set([...tls.getCACertificates("default"), ...tls.getCACertificates("system")])]);
  return true;
}

/**
 * One fetch, retried once after `RETRY_DELAY_MS` when the engine answered
 * 502/503 or refused the connection: a few seconds with no ready engine
 * endpoint (a lease handover, engine/README.md "Replicas and the lease") is
 * ridden out rather than shown. Anything else, including a timeout, is not
 * retried.
 */
const RETRY_STATUSES = new Set([502, 503]);
const RETRY_DELAY_MS = 500;
const refused = (e) => !!e && (e.code === "ECONNREFUSED" || (e.cause && e.cause.code === "ECONNREFUSED"));
async function fetchRetrying(fetch, url, init, delayMs = RETRY_DELAY_MS) {
  let res;
  try { res = await fetch(url, init); } catch (e) { if (!refused(e)) throw e; }
  if (res && !RETRY_STATUSES.has(res.status)) return res;
  await new Promise((r) => setTimeout(r, delayMs));
  return fetch(url, init);
}

/**
 * GET <ENGINE_URL><path>, parsed. Resolves to { value } or { error }; never
 * rejects. `fetch` is injectable so tests run without a server.
 */
async function getJson(path, { env = PROCESS_ENV, fetch = globalThis.fetch, retryDelayMs } = {}) {
  const url = `${engineUrl(env)}${path}`;
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs(env));
  try {
    const res = await fetchRetrying(fetch, url, { signal: ctl.signal, headers: { ...authHeaders(), accept: "application/json" } }, retryDelayMs);
    const text = await res.text();
    if (!res.ok) return { error: signInError(res.status, path) || `engine answered ${res.status} for ${path}${text ? `: ${text.slice(0, 200)}` : ""}` };
    try { return { value: JSON.parse(text) }; }
    catch (e) { return { error: `engine produced no JSON for ${path}: ${e.message}` }; }
  } catch (e) {
    const why = e && e.name === "AbortError" ? `timed out after ${timeoutMs(env) / 1000}s` : (e && e.message) || String(e);
    return { error: `engine unreachable at ${url}: ${why}` };
  } finally { clearTimeout(t); }
}

// `?repo=owner/name` for one repo, nothing for every target repo; a malformed
// repo is refused before any request, as fetchClosedIssues does.
const repoQuery = (repo) => {
  if (repo === undefined || repo === null) return { query: "" };
  if (typeof repo !== "string" || !REPO_FULL.test(repo)) return { error: `not an owner/name repo: ${JSON.stringify(repo)}` };
  return { query: `?repo=${encodeURIComponent(repo)}` };
};

/**
 * The issues board, `GET /issues`: every open issue of every target repo with
 * the engine's state joined on (engine/README.md, "The issues board"), or of
 * one, `opts.repo` (`GET /issues?repo=`, the repo switcher's choice).
 * Resolves to { issues, failures } or { error, issues: [] }.
 */
async function fetchIssues(opts = {}) {
  const q = repoQuery(opts.repo);
  if (q.error) return { error: q.error, issues: [], failures: [] };
  const r = await getJson(`/issues${q.query}`, opts);
  if (r.error) return { error: r.error, issues: [], failures: [] };
  const b = r.value;
  if (!b || !Array.isArray(b.issues)) return { error: "engine's /issues has no `issues` list", issues: [], failures: [] };
  return { issues: b.issues, failures: Array.isArray(b.failures) ? b.failures : [], error: null };
}

// An Operaton process instance id (a UUID, as the engine hands them out), and
// the only thing put into the flow URL's path.
const MAX_VARIABLE_CHARS = 4000;
const INSTANCE_ID = /^[0-9a-f][0-9a-f-]{7,63}$/i;

/**
 * One flow as the engine sees it, `GET /intake/<instance>` (engine/README.md;
 * it answers for a chore's id too): `ended`, every step taken across the
 * called processes, the variables, and the questions asked on it. Resolves
 * to { flow } or { error }.
 */
async function fetchFlow(instance, opts = {}) {
  if (typeof instance !== "string" || !INSTANCE_ID.test(instance)) return { error: `not a flow instance id: ${JSON.stringify(instance)}` };
  const r = await getJson(`/intake/${instance}`, opts);
  if (r.error) return { error: / 404 /.test(r.error) ? `the engine has no flow ${instance}` : r.error };
  const f = r.value;
  if (!f || !Array.isArray(f.steps)) return { error: `engine's /intake/${instance} has no \`steps\` list` };
  // The run log and the Smith's output ride along as base64 blobs hundreds of
  // KB long; the view never draws them, so they stop here and only their
  // names cross to the renderer.
  const variables = {}, omitted = [];
  for (const [k, v] of Object.entries(f.variables || {})) {
    if (typeof v === "string" && v.length > MAX_VARIABLE_CHARS) omitted.push(k); else variables[k] = v;
  }
  return { flow: { ...f, questions: Array.isArray(f.questions) ? f.questions : [], variables, omitted }, error: null };
}

/**
 * The AI accounts, `GET /accounts` (engine/README.md, "AI accounts"), each
 * with its plan meter from `GET /accounts/usage` joined on by name. The
 * engine never sends a credential, only `hasCredential`. The meter is extra:
 * when it fails the accounts still draw, with `usageError` said beside them.
 * Resolves to { accounts, usageError } or { error, accounts: [] }.
 */
async function fetchAccounts(opts = {}) {
  const [a, u] = await Promise.all([getJson("/accounts", opts), getJson("/accounts/usage", opts)]);
  const none = { accounts: [], usageError: null };
  if (a.error) return { ...none, error: a.error };
  if (!Array.isArray(a.value)) return { ...none, error: "engine's /accounts is not a list" };
  const usage = !u.error && Array.isArray(u.value) ? u.value : [];
  const usageError = u.error || (Array.isArray(u.value) ? null : "engine's /accounts/usage is not a list");
  const byName = new Map(usage.map((x) => [x.account, x]));
  // `running`: the live Smith runs per model, [{ model, count }]; an older engine sends none, read as [].
  const accounts = a.value.map((acct) => ({ ...acct, running: Array.isArray(acct.running) ? acct.running : [], usage: byName.get(acct.name) || null }));
  return { accounts, usageError, error: null };
}

/**
 * A write to the engine: <method> <ENGINE_URL><path> with a JSON body.
 * Resolves to { ok: true, status } or { error }; never rejects. The body is
 * never echoed into the error, since it may carry a credential.
 */
// `headers` adds request headers;
// `keep` adds the engine's `status` and whole `body` text to an error, for a
// view that shows the engine's answer verbatim (Config > Jobs).
async function sendJson(method, path, body, { env = PROCESS_ENV, fetch = globalThis.fetch, parse = false, headers = null, keep = false, retryDelayMs } = {}) {
  const url = `${engineUrl(env)}${path}`;
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs(env));
  try {
    const init = { method, signal: ctl.signal, headers: { ...authHeaders(), accept: "application/json", ...(headers || {}) } };
    if (body !== undefined) { init.headers["content-type"] = "application/json"; init.body = JSON.stringify(body); }
    const res = await fetchRetrying(fetch, url, init, retryDelayMs);
    if (res.ok && parse) {
      const text = await res.text().catch(() => "");
      try { return { ok: true, status: res.status, value: JSON.parse(text) }; } catch { return { error: `engine's ${method} ${path} produced no JSON` }; }
    }
    if (res.ok) return { ok: true, status: res.status };
    const text = await res.text().catch(() => "");
    let why = text;
    try { const j = JSON.parse(text); why = j.message || j.error || text; } catch { /* plain text */ }
    const error = signInError(res.status, `${method} ${path}`) || `engine answered ${res.status} for ${method} ${path}${why ? `: ${String(why).slice(0, 200)}` : ""}`;
    return keep ? { error, status: res.status, body: why ? String(why) : "" } : { error };
  } catch (e) {
    const why = e && e.name === "AbortError" ? `timed out after ${timeoutMs(env) / 1000}s` : (e && e.message) || String(e);
    return { error: `engine unreachable at ${url}: ${why}` };
  } finally { clearTimeout(t); }
}

/**
 * First mate's account, `POST /accounts/pick` (engine/README.md, "First mate's
 * account"): the account a Smith on `model` would get, with its long-lived
 * token. Resolves to { ok, account, envVar, token } or { error }; never
 * rejects. The token goes to the child's environment and nowhere else: never
 * logged, saved or sent to the renderer.
 */
async function pickAccount(model, opts = {}) {
  if (typeof model !== "string" || !model.trim()) return { error: "no model to pick an account for" };
  const r = await sendJson("POST", "/accounts/pick", { model: model.trim() }, { ...opts, parse: true });
  if (r.error) return { error: r.error };
  const v = r.value;
  if (!v || typeof v.account !== "string" || typeof v.env !== "string" || typeof v.token !== "string" || !v.token) {
    return { error: "engine's POST /accounts/pick had no account, env or token" };
  }
  return { ok: true, account: v.account, envVar: v.env, token: v.token };
}

/**
 * The desktop First mate's HITLP lineage (engine/README.md, "Lineage"), `POST
 * /first-mate/lineage {ask}` with the signed-in token: resolves to
 * { ok, lineage } where `lineage` is the engine's JSON as text, which the
 * session is handed unchanged, or { error }; never rejects.
 */
async function fetchLineage(ask, opts = {}) {
  if (typeof ask !== "string" || !ask.trim()) return { error: "a lineage needs the chat's ask" };
  const r = await sendJson("POST", "/first-mate/lineage", { ask: ask.trim().slice(0, 100000) }, { ...opts, parse: true });
  if (r.error) return { error: r.error };
  const v = r.value;
  if (!v || typeof v.asOf !== "string" || !Array.isArray(v.links)) return { error: "engine's POST /first-mate/lineage had no asOf or links" };
  return { ok: true, lineage: JSON.stringify(v) };
}

/**
 * The intake refiner's own tier, `GET /intake/tier` (engine/README.md,
 * "Intake"): a setting the engine keeps in its database, shown above the
 * account tiles. Resolves to { refinerTier: { tier, model, effort } } or
 * { error }; never rejects.
 */
async function fetchRefinerTier(opts = {}) {
  const r = await getJson("/intake/tier", opts);
  if (r.error) return { error: r.error };
  const v = r.value;
  if (!v || !Number.isInteger(v.tier)) return { error: "engine's /intake/tier has no integer `tier`" };
  return { refinerTier: { tier: v.tier, model: String(v.model || ""), effort: String(v.effort || "") }, error: null };
}

/**
 * Sets the refiner's tier, `PUT /intake/tier` `{ tier }`. The engine refuses
 * a tier its map has no row for (a 400, said back); an integer outside 1-10
 * is refused here first. Resolves to
 * { ok, status, refinerTier } or { error }; never rejects.
 */
async function setRefinerTier(tier, opts = {}) {
  if (!Number.isInteger(tier) || tier < 1 || tier > 10) return { error: "the refiner tier is an integer from 1 to 10" };
  const r = await sendJson("PUT", "/intake/tier", { tier }, { ...opts, parse: true });
  if (r.error) return r;
  const v = r.value;
  if (!v || !Number.isInteger(v.tier)) return { error: "engine's PUT /intake/tier answered no integer `tier`" };
  return { ok: true, status: r.status, refinerTier: { tier: v.tier, model: String(v.model || ""), effort: String(v.effort || "") } };
}

/**
 * Whether the engine hosts frakpanel tiles, `GET /frakpanel/tiles/enabled`
 * (engine/README.md, "frakpanel tiles"), with the AI accounts tile's URL on
 * this engine. Resolves to { frakpanelTiles: { enabled, url } } or { error };
 * never rejects.
 */
async function fetchFrakpanelTiles(opts = {}) {
  const r = await getJson("/frakpanel/tiles/enabled", opts);
  if (r.error) return { error: r.error };
  const v = r.value;
  if (!v || typeof v.enabled !== "boolean") return { error: "engine's /frakpanel/tiles/enabled has no boolean `enabled`" };
  return { frakpanelTiles: { enabled: v.enabled, url: `${engineUrl(opts.env)}/frakpanel/tiles/ai-accounts` }, error: null };
}

/** Turns tile hosting on or off, `PUT /frakpanel/tiles/enabled` `{ enabled }`. Resolves to { ok, status, frakpanelTiles } or { error }; never rejects. */
async function setFrakpanelTiles(enabled, opts = {}) {
  if (typeof enabled !== "boolean") return { error: "enabled is true or false" };
  const r = await sendJson("PUT", "/frakpanel/tiles/enabled", { enabled }, { ...opts, parse: true });
  if (r.error) return r;
  const v = r.value;
  if (!v || typeof v.enabled !== "boolean") return { error: "engine's PUT /frakpanel/tiles/enabled answered no boolean `enabled`" };
  return { ok: true, status: r.status, frakpanelTiles: { enabled: v.enabled, url: `${engineUrl(opts.env)}/frakpanel/tiles/ai-accounts` } };
}

/** The places a Smith can run, as the engine's `/settings/smith-hosting` names them (engine/README.md, "Hosting models"). */
const SMITH_HOSTINGS = ["docker", "kubernetes"];

/** The hosting part of the engine's SmithHostingSettings, or null when it is not one of SMITH_HOSTINGS. */
function hostingOf(v) {
  return v && SMITH_HOSTINGS.includes(v.hosting) ? { hosting: v.hosting } : null;
}

/**
 * Where new Smith runs start, `GET /settings/smith-hosting`: `docker` or
 * `kubernetes`. Resolves to { smithHosting: { hosting } } or { error };
 * never rejects.
 */
async function fetchSmithHosting(opts = {}) {
  const r = await getJson("/settings/smith-hosting", opts);
  if (r.error) return { error: r.error };
  const h = hostingOf(r.value);
  if (!h) return { error: `engine's /settings/smith-hosting has no \`hosting\` of ${SMITH_HOSTINGS.join(" or ")}` };
  return { smithHosting: h, error: null };
}

/**
 * Sets the Smith hosting, `PUT /settings/smith-hosting` `{ hosting }` (the
 * Kubernetes settings keep their values). The engine refuses `kubernetes`
 * while a Kubernetes setting is blank (a 400, said back); anything but the
 * two values is refused here first. Resolves to { ok, status, smithHosting }
 * or { error }; never rejects.
 */
async function setSmithHosting(hosting, opts = {}) {
  if (!SMITH_HOSTINGS.includes(hosting)) return { error: `the Smith hosting is ${SMITH_HOSTINGS.join(" or ")}` };
  const r = await sendJson("PUT", "/settings/smith-hosting", { hosting }, { ...opts, parse: true });
  if (r.error) return r;
  const h = hostingOf(r.value);
  if (!h) return { error: "engine's PUT /settings/smith-hosting answered no `hosting`" };
  return { ok: true, status: r.status, smithHosting: h };
}

/** Where the desktop's First mate runs, as `/settings/first-mate-hosting` names it (engine/README.md, "First-mate sessions"). */
const FIRST_MATE_HOSTINGS = ["local", "kubernetes"];

const firstMateHostingOf = (v) => (v && FIRST_MATE_HOSTINGS.includes(v.hosting) ? { hosting: v.hosting } : null);

/**
 * Where a new First mate conversation runs, `GET /settings/first-mate-hosting`:
 * `local` (a `claude` child here) or `kubernetes` (a session pod). Resolves to
 * { firstMateHosting: { hosting } } or { error }; never rejects.
 */
async function fetchFirstMateHosting(opts = {}) {
  const r = await getJson("/settings/first-mate-hosting", opts);
  if (r.error) return { error: r.error };
  const h = firstMateHostingOf(r.value);
  if (!h) return { error: `engine's /settings/first-mate-hosting has no \`hosting\` of ${FIRST_MATE_HOSTINGS.join(" or ")}` };
  return { firstMateHosting: h, error: null };
}

/**
 * Sets the First mate hosting, `PUT /settings/first-mate-hosting` `{ hosting }`.
 * The engine refuses `kubernetes` until Smith hosting is `kubernetes` with its
 * settings filled (a 400, said back); anything but the two values is refused
 * here first. Resolves to { ok, status, firstMateHosting } or { error }.
 */
async function setFirstMateHosting(hosting, opts = {}) {
  if (!FIRST_MATE_HOSTINGS.includes(hosting)) return { error: `the First mate hosting is ${FIRST_MATE_HOSTINGS.join(" or ")}` };
  const r = await sendJson("PUT", "/settings/first-mate-hosting", { hosting }, { ...opts, parse: true });
  if (r.error) return r;
  const h = firstMateHostingOf(r.value);
  if (!h) return { error: "engine's PUT /settings/first-mate-hosting answered no `hosting`" };
  return { ok: true, status: r.status, firstMateHosting: h };
}

// The session API (engine/README.md, "First-mate sessions") takes only a
// `first-mate` purpose token: `bearer` is the `Authorization` value to send
// (main's first-mate token), never the sign-in one. Each answers the engine's
// status with an error, so a 401/403 (sign in) and a 404/409 (the session is
// gone) can be told apart.
const withBearer = (opts, bearer) => ({ ...opts, keep: true, headers: { ...(opts.headers || {}), ...(bearer ? { authorization: bearer } : {}) } });

/** `POST /first-mate/sessions {repo, ask, tier}`: `{ ok, session }` (the engine's row: id, firstLine, …) or `{ error, status }`. */
async function startFirstMateSession(repo, ask, tier, bearer, opts = {}) {
  if (typeof repo !== "string" || !REPO_FULL.test(repo)) return { error: `not an owner/name repo: ${JSON.stringify(repo)}` };
  const body = { repo };
  if (typeof ask === "string" && ask) body.ask = ask;
  if (Number.isInteger(tier)) body.tier = tier;
  const r = await sendJson("POST", "/first-mate/sessions", body, { ...withBearer(opts, bearer), parse: true });
  if (r.error) return { error: r.error, status: r.status || null };
  const v = r.value || {};
  if (typeof v.id !== "string" || !v.id) return { error: "engine's POST /first-mate/sessions answered no id" };
  return { ok: true, session: v };
}

/** `POST /first-mate/sessions/{id}/messages {text, images, clientId}`: `{ ok }` or `{ error, status }`. */
async function sendFirstMateMessage(id, text, images, clientId, bearer, opts = {}) {
  const body = { text: String(text || "") };
  if (Array.isArray(images) && images.length) body.images = images;
  if (typeof clientId === "string" && clientId) body.clientId = clientId;
  const r = await sendJson("POST", `/first-mate/sessions/${encodeURIComponent(id)}/messages`, body, withBearer(opts, bearer));
  return r.error ? { error: r.error, status: r.status || null } : { ok: true };
}

/**
 * `GET /first-mate/sessions/{id}/transcript`: `{ ok, session, messages }`
 * (`messages` the engine's `{seq, role, body, at}` rows, oldest first) or
 * `{ error, status }`. The web version's catch-up on a resume (#100104).
 */
async function fetchFirstMateTranscript(id, bearer, opts = {}) {
  const r = await sendJson("GET", `/first-mate/sessions/${encodeURIComponent(id)}/transcript`, undefined, { ...withBearer(opts, bearer), parse: true });
  if (r.error) return { error: r.error, status: r.status || null };
  const v = r.value || {};
  const messages = Array.isArray(v.messages) ? v.messages : Array.isArray(v) ? v : null;
  if (!messages) return { error: `engine's GET /first-mate/sessions/${id}/transcript answered no messages` };
  return { ok: true, session: v.session || v, messages };
}

/** `DELETE /first-mate/sessions/{id}`: `{ ok }` or `{ error, status }`. */
async function endFirstMateSession(id, bearer, opts = {}) {
  const r = await sendJson("DELETE", `/first-mate/sessions/${encodeURIComponent(id)}`, undefined, withBearer(opts, bearer));
  return r.error ? { error: r.error, status: r.status || null } : { ok: true };
}

/** A tier map row as the engine answers it, the model shortened to the name `claude --model` and the `Tier:` line take (`claude-opus-5-5` -> `opus`). */
function tierRow(v) {
  const model = String((v && v.model) || "");
  const m = /^claude-([a-z]+)-/.exec(model);
  return { tier: v.tier, model: m ? m[1] : model, effort: String((v && v.effort) || "") };
}

/**
 * The engine's tier map, `GET /tiers` (engine/README.md, "Tier resolution"):
 * ten rows, each a tier's model and effort, kept in the engine's database.
 * Resolves to { tiers: [{ tier, model, effort }] } (model short, sorted 1-10)
 * or { error }; never rejects.
 */
async function fetchTiers(opts = {}) {
  const r = await getJson("/tiers", opts);
  if (r.error) return { error: r.error };
  if (!Array.isArray(r.value) || r.value.some((v) => !v || !Number.isInteger(v.tier))) return { error: "engine's /tiers is not a list of rows with an integer `tier`" };
  return { tiers: r.value.map(tierRow).sort((a, b) => a.tier - b.tier), error: null };
}

/**
 * Each tier's account right now, `GET /tiers/accounts` (engine/README.md, "AI
 * accounts"): the engine's peek, never a pick, so asking moves no rotation.
 * Resolves to { rows: [{ tier, model, effort, account, blockedUntil }] }
 * (sorted 1-10) or { error, rows: [] }; never rejects.
 */
async function fetchTierAccounts(opts = {}) {
  const r = await getJson("/tiers/accounts", opts);
  if (r.error) return { error: r.error, rows: [] };
  if (!Array.isArray(r.value) || r.value.some((v) => !v || !Number.isInteger(v.tier))) return { error: "engine's /tiers/accounts is not a list of rows with an integer `tier`", rows: [] };
  return { rows: [...r.value].sort((a, b) => a.tier - b.tier), error: null };
}

/**
 * Sets one tier's model and effort, `PUT /tiers/<n>` `{ model, effort }`. The
 * engine refuses an unknown model or an effort the model does not take (a
 * 400, said back); a tier outside 1-10 or an empty field is refused here
 * first. Resolves to { ok, status, row } or { error }; never rejects.
 */
async function setTier(tier, model, effort, opts = {}) {
  if (!Number.isInteger(tier) || tier < 1 || tier > 10) return { error: "a tier is an integer from 1 to 10" };
  if (typeof model !== "string" || !model.trim()) return { error: "model is empty" };
  if (typeof effort !== "string" || !effort.trim()) return { error: "effort is empty" };
  const r = await sendJson("PUT", `/tiers/${tier}`, { model: model.trim(), effort: effort.trim() }, { ...opts, parse: true });
  if (r.error) return r;
  if (!r.value || !Number.isInteger(r.value.tier)) return { error: `engine's PUT /tiers/${tier} answered no integer \`tier\`` };
  return { ok: true, status: r.status, row: tierRow(r.value) };
}

/**
 * The tier every security review runs at, `GET /security/tier`. Resolves to
 * { securityTier: { tier, model, effort } } or { error }; never rejects.
 */
async function fetchSecurityTier(opts = {}) {
  const r = await getJson("/security/tier", opts);
  if (r.error) return { error: r.error };
  if (!r.value || !Number.isInteger(r.value.tier)) return { error: "engine's /security/tier has no integer `tier`" };
  return { securityTier: tierRow(r.value), error: null };
}

/** Sets the security tier, `PUT /security/tier` `{ tier }`. Resolves to { ok, status, securityTier } or { error }; never rejects. */
async function setSecurityTier(tier, opts = {}) {
  if (!Number.isInteger(tier) || tier < 1 || tier > 10) return { error: "the security tier is an integer from 1 to 10" };
  const r = await sendJson("PUT", "/security/tier", { tier }, { ...opts, parse: true });
  if (r.error) return r;
  if (!r.value || !Number.isInteger(r.value.tier)) return { error: "engine's PUT /security/tier answered no integer `tier`" };
  return { ok: true, status: r.status, securityTier: tierRow(r.value) };
}

/**
 * The tier every post-deployment check run (Smith mode `check`) runs at, `GET /check/tier`. Resolves to
 * { checkTier: { tier, model, effort } } or { error }; never rejects.
 */
async function fetchCheckTier(opts = {}) {
  const r = await getJson("/check/tier", opts);
  if (r.error) return { error: r.error };
  if (!r.value || !Number.isInteger(r.value.tier)) return { error: "engine's /check/tier has no integer `tier`" };
  return { checkTier: tierRow(r.value), error: null };
}

/** Sets the check tier, `PUT /check/tier` `{ tier }`. Resolves to { ok, status, checkTier } or { error }; never rejects. */
async function setCheckTier(tier, opts = {}) {
  if (!Number.isInteger(tier) || tier < 1 || tier > 10) return { error: "the check tier is an integer from 1 to 10" };
  const r = await sendJson("PUT", "/check/tier", { tier }, { ...opts, parse: true });
  if (r.error) return r;
  if (!r.value || !Number.isInteger(r.value.tier)) return { error: "engine's PUT /check/tier answered no integer `tier`" };
  return { ok: true, status: r.status, checkTier: tierRow(r.value) };
}

// The engine's own rules (AiAccountController), checked here too so a typo
// is said before a request is spent and nothing odd lands in a URL path.
const ACCOUNT_NAME = /^[a-z0-9][a-z0-9_.-]*$/;
const PROVIDERS = ["anthropic", "openai", "jev", "codex"];

/**
 * One account action from Config > AI Accounts, validated, then sent:
 *   { op: "add", name, provider }        POST   /accounts
 *   { op: "credential", name, token, provider? }  PUT /accounts/<name>/credential
 *   { op: "rename", name, to }           PUT    /accounts/<name>/name (the engine carries its usage, run stats and Jev calls over)
 *   { op: "enabled", name, enabled }     PUT    /accounts/<name>/enabled
 *   { op: "remove", name }               DELETE /accounts/<name>
 *   { op: "verify", name }               POST   /accounts/<name>/verify
 *   { op: "refreshUsage", name }         POST   /accounts/<name>/usage/refresh (anthropic only: the engine re-reads its meter)
 *   { op: "api-key" | "admin-key", name, key }  PUT /accounts/<name>/api-key | admin-key (anthropic only; the engine tries it first)
 *   { op: "clear-api-key" | "clear-admin-key", name }  DELETE /accounts/<name>/api-key | admin-key
 *   { op: "billing", name, cycleDate, grantCents }  PUT /accounts/<name>/billing (the API credit cycle's date and grant)
 *   { op: "refreshCredit", name }        POST   /accounts/<name>/credit/refresh (re-reads cost_report now)
 *   { op: "refiner-tier", tier }         PUT    /intake/tier (no account: the page's refiner section)
 *   { op: "tier", tier, model, effort }  PUT    /tiers/<tier> (no account: the page's Tiers section)
 *   { op: "security-tier", tier }        PUT    /security/tier (the Tiers section's security row)
 *   { op: "check-tier", tier }           PUT    /check/tier (the Tiers section's post-deployment check row)
 *   { op: "frakpanel-tiles", enabled }   PUT    /frakpanel/tiles/enabled (the frakworks page's frakpanel tiles section)
 *   { op: "smith-hosting", hosting }     PUT    /settings/smith-hosting (the page's Smith hosting section)
 *   { op: "first-mate-hosting", hosting } PUT   /settings/first-mate-hosting (the page's First mate hosting section)
 * Resolves to { ok } or { error }; never rejects. `verify` resolves to
 * { ok, status, verdict }, the engine's Verdict ({ valid, reachable, detail }):
 * a credential the provider refused is still an answer, not an error.
 */
/** The engine's cap on an account's email note. */
const MAX_EMAIL_CHARS = 200;
/** The engine's cap on an account's name. */
const MAX_NAME_CHARS = 100;

/** The prefixes the engine takes for each Anthropic key (ApiKeyKind); `sk-ant-usr-` works as both. */
const API_KEY_PREFIXES = { "api-key": ["sk-ant-api03-", "sk-ant-usr-"], "admin-key": ["sk-ant-admin01-", "sk-ant-usr-"] };

/** Why `key` cannot be the `op` ("api-key" or "admin-key") kind of key, or null; the engine checks the same. */
function apiKeyProblem(op, key) {
  const label = op === "api-key" ? "API key" : "admin key";
  if (!key) return `${label} is empty`;
  const space = key.search(/\s/);
  if (space >= 0) return `${label} has whitespace at character ${space + 1} of ${key.length}; paste it as one unbroken line`;
  if (!API_KEY_PREFIXES[op].some((p) => key.startsWith(p))) return `an Anthropic ${label} starts with ${API_KEY_PREFIXES[op].join(" or ")}`;
  return null;
}

async function accountAction(action, opts = {}) {
  const a = action && typeof action === "object" ? action : {};
  if (a.op === "refiner-tier") return setRefinerTier(a.tier, opts);
  if (a.op === "tier") return setTier(a.tier, a.model, a.effort, opts);
  if (a.op === "security-tier") return setSecurityTier(a.tier, opts);
  if (a.op === "check-tier") return setCheckTier(a.tier, opts);
  if (a.op === "frakpanel-tiles") return setFrakpanelTiles(a.enabled, opts);
  if (a.op === "smith-hosting") return setSmithHosting(a.hosting, opts);
  if (a.op === "first-mate-hosting") return setFirstMateHosting(a.hosting, opts);
  const name = typeof a.name === "string" ? a.name.trim() : "";
  if (!ACCOUNT_NAME.test(name)) return { error: "name is lowercase letters, digits, _ . - (starting with a letter or digit)" };
  const at = `/accounts/${encodeURIComponent(name)}`;
  switch (a.op) {
    case "add":
      if (!PROVIDERS.includes(a.provider)) return { error: `provider is one of ${PROVIDERS.join(", ")}` };
    {
      // `email` is the user's note of whose login it is; the engine never uses it.
      const email = typeof a.email === "string" ? a.email.trim() : "";
      if (email.length > MAX_EMAIL_CHARS) return { error: `email is at most ${MAX_EMAIL_CHARS} characters` };
      return sendJson("POST", "/accounts", email ? { name, provider: a.provider, email } : { name, provider: a.provider }, opts);
    }
    case "email": {
      // Empty clears it.
      const email = typeof a.email === "string" ? a.email.trim() : "";
      if (email.length > MAX_EMAIL_CHARS) return { error: `email is at most ${MAX_EMAIL_CHARS} characters` };
      return sendJson("PUT", `${at}/email`, { email: email || null }, opts);
    }
    case "rename": {
      const to = typeof a.to === "string" ? a.to.trim() : "";
      if (!ACCOUNT_NAME.test(to) || to.length > MAX_NAME_CHARS)
        return { error: `the new name is lowercase letters, digits, _ . - (starting with a letter or digit), at most ${MAX_NAME_CHARS} characters` };
      return sendJson("PUT", `${at}/name`, { name: to }, opts);
    }
    case "credential": {
      const codex = a.provider === "codex";
      const token = typeof a.token === "string" ? (codex ? a.token : a.token.trim()) : "";
      if (codex) {
        if (!token.trim()) return { error: "auth.json is empty" };
        if (new TextEncoder().encode(token).length > 64 * 1024) return { error: "auth.json is over 65536 bytes" };
        const r = await sendJson("PUT", `${at}/credential`, { token }, opts);
        if (r.error && / 503 /.test(r.error)) r.error += " (the engine has no ENGINE_SECRETS_KEY, so it cannot seal a credential)";
        return r;
      }
      // `provider` is optional: without it only the prefix goes unchecked, and the engine checks it.
      const problem = credentialProblem(PROVIDERS.includes(a.provider) ? a.provider : undefined, token);
      if (problem) return { error: problem };
      const r = await sendJson("PUT", `${at}/credential`, { token }, opts);
      // The engine says why it refused (a 400 for a token the provider
      // refused, a 502 when it could not ask); a 503 is a missing sealing
      // key, said again here in the app's words.
      if (r.error && / 503 /.test(r.error)) r.error += " (the engine has no ENGINE_SECRETS_KEY, so it cannot seal a credential)";
      return r;
    }
    case "enabled":
      if (typeof a.enabled !== "boolean") return { error: "enabled is true or false" };
      return sendJson("PUT", `${at}/enabled`, { enabled: a.enabled }, opts);
    case "remove":
      return sendJson("DELETE", at, undefined, opts);
    case "verify": {
      const r = await sendJson("POST", `${at}/verify`, undefined, { ...opts, parse: true });
      if (r.error) return / 404 /.test(r.error) ? { error: `${name} has no credential to verify` } : r;
      const v = r.value;
      if (!v || typeof v !== "object" || typeof v.valid !== "boolean") return { error: `engine's verify for ${name} is not a verdict` };
      return { ok: true, status: r.status, verdict: { valid: v.valid, reachable: v.reachable !== false, detail: String(v.detail || "") } };
    }
    case "refreshUsage":
      // The engine's 409 (no meter), 404 (no credential) or 502 (no reading) comes back as its own error.
      return sendJson("POST", `${at}/usage/refresh`, undefined, opts);
    case "api-key":
    case "admin-key": {
      const problem = apiKeyProblem(a.op, typeof a.key === "string" ? a.key.trim() : "");
      if (problem) return { error: problem };
      // The engine answers 422 with Anthropic's own message for a key it refused, 409 for an admin key with no billing date.
      const r = await sendJson("PUT", `${at}/${a.op}`, { key: a.key.trim() }, opts);
      if (r.error && / 503 /.test(r.error)) r.error += " (the engine has no ENGINE_SECRETS_KEY, so it cannot seal a key)";
      return r;
    }
    case "clear-api-key":
    case "clear-admin-key":
      return sendJson("DELETE", `${at}/${a.op.slice("clear-".length)}`, undefined, opts);
    case "billing": {
      const date = typeof a.cycleDate === "string" ? a.cycleDate.trim() : "";
      if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(date))) return { error: "the billing-cycle date is YYYY-MM-DD" };
      if (!Number.isInteger(a.grantCents) || a.grantCents < 0) return { error: "the grant is a whole number of cents, 0 or more" };
      return sendJson("PUT", `${at}/billing`, { cycleDate: date, grantCents: a.grantCents }, opts);
    }
    case "refreshCredit":
      return sendJson("POST", `${at}/credit/refresh`, undefined, opts);
    default:
      return { error: `unknown account action ${JSON.stringify(a.op)}` };
  }
}

// ---- the process view (engine/README.md, the /camunda endpoints) ----------

// A GitHub issue URL, the only thing put into /camunda/instances' query; and
// a process definition id (`intake:3:<uuid>`), the only thing put into
// /camunda/definitions/<id>/xml's path.
const ISSUE_URL = /^https:\/\/github\.com\/[\w.-]+\/[\w.-]+\/issues\/\d+$/;
const DEFINITION_ID = /^[\w.-]+:\d+:[0-9a-f][0-9a-f-]{7,63}$/i;

/**
 * Every root process instance the engine tied to an issue, newest first,
 * `GET /camunda/instances?issueUrl=`. Resolves to { instances } or { error }.
 */
async function fetchProcessInstances(issueUrl, opts = {}) {
  if (typeof issueUrl !== "string" || !ISSUE_URL.test(issueUrl)) return { error: `not a GitHub issue URL: ${JSON.stringify(issueUrl)}` };
  const r = await getJson(`/camunda/instances?issueUrl=${encodeURIComponent(issueUrl)}`, opts);
  if (r.error) return { error: r.error, instances: [] };
  if (!Array.isArray(r.value)) return { error: "engine's /camunda/instances is not a list", instances: [] };
  return { instances: r.value, error: null };
}

/**
 * One instance's call tree, `GET /camunda/instances/<id>`: the root and every
 * process it called, each with its completed and active activities, incidents
 * and variables. The engine already cuts long values (`truncated`), so nothing
 * is dropped here. Resolves to { tree } or { error }.
 */
async function fetchProcessTree(instance, opts = {}) {
  if (typeof instance !== "string" || !INSTANCE_ID.test(instance)) return { error: `not a process instance id: ${JSON.stringify(instance)}` };
  const r = await getJson(`/camunda/instances/${instance}`, opts);
  if (r.error) return { error: / 404 /.test(r.error) ? `the engine has no process instance ${instance}` : r.error };
  const t = r.value;
  if (!t || !Array.isArray(t.nodes)) return { error: `engine's /camunda/instances/${instance} has no \`nodes\` list` };
  return { tree: t, error: null };
}

/**
 * The BPMN XML a definition was deployed with, `GET /camunda/definitions/<id>/xml`,
 * for the diagram. Resolves to { xml } or { error }; never rejects.
 */
async function fetchProcessXml(definitionId, { env = PROCESS_ENV, fetch = globalThis.fetch } = {}) {
  if (typeof definitionId !== "string" || !DEFINITION_ID.test(definitionId)) return { error: `not a process definition id: ${JSON.stringify(definitionId)}` };
  const path = `/camunda/definitions/${encodeURIComponent(definitionId)}/xml`;
  const url = `${engineUrl(env)}${path}`;
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs(env));
  try {
    const res = await fetch(url, { signal: ctl.signal, headers: { ...authHeaders(), accept: "application/xml" } });
    const text = await res.text();
    if (res.status === 404) return { error: `the engine has no process definition ${definitionId}` };
    if (!res.ok) return { error: signInError(res.status, path) || `engine answered ${res.status} for ${path}${text ? `: ${text.slice(0, 200)}` : ""}` };
    if (!/<(bpmn:)?definitions[\s>]/.test(text)) return { error: `engine's ${path} is not BPMN XML` };
    return { xml: text, error: null };
  } catch (e) {
    const why = e && e.name === "AbortError" ? `timed out after ${timeoutMs(env) / 1000}s` : (e && e.message) || String(e);
    return { error: `engine unreachable at ${url}: ${why}` };
  } finally { clearTimeout(t); }
}

/**
 * Retry one incident, `POST /camunda/incidents/<id>/retry` as GitHub login
 * `by` (engine/README.md, "Incidents"): the engine retries the failed job, or
 * starts a Smith run that ended failed over. Resolves to { ok } or { error }.
 */
async function retryIncident(incidentId, by, opts = {}) {
  if (typeof incidentId !== "string" || !INSTANCE_ID.test(incidentId)) return { error: `not an incident id: ${JSON.stringify(incidentId)}` };
  if (typeof by !== "string" || !GITHUB_LOGIN.test(by)) return { error: "no GitHub login to retry as (sign in to the engine: Config > Sign in)" };
  return sendJson("POST", `/camunda/incidents/${incidentId}/retry`, {}, opts);
}
const GITHUB_LOGIN = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$/;

const MAX_REASON_CHARS = 2000;

/**
 * Cancel one root flow, `DELETE /intake/<id>` as GitHub login `by` with the
 * reason it goes on the issue (engine/README.md, "Cancel and the sweep"): the
 * engine stops its Smith, deletes the flow, closes the issue as not planned
 * and closes an open PR. Refused before any request without a login or with
 * a blank reason. Resolves to { ok, failedWrites } or { error }; `failedWrites`
 * names each GitHub write the engine could not make after the flow stopped.
 */
async function cancelFlow(instanceId, by, reason, opts = {}) {
  if (typeof instanceId !== "string" || !INSTANCE_ID.test(instanceId)) return { error: `not a flow instance id: ${JSON.stringify(instanceId)}` };
  const why = typeof reason === "string" ? reason.trim() : "";
  if (!why) return { error: "a cancel needs a reason" };
  if (why.length > MAX_REASON_CHARS) return { error: `a reason is at most ${MAX_REASON_CHARS} characters` };
  if (typeof by !== "string" || !GITHUB_LOGIN.test(by)) return { error: "no GitHub login to cancel as (sign in to the engine: Config > Sign in)" };
  // sendJson keeps no body on success; this keeps the response to read the engine's failedWrites.
  const fetch = opts.fetch || globalThis.fetch;
  let res = null;
  const r = await sendJson("DELETE", `/intake/${instanceId}`, { reason: why },
    { ...opts, fetch: async (url, init) => (res = await fetch(url, init)) });
  if (r.error) return r;
  let failedWrites = [];
  try {
    const state = JSON.parse(await res.text());
    if (state && Array.isArray(state.failedWrites)) failedWrites = state.failedWrites.map(String);
  } catch { /* no body: nothing failed that the engine said */ }
  return { ...r, failedWrites };
}

/**
 * A ticket's open incident and its latest Fix and Propose a fix,
 * `GET /camunda/incident-fixes?issueUrl=` (engine/README.md, "Fix and Propose
 * a fix"): { incident, fix: { state, fixFailed, ... }, propose: { state,
 * proposal, ... } }, each null when there is none. Resolves to { fixes } or { error }.
 */
async function fetchIncidentFixes(issueUrl, opts = {}) {
  if (typeof issueUrl !== "string" || !ISSUE_URL.test(issueUrl)) return { error: `not a GitHub issue URL: ${JSON.stringify(issueUrl)}` };
  const r = await getJson(`/camunda/incident-fixes?issueUrl=${encodeURIComponent(issueUrl)}`, opts);
  if (r.error) return { error: r.error };
  if (!r.value || typeof r.value !== "object") return { error: "engine's /camunda/incident-fixes is not an object" };
  return { fixes: r.value, error: null };
}

/**
 * A ticket's doctor runs, `GET /doctor/history?issueUrl=` (engine/README.md,
 * "Doctor", "The history"): newest first, each with its position, stuckSince,
 * startedAt, endedAt, diagnosis, remedy, target, outcome and detail.
 * Resolves to { runs } or { error }.
 */
async function fetchDoctorHistory(issueUrl, opts = {}) {
  if (typeof issueUrl !== "string" || !ISSUE_URL.test(issueUrl)) return { error: `not a GitHub issue URL: ${JSON.stringify(issueUrl)}` };
  const r = await getJson(`/doctor/history?issueUrl=${encodeURIComponent(issueUrl)}`, opts);
  if (r.error) return { error: r.error };
  if (!r.value || !Array.isArray(r.value.runs)) return { error: "engine's /doctor/history has no runs list" };
  return { runs: r.value.runs, error: null };
}

// An ISO-8601 date-time with a zone, as the page's window switch sends it
// (`sinceOf`): checked before it goes in the URL.
const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/;

/**
 * The doctor's runs across tickets that started at or after `since` (an ISO
 * instant), newest first, `GET /doctor/runs?since=` (engine/README.md,
 * "Doctor"; #706): the Analytics > Doctor page. An engine older than the
 * endpoint (a 404) is one line saying so. Resolves to { runs, since } or
 * { error }; never rejects.
 */
async function fetchDoctorRuns(since, opts = {}) {
  if (typeof since !== "string" || !ISO_INSTANT.test(since)) return { error: `not an ISO instant: ${JSON.stringify(since)}` };
  const r = await getJson(`/doctor/runs?since=${encodeURIComponent(since)}`, opts);
  if (r.error) return { error: /^engine answered 404 /.test(r.error) ? "this engine has no /doctor/runs (older than #706)" : r.error };
  if (!r.value || !Array.isArray(r.value.runs)) return { error: "engine's /doctor/runs has no runs list" };
  return { runs: r.value.runs, since: r.value.since || since, error: null };
}

// The timeframes Analytics > AI Accounts > Account consumption offers, as `GET /accounts/consumption?window=` takes them.
const CONSUMPTION_WINDOWS = ["1h", "4h", "1d", "1w", "30d"];

/**
 * What the fleet consumed from the AI accounts over `window` (one of
 * CONSUMPTION_WINDOWS), `GET /accounts/consumption?window=` (engine/README.md,
 * "Consumption ledger"; #1934): one row per ticket-round role, then per other
 * Claude caller, then `total`. An engine older than the endpoint (a 404) is
 * one line saying so. Resolves to { rows, since, until } or { error }; never rejects.
 */
async function fetchConsumption(window, opts = {}) {
  if (!CONSUMPTION_WINDOWS.includes(window)) return { error: `not a timeframe: ${JSON.stringify(window)}` };
  const r = await getJson(`/accounts/consumption?window=${window}`, opts);
  if (r.error) return { error: /^engine answered 404 /.test(r.error) ? "this engine has no /accounts/consumption (older than #1934)" : r.error };
  if (!r.value || !Array.isArray(r.value.rows)) return { error: "engine's /accounts/consumption has no rows list" };
  return { rows: r.value.rows, since: r.value.since || null, until: r.value.until || null, error: null };
}

// A target repo's name as a URL path takes it: `owner/name`, nothing else.
const REPO_NAME = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;

/**
 * A target repo's newest GitHub Actions workflow runs, newest first, each with
 * its `area` and `trigger`, `GET /repos/{owner}/{name}/ci/runs?limit=`
 * (engine/README.md, "CI runs of a repo"; #1935): the Analytics > Builds page.
 * `repo` must be `owner/name` and `limit` an integer from 1 to 100 before
 * either goes in the URL. An engine older than the endpoint (a 404) is one line
 * saying so. Resolves to { repo, runs, summary } or { error }; never rejects.
 */
async function fetchCiRuns(repo, limit, workflow = null, branch = null, opts = {}) {
  if (typeof repo !== "string" || !REPO_NAME.test(repo)) return { error: `not a repo: ${JSON.stringify(repo)} (owner/name)` };
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) return { error: `not a limit: ${JSON.stringify(limit)} (an integer from 1 to 100)` };
  const [owner, name] = repo.split("/");
  let q = `limit=${limit}`;
  if (typeof workflow === "string" && workflow) q += `&workflow=${encodeURIComponent(workflow)}`;
  if (typeof branch === "string" && branch) q += `&branch=${encodeURIComponent(branch)}`;
  const r = await getJson(`/repos/${encodeURIComponent(owner)}/${encodeURIComponent(name)}/ci/runs?${q}`, opts);
  if (r.error) return { error: /^engine answered 404 /.test(r.error) ? "this engine has no /repos/{owner}/{name}/ci/runs (older than this ticket)" : r.error };
  if (!r.value || !Array.isArray(r.value.runs)) return { error: "engine's /ci/runs has no runs list" };
  return { repo: r.value.repo || repo, runs: r.value.runs, summary: r.value.summary || null, error: null };
}

/**
 * The doctor's bugs in every repo whose newest sighting is at or after `since`
 * (an ISO instant), newest first, `GET /doctor/bugs?since=` (engine/README.md,
 * "Doctor"; #1000): each with its `repo`, `issue`, `state`, signatures and its
 * `tickets` and `runs`. The endpoint takes no repo: Config > Repos' **Doctor
 * files bugs** modal filters the rows to its repo. Resolves to { bugs, since }
 * or { error }; never rejects.
 */
async function fetchDoctorBugs(since, opts = {}) {
  if (typeof since !== "string" || !ISO_INSTANT.test(since)) return { error: `not an ISO instant: ${JSON.stringify(since)}` };
  const r = await getJson(`/doctor/bugs?since=${encodeURIComponent(since)}`, opts);
  if (r.error) return { error: /^engine answered 404 /.test(r.error) ? "this engine has no /doctor/bugs (older than #1000)" : r.error };
  if (!r.value || !Array.isArray(r.value.bugs)) return { error: "engine's /doctor/bugs has no bugs list" };
  return { bugs: r.value.bugs, since: r.value.since || since, error: null };
}

// Fix and Propose a fix: one sandboxed Smith run on the incident, started as
// GitHub login `by`. The engine answers 404 (no open incident), 409 (a run on
// it is already going) or 403 (no access). Resolves to { ok } or { error }.
async function startIncidentRun(action, incidentId, by, opts) {
  if (typeof incidentId !== "string" || !INSTANCE_ID.test(incidentId)) return { error: `not an incident id: ${JSON.stringify(incidentId)}` };
  if (typeof by !== "string" || !GITHUB_LOGIN.test(by)) return { error: `no GitHub login to ${action} as (sign in to the engine: Config > Sign in)` };
  return sendJson("POST", `/camunda/incidents/${incidentId}/${action}`, {}, opts);
}
const startIncidentFix = (incidentId, by, opts = {}) => startIncidentRun("fix", incidentId, by, opts);
const startIncidentPropose = (incidentId, by, opts = {}) => startIncidentRun("propose", incidentId, by, opts);

/**
 * Teaches the judge, `POST /judge/teach` (engine/README.md, "Teaching the
 * judge"; #2436): after the HITLP server's surface API decided judge escalation `taskId`, the
 * engine commits one dated line under `## Decisions` of the rubric. Only a
 * first-mate token may: `bearer` is the one main keeps for it
 * (`Bearer frk_…`), sent in place of the sign-in token. Resolves to
 * `{ ok, line, commit }` or `{ error }`.
 */
async function teachJudge(taskId, decision, note, bearer, opts = {}) {
  const id = typeof taskId === "string" ? taskId.trim() : "";
  if (!id) return { error: "no HITLP task id to teach" };
  if (decision !== "allow" && decision !== "deny") return { error: `not a teach decision: ${JSON.stringify(decision)} (allow or deny)` };
  if (typeof bearer !== "string" || !bearer) return { error: "no first-mate token (Config > frakworks > Judge teach token)" };
  const body = { taskId: id, decision };
  if (note !== undefined && note !== null && String(note).trim()) body.note = String(note).trim();
  const r = await sendJson("POST", "/judge/teach", body, { ...opts, parse: true, headers: { authorization: bearer } });
  if (r.error) return { error: r.error };
  const v = r.value || {};
  return { ok: true, line: v.line || null, commit: v.commit || null };
}

/**
 * Attach one file to the answer of the question flow `intake` waits on,
 * `POST /intake/<id>/attachments` as GitHub login `by` (engine/README.md,
 * "Answer attachments"): `file` is { name, mime, content } with the bytes in
 * base64. The engine answers 413 over its cap and 415 for a binary that is
 * not an image, with the reason. Resolves to { ok } or { error }.
 */
async function uploadAttachment(intake, file, by, opts = {}) {
  if (typeof intake !== "string" || !INSTANCE_ID.test(intake)) return { error: `not an intake id: ${JSON.stringify(intake)}` };
  if (!file || typeof file.name !== "string" || !file.name.trim()) return { error: "an attachment needs a file name" };
  if (typeof file.content !== "string") return { error: `${file.name} has no content` };
  if (typeof by !== "string" || !GITHUB_LOGIN.test(by)) return { error: "no GitHub login to attach as (sign in to the engine: Config > Sign in)" };
  const body = { name: file.name, content: file.content };
  if (file.mime) body.mime = file.mime;
  const r = await sendJson("POST", `/intake/${intake}/attachments`, body, opts);
  if (r.error && / 409 /.test(r.error)) r.error = "the engine is not waiting on this question any more (answered already, or the flow ended)";
  return r;
}

const MAX_ASK_CHARS = 20000;

/**
 * Start one intake, `POST /intake` (engine/README.md "Intake";
 * IntakeController.submit): posts the ask verbatim (trimmed),
 * the submitter's GitHub login when known, and `authorize` only when the
 * caller gives a boolean (true: authorize now or be refused; false: never at
 * filing; absent: the engine decides as before) — no tier handling here, the
 * text goes as typed. Two callers: the Issues view's "New intake" composer
 * (#128, through main's `engine:start-intake`), which passes `authorize: true`
 * with the resolved login, like First mate's `submit_ask` (engine/README.md,
 * "First mate's tools"); and First mate's File button (#100, `submitIntakeFn`,
 * which passes the ask alone). `POST /intake` is not
 * Keycloak-gated (see "Authorization from an intake" in engine/README.md —
 * `submittedBy` is taken as given until the API is authenticated). Unlike
 * `sendJson`, a 202 carries a body worth keeping (`{ intakeId }`), so this
 * makes its own request. Resolves to `{ ok: true, status, intakeId }` or
 * `{ error }`; never rejects. `fromRun` (`{ runId, issue, repo }`) is the engine's
 * own Smith check (IntakeController); no caller here sends it since the frakworks
 * MCP server moved into the engine (issue #541).
 * `repo` (`owner/name`, a target repo) is required: the repo the intake and its
 * ticket are filed in. There is no default repo, so an ask without one posts nothing.
 */
async function submitIntake(ask, submittedBy = undefined, { env = PROCESS_ENV, fetch = globalThis.fetch, authorize = undefined, fromRun = undefined, repo = undefined } = {}) {
  const text = typeof ask === "string" ? ask.trim() : "";
  if (!text) return { error: "an ask needs some text" };
  if (text.length > MAX_ASK_CHARS) return { error: `an ask is at most ${MAX_ASK_CHARS} characters` };
  const body = { ask: text };
  if (typeof submittedBy === "string" && submittedBy.trim()) body.submittedBy = submittedBy.trim();
  if (typeof authorize === "boolean") body.authorize = authorize;
  if (fromRun && typeof fromRun === "object") body.fromRun = fromRun;
  if (repo === undefined || repo === null) return { error: "an ask needs the repo to file in (owner/name)" };
  if (typeof repo !== "string" || !REPO_FULL.test(repo)) return { error: `not an owner/name repo: ${JSON.stringify(repo)}` };
  body.repo = repo;
  const url = `${engineUrl(env)}/intake`;
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs(env));
  try {
    const res = await fetch(url, {
      method: "POST", signal: ctl.signal,
      headers: { ...authHeaders(), accept: "application/json", "content-type": "application/json" },
      body: JSON.stringify(body),
    });
    const resText = await res.text();
    if (!res.ok) {
      let why = resText;
      try { const j = JSON.parse(resText); why = j.message || j.error || resText; } catch { /* plain text */ }
      return { error: `engine answered ${res.status} for POST /intake${why ? `: ${String(why).slice(0, 200)}` : ""}` };
    }
    let parsed;
    try { parsed = JSON.parse(resText); }
    catch (e) { return { error: `engine produced no JSON for POST /intake: ${e.message}` }; }
    if (!parsed || typeof parsed.intakeId !== "string" || !parsed.intakeId) return { error: "engine's POST /intake had no intakeId" };
    return { ok: true, status: res.status, intakeId: parsed.intakeId };
  } catch (e) {
    const why = e && e.name === "AbortError" ? `timed out after ${timeoutMs(env) / 1000}s` : (e && e.message) || String(e);
    return { error: `engine unreachable at ${url}: ${why}` };
  } finally { clearTimeout(t); }
}

// ---- the ticket timeline (src/renderer/timeline.jsx) -----------------------
//
// GET /ticket/<owner>/<repo>/<n>/timeline (engine, #205 / PR #210), against
// the contract observability/desktop/README.md "The ticket detail view" fixes;
// the engine's tests keep a byte-identical copy of the fixture.
const REPO_PART = /^[\w.-]+$/;

/**
 * One ticket's timeline — steps, waits and totals across every round,
 * `GET /ticket/<owner>/<repo>/<n>/timeline`. A 404 is not an error: it means
 * the engine has no history for this ticket yet, so it maps to
 * `{ notFound: true }` rather than `{ error }`. Resolves to { timeline } or
 * { error } or { notFound: true }; never rejects.
 */
async function fetchTicketTimeline(owner, repo, n, opts = {}) {
  if (typeof owner !== "string" || !REPO_PART.test(owner)) return { error: `not a repo owner: ${JSON.stringify(owner)}` };
  if (typeof repo !== "string" || !REPO_PART.test(repo)) return { error: `not a repo name: ${JSON.stringify(repo)}` };
  const num = Number(n);
  if (!Number.isInteger(num) || num <= 0) return { error: `not a ticket number: ${JSON.stringify(n)}` };
  const r = await getJson(`/ticket/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/${num}/timeline`, opts);
  if (r.error) return / 404 /.test(r.error) ? { notFound: true } : { error: r.error };
  const t = r.value;
  if (!t || !Array.isArray(t.steps) || !Array.isArray(t.waits) || !t.totals || typeof t.totals !== "object") {
    return { error: `engine's .../timeline has no \`steps\`/\`waits\`/\`totals\`` };
  }
  return { timeline: t, error: null };
}

// ---- the GitHub reads the app does through the engine, not `gh` (#239) -----

/**
 * Everything waiting on a person, `GET /needs`: a bare list of Need
 * ({ repo, number, title, kind, wanted, since, actionId }), in every target
 * repo or in one, `opts.repo` (`GET /needs?repo=`). Resolves to
 * { rows } or { error, rows: [] }; never rejects.
 */
async function fetchNeeds(opts = {}) {
  const q = repoQuery(opts.repo);
  if (q.error) return { error: q.error, rows: [] };
  const r = await getJson(`/needs${q.query}`, opts);
  if (r.error) return { error: r.error, rows: [] };
  if (!Array.isArray(r.value)) return { error: "engine's /needs is not a list", rows: [] };
  return { rows: r.value, error: null };
}

/**
 * The builds in progress, `GET /builds/active` (#1389): `{ builds, timeout }`,
 * each build `{ repo, issue, check, headSha, since, processInstanceId }`.
 * Resolves to that or `{ error, builds: [] }`; never rejects.
 */
async function fetchActiveBuilds(opts = {}) {
  const r = await getJson("/builds/active", opts);
  if (r.error) return { error: r.error, builds: [], timeout: null };
  const v = r.value;
  if (!v || !Array.isArray(v.builds)) return { error: "engine's /builds/active has no builds list", builds: [], timeout: null };
  return { builds: v.builds, timeout: typeof v.timeout === "string" ? v.timeout : null, error: null };
}

// "owner/name", the only form the engine's `repo=` filter takes; neither part
// may be only dots.
const REPO_FULL = /^(?!\.+\/)[\w.-]+\/(?!\.+$)[\w.-]+$/;
const DEFAULT_CLOSED_LIMIT = 100;
const MAX_CLOSED_LIMIT = 500;

/**
 * The most recently closed issues of the target repos (or of one, `repo`),
 * `GET /issues?state=closed&limit=n[&repo=owner/name]`. `limit` is clamped to
 * 1..500 (default 100). Resolves to { issues, failures } or { error }; never
 * rejects.
 */
async function fetchClosedIssues({ limit, repo } = {}, opts = {}) {
  if (repo !== undefined && repo !== null && (typeof repo !== "string" || !REPO_FULL.test(repo))) {
    return { error: `not an owner/name repo: ${JSON.stringify(repo)}` };
  }
  const want = Math.trunc(Number(limit));
  const n = Number.isFinite(want) ? Math.min(MAX_CLOSED_LIMIT, Math.max(1, want)) : DEFAULT_CLOSED_LIMIT;
  const r = await getJson(`/issues?state=closed&limit=${n}${repo ? `&repo=${encodeURIComponent(repo)}` : ""}`, opts);
  if (r.error) return { error: r.error };
  const b = r.value;
  if (!b || !Array.isArray(b.issues)) return { error: "engine's /issues?state=closed has no `issues` list" };
  return { issues: b.issues, failures: Array.isArray(b.failures) ? b.failures : [], error: null };
}

/**
 * One ticket as GitHub has it — body, comments, linked PRs —
 * `GET /ticket/<owner>/<repo>/<n>`. The engine answers 404 for an unknown
 * issue or a repo that is not a target repo. Resolves to { ticket } or
 * { error }; never rejects.
 */
async function fetchTicketDetail(owner, repo, n, opts = {}) {
  if (typeof owner !== "string" || !REPO_PART.test(owner)) return { error: `not a repo owner: ${JSON.stringify(owner)}` };
  if (typeof repo !== "string" || !REPO_PART.test(repo)) return { error: `not a repo name: ${JSON.stringify(repo)}` };
  const num = typeof n === "string" && /^\d+$/.test(n) ? Number(n) : n;
  if (!Number.isInteger(num) || num <= 0) return { error: `not a ticket number: ${JSON.stringify(n)}` };
  const r = await getJson(`/ticket/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/${num}`, opts);
  if (r.error) return / 404 /.test(r.error) ? { error: `no such ticket in a target repo: ${owner}/${repo}#${num}` } : { error: r.error };
  const t = r.value;
  if (!t || typeof t !== "object" || Array.isArray(t) || typeof t.number !== "number" || !Array.isArray(t.comments)) {
    return { error: `engine's /ticket/${owner}/${repo}/${num} has no \`number\`/\`comments\`` };
  }
  return { ticket: { ...t, prs: Array.isArray(t.prs) ? t.prs : [], labels: Array.isArray(t.labels) ? t.labels : [] }, error: null };
}

/**
 * What lies between two builds of a target repo (#100091):
 * `GET /repos/<owner>/<name>/changes?from=&to=` (engine/README.md, "Repos"),
 * the PRs on `to`'s first-parent line since `from`, newest first, each with the
 * tickets it closes and their titles from the engine's work-item store (null
 * when the store has none). Resolves to { changes } or { error }; never rejects.
 */
async function fetchChanges(repo, from, to, opts = {}) {
  if (typeof repo !== "string" || !REPO_FULL.test(repo)) return { error: `not an owner/name repo: ${JSON.stringify(repo)}` };
  for (const s of [from, to]) if (typeof s !== "string" || !/^[0-9a-f]{7,40}$/i.test(s)) return { error: `not a commit sha: ${JSON.stringify(s)}` };
  const r = await getJson(`/repos/${repo.split("/").map(encodeURIComponent).join("/")}/changes?from=${from}&to=${to}`, opts);
  if (r.error) return { error: r.error };
  if (!r.value || !Array.isArray(r.value.changes)) return { error: "the engine's /changes has no `changes`" };
  return { changes: r.value.changes };
}

/**
 * A proposal ticket's pitch for the Pitch view (src/renderer/pitch.jsx):
 * `GET /ticket/<owner>/<repo>/<n>/pitch` (engine/README.md). `kind` is
 * `html` (the pitch page as committed, drawn in a sandboxed frame) or
 * `markdown` (the doc as GitHub renders it, sanitized before it is drawn).
 * The engine answers 404 for a ticket that is not a proposal or has no PR.
 * Resolves to { pitch } or { error }; never rejects.
 */
/**
 * A confirm-mode feature's design proposal as a dark slide deck (#100114),
 * `GET /ticket/<owner>/<repo>/<n>/design-deck`. Resolves to { html } or
 * { error }; never rejects. A 404 is a ticket with no confirm design.
 */
async function fetchDesignDeck(owner, repo, n, opts = {}) {
  if (typeof owner !== "string" || !REPO_PART.test(owner)) return { error: `not a repo owner: ${JSON.stringify(owner)}` };
  if (typeof repo !== "string" || !REPO_PART.test(repo)) return { error: `not a repo name: ${JSON.stringify(repo)}` };
  const num = typeof n === "string" && /^\d+$/.test(n) ? Number(n) : n;
  if (!Number.isInteger(num) || num <= 0) return { error: `not a ticket number: ${JSON.stringify(n)}` };
  const r = await getJson(`/ticket/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/${num}/design-deck`, opts);
  if (r.error) return / 404 /.test(r.error) ? { error: `no design deck on ${owner}/${repo}#${num}`, none: true } : { error: r.error };
  const html = r.value && r.value.html;
  return typeof html === "string" && html.trim() ? { html } : { error: `engine's design deck of ${owner}/${repo}#${num} has no \`html\`` };
}

async function fetchTicketPitch(owner, repo, n, opts = {}) {
  if (typeof owner !== "string" || !REPO_PART.test(owner)) return { error: `not a repo owner: ${JSON.stringify(owner)}` };
  if (typeof repo !== "string" || !REPO_PART.test(repo)) return { error: `not a repo name: ${JSON.stringify(repo)}` };
  const num = typeof n === "string" && /^\d+$/.test(n) ? Number(n) : n;
  if (!Number.isInteger(num) || num <= 0) return { error: `not a ticket number: ${JSON.stringify(n)}` };
  const r = await getJson(`/ticket/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/${num}/pitch`, opts);
  if (r.error) return / 404 /.test(r.error) ? { error: `no pitch: ${owner}/${repo}#${num} is not a proposal with a PR` } : { error: r.error };
  const p = r.value;
  if (!p || typeof p !== "object" || !["html", "markdown"].includes(p.kind) || typeof p.content !== "string") {
    return { error: `engine's /ticket/${owner}/${repo}/${num}/pitch has no \`kind\`/\`content\`` };
  }
  const options = Array.isArray(p.options)
    ? p.options.filter((o) => o && OPTION_ID.test(o.id) && typeof o.label === "string").map((o) => ({ id: o.id, label: o.label }))
    : [];
  return { pitch: { kind: p.kind, path: String(p.path || ""), prUrl: String(p.prUrl || ""), content: p.content,
    options, awaitingChoice: p.awaitingChoice === true && options.length > 0,
    awaitingDecision: p.awaitingDecision === true && options.length > 0 }, error: null };
}

// An option's id in a pitch (#1720): one lowercase letter, as the engine's ProposeResult.OPTION.
const OPTION_ID = /^[a-z]$/;

// ---- the Proposals branch and view (#660) ---------------------------------

// A proposal doc's slug, as the engine's ProposalDocs.SLUG takes it: a plain file name, not starting with a dot.
const PROPOSAL_SLUG = /^[A-Za-z0-9_-][A-Za-z0-9._-]*$/;

/**
 * The proposal docs on the target repos' default branches, `GET /proposals`
 * (engine/README.md), in every target repo or in one, `opts.repo`. Each doc
 * is `{ repo, slug, path, title, status, decisionLog, ticket, buildTicket,
 * pitch }`. Resolves to { docs, failures } (`failures`: the engine's
 * `errors`, `{ repo, error }` each) or { error, docs: [] }; never rejects.
 */
async function fetchProposals(opts = {}) {
  const q = repoQuery(opts.repo);
  if (q.error) return { error: q.error, docs: [], failures: [] };
  const r = await getJson(`/proposals${q.query}`, opts);
  if (r.error) return { error: r.error, docs: [], failures: [] };
  const b = r.value;
  if (!b || !Array.isArray(b.docs)) return { error: "engine's /proposals has no `docs` list", docs: [], failures: [] };
  const docs = b.docs.filter((d) => d && typeof d.repo === "string" && typeof d.slug === "string")
    .map((d) => ({ ...d, decisionLog: Array.isArray(d.decisionLog) ? d.decisionLog.map(String) : [] }));
  return { docs, failures: Array.isArray(b.errors) ? b.errors : [], error: null };
}

/**
 * One proposal doc's page from the default branch, for a doc with no ticket:
 * `GET /proposals/<owner>/<repo>/<slug>/page`, in fetchTicketPitch's shape.
 * Resolves to { pitch } or { error }; never rejects.
 */
async function fetchProposalPage(owner, repo, slug, opts = {}) {
  if (typeof owner !== "string" || !REPO_PART.test(owner)) return { error: `not a repo owner: ${JSON.stringify(owner)}` };
  if (typeof repo !== "string" || !REPO_PART.test(repo)) return { error: `not a repo name: ${JSON.stringify(repo)}` };
  if (typeof slug !== "string" || !PROPOSAL_SLUG.test(slug)) return { error: `not a proposal slug: ${JSON.stringify(slug)}` };
  const path = `/proposals/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/${encodeURIComponent(slug)}/page`;
  const r = await getJson(path, opts);
  if (r.error) return / 404 /.test(r.error) ? { error: `no proposal doc ${slug} in ${owner}/${repo}` } : { error: r.error };
  const p = r.value;
  if (!p || typeof p !== "object" || !["html", "markdown"].includes(p.kind) || typeof p.content !== "string") {
    return { error: `engine's ${path} has no \`kind\`/\`content\`` };
  }
  return { pitch: { kind: p.kind, path: String(p.path || ""), prUrl: String(p.prUrl || ""), content: p.content }, error: null };
}

// ---- the Repos branch and file view (#674) -------------------------------

// A path as the engine's RepoFiles takes it: plain relative segments, no `.` or `..`.
const validRepoPath = (p) => typeof p === "string" && p.length > 0 && p.length <= 4096 && !/[\\\0]/.test(p) &&
  p.split("/").every((s) => s && s !== "." && s !== "..");

/**
 * One target repo's whole tree at its default branch (or `opts.ref`),
 * `GET /repos/<owner>/<repo>/tree` (engine/README.md): `{ commit, truncated,
 * entries }`, each entry `{ path, type, sha }`. One call per repo per
 * expansion; the engine reads GitHub once per commit. Resolves to { tree } or
 * { error }; never rejects.
 */
async function fetchRepoTree(owner, repo, opts = {}) {
  if (typeof owner !== "string" || !REPO_PART.test(owner)) return { error: `not a repo owner: ${JSON.stringify(owner)}` };
  if (typeof repo !== "string" || !REPO_PART.test(repo)) return { error: `not a repo name: ${JSON.stringify(repo)}` };
  const q = opts.ref ? `?ref=${encodeURIComponent(opts.ref)}` : "";
  const r = await getJson(`/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/tree${q}`, opts);
  if (r.error) return / 404 /.test(r.error) ? { error: `no tree for ${owner}/${repo}: ${r.error}` } : { error: r.error };
  const t = r.value;
  if (!t || typeof t.commit !== "string" || !Array.isArray(t.entries)) return { error: `engine's tree for ${owner}/${repo} has no \`commit\`/\`entries\`` };
  const entries = t.entries.filter((e) => e && typeof e.path === "string" && typeof e.type === "string")
    .map((e) => ({ path: e.path, type: e.type, sha: String(e.sha || "") }));
  return { tree: { commit: t.commit, truncated: t.truncated === true, entries }, error: null };
}

/**
 * One file's bytes at `ref` (the tree's `commit`, so the file matches the tree
 * it was picked from; null: the default branch), `GET /repos/<owner>/<repo>/
 * file?path=&ref=`. Resolves to { file: { path, size, contentType, bytes } }
 * (`bytes` a Uint8Array, whole: the renderer truncates) or { error }; never rejects.
 */
async function fetchRepoFile(owner, repo, path, ref, { env = PROCESS_ENV, fetch = globalThis.fetch } = {}) {
  if (typeof owner !== "string" || !REPO_PART.test(owner)) return { error: `not a repo owner: ${JSON.stringify(owner)}` };
  if (typeof repo !== "string" || !REPO_PART.test(repo)) return { error: `not a repo name: ${JSON.stringify(repo)}` };
  if (!validRepoPath(path)) return { error: `not a file path: ${JSON.stringify(path)}` };
  const q = `?path=${encodeURIComponent(path)}${ref ? `&ref=${encodeURIComponent(ref)}` : ""}`;
  const at = `/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/file`;
  const url = `${engineUrl(env)}${at}${q}`;
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs(env));
  try {
    const res = await fetch(url, { signal: ctl.signal, headers: { ...authHeaders(), accept: "*/*" } });
    const body = new Uint8Array(await res.arrayBuffer());
    if (!res.ok) {
      const text = new TextDecoder().decode(body.subarray(0, 400));
      let why = text;
      try { why = JSON.parse(text).error || text; } catch (e) { /* not JSON: the text as it came */ }
      return { error: res.status === 404 ? `not found: ${why}` : `engine answered ${res.status} for ${at}${why ? `: ${String(why).slice(0, 200)}` : ""}` };
    }
    return { file: { path, size: body.length, contentType: res.headers.get("content-type") || "", bytes: body }, error: null };
  } catch (e) {
    const why = e && e.name === "AbortError" ? `timed out after ${timeoutMs(env) / 1000}s` : (e && e.message) || String(e);
    return { error: `engine unreachable at ${url}: ${why}` };
  } finally { clearTimeout(t); }
}

/**
 * The coding card's Summarize button (src/renderer/detail.jsx): the engine
 * summarizes the ticket's running Smith's session with an LLM,
 * `POST /ticket/<owner>/<repo>/<n>/summarize` (engine/README.md, "Summarize: a
 * live run summarized"). Resolves to { summary: { runId, mode, model, summary, now } } or
 * { error }: the engine's 409 (no Smith running), 503 (no account to
 * summarize with) and 502 (the provider failed) carry its reason. Never rejects.
 */
async function summarizeTicket(owner, repo, n, opts = {}) {
  if (typeof owner !== "string" || !REPO_PART.test(owner)) return { error: `not a repo owner: ${JSON.stringify(owner)}` };
  if (typeof repo !== "string" || !REPO_PART.test(repo)) return { error: `not a repo name: ${JSON.stringify(repo)}` };
  const num = typeof n === "string" && /^\d+$/.test(n) ? Number(n) : n;
  if (!Number.isInteger(num) || num <= 0) return { error: `not a ticket number: ${JSON.stringify(n)}` };
  const path = `/ticket/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/${num}/summarize`;
  const r = await sendJson("POST", path, undefined, { ...opts, parse: true });
  if (r.error) return { error: r.error };
  const s = r.value;
  if (!s || typeof s.summary !== "string" || typeof s.now !== "string") return { error: `engine's ${path} has no \`summary\`/\`now\`` };
  return { summary: s, error: null };
}

/**
 * The review tile's prediction bar (src/renderer/timeline.jsx, #1746):
 * `POST /ticket/<owner>/<repo>/<n>/predict-review` (engine/README.md, "Predict
 * a review: Jev on the live review run"), `repo` as owner/name. Resolves to
 * { prediction: { runId, callId, verdict, confidence, value, lines, fresh } }
 * or { error }: the engine's 409 (no review running), 503 and 502 carry its
 * reason. Never rejects.
 */
async function predictReview(repo, n, opts = {}) {
  const [owner, name, ...rest] = typeof repo === "string" ? repo.split("/") : [];
  if (rest.length || !owner || !REPO_PART.test(owner) || !name || !REPO_PART.test(name)) return { error: `not a repo: ${JSON.stringify(repo)}` };
  const num = typeof n === "string" && /^\d+$/.test(n) ? Number(n) : n;
  if (!Number.isInteger(num) || num <= 0) return { error: `not a ticket number: ${JSON.stringify(n)}` };
  const path = `/ticket/${encodeURIComponent(owner)}/${encodeURIComponent(name)}/${num}/predict-review`;
  const r = await sendJson("POST", path, undefined, { ...opts, parse: true });
  if (r.error) return { error: r.error };
  const p = r.value;
  if (!p || typeof p.runId !== "string" || !(p.value === null || Number.isInteger(p.value))) return { error: `engine's ${path} has no \`runId\`/\`value\`` };
  return { prediction: p, error: null };
}

// ---- the Jev decisions (src/renderer/jev.jsx, #553) ------------------------
//
// Read-only over the engine's engine.jev_call (engine/README.md, "Jev
// decisions"): the calls, one call in full, and the per-use-case summary.
const JEV_CALL_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const JEV_USE_CASE = /^[a-z][a-z0-9-]{0,63}$/;
const MAX_JEV_LIMIT = 500;
const MAX_JEV_TEXT = 200;

/**
 * The Jev calls newest first, `GET /jev/calls`, filtered by any of `useCase`,
 * `repo` ("owner/name"), `issue`, `action` and `outcome` (prefixes, or
 * `set`/`unset`), `since` (an ISO instant or date); `limit` 1..500 (default
 * the engine's 50) from `offset`. Resolves to { total, calls } or { error };
 * never rejects.
 */
async function fetchJevCalls(filter = {}, opts = {}) {
  const f = filter || {};
  const q = [];
  if (f.useCase != null) {
    if (typeof f.useCase !== "string" || !JEV_USE_CASE.test(f.useCase)) return { error: `not a use case: ${JSON.stringify(f.useCase)}` };
    q.push(`use_case=${encodeURIComponent(f.useCase)}`);
  }
  if (f.repo != null) {
    if (typeof f.repo !== "string" || !REPO_FULL.test(f.repo)) return { error: `not an owner/name repo: ${JSON.stringify(f.repo)}` };
    q.push(`repo=${encodeURIComponent(f.repo)}`);
  }
  if (f.issue != null) {
    const n = typeof f.issue === "string" && /^\d+$/.test(f.issue) ? Number(f.issue) : f.issue;
    if (!Number.isInteger(n) || n <= 0) return { error: `not a ticket number: ${JSON.stringify(f.issue)}` };
    q.push(`issue=${n}`);
  }
  for (const key of ["action", "outcome", "since"]) {
    if (f[key] == null) continue;
    if (typeof f[key] !== "string" || !f[key] || f[key].length > MAX_JEV_TEXT) return { error: `not a ${key}: ${JSON.stringify(f[key])}` };
    q.push(`${key}=${encodeURIComponent(f[key])}`);
  }
  if (f.limit != null) {
    const n = Math.trunc(Number(f.limit));
    if (!Number.isFinite(n)) return { error: `not a limit: ${JSON.stringify(f.limit)}` };
    q.push(`limit=${Math.min(MAX_JEV_LIMIT, Math.max(1, n))}`);
  }
  if (f.offset != null) {
    const n = Math.trunc(Number(f.offset));
    if (!Number.isFinite(n) || n < 0) return { error: `not an offset: ${JSON.stringify(f.offset)}` };
    q.push(`offset=${n}`);
  }
  const r = await getJson(`/jev/calls${q.length ? `?${q.join("&")}` : ""}`, opts);
  if (r.error) return { error: r.error };
  const b = r.value;
  if (!b || !Array.isArray(b.calls)) return { error: "engine's /jev/calls has no `calls` list" };
  return { total: typeof b.total === "number" ? b.total : b.calls.length, calls: b.calls, error: null };
}

/**
 * One Jev call in full, `GET /jev/calls/<id>`: the state and questions as
 * sent, the picks with their probabilities, the outcome or the error.
 * Resolves to { call } or { error }; never rejects.
 */
async function fetchJevCall(id, opts = {}) {
  if (typeof id !== "string" || !JEV_CALL_ID.test(id)) return { error: `not a jev call id: ${JSON.stringify(id)}` };
  const r = await getJson(`/jev/calls/${id}`, opts);
  if (r.error) return / 404 /.test(r.error) ? { error: `no jev call ${id}` } : { error: r.error };
  const c = r.value;
  if (!c || typeof c !== "object" || Array.isArray(c) || c.id !== id) return { error: `engine's /jev/calls/${id} has no matching \`id\`` };
  return { call: { ...c, picks: Array.isArray(c.picks) ? c.picks : [] }, error: null };
}

/**
 * Per use case over the window from `since` (an ISO instant or date; the
 * engine's default is 7 days ago), `GET /jev/summary`. Resolves to
 * { summary } or { error }; never rejects.
 */
async function fetchJevSummary(since, opts = {}) {
  if (since != null && (typeof since !== "string" || !since || since.length > MAX_JEV_TEXT)) return { error: `not a since: ${JSON.stringify(since)}` };
  const r = await getJson(`/jev/summary${since ? `?since=${encodeURIComponent(since)}` : ""}`, opts);
  if (r.error) return { error: r.error };
  const s = r.value;
  if (!s || !s.totals || !Array.isArray(s.useCases)) return { error: "engine's /jev/summary has no `totals`/`useCases`" };
  return { summary: s, error: null };
}

// ---- the SSH verdicts (src/renderer/ssh-verdicts.jsx, #2439) ----------------
//
// GET /jev/ssh-verdicts (engine/README.md, "SSH verdicts"): the ssh-judge calls
// newest first, each with the human decision of the hitlp task that carried its
// call id. `repo` (owner/name) narrows them; the selection's repo when one is set.
async function fetchSshVerdicts(repo, opts = {}) {
  if (repo != null && (typeof repo !== "string" || !REPO_FULL.test(repo))) return { error: `not a repo: ${JSON.stringify(repo)}` };
  const r = await getJson(`/jev/ssh-verdicts${repo ? `?repo=${encodeURIComponent(repo)}` : ""}`, opts);
  if (r.error) return { error: r.error };
  const v = r.value;
  if (!v || !Array.isArray(v.verdicts)) return { error: "engine's /jev/ssh-verdicts has no `verdicts`" };
  return { verdicts: v.verdicts, error: null };
}

// ---- the friction notes (src/renderer/friction.jsx, #1046, #1098) -----------
//
// The engine's engine.friction_note and its groups (engine/README.md,
// "Friction notes"): the notes, the groups and the summary over a window, and
// the two corrections any login may make: a pull-out and a merge.
const FRICTION_KINDS = ["bug", "design", "docs", "tooling", "other"];
const FRICTION_SOURCES = ["as-you-go", "retro", "first-mate"];
const MAX_FRICTION_LIMIT = 500;

// `since` and `repo` as query parts, or { error } for either.
const frictionWindow = (f) => {
  const q = [];
  if (f.since != null) {
    if (typeof f.since !== "string" || !f.since || f.since.length > MAX_JEV_TEXT) return { error: `not a since: ${JSON.stringify(f.since)}` };
    q.push(`since=${encodeURIComponent(f.since)}`);
  }
  if (f.repo != null) {
    if (typeof f.repo !== "string" || !REPO_FULL.test(f.repo)) return { error: `not an owner/name repo: ${JSON.stringify(f.repo)}` };
    q.push(`repo=${encodeURIComponent(f.repo)}`);
  }
  return { q };
};

/**
 * The friction summary over the window from `since` (an ISO instant or date;
 * the engine's default is 7 days ago) in `repo` or every repo, `GET
 * /friction/summary`: { from, to, total, per_day, by_kind, by_source,
 * by_severity, by_repo }. An engine older than the endpoint (a 404) is one
 * line saying so. Resolves to { summary } or { error }; never rejects.
 */
async function fetchFrictionSummary(filter = {}, opts = {}) {
  const w = frictionWindow(filter || {});
  if (w.error) return { error: w.error };
  const r = await getJson(`/friction/summary${w.q.length ? `?${w.q.join("&")}` : ""}`, opts);
  if (r.error) return { error: /^engine answered 404 /.test(r.error) ? "this engine has no /friction/summary (older than #1046)" : r.error };
  const s = r.value;
  if (!s || typeof s.total !== "number" || !Array.isArray(s.per_day)) return { error: "engine's /friction/summary has no `total`/`per_day`" };
  return { summary: s, error: null };
}

// The `kind`, `source` and `limit` query parts shared by /friction/notes and /friction/groups, after `frictionWindow`'s.
const frictionQuery = (f) => {
  const w = frictionWindow(f);
  if (w.error) return w;
  const q = w.q;
  if (f.kind != null) {
    if (!FRICTION_KINDS.includes(f.kind)) return { error: `not a friction kind: ${JSON.stringify(f.kind)}` };
    q.push(`kind=${f.kind}`);
  }
  if (f.source != null) {
    if (!FRICTION_SOURCES.includes(f.source)) return { error: `not a friction source: ${JSON.stringify(f.source)}` };
    q.push(`source=${f.source}`);
  }
  if (f.limit != null) {
    const n = Math.trunc(Number(f.limit));
    if (!Number.isFinite(n)) return { error: `not a limit: ${JSON.stringify(f.limit)}` };
    q.push(`limit=${Math.min(MAX_FRICTION_LIMIT, Math.max(1, n))}`);
  }
  return { q };
};

/**
 * The friction notes newest first, `GET /friction/notes`, filtered by any of
 * `since`, `repo`, `kind` and `source`; `limit` 1..500 (default the engine's
 * 100). Resolves to { notes } or { error }; never rejects.
 */
async function fetchFrictionNotes(filter = {}, opts = {}) {
  const w = frictionQuery(filter || {});
  if (w.error) return { error: w.error };
  const r = await getJson(`/friction/notes${w.q.length ? `?${w.q.join("&")}` : ""}`, opts);
  if (r.error) return { error: /^engine answered 404 /.test(r.error) ? "this engine has no /friction/notes (older than #1046)" : r.error };
  if (!Array.isArray(r.value)) return { error: "engine's /friction/notes is no list" };
  return { notes: r.value, error: null };
}

/**
 * The friction groups, `GET /friction/groups` (engine/README.md, "Friction
 * notes"): the groups with a member passing the same filters as the notes,
 * newest passing member first, each { id, title, count, total, newest, oldest,
 * kind, repos, severity, members }; `limit` counts groups. Resolves to
 * { groups } or { error }; never rejects.
 */
async function fetchFrictionGroups(filter = {}, opts = {}) {
  const w = frictionQuery(filter || {});
  if (w.error) return { error: w.error };
  const r = await getJson(`/friction/groups${w.q.length ? `?${w.q.join("&")}` : ""}`, opts);
  if (r.error) return { error: /^engine answered 404 /.test(r.error) ? "this engine has no /friction/groups (older than #1095)" : r.error };
  if (!Array.isArray(r.value)) return { error: "engine's /friction/groups is no list" };
  return { groups: r.value, error: null };
}

// A friction note's id (a UUID) and a group's id (the BIGSERIAL GET /friction/groups lists).
const FRICTION_NOTE_ID = /^[\w-]{1,64}$/;
const frictionGroupId = (v) => (Number.isInteger(v) && v > 0 ? v : null);
const frictionLogin = (by, what) => (typeof by === "string" && GITHUB_LOGIN.test(by) ? null
  : { error: `no GitHub login to ${what} as (sign in to the engine: Config > Sign in)` });

/**
 * Pull out: the note leaves its group for a new one holding it alone, `PATCH
 * /friction/notes/{id}` with { group: null } (engine/README.md, "Friction
 * notes"). `by` is main's signed-in login, checked before anything is sent and never sent: the bearer token names the login. Resolves to
 * { ok, status } or { error } with the engine's reason; never rejects.
 */
async function frictionPullOut(noteId, by, opts = {}) {
  if (typeof noteId !== "string" || !FRICTION_NOTE_ID.test(noteId)) return { error: `not a friction note id: ${JSON.stringify(noteId)}` };
  const no = frictionLogin(by, "pull the note out");
  if (no) return no;
  return sendJson("PATCH", `/friction/notes/${encodeURIComponent(noteId)}`, { group: null }, opts);
}

/**
 * Not two things: clears Jev's two-things mark on a note, `PATCH
 * /friction/notes/{id}` with { twoThings: null } (#1112). Resolves to
 * { ok, status } or { error } with the engine's reason (404 for no such note,
 * 409 for a note not marked); never rejects.
 */
async function frictionNotTwoThings(noteId, by, opts = {}) {
  if (typeof noteId !== "string" || !FRICTION_NOTE_ID.test(noteId)) return { error: `not a friction note id: ${JSON.stringify(noteId)}` };
  const no = frictionLogin(by, "clear the two-things mark");
  if (no) return no;
  return sendJson("PATCH", `/friction/notes/${encodeURIComponent(noteId)}`, { twoThings: null }, opts);
}

/**
 * Merge: every note and signature of group `groupId` moves into group `into`,
 * `POST /friction/groups/{id}/merge` with { into }. Resolves to { ok, status }
 * or { error } with the engine's reason (404 for a group gone meanwhile, 409 for
 * a merge into itself); never rejects.
 */
async function frictionMerge(groupId, into, by, opts = {}) {
  const id = frictionGroupId(groupId);
  if (id == null) return { error: `not a friction group id: ${JSON.stringify(groupId)}` };
  const target = frictionGroupId(into);
  if (target == null) return { error: `not a friction group id: ${JSON.stringify(into)}` };
  const no = frictionLogin(by, "merge the groups");
  if (no) return no;
  return sendJson("POST", `/friction/groups/${id}/merge`, { into: target }, opts);
}

/**
 * Fixed by: group `groupId` is fixed by `fix` ({ repo, issue }), or its link is
 * cleared with null, `PATCH /friction/groups/{id}` with { fix }. Resolves to
 * { ok, status } or { error } with the engine's reason; never rejects.
 */
async function frictionFix(groupId, fix, by, opts = {}) {
  const id = frictionGroupId(groupId);
  if (id == null) return { error: `not a friction group id: ${JSON.stringify(groupId)}` };
  if (fix !== null && !(fix && /^[\w.-]+\/[\w.-]+$/.test(fix.repo) && Number.isInteger(fix.issue) && fix.issue > 0))
    return { error: `not a fixing ticket: ${JSON.stringify(fix)} ({ repo: "owner/name", issue: n } or null)` };
  const no = frictionLogin(by, "link the fix");
  if (no) return no;
  return sendJson("PATCH", `/friction/groups/${id}`, { fix: fix && { repo: fix.repo, issue: fix.issue } }, opts);
}

// ---- the target repos (src/renderer/repos.jsx, Config > Repos) -------------
//
// Config > Repos (#567) over the engine's /repos endpoints
// (engine/README.md, the endpoint table and "Its own tables"). Each repo is
// "owner/name" (REPO_FULL), split into two checked path segments.

const repoPath = (repo) => {
  if (typeof repo !== "string" || !REPO_FULL.test(repo)) return null;
  const [owner, name] = repo.split("/");
  return `/repos/${encodeURIComponent(owner)}/${encodeURIComponent(name)}`;
};

/**
 * How many flows the board says are running per repo, by lowercased repo: a
 * flow with a process instance that has not ended. That takes in an intake
 * POST /intake started (the board draws it with `outcome: null`), which
 * DELETE /repos refuses on too, and skips a deferred, refused or ignored
 * approval, which has no instance.
 */
const runningByRepo = (issues) => {
  const counts = new Map();
  for (const i of issues || []) {
    const f = i && i.flow;
    if (!f || !f.instance || f.ended || typeof i.repo !== "string") continue;
    const key = i.repo.toLowerCase();
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  return counts;
};

/**
 * One repo's GitHub-side setup, `GET /repos/<o>/<n>/setup` (engine/README.md,
 * "A new target repo's GitHub setup"): readable, push, missingLabels,
 * defaultBranch, ciCheck, webhook. Resolves to { setup } or { error }.
 */
async function fetchRepoSetup(repo, opts = {}) {
  const at = repoPath(repo);
  if (!at) return { error: `not an owner/name repo: ${JSON.stringify(repo)}` };
  const r = await getJson(`${at}/setup`, opts);
  if (r.error) return { error: / 404 /.test(r.error) ? `${repo} is not a target repo` : r.error };
  const s = r.value;
  if (!s || typeof s !== "object" || typeof s.readable !== "boolean") return { error: `engine's ${at}/setup has no \`readable\`` };
  return { setup: s, error: null };
}

/**
 * The target repos for Config > Repos: `GET /repos`, each row with its
 * running-flow count from the issues board (`GET /issues`: an open issue
 * whose flow has a process instance that has not ended). The count is extra:
 * when it fails the rows still draw, with `runningError` said beside them.
 * No setup check: each row's `setup` is null and `setupError` null; the page
 * asks for the check with requestSetupCheck and the reports arrive as `setup`
 * frames on the event stream. Resolves to { repos, runningError } or
 * { error, repos: [] }; never rejects.
 */
async function fetchRepos(opts = {}) {
  const rows = await fetchRepoRows(opts);
  if (rows.error) return rows;
  return { ...rows, repos: rows.repos.map((r) => ({ ...r, setup: null, setupError: null })) };
}

/**
 * The rows alone: `GET /repos` with each row's running count from
 * `GET /issues`, no setup check (`setup` and `setupError` are not on the
 * rows). The desktop re-reads this on the stream's `repo` and `ticket` frames
 * (engine/README.md, "Events"). Same result shape, never rejects.
 */
async function fetchRepoRows(opts = {}) {
  const [list, board] = await Promise.all([getJson("/repos", opts), getJson("/issues", opts)]);
  if (list.error) return { error: list.error, repos: [], runningError: null };
  if (!Array.isArray(list.value)) return { error: "engine's /repos is not a list", repos: [], runningError: null };
  const issues = !board.error && board.value && Array.isArray(board.value.issues) ? board.value.issues : null;
  const runningError = board.error || (issues ? null : "engine's /issues has no `issues` list");
  const counts = runningByRepo(issues);
  const rows = list.value.filter((r) => r && typeof r.fullName === "string");
  const repos = rows.map((row) => ({
    ...row, ciCheck: row.ciCheck ?? null, ciPaths: Array.isArray(row.ciPaths) ? row.ciPaths : [],
    // An engine from before the setting has no `securityReview`: its flows all run the security review.
    securityReview: row.securityReview !== false,
    // Off unless the engine says on: an engine from before #1000 has neither, and files no doctor bugs.
    doctorBugs: row.doctorBugs === true, engineDefects: row.engineDefects === true,
    // Off unless the engine says on: an engine from before #1112 has no `frictionBugs`, and files no friction bugs.
    frictionBugs: row.frictionBugs === true,
    // Off unless the engine says on: an engine from before #2339 has no `docsFirst`.
    docsFirst: row.docsFirst === true,
    // Off unless the engine says on: an engine from before #2500 has no `hitlpSurface`.
    hitlpSurface: row.hitlpSurface === true,
    // Serial unless the engine names another mode: an engine from before #2501 has no `childMode`.
    childMode: CHILD_MODES.includes(row.childMode) ? row.childMode : "serial",
    running: issues ? counts.get(row.fullName.toLowerCase()) || 0 : null,
  }));
  return { repos, runningError, error: null };
}

/**
 * Asks the engine to check the GitHub-side setup of `repo` (`owner/name`, or
 * `*` for every target repo): `POST /repos/setup/check` (engine/README.md, "A
 * new target repo's GitHub setup"). The engine answers 202 before any GitHub
 * read and delivers each repo's result as a `setup` frame on the event stream;
 * a report younger than 5 minutes is re-published rather than read again,
 * unless `force`. Resolves to { queued, fresh } or { error }; never rejects.
 */
async function requestSetupCheck(repo, { force = false } = {}, opts = {}) {
  if (repo !== "*" && (typeof repo !== "string" || !REPO_FULL.test(repo))) return { error: `not an owner/name repo or *: ${JSON.stringify(repo)}` };
  const body = force ? { repo, force: true } : { repo };
  const r = await sendJson("POST", "/repos/setup/check", body, { ...opts, parse: true });
  if (r.error) return { error: / 404 /.test(r.error) ? `${repo} is not a target repo` : r.error };
  const v = r.value && typeof r.value === "object" ? r.value : {};
  const names = (x) => (Array.isArray(x) ? x.filter((n) => typeof n === "string") : []);
  return { queued: names(v.queued), fresh: names(v.fresh) };
}

/**
 * The repo switcher's list (#577): the target
 * repos' names from `GET /repos`, and each one's `GET /needs` row count for
 * its badge. No setup check and no board read, unlike fetchRepos. Resolves to
 * { repos: [name], needs: { name: count }, needsError } or { error, repos: null };
 * never rejects. A failed /needs read leaves the badges out, not the list.
 * `opts.needs: false` skips /needs (about a second on a busy engine, #1693):
 * a repo pick only checks the name against the list.
 */
async function fetchRepoNames(opts = {}) {
  const skipNeeds = opts.needs === false;
  const [list, needs] = await Promise.all([getJson("/repos", opts),
    skipNeeds ? { error: "not read" } : getJson("/needs", opts)]);
  if (list.error) return { error: list.error, repos: null, needs: {}, needsError: null };
  if (!Array.isArray(list.value)) return { error: "engine's /repos is not a list", repos: null, needs: {}, needsError: null };
  const repos = list.value.filter((r) => r && typeof r.fullName === "string" && REPO_FULL.test(r.fullName)).map((r) => r.fullName);
  const rows = !needs.error && Array.isArray(needs.value) ? needs.value : null;
  const counts = {};
  if (rows) {
    for (const name of repos) counts[name] = 0;
    for (const n of rows) {
      const key = n && typeof n.repo === "string" && repos.find((r) => r.toLowerCase() === n.repo.toLowerCase());
      if (key) counts[key] += 1;
    }
  }
  return { repos, needs: counts, needsError: rows ? null : (needs.error || "engine's /needs is not a list"), error: null };
}

/**
 * The engine-wide list of trusted logins and its audit, `GET /access`: a login
 * on it is trusted on every target repo. Resolves to { access: { logins,
 * history } } or { error }; never rejects.
 */
async function fetchEngineAccess(opts = {}) {
  const r = await getJson("/access", opts);
  if (r.error) return { error: r.error };
  const v = r.value;
  if (!v || !Array.isArray(v.logins)) return { error: "engine's /access has no `logins` list" };
  return { access: { logins: v.logins, history: Array.isArray(v.history) ? v.history : [] }, error: null };
}

/**
 * Add a login to the trusted list or remove it, as `by` (main's signed-in login):
 *   { op: "add", login }     POST /access { login, by }
 *   { op: "remove", login }  DELETE /access/<login>?by=
 * Resolves to { ok, … } or { error }; never rejects.
 */
async function engineAccessAction(action, by, opts = {}) {
  const a = action && typeof action === "object" ? action : {};
  if (a.op !== "add" && a.op !== "remove") return { error: `unknown engine access op ${JSON.stringify(a.op)}` };
  const login = typeof a.login === "string" ? a.login.trim() : "";
  if (!GITHUB_LOGIN.test(login)) return { error: "login is a GitHub login" };
  if (typeof by !== "string" || !GITHUB_LOGIN.test(by)) return { error: `no GitHub login to ${a.op} as (sign in to the engine: Config > Sign in)` };
  return a.op === "add"
    ? sendJson("POST", "/access", { login }, opts)
    : sendJson("DELETE", `/access/${encodeURIComponent(login)}`, undefined, opts);
}

// A check name as the engine takes it (TargetRepoController.checkName), and a
// path prefix (its `paths`): not blank, no commas, short enough for the column.
const MAX_CHECK_CHARS = 200;
const MAX_PATHS_CHARS = 2000;

const checkFields = (a) => {
  const body = {};
  if ("ciCheck" in a) {
    const c = typeof a.ciCheck === "string" ? a.ciCheck.trim() : a.ciCheck;
    if (c !== null && c !== "" && typeof c !== "string") return { error: "ciCheck is a check run's name, or empty for none" };
    if (typeof c === "string" && c.length > MAX_CHECK_CHARS) return { error: `ciCheck is at most ${MAX_CHECK_CHARS} characters` };
    body.ciCheck = c || null;
  }
  if ("ciPaths" in a) {
    if (a.ciPaths !== null && !Array.isArray(a.ciPaths)) return { error: "ciPaths is a list of path prefixes" };
    const paths = (a.ciPaths || []).map((p) => (typeof p === "string" ? p.trim() : p));
    if (paths.some((p) => typeof p !== "string" || !p || p.includes(","))) return { error: "ciPaths are path prefixes, not blank, with no commas" };
    if (paths.join(",").length > MAX_PATHS_CHARS) return { error: "ciPaths are too long" };
    body.ciPaths = paths;
  }
  return { body };
};

/**
 * One action from Config > Repos, validated, then sent. `by` is the signed-in
 * GitHub login main resolves (the same one a retry or an answer goes as),
 * never the renderer's:
 *   { op: "add", repo, token? }            POST /repos, PUT …/token when a token is given, then GET …/setup:
 *                                          { ok, setup | setupError }
 *   { op: "token", repo, token }           PUT /repos/<o>/<n>/token { token }: the repo's own GitHub token, sealed by the
 *                                          engine after it tried it against GitHub; a 400 is GitHub refusing it
 *   { op: "clear-token", repo }            DELETE /repos/<o>/<n>/token: the repo has no token until one is set
 *   { op: "webhook-secret", repo, secret } PUT /repos/<o>/<n>/webhook-secret { secret }: the secret GitHub signs the
 *                                          repo's webhook deliveries with, sealed by the engine and never read back
 *   { op: "clear-webhook-secret", repo }   DELETE /repos/<o>/<n>/webhook-secret: the repo's deliveries are refused
 *   { op: "remove", repo }                 DELETE /repos/<o>/<n>; a 409 says the engine's reason (the running flows)
 *   { op: "check", repo, ciCheck?, ciPaths? }  PATCH /repos/<o>/<n>: { ok, repo } (the updated row)
 *   { op: "security-review", repo, securityReview }  PATCH /repos/<o>/<n> { securityReview }: false skips the
 *                                          security review in the repo's chores, bugs and features from their next push
 *   { op: "doctor-bugs", repo, doctorBugs, engineDefects? }  PATCH /repos/<o>/<n> { doctorBugs, engineDefects? }:
 *                                          whether the doctor files bugs for the repo's stuck tickets, and whether the
 *                                          repo is the one engine defects are filed in; the engine's 400 or 409 (the
 *                                          409 names the repo holding engine defects) is the error, in full
 *   { op: "docs-first", repo, docsFirst }  PATCH /repos/<o>/<n> { docsFirst }: whether the repo is docs-first (its
 *                                          tickets default to the light docs path); the engine's message is the error
 *   { op: "hitlp-surface", repo, hitlpSurface }  PATCH /repos/<o>/<n> { hitlpSurface }: whether frakworks facilitates
 *                                          the repo's HITLP requests as a surface (#2500); the engine's message is the error
 *   { op: "child-mode", repo, childMode, docsFirst? }  PATCH /repos/<o>/<n> { childMode, docsFirst? }: how a feature's split
 *                                          is cut and its children worked, serial, parallel or live (any other value is
 *                                          refused here); with `docsFirst` when the page changed both; the engine's message is the error
 *   { op: "friction-bugs", repo, frictionBugs }  PATCH /repos/<o>/<n> { frictionBugs }: whether the engine files one bug
 *                                          per group of friction notes whose note Jev marks as two things, in this repo;
 *                                          the engine's 400, 403 or 409 message is the error, in full
 *   { op: "scan-interval", repo, scanIntervalMinutes }  PATCH /repos/<o>/<n> { scanIntervalMinutes }: how often the
 *                                          engine scans the repo on its own, a whole number of minutes from 1 to 1440
 *   { op: "labels", repo }                 POST …/setup/labels: { ok, created }
 *   { op: "setup-check", repo }            POST /repos/setup/check { repo, force: true }: Create labels' re-check,
 *                                          past the engine's 5 min freshness; the report comes as a `setup` frame
 * Resolves to { ok, … } or { error }; never rejects.
 */
async function repoAction(action, by, opts = {}) {
  const a = action && typeof action === "object" ? action : {};
  const at = repoPath(a.repo);
  if (!at) return { error: `not an owner/name repo: ${JSON.stringify(a.repo)}` };
  const repo = a.repo;
  switch (a.op) {
    case "add": {
      const token = typeof a.token === "string" ? a.token.trim() : "";
      if (token) { const p = tokenProblem(token); if (p) return { error: p }; }
      const added = await sendJson("POST", "/repos", { repo }, opts);
      if (added.error) return added;
      if (token) {
        // A repo the token cannot reach is refused here, with the row left to fix, not scanned.
        const t = await putRepoToken(at, token, opts);
        if (t.error) return { error: `added ${repo}, but its token was refused: ${t.error}` };
      }
      const s = await fetchRepoSetup(repo, opts);
      return { ok: true, status: added.status, setup: s.setup || null, setupError: s.error || null };
    }
    case "remove": {
      // sendJson cuts the engine's reason to 200 characters; a 409 names every running flow, so it keeps the body.
      const fetch = opts.fetch || globalThis.fetch;
      let seen = null;
      const keep = async (url, init) => {
        const res = await fetch(url, init);
        const text = await res.text().catch(() => "");
        seen = { status: res.status, text };
        return { ok: res.ok, status: res.status, text: async () => text };
      };
      const r = await sendJson("DELETE", at, undefined, { ...opts, fetch: keep });
      if (!r.error || !seen || seen.status !== 409) return r;
      try {
        const j = JSON.parse(seen.text);
        if (j && typeof j.error === "string") return { error: j.error, flows: Array.isArray(j.flows) ? j.flows : [] };
      } catch { /* not JSON: sendJson's words stand */ }
      return r;
    }
    case "check": {
      const f = checkFields(a);
      if (f.error) return f;
      if (!Object.keys(f.body).length) return { error: "nothing to change: give ciCheck or ciPaths" };
      const r = await sendJson("PATCH", at, f.body, { ...opts, parse: true });
      if (r.error) return r;
      return { ok: true, status: r.status, repo: r.value };
    }
    case "security-review": {
      if (typeof a.securityReview !== "boolean") return { error: "securityReview is true or false" };
      const r = await sendJson("PATCH", at, { securityReview: a.securityReview }, { ...opts, parse: true });
      if (r.error) return r;
      return { ok: true, status: r.status, repo: r.value };
    }
    case "doctor-bugs": {
      if (typeof a.doctorBugs !== "boolean") return { error: "doctorBugs is true or false" };
      if (a.engineDefects !== undefined && typeof a.engineDefects !== "boolean") return { error: "engineDefects is true or false, or left out" };
      const body = a.engineDefects === undefined ? { doctorBugs: a.doctorBugs } : { doctorBugs: a.doctorBugs, engineDefects: a.engineDefects };
      const r = await sendJson("PATCH", at, body, { ...opts, parse: true });
      if (r.error) return r;
      return { ok: true, status: r.status, repo: r.value };
    }
    case "docs-first": {
      if (typeof a.docsFirst !== "boolean") return { error: "docsFirst is true or false" };
      const r = await sendJson("PATCH", at, { docsFirst: a.docsFirst }, { ...opts, parse: true });
      if (r.error) return r;
      return { ok: true, status: r.status, repo: r.value };
    }
    case "hitlp-surface": {
      if (typeof a.hitlpSurface !== "boolean") return { error: "hitlpSurface is true or false" };
      const r = await sendJson("PATCH", at, { hitlpSurface: a.hitlpSurface }, { ...opts, parse: true });
      if (r.error) return r;
      return { ok: true, status: r.status, repo: r.value };
    }
    case "child-mode": {
      if (!CHILD_MODES.includes(a.childMode)) return { error: "childMode is serial, parallel or live" };
      if (a.docsFirst !== undefined && typeof a.docsFirst !== "boolean") return { error: "docsFirst is true or false" };
      const body = a.docsFirst === undefined ? { childMode: a.childMode } : { childMode: a.childMode, docsFirst: a.docsFirst };
      const r = await sendJson("PATCH", at, body, { ...opts, parse: true });
      if (r.error) return r;
      return { ok: true, status: r.status, repo: r.value };
    }
    case "friction-bugs": {
      if (typeof a.frictionBugs !== "boolean") return { error: "frictionBugs is true or false" };
      const r = await sendJson("PATCH", at, { frictionBugs: a.frictionBugs }, { ...opts, parse: true });
      if (r.error) return r;
      return { ok: true, status: r.status, repo: r.value };
    }
    case "scan-interval": {
      const m = a.scanIntervalMinutes;
      if (!Number.isInteger(m) || m < 1 || m > 1440) return { error: "the scan interval is a whole number of minutes from 1 to 1440 (24 hours)" };
      const r = await sendJson("PATCH", at, { scanIntervalMinutes: m }, { ...opts, parse: true });
      if (r.error) return r;
      return { ok: true, status: r.status, repo: r.value };
    }
    case "setup-check": {
      const r = await requestSetupCheck(repo, { force: true }, opts);
      return r.error ? r : { ok: true, ...r };
    }
    case "labels": {
      const r = await sendJson("POST", `${at}/setup/labels`, undefined, { ...opts, parse: true });
      if (r.error) return r;
      return { ok: true, status: r.status, created: r.value && Array.isArray(r.value.created) ? r.value.created : [] };
    }
    case "token": {
      const token = typeof a.token === "string" ? a.token.trim() : "";
      const p = tokenProblem(token);
      if (p) return { error: p };
      return putRepoToken(at, token, opts);
    }
    case "clear-token":
      return sendJson("DELETE", `${at}/token`, undefined, opts);
    case "webhook-secret": {
      const secret = typeof a.secret === "string" ? a.secret.trim() : "";
      if (secret.length < 16) return { error: "a webhook secret is at least 16 characters" };
      if (/\s/.test(secret)) return { error: "a webhook secret has no whitespace in it" };
      if (secret.length > 400) return { error: "a webhook secret is at most 400 characters" };
      const r = await sendJson("PUT", `${at}/webhook-secret`, { secret }, opts);
      if (r.error && / 503 /.test(r.error)) r.error += " (the engine has no ENGINE_SECRETS_KEY, so it cannot seal a secret)";
      return r;
    }
    case "clear-webhook-secret":
      return sendJson("DELETE", `${at}/webhook-secret`, undefined, opts);
    default:
      return { error: `unknown repo action ${JSON.stringify(a.op)}` };
  }
}

/** Why `token` cannot be a repo's GitHub token, or null: the same shape the engine refuses with a 400. */
const tokenProblem = (token) => {
  if (!token) return "token is empty";
  if (/\s/.test(token)) return "token has whitespace in it";
  if (token.length > 400) return "token is too long for a GitHub token";
  return null;
};

/** `PUT /repos/<o>/<n>/token`; the engine's 503 (no sealing key) said in the app's words, its 400 (GitHub refused it) as it is. */
async function putRepoToken(at, token, opts) {
  const r = await sendJson("PUT", `${at}/token`, { token }, opts);
  if (r.error && / 503 /.test(r.error)) r.error += " (the engine has no ENGINE_SECRETS_KEY, so it cannot seal a token)";
  return r;
}

// A saved chat's id (#598): the UUID the desktop minted for the conversation,
// checked here too so nothing odd lands in a URL path.
const SAVED_CHAT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const savedChatIdProblem = (id) => (typeof id === "string" && SAVED_CHAT_ID.test(id) ? null : `not a saved chat id: ${JSON.stringify(id)}`);

/**
 * The saved First mate chats, `GET /saved-chats` (engine/README.md, "Saved
 * First mate chats"): newest saved first, without transcripts. Resolves to
 * { chats } or { error, chats: [] }; never rejects.
 */
async function fetchSavedChats(opts = {}) {
  const r = await getJson("/saved-chats", opts);
  if (r.error) return { error: r.error, chats: [] };
  if (!Array.isArray(r.value)) return { error: "engine's /saved-chats is not a list", chats: [] };
  return { chats: r.value.filter((c) => c && typeof c.id === "string"), error: null };
}

/**
 * One saved chat with its `messages`, `GET /saved-chats/<id>`. Resolves to
 * { chat } or { error }; never rejects.
 */
async function fetchSavedChat(id, opts = {}) {
  const bad = savedChatIdProblem(id);
  if (bad) return { error: bad };
  const r = await getJson(`/saved-chats/${id}`, opts);
  if (r.error) return / 404 /.test(r.error) ? { error: `no saved chat ${id}` } : { error: r.error };
  const chat = r.value;
  if (!chat || chat.id !== id || !Array.isArray(chat.messages)) return { error: `engine's /saved-chats/${id} has no matching \`id\` and \`messages\`` };
  return { chat, error: null };
}

/**
 * Saves a chat, `PUT /saved-chats/<id>` with `body` ({ title, startedAt,
 * repo, tier, claudeSessionId, savedFrom, messages }): 201 when it is new,
 * 200 when it updated an earlier save. Resolves to { ok, created, chat } or
 * { error }; never rejects.
 */
async function saveChat(id, body, opts = {}) {
  const bad = savedChatIdProblem(id);
  if (bad) return { error: bad };
  const r = await sendJson("PUT", `/saved-chats/${id}`, body, { ...opts, parse: true });
  if (r.error) return r;
  const chat = r.value;
  return { ok: true, created: r.status === 201, chat };
}

/** Deletes a saved chat, `DELETE /saved-chats/<id>`. Resolves to { ok } or { error }; never rejects. */
async function deleteSavedChat(id, opts = {}) {
  const bad = savedChatIdProblem(id);
  if (bad) return { error: bad };
  return sendJson("DELETE", `/saved-chats/${id}`, undefined, opts);
}

// ---- custom jobs (Config > Jobs, #1228) ------------------------------------------
//
// engine/README.md, "Custom jobs and the SDK": GET /jobs?repo= (open, like
// every read) and the three writes, each as the signed-in login `by` (the
// bearer token names it; a write is not sent without one). A write's failure carries the
// engine's `status` and whole `body` (sendJson's `keep`), shown verbatim.

const JOB_ID = /^[\w-]{1,64}$/;
const JOB_KINDS = ["prompt", "bean", "flow"];
const JOB_CONFIG_KEYS = { prompt: ["prompt"], bean: ["jar", "bean"], flow: ["jar", "process"] };
const signedIn = (by) => typeof by === "string" && GITHUB_LOGIN.test(by);
const noLogin = (what) => ({ error: `no GitHub login to ${what} as (sign in to the engine: Config > Sign in)` });

/** GET /jobs?repo=<owner/name>: { jobs } or { error, jobs: [] }. A missing or malformed repo is refused, not sent. */
async function fetchJobs(repo, opts = {}) {
  if (typeof repo !== "string" || !REPO_FULL.test(repo)) return { error: `jobs need a repo (owner/name), got ${JSON.stringify(repo ?? null)}`, jobs: [] };
  const r = await getJson(`/jobs?repo=${encodeURIComponent(repo)}`, opts);
  if (r.error) return { error: r.error, jobs: [] };
  if (!Array.isArray(r.value)) return { error: "engine's /jobs is not a list", jobs: [] };
  return { jobs: r.value };
}

/** POST /jobs with exactly { name, kind, repo, config, signal }: { ok, job } or { error, status, body }. */
async function registerJob(job, by, opts = {}) {
  if (!signedIn(by)) return noLogin("register a job");
  const j = job && typeof job === "object" ? job : {};
  if (typeof j.repo !== "string" || !REPO_FULL.test(j.repo)) return { error: `not an owner/name repo: ${JSON.stringify(j.repo ?? null)}` };
  const body = { name: j.name, kind: j.kind, repo: j.repo, config: j.config, signal: j.signal };
  const r = await sendJson("POST", "/jobs", body, { ...opts, parse: true, keep: true });
  return r.error ? r : { ok: true, status: r.status, job: r.value };
}

/** POST /jobs/{id}/execute: { ok, instance } or { error, status, body }. */
async function executeJob(id, by, opts = {}) {
  if (!JOB_ID.test(String(id))) return { error: `not a job id: ${JSON.stringify(id)}` };
  if (!signedIn(by)) return noLogin("run a job");
  const r = await sendJson("POST", `/jobs/${id}/execute`, undefined, { ...opts, parse: true, keep: true });
  return r.error ? r : { ok: true, status: r.status, instance: r.value && r.value.instance || null };
}

/** DELETE /jobs/{id}: { ok } (204) or { error, status, body }. */
async function deleteJob(id, by, opts = {}) {
  if (!JOB_ID.test(String(id))) return { error: `not a job id: ${JSON.stringify(id)}` };
  if (!signedIn(by)) return noLogin("delete a job");
  return sendJson("DELETE", `/jobs/${id}`, undefined, { ...opts, keep: true });
}

/** GET /jobs/{id}/runs?limit=: { runs } (newest first) or { error, runs: [] } (engine/README.md, "Refactoring jobs", "Run history", #1653). */
async function fetchJobRuns(id, limit = 10, opts = {}) {
  if (typeof id !== "string" || !/^[A-Za-z0-9-]{1,64}$/.test(id)) return { error: `job runs need a job id, got ${JSON.stringify(id ?? null)}`, runs: [] };
  const n = Number.isInteger(limit) && limit >= 1 && limit <= 200 ? limit : 10;
  const r = await getJson(`/jobs/${encodeURIComponent(id)}/runs?limit=${n}`, opts);
  if (r.error) return { error: r.error, runs: [] };
  if (!Array.isArray(r.value)) return { error: "engine's job runs are not a list", runs: [] };
  return { runs: r.value };
}

// ---- refactoring config (Config > frakworks > Refactoring, #1399) ---------------
//
// engine/README.md, "Refactoring jobs": GET/PUT /repos/{owner}/{name}/refactor.
// A PUT sends only the keys that changed; `values: null` returns the repo to the
// shipped values. A 400 names the field, carried verbatim (sendJson's `keep`).

const refactorPath = (repo) => { const [o, n] = repo.split("/"); return `/repos/${encodeURIComponent(o)}/${encodeURIComponent(n)}/refactor`; };
const badRepo = (repo) => (typeof repo !== "string" || !REPO_FULL.test(repo) ? `refactoring needs a repo (owner/name), got ${JSON.stringify(repo ?? null)}` : null);

/** GET /repos/{owner}/{name}/refactor: { config } or { error }. A missing or malformed repo is refused, not sent. */
async function fetchRefactorConfig(repo, opts = {}) {
  const bad = badRepo(repo);
  if (bad) return { error: bad };
  const r = await getJson(refactorPath(repo), opts);
  if (r.error) return { error: r.error };
  if (!r.value || typeof r.value !== "object" || Array.isArray(r.value)) return { error: "engine's refactor config is not an object" };
  return { config: r.value };
}

/** PUT /repos/{owner}/{name}/refactor with `patch`, as `by`: { ok, config } or { error, status, body }. */
async function saveRefactorConfig(repo, patch, by, opts = {}) {
  const bad = badRepo(repo);
  if (bad) return { error: bad };
  if (!signedIn(by)) return noLogin("change refactoring");
  if (!patch || typeof patch !== "object" || Array.isArray(patch)) return { error: "no refactoring change to send" };
  const r = await sendJson("PUT", refactorPath(repo), patch, { ...opts, parse: true, keep: true });
  return r.error ? r : { ok: true, config: r.value };
}

// First mate's per-repo MCP servers (#1801; engine/README.md, "First mate's
// MCP servers"): the Configure First Mate modal's list, save, delete and
// engine test, and the spawn's config with the secrets opened (main only).
const MCP_SERVER_NAME = /^[A-Za-z0-9_-]{1,64}$/;
const mcpPath = (repo, rest = "") => { const [o, n] = repo.split("/"); return `/repos/${encodeURIComponent(o)}/${encodeURIComponent(n)}/mcp-servers${rest}`; };
const badMcpRepo = (repo) => (typeof repo !== "string" || !REPO_FULL.test(repo) ? `MCP servers need a repo (owner/name), got ${JSON.stringify(repo ?? null)}` : null);
const badMcpName = (name) => (typeof name !== "string" || !MCP_SERVER_NAME.test(name) ? `not an MCP server name: ${JSON.stringify(name ?? null)}` : null);

/** GET /repos/{o}/{n}/mcp-servers: { servers } (the read view, no secret value) or { error }. */
async function fetchRepoMcpServers(repo, opts = {}) {
  const bad = badMcpRepo(repo);
  if (bad) return { error: bad };
  const r = await getJson(mcpPath(repo), opts);
  if (r.error) return { error: r.error };
  if (!Array.isArray(r.value)) return { error: "engine's MCP server list is not a list" };
  return { servers: r.value };
}

/** PUT /repos/{o}/{n}/mcp-servers/{name} with `body`, as `by`: { ok, server } or { error, status, body }. */
async function saveRepoMcpServer(repo, name, body, by, opts = {}) {
  const bad = badMcpRepo(repo) || badMcpName(name);
  if (bad) return { error: bad };
  if (!signedIn(by)) return noLogin("change MCP servers");
  if (!body || typeof body !== "object" || Array.isArray(body)) return { error: "no MCP server to send" };
  const r = await sendJson("PUT", mcpPath(repo, `/${encodeURIComponent(name)}`), body, { ...opts, parse: true, keep: true });
  return r.error ? r : { ok: true, server: r.value };
}

/** DELETE /repos/{o}/{n}/mcp-servers/{name}, as `by`: { ok } or { error }. */
async function deleteRepoMcpServer(repo, name, by, opts = {}) {
  const bad = badMcpRepo(repo) || badMcpName(name);
  if (bad) return { error: bad };
  if (!signedIn(by)) return noLogin("change MCP servers");
  return sendJson("DELETE", mcpPath(repo, `/${encodeURIComponent(name)}`), undefined, opts);
}

/** POST /repos/{o}/{n}/mcp-servers/{name}/test: { reached, tools, error } or { error }. */
async function testRepoMcpServer(repo, name, opts = {}) {
  const bad = badMcpRepo(repo) || badMcpName(name);
  if (bad) return { reached: false, tools: [], error: bad };
  const r = await sendJson("POST", mcpPath(repo, `/${encodeURIComponent(name)}/test`), undefined, { ...opts, parse: true });
  if (r.error) return { reached: false, tools: [], error: r.error };
  const v = r.value || {};
  return { reached: Boolean(v.reached), tools: Array.isArray(v.tools) ? v.tools : [], error: v.error || null };
}

/** GET /repos/{o}/{n}/mcp-servers/config: { servers } (name to `--mcp-config` entry, secrets opened) or { error }. Main only. */
async function fetchRepoMcpConfig(repo, opts = {}) {
  const bad = badMcpRepo(repo);
  if (bad) return { error: bad };
  const r = await getJson(mcpPath(repo, "/config"), opts);
  if (r.error) return { error: r.error };
  const servers = r.value && r.value.mcpServers;
  if (!servers || typeof servers !== "object" || Array.isArray(servers)) return { error: "engine's MCP server config has no mcpServers" };
  return { servers };
}

/**
 * `GET /whoami` with `token` as the bearer (engine/README.md, "Tokens"), the
 * one request that carries a token the sign-in has not kept yet: resolves to
 * `{ login, tokenId, name }`, or `{ error, status }` with the engine's text
 * for a 401 or 403 (never the token), or `{ error }`; never rejects.
 */
async function fetchWhoAmI(token, { env = PROCESS_ENV, fetch = globalThis.fetch } = {}) {
  if (typeof token !== "string" || !token.trim()) return { error: "no token to verify" };
  const url = `${engineUrl(env)}/whoami`;
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs(env));
  try {
    const res = await fetch(url, { signal: ctl.signal, headers: { authorization: `Bearer ${token.trim()}`, accept: "application/json" } });
    const text = await res.text().catch(() => "");
    if (!res.ok) {
      let why = text;
      try { const j = JSON.parse(text); why = j.error || j.message || text; } catch { /* plain text */ }
      return { error: `engine answered ${res.status} for GET /whoami${why ? `: ${String(why).slice(0, 200)}` : ""}`, status: res.status };
    }
    let v;
    try { v = JSON.parse(text); } catch (e) { return { error: `engine produced no JSON for GET /whoami: ${e.message}` }; }
    if (!v || typeof v.login !== "string" || !GITHUB_LOGIN.test(v.login)) return { error: "engine's GET /whoami named no login" };
    return { login: v.login, tokenId: typeof v.tokenId === "string" ? v.tokenId : null, name: typeof v.name === "string" ? v.name : "" };
  } catch (e) {
    const why = e && e.name === "AbortError" ? `timed out after ${timeoutMs(env) / 1000}s` : (e && e.message) || String(e);
    return { error: `engine unreachable at ${url}: ${why}` };
  } finally { clearTimeout(t); }
}

/**
 * Pairing a phone from the web version (observability/desktop/README.md, "Pair
 * a phone"): `GET /settings/public-url` as `{ publicUrl, note }` (publicUrl null
 * while unset) or `{ error }`; never rejects.
 */
async function fetchPublicUrl(opts = {}) {
  const r = await getJson("/settings/public-url", opts);
  if (r.error) return { error: r.error };
  const v = r.value || {};
  return { publicUrl: typeof v.publicUrl === "string" && v.publicUrl ? v.publicUrl : null, note: typeof v.note === "string" ? v.note : null };
}

/**
 * `POST /tokens` for a phone: a first-mate token for `login`, named `name`.
 * Resolves to `{ id, token, prefix, name }` or `{ error }`. The token is shown
 * once on the page (its QR) and kept nowhere.
 */
async function mintPhoneToken(login, name, opts = {}) {
  if (typeof login !== "string" || !GITHUB_LOGIN.test(login)) return { error: "no signed-in login to mint a token for" };
  const r = await sendJson("POST", "/tokens", { login, name: String(name), purpose: "first-mate" }, { ...opts, parse: true });
  if (r.error) return { error: r.error };
  const v = r.value || {};
  if (typeof v.token !== "string" || !v.token || typeof v.id !== "string") return { error: "engine's POST /tokens had no id or token" };
  return { id: v.id, token: v.token, prefix: v.prefix || "", name: v.name || String(name) };
}

/** `GET /tokens`, as `{ tokens }` (the engine's rows) or `{ error, tokens: [] }`. */
async function fetchTokens(opts = {}) {
  const r = await getJson("/tokens", opts);
  if (r.error) return { error: r.error, tokens: [] };
  return { tokens: Array.isArray(r.value) ? r.value : [] };
}

/** `DELETE /tokens/{id}`: revokes the token (and ends its sessions). `{ ok }` or `{ error }`. */
async function revokeToken(id, opts = {}) {
  if (typeof id !== "string" || !id) return { error: "no token id to revoke" };
  const r = await sendJson("DELETE", `/tokens/${encodeURIComponent(id)}`, undefined, opts);
  return r.error ? { error: r.error } : { ok: true };
}

module.exports = { fetchDesignDeck, fetchRetrying, RETRY_DELAY_MS, fetchPublicUrl, mintPhoneToken, fetchTokens, revokeToken, PAGE_ENV, setBearer, authHeaders, SIGN_IN_PROMPT, fetchWhoAmI, teachJudge, fetchRepoMcpServers, saveRepoMcpServer, deleteRepoMcpServer, testRepoMcpServer, fetchRepoMcpConfig, trustSystemCAs, fetchActiveBuilds, fetchIssues, fetchFlow, INSTANCE_ID, MAX_VARIABLE_CHARS, fetchAccounts, pickAccount, fetchRefinerTier, setRefinerTier, fetchSmithHosting, setSmithHosting, SMITH_HOSTINGS, fetchFirstMateHosting, setFirstMateHosting, FIRST_MATE_HOSTINGS, startFirstMateSession, sendFirstMateMessage, endFirstMateSession, fetchFirstMateTranscript, fetchFrakpanelTiles, setFrakpanelTiles, fetchTiers, fetchTierAccounts, setTier, fetchSecurityTier, setSecurityTier, fetchCheckTier, setCheckTier, accountAction, MAX_EMAIL_CHARS, sendJson, ACCOUNT_NAME, PROVIDERS, getJson, engineUrl, DEFAULT_ENGINE_URL, DEFAULT_TIMEOUT_SECS,
  fetchProcessInstances, fetchProcessTree, fetchProcessXml, ISSUE_URL, DEFINITION_ID, retryIncident, cancelFlow, GITHUB_LOGIN,
  fetchIncidentFixes, fetchDoctorHistory, fetchDoctorRuns, fetchDoctorBugs, fetchConsumption, CONSUMPTION_WINDOWS, fetchCiRuns, ISO_INSTANT, startIncidentFix, startIncidentPropose, uploadAttachment,
  submitIntake, MAX_ASK_CHARS, fetchLineage,
  fetchTicketTimeline, REPO_PART, summarizeTicket, predictReview,
  fetchNeeds, fetchClosedIssues, fetchTicketDetail, fetchChanges, fetchTicketPitch, fetchProposals, fetchProposalPage, PROPOSAL_SLUG, fetchRepoTree, fetchRepoFile, REPO_FULL, DEFAULT_CLOSED_LIMIT, MAX_CLOSED_LIMIT,
  fetchJevCalls, fetchJevCall, fetchJevSummary, fetchSshVerdicts, JEV_CALL_ID, JEV_USE_CASE, MAX_JEV_LIMIT,
  fetchFrictionSummary, fetchFrictionNotes, fetchFrictionGroups, frictionPullOut, frictionNotTwoThings, frictionMerge, frictionFix, FRICTION_KINDS, FRICTION_SOURCES, MAX_FRICTION_LIMIT,
  fetchRepos, fetchRepoRows, requestSetupCheck, fetchRepoSetup, repoAction, fetchEngineAccess, engineAccessAction, fetchRepoNames,
  fetchSavedChats, fetchSavedChat, saveChat, deleteSavedChat, SAVED_CHAT_ID,
  fetchJobs, fetchJobRuns, registerJob, executeJob, deleteJob, JOB_KINDS, JOB_CONFIG_KEYS,
  fetchRefactorConfig, saveRefactorConfig };
