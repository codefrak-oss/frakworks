"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
const rgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
const luminance = (hex) => {
  const [r, g, b] = rgb(hex).map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const ratio = (a, b) => { const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };

test("every palette's text-like colour is at least 4.5:1 against its bg and surface (#2213)", () => {
  const { PALETTES } = require("../dist/palettes.cjs");
  assert.equal(PALETTES.length, 9, "Current plus schemes A–H");
  for (const p of PALETTES) {
    for (const role of ["text", "muted", "accent", "ok", "warn", "err", "info"]) {
      for (const under of ["bg", "surface"]) {
        const r = ratio(p.colors[role], p.colors[under]);
        assert.ok(r >= 4.5, `${p.name}: ${role} ${p.colors[role]} on ${under} ${p.colors[under]} is ${r.toFixed(2)}:1`);
      }
    }
  }
});

test("styles.css's :root roles are the Current palette, and the severity ramp reads them (#2213)", () => {
  const { PALETTES, ROLES } = require("../dist/palettes.cjs");
  const root = css.match(/:root\s*\{([^}]*)\}/);
  assert.ok(root, ":root rule exists");
  for (const r of ROLES) {
    const m = root[1].match(new RegExp(`--${r}:\\s*([^;]+);`));
    assert.ok(m, `:root defines --${r}`);
    assert.equal(m[1].trim().toLowerCase(), PALETTES[0].colors[r]);
  }
  assert.match(css, /--sev-good:\s*var\(--ok\);/);
  assert.match(css, /--sev-warn:\s*var\(--warn\);/);
  assert.match(css, /--sev-crit:\s*var\(--err\);/);
});

test("applyPalette sets every role on the root and remembers the choice (#2213)", () => {
  const { applyPalette, loadPaletteId, ROLES, paletteById } = require("../dist/palettes.cjs");
  const store = {};
  global.localStorage = { getItem: (k) => store[k] ?? null, setItem: (k, v) => { store[k] = v; } };
  const set = {};
  const root = { style: { setProperty: (k, v) => { set[k] = v; } } };
  assert.equal(applyPalette("synthwave", root), "synthwave");
  for (const r of ROLES) assert.equal(set[`--${r}`], paletteById("synthwave").colors[r]);
  assert.equal(loadPaletteId(), "synthwave");
  assert.equal(applyPalette("no-such", root), "current", "an unknown id falls to Current");
  delete global.localStorage;
});

test("Config has a Palettes page (#2213)", () => {
  const { VIEWS } = require("../dist/index.cjs");
  assert.ok(VIEWS.some((v) => v.id === "palettes" && v.branch === "Config"));
});
