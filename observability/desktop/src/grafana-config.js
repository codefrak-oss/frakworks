// grafana-config.js — the Grafana public dashboards under Explorer > Analytics
// (issue #332).
//
// Two keys in `agent-army.config.json`, both public configuration:
//
//   grafana_url         the Grafana base URL, e.g. http://grafana.example:3000.
//                       Plain http is allowed: this is a LAN host.
//   grafana_dashboards  [{ label, path, builds? }], each `path` a Grafana public
//                       dashboard, e.g. /public-dashboards/<id>; `builds: true`
//                       shows the engine's builds in progress above it.
//
// They are parsed here, so a missing or broken Grafana block costs the
// Grafana rows and nothing else: First mate reads the same file through
// answer-config.js and never sees these keys. The app holds no Grafana credential and needs none — a public
// dashboard is readable without login — so there is no field for one.
//
// The rest of the file is what main needs to show a dashboard safely: the
// iframe URL, the page whose CSP allows exactly that origin in `frame-src`,
// the probe that tells a down Grafana from a slow one, and the rule for which
// URLs may leave the app for the system browser.
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { readConfigFile } = require("./answer-config");

// The app is dark (styles.css), so the frame is too. `kiosk` hides Grafana's
// own chrome. No `refresh`: the dashboard's own interval stands.
const FRAME_PARAMS = Object.freeze([["theme", "dark"], ["kiosk", ""]]);

// How long the probe waits for Grafana before calling it down.
const PROBE_TIMEOUT_MS = 5000;

const str = (v) => (typeof v === "string" ? v.trim() : "");

/**
 * `grafana_url` and `grafana_dashboards` from a parsed config object.
 *
 * `{ base, origin, dashboards: [{ label, path, url } | { error }], error }`.
 * Both keys missing is not an error: there are simply no dashboards. A bad
 * `grafana_url` or a list that is not a list is one `error` and no rows; a bad
 * entry is its own `{ error }` row, and the good ones around it still show.
 */
function parseGrafana(cfg) {
  const none = { base: null, origin: null, dashboards: [], error: null };
  const obj = cfg && typeof cfg === "object" && !Array.isArray(cfg) ? cfg : {};
  const rawUrl = obj.grafana_url, rawList = obj.grafana_dashboards;
  if ((rawUrl === undefined || rawUrl === null || rawUrl === "") && (rawList === undefined || rawList === null)) return none;
  const base = baseUrl(rawUrl);
  if (base.error) return { ...none, error: base.error };
  if (rawList === undefined || rawList === null) return { ...none, base: base.base, origin: base.origin };
  if (!Array.isArray(rawList)) {
    return { ...none, error: "grafana_dashboards must be a list of { \"label\": …, \"path\": … }" };
  }
  const dashboards = rawList.map((d, i) => dashboardEntry(d, i, base.base));
  return { base: base.base, origin: base.origin, dashboards, error: null };
}

// `http(s)://host[:port][/sub/path]`, no query, no fragment, no credentials.
// The trailing slash goes, so `base + path` never doubles it.
function baseUrl(value) {
  const v = str(value);
  if (!v) return { error: "grafana_url is missing; it is needed for grafana_dashboards (e.g. http://grafana.example:3000)" };
  let u;
  try { u = new URL(v); } catch (e) { return { error: `grafana_url is not a URL: ${JSON.stringify(value)}` }; }
  if (u.protocol !== "http:" && u.protocol !== "https:") return { error: `grafana_url must be http or https, not ${u.protocol}//` };
  if (u.search || u.hash) return { error: "grafana_url must have no query string or fragment" };
  if (u.username || u.password) return { error: "grafana_url must carry no credentials; the dashboards are public" };
  return { base: `${u.origin}${u.pathname.replace(/\/+$/, "")}`, origin: u.origin };
}

function dashboardEntry(d, i, base) {
  const at = `grafana_dashboards[${i}]`;
  if (!d || typeof d !== "object" || Array.isArray(d)) return { error: `${at} must be { "label": …, "path": … }` };
  const label = str(d.label);
  if (!label) return { error: `${at} has no label` };
  const p = str(d.path);
  if (!p.startsWith("/") || p.startsWith("//")) {
    return { label, error: `${label}: path must start with a single "/", e.g. /public-dashboards/<id>` };
  }
  if (p.includes("#")) return { label, error: `${label}: path must have no fragment` };
  const url = dashboardUrl(base, p);
  if (new URL(url).origin !== new URL(base).origin) return { label, error: `${label}: path leaves grafana_url's origin` };
  // `builds: true` puts the builds-in-progress strip above the frame (#1389).
  return d.builds === true ? { label, path: p, url, builds: true } : { label, path: p, url };
}

