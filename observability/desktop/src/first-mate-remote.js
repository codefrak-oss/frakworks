// first-mate-remote.js — a First mate conversation in a session pod on the
// cluster (#2521, part 2 of #2503): when the engine's First mate hosting is
// `kubernetes`, src/first-mate.js builds a `RemoteSession` where it would
// build a `ClaudeChild`, and drives it the same way.
//
// `RemoteSession` has the `ClaudeChild` surface First mate uses (`start`,
// `write`, `kill`, `alive`, `sawResult`, events `event` and `exit`) over the
// engine's session API (engine/README.md, "First-mate sessions"):
//
//   * `start` POSTs `/first-mate/sessions {repo, ask, tier}` (or, given an id,
//     carries on with that session), emits the engine's `firstLine` as a
//     `system` row, and subscribes to the repo on main's event stream
//     (src/engine-events.js) for its lifetime.
//   * The stream is unauthenticated and carries every `first-mate` frame of
//     the repo, a phone's chats included: only a frame whose `data.sessionId`
//     is this session's is emitted, as `event` with the frame's `line`.
//   * `write` POSTs `/messages {text, images, clientId}`, the clientId a fresh
//     uuid per turn. The engine echoes the message as a `user` line with no
//     uuid; it is emitted with the turn's own uuid so the turn ledger takes it
//     as ours. The pod's claude never saw that uuid, so its `capabilities` and
//     `user_message_uuid(s)` are dropped and results are matched in order.
//   * A `resuming` line (the engine thawing a frozen session, every 2 s until
//     the new pod's first line) and a `pod-status` line are emitted as they
//     come, for the strip's `resuming…` state and its tooltip.
//   * `drainUntilEcho` (a session taken over after a cancel here): the pod
//     finishes the cancelled turn, so its `assistant` and `result` lines are
//     dropped until the next turn's `user` echo.
//   * `kill` DELETEs the session. A 404 or 409 (the session ended or is not
//     ours) emits `exit`; a 401 or 403 emits `exit` with `auth`.
//
// `api` is { start, send, end } (src/engine-data.js's startFirstMateSession,
// sendFirstMateMessage and endFirstMateSession), `bearer()` resolves to the
// sign-in bearer's `Authorization` value (the desktop's one engine token,
// #100103: no First mate token is minted or kept) or `{ error }`.
"use strict";

const { EventEmitter } = require("node:events");
const crypto = require("node:crypto");
const { SIGN_IN_PROMPT } = require("./engine-data");

const authError = (status) => status === 401 || status === 403;
const goneError = (status) => status === 404 || status === 409;

/** The text and images of a stream-json user message's `content` (a string, or image + text blocks). */
function splitContent(content) {
  if (typeof content === "string") return { text: content, images: [] };
  const blocks = Array.isArray(content) ? content : [];
  const text = blocks.filter((b) => b && b.type === "text").map((b) => b.text).join("\n");
  const images = blocks.filter((b) => b && b.type === "image" && b.source)
    .map((b) => ({ media_type: b.source.media_type, data: b.source.data }));
  return { text, images };
}

/** The pod's line as First mate reads it: no turn attribution the desktop could not match. */
function isToolResult(line) {
  const c = line.message && line.message.content;
  return Array.isArray(c) && c.some((b) => b && b.type === "tool_result");
}

function localLine(line) {
  if (!line || typeof line !== "object") return line;
  const out = { ...line };
  delete out.user_message_uuid;
  delete out.user_message_uuids;
  if (out.type === "system" && out.subtype === "init") delete out.capabilities;
  return out;
}

class RemoteSession extends EventEmitter {
  constructor({ repo, ask, tier, sessionId, api, bearer, engineEvents, log, drainUntilEcho = false } = {}) {
    super();
    this.repo = repo;
    this.ask = ask || "";
    this.tier = tier;
    this.id = sessionId || null;
    this.api = api;
    this.bearerFn = bearer || (async () => null);
    this.engineEvents = engineEvents || null;
    this.log = log || (() => {});
    this.sawResult = false;
    this.exited = false;
    this.started = false;
    // The clientIds written whose `user` echo has not come back, oldest first.
    this.echoes = [];
    this.draining = Boolean(drainUntilEcho);
    this._onFrame = (f) => this._frame(f);
  }

  get alive() { return this.started && !this.exited; }

