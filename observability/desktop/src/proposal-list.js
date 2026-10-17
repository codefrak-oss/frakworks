// proposal-list.js — the explorer's Proposals branch (#660): one row per
// proposal, merged from what the engine already serves.
//
//   - the docs under `docs/proposals/` on each repo's default branch, parsed by
//     the engine (`GET /proposals`: title, `Status:`, `Decision log:`,
//     `Ticket:`, the build ticket its log filed, the sibling pitch page);
//   - the board's open proposal tickets (`GET /issues`: `kind: proposal`, or a
//     `flow.flow` of `proposal`, with `flow.stage`, `flow.pitch`, `flow.prUrl`);
//   - the `decision` Needs (`GET /needs` through src/needs-you.js toRows).
//
// A doc and an open ticket its `Ticket:` names are one row; a running flow's
// step wins over the doc's `Status:`, and a waiting `decision` Need over both.
// Pure: no IPC, no DOM, so the branch and the view are testable on fixtures.

// The proposal flow's steps (engine bpmn/proposal.bpmn), by what the user reads.
const STAGES = {
  "call-draft": "drafting",
  "task-post-question": "drafting",
  "user-task-answer-question": "drafting",
  "call-review": "in review",
  "task-open-pr": "in review",
  "task-post-decision": "awaiting decision",
  "user-task-answer-decision": "awaiting decision",
  "task-read-decision": "deciding",
  "task-file-build": "deciding",
  "task-log-decision": "deciding",
  "task-rebase-pr": "deciding",
  "task-push-catch-up": "deciding",
  "task-merge-pr": "deciding",
  "task-start-build": "deciding",
  "task-close-ticket": "deciding",
};

// A doc's `Status:` values (CLAUDE.md "A proposal says its latest decision").
const DOC_STATUSES = ["proposed", "accepted", "shelved", "rejected", "built"];

// The order rows are listed in: what waits on the user first, then what is in
// its flow, then by the doc's status; newest ticket first within each.
const ORDER = ["awaiting decision", "drafting", "in review", "deciding", "in flow", "not started",
  "proposed", "accepted", "built", "shelved", "rejected", "unknown"];

/** A running proposal flow's step as the branch shows it, or null for a flow that is not running. */
const stageLabel = (flow) => {
  if (!flow || flow.ended || flow.flow !== "proposal") return null;
  return STAGES[flow.stage] || "in flow";
};

/** A row's id: `owner/name#n` for a proposal with a ticket (a ticket's id, #577), `owner/name:<slug>` for a doc with none. */
const proposalId = (repo, number, slug) => (number != null ? `${repo}#${Number(number)}` : `${repo}:${slug}`);

/** `proposalId`'s parts back: `{ repo, number }` or `{ repo, slug }`, else null. */
const parseProposalId = (id) => {
  const s = String(id == null ? "" : id);
  let m = /^([\w.-]+\/[\w.-]+)#(\d+)$/.exec(s);
  if (m) return { repo: m[1], number: Number(m[2]), slug: null };
  m = /^([\w.-]+\/[\w.-]+):([A-Za-z0-9_-][A-Za-z0-9._-]*)$/.exec(s);
  return m ? { repo: m[1], number: null, slug: m[2] } : null;
};

/** A board row that is a proposal ticket. */
const isProposalIssue = (i) => !!(i && (i.kind === "proposal" || (i.flow && i.flow.flow === "proposal")));

const rank = (status) => { const i = ORDER.indexOf(status); return i < 0 ? ORDER.length : i; };

/**
 * The rows: `docs` is fetchProposals' `docs`, `board` fetchIssues' `issues`,
 * `needs` src/needs-you.js toRows' rows (`{ repo, ticket, kind, actionId }`).
 * Each row is `{ id, repo, number, slug, title, status, statusSource
 * ("decision" | "flow" | "doc" | "ticket"), url, prUrl, pitch, docPath,
 * decisionLog, buildTicket, decision ({ actionId, ask } | null), onBoard }`.
 */
