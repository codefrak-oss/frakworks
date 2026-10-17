// The engine's tier map as V42__tier.sql seeds it (engine/src/main/resources/
// db/migration), as main's engine:tiers hands it over (src/engine-data.js
// fetchTiers: the model's short name). Tests that resolve a tier load these
// into src/engine-tiers.js with `setTiers`, as main does from GET /tiers.
"use strict";

const SEEDED_TIERS = [
  { tier: 1, model: "haiku", effort: "low" },
  { tier: 2, model: "sonnet", effort: "low" },
  { tier: 3, model: "sonnet", effort: "medium" },
  { tier: 4, model: "opus", effort: "low" },
  { tier: 5, model: "opus", effort: "medium" },
  { tier: 6, model: "opus", effort: "medium" },
  { tier: 7, model: "opus", effort: "medium" },
  { tier: 8, model: "fable", effort: "low" },
  { tier: 9, model: "fable", effort: "medium" },
  { tier: 10, model: "fable", effort: "high" },
];

module.exports = { SEEDED_TIERS };
