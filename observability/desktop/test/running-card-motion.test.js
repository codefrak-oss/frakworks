// The running timeline card and the Update engine overlay idle-animate for as
// long as a run is live (#1110): their keyframes must move only what the
// compositor animates (transform, opacity), and no backdrop-filter may sit
// over that animation, or the window repaints or re-blurs every frame.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8")
  .replace(/\/\*[\s\S]*?\*\//g, "");
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

// The body of `@keyframes <name> { ... }`, nested braces included.
const keyframes = (name) => {
  const start = css.search(new RegExp(`@keyframes\\s+${esc(name)}\\s*\\{`));
  assert.ok(start >= 0, `@keyframes ${name} exists`);
  let i = css.indexOf("{", start) + 1;
  const from = i;
  for (let depth = 1; depth > 0; i++) depth += css[i] === "{" ? 1 : css[i] === "}" ? -1 : 0;
  return css.slice(from, i - 1);
};
// The declarations of the first rule whose selector is exactly `selector`.
const rule = (selector) => {
  const found = css.match(new RegExp(`(?:^|[}\\n])\\s*${esc(selector)}\\s*\\{([^}]*)\\}`));
  assert.ok(found, `${selector} rule exists`);
  return found[1];
};

test("the running card's and the overlay's keyframes animate transform and opacity only (#1110)", () => {
  for (const name of ["tlSweep", "tlStripe", "tlPulse", "uoSpin"]) {
    const props = [...keyframes(name).matchAll(/([a-z-]+)\s*:/g)].map((m) => m[1]);
    assert.ok(props.length > 0, `${name} animates something`);
    for (const p of props) assert.ok(p === "transform" || p === "opacity", `${name} animates ${p}`);
  }
  const inKeyframes = [...css.matchAll(/@keyframes\s+([\w-]+)/g)].map((m) => keyframes(m[1])).join("\n");
  assert.doesNotMatch(inKeyframes, /background-position/, "no keyframe moves a background");
});

test("the sweep is a band slid across the card, not a moving background (#1110)", () => {
  const sweep = rule(".fleet-root .tlSweep");
  assert.match(sweep, /animation:\s*tlSweep\b/);
  assert.match(sweep, /width:\s*40%/, "the band is the old image's 40% of the card");
  assert.match(sweep, /var\(--tl-accent\)/, "in the card's accent colour");
  assert.doesNotMatch(sweep, /background-(size|position|repeat)/);
  assert.match(rule(".fleet-root .tlCard.tlActive"), /overflow:\s*hidden/, "the card clips the band");
});

test("no backdrop-filter is painted over the animating card or the overlay's spinner (#1110)", () => {
  assert.doesNotMatch(rule(".fleet-root"), /backdrop-filter/, ".fleet-root is not blurred");
  assert.doesNotMatch(rule(".uoBackdrop"), /backdrop-filter/, "the overlay's scrim is plain colour");
});

test("under prefers-reduced-motion the running card and the overlay's mark stay still", () => {
  const reduced = [...css.matchAll(/@media \(prefers-reduced-motion: reduce\) \{([\s\S]*?)\n\}/g)].map((m) => m[1]).join("\n");
  assert.match(reduced, /\.fleet-root \.tlCard\.tlActive::before, \.fleet-root \.tlSweep \{ animation: none; display: none; \}/);
  assert.match(reduced, /\.fleet-root \.tlPulse \{ animation: none; opacity: 1; \}/);
  assert.match(reduced, /\.fleet-root \.tlCard\.tlActive \{ border-style: solid; border-left-width: 5px;/);
  assert.match(reduced, /\.uo-running \.uoMark \{ animation: none; \}/);
});
