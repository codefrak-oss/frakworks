// iterm-font.js — First mate's terminal font (issue #366): the `Normal Font`
// of the user's default iTerm2 profile, read in main from the
// `com.googlecode.iterm2` defaults domain and handed to the renderer over the
// firstMate bridge (`firstMate:font`). The renderer never shells out.
//
// iTerm2 keeps fonts per profile, inside the `New Bookmarks` array; the
// top-level `Default Bookmark Guid` names the default profile. `Normal Font`
// reads "<PostScriptName> <size>", e.g. "MesloLGS-NF-Regular 13".
//
// Anything that goes wrong — not macOS, iTerm2 never run, the key missing,
// a value that does not parse — answers FALLBACK: the CSS stack in
// styles.css (`--fm-mono`) at DEFAULT_SIZE. Never an error.
"use strict";
const { execFile } = require("node:child_process");

const DOMAIN = "com.googlecode.iterm2";
const FALLBACK_STACK = "ui-monospace, SFMono-Regular, Menlo, monospace";
const DEFAULT_SIZE = 12;
const FALLBACK = Object.freeze({ family: null, size: DEFAULT_SIZE, source: "fallback" });

// PostScript names are printable ASCII with no spaces or CSS/string
// delimiters; anything else is refused rather than escaped into a stylesheet.
const POSTSCRIPT_NAME = /^[A-Za-z0-9][A-Za-z0-9._+-]*$/;

/** "MesloLGS-NF-Regular 13" -> { family: "MesloLGS-NF-Regular", size: 13 }; null if it does not parse. */
function parseFontSpec(value) {
  if (typeof value !== "string") return null;
  const m = value.trim().match(/^(\S+)\s+(\d+(?:\.\d+)?)$/);
  if (!m || !POSTSCRIPT_NAME.test(m[1])) return null;
  const size = Number(m[2]);
  if (!Number.isFinite(size) || size < 4 || size > 72) return null;
  return { family: m[1], size };
}

const XML_ENTITIES = { lt: "<", gt: ">", amp: "&", quot: "\"", apos: "'" };
const unescapeXml = (s) => s.replace(/&(#x[0-9a-fA-F]+|#\d+|lt|gt|amp|quot|apos);/g, (_, e) => {
  if (e[0] !== "#") return XML_ENTITIES[e];
  return String.fromCodePoint(e[1] === "x" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10));
});

/**
 * A minimal XML property list reader: dict, array, string, key, integer,
 * real, true, false, date and data (the last two as strings). Enough for
 * `defaults export <domain> -`; throws on anything it does not recognise.
 */
function parsePlist(xml) {
  const tokens = String(xml).replace(/<\?xml[^>]*\?>|<!DOCTYPE[^>]*>|<!--[\s\S]*?-->/g, "")
    .match(/<[^>]+>|[^<]+/g) || [];
  let i = 0;
  const skipSpace = () => { while (i < tokens.length && tokens[i][0] !== "<" && !tokens[i].trim()) i += 1; };
  const textUntil = (close) => {
    let text = "";
    while (i < tokens.length && tokens[i] !== close) { text += tokens[i]; i += 1; }
    if (tokens[i] !== close) throw new Error(`plist: missing ${close}`);
    i += 1;
    return unescapeXml(text);
  };
  const value = () => {
    skipSpace();
    const tag = tokens[i++];
    if (tag === undefined) throw new Error("plist: unexpected end");
    const name = (tag.match(/^<\s*([A-Za-z]+)/) || [])[1];
    const selfClosing = /\/>$/.test(tag);
    switch (name) {
      case "dict": {
        const out = {};
        if (selfClosing) return out;
        for (;;) {
          skipSpace();
          if (tokens[i] === "</dict>") { i += 1; return out; }
          if (tokens[i] !== "<key>") throw new Error(`plist: expected <key>, got ${tokens[i]}`);
          i += 1;
          const key = textUntil("</key>");
          out[key] = value();
        }
      }
      case "array": {
        const out = [];
        if (selfClosing) return out;
        for (;;) {
          skipSpace();
          if (tokens[i] === "</array>") { i += 1; return out; }
          out.push(value());
        }
      }
      case "string": case "date": case "data":
        return selfClosing ? "" : textUntil(`</${name}>`);
      case "integer": case "real":
        return Number(textUntil(`</${name}>`).trim());
      case "true": return true;
      case "false": return false;
      case "plist": {
        const v = value();
        skipSpace();
        return v;
      }
      default: throw new Error(`plist: unexpected ${tag}`);
    }
  };
  return value();
}

/** The profile whose Guid is `Default Bookmark Guid`, else the first profile; null if there are none. */
function pickProfile(prefs) {
  const profiles = prefs && Array.isArray(prefs["New Bookmarks"]) ? prefs["New Bookmarks"].filter((p) => p && typeof p === "object") : [];
  if (!profiles.length) return null;
  const guid = prefs["Default Bookmark Guid"];
  return profiles.find((p) => guid && p.Guid === guid) || profiles[0];
}

/** The parsed defaults domain -> `{ family, size, source: "iterm2" }`, or FALLBACK. */
function fontFromPrefs(prefs) {
  const profile = pickProfile(prefs);
  const font = profile && parseFontSpec(profile["Normal Font"]);
  return font ? { ...font, source: "iterm2" } : FALLBACK;
}

const run = (cmd, args, input) => new Promise((resolve, reject) => {
  const child = execFile(cmd, args, { timeout: 5000, maxBuffer: 32 * 1024 * 1024, encoding: "buffer" },
    (err, stdout) => (err ? reject(err) : resolve(stdout)));
  if (input) child.stdin.end(input); else child.stdin.end();
});

// `defaults export <domain> -` prints the domain as an XML plist; should a
// macOS release print it binary, `plutil` (also in the base system) turns
// it back into XML.
async function execDefaults() {
  const out = await run("defaults", ["export", DOMAIN, "-"]);
  if (out.subarray(0, 6).toString("latin1") !== "bplist") return out.toString("utf8");
  return (await run("plutil", ["-convert", "xml1", "-o", "-", "-"], out)).toString("utf8");
}

/**
 * Read the font. `platform` and `exec` (resolving to the exported plist's
 * text) are injectable for tests; every failure resolves to FALLBACK.
 */
async function readItermFont({ platform = process.platform, exec = execDefaults } = {}) {
  if (platform !== "darwin") return FALLBACK;
  try {
    return fontFromPrefs(parsePlist(await exec()));
  } catch {
    return FALLBACK;
  }
}

module.exports = { parseFontSpec, parsePlist, pickProfile, fontFromPrefs, readItermFont, FALLBACK, FALLBACK_STACK, DEFAULT_SIZE };
