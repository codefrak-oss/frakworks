// A ticket selected in the explorer's tree whose proposal waits on a decision
// opens the Proposal view, as the Needs you row does (#748).
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { resolveSelection } = require("../dist/index.cjs");

const rows = [
  { id: "o/r#731", repo: "o/r", number: 731, decision: { question: "Accept?" } },
  { id: "o/r#732", repo: "o/r", number: 732, decision: null },
];

test("a tree ticket whose proposal waits on a decision resolves to the proposal", () => {
  assert.deepEqual(resolveSelection({ kind: "ticket", id: "o/r#731" }, rows, null), { kind: "proposal", id: "o/r#731" });
  assert.deepEqual(resolveSelection({ kind: "ticket", id: 731 }, rows, "o/r"), { kind: "proposal", id: "o/r#731" });
});

test("a tree ticket with no proposal row, or no waiting decision, stays a ticket", () => {
  assert.deepEqual(resolveSelection({ kind: "ticket", id: "o/r#732" }, rows, null), { kind: "ticket", id: "o/r#732" });
  assert.deepEqual(resolveSelection({ kind: "ticket", id: "o/r#9" }, rows, null), { kind: "ticket", id: "o/r#9" });
  assert.deepEqual(resolveSelection({ kind: "needs" }, rows, null), { kind: "needs" });
});
