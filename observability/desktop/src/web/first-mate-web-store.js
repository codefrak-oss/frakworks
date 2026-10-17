// first-mate-web-store.js — where the web version keeps First mate's one
// session document (#100104): the browser's localStorage under the page's
// origin, in place of the desktop's `<userData>/first-mate.json`
// (src/first-mate-store.js). The document is the same `{ version: 2,
// session }` shape, written through the store's own `serializeSession`, so
// the pure helpers over it (`archiveCurrentConversation`, `capTotal`) are
// shared, not forked.
//
// Keyed by the signed-in login (`frakworks.firstMate.<login>`), so two
// sign-ins on one iPad do not share a conversation. `storage` is injectable
// (a Storage, or fleet-web's `memoryStorage` in tests), and every read or
// write is caught: a missing, malformed or wrong-version value is a fresh
// session, as `loadFirstMate` reads one, and a write the browser refuses (the
// quota) answers `{ error }` for the strip's error line.
"use strict";

const { FIRST_MATE_VERSION, serializeSession, freshSession, capTotal } = require("../first-mate-store");

const KEY_PREFIX = "frakworks.firstMate.";
const TIER_PREFIX = "frakworks.firstMate.tier.";

const storeKey = (login) => `${KEY_PREFIX}${String(login)}`;
const tierKey = (login) => `${TIER_PREFIX}${String(login)}`;

function createWebFirstMateStore({ storage, login } = {}) {
  if (!storage) throw new Error("the web First mate store needs a storage");
  if (!login) throw new Error("the web First mate store needs the signed-in login");
  const key = storeKey(login);
  const tkey = tierKey(login);
  return {
    key,
    /** `{ session, error }`: the stored document, or a fresh session with why. Never throws. */
    load() {
      let raw;
      try { raw = storage.getItem(key); } catch (e) { return { session: freshSession(), error: `localStorage unreadable: ${e.message}` }; }
      if (raw == null || raw === "") return { session: freshSession(), error: null };
      let doc;
      try { doc = JSON.parse(raw); } catch (e) { return { session: freshSession(), error: `the stored First mate session is not JSON: ${e.message}` }; }
      if (!doc || !doc.session || doc.version !== FIRST_MATE_VERSION) {
        return { session: freshSession(), error: `the stored First mate session version ${doc && doc.version} is not ${FIRST_MATE_VERSION}` };
      }
      return { session: serializeSession(doc.session), error: null };
    },
    /** Writes `session`, capped as the desktop's archive is. `{ ok }` or `{ error }`; never throws. */
    save(session) {
      const doc = { version: FIRST_MATE_VERSION, session: serializeSession(capTotal(session)) };
      try { storage.setItem(key, JSON.stringify(doc)); } catch (e) { return { error: `the First mate session could not be kept in localStorage: ${e.message}` }; }
      return { ok: true };
    },
    /** The standing tier kept for this login, an integer 1-10, or null. */
    loadTier() {
      try {
        const n = Number(storage.getItem(tkey));
        return Number.isInteger(n) && n >= 1 && n <= 10 ? n : null;
      } catch { return null; }
    },
    saveTier(tier) {
      try { storage.setItem(tkey, String(tier)); return { ok: true }; } catch (e) { return { error: `the tier could not be kept in localStorage: ${e.message}` }; }
    },
  };
}

module.exports = { createWebFirstMateStore, storeKey, tierKey, KEY_PREFIX };
