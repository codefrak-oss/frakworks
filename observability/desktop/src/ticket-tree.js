// ticket-tree.js — the main-process side of the explorer's "Tickets" branch.
//
// Read-only, and only through the engine: the open issues come from its
// issues board, `GET /issues` (engine/README.md, "The issues board"), which
// already joins each issue's flow, pending question and blockers on. The
// grouping is pure and lives in ticket-groups.js, which the renderer bundles;
// this file adds the engine reads and is main-process only.
const engineData = require("./engine-data");
const groups = require("./ticket-groups");
const { treeOf, bucketDone, DONE_PAGE, DONE_MAX } = groups;

/**
 * The open issues of `repo`, the repo switcher's choice, grouped: GET
 * /issues?repo= and { repo, groups }. With `repo` null (All repos) every
 * target repo's, as { repo: null, repos: [{ repo, groups }] }. Both carry
 * `proposals`, the board's proposal tickets (src/proposal-list.js). Resolves to
 * that or { error, groups: [] }; never rejects.
 */
async function fetchTicketTree({ repo = null, fetchIssues = engineData.fetchIssues } = {}) {
  try {
    const r = await fetchIssues(repo ? { repo } : {});
    if (r.error) return { error: `ticket tree failed: ${r.error}`, groups: [] };
    // The rows ride along, so the renderer can apply a `flow` frame's board row
    // in place and re-run this grouping (treeOf) without a read.
    return { ...treeOf(r.issues || [], repo), issues: r.issues || [] };
  } catch (e) {
    return { error: `ticket tree failed: ${e && e.message ? e.message : e}`, groups: [] };
  }
}

/**
 * The newest `limit` closed issues of `repo`, or of every target repo when it
 * is null (All repos), bucketed by when they closed. `more` says
 * whether a longer list could hold more. Resolves to { buckets, limit, more }
 * or { error, buckets: [] }; never rejects.
 */
async function fetchDoneTickets({ repo = null, limit = DONE_PAGE,
                                  fetchClosedIssues = engineData.fetchClosedIssues, now = Date.now() } = {}) {
  const n = Math.min(DONE_MAX, Math.max(DONE_PAGE, Math.floor(Number(limit) || DONE_PAGE)));
  try {
    const r = await fetchClosedIssues({ limit: n, ...(repo ? { repo } : {}) });
    if (r.error) return { error: `done tickets failed: ${r.error}`, buckets: [] };
    const issues = r.issues || [];
    return { repo: repo || null, buckets: bucketDone(issues, now), limit: n, more: issues.length >= n && n < DONE_MAX };
  } catch (e) {
    return { error: `done tickets failed: ${e && e.message ? e.message : e}`, buckets: [] };
  }
}

module.exports = { ...groups, fetchTicketTree, fetchDoneTickets };
