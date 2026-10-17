// hitlp-client.js — Utils > HITLP producer's MCP client in main (#1947): one
// `hitlp:call` is an `initialize` (protocol 2026-07-28, spec/hitlp.md §7), the
// `notifications/initialized` and then the `tools/call` or `tasks/get`, each a
// JSON-RPC POST over streamable HTTP to the URL the user typed. The server is
// any HITLP server, the user's own machine included, so it is not reached
// through the engine. Never rejects: a refusal, a non-JSON body or a transport
// error comes back as `{ request, status, body }` or `{ request, error }`.
// `headers` (main's) are sent on each POST: the requester token the producer's
// page took, as `Authorization: Bearer` (#2489); never the app's own
// credentials (its engine sign-in or the HITLP surface credential).
"use strict";

const { PROTOCOL_VERSION, initializeBody, initializedNotification, toolsCallBody, tasksGetBody } = require("./hitlp-request");

const TIMEOUT_MS = 20000;
const MAX_BODY_CHARS = 512 * 1024;

const badUrl = (url) => {
  let u;
  try { u = new URL(String(url || "")); } catch (e) { return "the server is not a URL"; }
  return u.protocol === "http:" || u.protocol === "https:" ? null : "the server URL must be http or https";
};

/**
 * One POST of `body`: `{ status, body, sessionId }` (the text cut at 512 KiB)
 * or `{ error }`. A `text/event-stream` answer is read to its end, as the
 * streamable HTTP transport closes it after the response.
 */
async function post(url, body, { fetchImpl = fetch, sessionId = null, timeoutMs = TIMEOUT_MS, headers: extra = null } = {}) {
  const headers = { ...(extra || {}), "content-type": "application/json", accept: "application/json, text/event-stream", "mcp-protocol-version": PROTOCOL_VERSION };
  if (sessionId) headers["mcp-session-id"] = sessionId;
  try {
    const res = await fetchImpl(url, { method: "POST", headers, body: JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs) });
    const text = await res.text();
    const sid = res.headers && typeof res.headers.get === "function" ? res.headers.get("mcp-session-id") : null;
    return { status: res.status, body: text.length > MAX_BODY_CHARS ? text.slice(0, MAX_BODY_CHARS) : text, sessionId: sid || sessionId };
  } catch (e) {
    return { error: String((e && e.cause && e.cause.message) || (e && e.message) || e) };
  }
}

/**
 * `op` is `{ tool, arguments }` for a `tools/call` or `{ taskId }` for a
 * `tasks/get`. Answers `{ request, status, body }`, the request the exact body
 * sent; `{ request, error }` when it never got an answer; `{ request: null,
 * error }` for a bad URL or op. A failed `initialize` is the answer itself, so
 * the user sees why the call was not sent.
 */
async function callHitlp(url, op, opts = {}) {
  const bad = badUrl(url);
  if (bad) return { request: null, error: bad };
  const o = op || {};
  let request;
  if (o.taskId != null && o.taskId !== "") request = tasksGetBody(o.taskId);
  else if (typeof o.tool === "string" && /^human\.(ask|approve)$/.test(o.tool) && o.arguments && typeof o.arguments === "object") {
    request = toolsCallBody(o.tool, o.arguments, { now: opts.now ?? Date.now() });
  } else return { request: null, error: "nothing to send: a human.ask or human.approve call, or a task id" };
  const init = await post(url, initializeBody(), opts);
  if (init.error) return { request: initializeBody(), error: `initialize: ${init.error}` };
  if (init.status < 200 || init.status >= 300) return { request: initializeBody(), status: init.status, body: init.body, step: "initialize" };
  const sessionId = init.sessionId;
  // A notification is answered 202 with no body; its failure is the call's to show.
  await post(url, initializedNotification(), { ...opts, sessionId });
  const r = await post(url, request, { ...opts, sessionId });
  if (r.error) return { request, error: r.error };
  return { request, status: r.status, body: r.body };
}

module.exports = { callHitlp, badUrl, TIMEOUT_MS, MAX_BODY_CHARS };
