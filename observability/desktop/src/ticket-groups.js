// ticket-groups.js — the explorer's "Tickets" branch grouped: the pure half of
// ticket-tree.js. The open issues come from the engine's issues board, `GET
// /issues` (engine/README.md, "The issues board"), which already joins each
// issue's flow, pending question and blockers on. The renderer bundles this
// file (esbuild, --format=iife), so nothing here may require a Node built-in or
// engine-data.js, directly or through another module: one did, and the desktop
// release build failed on it (node:tls, node:fs, 2026-10-07).
const { isProposalIssue } = require("./proposal-list");
const { sameRepo } = require("./repo-names");

// Order is the order the tree draws them in, and an issue lands in the first
// group that matches (groupOf).
const GROUPS = [
  { id: "needs-you", label: "Needs you" },
  { id: "in-progress", label: "In-Progress" },
  { id: "deferred", label: "Waiting on a blocker" },
  { id: "not-authorized", label: "Not authorized" },
  { id: "other", label: "Other open" },
];

const labelsOf = (issue) => (issue.labels || []).map((l) => (typeof l === "string" ? l : l && l.name)).filter(Boolean);

// One BoardIssue -> a GROUPS id. A flow stuck on an open Operaton incident
// (the board's `flow.incident`, in the flow or a process it called) needs
// the user: its ticket view has Fix and Propose a fix.
function groupOf(i) {
  if (i.pendingQuestion) return "needs-you";
  if (i.flow && i.flow.incident && !i.flow.ended) return "needs-you";
  if (i.flow && i.flow.startedAt && !i.flow.ended) return "in-progress";
  if (i.deferred) return "deferred";
  if (i.kind && !i.authorized) return "not-authorized";
  return "other";
}

// ---- the tree of related work (#571) --------
//
// A split feature's filed parts nest under the feature, from the board's
// `parent` / `children` (the engine's split state); the intake that filed a
// ticket is its provenance (`intake`), never a tree level; blocked-by stays a
// marker. Each family (a root and everything under it) sits once, in the
// group of its most urgent open member.

const GROUP_RANK = Object.fromEntries(GROUPS.map((g, n) => [g.id, n]));
const DONE_PARTS = new Set(["merged", "satisfied"]);
const isOpenState = (s) => String(s || "").toLowerCase() === "open";
const hasIncident = (i) => !!(i.flow && i.flow.incident && !i.flow.ended);

// One BoardIssue as a tree node, before its children are attached.
function nodeOf(i) {
  const intake = i.intake && i.intake.number != null && i.intake.number !== i.number
    ? { number: i.intake.number, url: i.intake.url || null } : null;
  return { number: i.number, title: i.title, url: i.url, labels: labelsOf(i),
    pitch: (i.flow && i.flow.pitch) || null, intake, group: groupOf(i),
    needsYou: i.pendingQuestion ? "question" : hasIncident(i) ? "incident" : null,
    blockers: [], partOf: null, children: [], partsDone: 0, partsTotal: 0, urgency: null };
}

/**
 * issues: BoardIssue[] (one repo's open board) -> the families, roots newest first:
 * `[{ number, title, url, labels, pitch, intake, group, needsYou, blockers, partOf,
 *     children, partsDone, partsTotal, urgency, family, size }]`.
 * `children` holds, in the split's order, open parts as nodes (with `index`) and the
 * parts that are no ticket on the board as rows `{ draft: "closed" | "pending" |
 * "dropped", index, title, number, url, prNumber, state }`. `partsDone/partsTotal`
 * is merged or satisfied over parts not dropped. `urgency` is the most urgent
 * descendant's state: "crit" (an incident), "warn" (needs the user otherwise), or
 * null. On a root: `family`, the group the whole family sits in, and `size`, its
 * open tickets. An issue whose parent is not on the board is a root with
 * `partOf: { number, closed: true }`. Every open issue appears exactly once.
 */
