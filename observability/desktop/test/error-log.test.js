// error-log.test.js — the persisted error log (#985).
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { createErrorLog } = require("../src/error-log");

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "error-log-"));
const read = (f) => fs.readFileSync(f, "utf8").split("\n").filter((l) => l).map((l) => JSON.parse(l));

test("73 appends keep the last 72: the first is gone, the 73rd is last", () => {
  const file = path.join(tmp(), "errors.log");
  const log = createErrorLog({ file, cap: 72 });
  for (let i = 1; i <= 73; i++) log.append({ source: "main", message: `e${i}`, stack: "s", context: "[app]" });
  const lines = read(file);
  assert.equal(lines.length, 72);
  assert.ok(!lines.some((l) => l.message === "e1"));
  assert.equal(lines[0].message, "e2");
  assert.equal(lines[71].message, "e73");
  assert.deepEqual(Object.keys(lines[71]), ["at", "source", "message", "stack", "context"]);
  assert.match(lines[71].at, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/);
});

test("a missing stack is written as null", () => {
  const file = path.join(tmp(), "errors.log");
  createErrorLog({ file }).append({ source: "renderer", message: "boom" });
  const [e] = read(file);
  assert.equal(e.stack, null);
  assert.equal(e.context, null);
  assert.equal(e.source, "renderer");
});

test("the file and its directory are created on the first append", () => {
  const file = path.join(tmp(), "a", "b", "errors.log");
  createErrorLog({ file }).append({ source: "main", error: new Error("x") });
  const [e] = read(file);
  assert.equal(e.message, "x");
  assert.match(e.stack, /Error: x/);
});
