// Draggable splitters between explorer, work area and First mate (#361).
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const S = require("../dist/splitter.cjs");

test("side widths clamp to the minimum and leave .work its minimum", () => {
  assert.equal(S.clampWidth(10, 1200, 260), S.MIN_SIDE_WIDTH);
  assert.equal(S.clampWidth(0, 1200, 260), S.MIN_SIDE_WIDTH);
  const max = 1200 - 260 - 2 * S.SPLITTER_WIDTH - S.MIN_WORK_WIDTH;
  assert.equal(S.clampWidth(5000, 1200, 260), max);
  assert.equal(S.clampWidth(300, 1200, 260), 300);
  assert.equal(S.clampWidth(NaN, 1200, 260), S.DEFAULT_WIDTH);
});

test("dragging the right splitter left widens the strip", () => {
  assert.equal(S.dragWidth(260, -40, "right"), 300);
  assert.equal(S.dragWidth(260, -40, "left"), 220);
  assert.equal(S.dragWidth(260, 40, "left"), 300);
});

test("stored widths are read, and fall back to 260 when missing or unreadable", () => {
  assert.equal(S.parseWidth("320"), 320);
  assert.equal(S.parseWidth(null), 260);
  assert.equal(S.parseWidth("garbage"), 260);
  assert.equal(S.parseWidth("-5"), 260);
  const saved = global.localStorage;
  global.localStorage = { getItem: () => { throw new Error("denied"); } };
  assert.equal(S.readWidth(S.EXPLORER_KEY), 260);
  global.localStorage = { getItem: (k) => (k === S.FIRST_MATE_KEY ? "300" : null) };
  assert.equal(S.readWidth(S.FIRST_MATE_KEY), 300);
  assert.equal(S.readWidth(S.EXPLORER_KEY), 260);
  delete global.localStorage;
  assert.equal(S.readWidth(S.EXPLORER_KEY), 260);
  if (saved) global.localStorage = saved;
});

test("a splitter renders as a vertical separator", () => {
  const html = renderToStaticMarkup(React.createElement(S.Splitter, { side: "left", width: 260, onDrag: () => {} }));
  assert.match(html, /role="separator"/);
  assert.match(html, /aria-orientation="vertical"/);
});

test("the shell puts a splitter on each side of the work area", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "src", "renderer", "index.jsx"), "utf8");
  assert.match(src, /<Explorer[\s\S]*?\{splitter\("left"\)\}\s*<main className="work">/);
  // the right one goes with First mate, which the web version (no window.firstMate) leaves out
  assert.match(src, /<\/main>[\s\S]*?\{hasFirstMate && splitter\("right"\)\}\s*\{hasFirstMate && <FirstMateStrip/);
});

// The repo file view's markdown split (#675): the same logic turned to heights.
test("the top panel's height clamps so neither panel goes under its minimum; none is an even split", () => {
  const total = 600;
  const room = total - S.SPLITTER_HEIGHT;
  assert.equal(S.clampHeight(null, total), room / 2);
  assert.equal(S.clampHeight(NaN, total), room / 2);
  assert.equal(S.clampHeight(10, total), S.MIN_PANEL_HEIGHT);
  assert.equal(S.clampHeight(5000, total), room - S.MIN_PANEL_HEIGHT);
  assert.equal(S.clampHeight(250, total), 250);
  assert.equal(S.clampHeight(250, 100), S.MIN_PANEL_HEIGHT, "a split too short for both: the top keeps its minimum");
});

test("dragging the horizontal bar down grows the top panel", () => {
  assert.equal(S.dragHeight(300, 40), 340);
  assert.equal(S.dragHeight(300, -40), 260);
});

test("the stored height is read and saved under its own key; missing or unreadable is the even split", () => {
  assert.equal(S.MARKDOWN_SPLIT_KEY, "repoFile.markdownSplitHeight");
  assert.notEqual(S.MARKDOWN_SPLIT_KEY, S.EXPLORER_KEY);
  assert.equal(S.parseHeight("320"), 320);
  assert.equal(S.parseHeight(null), null);
  assert.equal(S.parseHeight("garbage"), null);
  assert.equal(S.parseHeight("-5"), null);
  const saved = global.localStorage;
  const store = new Map();
  global.localStorage = { getItem: (k) => (store.has(k) ? store.get(k) : null), setItem: (k, v) => store.set(k, v), removeItem: (k) => store.delete(k) };
  S.saveHeight(S.MARKDOWN_SPLIT_KEY, 333);
  assert.equal(store.get(S.MARKDOWN_SPLIT_KEY), "333");
  assert.equal(S.readHeight(S.MARKDOWN_SPLIT_KEY), 333);
  S.saveHeight(S.MARKDOWN_SPLIT_KEY, null);
  assert.equal(store.has(S.MARKDOWN_SPLIT_KEY), false, "a reset forgets it");
  assert.equal(S.readHeight(S.MARKDOWN_SPLIT_KEY), null);
  global.localStorage = { getItem: () => { throw new Error("denied"); }, setItem: () => { throw new Error("denied"); } };
  assert.equal(S.readHeight(S.MARKDOWN_SPLIT_KEY), null);
  S.saveHeight(S.MARKDOWN_SPLIT_KEY, 1);
  delete global.localStorage;
  assert.equal(S.readHeight(S.MARKDOWN_SPLIT_KEY), null);
  if (saved) global.localStorage = saved;
});

test("the horizontal bar renders as a horizontal separator", () => {
  const html = renderToStaticMarkup(React.createElement(S.HSplitter, { height: 300, onDrag: () => {} }));
  assert.match(html, /class="hsplitter" role="separator" aria-orientation="horizontal" aria-valuenow="300"/);
});
