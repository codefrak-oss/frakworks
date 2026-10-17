// The confirm-mode design deck (#100114, src/renderer/design-deck.jsx): read
// through fleet.designDeck, drawn in the pitch frame under "Proposal", and
// nothing for a ticket without one.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { loadDesignDeck, DECK_HEADING } = require("../dist/design-deck.cjs");
const { TicketPitchView } = require("../dist/pitch.cjs");
const { fetchDesignDeck } = require("../src/engine-data");

test("loadDesignDeck reads through fleet.designDeck; a missing deck or a failure is null", async () => {
  const calls = [];
  const fleet = { designDeck: async (...a) => { calls.push(a); return { html: "<html>deck</html>" }; } };
  assert.equal(await loadDesignDeck({ repo: "o/r", number: "7", fleet }), "<html>deck</html>");
  assert.deepEqual(calls, [["o", "r", 7]]);
  assert.equal(await loadDesignDeck({ repo: "o/r", number: 7, fleet: { designDeck: async () => ({ error: "x" }) } }), null);
  assert.equal(await loadDesignDeck({ repo: "o/r", number: 7, fleet: { designDeck: async () => { throw new Error("ipc"); } } }), null);
  assert.equal(await loadDesignDeck({ repo: "o/r", number: 7, fleet: {} }), null);
});

test("the deck draws in the sandboxed pitch frame under its own heading", () => {
  const html = renderToStaticMarkup(React.createElement(TicketPitchView, { html: "<p>x</p>", heading: DECK_HEADING }));
  assert.match(html, /Proposal/);
  assert.match(html, /<iframe[^>]*sandbox="allow-popups"/);
});

test("fetchDesignDeck refuses a bad ticket before asking the engine", async () => {
  assert.match((await fetchDesignDeck("o", "r", -1)).error, /not a ticket number/);
});

// #100119: Close ✕ and Escape close the full-window deck/pitch.
const fs = require("node:fs");
const path = require("node:path");
const { PitchOverlay, bindPitchEscape } = require("../dist/pitch.cjs");

const fakeWindow = (active) => {
  const ls = {};
  const focused = [];
  const btn = { focus: () => focused.push("close") };
  return {
    focused,
    addEventListener: (t, f) => { ls[t] = f; },
    removeEventListener: (t, f) => { if (ls[t] === f) delete ls[t]; },
    fire: (t, e) => ls[t] && ls[t](e),
    ls,
    setTimeout: (f) => f(),
    document: { activeElement: active, querySelector: () => btn },
  };
};

test("Escape on the window closes the overlay; unbinding stops it", () => {
  const w = fakeWindow(null);
  let closed = 0;
  const unbind = bindPitchEscape(w, () => closed++);
  w.fire("keydown", { key: "a" });
  w.fire("keydown", { key: "Escape" });
  assert.equal(closed, 1);
  unbind();
  assert.deepEqual(Object.keys(w.ls), []);
});

test("focus in the overlay's frame comes back to Close, so Escape is heard again", () => {
  const frame = { tagName: "IFRAME", closest: (s) => (s === ".pitchOverlay" ? {} : null) };
  const w = fakeWindow(frame);
  bindPitchEscape(w, () => {});
  w.fire("blur");
  assert.deepEqual(w.focused, ["close"]);
  const other = fakeWindow({ tagName: "IFRAME", closest: () => null });
  bindPitchEscape(other, () => {});
  other.fire("blur");
  assert.deepEqual(other.focused, []);
});

test("the overlay's Close calls onClose, and the overlay is not a window drag region", () => {
  let closed = 0;
  const el = PitchOverlay({ doc: "<p>x</p>", title: DECK_HEADING, onClose: () => closed++ });
  const bar = el.props.children[0];
  bar.props.children[1].props.onClick();
  assert.equal(closed, 1);
  const css = fs.readFileSync(path.join(__dirname, "../src/renderer/styles.css"), "utf8");
  assert.match(css, /\.pitchOverlay \{[^}]*-webkit-app-region: no-drag/);
});