  start() {
    this.started = true;
    if (this.engineEvents) {
      this.engineEvents.on("frame", this._onFrame);
      if (this.repo && typeof this.engineEvents.subscribe === "function") this.engineEvents.subscribe(this.repo);
    }
    this.ready = this.id ? Promise.resolve(true) : this._create();
    return this;
  }

  async _bearer() {
    let b;
    try { b = await this.bearerFn(); } catch (e) { b = { error: (e && e.message) || String(e) }; }
    if (b && typeof b === "object" && b.error) { this._exit({ error: b.error, auth: true }); return undefined; }
    return typeof b === "string" ? b : null;
  }

  async _create() {
    const bearer = await this._bearer();
    if (bearer === undefined || this.exited) return false;
    const r = await this.api.start(this.repo, this.ask, this.tier, bearer);
    if (this.exited) return false;
    if (!r || r.error) { this._fail(r || { error: "no answer from the engine" }, "start a First mate session"); return false; }
    this.id = r.session.id;
    this.emit("session", { id: this.id });
    if (typeof r.session.firstLine === "string" && r.session.firstLine) {
      this.emit("event", { type: "system", subtype: "first_line", text: r.session.firstLine });
    }
    return true;
  }

  _frame(f) {
    if (this.exited || !f || f.type !== "first-mate") return;
    const d = f.data;
    if (!d || !this.id || d.sessionId !== this.id) return;
    const line = d.line;
    if (!line || typeof line !== "object") return;
    if (line.type === "user") {
      // A tool_result line claude writes after a tool call is not an echo:
      // taken as one, it queued a stray turn on the ledger and the next
      // turn's result was then refused, leaving the pane busy.
      if (isToolResult(line)) return;
      // The engine's echo of a message posted here: the turn's own uuid.
      const uuid = this.echoes.shift();
      this.draining = false;
      this.emit("event", { type: "user", uuid: uuid || null });
      return;
    }
    if (this.draining && (line.type === "assistant" || line.type === "result")) return;
    if (line.type === "result") this.sawResult = true;
    this.emit("event", localLine(line));
  }

  /** A stream-json user message `{ type: "user", uuid, message: { content } }`, posted; false once ended. */
  write(obj) {
    if (!this.alive) return false;
    const uuid = (obj && obj.uuid) || crypto.randomUUID();
    const { text, images } = splitContent(obj && obj.message ? obj.message.content : "");
    this.echoes.push(uuid);
    (async () => {
      const ok = await this.ready;
      if (!ok || this.exited) return;
      const bearer = await this._bearer();
      if (bearer === undefined || this.exited) return;
      const r = await this.api.send(this.id, text, images, uuid, bearer);
      if (r && r.error) this._fail(r, "send to the First mate session");
    })();
    return true;
  }

  _fail(r, what) {
    const status = r.status || null;
    if (authError(status)) {
      this._exit({ error: `the engine refused the sign-in token (${status}): sign in again under Config > frakworks > Sign in`, auth: true, status });
    } else if (goneError(status)) {
      this._exit({ error: `the session ${this.id || ""} is gone (${status})`.replace("  ", " "), gone: true, status });
    } else {
      this._exit({ error: `could not ${what}: ${r.error}`, status });
    }
  }

  _exit(info) {
    if (this.exited) return;
    this._detach();
    this.emit("exit", { code: null, signal: null, ...info });
  }

  _detach() {
    this.exited = true;
    if (this.engineEvents) {
      this.engineEvents.removeListener("frame", this._onFrame);
      if (this.repo && typeof this.engineEvents.unsubscribe === "function") this.engineEvents.unsubscribe(this.repo);
    }
  }

  /** Lets go of the session (the app closing) and leaves it on the engine. */
  detach() { if (!this.exited) this._detach(); }

  /** Ends the session on the engine, best effort; no `exit` is emitted for it. */
  kill() {
    if (this.exited) return;
    this._detach();
    const id = this.id;
    (async () => {
      const ok = await this.ready;
      const sid = this.id || id;
      if (!ok || !sid) return;
      let bearer = null;
      try { bearer = await this.bearerFn(); } catch { bearer = null; }
      const r = await this.api.end(sid, typeof bearer === "string" ? bearer : null);
      if (r && r.error) this.log(`[first-mate] ending session ${sid}: ${r.error}`);
    })().catch(() => {});
  }
}

module.exports = { RemoteSession, splitContent, localLine };
