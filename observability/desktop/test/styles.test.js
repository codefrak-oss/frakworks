"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

test("the tab bar uses the same sans-serif family as the fleet root", () => {
  const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
  const family = (selector) => {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const block = css.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`));
    assert.ok(block, `${selector} rule exists`);
    const declaration = block[1].match(/font-family:\s*([^;]+);/);
    assert.ok(declaration, `${selector} declares font-family`);
    return declaration[1].trim();
  };
  assert.equal(family(".tabs"), family(".fleet-root"));
  assert.match(family(".tabs"), /sans-serif$/);
});

test("First mate replies show at full height; only the transcript scrolls (#282, #285)", () => {
  const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
  const block = (selector) => {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const found = css.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`));
    assert.ok(found, `${selector} rule exists`);
    return found[1];
  };
  const replyText = css.match(/\.fmRow-assistant \.fmText\s*\{([^}]*)\}/);
  const decls = replyText ? replyText[1] : "";
  assert.doesNotMatch(decls, /max-height/, ".fmRow-assistant .fmText has no max-height");
  assert.doesNotMatch(decls, /overflow/, ".fmRow-assistant .fmText has no overflow");
  assert.doesNotMatch(css, /\.fmRow-assistant \.fmText::-webkit-scrollbar/, "no per-reply scrollbar rules");
  assert.match(block(".fmTranscript"), /overflow-y:\s*auto/, ".fmTranscript declares overflow-y: auto");
});

test("the ticket detail's pinned wrapper is sticky and opaque (#342)", () => {
  const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
  const found = css.match(/\.tdPinned\s*\{([^}]*)\}/);
  assert.ok(found, ".tdPinned rule exists");
  assert.match(found[1], /position:\s*sticky/);
  assert.match(found[1], /z-index:/);
  // An opaque hex, or the palette's solid --bg / --surface role (#2213).
  assert.match(found[1], /background:\s*(#|var\(--(bg|surface)\))/);
});

test("First mate's transcript, File ask and compose box use the terminal font, spaced a line apart (#366)", () => {
  const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
  const block = (selector) => {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const found = css.match(new RegExp(`(?:^|\\n)${escaped}\\s*\\{([^}]*)\\}`));
    assert.ok(found, `${selector} rule exists`);
    return found[1];
  };
  const strip = block(".firstMate");
  assert.match(strip, /--fm-mono:\s*ui-monospace, SFMono-Regular, Menlo, monospace;/, "fallback stack");
  assert.match(strip, /--fm-mono-size:\s*12px;/, "fallback size");
  assert.match(strip, /--fm-claude:\s*#d77757;/, "Claude accent");
  for (const sel of [".fmTranscript", ".fmFileAsk", ".fmCompose"]) {
    assert.match(block(sel), /font-family:\s*var\(--fm-mono\)/, `${sel} uses --fm-mono`);
    assert.match(block(sel), /font-size:\s*var\(--fm-mono-size\)/, `${sel} uses --fm-mono-size`);
  }
  assert.match(block(".fmTranscript"), /gap:\s*1lh/, "a blank line between turns");
  assert.match(block(".fmExchange"), /gap:\s*1lh/);
  assert.doesNotMatch(block(".fmExchange"), /border/, "no hairline separators");
  assert.doesNotMatch(block(".fmFileCard"), /border-radius/, "no rounded card");
  assert.match(block(".fmRow-assistant .fmMark"), /color:\s*var\(--fm-claude\)/);
  assert.match(block(".fmCompose"), /border:\s*1px solid var\(--fm-border\)/);
});

test("no comment in styles.css holds a */ of its own, which would end it early and leak the rest as CSS", () => {
  const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
  // Outside its comments a stylesheet has no /* or */ left; a stray one is the tail of a comment that closed early.
  const stray = css.replace(/\/\*[\s\S]*?\*\//g, "").match(/.*(?:\*\/|\/\*).*/);
  assert.equal(stray, null, `styles.css has a comment that ends early, leaving: ${stray && stray[0].trim()}`);
});

test("transcript tool lines are dimmer than prose (#1023)", () => {
  const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
  const found = css.match(/\.fleet-root \.tdTranscript \.tdToolLine\s*\{([^}]*)\}/);
  assert.ok(found, "rule exists");
  assert.match(found[1], /(opacity|color):/);
});

test("the title strip's empty width drags the window; only the repo switcher's controls are no-drag (#1687)", () => {
  const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
  const wrapper = css.match(/^\.repoSwitcher\s*\{([^}]*)\}/m);
  assert.ok(wrapper, ".repoSwitcher rule exists");
  assert.doesNotMatch(wrapper[1], /app-region:\s*no-drag/);
  const controls = css.match(/^\.repoSwitcherBtn,\s*\.repoMenu\s*\{([^}]*)\}/m);
  assert.ok(controls, ".repoSwitcherBtn, .repoMenu rule exists");
  assert.match(controls[1], /-webkit-app-region:\s*no-drag/);
});

test("rendered descriptions use the raw view's monospace face, option A (#1731)", () => {
  const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
  const block = (selector) => {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const found = css.match(new RegExp(`(?:^|\\n)${escaped}\\s*\\{([^}]*)\\}`));
    assert.ok(found, `${selector} rule exists`);
    return found[1];
  };
  const md = block(".fleet-root .tdDesc.md");
  assert.match(md, /font-family:\s*ui-monospace[^;]*monospace;/);
  assert.match(md, /font-size:\s*11\.5px;/);
  assert.match(md, /line-height:\s*1\.6;/);
  assert.match(md, /max-width:\s*72ch;/);
  assert.match(block(".fleet-root .tdDesc.md.clipped"), /max-height:\s*19\.2em;/);
  assert.match(block(".fleet-root .tdDesc.md pre"), /overflow-x:\s*auto;/);
  assert.doesNotMatch(block(".fleet-root .tdDesc.md blockquote"), /opacity/);
  const raw = block(".fleet-root .tdDesc");
  assert.match(raw, /white-space:\s*pre-wrap;/);
  assert.match(raw, /line-height:\s*1\.45;/);
});

test("the urgent HITLP popup is the task view in the modal: app blurred, red edge, capped height; the old card's rules are gone", () => {
  const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
  const rule = (sel) => {
    const m = css.match(new RegExp(sel.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\s*\\{([^}]*)\\}"));
    assert.ok(m, `${sel} rule exists`);
    return m[1];
  };
  assert.match(rule(".uoBackdrop:has(> .uoPanel.hpPanel)"), /backdrop-filter:\s*blur\(/);
  const panel = rule(".uoPanel.hpPanel");
  assert.match(panel, /border:\s*2px solid #e5604f/);
  assert.match(panel, /max-height:\s*86vh/);
  assert.match(panel, /overflow-y:\s*auto/);
  assert.doesNotMatch(css, /\.hp(Card|Tile|Title|Question|Scope|Num|Box|Link|Answered|Err|Foot|Small|Hint|Snooze|ScriptWrap)\b/);
});
