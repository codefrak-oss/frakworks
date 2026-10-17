// app-menu.js — the application menu (issue #371, with #368's image paste).
//
// Set explicitly so the Edit menu, and with it the paste role, is always
// there: on macOS Cmd-V only reaches a text field through a menu item with
// that accelerator, and First mate's input takes pasted images through the
// ordinary paste event. The ticket also wants Ctrl-V to paste on macOS, where
// Cocoa does not bind it; a hidden second Paste item carries that accelerator.
// Elsewhere Ctrl-V is already the paste role's own accelerator.
//
// Edit > Find (#703) opens the find bar: `onFind(menuItem, browserWindow)`,
// main's. A menu accelerator is taken before a focused text field sees the
// key, so Cmd-F in First mate's input opens find too. Electron's `editMenu`
// role cannot take an extra item, so the other platforms spell out the same
// items it has, plus Find.
"use strict";

// Help > Show error log (#985) reveals <userData>/errors.log:
// `onShowErrorLog()`, main's.
function appMenuTemplate(platform, { onFind, onShowErrorLog } = {}) {
  const mac = platform === "darwin";
  const find = { label: "Find", accelerator: "CmdOrCtrl+F", click: (item, win) => { if (onFind) onFind(item, win); } };
  const edit = mac
    ? { label: "Edit", submenu: [
      { role: "undo" }, { role: "redo" }, { type: "separator" },
      { role: "cut" }, { role: "copy" }, { role: "paste" },
      { role: "paste", accelerator: "Ctrl+V", visible: false, acceleratorWorksWhenHidden: true },
      { role: "pasteAndMatchStyle" }, { role: "delete" }, { role: "selectAll" },
      { type: "separator" }, find,
    ] }
    : { label: "Edit", submenu: [
      { role: "undo" }, { role: "redo" }, { type: "separator" },
      { role: "cut" }, { role: "copy" }, { role: "paste" }, { role: "delete" },
      { type: "separator" }, { role: "selectAll" }, { type: "separator" }, find,
    ] };
  return [
    ...(mac ? [{ role: "appMenu" }] : []),
    { role: "fileMenu" },
    edit,
    { role: "viewMenu" },
    { role: "windowMenu" },
    { role: "help", submenu: [
      { label: "Show error log", click: () => { if (onShowErrorLog) onShowErrorLog(); } },
    ] },
  ];
}

module.exports = { appMenuTemplate };
