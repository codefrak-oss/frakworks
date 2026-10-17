// First mate's tier setting (issue #231): src/first-mate-tier.js reads and
// writes `first_mate` in agent-army.config.json and resolves the tier a turn
// runs at. Resolves through src/engine-tiers.js, loaded with the engine's
// map as V42 seeds it (main loads it from GET /tiers).
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const tier = require("../src/first-mate-tier");
const { FirstMateManager } = require("../src/first-mate");
require("../src/engine-tiers").setTiers(require("./seeded-tiers").SEEDED_TIERS);

const tmpdir = () => fs.mkdtempSync(path.join(os.tmpdir(), "first-mate-tier-test-"));
const cfgFile = () => path.join(tmpdir(), "agent-army.config.json");
const read = (f) => JSON.parse(fs.readFileSync(f, "utf8"));

test("a missing file or key gives tier 8, fable low, standing", () => {
  const f = cfgFile();
  assert.deepEqual(tier.resolveEffective(f, 0),
    { tier: 8, model: "fable", effort: "low", source: "standing" });
  fs.writeFileSync(f, JSON.stringify({ tickets_url: "https://oracle.example/tickets/mcp" }));
  assert.equal(tier.resolveEffective(f, 0).tier, 8);
  assert.equal(tier.resolveEffective(null, 0).tier, 8);
});

test("resolveEffective always answers the standing tier", () => {
  const f = cfgFile();
  assert.equal(tier.setStandingTier(f, 3).ok, true);
  assert.deepEqual(tier.resolveEffective(f, 5_000_000),
    { tier: 3, model: "sonnet", effort: "medium", source: "standing" });
  assert.equal(tier.resolveEffective(f, 9_000_000).tier, 3);
});

test("a stale first_mate.override key left from before the strip's Override… control is ignored", () => {
  const f = cfgFile();
  fs.writeFileSync(f, JSON.stringify({ first_mate: { tier: 3, override: { tier: 9, until: 99_999_999_999 } } }));
  assert.deepEqual(tier.resolveEffective(f, 0),
    { tier: 3, model: "sonnet", effort: "medium", source: "standing" });
  assert.equal(tier.readFirstMateSettings(f).override, undefined);
});

test("every write keeps every other key in the file", () => {
  const f = cfgFile();
  const seed = {
    tickets_url: "https://oracle.example/tickets/mcp", issuer: "https://kc.example/realms/x", client_id: "dev-mac",
    something_new: { nested: [1, 2, 3] },
  };
  fs.writeFileSync(f, JSON.stringify(seed));
  const others = () => { const o = read(f); delete o.first_mate; return o; };
  assert.equal(tier.setStandingTier(f, 9).ok, true);
  assert.deepEqual(others(), seed);
  assert.deepEqual(read(f).first_mate, { tier: 9 });
});

test("a file that is not valid JSON is refused and left byte-identical", () => {
  const f = cfgFile();
  fs.writeFileSync(f, "{ not json");
  const before = fs.readFileSync(f);
  assert.ok(tier.setStandingTier(f, 5).error);
  assert.deepEqual(fs.readFileSync(f), before);
  assert.equal(tier.resolveEffective(f, 0).tier, 8);
});

test("tiers 0, 11, 2.5, NaN and strings are rejected with { error }", () => {
  const f = cfgFile();
  for (const bad of [0, 11, 2.5, NaN, "8", null, undefined]) {
    assert.ok(tier.setStandingTier(f, bad).error, String(bad));
  }
  assert.ok(!fs.existsSync(f));
});

test("isFableLimit matches the CLI's text case-insensitively", () => {
  assert.equal(tier.isFableLimit("You've reached your Fable limit. Switch to another model to continue."), true);
  assert.equal(tier.isFableLimit("YOU'VE REACHED YOUR FABLE LIMIT"), true);
  assert.equal(tier.isFableLimit("echo: hi"), false);
  assert.equal(tier.isFableLimit(undefined), false);
});

test("the standing tier survives a config reload: a new manager over the same file sees it", () => {
  const dir = tmpdir();
  const configFile = path.join(dir, "agent-army.config.json");
  const now = () => 7_000_000;
  const a = new FirstMateManager({ dir, configFile, now, command: "true" }).init();
  const r = a.setStandingTier(4);
  assert.equal(r.ok, true);
  const b = new FirstMateManager({ dir, configFile, now, command: "true" }).init();
  assert.deepEqual(b.snapshot().tier, { tier: 4, model: "opus", effort: "low", source: "standing" });
  assert.equal(b.tierState().standing, 4);
});
