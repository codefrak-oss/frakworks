// app-link.test.js — frakworks://ticket/<owner>/<name>/<n>, the link that
// opens one ticket in the app from outside it.
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { parseAppLink } = require("../src/app-link");

const read = (...p) => fs.readFileSync(path.join(__dirname, "..", ...p), "utf8");

test("a ticket link names its repo and number, and nothing else is a link", () => {
  assert.deepEqual(parseAppLink("frakworks://ticket/codefrak-oss/frakworks/870"), { repo: "codefrak-oss/frakworks", number: 870 });
  assert.deepEqual(parseAppLink(" FRAKWORKS://ticket/acme/my.repo_x/7/ "), { repo: "acme/my.repo_x", number: 7 });
  for (const bad of [null, undefined, "", "frakworks://ticket/acme/widgets", "frakworks://ticket/acme/widgets/0",
    "frakworks://ticket/acme/widgets/7/extra", "frakworks://ticket/acme/widgets/7?x=1", "frakworks://ticket/acme/wid gets/7",
    "frakworks://issue/acme/widgets/7", "https://github.com/acme/widgets/issues/7",
    "frakworks://ticket/acme/widgets/99999999999999999999"]) {
    assert.equal(parseAppLink(bad), null, String(bad));
  }
});

test("the packaged app claims the scheme, and main hands the renderer the ticket, never the URL", () => {
  const builder = require("../electron-builder.js");
  assert.deepEqual(builder.protocols, [{ name: "frakworks", schemes: ["frakworks"] }]);
  const main = read("src", "main.js");
  assert.match(main, /app\.on\("open-url", \(e, url\) => \{\n {2}e\.preventDefault\(\);\n {2}const ticket = parseAppLink\(url\);/);
  assert.match(main, /ipcMain\.handle\("app:take-link", \(\) => \{ const link = pendingLink; pendingLink = null; return link; \}\);/);
  assert.match(main, /win\.webContents\.send\("app:link"\);/, "the event carries no payload");
  const preload = read("src", "preload.js");
  assert.match(preload, /takeLink: \(\) => ipcRenderer\.invoke\("app:take-link"\)/);
  assert.match(preload, /ipcRenderer\.on\("app:link", h\)/);
  const renderer = read("src", "renderer", "index.jsx");
  assert.match(renderer, /const linked = await window\.app\.takeLink\(\);\n {6}if \(linked\) \{ setIssueSel\(null\); setTicket\(linked\); \}/);
});
