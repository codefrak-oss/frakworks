// pitch-frame.js — how the Pitch view (src/renderer/pitch.jsx) frames a
// proposal's pitch page (#563). The page is written by a Smith and committed
// on a branch, so it is drawn as untrusted: in an <iframe sandbox> with no
// allow-scripts (nothing in it runs) and no allow-same-origin (it is its own
// opaque origin: no storage, no reach into the app), through `srcdoc`, with a
// CSP of its own that lets it reach nothing but its inline styles and data:
// images. The app's own CSP (src/renderer/index.html) is left as it is: a
// srcdoc frame is about:srcdoc, which `frame-src` does not govern, and it
// inherits the app's policy on top of this one, so both apply.
//
// Pure, so the frame's attributes and the CSP are testable without a DOM.

/** The frame's own policy: nothing from the network, inline styles and data: images only. */
const PITCH_CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:";

const CSP_META = `<meta http-equiv="Content-Security-Policy" content="${PITCH_CSP}">`;

// The page draws in both themes by its own `prefers-color-scheme` rules; this
// tells the frame's canvas it may be dark as well as light, and the frame's
// `color-scheme` style (PITCH_FRAME_STYLE) makes the query follow the app's.
const COLOR_META = `<meta name="color-scheme" content="light dark">`;
const DARK_META = `<meta name="color-scheme" content="dark">`;

/**
 * The attributes the frame is drawn with: `sandbox` present and empty (every
 * restriction on: no scripts, no same origin, no forms, no popups, no top
 * navigation) and no referrer.
 */
const PITCH_FRAME_ATTRS = Object.freeze({ sandbox: "", referrerPolicy: "no-referrer" });

/** The frame's style: `color-scheme` on the frame is what `prefers-color-scheme` inside it follows. */
const PITCH_FRAME_STYLE = Object.freeze({ colorScheme: "light dark" });

// A byte-order mark, whitespace and comments, then a doctype: kept first, so
// the page keeps its standards mode.
const LEAD = /^﻿?(?:\s|<!--[\s\S]*?-->)*<!doctype[^>]*>/i;

/**
 * `html` with the CSP meta tag as the first thing after its doctype (or the
 * first thing at all): the parser opens <head> for it, so it is the head's
 * first element and binds everything after it, even for a page with no
 * <head>, or with an <img> before its <head>, whose own head would come too
 * late. A later <head> tag of the page's own is folded into it. `scheme`
 * "dark" tells the canvas it is dark only (the ticket detail's Pitch section).
 */
function pitchSrcdoc(html, { scheme = "light dark" } = {}) {
  const text = String(html == null ? "" : html);
  const lead = (text.match(LEAD) || [""])[0];
  return lead + CSP_META + (scheme === "dark" ? DARK_META : COLOR_META) + text.slice(lead.length);
}

module.exports = { PITCH_CSP, CSP_META, PITCH_FRAME_ATTRS, PITCH_FRAME_STYLE, pitchSrcdoc };