function buildForest(issues) {
  const list = (issues || []).filter((i) => i && i.number != null);
  const byNum = new Map();
  for (const i of list) if (!byNum.has(i.number)) byNum.set(i.number, i);
  // The edges: an open part points at its parent while that parent is on the board.
  const up = new Map();
  for (const i of byNum.values()) {
    const p = i.parent && i.parent.number;
    if (p != null && p !== i.number && byNum.has(p)) up.set(i.number, p);
  }
  const kidsOf = new Map();
  for (const [c, p] of up) kidsOf.set(p, [...(kidsOf.get(p) || []), c]);

  const seen = new Set();
  const build = (n) => {
    seen.add(n);
    const i = byNum.get(n);
    const node = nodeOf(i);
    const kids = new Set((kidsOf.get(n) || []).filter((k) => !seen.has(k)));
    const parts = [...(i.children || [])].sort((a, b) => (a.index ?? 0) - (b.index ?? 0));
    for (const c of parts) {
      if (c.state !== "dropped") node.partsTotal++;
      if (DONE_PARTS.has(c.state)) node.partsDone++;
      if (c.number != null && kids.has(c.number) && !seen.has(c.number)) {
        kids.delete(c.number);
        node.children.push({ ...build(c.number), index: c.index });
      } else if (DONE_PARTS.has(c.state) || c.state === "pending" || c.state === "dropped") {
        node.children.push({ draft: DONE_PARTS.has(c.state) ? "closed" : c.state, index: c.index, title: c.title,
          number: c.number ?? null, url: c.url ?? null, prNumber: c.prNumber ?? null, state: c.state });
      }
      // An `open` part that is not on the board (a listing that missed it) draws nothing.
    }
    // A part whose parent's split no longer names it still nests, after the named ones.
    for (const k of [...kids].sort((a, b) => a - b)) if (!seen.has(k)) node.children.push(build(k));
    const nested = new Set(node.children.filter((c) => !c.draft).map((c) => c.number));
    node.blockers = (i.blockedBy || []).filter((b) => isOpenState(b.state) && !nested.has(b.number)).map((b) => b.number);
    for (const c of node.children) {
      if (c.draft) continue;
      const u = c.needsYou === "incident" || c.urgency === "crit" ? "crit"
        : c.needsYou || c.urgency === "warn" ? "warn" : null;
      if (u === "crit" || (u === "warn" && node.urgency !== "crit")) node.urgency = u;
    }
    return node;
  };
  const rootOf = (i) => {
    const node = build(i.number);
    if (i.parent && i.parent.number != null && i.parent.number !== i.number && !byNum.has(i.parent.number))
      node.partOf = { number: i.parent.number, closed: true };
    return node;
  };

  const newest = [...byNum.values()].sort((a, b) => b.number - a.number);
  const roots = newest.filter((i) => !up.has(i.number)).map(rootOf);
  // A cycle in bad records has no root: cut it at its newest member.
  for (const i of newest) if (!seen.has(i.number)) roots.push(rootOf(i));
  for (const r of roots) {
    const members = [];
    const walk = (n) => { members.push(n); n.children.forEach((c) => !c.draft && walk(c)); };
    walk(r);
    r.size = members.length;
    r.family = members.map((m) => m.group).sort((a, b) => GROUP_RANK[a] - GROUP_RANK[b])[0];
  }
  return roots.sort((a, b) => b.number - a.number);
}

// issues: BoardIssue[] -> [{ id, label, tickets, count }]: `tickets` the families
// (buildForest's roots) that sit in the group, newest first; `count` its open
// tickets, not its families, so the Tickets total is unchanged by the nesting.
// `pitch` on a node is a proposal's pitch page (the board's `flow.pitch`), null for none.
function groupTickets(issues) {
  const by = Object.fromEntries(GROUPS.map((g) => [g.id, []]));
  for (const r of buildForest(issues)) by[r.family].push(r);
  return GROUPS.map((g) => ({ ...g, tickets: by[g.id], count: by[g.id].reduce((n, r) => n + r.size, 0) }));
}

/**
 * The tree of `issues` (board rows) for `repo`: `{ repo, groups, proposals }`,
 * or in All repos (`repo` null) `{ repo: null, repos: [{ repo, groups }],
 * groups: [], proposals }`. Pure: fetchTicketTree reads the board and calls
 * this; the renderer calls it again on a `flow` frame's row.
 */
function treeOf(issues, repo = null) {
  const rows = Array.isArray(issues) ? issues : [];
  // The board's proposal tickets as they are, for the Proposals branch (#660), so it reads no board of its own.
  const proposals = rows.filter((i) => isProposalIssue(i) && (!repo || sameRepo(i.repo, repo)));
  if (!repo) return { repo: null, repos: groupByRepo(rows), groups: [], proposals };
  return { repo, groups: groupTickets(rows.filter((i) => sameRepo(i.repo, repo))), proposals };
}

