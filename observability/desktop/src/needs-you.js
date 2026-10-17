// needs-you.js — the main-process side of the "needs you" view and of the
// HITLP Requests panes. Needs you is the engine's `GET /needs` rows only
// (engine/README.md, through src/engine-data.js), whose `ask`/`approve` rows are
// ignored. The HITLP server's tasks (its surface API, src/hitlp-surface.js,
// #2487) are a read of their own, `fetchHitlpTasks`: the open ones for HITLP
// Requests, the explorer badge and the urgent popup, the decided ones for
// HITLP Requests > Closed. No `gh`, nothing written.
//
// Like the other fetchers nothing ever rejects — a failure becomes `{ error }`
// so the renderer draws the .err block instead of a blank view. `toRows()` is
// a pure function from the engine's Need list to the rows on screen, so
// test/needs-you.test.js exercises it from a checked-in fixture.
"use strict";

const engineData = require("./engine-data");

// The kinds, in the order the view draws them. `insufficient-permissions`
// is a ticket an intake filed unlabeled for human triage: `wanted` is its blocker.
const KIND_ORDER = ["question", "acceptance", "decision", "incident", "insufficient-permissions", "reauthorize", "authorize"];
const kindRank = (k) => { const i = KIND_ORDER.indexOf(k); return i < 0 ? KIND_ORDER.length : i; };

const secs = (iso) => { const t = iso ? Date.parse(iso) : NaN; return Number.isFinite(t) ? Math.floor(t / 1000) : null; };

/**
 * The rows the view draws from the engine's Need list, every kind but `ask`
 * and `approve` (HITLP tasks are HITLP Requests', `surfaceRows`). Pure: same
 * list and `now` (epoch seconds) in, same rows out. Ordered by kind, then
 * longest wait first. `waited` is in seconds, what the renderer's fmtWaited takes.
 */
function toRows(needs, now = Math.floor(Date.now() / 1000)) {
  return (Array.isArray(needs) ? needs : [])
    .filter((n) => n && typeof n === "object" && !HITLP_KINDS.includes(n.kind))
    .map((n) => {
      const ticket = Number.isInteger(n.number) ? n.number : null;
      const repo = typeof n.repo === "string" && n.repo ? n.repo : null;
      const since = secs(n.since);
      return {
        ticket, repo, title: n.title || "",
        url: repo && ticket != null ? `https://github.com/${repo}/issues/${ticket}` : null,
        kind: n.kind, ask: n.wanted || "",
        // The recorded question in full (a question, acceptance or decision); `ask` is its one line.
        question: typeof n.question === "string" && n.question ? n.question : null,
        waited: since == null ? 0 : Math.max(now - since, 0),
        source: n.kind, actionId: n.actionId,
        // Whom the row waits on (a login), null when the engine names no one.
        for: typeof n.for === "string" && n.for ? n.for : null,
        key: `${n.kind}${n.actionId}`,
      };
    })
    .sort((a, b) => kindRank(a.kind) - kindRank(b.kind) || b.waited - a.waited);
}

const HITLP_KINDS = ["ask", "approve"];
// A surface task that still waits on a person (HITLP spec 7.3); completed, failed and cancelled do not.
const OPEN_STATUSES = ["working", "input_required"];
const obj = (v) => (v && typeof v === "object" && !Array.isArray(v) ? v : null);
const str = (v) => (typeof v === "string" && v ? v : null);

/**
 * The person a task waits on: `attested.onBehalfOf`, else `asserted.onBehalfOf`,
 * when it is a person (`{ kind: "person", id | login | name }`, or a bare
 * string); null for an agent, a team or none, which every human sees.
 */
function personOf(task) {
  for (const side of [obj(task.attested), obj(task.asserted)]) {
    const b = side ? side.onBehalfOf : null;
    if (typeof b === "string" && b) return b;
    const o = obj(b);
    if (o && o.kind === "person") { const who = str(o.id) || str(o.login) || str(o.name); if (who) return who; }
  }
  return null;
}

// A lineage entry's instant (epoch ms), or NaN.
const lineageAt = (l) => Date.parse((l && (l.at || l.asOf || l.createdAt)) || "");

/** `asserted.lineage` (an array, or `{ links }`) newest first; entries with no time keep theirs, after the dated. */
function lineageOf(asserted) {
  const raw = asserted ? asserted.lineage : null;
  const list = Array.isArray(raw) ? raw : obj(raw) && Array.isArray(raw.links) ? raw.links : [];
  const asOf = obj(raw) ? Date.parse(raw.asOf || "") : NaN;
  return list.filter((l) => l && typeof l === "object")
    .map((l, i) => ({ l, i, t: Number.isFinite(lineageAt(l)) ? lineageAt(l) : asOf }))
    .sort((a, b) => (Number.isFinite(b.t) ? b.t : -Infinity) - (Number.isFinite(a.t) ? a.t : -Infinity) || b.i - a.i)
    .map((x) => x.l);
}

