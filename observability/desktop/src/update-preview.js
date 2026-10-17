// update-preview.js — the update preview modal's view model (#100091): what a
// desktop update or an engine update brings, before the user confirms it.
// Pure; main builds it (`app:update-preview`, `engine:update-preview`) and
// src/renderer/update-preview.jsx draws it, as the mockups under
// docs/mockups/update-preview/ show.
//
// The change list is `{ changes }` or `{ error }` as
// src/engine-update-changes.js's `listChanges` answers it: one entry per PR,
// each with the tickets its squash subject closes and their titles from the
// engine's work-item store (GET /repos/{owner}/{name}/changes). Every PR
// naming tickets gives one row per ticket; a PR naming none gives a `PR #n`
// row. A missing list is one line saying why: the update never waits on it.
"use strict";

const { liveRunsPhrase, sessionsPhrase, buildLabel } = require("./engine-update");

// Rows past this many collapse into one "… and N more" line.
const MAX_ROWS = 40;
// How long main waits for the list before the modal opens without it.
const PREVIEW_MS = 4000;

/** The header's version pair: `v0.1.412 → v0.1.418`, or `sha-76600fb → sha-a1b2c3d (3 commits behind)`. */
function versionHeader({ kind, running, incoming, behind }) {
  if (kind === "desktop") {
    const v = (x) => (x && x.version ? `v${x.version}` : "?");
    return { running: v(running), incoming: v(incoming), runningLabel: "running", incomingLabel: "incoming", behind: "" };
  }
  return {
    running: buildLabel(running), incoming: buildLabel(incoming), runningLabel: "running", incomingLabel: "available",
    behind: behind == null ? "" : `(${behind} commit${behind === 1 ? "" : "s"} behind)`,
  };
}

/** The rows, newest first: `{ kind: "ticket", n, url, title }` (title null: unavailable) or `{ kind: "pr", pr, url, title }`. */
function rowsFrom(changes) {
  const rows = [];
  for (const c of changes || []) {
    if (c.tickets && c.tickets.length) {
      for (const t of c.tickets) rows.push({ kind: "ticket", n: t.n, url: t.url, title: t.title || null });
    } else {
      rows.push({ kind: "pr", pr: c.pr, url: c.prUrl, title: c.title });
    }
  }
  return rows;
}

/** The amber line for a list that is missing: `{ error }`, or null when there is a list. */
function missingLine(list) {
  if (!list) return "The change list is missing: it was not read.";
  if (list.error) return `The change list is missing: ${list.error}.`.replace(/\.\.$/, ".");
  return null;
}

/** The empty-range line; null when the list has rows or is missing. */
function emptyLine(list) {
  if (!list || list.error || (list.changes && list.changes.length)) return null;
  return "No changes between the two builds (same commit, rebuilt image).";
}

/** The sentence over the buttons. */
function footer({ kind, running, incoming, remote }) {
  if (kind === "desktop") {
    return `Update installs v${(incoming && incoming.version) || "?"} and relaunches the app. Cancel keeps v${(running && running.version) || "?"} running.`;
  }
  if (remote) return "The engine restarts itself onto :main; it is down for about 45 s. Cancel leaves it running.";
  return "Update pulls :main and recreates the engine container; a failed pull leaves the running engine as it is. Cancel leaves it running.";
}

/**
 * The modal's whole model: `{ kind, title, header, lines, rows, more, count,
 * missing, empty, footer }`. `lines` are the engine kind's live-run and
 * session sentences (empty for the desktop kind).
 */
function previewModel({ kind, running, incoming, behind = null, list, liveRuns, liveRunsError, sessions, remote = false }) {
  const all = list && !list.error ? rowsFrom(list.changes) : [];
  return {
    kind,
    title: kind === "desktop" ? "Update the desktop app?" : "Update the engine?",
    header: versionHeader({ kind, running, incoming, behind }),
    lines: kind === "engine" ? [liveRunsPhrase(liveRuns, liveRunsError), sessionsPhrase(sessions)] : [],
    rows: all.slice(0, MAX_ROWS),
    more: Math.max(0, all.length - MAX_ROWS),
    count: list && !list.error ? (list.changes || []).length : 0,
    missing: missingLine(list),
    empty: emptyLine(list),
    footer: footer({ kind, running, incoming, remote }),
  };
}

/** `promise`, or `{ error }` once `ms` passed: the list never holds the modal back. */
function withCap(promise, ms = PREVIEW_MS, setTimer = setTimeout) {
  return Promise.race([
    Promise.resolve(promise).catch((e) => ({ error: (e && e.message) || String(e) })),
    new Promise((resolve) => setTimer(() => resolve({ error: `the engine did not answer within ${Math.round(ms / 1000)} s` }), ms)),
  ]);
}

module.exports = { previewModel, versionHeader, rowsFrom, missingLine, emptyLine, footer, withCap, MAX_ROWS, PREVIEW_MS };