const toProposalRows = ({ docs = [], board = [], needs = [] } = {}) => {
  const rows = new Map();
  const blank = (repo, number, slug) => ({
    id: proposalId(repo, number, slug), repo, number: number != null ? Number(number) : null, slug: slug || null,
    title: "", status: null, statusSource: null,
    url: number != null ? `https://github.com/${repo}/issues/${Number(number)}` : null,
    prUrl: null, pitch: null, docPath: null, decisionLog: [], buildTicket: null, decision: null, onBoard: false, flow: null,
  });
  const at = (repo, number, slug) => {
    const id = proposalId(repo, number, slug);
    if (!rows.has(id)) rows.set(id, blank(repo, number, slug));
    return rows.get(id);
  };
  for (const i of Array.isArray(board) ? board : []) {
    if (!isProposalIssue(i) || typeof i.repo !== "string" || !Number.isInteger(i.number)) continue;
    const r = at(i.repo, i.number, null);
    const f = i.flow || null;
    Object.assign(r, { title: i.title || r.title, url: i.url || r.url, onBoard: true, flow: f,
      prUrl: (f && f.prUrl) || r.prUrl, pitch: (f && f.pitch) || r.pitch });
  }
  for (const d of Array.isArray(docs) ? docs : []) {
    if (!d || typeof d.repo !== "string" || typeof d.slug !== "string") continue;
    const own = Number.isInteger(d.ticket) ? proposalId(d.repo, d.ticket, null) : null;
    // A second doc naming the same ticket keeps a row of its own, by slug.
    const taken = own && rows.has(own) && rows.get(own).docPath;
    const r = own && !taken ? at(d.repo, d.ticket, null) : at(d.repo, null, d.slug);
    Object.assign(r, { slug: d.slug, docPath: d.path || `docs/proposals/${d.slug}.md`, title: r.title || d.title || d.slug,
      decisionLog: Array.isArray(d.decisionLog) ? d.decisionLog.map(String) : [],
      buildTicket: Number.isInteger(d.buildTicket) ? d.buildTicket : null,
      docStatus: DOC_STATUSES.includes(d.status) ? d.status : null,
      pitch: r.pitch || d.pitch || null });
  }
  for (const n of Array.isArray(needs) ? needs : []) {
    if (!n || n.kind !== "decision" || typeof n.repo !== "string" || !Number.isInteger(n.ticket) || !n.actionId) continue;
    const r = at(n.repo, n.ticket, null);
    r.decision = { actionId: n.actionId, ask: n.ask || "" };
    if (!r.title) r.title = n.title || "";
  }
  const out = [...rows.values()].map((r) => {
    const stage = stageLabel(r.flow);
    const [status, statusSource] = r.decision ? ["awaiting decision", "decision"]
      : stage ? [stage, "flow"]
      : r.docPath ? [r.docStatus || "unknown", "doc"]
      : ["not started", "ticket"];
    const { flow, docStatus, ...rest } = r;
    return { ...rest, title: r.title || (r.number != null ? `#${r.number}` : r.slug), status, statusSource };
  });
  return out.sort((a, b) => rank(a.status) - rank(b.status)
    || (b.number ?? -1) - (a.number ?? -1)
    || String(a.repo).localeCompare(String(b.repo)) || String(a.slug || "").localeCompare(String(b.slug || "")));
};

/** The rows by repo, in the order the repos first appear in `repos` (the target repos) and then in the rows. */
const groupByRepo = (rows, repos = []) => {
  const order = [...(repos || []), ...rows.map((r) => r.repo)].filter((r, i, all) => all.indexOf(r) === i);
  return order.map((repo) => ({ repo, rows: rows.filter((r) => r.repo === repo) })).filter((g) => g.rows.length);
};

/** The row `id` names, or null. */
const findProposal = (rows, id) => (Array.isArray(rows) ? rows : []).find((r) => r.id === String(id)) || null;

/**
 * The proposal row a ticket is, for a link from the Issues view, the Tickets
 * tree or Needs you: the id of the row for `repo`#`number`, or null when the
 * ticket is no proposal the branch lists.
 */
const proposalIdOfTicket = (rows, repo, number) => {
  const id = proposalId(repo, number, null);
  return findProposal(rows, id) ? id : null;
};

module.exports = { STAGES, DOC_STATUSES, ORDER, stageLabel, proposalId, parseProposalId, isProposalIssue,
  toProposalRows, groupByRepo, findProposal, proposalIdOfTicket };
