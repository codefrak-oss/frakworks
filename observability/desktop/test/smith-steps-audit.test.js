// Every Smith-invoking step (#918): each `call-*` activity in engine/bpmn whose
// calledElement is reusable-smith-run is listed in the README's "Where the live
// transcript goes (#867)", so a new one cannot land without revisiting it.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const BPMN = path.join(__dirname, "..", "..", "..", "engine", "bpmn");
const readme = fs.readFileSync(path.join(__dirname, "..", "README.md"), "utf8");

test("every reusable-smith-run call is in the README's transcript audit", () => {
  const start = readme.indexOf("**Where the live transcript goes (#867).**");
  assert.ok(start >= 0);
  const section = readme.slice(start, readme.indexOf("**The ask card**", start));
  const ids = new Set();
  for (const f of fs.readdirSync(BPMN).filter((f) => f.endsWith(".bpmn"))) {
    const xml = fs.readFileSync(path.join(BPMN, f), "utf8");
    for (const m of xml.matchAll(/<bpmn:callActivity\b[^>]*>/g)) {
      const id = (m[0].match(/\bid="([^"]+)"/) || [])[1];
      if (/calledElement="reusable-smith-run"/.test(m[0]) && id && id.startsWith("call-")) ids.add(id);
    }
  }
  assert.ok(ids.size >= 13, `found ${ids.size}`);
  for (const id of ids) assert.match(section, new RegExp("`" + id + "`"), `${id} missing from the README audit`);
});
