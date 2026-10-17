// deployment-config.js — the deployment values the app needs that are not
// framework: which registry publishes the engine image (engine-update.js) and
// which feed the packaged app updates from (app-update.js), and where the
// telemetry collector's VictoriaLogs and VictoriaMetrics answer. All are one lab's
// names, so they live in
// `agent-army.config.json` next to the Grafana keys, never in the tree:
//
//   registry      host:port of the plain-HTTP image registry, e.g.
//                 registry.example:5000. Unset: localhost:5000.
//   release_feed  the packaged app's update feed, e.g. http://releases.example/.
//                 Unset: the URL baked into the build (package.json's placeholder).
//   logs_url      VictoriaLogs base URL, e.g. http://logs.example:9428/, for the
//                 transcript pane and its live tail. Unset: those panes say so.
//   metrics_url   VictoriaMetrics base URL, e.g. http://metrics.example:8428/, for
//                 the transcript pane's live-run gauge. Unset: likewise.
//
// A malformed value is reported and ignored, so a typo costs the Update engine
// button or the self-update, not the app.
"use strict";

const { readConfigFile } = require("./answer-config");

const REGISTRY_RE = /^[A-Za-z0-9.-]+(:[0-9]{1,5})?$/;

/** An http(s) URL without credentials, with a trailing slash; else null. */
function httpBase(raw) {
  const v = String(raw).trim();
  try {
    const u = new URL(v);
    if ((u.protocol !== "http:" && u.protocol !== "https:") || u.username || u.password) return null;
  } catch { return null; }
  return v.endsWith("/") ? v : `${v}/`;
}

/** Parse the keys out of a config object. `{ registry, releaseFeed, logsUrl, metricsUrl, errors }`. */
function parseDeployment(cfg) {
  const out = { registry: null, releaseFeed: null, logsUrl: null, metricsUrl: null, errors: [] };
  const c = cfg && typeof cfg === "object" ? cfg : {};
  if (c.registry != null) {
    const v = String(c.registry).trim();
    if (REGISTRY_RE.test(v)) out.registry = v;
    else out.errors.push(`registry must be host[:port], got ${JSON.stringify(c.registry)}`);
  }
  for (const [key, field] of [["release_feed", "releaseFeed"], ["logs_url", "logsUrl"], ["metrics_url", "metricsUrl"]]) {
    if (c[key] == null) continue;
    const v = httpBase(c[key]);
    if (v) out[field] = v;
    else out.errors.push(`${key} must be an http(s) URL without credentials, got ${JSON.stringify(c[key])}`);
  }
  return out;
}

/** Read the config file and parse it; a missing file gives the defaults. */
function loadDeployment({ file, readFileSync } = {}) {
  const r = readConfigFile(file, readFileSync ? { readFileSync } : undefined);
  if (r.error) return { registry: null, releaseFeed: null, logsUrl: null, metricsUrl: null, errors: [r.error] };
  return parseDeployment(r.value);
}

module.exports = { parseDeployment, loadDeployment };
