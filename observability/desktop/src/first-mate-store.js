// first-mate-store.js — persistence for First mate's one session (issue
// #100): an atomic replace of its own document. First mate is one
// singular pane (no rename, no pin, no peers; its one reset, added by #185,
// keeps the Filed list, but the display history is archived into a bounded
// `conversations` list, not kept inline — #257).
"use strict";

const fs = require("node:fs");
const path = require("node:path");

const FIRST_MATE_VERSION = 2;
const FIRST_MATE_HISTORY_LIMIT = 200;
const FIRST_MATE_FILE = "first-mate.json";
// The "New conversation started" system-row marker that a version-1 document
// used to record a reset inline (#185). Migration (#257) splits on it and
// then drops it: the marker becomes conversation boundaries, not content.
const NEW_CONVERSATION_MARKER = "New conversation started";
// Newest-first "Filed" list under the strip (acceptance criterion 9). Bounded
// for the same reason the display history is: a convenience list, not an
// archive — the tickets themselves are the record.
const FILED_LIMIT = 100;

// The ticket an intake was filed as (#927): `ticket`, `ticket_url` and
// `announced_at` once its "Filed as #<n>" row was pushed, `ended_at` once the
// engine said the intake ended (with or without a ticket), so it is not
// watched again; `conversation_key` names the conversation that filed it.
// All null on an entry from before #927, which is then watched again.
const numOrNull = (v) => (v == null || v === "" || !Number.isFinite(Number(v)) ? null : Number(v));
const strOrNull = (v) => (v ? String(v) : null);

const serializeFiled = (f) => ({
  intake_id: String(f.intake_id),
  tier: Number(f.tier) || 1,
  ask: String(f.ask),
  at: Number(f.at) || 0,
  conversation_key: strOrNull(f.conversation_key),
  ticket: Number.isInteger(f.ticket) ? f.ticket : null,
  ticket_url: strOrNull(f.ticket_url),
  announced_at: numOrNull(f.announced_at),
  ended_at: numOrNull(f.ended_at),
});

// `images` is how many images a user message carried (#368), never the
// images themselves; absent when there were none.
const serializeMessage = (h) => {
  const m = { role: String(h.role), text: String(h.text), at: Number(h.at) || 0 };
  const images = Math.floor(Number(h.images));
  if (images > 0) m.images = images;
  return m;
};

// `claude_session_id` (#486) is the Claude session the conversation ran in,
// so it can be resumed; null for one archived before #486, which had none
// stored — such a document still loads, its conversations with null here.
// `saved_chat_id` (#598) is the engine's saved-chat id the conversation was
// saved under ("Save for later"), so saving it again after a resume updates
// that save; null when it was never saved.
const savedChatId = (v) => (v ? String(v) : null);

// `conversation_key` (#927) is a conversation's stable identity, minted the
// first time it files an ask and kept through archive and resume, so the
// "Filed as #<n>" row finds it; null until then, and on older documents.
// `cwd` (#960, fresh code) is the directory the conversation's `claude` child
// runs in: the fresh clone of its repo (`<freshDir>/<owner>/<name>`), or
// `<freshDir>` in All repos. Null on an entry from before the build, which the
// manager reads as its legacy cwd (main's REPO_DIR); it is never written back
// as a path. A resumed or restored conversation starts in its stored cwd.
// `repo` (#2343) is the conversation's own repo, `owner/name`, or null when
// none is chosen yet (All repos). Absent on an entry from before it: the
// manager then takes the repo switcher's selection, once, as before.
const chatRepoField = (c) => (c.repo === undefined ? {} : { repo: strOrNull(c.repo) });
// `runtime` (#2521) is where the conversation runs, `local` (a `claude` child)
// or `kubernetes` (a session pod, src/first-mate-remote.js), fixed when it
// starts; `remote_session_id` the engine's session id for a pod. Both absent
// until a conversation starts, and on an entry from before: that one is local.
const RUNTIMES = ["local", "kubernetes"];
const runtimeField = (c) => (RUNTIMES.includes(c.runtime)
  ? { runtime: c.runtime, remote_session_id: c.runtime === "kubernetes" ? strOrNull(c.remote_session_id) : null }
  : {});

const serializeConversation = (c) => ({
  id: Number(c.id) || 1,
  started_at: Number(c.started_at) || 0,
  claude_session_id: c.claude_session_id ? String(c.claude_session_id) : null,
  saved_chat_id: savedChatId(c.saved_chat_id),
  conversation_key: strOrNull(c.conversation_key),
  cwd: strOrNull(c.cwd),
  ...chatRepoField(c),
  ...runtimeField(c),
  messages: (c.messages || []).map(serializeMessage),
});

const serializeSession = (s) => ({
  session_generation: Number(s.session_generation) || 1,
  claude_session_id: s.claude_session_id ? String(s.claude_session_id) : null,
  saved_chat_id: savedChatId(s.saved_chat_id),
  conversation_key: strOrNull(s.conversation_key),
  cwd: strOrNull(s.cwd),
  ...chatRepoField(s),
  ...runtimeField(s),
  // A restored saved chat whose Claude session is on another machine (#598):
  // the transcript preamble the next turn starts its fresh session with.
  seed: s.seed ? String(s.seed) : null,
  turns_since_filed: Number(s.turns_since_filed) || 0,
  history: (s.history || []).slice(-FIRST_MATE_HISTORY_LIMIT).map(serializeMessage),
  conversations: (s.conversations || []).map(serializeConversation),
  next_conversation_id: Number(s.next_conversation_id) || 1,
  filed: (s.filed || []).slice(0, FILED_LIMIT).map(serializeFiled),
});

