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
