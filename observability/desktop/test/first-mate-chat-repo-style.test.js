// The chat's repo row (#100123): sits inside .fmHeader, so it must undo its uppercase/600/letter-spacing and not wrap.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
const rule = (sel) => {
  const m = css.match(new RegExp("(^|\\n)\\" + sel + " \\{([^}]*)\\}"));
  assert.ok(m, `no rule for ${sel}`);
  return m[2];
};

test("the row resets the header's uppercase, weight and letter-spacing", () => {
  const r = rule(".fmChatRepoRow");
  assert.match(r, /text-transform: none/);
  assert.match(r, /letter-spacing: normal/);
  assert.match(r, /font-weight: normal/);
});

test("the label is 12px, muted, sentence case and on one line", () => {
  const r = rule(".fmChatRepoLabel");
  assert.match(r, /white-space: nowrap/);
  assert.match(r, /text-transform: none/);
  assert.match(r, /font-size: 12px/);
  assert.match(r, /color: var\(--muted\)/);
});

test("the last used tag is an 11px pill on one line, and the chip wraps it rather than clipping", () => {
  const r = rule(".fmChatRepoSticky");
  assert.match(r, /white-space: nowrap/);
  assert.match(r, /text-transform: none/);
  assert.match(r, /font-size: 11px/);
  assert.match(rule(".fmChatRepo"), /flex-wrap: wrap/);
});
