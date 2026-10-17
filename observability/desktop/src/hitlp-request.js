// hitlp-request.js — Utils > HITLP producer's pure parts (#1947): the form's
// fields and defaults per kind, the tool arguments built from a form, and the
// JSON-RPC bodies main's hitlp-client.js sends. Read against
// codefrak-oss/hitlp spec/hitlp.md and spec/schemas/ at HITLP 0.2 (hitlp#45,
// read 2026-10-12; #2489): §4.1 Ask, §4.2 Approve, §5 the envelope (0.2's
// `asserted` and `requiresHuman`; `attested` is the server's, filled from the
// requester's token, and 0.1's `requester` is gone), §7 the MCP binding. No
// React, no Node: the renderer bundles it and the tests require it as it is.
"use strict";

/** §7: the MCP revision a HITLP server speaks. */
const PROTOCOL_VERSION = "2026-07-28";
/** §7.1's two v1 tools. */
const TOOLS = { ask: "human.ask", approve: "human.approve" };
const DEFAULT_ON_TIMEOUT = ["reject", "escalate", "cancel", "fail"];
const PRIORITIES = ["low", "normal", "high", "urgent"];
/**
 * §4.1: a server supports at least string, number, integer, boolean, enum
 * (pick-one) and flat object schemas; one answer kind each.
 */
const ANSWER_KINDS = [
  { id: "text", label: "Text" },
  { id: "number", label: "Number" },
  { id: "integer", label: "Integer" },
  { id: "yes-no", label: "Yes / no" },
  { id: "choice", label: "Choice (pick one)" },
  { id: "object", label: "Object (JSON Schema)" },
];
const HOUR_MS = 60 * 60 * 1000;

/**
 * The form, field by field: `type` is how it is drawn (text, select,
 * datetime, json, list, boolean), `required` what the schema requires, `only`
 * the answer kind a field belongs to. §5's envelope first, then the kind's
 * body. `asserted.*` are the requester's own claims (0.2 §5): `why`,
 * `onBehalfOf`, a `recommendation` object (`verdict`, `confidence`, `by`,
 * `reasons`), a `lineage` array of `{ kind, claim, source, by }` links and
 * `asOf`; `requiresHuman` asks that no rule or model decide it.
 */
const ENVELOPE_FIELDS = [
  { name: "idempotencyKey", type: "text", required: true },
  { name: "deadline", type: "datetime", required: true },
  { name: "defaultOnTimeout", type: "select", required: true, choices: DEFAULT_ON_TIMEOUT },
  { name: "priority", type: "select", required: false, choices: PRIORITIES },
  { name: "requires", type: "json", required: false },
  { name: "context", type: "json", required: false },
  { name: "requiresHuman", type: "boolean", required: false },
  { name: "asserted.why", type: "text", required: false },
  { name: "asserted.onBehalfOf", type: "text", required: false },
  { name: "asserted.recommendation", type: "json", required: false },
  { name: "asserted.lineage", type: "json", required: false, array: true },
  { name: "asserted.asOf", type: "datetime", required: false },
];
const BODY_FIELDS = {
  ask: [
    { name: "question", type: "text", required: true },
    { name: "answerKind", type: "select", required: true, choices: ANSWER_KINDS.map((k) => k.id) },
    { name: "options", type: "list", required: false, only: "choice" },
    { name: "responseSchema", type: "json", required: true, only: "object" },
  ],
  approve: [
    { name: "action", type: "text", required: true },
    { name: "payload", type: "json", required: true },
    { name: "payloadDigest", type: "text", required: false },
    { name: "scope", type: "json", required: false },
  ],
};

/** The fields drawn for `kind` (and, for Ask, its answer kind). */
const formFields = (kind, answerKind) => [...ENVELOPE_FIELDS, ...(BODY_FIELDS[kind] || [])
  .filter((f) => !f.only || f.only === answerKind)];