const firstMatePath = (dir) => path.join(dir, FIRST_MATE_FILE);

function saveFirstMate(dir, session) {
  const target = firstMatePath(dir);
  fs.mkdirSync(dir, { recursive: true });
  const doc = { version: FIRST_MATE_VERSION, session: serializeSession(session) };
  const tmp = `${target}.${process.pid}.${Date.now()}.tmp`;
  fs.writeFileSync(tmp, `${JSON.stringify(doc, null, 2)}\n`, { mode: 0o600 });
  try {
    fs.renameSync(tmp, target);
  } catch (e) {
    fs.rmSync(tmp, { force: true });
    throw e;
  }
  return target;
}

const freshSession = () => ({
  session_generation: 1, claude_session_id: null, saved_chat_id: null, conversation_key: null, cwd: null, seed: null, turns_since_filed: 0,
  history: [], conversations: [], next_conversation_id: 1, filed: [],
});

// Moves a non-empty `history` into a new, newest-first past conversation and
// resets `history` to []; a no-op when `history` is already empty (the
// pattern `_push` and `startNewConversation` share — #257). The live
// `claude_session_id` goes with it (#486), so the conversation can later be
// resumed; clearing `session.claude_session_id` stays the caller's job. So
// does its `saved_chat_id` (#598), and clearing that too, and its
// `conversation_key` (#927) and its `cwd` (#960), and its `runtime` (#2521).
function archiveCurrentConversation(session) {
  if (!session.history.length) return session;
  const conversation = {
    id: session.next_conversation_id,
    started_at: session.history[0].at,
    claude_session_id: session.claude_session_id ? String(session.claude_session_id) : null,
    saved_chat_id: savedChatId(session.saved_chat_id),
    conversation_key: strOrNull(session.conversation_key),
    cwd: strOrNull(session.cwd),
    ...chatRepoField(session),
    ...runtimeField(session),
    messages: session.history,
  };
  session.conversations.unshift(conversation);
  session.next_conversation_id += 1;
  session.history = [];
  return session;
}

// Keeps `history` plus every conversation's messages, combined, at or under
// FIRST_MATE_HISTORY_LIMIT: trims the oldest message off the oldest past
// conversation first, dropping a conversation once it empties, and never
// trims the live `history` (#257).
function capTotal(session) {
  let total = session.history.length +
    session.conversations.reduce((n, c) => n + c.messages.length, 0);
  while (total > FIRST_MATE_HISTORY_LIMIT && session.conversations.length) {
    const oldest = session.conversations[session.conversations.length - 1];
    oldest.messages.shift();
    total -= 1;
    if (!oldest.messages.length) session.conversations.pop();
  }
  return session;
}

// Splits a version-1 flat `history` on its "New conversation started" system
// rows (#185's inline marker) into oldest-first past conversations, storing
// them newest-first, plus a final current-history tail. The markers
// themselves are dropped, not kept as content (#257).
function migrateHistory(history) {
  const groups = [[]];
  for (const h of history) {
    if (h.role === "system" && String(h.text).startsWith(NEW_CONVERSATION_MARKER)) {
      groups.push([]);
    } else {
      groups[groups.length - 1].push(h);
    }
  }
  const currentHistory = groups.pop();
  const pastGroups = groups.filter((g) => g.length);
  const conversations = pastGroups.map((messages, i) => ({
    id: i + 1,
    started_at: messages[0].at,
    messages,
  })).reverse();
  return { history: currentHistory, conversations, next_conversation_id: pastGroups.length + 1 };
}

// Never throws: a missing, unreadable, malformed or unknown-version document
// all mean "no saved session", which is a first run.
function loadFirstMate(dir) {
  let raw;
  try {
    raw = fs.readFileSync(firstMatePath(dir), "utf8");
  } catch (e) {
    return { session: freshSession(), error: e.code === "ENOENT" ? null : `first-mate.json unreadable: ${e.message}` };
  }
  let doc;
  try {
    doc = JSON.parse(raw);
  } catch (e) {
    return { session: freshSession(), error: `first-mate.json is not JSON: ${e.message}` };
  }
  if (!doc || !doc.session || (doc.version !== 1 && doc.version !== FIRST_MATE_VERSION)) {
    return { session: freshSession(), error: `first-mate.json version ${doc && doc.version} is not ${FIRST_MATE_VERSION}` };
  }
  if (doc.version === FIRST_MATE_VERSION) return { session: serializeSession(doc.session), error: null };
  const migrated = migrateHistory(doc.session.history || []);
  return { session: serializeSession({ ...doc.session, ...migrated }), error: null };
}

module.exports = {
  FIRST_MATE_VERSION, FIRST_MATE_HISTORY_LIMIT, FIRST_MATE_FILE, FILED_LIMIT, NEW_CONVERSATION_MARKER,
  serializeSession, serializeFiled, firstMatePath, saveFirstMate, loadFirstMate, freshSession,
  archiveCurrentConversation, capTotal,
};
