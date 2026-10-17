// engine-update-changes.js — what an engine update brings (#1398): the PRs
// merged to main between the running engine's commit (GET /version's
// `commit`) and the registry's `:main` build (its config's
// `org.opencontainers.image.revision` label, src/engine-update.js), and the
// tickets each closes. Main process only.
//
// No GitHub API and no new credential: the range is `git log --first-parent
// <running>..<available>` in the checkout the app runs from, after `rev-parse
// --verify` of both ends; a missing end is fetched once (`git fetch origin
// main`, the user's own credentials, as src/app-version.js does). Main is
// squash-merged, so a commit's subject is `Title (#ticket…) (#pr)`: the last
// `(#n)` is the PR, the ones before it the tickets it closes. A commit with no
// `(#n)` (a direct push to main) is not a PR and is left out.
//
// The engine fills what git cannot (#100091): `remote(from, to)` is
// src/engine-data.js's `fetchChanges`, GET /repos/{owner}/{name}/changes, the
// same list with each ticket's title from the engine's work-item store. With
// no checkout (a packaged build) the whole list is the engine's; with one,
// git gives the range and the engine only the titles (null when it fails:
// the list stands).
//
// Every failure is `{ error }` with the reason, shown in place of the list;
// the update itself never waits on or depends on it.
"use strict";

const OWNER_REPO = "codefrak-oss/frakworks";
const SHA = /^[0-9a-f]{7,40}$/i;
// Trailing ` (#n)` groups of a squash subject.
const TRAILING = /\s*\(#(\d+)\)\s*$/;

/** One subject's `{ title, pr, tickets }`; pr null when it carries no `(#n)`. */
function parseSubject(subject) {
  let rest = String(subject || "").trim();
  const nums = [];
  for (let m = TRAILING.exec(rest); m; m = TRAILING.exec(rest)) {
    nums.unshift(Number(m[1]));
    rest = rest.slice(0, m.index);
  }
  if (!nums.length) return { title: rest, pr: null, tickets: [] };
  return { title: rest.trim(), pr: nums[nums.length - 1], tickets: nums.slice(0, -1) };
}

/**
 * `git log --format=%H%x09%s` text to the list, newest first:
 * `[{ sha, title, pr, prUrl, tickets: [{ n, repo, url }] }]`, PR commits only.
 */
function parseChanges(logText, repo = OWNER_REPO) {
  const out = [];
  for (const line of String(logText || "").split("\n")) {
    const tab = line.indexOf("\t");
    if (tab < 0) continue;
    const sha = line.slice(0, tab).trim();
    if (!SHA.test(sha)) continue;
    const s = parseSubject(line.slice(tab + 1));
    if (s.pr == null) continue;
    out.push({
      sha, title: s.title, pr: s.pr, prUrl: `https://github.com/${repo}/pull/${s.pr}`,
      tickets: s.tickets.map((n) => ({ n, repo, url: `frakworks://ticket/${repo}/${n}` })),
    });
  }
  return out;
}

/**
 * `{ changes }` or `{ error }` for the range running..available. `git(args)`
 * resolves to stdout or rejects with the reason (main hands in one bound to
 * the checkout, null when the app does not run from one).
 */
async function listChanges({ running, available, git, remote = null, repo = OWNER_REPO,
  noRunning = "the running engine reports no commit on /version",
  noAvailable = "the registry's image has no org.opencontainers.image.revision label" }) {
  if (!running) return { error: noRunning };
  if (!available) return { error: noAvailable };
  if (running === available) return { changes: [] };
  if (!git) {
    if (!remote) return { error: "the app does not run from a git checkout, so the commit range cannot be read" };
    const r = await remote(running, available).catch((e) => ({ error: (e && e.message) || String(e) }));
    return r && r.error ? { error: r.error } : { changes: (r && r.changes) || [] };
  }
  const local = await gitChanges({ running, available, git, repo });
  return local.error || !remote || !local.changes.length ? local : withTitles(local, await remote(running, available).catch(() => null));
}

/** `local`'s tickets with the titles the engine's list has for them; untouched when it has none. */
function withTitles(local, fromEngine) {
  if (!fromEngine || fromEngine.error || !Array.isArray(fromEngine.changes)) return local;
  const titles = new Map();
  for (const c of fromEngine.changes) for (const t of c.tickets || []) if (t.title) titles.set(`${t.repo}#${t.n}`, t.title);
  return { changes: local.changes.map((c) => ({ ...c, tickets: c.tickets.map((t) => ({ ...t, title: titles.get(`${t.repo}#${t.n}`) || null })) })) };
}

async function gitChanges({ running, available, git, repo }) {
  const has = async (sha) => { try { await git(["rev-parse", "--verify", "--quiet", `${sha}^{commit}`]); return true; } catch (e) { return false; } };
  const missing = async () => { const m = []; for (const s of [running, available]) if (!(await has(s))) m.push(s); return m; };
  try {
    let gone = await missing();
    if (gone.length) {
      try { await git(["fetch", "--quiet", "origin", "main"]); } catch (e) {
        return { error: `commit ${gone.map((s) => s.slice(0, 7)).join(", ")} is not in the checkout and fetching origin main failed: ${(e && e.message) || e}` };
      }
      gone = await missing();
      if (gone.length) return { error: `commit ${gone.map((s) => s.slice(0, 7)).join(", ")} is not on origin main` };
    }
    const text = await git(["log", "--first-parent", "--format=%H%x09%s", `${running}..${available}`]);
    return { changes: parseChanges(text, repo) };
  } catch (e) {
    return { error: `git log failed: ${(e && e.message) || e}` };
  }
}

/** The hover's and confirm's lines for `{ changes } | { error }`; [] for null (not read yet). */
function changesLines(c, max = 15) {
  if (!c) return [];
  if (c.error) return [`The PR list is missing: ${c.error}`];
  const list = c.changes || [];
  if (!list.length) return ["No PRs merged between the two commits."];
  const lines = [`${list.length} PR${list.length === 1 ? "" : "s"} merged:`];
  for (const x of list.slice(0, max)) lines.push(`  #${x.pr} ${x.title}${x.tickets.length ? ` (closes ${x.tickets.map((t) => `#${t.n}${t.title ? ` ${t.title}` : ""}`).join(", ")})` : ""}`);
  if (list.length > max) lines.push(`  … and ${list.length - max} more`);
  return lines;
}

module.exports = { parseSubject, parseChanges, listChanges, withTitles, changesLines, OWNER_REPO };
