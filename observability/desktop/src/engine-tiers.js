// engine-tiers.js — main's copy of the engine's tier map (`GET /tiers`,
// engine/README.md "Tier resolution"), for the places that need a tier's
// (model, effort) without waiting on a request: First mate's model and effort
// (src/first-mate-tier.js, src/claude-session.js), its handoff line, and the
// `Tier: <n> (<model> <effort>)` line the File button writes (CLAUDE.md
// "Filing tickets").
//
// The map lives in the engine's database and is edited on Config > frakworks >
// Tiers (#1099; it was bots/tiers.toml until then). Main loads it at startup,
// on the fleet stream's `hello` and on each `setting` frame named `tiers`, so
// an edit applies to First mate's next turn. Before the first load answers,
// `resolveTier` is null for every tier: callers already treat that as a tier
// that cannot be resolved, a state they show rather than a crash.
"use strict";

const { fetchTiers } = require("./engine-data");

// Map<tier:number, {model, effort}>, or null before the first load answered.
let cache = null;

/** Replaces the copy with `rows` (`[{ tier, model, effort }]`); exported for tests. */
function setTiers(rows) {
  cache = new Map((Array.isArray(rows) ? rows : []).map((r) => [r.tier, { model: r.model, effort: r.effort }]));
}

/**
 * Reads the map from the engine and keeps it. Resolves to what fetchTiers
 * resolved to ({ tiers } or { error }); on an error the last copy stays.
 */
async function loadTiers(opts = {}, fetchFn = fetchTiers) {
  const r = await fetchFn(opts);
  if (r && Array.isArray(r.tiers)) setTiers(r.tiers);
  return r;
}

/** The rows as the last load had them, sorted 1-10, or null before one answered. */
function cachedTiers() {
  return cache ? [...cache].sort((a, b) => a[0] - b[0]).map(([tier, v]) => ({ tier, ...v })) : null;
}

// { model, effort } for a tier 1-10, or null for any other value or before the
// map was loaded. Never throws.
function resolveTier(n) {
  const tier = Number(n);
  if (!Number.isInteger(tier) || tier < 1 || tier > 10 || !cache) return null;
  return cache.get(tier) || null;
}

module.exports = { loadTiers, setTiers, cachedTiers, resolveTier };
