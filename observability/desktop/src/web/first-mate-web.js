// first-mate-web.js — `window.firstMate` for the web version (#100104): the
// part of main's FirstMateManager (src/first-mate.js) a browser can play,
// over the same pieces. A conversation runs in a session pod on the cluster,
// driven by the unchanged `RemoteSession` (src/first-mate-remote.js) with the
// page's own sign-in bearer (the session API takes a plain sign-in bearer,
// engine/docs/first-mate-sessions.md); turns are attributed by `TurnLedger`
// (src/pane-events.js), images checked by src/first-mate-images.js, and the
// session document is the desktop's shape (src/first-mate-store.js's pure
// helpers), kept in localStorage by src/web/first-mate-web-store.js.
//
// The renderer (src/renderer/first-mate.jsx, index.jsx's FirstMateStrip) is
// reused as it is: every member preload's `firstMate` block exposes exists
// here and answers the shapes main answers. What only the desktop can do is
// fixed off: `can_handoff`, `can_open_in_iterm` false, `iterm` unavailable,
// `account` and `mcp_notice` null; `handoff`, `openInIterm` and
// `restoreSaved` answer `{ error: OMITTED }`, `font` `{ family: null }`.
//
// Hosting (`GET /settings/first-mate-hosting`) is read at the first `list`
// and again whenever a conversation would start: `kubernetes` runs it in a
// pod; `local` is a `claude` process on the desktop's machine, which a
// browser cannot start, so the snapshot carries one `notice` saying so and
// `send`/`startNew` answer it.
"use strict";

const crypto = require("node:crypto");
const { RemoteSession } = require("../first-mate-remote");
const { TurnLedger, userMessageUuids } = require("../pane-events");
const { validateImages, buildContent } = require("../first-mate-images");
const { normalizeFileLine } = require("../file-line");
const {
  FIRST_MATE_HISTORY_LIMIT, FILED_LIMIT, freshSession, archiveCurrentConversation, capTotal,
} = require("../first-mate-store");
const engineData = require("../engine-data");
const engineTiers = require("../engine-tiers");
const { createWebFirstMateStore } = require("./first-mate-web-store");

const { PAGE_ENV } = engineData;

/** What a desktop-only member answers in the browser (fleet-web.js's wording). */
const OMITTED = "not in the web version: this needs the desktop app (observability/desktop/README.md, \"Web version\")";

const LOCAL_HOSTING_NOTICE = "First mate hosting is local: local First mate runs a `claude` process on the desktop's machine, " +
  "which a browser cannot start. Use the desktop app, or set First mate hosting to kubernetes (Config > frakworks).";
const REMOTE_TIER_NOTE = "applies to the next conversation";
const REMOTE_CANCEL_ROW = "Cancelled here: the pod finishes the turn on its own, and its answer is not shown.";
const REMOTE_EXPIRED_ROW = "The session pod expired (404): a new session starts in this strip with the next message.";
const INTERRUPTED_TEXT = "Interrupted. The session was stopped; anything it had already done still happened.";
const PICK_REPO_NOTICE = "Pick a repo for this chat: First mate runs in a pod on the cluster, which needs one.";
const UNFILED_TURN_HINT = 3;
const DEFAULT_TIER = 8;
const TURN_TIMEOUT_MS = 90 * 1000;
const REPO_RE = /^[\w.-]+\/[\w.-]+$/;

/** The text of an assistant event, ignoring tool-use blocks (src/claude-session.js's). */
function assistantText(event) {
  const content = event && event.message && event.message.content;
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content.filter((b) => b && b.type === "text").map((b) => b.text || "").join("");
}

// src/saved-chats.js's `chatTitle`, as it is: that module reaches
// src/claude-session.js, which reads `process.env` as it loads and so cannot
// be in the browser bundle (test/web-first-mate.test.js keeps the two equal).
const TITLE_MAX = 80;
function chatTitle(messages) {
  const first = (messages || []).find((m) => m && m.role === "user" && String(m.text || "").trim());
  if (!first) return "Untitled chat";
  const line = String(first.text).trim().split("\n")[0].replace(/\s+/g, " ").trim();
  if (!line) return "Untitled chat";
  return line.length > TITLE_MAX ? `${line.slice(0, TITLE_MAX - 1)}…` : line;
}

