// web-ipad.test.js — the iPad (README, "iPad"): the web version's Add to Home
// Screen files, and its touch and narrow rules kept out of the desktop's way.
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const WEB = path.join(__dirname, "..", "src", "web");
const read = (f) => fs.readFileSync(path.join(WEB, f), "utf8");

test("the manifest starts and scopes at /web/, standalone, and every icon it names exists", () => {
  const m = JSON.parse(read("manifest.webmanifest"));
  assert.equal(m.start_url, "/web/");
  assert.equal(m.scope, "/web/");
  assert.equal(m.display, "standalone");
  assert.ok(m.icons.length >= 2);
  for (const i of m.icons) {
    const buf = fs.readFileSync(path.join(WEB, i.src));
    assert.equal(buf.subarray(1, 4).toString(), "PNG", i.src);
    const [w, h] = i.sizes.split("x").map(Number);
    assert.deepEqual([buf.readUInt32BE(16), buf.readUInt32BE(20)], [w, h], i.src);
  }
});

test("index.html links the manifest and the apple-touch-icon, and the build copies them", () => {
  const html = read("index.html");
  assert.match(html, /<link rel="manifest" href="\.\/manifest\.webmanifest">/);
  const icon = html.match(/<link rel="apple-touch-icon" href="\.\/([^"]+)">/);
  assert.ok(icon && fs.existsSync(path.join(WEB, icon[1])));
  assert.match(html, /apple-mobile-web-app-capable" content="yes"/);
  assert.match(html, /viewport-fit=cover/);
  const build = read("build.js");
  assert.match(build, /manifest\.webmanifest/);
  assert.match(build, /cpSync\(path\.join\(__dirname, "icons"\)/);
});

test("every rule after the iPad marker in web-overrides.css sits inside a (hover: none) or max-width query", () => {
  const css = read("web-overrides.css");
  const at = css.indexOf("iPad (README");
  assert.ok(at > 0);
  const rest = css.slice(css.indexOf("*/", at) + 2).replace(/\/\*[\s\S]*?\*\//g, "");
  let depth = 0;
  let i = 0;
  while (i < rest.length) {
    const open = rest.indexOf("{", i);
    if (open < 0) break;
    const head = rest.slice(i, open).trim();
    if (depth === 0) assert.match(head, /^@media \((hover: none|max-width: \d+px)\)$/, head);
    depth = 1;
    let j = open + 1;
    while (depth > 0) { if (rest[j] === "{") depth++; else if (rest[j] === "}") depth--; j++; }
    i = j;
    depth = 0;
  }
});
