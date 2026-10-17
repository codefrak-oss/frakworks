// The repo switcher's choice (#577):
// src/repo-selection.js's first default, persistence, a vanished repo's
// notice and the repo:set check, without Electron or an engine.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const sel = require("../src/repo-selection");

test("the first default: the only target repo, else All repos", () => {
  assert.equal(sel.defaultSelection({ repos: ["o/a"] }), "o/a");
  assert.equal(sel.defaultSelection({ repos: ["o/a", "o/b"] }), null);
  assert.equal(sel.defaultSelection({ repos: null }), null);
  assert.equal(sel.defaultSelection({ repos: [] }), null);
});

test("the choice persists in a file; a missing or broken file means the first default", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "repo-sel-"));
  const file = path.join(dir, "sub", sel.FILE_NAME);
  assert.equal(sel.load(file), null);
  sel.save(file, "o/r");
  assert.deepEqual(sel.load(file), { repo: "o/r" });
  sel.save(file, null);
  assert.deepEqual(sel.load(file), { repo: null });
  fs.writeFileSync(file, "{nope");
  assert.equal(sel.load(file), null);
  fs.writeFileSync(file, JSON.stringify({ repo: "../../etc" }));
  assert.equal(sel.load(file), null);
});

test("resolveSelection: a persisted repo /repos no longer lists becomes All repos, with a notice", () => {
  const fresh = sel.resolveSelection({ persisted: null, repos: ["o/a"] });
  assert.deepEqual(fresh, { repo: "o/a", fresh: true, notice: null });
  assert.deepEqual(sel.resolveSelection({ persisted: { repo: "O/A" }, repos: ["o/a"] }),
    { repo: "o/a", fresh: false, notice: null });
  const gone = sel.resolveSelection({ persisted: { repo: "o/gone" }, repos: ["o/a"] });
  assert.equal(gone.repo, null);
  assert.match(gone.notice, /o\/gone is no longer a target repo; showing All repos/);
  // while /repos cannot be read, the choice stands
  assert.equal(sel.resolveSelection({ persisted: { repo: "o/gone" }, repos: null }).repo, "o/gone");
  assert.equal(sel.resolveSelection({ persisted: { repo: null }, repos: ["o/a"] }).repo, null,
    "a persisted All repos wins over the only target repo");
});

test("checkChoice takes All repos or a listed repo, and refuses anything else", () => {
  assert.deepEqual(sel.checkChoice(null, ["o/a"]), { repo: null });
  assert.deepEqual(sel.checkChoice("O/A", ["o/a"]), { repo: "o/a" });
  assert.match(sel.checkChoice("o/b", ["o/a"]).error, /not a target repo/);
  assert.match(sel.checkChoice("o/a", null).error, /could not be read/);
  assert.match(sel.checkChoice("x", ["o/a"]).error, /not an owner\/name repo/);
});

test("the window title is the repo, or All repos", () => {
  assert.equal(sel.titleFor("o/r"), "o/r");
  assert.equal(sel.titleFor(null), "All repos");
});

test("main opens a hiddenInset window titled by the selection, and no source reads an environment repo default", () => {
  const src = (f) => fs.readFileSync(path.join(__dirname, "..", "src", f), "utf8");
  const main = src("main.js");
  assert.match(main, /titleBarStyle: "hiddenInset"/);
  assert.match(main, /title: repoSelection\.titleFor\(selectedRepo\)/);
  for (const f of fs.readdirSync(path.join(__dirname, "..", "src")).filter((f) => /\.(js|ts)$/.test(f))) {
    assert.doesNotMatch(src(f), /DISPATCH[_]/, f);
  }
});
