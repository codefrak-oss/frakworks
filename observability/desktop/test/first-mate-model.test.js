// Config > frakworks > First mate model (issue #240, part 2 of #226; a
// drop-down since #616): the standing-tier picker. Drawing only, renderToStaticMarkup like test/accounts.test.js; the
// component holds no hooks, so its element tree can be walked to call the select's
// onChange the way a pick would.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const { FirstMateModel, tierLabel, DEFAULT_TIER } = require("../dist/first-mate-model.cjs");

const TIERS = [
  [1, "haiku", "low"], [2, "haiku", "medium"], [3, "sonnet", "low"], [4, "sonnet", "medium"],
  [5, "opus", "medium"], [6, "opus", "high"], [7, "opus", "max"], [8, "fable", "low"],
  [9, "fable", "medium"], [10, "fable", "high"],
].map(([tier, model, effort]) => ({ tier, model, effort }));

const draw = (props) => renderToStaticMarkup(React.createElement(FirstMateModel, { tiers: TIERS, onSetTier: () => {}, ...props }));

// Every element in a tree, depth first.
const walk = (el, out = []) => {
  if (!el || typeof el !== "object") return out;
  if (Array.isArray(el)) { for (const c of el) walk(c, out); return out; }
  out.push(el);
  if (el.props) walk(el.props.children, out);
  return out;
};

test("tierLabel is '<n> · <model> <effort>'", () => {
  assert.equal(tierLabel({ tier: 5, model: "opus", effort: "medium" }), "5 · opus medium");
});

test("the page is headed frakworks, with one setting, First mate model, and its hint", () => {
  const html = draw({ state: { standing: 8 } });
  assert.match(html, /<h1>frakworks<\/h1>/);
  assert.equal([...html.matchAll(/class="acSetting"/g)].length, 1);
  assert.match(html, /class="acName">First mate model</);
  assert.match(html, /The tier First mate runs at\./);
  assert.doesNotMatch(html, /override/i);
});

test("the picker is one select listing tiers 1-10 from engine:tiers, each as '<n> · <model> <effort>', and no radios", () => {
  const html = draw({ state: { standing: 8 } });
  assert.equal([...html.matchAll(/<select/g)].length, 1);
  assert.match(html, /<select class="acInput" aria-label="first mate tier"/);
  const labels = [...html.matchAll(/<option[^>]*>([^<]+)</g)].map((m) => m[1]);
  assert.equal(labels.length, 10);
  assert.equal(labels[4], "5 · opus medium");
  assert.equal(labels[7], "8 · fable low");
  assert.doesNotMatch(html, /type="radio"/);
});

test("the saved standing tier is the selected option", () => {
  const html = draw({ state: { standing: 5 } });
  assert.match(html, /<option value="5" selected="">5 · opus medium/);
  assert.equal([...html.matchAll(/selected=""/g)].length, 1);
});

test("with no first_mate.tier, tier 8 is selected", () => {
  assert.equal(DEFAULT_TIER, 8);
  for (const state of [null, { standing: null }]) {
    assert.match(draw({ state }), /<option value="8" selected="">8 · fable low/);
  }
});

test("choosing an option saves that tier as a number, through onSetTier, and the select is disabled while saving", () => {
  const saved = [];
  const tree = FirstMateModel({ tiers: TIERS, state: { standing: 8 }, onSetTier: (n) => saved.push(n) });
  const selects = walk(tree).filter((e) => e.type === "select");
  assert.equal(selects.length, 1);
  selects[0].props.onChange({ target: { value: "5" } });
  assert.deepEqual(saved, [5]);
  assert.equal(typeof saved[0], "number");
  assert.match(draw({ state: { standing: 8 }, busy: true }), /<select[^>]*disabled=""/);
});

test("a tiers read that failed, a config read that failed, and a refused save are shown; a refused save leaves the saved tier selected", () => {
  const html = draw({ tiers: { error: "engine unreachable at http://engine" }, state: null, error: "pick a tier 1-10" });
  assert.match(html, /could not load the tiers: engine unreachable at http:\/\/engine/);
  assert.match(html, /not saved: pick a tier 1-10/);
  assert.doesNotMatch(html, /<select/);
  assert.match(draw({ state: { standing: 8, error: "bad json" } }), /could not read the config: bad json/);
  assert.match(draw({ state: { standing: 3 }, error: "nope" }), /<option value="3" selected="">/);
});

test("first-mate-model.jsx draws only and never names a model or effort to send", () => {
  const s = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "first-mate-model.jsx"), "utf8")
    .replace(/^\s*\/\/.*$/gm, ""); // the header comment names the bridge it is fed from
  assert.doesNotMatch(s, /window\./);
  assert.doesNotMatch(s, /setTimeout|setInterval|\bfetch\(|\brequire\(/);
  assert.match(s, /onSetTier\(Number\(e\.target\.value\)\)/);
});
