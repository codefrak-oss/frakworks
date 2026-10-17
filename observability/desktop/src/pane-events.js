// pane-events.js — the turn ledger: which `result` on a Claude Code child's
// stdout answers which turn (stage 2 of the proxied-sessions work, issue
// #1334). First mate (src/first-mate.js) is its user. The routing envelope
// and the unsolicited-event queue that sat beside it served only the two
// Chats panes and went with them (#634).
//
// Plain objects with no timers, no processes and no Electron, so the tests
// can drive the bookkeeping without spawning anything.
"use strict";

// The stdin turn(s) an event says it belongs to, newest field first. The CLI
// emits both the singular and the plural form; a caller should treat them as
// one set rather than trusting either alone, because an event that answers a
// batch carries only the plural.
function userMessageUuids(event) {
  if (!event) return [];
  const out = [];
  const one = event.user_message_uuid;
  if (typeof one === "string" && one) out.push(one);
  const many = event.user_message_uuids;
  if (Array.isArray(many)) {
    for (const u of many) if (typeof u === "string" && u && !out.includes(u)) out.push(u);
  }
  return out;
}

// ---------------------------------------------------------------------------
// The turn ledger
// ---------------------------------------------------------------------------

// Which `result` answers which turn.
//
// The bridge (`mobile-bridge/server.py`, `ClaudeSession._read`) uses a single
// `mine` latch: a `user` event whose uuid matches the turn we are waiting on
// sets it, and the next `result` is ours. That is correct for one sender. With
// a peer able to start a turn on the same session it is not: a peer's result
// arriving while `mine` is set would be handed back as the answer to the
// user's question.
//
// So the ledger keeps the turns *in arrival order* instead of one bit. Every
// `user` event pushes an entry — `ours` when its uuid is the uuid we wrote to
// stdin, `peer` otherwise. A result arriving with the ledger empty belongs to
// nobody we know of, which is also a peer turn, so it is never allowed to
// resolve a pending turn either.
//
// **Which entry a `result` belongs to is read off the event, not guessed from
// the order.** `claude -p --output-format stream-json` (2.1.263, probed
// directly while fixing #1351's review) puts the stdin uuid its result answers
// on the result itself:
//
//   {"type":"result", …, "uuid":"d4d58e4f-…",          ← the *event's* own uuid
//    "user_message_uuid":"PROBE-UUID-123",             ← the turn it answers
//    "user_message_uuids":["PROBE-UUID-123"]}
//
// so `uuid` is useless for attribution — it is a fresh id per event, never the
// turn's — while `user_message_uuid(s)` is exact. `assistant` events carry the
// same field. A turn a *peer* started names a uuid this app never wrote to
// stdin, so it matches no ledger entry and is a peer turn by construction: no
// ordering assumption, and no way for a peer's reply to resolve the user's
// turn even when it arrives first.
//
// FIFO survives only as the fallback for a CLI that sends no attribution
// field at all. There the old caveat still applies — where arrival order lies,
// the ledger degrades to "unsolicited", which shows a peer message in the
// wrong place at worst.
// The `system/init` capability that means "every result I emit for a turn you
// sent will name that turn". Its presence is what lets an *un*attributed
// result be called a peer's outright instead of being handed to the oldest
// entry in the hope that the order was honest.
const MSG_LIFECYCLE_CAPABILITY = "msg_lifecycle_v1";

class TurnLedger {
  constructor() {
    this.queue = [];
    // Does this session attribute its results? Set from `system/init`, and
    // again by the first attributed event, because a capability list that
    // grows a new name is not a reason to stop believing the field in front
    // of us.
    this.attributes = false;
  }

  noteCapabilities(capabilities) {
    if (Array.isArray(capabilities) && capabilities.includes(MSG_LIFECYCLE_CAPABILITY)) {
      this.attributes = true;
    }
    return this.attributes;
  }

  // `pendingUuid` is the uuid of the turn this pane is waiting on, or null.
  noteUser(uuid, pendingUuid) {
    const kind = pendingUuid && uuid && uuid === pendingUuid ? "ours" : "peer";
    this.queue.push({ kind, uuid: uuid || null });
    return kind;
  }

  head() { return this.queue.length ? this.queue[0] : null; }

  // Which turn this `result` answers.
  //
  // Returns the ledger entry plus `attributed`, which says *how* it was
  // decided — `"user-message-uuid"` (exact), `"unknown-user-message"` (the
  // event named a turn we never sent), `"unattributed"` (it named none on a
  // session that attributes, so it answers no turn of ours) or `"fifo"` (it
  // named none on a session that does not attribute at all, and the oldest
  // entry was assumed). Only the last depends on arrival order.
  claimResult(event) {
    const named = userMessageUuids(event);
    if (named.length) {
      this.attributes = true;
      const at = this.queue.findIndex((e) => e.uuid && named.includes(e.uuid));
      if (at < 0) {
        // A turn this app did not send: a peer's, or one from a session we
        // resumed into. Never ours, whatever the order.
        return { kind: "peer", uuid: named[0], attributed: "unknown-user-message" };
      }
      const [entry] = this.queue.splice(at, 1);
      return { ...entry, attributed: "user-message-uuid" };
    }
    // Nothing named, on a session that names things: this result is not the
    // answer to any turn we sent.
    if (this.attributes) return { kind: "peer", uuid: null, attributed: "unattributed" };
    if (!this.queue.length) return { kind: "peer", uuid: null, attributed: "fifo" };
    return { ...this.queue.shift(), attributed: "fifo" };
  }

  // A turn that will never get a result — it timed out, or its child died.
  forget(uuid) { this.queue = this.queue.filter((e) => e.uuid !== uuid); }

  clear() { this.queue = []; }

  get depth() { return this.queue.length; }
}

module.exports = { userMessageUuids, TurnLedger };
