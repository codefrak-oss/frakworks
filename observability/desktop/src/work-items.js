// work-items.js — the desktop's client for the engine's work items
// (engine/docs/work-items.md, WorkItemController): GET /work-items?repo=,
// GET /work-items/{id}, POST /work-items/{id}/entries (a login's reply) and
// POST /work-items/import (a GitHub issue brought in by a trusted login). There
// is no default repo: a list or an import without one is refused before any
// request. Every call resolves to a value or { error }; never rejects.
"use strict";
const { getJson, sendJson } = require("./engine-data");

const REPO_FULL = /^(?!\.+\/)[\w.-]+\/(?!\.+$)[\w.-]+$/;
const ITEM_ID = /^[\w-]{1,64}$/;
const MAX_REPLY_CHARS = 20000;

const repoError = (repo, targets) => (typeof repo === "string" && REPO_FULL.test(repo) ? null
  : `repo is required: a target repo${Array.isArray(targets) && targets.length ? ` (${targets.join(", ")})` : ""}`);

/** GET /work-items?repo=: the repo's items newest first, without entries. Resolves to { items } or { error }. */
async function listWorkItems(repo, opts = {}) {
  const bad = repoError(repo, opts.targets);
  if (bad) return { error: bad };
  const r = await getJson(`/work-items?repo=${encodeURIComponent(repo)}`, opts);
  if (r.error) return { error: /^engine answered 404 /.test(r.error) ? "this engine has no /work-items (older than #2579)" : r.error };
  if (!Array.isArray(r.value)) return { error: "engine's /work-items is no list" };
  return { items: r.value, error: null };
}

/** GET /work-items/{id}: one item with its blockers and entries, untrusted text fenced by the engine. */
async function readWorkItem(id, opts = {}) {
  if (typeof id !== "string" || !ITEM_ID.test(id)) return { error: `not a work item id: ${JSON.stringify(id)}` };
  const r = await getJson(`/work-items/${encodeURIComponent(id)}`, opts);
  if (r.error) return { error: / 404 /.test(r.error) ? `the engine has no work item ${id}` : r.error };
  if (!r.value || typeof r.value !== "object") return { error: `engine's /work-items/${id} is no object` };
  return { item: r.value, error: null };
}

/** POST /work-items/{id}/entries with { body }: the signed-in login's reply. Resolves to { entry } or { error }. */
async function replyToWorkItem(id, text, opts = {}) {
  if (typeof id !== "string" || !ITEM_ID.test(id)) return { error: `not a work item id: ${JSON.stringify(id)}` };
  const body = typeof text === "string" ? text.trim() : "";
  if (!body) return { error: "a reply needs text" };
  if (body.length > MAX_REPLY_CHARS) return { error: `a reply is at most ${MAX_REPLY_CHARS} characters` };
  const r = await sendJson("POST", `/work-items/${encodeURIComponent(id)}/entries`, { body }, { ...opts, parse: true });
  if (r.error) return { error: r.error };
  return { entry: r.value, error: null };
}

/**
 * POST /work-items/import with { repo, issue }. A 404 means this engine has no
 * import API: { unavailable: true }; a 403 (not a trusted login) and every
 * other refusal come back as { error } with the engine's text.
 */
async function importIssue(repo, issue, opts = {}) {
  const bad = repoError(repo, opts.targets);
  if (bad) return { error: bad };
  const n = Number(issue);
  if (!Number.isInteger(n) || n < 1) return { error: `not an issue number: ${JSON.stringify(issue)}` };
  const r = await sendJson("POST", "/work-items/import", { repo, issue: n }, { ...opts, parse: true, keep: true });
  if (r.error) return r.status === 404 ? { unavailable: true, error: null } : { error: r.error, status: r.status };
  return { item: r.value, error: null };
}

/**
 * Whether the Import action can be offered: an empty POST /work-items/import
 * writes nothing (the engine refuses it, 400 for the missing repo or 403 for a
 * login that is not trusted); a 404 means the API is not there. Resolves to
 * { available, reason }, `reason` the button's tooltip when it is disabled.
 */
async function probeImport(opts = {}) {
  const r = await sendJson("POST", "/work-items/import", {}, { ...opts, keep: true });
  if (r.ok) return { available: true, reason: null };
  if (r.status === 404) return { available: false, reason: "this engine has no import API yet (POST /work-items/import answered 404)" };
  if (r.status === 403) return { available: false, reason: "only a trusted login can import an issue" };
  if (r.status === 401) return { available: false, reason: r.error };
  if (r.status === 400) return { available: true, reason: null };
  return { available: false, reason: r.error };
}

