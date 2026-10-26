// ticket-data.js — the main-process side of the second IPC call, the one the
// ticket detail view makes.
//
// This never rejects: every failure becomes a string on
// the document it returns, so the detail view always has something to draw and
// a dead backend shows up as the view's `.err` block rather than a blank
// pane.
//
// The issue, its comments, the PRs that close it and GitHub's rendered HTML all
// come from one engine call, GET /ticket/{owner}/{repo}/{n}
// (src/engine-data.js `fetchTicketDetail`, #239). The app no longer runs `gh`
// for the ticket; nothing here spawns a process. The lifecycle/cost/runs
// history lives in the Timeline widget (src/renderer/timeline.jsx, #101), fed
// by the engine's `/ticket/{owner}/{repo}/{n}/timeline` (#91).
//
// `postLogs`, `parseLines` and `timeoutOf` stay here for
// src/transcript-data.js's VictoriaLogs queries. The VictoriaLogs base URL is
// the caller's (`logs_url` in agent-army.config.json, src/deployment-config.js);
// with none, postLogs rejects naming the missing key.
//
// The ticket's repo is always the caller's: a ticket is `owner/name#n`
// everywhere in the app, so there is no
// default repo here.
//
// Environment:
//   TICKET_DATA_TIMEOUT_SECS  per-request timeout for postLogs (default 10)
"use strict";

const http = require("node:http");
const https = require("node:https");
const crypto = require("node:crypto");
const { fetchTicketDetail } = require("./engine-data");

const DEFAULT_TIMEOUT_MS = 10_000;

const LOGS_URL_MISSING = "`logs_url` is not set in agent-army.config.json";
const timeoutOf = (env = process.env) =>
  Number(env.TICKET_DATA_TIMEOUT_SECS || DEFAULT_TIMEOUT_MS / 1000) * 1000;

// --- transport --------------------------------------------------------------

// POST `query=<expr>` to a VictoriaLogs endpoint and resolve to the raw body.
// Rejects on transport failure; every caller catches.
function postLogs({ base, path, query, timeoutMs = DEFAULT_TIMEOUT_MS }) {
  return new Promise((resolve, reject) => {
    if (!base) return reject(new Error(LOGS_URL_MISSING));
    let url;
    try { url = new URL(path, base.endsWith("/") ? base : base + "/"); }
    catch (e) { return reject(new Error(`bad logs_url ${base}: ${e.message}`)); }
    const body = new URLSearchParams({ query }).toString();
    const lib = url.protocol === "https:" ? https : http;
    const req = lib.request(url, {
      method: "POST",
      headers: {
        "content-type": "application/x-www-form-urlencoded",
        "content-length": Buffer.byteLength(body),
      },
      timeout: timeoutMs,
    }, (res) => {
      let out = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { out += c; });
      res.on("end", () => {
        if (res.statusCode !== 200) return reject(new Error(`VictoriaLogs HTTP ${res.statusCode}`));
        resolve(out);
      });
    });
    req.on("timeout", () => { req.destroy(new Error(`VictoriaLogs timed out after ${Math.round(timeoutMs / 1000)}s`)); });
    req.on("error", reject);
    req.end(body);
  });
}

// --- parsing ----------------------------------------------------------------

// /select/logsql/query streams one JSON object per line — still used by
// transcript-data.js's own queries.
const parseLines = (body) =>
  body.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => JSON.parse(l));

// --- identity ---------------------------------------------------------------

// `node_id` is GitHub's node id, added for the ticket pins of stage 3 (#1337):
// a pin stores it so that an issue transferred to another repository — which
// keeps its number and changes its node id — is detected as an identity change
// rather than silently followed. The detail view ignores it.
//
// Comment identity is the other stage-3 addition. `commentIdOf` prefers the
// comment's id and falls back to its url so the pin has a stable handle either
// way, and `commentRevisionOf` hashes the body so an *edit* — which may not
// move any timestamp — still reads as a new revision.
const commentIdOf = (c) => String((c && (c.id || c.url)) || "");

const commentRevisionOf = (c) => {
  const at = (c && (c.updatedAt || c.lastEditedAt || c.createdAt)) || "";
  const h = crypto.createHash("sha256")
    .update(`${at}\n${(c && c.body) || ""}`).digest("hex").slice(0, 12);
  return `${at || "0"}:${h}`;
};

// --- the document -----------------------------------------------------------

// The `owner/name` of a GitHub issue URL, or null. The engine's GitHub read
// follows a moved repo, so `issue.url` names the repo the issue lives in now,
// which is the name the engine recorded as `repo` on the ticket's process
// instances. The repo asked for (a link, an old pin) may still use the old name: the issue
// read follows the move, the timeline lookup does not (#355).
const repoOfIssueUrl = (url) => {
  const m = /^https:\/\/github\.com\/([^/\s]+)\/([^/\s]+)\/issues\/\d+\/?$/.exec(typeof url === "string" ? url : "");
  return m ? `${m[1]}/${m[2]}` : null;
};

