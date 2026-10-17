// qr.js — a QR code as SVG for the web version's Pair a phone page
// (observability/desktop/README.md, "Pair a phone"), from qrcode-generator
// (MIT, no dependencies): error level M, byte mode, the smallest version that
// holds the text.
"use strict";

const qrcode = require("qrcode-generator");

/** The pairing payload the iPhone app scans: `{"url","token"}` as JSON. */
const pairingPayload = (url, token) => JSON.stringify({ url, token });

/**
 * `text` as a QR code: `{ size, path }`, the module count with the quiet zone
 * and one SVG path (`M x y h1v1h-1z` per dark module) in module units.
 */
function qrSvg(text) {
  const qr = qrcode(0, "M");
  qr.addData(String(text), "Byte");
  qr.make();
  const n = qr.getModuleCount();
  const quiet = 4;
  let path = "";
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) if (qr.isDark(r, c)) path += `M${c + quiet} ${r + quiet}h1v1h-1z`;
  }
  return { size: n + 2 * quiet, modules: n, path, isDark: (r, c) => qr.isDark(r, c) };
}

module.exports = { qrSvg, pairingPayload };
