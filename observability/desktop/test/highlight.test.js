// The repo file view's highlighting (#675): the extension → language map, the
// highlight.js languages it needs all registered, escaped output, and the
// github-dark / github token colours in styles.css.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { LANGUAGE_BY_EXTENSION, LANGUAGE_BY_NAME, LANGUAGES, languageFor } = require("../src/highlight-languages");
const { highlightHtml, highlightFile, unregistered, REGISTERED } = require("../dist/highlight.cjs");

test("every registered extension maps to its language; an unknown one is plaintext", () => {
  const expected = {
    js: "javascript", mjs: "javascript", cjs: "javascript", jsx: "javascript",
    ts: "typescript", mts: "typescript", cts: "typescript", tsx: "typescript",
    py: "python", kt: "kotlin", kts: "kotlin", java: "java",
    sh: "bash", bash: "bash", zsh: "bash", yaml: "yaml", yml: "yaml",
    toml: "ini", ini: "ini", json: "json", css: "css",
    html: "xml", htm: "xml", xml: "xml", svg: "xml", bpmn: "xml",
    md: "markdown", markdown: "markdown", dockerfile: "dockerfile", sql: "sql", txt: "plaintext",
  };
  assert.deepEqual({ ...LANGUAGE_BY_EXTENSION }, expected);
  for (const [ext, lang] of Object.entries(expected)) assert.equal(languageFor(`dir/file.${ext}`), lang, ext);
  assert.equal(languageFor("engine/src/main/resources/ticket.BPMN"), "xml", "case does not matter");
  assert.equal(languageFor("Dockerfile"), "dockerfile");
  assert.equal(languageFor("x/Containerfile"), "dockerfile");
  assert.deepEqual(Object.keys(LANGUAGE_BY_NAME).sort(), ["containerfile", "dockerfile"]);
  for (const p of ["a.unknown", "Makefile", "LICENSE", ".gitignore", "a.", "", null]) assert.equal(languageFor(p), "plaintext", String(p));
});

test("every language the map names is registered, and only those", () => {
  assert.deepEqual(unregistered(), []);
  assert.deepEqual(Object.keys(REGISTERED).sort(), [...LANGUAGES]);
});

test("the output is highlight.js's escaped HTML: markup in a file is text", () => {
  const html = highlightHtml("<script>alert('x' && 1)</script>", "xml");
  assert.doesNotMatch(html, /<script/);
  assert.match(html, /&lt;<span class="hljs-name">script<\/span>&gt;/);
  assert.equal(highlightHtml("<b>&</b>", "plaintext"), "&lt;b&gt;&amp;&lt;/b&gt;");
  assert.equal(highlightHtml("<b>", "cobol"), "&lt;b&gt;", "a language that is not ours is plaintext");
  const { language, html: py } = highlightFile("a/b.py", "def f(): pass");
  assert.equal(language, "python");
  assert.match(py, /<span class="hljs-keyword">def<\/span>/);
  for (const [p, text, cls] of [["a.yaml", "a: 1", "hljs-attr"], ["a.toml", "[x]\nk = \"v\"", "hljs-section"],
    ["a.json", "{\"k\": true}", "hljs-attr"], ["a.sh", "echo \"$HOME\"", "hljs-built_in"], ["a.bpmn", "<bpmn:process id=\"p\"/>", "hljs-name"],
    ["a.js", "const a = 1;", "hljs-keyword"]]) {
    assert.match(highlightFile(p, text).html, new RegExp(`class="${cls}"`), p);
  }
});

const L = (h) => {
  const c = [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
};
const ratio = (a, b) => { const [x, y] = [L(a), L(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };

test("both themes' token colours hold 4.5:1 against their pane background; light follows prefers-color-scheme", () => {
  const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
  const start = css.indexOf("/* The file view's highlighting (#675");
  const end = css.indexOf("/* The markdown split (#675");
  assert.ok(start > 0 && end > start);
  const block = css.slice(start, end);
  const light = block.slice(block.indexOf("@media (prefers-color-scheme: light)"));
  const dark = block.slice(0, block.indexOf("@media (prefers-color-scheme: light)"));
  for (const [name, part, bg] of [["dark", dark, "#0d1117"], ["light", light, "#ffffff"]]) {
    assert.match(part, new RegExp(`\\.repoCode \\{ background: ${bg};`), `${name} background`);
    // Each rule's own background (addition, deletion) if it has one, else the pane's.
    for (const rule of part.match(/[^{}]*\{[^{}]*color: #[0-9a-f]{6}[^{}]*\}/g)) {
      const fg = /(?:^|[\s;{])color: (#[0-9a-f]{6})/.exec(rule)[1];
      const own = /background(?:-color)?: (#[0-9a-f]{6})/.exec(rule);
      const r = ratio(fg, own ? own[1] : bg);
      assert.ok(r >= 4.5, `${name}: ${fg} is ${r.toFixed(2)}:1 in ${rule.trim().slice(0, 60)}`);
    }
  }
});
