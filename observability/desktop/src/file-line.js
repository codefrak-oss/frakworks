// file-line.js — the normalizer for First mate's `File:` carrier line (issue
// #100), modelled on src/choices.js's `Options:` marker.
//
// The carrier is one trailing line, `File: <tier> | <ask>`: `<tier>` is the
// number (1-10, the engine's tier map) First mate proposes, `<ask>` is the rest of
// the line — the request as it should be filed. Same "sole content of the
// last non-empty line" rule `choices.js` uses, and the same reasons: a marker
// anywhere else, or more than one, is not the contract, so it produces no
// payload.
//
// The one place this deliberately does **not** copy `normalizeChoice`: that
// function strips every marker line from the displayed reply, payload or not,
// because a choice's "question" *is* the reply with the marker removed — the
// buttons speak for themselves. `File:`'s ask is not decoration on top of the
// reply, it is the content of the reply's last line, so stripping it would
// erase the very ask the card is about to show a tier selector for. Instead,
// a well-formed marker line is *replaced* by its own `<ask>` text: the line
// stays, the plumbing (`File: <tier> |`) does not.
"use strict";

const FILE_MARKER = /^File:[ \t]*(.*)$/;
const FILE_LINE_BODY = /^\s*(\d{1,2})\s*\|\s*([\s\S]+?)\s*$/;

const FILE_MIN_TIER = 1;
const FILE_MAX_TIER = 10;
const FILE_ASK_MAX = 2000;

/**
 * Read one reply. Returns `{ reply, file }`:
 *
 *   reply — what is displayed. A malformed or misplaced marker line is
 *           stripped, the same fallback `choices.js` uses. A well-formed one
 *           is replaced by its own ask text, so the ask still renders.
 *   file  — `{ tier, ask }` or null.
 */
function normalizeFileLine(reply) {
  const text = String(reply == null ? "" : reply);
  const lines = text.split("\n");
  const markerAt = [];
  let lastContentAt = -1;
  lines.forEach((line, i) => {
    if (FILE_MARKER.test(line)) markerAt.push(i);
    if (line.trim()) lastContentAt = i;
  });
  if (!markerAt.length) return { reply: text, file: null };

  const wellPlaced = markerAt.length === 1 && markerAt[0] === lastContentAt;
  if (!wellPlaced) {
    const kept = lines.filter((_l, i) => !markerAt.includes(i));
    return { reply: kept.join("\n").replace(/\s+$/, ""), file: null };
  }

  const body = FILE_MARKER.exec(lines[markerAt[0]])[1];
  const m = FILE_LINE_BODY.exec(body);
  if (!m) {
    const kept = lines.filter((_l, i) => !markerAt.includes(i));
    return { reply: kept.join("\n").replace(/\s+$/, ""), file: null };
  }
  const tier = Number(m[1]);
  const ask = m[2].trim();
  if (!Number.isInteger(tier) || tier < FILE_MIN_TIER || tier > FILE_MAX_TIER || !ask || ask.length > FILE_ASK_MAX) {
    const kept = lines.filter((_l, i) => !markerAt.includes(i));
    return { reply: kept.join("\n").replace(/\s+$/, ""), file: null };
  }

  // The marker line is replaced by the ask, not removed — the whole point of
  // this normalizer over `normalizeChoice`.
  const shown = [...lines];
  shown[markerAt[0]] = ask;
  const displayed = shown.join("\n").replace(/\s+$/, "");
  return { reply: displayed, file: { tier, ask } };
}

module.exports = { FILE_MARKER, FILE_MIN_TIER, FILE_MAX_TIER, FILE_ASK_MAX, normalizeFileLine };