/**
 * The surface API's open tasks (`GET /tasks`, HITLP 0.2) as Needs rows:
 * `actionId` the task id, `for` the person it waits on, the §4 body, the
 * server-`attested` who, the requester's `asserted` why, recommendation and
 * lineage (newest first). Pure; ordered ask, approve, then longest wait.
 * A task is the spec's (HITLP spec/schemas/surface/task.schema.json): `primitive`
 * ask or approve, open while its `status` is `working` or `input_required`
 * (spec 7.3: an Ask, and an Approve with no decision page, stay `working` until
 * decided); the list also holds decided tasks, which wait on no one.
 */
function surfaceRows(tasks, now = Math.floor(Date.now() / 1000)) {
  return (Array.isArray(tasks) ? tasks : [])
    .filter((t) => isTask(t) && OPEN_STATUSES.includes(t.status))
    .map((t) => taskRow(t, now))
    .sort((a, b) => HITLP_KINDS.indexOf(a.kind) - HITLP_KINDS.indexOf(b.kind) || b.waited - a.waited);
}

const isTask = (t) => t && typeof t === "object" && str(t.id) && HITLP_KINDS.includes(t.primitive);

/** One surface task as a row (see `surfaceRows`), open or decided. */
function taskRow(t, now) {
  const kind = t.primitive;
  const since = secs(t.createdAt || t.since);
  const context = obj(t.context);
  const attested = obj(t.attested);
  const asserted = obj(t.asserted);
  // A task the engine raised names its ticket in `context.data.repo` and
  // `context.data.ticket` (the spec closes `context` to summary, links and
  // data): both make the row a frakworks://ticket link, one alone none.
  const data = context && obj(context.data);
  const repo = data && str(data.repo);
  const num = data && Number.isInteger(Number(data.ticket)) && Number(data.ticket) > 0 ? Number(data.ticket) : null;
  const linked = !!(repo && num != null);
  return {
    ticket: linked ? num : null, repo: linked ? repo : null,
    url: linked ? `frakworks://ticket/${repo}/${num}` : null,
    title: (context && str(context.summary)) || (asserted && str(asserted.why)) || "",
    kind, ask: kind === "approve" ? str(t.action) || "" : str(t.question) || "",
    question: kind === "ask" ? str(t.question) : null,
    waited: since == null ? 0 : Math.max(now - since, 0),
    source: "hitlp", actionId: t.id, key: `${kind}${t.id}`,
    for: personOf(t),
    deadline: secs(t.deadline),
    attested,
    why: asserted ? str(asserted.why) : null,
    recommendation: asserted ? obj(asserted.recommendation) : null,
    lineage: lineageOf(asserted),
    asserted,
    responseSchema: kind === "ask" && t.responseSchema !== undefined ? t.responseSchema : null,
    options: kind === "ask" && Array.isArray(t.options) ? t.options : null,
    action: kind === "approve" ? str(t.action) : null,
    payload: kind === "approve" && t.payload !== undefined ? t.payload : null,
    payloadDigest: kind === "approve" ? str(t.payloadDigest) : null,
    scope: kind === "approve" && t.scope !== undefined ? t.scope : null,
    priority: str(t.priority) || "normal",
    context,
    since,
    status: str(t.status),
  };
}

/**
 * The surface API's decided tasks (every status but `working` and
 * `input_required`) as rows for HITLP Requests > Closed, newest decided first.
 * The decision is the task's `result`, a HITLP decision record
 * (spec/schemas/decision-record.schema.json): `outcome` (answered, approved,
 * rejected, timed_out, cancelled), `decidedBy` ({ type, id }), `decidedAt`
 * and `channel`. A task with no result is decided as its status says
 * (`cancelled`, `failed`), at no known time.
 */
function closedRows(tasks, now = Math.floor(Date.now() / 1000)) {
  return (Array.isArray(tasks) ? tasks : [])
    .filter((t) => isTask(t) && !OPEN_STATUSES.includes(t.status))
    .map((t) => decidedRow(t, now))
    .sort((a, b) => (b.decidedAt ?? -Infinity) - (a.decidedAt ?? -Infinity));
}