// A GitHub source ref is `owner/name#12` (GitHubWorkItemSource.sourceRef), maybe prefixed `github:`.
const GITHUB_REF = /^(?:github:)?([\w.-]+\/[\w.-]+)#(\d+)$/;

/**
 * The source badge of an item: { kind, label, href, parent }. `href` is the
 * GitHub issue for an import; `parent` the parent's #n for a split child.
 */
function sourceBadge(item) {
  const src = (item && item.source) || {};
  const ref = typeof src.ref === "string" ? src.ref : null;
  switch (src.kind) {
    case "ask": return { kind: "ask", label: "ask", href: null, parent: null };
    case "import": {
      const m = ref && GITHUB_REF.exec(ref);
      return m ? { kind: "import", label: `GitHub ${m[1]}#${m[2]}`, href: `https://github.com/${m[1]}/issues/${m[2]}`, parent: null }
        : { kind: "import", label: `imported${ref ? ` ${ref}` : ""}`, href: null, parent: null };
    }
    case "split-child": {
      const m = ref && /#?(\d+)$/.exec(ref);
      const parent = m ? Number(m[1]) : null;
      return { kind: "split-child", label: parent ? `split child of #${parent}` : `split child${ref ? ` of ${ref}` : ""}`, href: null, parent };
    }
    case "raised": return { kind: "raised", label: `raised by run${ref ? ` ${ref}` : ""}`, href: null, parent: null };
    default: return { kind: "unknown", label: src.kind ? String(src.kind) : "unknown source", href: null, parent: null };
  }
}

// The engine's fence (UntrustedFence): <<<UNTRUSTED <nonce> source=...>>> ... <<<END UNTRUSTED <nonce>>>.
const FENCE = /^<<<UNTRUSTED (\w+) source=([^\n]*)>>>\n([\s\S]*)\n<<<END UNTRUSTED \1>>>$/;

/** A text split into { untrusted, source, text }: a fenced body loses its fence lines and keeps its source. */
function unfence(body) {
  const s = typeof body === "string" ? body : "";
  const m = FENCE.exec(s);
  if (!m) return { untrusted: false, source: null, text: s };
  const inner = m[3].replace(/^The text below came from outside frakworks\.[^\n]*\n?/, "");
  return { untrusted: true, source: m[2], text: inner };
}

/** The item's entries, oldest first, each with `untrusted` set from the entry's flag or its fence. */
function fenceEntries(item) {
  const entries = Array.isArray(item && item.entries) ? item.entries : [];
  return entries.map((e) => {
    const u = unfence(e.body);
    return { id: e.id, kind: e.kind, author: e.author, createdAt: e.createdAt, untrusted: e.trusted === false || u.untrusted, source: u.source, text: u.text };
  });
}

/** An item as the view draws it: its source badge, and its description and entries unfenced with `untrusted` flags. */
function viewItem(item) {
  if (!item || typeof item !== "object") return item;
  const d = unfence(item.description);
  return { ...item, badge: sourceBadge(item), description: d.text, descriptionUntrusted: item.trusted === false || d.untrusted,
    descriptionSource: d.source, blockedBy: Array.isArray(item.blockedBy) ? item.blockedBy : [], conversation: fenceEntries(item) };
}

// The view's four calls, shaped by viewItem: main's IPC handlers and the web
// bridge (src/web/fleet-web.js) both answer with these.
async function viewList(repo, opts = {}) {
  const [r, importable] = await Promise.all([listWorkItems(repo, opts), probeImport(opts)]);
  return { ...r, items: r.items ? r.items.map(viewItem) : [], importable };
}
async function viewRead(id, opts = {}) {
  const r = await readWorkItem(id, opts);
  return r.item ? { ...r, item: viewItem(r.item) } : r;
}
async function viewImport(repo, issue, opts = {}) {
  const r = await importIssue(repo, issue, opts);
  return r.item ? { ...r, item: viewItem(r.item) } : r;
}

module.exports = { viewList, viewRead, viewImport, viewItem,  listWorkItems, readWorkItem, replyToWorkItem, importIssue, probeImport, sourceBadge, unfence, fenceEntries, MAX_REPLY_CHARS };
