// screenshot.js — "Let First mate screenshot this app" (#100105): the
// setting, the pane-to-rect choice and the image's encoding for the desktop
// channel (src/desktop-channel.js, engine/docs/desktop-channel.md).
//
// The setting is this machine's permission to be photographed: a small JSON
// file under userData, off when the file is missing or unreadable, never in
// agent-army.config.json. Everything here but load/save is pure, so
// test/screenshot.test.js runs it without Electron.
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { IMAGE_MAX_BYTES: MAX_BYTES } = require("./first-mate-images");

const FILE_NAME = "screenshot-setting.json";
const SETTING_LABEL = "Let First mate screenshot this app";
const PANES = ["left", "middle", "right"];
// The pane's root element in the renderer (src/renderer/index.jsx, the .shell's children).
const PANE_SELECTORS = { left: "nav.explorer", middle: "main.work", right: "aside.firstMate" };

/** Whether the user allowed it: true only for a file that says `{ "allowed": true }`. */
function load(file) {
  try { return JSON.parse(fs.readFileSync(file, "utf8")).allowed === true; } catch { return false; }
}

function save(file, allowed) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify({ allowed: allowed === true }) + "\n");
  return allowed === true;
}

/**
 * The rect `capturePage` takes for `pane`, from `bounds` (each pane's
 * getBoundingClientRect, `{ left: {x, y, width, height}, ... }`): whole pixels,
 * clipped to the window (`view`, `{ width, height }`). Null pane is the whole
 * window (`{ rect: null }`); an unknown pane, or one not on screen, is `{ error }`.
 */
function paneRect(pane, bounds, view) {
  if (pane == null) return { rect: null };
  if (!PANES.includes(pane)) return { error: `no pane ${pane}: left, middle or right` };
  const b = bounds && bounds[pane];
  if (!b) return { error: `the ${pane} pane is not on screen` };
  const x = Math.max(0, Math.floor(b.x));
  const y = Math.max(0, Math.floor(b.y));
  const right = Math.min(view && view.width ? view.width : Infinity, Math.ceil(b.x + b.width));
  const bottom = Math.min(view && view.height ? view.height : Infinity, Math.ceil(b.y + b.height));
  if (!(right > x && bottom > y)) return { error: `the ${pane} pane is not on screen` };
  return { rect: { x, y, width: right - x, height: bottom - y } };
}

/** The renderer script that answers every pane's bounds, for webContents.executeJavaScript. */
function boundsScript() {
  return `(() => { const s = ${JSON.stringify(PANE_SELECTORS)}; const o = {}; for (const [k, q] of Object.entries(s)) {` +
    ` const e = document.querySelector(q); if (e) { const r = e.getBoundingClientRect(); o[k] = { x: r.x, y: r.y, width: r.width, height: r.height }; } }` +
    ` return { bounds: o, view: { width: window.innerWidth, height: window.innerHeight } }; })()`;
}

/**
 * The image to send: the PNG while it is within the cap (first-mate-images'
 * 5 MiB), else the JPEG at falling quality, else `{ error }`. `png` and `jpeg(q)`
 * answer Buffers (a NativeImage's toPNG/toJPEG).
 */
function encode({ png, jpeg }) {
  const p = png();
  if (p.length <= MAX_BYTES) return { data: p, mimeType: "image/png" };
  for (const q of [90, 75, 60]) {
    const j = jpeg(q);
    if (j.length <= MAX_BYTES) return { data: j, mimeType: "image/jpeg" };
  }
  return { error: "the capture is over 5 MiB even as a JPEG" };
}

module.exports = { FILE_NAME, SETTING_LABEL, PANES, PANE_SELECTORS, load, save, paneRect, boundsScript, encode };
