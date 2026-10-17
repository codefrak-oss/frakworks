// first-mate-tier.js — which tier First mate runs at (issue #231, part 1 of
// #226). Main-process only.
//
// The setting lives under `first_mate` in agent-army.config.json, the file in
// the app's data folder (src/answer-config.js's `configPath`):
//
//   first_mate.tier       the standing tier, 1-10; tier 8 (fable low) when unset
//
// Nothing here goes to the engine. Each turn, main resolves the effective tier
// with `resolveEffective`: the standing tier. The tier -> (model, effort) pair
// always comes from the engine's tier map through src/engine-tiers.js; the
// renderer sends tier numbers only (#100's rule), and they are validated here.
//
// Writes change `first_mate` and nothing else: the file is re-read, the one
// key replaced, and the result written to a temp file that is renamed over
// the original. A file `readConfigFile` refuses (bad JSON, or a key shaped
// like a credential) is never overwritten.
"use strict";

const fs = require("node:fs");
const path = require("node:path");

const { readConfigFile } = require("./answer-config");
const { resolveTier: defaultResolveTier } = require("./engine-tiers");

const DEFAULT_FIRST_MATE_TIER = 8;
// The tier a turn is retried at when Fable says its limit is reached.
const FALLBACK_TIER = 5;

// The text a Fable turn's `result` carries once the account's Fable allowance
// is used up, as the engine's tests record it
// (engine/src/test/kotlin/.../smith/SmithRunProcessTest.kt):
//   "You've reached your Fable limit. Switch to another model to continue."
// Matched case-insensitively on the phrase, as the engine does.
const FABLE_LIMIT_RE = /reached your fable limit/i;
const isFableLimit = (text) => typeof text === "string" && FABLE_LIMIT_RE.test(text);

// A tier from the renderer: a number (main has already applied `Number()`),
// an integer 1-10, and one the engine's tier map (src/engine-tiers.js) resolves. `null` when fine.
function tierProblem(n, resolveTierFn = defaultResolveTier) {
  if (typeof n !== "number" || !Number.isInteger(n) || n < 1 || n > 10) {
    return `pick a tier 1-10, not ${JSON.stringify(n)}`;
  }
  if (!resolveTierFn(n)) return `tier ${n} has no (model, effort): the engine's tier map is not loaded (is the engine up?)`;
  return null;
}

const validStoredTier = (v) => Number.isInteger(v) && v >= 1 && v <= 10;

// `{ tier, error }`. A missing file or key is the default; a bad stored value
// is ignored rather than trusted. A stale `first_mate.override` key (#1751)
// is ignored, not read.
function readFirstMateSettings(file) {
  const out = { tier: DEFAULT_FIRST_MATE_TIER, error: null };
  if (!file) return out;
  const r = readConfigFile(file);
  if (r.error) return { ...out, error: r.error };
  const fm = r.value.first_mate;
  if (!fm || typeof fm !== "object" || Array.isArray(fm)) return out;
  if (validStoredTier(fm.tier)) out.tier = fm.tier;
  return out;
}

// Re-reads the file, hands `mutate` a copy of `first_mate`, and writes back
// only that key. `{ ok: true }` / `{ error }`.
function writeFirstMateSettings(file, mutate) {
  if (!file) return { error: "First mate has no config file to save its tier in" };
  const r = readConfigFile(file);
  if (r.error) return { error: `not saved: ${r.error}` };
  const obj = { ...r.value };
  const cur = obj.first_mate && typeof obj.first_mate === "object" && !Array.isArray(obj.first_mate)
    ? { ...obj.first_mate } : {};
  obj.first_mate = mutate(cur);
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const tmp = `${file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(obj, null, 2) + "\n", { mode: 0o600 });
    fs.renameSync(tmp, file);
  } catch (e) {
    return { error: `not saved: ${e.message}` };
  }
  return { ok: true };
}

function setStandingTier(file, n, { resolveTierFn } = {}) {
  const bad = tierProblem(n, resolveTierFn);
  if (bad) return { error: bad };
  return writeFirstMateSettings(file, (fm) => ({ ...fm, tier: n }));
}

// The tier this turn runs at: `{ tier, model, effort, source }`, where source
// is always "standing". A tier the map cannot resolve falls back to the
// default tier; if even that fails, the pair is null and the caller uses its
// own default.
function resolveEffective(file, now, resolveTierFn = defaultResolveTier) {
  const s = readFirstMateSettings(file);
  let tier = s.tier;
  let pair = resolveTierFn(tier);
  if (!pair && tier !== DEFAULT_FIRST_MATE_TIER) {
    tier = DEFAULT_FIRST_MATE_TIER;
    pair = resolveTierFn(tier);
  }
  return {
    tier,
    model: pair ? pair.model : null,
    effort: pair ? pair.effort : null,
    source: "standing",
  };
}

module.exports = {
  DEFAULT_FIRST_MATE_TIER, FALLBACK_TIER, FABLE_LIMIT_RE,
  isFableLimit, tierProblem, readFirstMateSettings, writeFirstMateSettings,
  setStandingTier, resolveEffective,
};
