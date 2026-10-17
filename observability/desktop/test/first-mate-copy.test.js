// First mate's Copy button (issue #808) is gone from the strip.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const { FirstMate } = require("../dist/first-mate.cjs");

const history = [{ role: "user", text: "what is #12?", at: "t1" }];
const snap = (over = {}) => ({ history: [], filed: [], ...over });

test("no Copy button on the live strip, the History list or a past conversation", () => {
  for (const props of [{ snapshot: snap({ history }), onSend: () => {} },
    { snapshot: snap({ history }), historyOpen: true, historyList: [] },
    { snapshot: snap(), viewingConversation: { id: 1, messages: history } }]) {
    assert.doesNotMatch(renderToStaticMarkup(React.createElement(FirstMate, props)), /fmCopyButton|>Copy</);
  }
});