/** One decided task as a row: `taskRow`'s and its decision record's fields. */
function decidedRow(t, now) {
  const res = obj(t.result) || {};
  const by = obj(res.decidedBy);
  return {
    ...taskRow(t, now),
    outcome: str(res.outcome) || str(t.status) || "closed",
    decidedBy: by ? str(by.id) || str(by.type) : str(res.decidedBy),
    decidedByType: by ? str(by.type) : null,
    channel: str(res.channel),
    decidedAt: secs(res.decidedAt),
    answer: res.answer !== undefined ? res.answer : null,
    reason: str(res.reason),
  };
}

/**
 * One task read by id for a ticket's inline task view (detail.jsx):
 * `surface(id)` is src/hitlp-surface.js `get` (`{ task }` or `{ error }`).
 * Resolves to `{ row, error, notSet }`, `row` taskRow's for an open task and
 * with the decision record (`decided: true`, `outcome`, `decidedBy`,
 * `decidedAt`) for a decided one; never rejects, never carries the credential.
 */
async function fetchHitlpTask({ surface = null, configured = true, id = null, now = Math.floor(Date.now() / 1000) } = {}) {
  if (!surface || !configured) return { row: null, error: "no HITLP server set: Config > frakworks > HITLP server", notSet: true };
  try {
    const s = await surface(typeof id === "string" ? id : String(id == null ? "" : id));
    if (!s || s.error) return { row: null, error: `HITLP server: ${(s && s.error) || "no answer"}`, notSet: false };
    const t = s.task;
    if (!isTask(t)) return { row: null, error: `HITLP server: task ${id} is not an ask or approve task`, notSet: false };
    const row = OPEN_STATUSES.includes(t.status) ? taskRow(t, now) : { ...decidedRow(t, now), decided: true };
    return { row, error: null, notSet: false };
  } catch (e) {
    return { row: null, error: `HITLP server: ${(e && e.message) || e}`, notSet: false };
  }
}

// The surface URL's host, for the HITLP Requests heading.
const hostOf = (url) => { try { return url ? new URL(url).host : null; } catch { return null; } };

/**
 * The one read of the HITLP server's tasks for the window (HITLP Requests,
 * Closed, the explorer badge and the urgent popup): `surface()` is
 * src/hitlp-surface.js `list` (`{ tasks }` or `{ error }`), `configured` whether
 * a surface is set. Resolves to `{ open, closed, error, notSet, host }`; never
 * rejects. Not set is an error naming where to set it.
 */
async function fetchHitlpTasks({ surface = null, configured = true, url = null, now } = {}) {
  const host = hostOf(url);
  if (!surface || !configured) {
    return { open: [], closed: [], error: "no HITLP server set: Config > frakworks > HITLP server", notSet: true, host };
  }
  try {
    const s = await surface();
    if (s && s.error) return { open: [], closed: [], error: `HITLP server: ${s.error}`, notSet: false, host };
    const tasks = s && s.tasks;
    return { open: surfaceRows(tasks, now), closed: closedRows(tasks, now), error: null, notSet: false, host };
  } catch (e) {
    return { open: [], closed: [], error: `HITLP server: ${(e && e.message) || e}`, notSet: false, host };
  }
}

/**
 * Whether row `r` shows with repo `repo` selected (#2423): every row in All
 * repos (null), else a row in that repo.
 */
const inRepo = (r, repo) => !repo || r.repo === repo;

/**
 * The one call the IPC handler makes: GET /needs' kinds but ask and approve.
 * Resolves to `{ error: null, repo, rows }` or `{ error, repo, rows }`; never
 * rejects. `repo` is the repo switcher's choice. Every repo's rows are read
 * (the engine's `?repo=` drops a need with no repo) and `inRepo` keeps a
 * repo's own. The renderer labels a row `#n` in one repo and `owner/name#n`
 * in All repos.
 */
async function fetchNeeds({ repo = null, env, fetch, now } = {}) {
  try {
    const opts = { repo: null };
    if (env) opts.env = env;
    if (fetch) opts.fetch = fetch;
    const r = await engineData.fetchNeeds(opts);
    if (r.error) return { error: r.error, repo, rows: [] };
    return { error: null, repo, rows: toRows(r.rows, now).filter((row) => inRepo(row, repo)) };
  } catch (e) {
    return { error: `needs failed: ${e && e.message ? e.message : e}`, repo, rows: [] };
  }
}

module.exports = { fetchNeeds, fetchHitlpTasks, fetchHitlpTask, toRows, surfaceRows, closedRows, personOf, lineageOf, inRepo, KIND_ORDER, OPEN_STATUSES };
