// app-menu.test.js — the Edit menu that lets Ctrl-V and Cmd-V reach First
// mate's input (issue #371).
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { appMenuTemplate } = require("../src/app-menu");

test("the app menu keeps an Edit menu with paste on every platform, and Ctrl+V pastes on macOS too", () => {
  for (const platform of ["linux", "win32"]) {
    const edit = appMenuTemplate(platform).find((m) => m.label === "Edit");
    assert.ok(edit, platform);
    // the items Electron's editMenu role has there
    assert.deepEqual(edit.submenu.filter((i) => i.role).map((i) => i.role),
      ["undo", "redo", "cut", "copy", "paste", "delete", "selectAll"], platform);
  }
  const mac = appMenuTemplate("darwin");
  const edit = mac.find((m) => m.label === "Edit");
  assert.ok(edit, "macOS has an Edit menu");
  const pastes = edit.submenu.filter((i) => i.role === "paste");
  assert.ok(pastes.some((i) => !i.accelerator), "the standard Cmd+V paste");
  assert.ok(pastes.some((i) => i.accelerator === "Ctrl+V" && i.acceleratorWorksWhenHidden), "Ctrl+V as well");
  const main = fs.readFileSync(path.join(__dirname, "..", "src", "main.js"), "utf8");
  assert.match(main, /Menu\.setApplicationMenu\(Menu\.buildFromTemplate\(appMenuTemplate\(process\.platform, \{/);
});

test("Edit > Find is CmdOrCtrl+F on every platform and calls main's onFind (#703)", () => {
  for (const platform of ["darwin", "linux", "win32"]) {
    const calls = [];
    const edit = appMenuTemplate(platform, { onFind: (item, win) => calls.push([item, win]) }).find((m) => m.label === "Edit");
    const finds = edit.submenu.filter((i) => i.label === "Find");
    assert.equal(finds.length, 1, platform);
    assert.equal(finds[0].accelerator, "CmdOrCtrl+F", platform);
    finds[0].click("item", "win");
    assert.deepEqual(calls, [["item", "win"]], platform);
    // no onFind (the tests' own calls): the click is a no-op, not a throw
    appMenuTemplate(platform).find((m) => m.label === "Edit").submenu.find((i) => i.label === "Find").click();
  }
  const main = fs.readFileSync(path.join(__dirname, "..", "src", "main.js"), "utf8");
  assert.match(main, /onFind: \(_item, bw\) => openFind\(bw/);
});

test("Help > Show error log is on every platform and calls main's onShowErrorLog (#985)", () => {
  for (const platform of ["darwin", "linux", "win32"]) {
    let shown = 0;
    const help = appMenuTemplate(platform, { onShowErrorLog: () => shown++ }).find((m) => m.role === "help");
    assert.ok(help, platform);
    const item = help.submenu.find((i) => i.label === "Show error log");
    assert.ok(item, platform);
    item.click();
    assert.equal(shown, 1, platform);
  }
  const main = fs.readFileSync(path.join(__dirname, "..", "src", "main.js"), "utf8");
  assert.match(main, /onShowErrorLog: \(\) => \{ errorLog\.ensure\(\); shell\.showItemInFolder\(errorLog\.file\); \}/);
});