const randomUuid = () => {
  const c = typeof globalThis !== "undefined" ? globalThis.crypto : null;
  if (c && typeof c.randomUUID === "function") return c.randomUUID();
  const h = () => Math.floor(Math.random() * 16).toString(16);
  return "xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx".replace(/x/g, h);
};

/** RFC 3339 in UTC without milliseconds, as the spec's examples write it. */
const rfc3339 = (ms) => new Date(ms).toISOString().replace(/\.\d{3}Z$/, "Z");
const pretty = (v) => JSON.stringify(v, null, 2);

/** §4.2's digest form: the canonical JSON (keys sorted, no spaces), as the reference server hashes it. */
const canonicalJson = (v) => {
  if (Array.isArray(v)) return `[${v.map(canonicalJson).join(",")}]`;
  if (v && typeof v === "object") {
    return `{${Object.keys(v).sort().filter((k) => v[k] !== undefined)
      .map((k) => `${JSON.stringify(k)}:${canonicalJson(v[k])}`).join(",")}}`;
  }
  return JSON.stringify(v);
};

// SHA-256 (FIPS 180-4) over a string's UTF-8 bytes: the renderer has no Node
// crypto and SubtleCrypto is async, and the digest follows each keystroke.
const K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
const sha256Hex = (str) => {
  const bytes = Array.from(new TextEncoder().encode(str));
  const bitLen = bytes.length * 8;
  bytes.push(0x80);
  while (bytes.length % 64 !== 56) bytes.push(0);
  for (let i = 7; i >= 0; i--) bytes.push(i >= 4 ? 0 : (bitLen >>> (i * 8)) & 0xff);
  const H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  const W = new Array(64);
  const rotr = (x, n) => (x >>> n) | (x << (32 - n));
  for (let o = 0; o < bytes.length; o += 64) {
    for (let t = 0; t < 16; t++) W[t] = (bytes[o + 4 * t] << 24) | (bytes[o + 4 * t + 1] << 16) | (bytes[o + 4 * t + 2] << 8) | bytes[o + 4 * t + 3];
    for (let t = 16; t < 64; t++) {
      const s0 = rotr(W[t - 15], 7) ^ rotr(W[t - 15], 18) ^ (W[t - 15] >>> 3);
      const s1 = rotr(W[t - 2], 17) ^ rotr(W[t - 2], 19) ^ (W[t - 2] >>> 10);
      W[t] = (W[t - 16] + s0 + W[t - 7] + s1) | 0;
    }
    let [a, b, c, d, e, f, g, h] = H;
    for (let t = 0; t < 64; t++) {
      const t1 = (h + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[t] + W[t]) | 0;
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
    H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
  }
  return H.map((x) => (x >>> 0).toString(16).padStart(8, "0")).join("");
};

/** `sha256:<hex>` of a payload's canonical JSON; null when the text is not a JSON object. */
const payloadDigest = (payloadText) => {
  let v;
  try { v = JSON.parse(payloadText); } catch (e) { return null; }
  if (!v || typeof v !== "object" || Array.isArray(v)) return null;
  return `sha256:${sha256Hex(canonicalJson(v))}`;
};

/**
 * A fresh form for `kind`: a new idempotency key, a deadline an hour on, the
 * default action `reject` (§5 recommends it for Approve; for Ask it ends the
 * task `completed` with outcome `timed_out`, so the record still says what
 * happened), the login as `asserted.onBehalfOf`, a placeholder `asserted.why`,
 * `requiresHuman` off and the rest of `asserted` blank. Approve's scope is
 * single use until the deadline, since R6 refuses a scope with no bounds by
 * default.
 */
const defaultForm = (kind, { login = null, now = Date.now(), uuid = randomUuid } = {}) => {
  const deadline = rfc3339(now + HOUR_MS);
  const base = {
    kind: kind === "approve" ? "approve" : "ask",
    idempotencyKey: uuid(),
    deadline,
    defaultOnTimeout: "reject",
    priority: "normal",
    requires: "",
    context: pretty({ summary: "Sent from the frakworks desktop app's HITLP producer (Utils > HITLP producer)." }),
    requiresHuman: false,
    "asserted.why": "A test request from the frakworks desktop app's HITLP producer.",
    "asserted.onBehalfOf": login || "",
    "asserted.recommendation": "",
    "asserted.lineage": "",
    "asserted.asOf": "",
  };
  if (base.kind === "ask") {
    return { ...base, question: "Should the test go ahead?", answerKind: "yes-no",
      options: [{ value: "yes", label: "Yes" }, { value: "no", label: "No" }],
      responseSchema: pretty({ type: "object", properties: { note: { type: "string" } }, required: ["note"] }) };
  }
  const payload = pretty({ command: "echo hello", target: "test" });
  return { ...base, action: "Run a test command", payload, payloadDigest: payloadDigest(payload) || "",
    scope: pretty({ maxUses: 1, notAfter: deadline }) };
};

/** §4.1: the `responseSchema` an answer kind stands for; `options` only matters for a choice. */
const responseSchemaOf = (answerKind, options = []) => {
  switch (answerKind) {
    case "text": return { type: "string", minLength: 1 };
    case "number": return { type: "number" };
    case "integer": return { type: "integer" };
    case "yes-no": return { type: "boolean" };
    case "choice": return { enum: options.map((o) => o.value) };
    default: return null;
  }
};

/** A JSON area's text: `undefined` when blank, the value, or an error naming the field; `array` wants a JSON array instead of an object. */
const jsonField = (form, name, { object = true, array = false } = {}) => {
  const text = String(form[name] ?? "").trim();
  if (!text) return { value: undefined };
  let v;
  try { v = JSON.parse(text); } catch (e) { return { error: `${name} is not JSON: ${e.message}` }; }
  if (array) { if (!Array.isArray(v)) return { error: `${name} is not a JSON array` }; return { value: v }; }
  if (object && (!v || typeof v !== "object" || Array.isArray(v))) return { error: `${name} is not a JSON object` };
  return { value: v };
};

/**
 * §7.1: the tool's arguments, the envelope and the body flattened into one
 * object, from a form: `{ tool, arguments }` or `{ error }`. Blank optional
 * fields are left out (`asserted` only with a set sub-field, `requiresHuman`
 * only when true, the spec's default being false); required ones are sent as
 * typed, so a server's own refusal is what the producer shows. Never a
 * `requester` (0.1) or an `attested` (the server fills it from the token and
 * must drop one sent).
 */
const toolArguments = (form) => {
  const f = form || {};
  const kind = f.kind === "approve" ? "approve" : "ask";
  const args = {
    idempotencyKey: String(f.idempotencyKey ?? ""),
    deadline: String(f.deadline ?? ""),
    defaultOnTimeout: String(f.defaultOnTimeout ?? ""),
  };
  if (f.priority) args.priority = String(f.priority);
  for (const name of ["requires", "context"]) {
    const r = jsonField(f, name);
    if (r.error) return { error: r.error };
    if (r.value !== undefined) args[name] = r.value;
  }
  if (f.requiresHuman === true || f.requiresHuman === "true") args.requiresHuman = true;
  const asserted = {};
  for (const k of ["why", "onBehalfOf", "asOf"]) {
    const v = String(f[`asserted.${k}`] ?? "").trim();
    if (v) asserted[k] = v;
  }
  const rec = jsonField(f, "asserted.recommendation");
  if (rec.error) return { error: rec.error };
  if (rec.value !== undefined) asserted.recommendation = rec.value;
  const lin = jsonField(f, "asserted.lineage", { array: true });
  if (lin.error) return { error: lin.error };
  if (lin.value !== undefined) asserted.lineage = lin.value;
  if (Object.keys(asserted).length) args.asserted = asserted;
  if (kind === "ask") {
    args.question = String(f.question ?? "");
    if (f.answerKind === "object") {
      const r = jsonField(f, "responseSchema");
      if (r.error) return { error: r.error };
      args.responseSchema = r.value === undefined ? {} : r.value;
    } else {
      const options = (Array.isArray(f.options) ? f.options : [])
        .filter((o) => o && String(o.value ?? "").trim() !== "")
        .map((o) => ({ value: String(o.value).trim(), label: String(o.label ?? "").trim() || String(o.value).trim() }));
      if (f.answerKind === "choice" && !options.length) return { error: "a choice needs at least one option" };
      args.responseSchema = responseSchemaOf(f.answerKind, options) || { type: "string" };
      if (f.answerKind === "choice") args.options = options;
    }
  } else {
    args.action = String(f.action ?? "");
    const p = jsonField(f, "payload");
    if (p.error) return { error: p.error };
    args.payload = p.value === undefined ? {} : p.value;
    const digest = String(f.payloadDigest ?? "").trim();
    if (digest) args.payloadDigest = digest;
    const s = jsonField(f, "scope");
    if (s.error) return { error: s.error };
    if (s.value !== undefined) args.scope = s.value;
  }
  return { tool: TOOLS[kind], arguments: args };
};

/** §7: the `initialize` the binding opens with. */
const initializeBody = (id = 1) => ({ jsonrpc: "2.0", id, method: "initialize", params: {
  protocolVersion: PROTOCOL_VERSION, capabilities: {}, clientInfo: { name: "frakworks-desktop-hitlp-producer", version: "1" } } });
const initializedNotification = () => ({ jsonrpc: "2.0", method: "notifications/initialized" });

/**
 * §7.1-7.2: the `tools/call`, task-augmented (the reference server refuses a
 * call without `task`), its TTL the time left to the deadline.
 */
const toolsCallBody = (tool, args, { id = 2, now = Date.now() } = {}) => {
  const left = Date.parse(args && args.deadline) - now;
  return { jsonrpc: "2.0", id, method: "tools/call", params: {
    name: tool, arguments: args, task: Number.isFinite(left) && left > 0 ? { ttl: left } : {} } };
};
/** §7.4: one poll of a task. */
const tasksGetBody = (taskId, { id = 2 } = {}) => ({ jsonrpc: "2.0", id, method: "tasks/get", params: { taskId: String(taskId) } });

/**
 * A response body's JSON: the body itself, or the JSON-RPC message in a
 * `text/event-stream` answer's `data:` lines; null when there is none.
 */
const parseResponse = (text) => {
  const t = String(text ?? "");
  try { return JSON.parse(t); } catch (e) { /* maybe SSE */ }
  const datas = t.split(/\r?\n/).filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trim());
  for (let i = datas.length - 1; i >= 0; i--) {
    try { const v = JSON.parse(datas[i]); if (v && v.jsonrpc) return v; } catch (e) { /* next */ }
  }
  return null;
};

/** §7.2: the task id in a `tools/call` (or `tasks/get`) answer, null when it carries none. */
const taskIdOf = (body) => {
  const msg = typeof body === "string" ? parseResponse(body) : body;
  const r = msg && msg.result;
  if (!r || typeof r !== "object") return null;
  const id = (r.task && r.task.taskId) || r.taskId;
  return id == null || id === "" ? null : String(id);
};

/** The Response pane's text: pretty JSON when the body is JSON, the text verbatim otherwise. */
const shownBody = (text) => {
  const t = String(text ?? "");
  try { return pretty(JSON.parse(t)); } catch (e) { return t; }
};

module.exports = { PROTOCOL_VERSION, TOOLS, DEFAULT_ON_TIMEOUT, PRIORITIES, ANSWER_KINDS, ENVELOPE_FIELDS, BODY_FIELDS,
  formFields, defaultForm, canonicalJson, sha256Hex, payloadDigest, responseSchemaOf, toolArguments,
  initializeBody, initializedNotification, toolsCallBody, tasksGetBody, parseResponse, taskIdOf, shownBody, rfc3339 };