// The engine's ticket (fetchTicketDetail's `ticket`) onto the document the
// detail view (src/renderer/ticket.jsx) and the pins read. Labels and comment
// authors are plain strings on both sides.
function toDocument(t, repo, n) {
  const issue = {
    node_id: t.nodeId || null,
    number: t.number,
    title: t.title || "",
    body: t.body || "",
    state: t.state || "",
    url: t.url || `https://github.com/${repo}/issues/${n}`,
    labels: (t.labels || []).map((l) => (l && typeof l === "object" ? l.name : l)).filter((l) => typeof l === "string"),
    // The engine's story points (engine.handled_issue.points, bots/story-points.md); null when it has none.
    points: Number.isInteger(t.points) ? t.points : null,
    createdAt: t.createdAt || null,
    closedAt: t.closedAt || null,
    // Who asked for it (the engine's `engine.handled_issue.asked_by`), null when the engine has not seen it.
    askedBy: typeof t.askedBy === "string" && t.askedBy ? t.askedBy : null,
    // What the merged work changed (engine.implementation_summary), null unless the engine
    // serves it: only for a ticket closed with a merged PR.
    implementationSummary: typeof t.implementationSummary === "string" && t.implementationSummary.trim()
      ? t.implementationSummary.trim() : null,
    comments: (t.comments || []).map((c) => ({
      id: commentIdOf(c),
      revision: commentRevisionOf(c),
      author: typeof c.author === "string" ? c.author : ((c.author && c.author.login) || ""),
      createdAt: c.createdAt || null,
      updatedAt: c.updatedAt || c.createdAt || null,
      body: c.body || "",
      url: c.url || "",
    })),
  };
  const prs = (t.prs || []).map((p) => ({
    number: p.number,
    title: p.title || "",
    url: p.url || "",
    state: p.isDraft && p.state === "OPEN" ? "DRAFT" : (p.state || ""),
    review: p.reviewDecision || null,
    mergeSha: typeof p.mergeCommitSha === "string" && p.mergeCommitSha ? p.mergeCommitSha : null,
  }));
  const commentHTML = {};
  for (const c of t.comments || []) {
    if (c && c.url && c.bodyHTML) commentHTML[c.url] = c.bodyHTML;
  }
  return { issue, prs, bodyHTML: t.bodyHTML || null, commentHTML };
}

// `repo` is the repo asked for; `issue_repo` is the one GitHub says the issue
// is in (repoOfIssueUrl), null until the read succeeds. Engine-history reads
// (the Timeline) go by `issue_repo`.
//
// Never rejects. One engine call answers the issue and its PRs together, so a
// failure sets both `issue_error` and `prs_error`. A bad ticket number or a
// missing or bad repo never reaches the engine.
async function fetchTicketData(ticket, {
  repo = null, env = process.env, fetchDetail = fetchTicketDetail,
  now = () => Math.floor(Date.now() / 1000),
} = {}) {
  const n = Number(ticket);
  const doc = {
    ticket: n, repo, issue_repo: null, now: now(),
    issue: null, issue_error: null,
    prs: [], prs_error: null,
    body_html: null, comment_html: {},
  };
  const fail = (why) => { doc.issue_error = doc.prs_error = why; return doc; };
  if (ticket === null || ticket === "" || typeof ticket === "boolean" || !Number.isInteger(n) || n <= 0) {
    return fail(`not a ticket number: ${JSON.stringify(ticket)}`);
  }
  const parts = typeof repo === "string" ? repo.split("/") : [];
  if (parts.length !== 2 || !parts[0] || !parts[1]) return fail(`not a repo: ${JSON.stringify(repo)}`);
  let r;
  try { r = await fetchDetail(parts[0], parts[1], n, { env }); }
  catch (e) { r = { error: (e && e.message) || String(e) }; }
  if (!r || r.error || !r.ticket) return fail(`ticket lookup failed: ${(r && r.error) || "no ticket"}`);
  const m = toDocument(r.ticket, repo, n);
  doc.issue = m.issue;
  doc.issue_repo = repoOfIssueUrl(m.issue.url) || repo;
  doc.prs = m.prs;
  doc.body_html = m.bodyHTML;
  doc.comment_html = m.commentHTML;
  doc.now = now();
  return doc;
}

module.exports = {
  fetchTicketData, toDocument, repoOfIssueUrl,
  commentIdOf, commentRevisionOf,
  postLogs, parseLines,
  timeoutOf, LOGS_URL_MISSING, DEFAULT_TIMEOUT_MS,
};