// issues of several repos -> [{ repo, groups }], one per repo in name order.
function groupByRepo(issues) {
  const repos = [...new Set((issues || []).map((i) => i.repo).filter((r) => typeof r === "string"))].sort();
  return repos.map((repo) => ({ repo, groups: groupTickets(issues.filter((i) => i.repo === repo)) }));
}

// ---- the "Done" group: closed issues, fetched only when it is opened --------

// The engine's closed read has no offset, so "more" asks again for a longer
// list; DONE_PAGE at a time, never past DONE_MAX (the engine's own cap).
const DONE_PAGE = 50;
const DONE_MAX = 500;
const DONE_BUCKETS = [
  { id: "today", label: "Today" },
  { id: "yesterday", label: "Yesterday" },
  { id: "week", label: "This week" },
  { id: "earlier", label: "Earlier" },
];

const DAY_MS = 24 * 60 * 60 * 1000;
// Local midnight, so "today" is the user's day, not UTC's.
const startOfDay = (ms) => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); };

/**
 * One closed issue as the tree draws it: `unfinished` when it was closed as not
 * planned (a cancel, a duplicate), and `pr` the pull request that closed it.
 */
// i is a ClosedBoardIssue: { repo, number, title, url, closedAt, stateReason, closedBy: [{ number, url, state }], parent: { number, url } | null };
// `parent` is the split feature it was a part of (Done's `part of #n`).
// `repo` falls back to the one its url names.
const repoOfUrl = (url) => {
  const m = /^https:\/\/github\.com\/([^/\s]+\/[^/\s]+)\/issues\/\d+/.exec(typeof url === "string" ? url : "");
  return m ? m[1] : null;
};
function doneTicket(i) {
  const pr = (i.closedBy || [])[0];
  return { repo: (typeof i.repo === "string" && i.repo) || repoOfUrl(i.url), number: i.number, title: i.title, url: i.url, closedAt: i.closedAt || null,
    unfinished: String(i.stateReason || "").toUpperCase() === "NOT_PLANNED", pr: pr ? pr.number : null,
    parent: (i.parent && i.parent.number) || null, body: typeof i.body === "string" ? i.body : "" };
}

// issues (newest first or not), now -> [{ id, label, tickets }], newest first in each; empty buckets kept.
function bucketDone(issues, now = Date.now()) {
  const today = startOfDay(now), yesterday = today - DAY_MS, week = today - 6 * DAY_MS;
  const by = Object.fromEntries(DONE_BUCKETS.map((b) => [b.id, []]));
  for (const t of (issues || []).map(doneTicket)) {
    const at = t.closedAt ? Date.parse(t.closedAt) : NaN;
    const b = !(at >= week) ? "earlier" : at >= today ? "today" : at >= yesterday ? "yesterday" : "week";
    by[b].push(t);
  }
  for (const b of DONE_BUCKETS) by[b.id].sort((x, y) => String(y.closedAt).localeCompare(String(x.closedAt)));
  return DONE_BUCKETS.map((b) => ({ ...b, tickets: by[b.id] }));
}

/**
 * `done` (fetchDoneTickets' answer) with `closed` (closed issues as the Done
 * read gives them) added to their buckets where it does not list them yet:
 * an issue closed on a `ticket` `closed` frame (#1065), which the closed
 * listing may not have caught up with. Only issues of `done.repo` (every repo
 * when null); `done` itself when nothing is added, an error or a load.
 */
function withClosed(done, closed, now = Date.now()) {
  if (!done || done.error || !Array.isArray(done.buckets) || !done.buckets.length) return done;
  const has = new Set(done.buckets.flatMap((b) => (b.tickets || []).map((t) => `${String(t.repo || done.repo || "").toLowerCase()}#${t.number}`)));
  const add = (closed || []).filter((i) => i && i.number != null && (!done.repo || sameRepo(i.repo, done.repo))
    && !has.has(`${String(i.repo || "").toLowerCase()}#${i.number}`));
  if (!add.length) return done;
  const extra = bucketDone(add, now);
  const buckets = done.buckets.map((b) => {
    const more = (extra.find((x) => x.id === b.id) || { tickets: [] }).tickets;
    if (!more.length) return b;
    return { ...b, tickets: [...more, ...b.tickets].sort((x, y) => String(y.closedAt).localeCompare(String(x.closedAt))) };
  });
  return { ...done, buckets };
}

module.exports = { GROUPS, groupOf, buildForest, groupTickets, groupByRepo, treeOf,
  DONE_PAGE, DONE_MAX, DONE_BUCKETS, doneTicket, bucketDone, withClosed };
