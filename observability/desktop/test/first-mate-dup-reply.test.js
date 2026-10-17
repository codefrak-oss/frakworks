// Regression for #100108: a late assistant line must not redraw a finished reply.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs"); const os = require("node:os"); const path = require("node:path");
const { FirstMateManager } = require("../src/first-mate");
test("an assistant line after the turn's result leaves the reply in `streaming` too", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fm-"));
  const m = new FirstMateManager({ dir, command: "false" });
  const reply = "Filed as [#100107](frakworks://ticket/codefrak-oss/frakworks/100107); the refiner will fill it in.";
  const gen = m.session.session_generation;
  // The reply arrives once, then the same assistant line again (a re-delivered frame) with no turn pending.
  m.rt.pending = null;
  m.session.history.push({ role: "assistant", text: reply, at: 1 });
  m._onEvent(gen, { type: "assistant", message: { content: [{ type: "text", text: reply }] } });
  const s = m.snapshot();
  const shown = s.history.filter((h) => h.text === reply).length + (s.streaming === reply ? 1 : 0);
  assert.equal(shown, 1, `reply drawn ${shown} times: once as a history row (LinkedText) and once as the raw fmStreaming bubble`);
});

test("the streaming bubble renders ticket links through LinkedText, as history rows do", () => {
  const src = require("node:fs").readFileSync(require("node:path").join(__dirname, "../src/renderer/first-mate.jsx"), "utf8");
  const bubble = src.split("\n").find((l) => l.includes("fmStreaming"));
  assert.match(bubble, /<LinkedText text=\{s\.streaming\}/);
});
