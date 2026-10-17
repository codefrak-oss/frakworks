// desktop-channel.js — the desktop's own long-held connection to the engine
// (#100105, engine/docs/desktop-channel.md), held in the main process while
// signed in: `GET /desktop/channel?allowed=<bool>` with the sign-in bearer, an
// SSE stream the engine opens with `{type: "hello", id}` and then sends
// `{type: "capture", requestId, pane}` on when First mate calls
// screenshot_desktop. Each capture is answered by
// `POST /desktop/capture/{requestId}` (`{image, mimeType}` or `{error}`); a
// change of the setting, and the window gaining focus, by
// `POST /desktop/channel/{id}/state`. Outbound only: no port, no new secret.
// Reconnects as src/engine-events.js does (1, 2, then 4 s; 45 s of silence is
// a dead connection). The capture itself is main's (`capture(pane)`).
"use strict";

const { parseSse, BACKOFF_MIN_MS, BACKOFF_MAX_MS, SILENCE_MS } = require("./engine-events");

/**
 * Options, injectable for tests: fetch, engineUrl(), headers() (the bearer;
 * empty while signed out, and then nothing connects), allowed() (the
 * setting), capture(pane) (→ `{ data: Buffer, mimeType }` or `{ error }`), log,
 * setTimeout.
 */
class DesktopChannel {
  constructor({ fetch, engineUrl, headers, allowed, capture, log, setTimeout: st } = {}) {
    this.fetchFn = fetch || ((...a) => globalThis.fetch(...a));
    this.engineUrl = engineUrl;
    this.headers = headers || (() => ({}));
    this.allowed = allowed || (() => false);
    this.capture = capture;
    this.log = log || ((line) => console.log(`[desktop-channel] ${line}`));
    this.setTimeoutFn = st || ((fn, ms) => { const t = setTimeout(fn, ms); if (t.unref) t.unref(); return t; });
    this.id = null;
    this.ctl = null;
    this.running = false;
    this.backoff = BACKOFF_MIN_MS;
  }

  start() {
    if (this.running) return;
    this.running = true;
    this._loop();
  }

  stop() {
    this.running = false;
    if (this.ctl) this.ctl.abort(new Error("stopped"));
  }

  /** Drops the connection so the next one carries a new bearer (sign-in, sign-out). */
  restart() { if (this.ctl) this.ctl.abort(new Error("restarted")); }

  async _loop() {
    while (this.running) {
      const auth = this.headers() || {};
      if (auth.authorization || auth.Authorization) await this._connectOnce(auth);
      if (!this.running) return;
      await new Promise((r) => this.setTimeoutFn(r, this.backoff));
      this.backoff = Math.min(this.backoff * 2, BACKOFF_MAX_MS);
    }
  }

  async _connectOnce(auth) {
    const ctl = new AbortController();
    this.ctl = ctl;
    let silence = null;
    const arm = () => { clearTimeout(silence); silence = setTimeout(() => ctl.abort(new Error("silent")), SILENCE_MS); if (silence.unref) silence.unref(); };
    try {
      const res = await this.fetchFn(`${this.engineUrl()}/desktop/channel?allowed=${this.allowed() === true}`,
        { signal: ctl.signal, headers: { ...auth, accept: "text/event-stream" } });
      if (!res.ok) throw new Error(`engine answered ${res.status}`);
      arm();
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let rest = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        const parsed = parseSse(rest + decoder.decode(value, { stream: true }));
        rest = parsed.rest;
        if (parsed.frames.length || parsed.comments) arm();
        for (const f of parsed.frames) {
          let data;
          try { data = JSON.parse(f.data); } catch { continue; }
          this._onFrame(data, auth);
        }
      }
    } catch (e) {
      if (this.running) this.log(`down: ${(ctl.signal.reason && ctl.signal.reason.message) || (e && e.message) || e}`);
    } finally {
      clearTimeout(silence);
      this.ctl = null;
      this.id = null;
    }
  }

  _onFrame(data, auth) {
    if (!data || typeof data !== "object") return;
    if (data.type === "hello") { this.id = data.id; this.backoff = BACKOFF_MIN_MS; this.log(`connected as ${data.id}`); return; }
    if (data.type === "capture" && typeof data.requestId === "string") this.answer(data.requestId, data.pane || null, auth);
  }

  /** One capture: refused with no capture while the setting is off. */
  async answer(requestId, pane, auth = this.headers()) {
    let body;
    if (this.allowed() !== true) body = { error: "the setting \"Let First mate screenshot this app\" is off" };
    else {
      let r;
      try { r = await this.capture(pane); } catch (e) { r = { error: (e && e.message) || String(e) }; }
      body = r.error ? { error: r.error } : { image: Buffer.from(r.data).toString("base64"), mimeType: r.mimeType };
    }
    this.log(`capture ${requestId} ${pane || "window"}: ${body.error ? `refused: ${body.error}` : body.mimeType}`);
    return this._post(`/desktop/capture/${encodeURIComponent(requestId)}`, body, auth);
  }

  /** Tells the engine the setting moved, or that the window has focus. */
  report(state) {
    if (!this.id) return Promise.resolve(null);
    return this._post(`/desktop/channel/${encodeURIComponent(this.id)}/state`, state, this.headers());
  }

  async _post(p, body, auth) {
    try {
      const res = await this.fetchFn(`${this.engineUrl()}${p}`, { method: "POST", headers: { ...(auth || {}), "content-type": "application/json" }, body: JSON.stringify(body) });
      return res.ok ? null : `engine answered ${res.status}`;
    } catch (e) { return (e && e.message) || String(e); }
  }
}

module.exports = { DesktopChannel };