/** A transcript row's text: the engine's body is `{ text }` or `{ text, images }`, or a plain string. */
function rowText(body) {
  if (typeof body === "string") return body;
  if (body && typeof body === "object") {
    if (typeof body.text === "string") return body.text;
    if (typeof body.result === "string") return body.result;
  }
  return "";
}

/**
 * The runtime. `store` is the browser store (or `{ storage, login }` to build
 * one); `bearer()` answers the page's `Bearer <token>` or null; `engineEvents`
 * the page's EngineEventStream; `hosting()` resolves to engine-data's
 * `fetchFirstMateHosting` answer; `repo()` the switcher's selection;
 * `submitIntake(ask, repo)` files as the login; `repoNames()` the target repos.
 */
function createWebFirstMate({ store, storage, login, bearer, engineEvents, hosting, repo, submitIntake, repoNames,
  loadTiers, resolveTier, api, transcript, saveChat, now, turnTimeoutMs } = {}) {
  const st = store || createWebFirstMateStore({ storage, login });
  const bearerFn = bearer || (() => null);
  const hostingFn = hosting || (() => engineData.fetchFirstMateHosting({ env: PAGE_ENV }));
  const repoFn = repo || (() => null);
  const submitIntakeFn = submitIntake || (async () => ({ error: "no way to file from this page" }));
  const repoNamesFn = repoNames || (() => engineData.fetchRepoNames({ needs: false, env: PAGE_ENV }));
  const loadTiersFn = loadTiers || (() => engineTiers.loadTiers({ env: PAGE_ENV }));
  const resolveTierFn = resolveTier || engineTiers.resolveTier;
  const sessionApi = api || {
    start: (r, ask, tier, b) => engineData.startFirstMateSession(r, ask, tier, b, { env: PAGE_ENV }),
    send: (id, text, images, clientId, b) => engineData.sendFirstMateMessage(id, text, images, clientId, b, { env: PAGE_ENV }),
    end: (id, b) => engineData.endFirstMateSession(id, b, { env: PAGE_ENV }),
  };
  const transcriptFn = transcript || ((id, b) => engineData.fetchFirstMateTranscript(id, b, { env: PAGE_ENV }));
  const saveChatFn = saveChat || (async (id, body) => {
    const b = await bearerFn();
    return engineData.saveChat(id, body, { env: PAGE_ENV, headers: b ? { authorization: b } : null });
  });
  const clock = now || (() => Date.now());
  const timeoutMs = turnTimeoutMs || TURN_TIMEOUT_MS;

  const loaded = st.load();
  let session = loaded.session || freshSession();
  let loadError = loaded.error;
  const rt = {
    child: null, pending: null, busy: false, streaming: "", notice: null, recovery: null,
    ledger: new TurnLedger(), fileCard: null, fileBusy: false, fileError: null, tierNotice: null,
    remoteState: null, remoteDetail: null, childEff: null, drainRemote: false, hosting: null, hostingError: null,
    repoChangedFrom: undefined,
  };
  const updateListeners = new Set();
  const streamListeners = new Set();
  const emitUpdate = () => { const snap = snapshot(); for (const cb of [...updateListeners]) cb(snap); };
  const emitStream = (chunk) => { for (const cb of [...streamListeners]) cb(chunk); };

  const save = () => { const r = st.save(session); loadError = r.error || null; };
  const push = (role, text, images = 0) => {
    const row = { role, text: String(text), at: clock() };
    if (images > 0) row.images = images;
    session.history.push(row);
    capTotal(session);
  };
  const remote = () => session.runtime === "kubernetes";
  const chatRepo = () => (session.repo === undefined ? repoFn() || null : session.repo);
  const startChatRepo = () => { session.repo = repoFn() || null; rt.repoChangedFrom = undefined; };
  const popTarget = () => session.conversations.find((c) => c.runtime === "kubernetes" && c.remote_session_id) || null;

  // ---- hosting -------------------------------------------------------------
  async function readHosting() {
    let r;
    try { r = await hostingFn(); } catch (e) { r = { error: (e && e.message) || String(e) }; }
    if (r && r.firstMateHosting && r.firstMateHosting.hosting) { rt.hosting = r.firstMateHosting.hosting; rt.hostingError = null; }
    else { rt.hosting = null; rt.hostingError = `First mate hosting unavailable (${(r && r.error) || "no answer"})`; }
    return rt.hosting;
  }
  const hostingNotice = () => {
    if (rt.hosting === "local") return LOCAL_HOSTING_NOTICE;
    if (rt.hostingError && !remote()) return `${rt.hostingError}: First mate cannot start a conversation from this page until the engine answers.`;
    return null;
  };

  // ---- the tier --------------------------------------------------------------
  const standingTier = () => st.loadTier() || DEFAULT_TIER;
  const effective = () => {
    const tier = standingTier();
    const pair = resolveTierFn(tier);
    return { tier, model: pair ? pair.model : null, effort: pair ? pair.effort : null, source: "standing" };
  };
  const tierState = () => ({ standing: standingTier(), effective: effective(), error: null });

  // ---- the snapshot ------------------------------------------------------------
  function snapshot() {
    return {
      chat_repo: chatRepo(),
      chat_repo_on: true,
      version: 1,
      error: loadError || null,
      session_generation: session.session_generation,
      has_session: false,
      runtime: session.runtime || null,
      remoteState: rt.remoteState || null,
      remote_detail: rt.remoteDetail || null,
      running: Boolean(rt.child && rt.child.alive),
      busy: rt.busy,
      recovery: rt.recovery,
      notice: rt.notice || hostingNotice(),
      repo_mismatch: null,
      streaming: rt.streaming,
      history: session.history.slice(-FIRST_MATE_HISTORY_LIMIT),
      file: rt.fileCard ? { ...rt.fileCard } : null,
      file_busy: rt.fileBusy,
      file_error: rt.fileError,
      filed: session.filed.slice(0, FILED_LIMIT),
      turns_since_filed: session.turns_since_filed,
      unfiled_hint: session.turns_since_filed >= UNFILED_TURN_HINT,
      tier: effective(),
      tier_notice: rt.tierNotice,
      account: null,
      account_notice: null,
      mcp_notice: null,
      can_save: session.history.length > 0,
      saved_chat_id: session.saved_chat_id || null,
      can_handoff: false,
      can_pop: Boolean(popTarget()),
      can_open_in_iterm: false,
      iterm: { available: false, why: OMITTED },
    };
  }

  // ---- the session pod ---------------------------------------------------------
  function killChild(why, { detach = false } = {}) {
    if (rt.child) {
      rt.child.removeAllListeners();
      // A pod's session is let go of, not ended (main's rule): it stays on the engine for History and Back.
      if (detach || typeof rt.child.detach === "function") rt.child.detach(); else rt.child.kill();
    }
    rt.child = null;
    rt.busy = false;
    rt.streaming = "";
    rt.remoteState = null;
    rt.remoteDetail = null;
    rt.ledger.clear();
    if (rt.pending) {
      const { resolve, timer } = rt.pending;
      clearTimeout(timer);
      rt.pending = null;
      resolve({ ok: true, interrupted: true, text: INTERRUPTED_TEXT, why: why || null });
    }
  }

  /** Ends the live pod session on the engine (DELETE), letting the runtime go. */
  function endChild(why) {
    const child = rt.child;
    if (child) child.removeAllListeners();
    rt.child = null;
    if (child && !child.exited) child.kill();
    else if (session.remote_session_id) {
      const id = session.remote_session_id;
      Promise.resolve(bearerFn()).then((b) => sessionApi.end(id, typeof b === "string" ? b : null)).catch(() => {});
    }
    rt.busy = false;
    rt.streaming = "";
    rt.remoteState = null;
    rt.remoteDetail = null;
    rt.ledger.clear();
    if (rt.pending) {
      const { resolve, timer } = rt.pending;
      clearTimeout(timer);
      rt.pending = null;
      resolve({ ok: true, interrupted: true, text: INTERRUPTED_TEXT, why: why || null });
    }
  }

  function startRemote(eff, text = "") {
    const first = session.history.find((m) => m.role === "user");
    const child = new RemoteSession({
      repo: chatRepo(),
      ask: (first && first.text) || text || "",
      tier: eff.tier,
      drainUntilEcho: Boolean(rt.drainRemote),
      sessionId: session.remote_session_id || null,
      api: sessionApi,
      bearer: async () => {
        const b = await bearerFn();
        return b || { error: engineData.SIGN_IN_PROMPT };
      },
      engineEvents: engineEvents || null,
      log: (line) => console.log(line),
    });
    rt.child = child;
    rt.childEff = { ...eff };
    rt.drainRemote = false;
    const generation = session.session_generation;
    child.on("session", ({ id }) => { if (generation !== session.session_generation) return; session.remote_session_id = id; save(); });
    child.on("event", (event) => onEvent(generation, event));
    child.on("exit", (info) => onExit(generation, info, child));
    child.start();
    return child;
  }

  function onEvent(generation, event) {
    if (generation !== session.session_generation || !event) return;
    const pending = rt.pending;
    if (event.type === "resuming") { rt.remoteState = "resuming…"; emitUpdate(); return; }
    if (event.type === "pod-status") { rt.remoteDetail = String(event.text || event.status || event.phase || ""); emitUpdate(); return; }
    if (rt.remoteState) { rt.remoteState = null; rt.remoteDetail = null; emitUpdate(); }
    if (event.type === "system" && event.subtype === "first_line") { push("system", String(event.text)); save(); emitUpdate(); return; }
    if (event.type === "system" && event.subtype === "init") { rt.ledger.noteCapabilities(event.capabilities); return; }
    if (event.type === "user") {
      const kind = rt.ledger.noteUser(event.uuid, pending && pending.uuid);
      if (kind === "ours" && pending) pending.mine = true;
      return;
    }
    if (event.type === "assistant") {
      if (!pending) return;
      const named = userMessageUuids(event);
      if (named.length && (!pending || !pending.uuid || !named.includes(pending.uuid))) return;
      const text = assistantText(event);
      if (text) { rt.streaming += text; emitStream({ text }); }
      return;
    }
    if (event.type === "result") {
      const owner = rt.ledger.claimResult(event);
      const ours = owner.kind === "ours" && pending && pending.mine && (owner.attributed !== "user-message-uuid" || owner.uuid === pending.uuid);
      if (!ours) return;
      const raw = event.result || rt.streaming || "The turn finished with nothing to say.";
      const { reply, file } = normalizeFileLine(raw);
      rt.pending = null;
      rt.busy = false;
      rt.streaming = "";
      rt.recovery = null;
      clearTimeout(pending.timer);
      push("assistant", reply);
      session.turns_since_filed += 1;
      rt.fileCard = file ? { ...file, turn_id: pending.uuid } : null;
      rt.fileError = null;
      save();
      pending.resolve({ ok: true, text: reply, file: rt.fileCard });
      emitUpdate();
    }
  }

  function onExit(generation, info, child) {
    if (generation !== session.session_generation || rt.child !== child) return;
    rt.child = null;
    const pending = rt.pending;
    rt.pending = null;
    rt.busy = false;
    rt.streaming = "";
    rt.ledger.clear();
    if (info && (info.gone || info.auth)) session.remote_session_id = null;
    if (info && info.status === 404) push("system", REMOTE_EXPIRED_ROW);
    if (info && info.auth) rt.notice = info.error;
    if (pending || !child.sawResult) {
      const detail = info && info.error ? ` (${info.error})` : "";
      rt.recovery = { kind: "child_exited", message: `First mate's session ended before answering${detail}.`, session_id: null };
      push("system", rt.recovery.message);
      if (pending) { clearTimeout(pending.timer); pending.resolve({ ok: false, error: rt.recovery.message }); }
    }
    save();
    emitUpdate();
  }

  function writeTurn({ content, eff, resolve }) {
    const uuid = crypto.randomUUID();
    const timer = setTimeout(() => {
      if (rt.pending && rt.pending.uuid === uuid) {
        killChild("timed out");
        rt.recovery = { kind: "timeout", message: `No answer after ${Math.round(timeoutMs / 1000)}s; First mate's session was let go of.`, session_id: null };
        emitUpdate();
      }
    }, timeoutMs);
    if (timer.unref) timer.unref();
    rt.pending = { uuid, mine: false, resolve, timer, content, eff };
    const wrote = rt.child.write({ type: "user", uuid, message: { role: "user", content } });
    if (!wrote) {
      clearTimeout(timer);
      rt.busy = false;
      rt.pending = null;
      resolve({ ok: false, error: "First mate's session is not accepting input" });
    }
  }

  /** The assistant rows the pod wrote while the page was away: appended from the transcript route. */
  async function catchUp() {
    const id = session.remote_session_id;
    if (!remote() || !id) return;
    const b = await bearerFn();
    const r = await transcriptFn(id, b || null);
    if (!r || r.error) {
      if (r && r.status === 404) { session.remote_session_id = null; push("system", REMOTE_EXPIRED_ROW); save(); }
      return;
    }
    const rows = r.messages.filter((m) => m && (m.role === "assistant" || m.role === "user"));
    // The pod's answer(s) to the page's last user row, less those the page already has.
    const lastUserAt = session.history.map((m) => m.role).lastIndexOf("user");
    if (lastUserAt < 0) return;
    const lastUser = session.history[lastUserAt];
    let fromIdx = -1;
    for (let i = rows.length - 1; i >= 0; i--) if (rows[i].role === "user" && rowText(rows[i].body) === lastUser.text) { fromIdx = i; break; }
    if (fromIdx < 0) return;
    const already = session.history.slice(lastUserAt + 1).filter((m) => m.role === "assistant").length;
    const missing = rows.slice(fromIdx + 1).filter((m) => m.role === "assistant").slice(already);
    if (!missing.length) return;
    for (const m of missing) {
      const t = rowText(m.body);
      if (t) session.history.push({ role: "assistant", text: t, at: Date.parse(m.at) || clock() });
    }
    capTotal(session);
    save();
    emitUpdate();
  }

  // ---- the members ---------------------------------------------------------------
  const api_ = {
    async list() {
      if (rt.hosting === null && !rt.hostingError) await readHosting();
      return snapshot();
    },
    onUpdate(cb) { updateListeners.add(cb); return () => updateListeners.delete(cb); },
    onStream(cb) { streamListeners.add(cb); return () => streamListeners.delete(cb); },

    async send(text, images) {
      const checked = validateImages(images);
      if (!checked.ok) return { ok: false, refused: true, error: checked.error };
      const imgs = checked.images;
      if (typeof text !== "string" || (!text.trim() && !imgs.length)) return { ok: false, refused: true, error: "nothing to send" };
      if (rt.busy) {
        rt.notice = "First mate is still working on the previous message. Wait for it, or cancel.";
        emitUpdate();
        return { ok: false, busy: true, refused: true, error: rt.notice };
      }
      if (session.repo === undefined && !session.history.length) startChatRepo();
      rt.notice = null;
      rt.fileCard = null;
      rt.fileError = null;
      rt.tierNotice = null;
      let eff = effective();
      if (!eff.model) { try { await loadTiersFn(); } catch { /* the tier map stays unknown */ } eff = effective(); }
      // Where it runs is fixed as the conversation starts: the hosting, read now.
      if (!session.runtime && !session.remote_session_id) {
        rt.busy = true;
        const generation = session.session_generation;
        await readHosting();
        rt.busy = false;
        if (generation !== session.session_generation) return { ok: true, interrupted: true, text: INTERRUPTED_TEXT, why: "cancelled" };
        if (rt.hosting !== "kubernetes") {
          rt.notice = hostingNotice();
          emitUpdate();
          return { ok: false, refused: true, error: rt.notice };
        }
        session.runtime = "kubernetes";
        session.remote_session_id = null;
      }
      if (!chatRepo()) {
        rt.notice = PICK_REPO_NOTICE;
        emitUpdate();
        return { ok: false, refused: true, error: rt.notice };
      }
      // A relaunch that finds a pod session: the rows it wrote meanwhile come first.
      if (!rt.child && session.remote_session_id) {
        rt.busy = true;
        const generation = session.session_generation;
        try { await catchUp(); } catch { /* best effort */ }
        rt.busy = false;
        if (generation !== session.session_generation) return { ok: true, interrupted: true, text: INTERRUPTED_TEXT, why: "cancelled" };
      }
      if (!rt.child || !rt.child.alive) startRemote(eff, text);
      else if (rt.childEff && rt.childEff.tier !== eff.tier) {
        rt.tierNotice = `Tier ${eff.tier} (${eff.model} ${eff.effort}) ${REMOTE_TIER_NOTE}: this one runs on tier ${rt.childEff.tier} in its pod.`;
      }
      const content = buildContent(text.trim() ? text : "", imgs);
      push("user", text, imgs.length);
      rt.busy = true;
      rt.streaming = "";
      save();
      emitUpdate();
      const r = await new Promise((resolve) => writeTurn({ content, eff, resolve }));
      emitUpdate();
      return r;
    },

    async cancel() {
      const wasBusy = rt.busy;
      session.session_generation += 1;
      endChild("cancelled");
      session.remote_session_id = null;
      if (wasBusy) { push("assistant", INTERRUPTED_TEXT); push("system", REMOTE_CANCEL_ROW); }
      save();
      emitUpdate();
      return { ok: true };
    },

    async startNew() {
      if (rt.hosting === null && !rt.hostingError) await readHosting();
      if (rt.hosting === "local" && !remote() && !session.history.length) {
        rt.notice = LOCAL_HOSTING_NOTICE;
        emitUpdate();
        return { ok: false, refused: true, error: rt.notice };
      }
      endChild("starting a new conversation");
      archiveCurrentConversation(session);
      session.claude_session_id = null;
      session.saved_chat_id = null;
      session.conversation_key = null;
      session.seed = null;
      delete session.runtime;
      delete session.remote_session_id;
      startChatRepo();
      session.session_generation += 1;
      session.turns_since_filed = 0;
      rt.recovery = null;
      rt.fileCard = null;
      rt.fileError = null;
      rt.notice = rt.hosting === "local" ? LOCAL_HOSTING_NOTICE : "New conversation started.";
      save();
      emitUpdate();
      return rt.hosting === "local" ? { ok: false, refused: true, error: rt.notice } : { ok: true };
    },

    async conversations() {
      return session.conversations.map((c) => {
        const first = c.messages.find((m) => m.role === "user") || c.messages[0] || null;
        return { id: c.id, started_at: c.started_at, first_message: first ? first.text : null, saved_chat_id: c.saved_chat_id || null };
      });
    },
    async conversation(id) {
      const c = session.conversations.find((x) => x.id === Number(id));
      return c ? { id: c.id, started_at: c.started_at, resumable: Boolean(c.runtime === "kubernetes" && c.remote_session_id), saved_chat_id: c.saved_chat_id || null, messages: c.messages } : null;
    },

    async resume(id) {
      const target = session.conversations.find((c) => c.id === Number(id));
      if (!target) return { ok: false, error: `there is no past conversation ${id}` };
      if (!(target.runtime === "kubernetes" && target.remote_session_id)) return { ok: false, error: "this conversation has no session pod to resume" };
      return makeLive(target, "resuming a past conversation", "Resumed a past conversation.");
    },
    async pop() {
      const target = popTarget();
      if (!target) return { ok: false, error: "there is no past conversation to go back to" };
      return makeLive(target, "going back to the previous conversation", "Back to the previous conversation.");
    },

    async save() {
      const history = session.history;
      if (!history.length) return { ok: false, error: "there is no conversation to save" };
      if (!session.saved_chat_id) { session.saved_chat_id = crypto.randomUUID(); save(); }
      const eff = effective();
      const body = {
        title: chatTitle(history),
        startedAt: new Date(Number(history[0].at) || clock()).toISOString(),
        repo: chatRepo(),
        tier: Number.isInteger(eff.tier) ? eff.tier : null,
        claudeSessionId: null,
        ...(remote() ? { runtime: "kubernetes" } : {}),
        savedFrom: "web",
        messages: history.slice(-FIRST_MATE_HISTORY_LIMIT).map((m) => {
          const row = { role: m.role, text: String(m.text), at: Number(m.at) || 0 };
          if (m.images > 0) row.images = m.images;
          return row;
        }),
      };
      const r = await saveChatFn(session.saved_chat_id, body);
      if (!r || r.error) return { ok: false, error: (r && r.error) || "saving failed" };
      emitUpdate();
      return { ok: true, created: Boolean(r.created), id: session.saved_chat_id };
    },

    async file(tier, repoArg) {
      if (!rt.fileCard) return { ok: false, error: "there is nothing to file" };
      const t = Number(tier);
      if (!Number.isInteger(t) || t < 1 || t > 10) return { ok: false, error: "pick a tier 1-10" };
      let resolved = resolveTierFn(t);
      if (!resolved) { try { await loadTiersFn(); } catch { /* unchanged */ } resolved = resolveTierFn(t); }
      if (!resolved) return { ok: false, error: `tier ${t} has no (model, effort): the engine's tier map is not loaded (is the engine up?)` };
      const target = (typeof repoArg === "string" && repoArg) || chatRepo() || null;
      if (!target) return { ok: false, error: "All repos is selected and this chat has no repo: pick the repo to file in" };
      const names = await repoNamesFn();
      if (names && names.error) return { ok: false, error: names.error };
      const known = (names && names.repos) || [];
      if (!known.some((r) => String(r).toLowerCase() === target.toLowerCase())) return { ok: false, error: `${target} is not a target repo` };
      rt.fileBusy = true;
      rt.fileError = null;
      emitUpdate();
      const header = `Tier: ${t} (${resolved.model} ${resolved.effort}) — prescribed by the user; do not re-judge`;
      const r = await submitIntakeFn(`${header}\n\n${rt.fileCard.ask}`, target);
      rt.fileBusy = false;
      if (!r || !r.ok) {
        rt.fileError = (r && r.error) || "filing failed";
        emitUpdate();
        return { ok: false, error: rt.fileError };
      }
      const filedAsk = rt.fileCard.ask;
      rt.fileCard = null;
      rt.fileError = null;
      if (!session.conversation_key) session.conversation_key = crypto.randomUUID();
      session.filed.unshift({ intake_id: r.intakeId, tier: t, ask: filedAsk, at: clock(), conversation_key: session.conversation_key,
        ticket: Number.isInteger(r.ticket) ? r.ticket : null, ticket_url: Number.isInteger(r.ticket) && typeof r.ticketUrl === "string" ? r.ticketUrl : null,
        announced_at: null, ended_at: null });
      if (session.filed.length > FILED_LIMIT) session.filed.length = FILED_LIMIT;
      session.turns_since_filed = 0;
      save();
      emitUpdate();
      return { ok: true, intake_id: r.intakeId };
    },

    async tierState() { return tierState(); },
    async setTier(tier) {
      const t = Number(tier);
      if (!Number.isInteger(t) || t < 1 || t > 10) return { ok: false, error: `pick a tier 1-10, not ${JSON.stringify(tier)}` };
      if (!resolveTierFn(t)) { try { await loadTiersFn(); } catch { /* unchanged */ } }
      if (!resolveTierFn(t)) return { ok: false, error: `tier ${t} has no (model, effort): the engine's tier map is not loaded (is the engine up?)` };
      const r = st.saveTier(t);
      if (r.error) return { ok: false, error: r.error };
      emitUpdate();
      return { ok: true, ...tierState() };
    },
    async setRepo(r) {
      const next = r == null || r === "" ? null : String(r);
      if (next !== null && !REPO_RE.test(next)) return { ok: false, error: `not a repo: ${r}` };
      session.repo = next;
      save();
      emitUpdate();
      return { ok: true, repo: next };
    },

    handoff: async () => ({ error: OMITTED }),
    openInIterm: async () => ({ error: OMITTED }),
    restoreSaved: async () => ({ error: OMITTED }),
    font: async () => ({ family: null, size: null, source: null }),

    /** Sign out or the page going away: the pod session is let go of, not ended. */
    stop() { killChild("page closing", { detach: true }); },
    snapshot,
  };

  async function makeLive(target, why, notice) {
    killChild(why);
    archiveCurrentConversation(session);
    session.conversations = session.conversations.filter((c) => c !== target);
    session.history = target.messages;
    session.claude_session_id = null;
    session.runtime = target.runtime;
    session.remote_session_id = target.remote_session_id || null;
    session.saved_chat_id = target.saved_chat_id || null;
    session.conversation_key = target.conversation_key || null;
    session.cwd = null;
    session.repo = target.repo;
    rt.repoChangedFrom = undefined;
    session.seed = null;
    session.session_generation += 1;
    session.turns_since_filed = 0;
    capTotal(session);
    rt.recovery = null;
    rt.fileCard = null;
    rt.fileError = null;
    rt.drainRemote = false;
    rt.notice = notice;
    save();
    emitUpdate();
    try { await catchUp(); } catch { /* the next turn tries again */ }
    return { ok: true };
  }

  return api_;
}

module.exports = { createWebFirstMate, chatTitle, OMITTED, LOCAL_HOSTING_NOTICE, REMOTE_CANCEL_ROW, REMOTE_EXPIRED_ROW, INTERRUPTED_TEXT, PICK_REPO_NOTICE, DEFAULT_TIER };