/** The iframe's URL: `base + path`, with `theme=dark&kiosk` and nothing else added. */
function dashboardUrl(base, p) {
  const [pathname, query = ""] = String(p).split("?");
  const params = new URLSearchParams(query);
  for (const [k, v] of FRAME_PARAMS) params.set(k, v);
  // `kiosk` is a bare flag; URLSearchParams would write `kiosk=`.
  const qs = params.toString().replace(/(^|&)kiosk=(?=&|$)/, "$1kiosk");
  return `${String(base).replace(/\/+$/, "")}${pathname}?${qs}`;
}

/**
 * The page's CSP with `frame-src` set to exactly `origin`. Every other
 * directive is left as it is. With no origin the policy is returned unchanged.
 */
function cspWithFrame(policy, origin) {
  const directives = String(policy).split(";").map((d) => d.trim()).filter(Boolean)
    .filter((d) => !/^frame-src\b/i.test(d));
  if (origin) directives.push(`frame-src ${new URL(origin).origin}`);
  return directives.join("; ");
}

const CSP_META = /(<meta\s+http-equiv="Content-Security-Policy"\s+content=")([^"]*)(")/i;

/**
 * index.html with `frame-src <origin>` in its CSP and a `<base>` pointing at
 * `rendererDir`, so its relative stylesheet and script links still resolve
 * from wherever the copy is written. A `<meta>` CSP cannot be loosened from
 * script, so allowing the frame means serving a page whose policy says so.
 */
function framePage(html, origin, rendererDir) {
  if (!CSP_META.test(html)) throw new Error("index.html has no Content-Security-Policy meta tag");
  const baseHref = require("node:url").pathToFileURL(path.join(rendererDir, path.sep)).href;
  return html
    .replace(CSP_META, (_m, a, policy, c) => `${a}${cspWithFrame(policy, origin)}${c}`)
    .replace(/<head>/i, `<head>\n  <base href="${baseHref}">`);
}

/**
 * The page the window loads: the checked-in index.html when there is no
 * Grafana origin, else a copy with the frame allowed, written under `outDir`.
 */
function pageToLoad({ rendererDir, origin, outDir, readFileSync = fs.readFileSync, writeFileSync = fs.writeFileSync, mkdirSync = fs.mkdirSync }) {
  const index = path.join(rendererDir, "index.html");
  if (!origin) return index;
  const out = path.join(outDir, "index.html");
  mkdirSync(outDir, { recursive: true });
  writeFileSync(out, framePage(readFileSync(index, "utf8"), origin, rendererDir));
  return out;
}

/**
 * Is Grafana answering at `url`? `{ ok: true }` or `{ ok: false, error }`,
 * the error naming the URL. A cross-origin iframe cannot report its own load
 * failure, so main asks first: refused, timed out and non-2xx are all down.
 */
async function probe(url, { fetchFn = globalThis.fetch, timeoutMs = PROBE_TIMEOUT_MS } = {}) {
  let res;
  try {
    res = await fetchFn(url, { method: "GET", redirect: "follow", signal: AbortSignal.timeout(timeoutMs) });
  } catch (e) {
    const why = e && (e.name === "TimeoutError" || e.name === "AbortError") ? `no answer within ${Math.round(timeoutMs / 1000)} s`
      : (e && e.cause && (e.cause.code || e.cause.message)) || (e && e.message) || String(e);
    return { ok: false, error: `Grafana is unreachable at ${url}: ${why}` };
  }
  try { if (res.body && res.body.cancel) await res.body.cancel(); } catch (e) { /* only freeing the socket */ }
  if (res.status < 200 || res.status > 299) {
    return { ok: false, error: `Grafana answered ${res.status}${res.statusText ? ` ${res.statusText}` : ""} at ${url}` };
  }
  return { ok: true };
}

/**
 * Whether a URL the window (or a frame in it) asks to open may be handed to
 * the system browser. Only http and https: a `file:`, `smb:` or custom-scheme
 * URL from a tampered page would otherwise be opened by the OS.
 */
function isExternalUrl(url) {
  try { const u = new URL(String(url)); return u.protocol === "http:" || u.protocol === "https:"; }
  catch (e) { return false; }
}

/** The config file's Grafana block, read the way answer-config.js reads the file. */
function loadGrafana({ file, readFileSync = fs.readFileSync } = {}) {
  if (!file) return parseGrafana({});
  const r = readConfigFile(file, { readFileSync });
  if (r.error) return { base: null, origin: null, dashboards: [], error: r.error };
  return parseGrafana(r.value);
}

module.exports = {
  FRAME_PARAMS, PROBE_TIMEOUT_MS,
  parseGrafana, dashboardUrl, cspWithFrame, framePage, pageToLoad, probe, isExternalUrl, loadGrafana,
};
