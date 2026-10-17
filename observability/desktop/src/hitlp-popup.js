// hitlp-popup.js — the pure parts of the urgent HITLP popup (#2196): which
// Needs row pops (an `ask` or `approve` with `priority` `urgent`, for this
// login or for everyone, not snoozed, not silenced, oldest `since` first), the
// snooze and silence bookkeeping in the renderer's localStorage, the card's
// header line and what a key press does. src/renderer/hitlp-popup.jsx draws
// the card; test/hitlp-popup.test.js exercises these on fixtures.
"use strict";

const MINUTE = 60 * 1000;
const SNOOZES = [{ label: "5 min", ms: 5 * MINUTE }, { label: "15 min", ms: 15 * MINUTE }, { label: "1 hour", ms: 60 * MINUTE }];
const SILENCE_MS = 60 * MINUTE;
const HINT = "1–9 picks an option · Esc snoozes 5 min";

// Epoch milliseconds, per login.
const snoozeKey = (login, taskId) => `hitlp.snooze.${login}.${taskId}`;
const silenceKey = (login) => `hitlp.silence.${login}`;

const readMs = (storage, key) => {
  try {
    const v = storage ? Number(storage.getItem(key)) : NaN;
    return Number.isFinite(v) && v > 0 ? v : 0;
  } catch (e) { return 0; }
};
const writeMs = (storage, key, until) => {
  try { if (storage) storage.setItem(key, String(until)); } catch (e) { /* per-viewer nicety only */ }
};

const snoozedUntil = (storage, login, taskId) => readMs(storage, snoozeKey(login, taskId));
const silencedUntil = (storage, login) => readMs(storage, silenceKey(login));
/** Hides one task until `now + ms`; returns that instant. */
const snooze = (storage, login, taskId, ms, now = Date.now()) => { const until = now + ms; writeMs(storage, snoozeKey(login, taskId), until); return until; };
/** "Stop all for 1 hour": hides every urgent popup until `now + ms`. */
const silence = (storage, login, ms = SILENCE_MS, now = Date.now()) => { const until = now + ms; writeMs(storage, silenceKey(login), until); return until; };

/** An open urgent HITLP task waiting on `login` or on everyone (`for` null). */
const isUrgentForMe = (row, login) => !!row && (row.kind === "ask" || row.kind === "approve") && row.priority === "urgent"
  && (row.for == null || (!!login && String(row.for).toLowerCase() === String(login).toLowerCase()));

// Oldest first: `since` (epoch seconds) ascending, else the longest `waited`.
const age = (r) => (r.since != null ? r.since : -(r.waited || 0) - 1e12);

/** Every row that may pop now, oldest first. */
const popupQueue = (rows, login, storage, now = Date.now()) => {
  if (silencedUntil(storage, login) > now) return [];
  return (Array.isArray(rows) ? rows : [])
    .filter((r) => isUrgentForMe(r, login) && !(snoozedUntil(storage, login, r.actionId) > now))
    .sort((a, b) => age(a) - age(b));
};

/** The one row the popup shows, or null. */
const pickPopup = (rows, login, storage, now = Date.now()) => popupQueue(rows, login, storage, now)[0] || null;

/**
 * When the next snooze or the silence on an urgent row elapses (epoch ms), or
 * null: the popup sets one timeout for it, never a poll.
 */
const nextWake = (rows, login, storage, now = Date.now()) => {
  const ends = [];
  const quiet = silencedUntil(storage, login);
  if (quiet > now) ends.push(quiet);
  for (const r of Array.isArray(rows) ? rows : []) {
    if (!isUrgentForMe(r, login)) continue;
    const s = snoozedUntil(storage, login, r.actionId);
    if (s > now) ends.push(s);
  }
  return ends.length ? Math.min(...ends) : null;
};

// A party as text: a string, or an object's id, login, name or client.
const partyText = (p) => (typeof p === "string" ? p
  : p && typeof p === "object" ? p.id || p.login || p.name || p.client || null : null);

/**
 * "<who> · <on behalf of, or owner/name#n> NEEDS YOU", missing parts left out:
 * `who` is what the HITLP server attested (`attested.requester`), never the
 * requester's own claim.
 */
const headerText = (row) => {
  const att = (row && row.attested) || {};
  const ticket = row && row.repo && row.ticket != null ? `${row.repo}#${row.ticket}` : null;
  const behalf = partyText(att.onBehalfOf) || (row && row.for) || null;
  const parts = [partyText(att.requester), behalf || ticket].filter((p) => typeof p === "string" && p);
  return `${parts.length ? parts.join(" · ") + " " : ""}NEEDS YOU`;
};

/**
 * What a key does while the card is up: `{ press: i }` (the i-th button, a
 * digit 1–9 when there is one), `{ snooze: ms }` (Escape), or null. Keys typed
 * into a box (`inBox`) are the box's own.
 */
const keyAction = (key, buttons, inBox = false) => {
  if (inBox) return null;
  if (key === "Escape") return { snooze: SNOOZES[0].ms };
  if (/^[1-9]$/.test(String(key))) {
    const i = Number(key) - 1;
    return i < (buttons || []).length ? { press: i } : null;
  }
  return null;
};

module.exports = { SNOOZES, SILENCE_MS, HINT, snoozeKey, silenceKey, snoozedUntil, silencedUntil, snooze, silence,
  isUrgentForMe, popupQueue, pickPopup, nextWake, headerText, partyText, keyAction };
