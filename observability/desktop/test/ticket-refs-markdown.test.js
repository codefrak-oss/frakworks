// Issue #100124: a markdown ticket link in a First mate reply renders as #N alone.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { splitTicketRefs } = require("../dist/ticket-refs.cjs");

test("[#n](frakworks://ticket/...) splits into the link alone, no brackets or URL", () => {
  const parts = splitTicketRefs("Filed as [#100123](frakworks://ticket/codefrak-oss/other/100123).");
  assert.deepEqual(parts, ["Filed as ", { text: "#100123", repo: "codefrak-oss/other", number: 100123 }, "."]);
});

test("bare #n still links to the framework repo", () => {
  assert.deepEqual(splitTicketRefs("see #5"), ["see ", { text: "#5", repo: "codefrak-oss/frakworks", number: 5 }]);
});
