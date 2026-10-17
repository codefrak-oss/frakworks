// find.test.js — the find-in-page bar (#703): its state, the calls it makes on
// window.app, and its markup. No Electron runs here; the IPC channels are
// checked in test/navigation.test.js.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { FIND_CLOSED, findStep, countText, keyAction, FindBar } = require("../dist/find.cjs");

// Runs actions in order: the last state and every call made.
const run = (actions, from = FIND_CLOSED) => {
  let state = from;
  const calls = [];
  for (const a of actions) {
    const r = findStep(state, a);
    state = r.state;
    if (r.call) calls.push(r.call);
  }
  return { state, calls };
};

test("Cmd-F opens the bar and asks for focus; a second Cmd-F refocuses the same bar", () => {
  const once = run([{ type: "open" }]);
  assert.equal(once.state.open, true);
  assert.equal(once.state.focus, 1);
  assert.deepEqual(once.calls, [], "nothing to search yet");
  const twice = run([{ type: "open" }, { type: "text", text: "verbatim" }, { type: "open" }]);
  assert.equal(twice.state.open, true);
  assert.equal(twice.state.focus, 2, "focus (and select) again");
  assert.deepEqual(twice.calls, [["find", "verbatim", { forward: true, findNext: true }]], "no second search while open");
});

test("typing searches from the start, and an emptied field stops", () => {
  const { state, calls } = run([{ type: "open" }, { type: "text", text: "v" }, { type: "text", text: "ve" }, { type: "text", text: "" }]);
  assert.deepEqual(calls, [
    ["find", "v", { forward: true, findNext: true }],
    ["find", "ve", { forward: true, findNext: true }],
    ["stop"],
  ]);
  assert.equal(countText(state), "");
});

test("the count is found-in-page's activeMatchOrdinal of matches, and 0 of 0 when nothing matches", () => {
  const open = run([{ type: "open" }, { type: "text", text: "comment" }]).state;
  assert.equal(countText(open), "0 of 0", "before the first result");
  const found = run([{ type: "result", active: 2, matches: 5 }], open).state;
  assert.equal(countText(found), "2 of 5");
  // an update that carries only one of the two keeps the other
  assert.equal(countText(run([{ type: "result", matches: 6 }], found).state), "2 of 6");
  const none = run([{ type: "text", text: "zqxwvu" }, { type: "result", active: 0, matches: 0 }], found).state;
  assert.equal(countText(none), "0 of 0");
  // a result for an emptied field is not shown
  assert.equal(countText(run([{ type: "text", text: "" }, { type: "result", active: 1, matches: 1 }], found).state), "");
});

test("Enter and Shift-Enter step forward and back in the same search; with no text they do nothing", () => {
  const open = run([{ type: "open" }, { type: "text", text: "Smith" }, { type: "result", active: 1, matches: 3 }]).state;
  assert.deepEqual(run([{ type: "next" }, { type: "prev" }], open).calls, [
    ["find", "Smith", { forward: true, findNext: false }],
    ["find", "Smith", { forward: false, findNext: false }],
  ]);
  assert.deepEqual(run([{ type: "open" }, { type: "next" }, { type: "prev" }]).calls, []);
  assert.equal(keyAction({ key: "Enter", shiftKey: false }), "next");
  assert.equal(keyAction({ key: "Enter", shiftKey: true }), "prev");
  assert.equal(keyAction({ key: "Escape" }), "close");
  assert.equal(keyAction({ key: "a" }), null);
});

test("Escape closes and stops (clearing the highlights); the text stays, and the next Cmd-F searches it again", () => {
  const open = run([{ type: "open" }, { type: "text", text: "Smith" }, { type: "result", active: 1, matches: 3 }]).state;
  const closed = run([{ type: "close" }], open);
  assert.deepEqual(closed.calls, [["stop"]]);
  assert.equal(closed.state.open, false);
  assert.equal(closed.state.text, "Smith");
  assert.equal(countText(closed.state), "0 of 0");
  const reopened = run([{ type: "open" }], closed.state);
  assert.equal(reopened.state.open, true);
  assert.deepEqual(reopened.calls, [["find", "Smith", { forward: true, findNext: true }]]);
});

test("the bar draws an input, the count and previous/next/close; step buttons wait for a match", () => {
  const html = renderToStaticMarkup(React.createElement(FindBar, { api: null }));
  assert.match(html, /<div class="findBar" role="search"><input class="findInput" type="text" placeholder="Find in page"/);
  assert.match(html, /<span class="findCount"><\/span>/);
  assert.equal([...html.matchAll(/<button class="findBtn"[^>]*disabled=""/g)].length, 2, "previous and next");
  assert.match(html, /aria-label="Close"/);
});

test("the bar is its own view: find.html loads dist/find.js, which build:renderer builds", () => {
  const dir = path.join(__dirname, "..");
  const html = fs.readFileSync(path.join(dir, "src", "renderer", "find.html"), "utf8");
  assert.match(html, /<div id="find-root"><\/div>/);
  assert.match(html, /<script src="\.\.\/\.\.\/dist\/find\.js"><\/script>/);
  const pkg = JSON.parse(fs.readFileSync(path.join(dir, "package.json"), "utf8"));
  assert.match(pkg.scripts["build:renderer"], /esbuild src\/renderer\/find\.jsx --bundle --outfile=dist\/find\.js/);
  const main = fs.readFileSync(path.join(dir, "src", "main.js"), "utf8");
  assert.match(main, /new WebContentsView\(\{\s*webPreferences: \{\s*preload: path\.join\(__dirname, "preload\.js"\),\s*contextIsolation: true,\s*nodeIntegration: false,\s*sandbox: true,/);
  assert.match(main, /loadFile\(path\.join\(__dirname, "renderer", "find\.html"\)\)/);
});
