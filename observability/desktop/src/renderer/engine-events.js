// engine-events.js (renderer) — how a view follows the engine's event stream
// (the stream itself is held in
// main, src/engine-events.js, and reaches the renderer as `window.fleet
// .onEngineEvent`, each frame `{ type, data, subscription }`, and
// `window.fleet.onEngineStream`, the connection's `{ repo, state, since,
// lastFrameAt }`).
//
// Three things, and no timer anywhere:
// - `useEngineEvents(types, handler, filter)`: the frames of `types` that pass
//   `filter` (by `number`, `instance`, `repo`, `issueUrl`, or a `where`
//   predicate) reach `handler`. Nothing else does.
// - `useRefetchOn(types, refetch, filter)`: the view's existing read, run once
//   per burst of matching frames (debounced REFETCH_DEBOUNCE_MS, coalesced per
//   refetch function), and once on every connect's catch-up (below). An
//   unmounted view costs nothing: its timer is cleared and it leaves the
//   catch-up's list.
// - The catch-up: on `engine:stream` `catching-up` for a connection, every
//   mounted view's refetch runs once through the same function the events
//   trigger, and when the last has returned the renderer answers
//   `window.fleet.engineSnapshotted(repo)`, which is when main releases the
//   frames it queued since `hello`. So a frame is never applied before the
//   snapshot it would be overwritten by. A connection that was already
//   `catching-up` before any view listened (the launch: main connects as the
//   window is created, and `onEngineStream` hears pushes from its call on)
//   is found by reading `engineStreamState()` when the first view mounts, and
//   caught up the same way; each connect is caught up once, by its `since`.
//
// `StaleLine` is the one line every moved view shows while the stream is
// `down` ("stale since 14:02, reconnecting"), and `useStreamState` is what it
// and the status bar read: `engineStreamState()` once, then `onEngineStream`.
// A down stream is shown, never polled over: nothing here refetches on a timer.
//
// `coalesce(read)` is how a view's read is made safe for the stream: a call
// while the read is in flight is not dropped (the poll's "skip this tick, the
// next re-reads" is gone with the poll) but remembered, and runs once more
// when the in-flight read settles.
import { createElement, useEffect, useRef, useState } from "react";

export const REFETCH_DEBOUNCE_MS = 250;

const fleet = () => (typeof window !== "undefined" && window.fleet) || null;

/**
 * `read` with one in flight at a time and no call lost: a call while a read is
 * out marks it wanted (the last call's arguments win), and when the read
 * settles one more runs, so a burst of frames during a read costs one extra
 * read, not a dropped change. Every call returns the promise of the read that
 * is out *and* the re-read it asked for, so the catch-up (`runCatchUp`) waits
 * for the state it will answer `engineSnapshotted` on. A rejecting `read`
 * neither ends the chain nor rejects the caller: the view's read reports its
 * own failures.
 */
export const coalesce = (read) => {
  let running = null, wanted = null;
  const call = (...args) => {
    if (running) { wanted = args; return running; }
    running = (async () => {
      let next = args;
      while (next) {
        try { await read(...next); } catch { /* the read's own */ }
        next = wanted; wanted = null;
      }
      running = null;
    })();
    return running;
  };
  return call;
};

const ISSUE_URL_RE = /^https:\/\/github\.com\/([\w.-]+\/[\w.-]+)\/issues\/(\d+)$/;
const sameRepo = (a, b) => String(a || "").toLowerCase() === String(b || "").toLowerCase();

/**
 * Whether `frame` (`{ type, data, subscription }`) is one of `types` and passes
 * `filter`: `number` (the frame's `number`), `instance` (its `instance`, `root`
 * or `intakeId`), `repo` (its `repo`, ignoring case; a fleet-wide `*` frame
 * passes), `issueUrl` (the repo and number of a GitHub issue URL), `where(data,
 * frame)` for anything else. A filter that is not given does not apply.
 */
export const matchesFrame = (frame, types, filter = {}) => {
  if (!frame || typeof frame.type !== "string") return false;
  if (Array.isArray(types) && types.length && !types.includes(frame.type)) return false;
  const d = frame.data || {};
  const f = filter || {};
  let repo = f.repo, number = f.number;
  if (f.issueUrl) {
    const m = ISSUE_URL_RE.exec(String(f.issueUrl));
    if (!m) return false;
    repo = m[1]; number = Number(m[2]);
  }
  if (number != null && Number(d.number) !== Number(number)) return false;
  if (repo && d.repo !== "*" && !sameRepo(d.repo, repo)) return false;
  if (f.instance && !(d.instance === f.instance || d.root === f.instance || d.intakeId === f.instance)) return false;
  if (typeof f.where === "function" && !f.where(d, frame)) return false;
  return true;
};

// A filter as a dependency key: predicates by identity are not comparable, so
// a `where` is read from a ref each frame and does not re-subscribe.
const filterKey = (types, f = {}) => JSON.stringify([types, f.number ?? null, f.instance ?? null, f.repo ?? null, f.issueUrl ?? null]);

/**
 * `handler(frame)` hears every `engine:event` frame of `types` that passes
 * `filter` (matchesFrame) while the component is mounted. The handler and the
 * `where` predicate are read from refs, so a new closure each render does not
 * re-subscribe.
 */
export function useEngineEvents(types, handler, filter = {}) {
  const h = useRef(handler); h.current = handler;
  const w = useRef(filter && filter.where); w.current = filter && filter.where;
  const key = filterKey(types, filter);
  useEffect(() => {
    const f = fleet();
    if (!(f && f.onEngineEvent)) return undefined;
    const spec = { ...(filter || {}), where: (d, fr) => (w.current ? w.current(d, fr) : true) };
    const off = f.onEngineEvent((frame) => { if (matchesFrame(frame, types, spec)) h.current(frame); });
    return typeof off === "function" ? off : undefined;
  }, [key]);
}

// ---- the refetch registry and the catch-up ------------------------------------

// refetch function -> its pending debounce timer, so two hooks on one function
// (or a burst of frames) cost one call.
const pending = new Map();
// Every mounted view's refetch, for the catch-up: refetch -> { repo } (its
// repo filter, if any; a view that follows the selection has none).
const mounted = new Map();
let streamOff = null;

const fmtClock = (ms) => {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
};

/** Which registered refetches a connection on `repo` snapshots: all with no repo filter, and those on that repo. */
export const snapshotTargets = (entries, repo) =>
  [...entries].filter(([, spec]) => !spec || !spec.repo || repo === "*" || sameRepo(spec.repo, repo)).map(([fn]) => fn);

/**
 * The catch-up for one `engine:stream` push: on `catching-up`, run every
 * mounted refetch for that connection once, then tell main the snapshot is
 * taken. Exported for the test; `runCatchUp(push, entries, snapshotted)` with
 * the registry and the `engineSnapshotted` call injected.
 */
export async function runCatchUp(push, entries = mounted, snapshotted = (repo) => fleet() && fleet().engineSnapshotted && fleet().engineSnapshotted(repo)) {
  if (!push || push.state !== "catching-up") return false;
  // The catch-up's reads are authoritative for whatever was missed while
  // disconnected (a reopen among it): nothing removed on a close stays hidden.
  forgetRemoved(push.repo);
  const fns = snapshotTargets(entries, push.repo);
  await Promise.allSettled(fns.map((fn) => Promise.resolve().then(() => fn())));
  await snapshotted(push.repo);
  return true;
}

// Each connect's catch-up runs once: a `catching-up` found in the seed read
// and the same connect's push (or two seed reads) share `repo@since`.
const caughtUp = new Set();
const catchUpOnce = (push) => {
  if (!push || push.state !== "catching-up") return;
  const key = `${push.repo}@${push.since == null ? "" : push.since}`;
  if (caughtUp.has(key)) return;
  caughtUp.add(key);
  runCatchUp(push);
};

/**
 * The seed: every connection already `catching-up` when a view starts to
 * listen (the selected one and each entry of `connections`, a detail's second
 * connection among them) is caught up as if its push had just arrived. The
 * read is asynchronous, so by the time it answers every hook of the same
 * render has registered its refetch. Exported for the test.
 */
export async function seedCatchUp(state = fleet() && fleet().engineStreamState ? fleet().engineStreamState() : null) {
  let s;
  try { s = await state; } catch { return; }
  if (!s) return;
  const all = s.connections && typeof s.connections === "object" ? Object.values(s.connections) : [];
  const seen = new Set();
  for (const c of [s, ...all]) {
    if (!c || !c.repo || seen.has(c.repo)) continue;
    seen.add(c.repo);
    catchUpOnce(c);
  }
}

const ensureCatchUp = () => {
  if (streamOff) return;
  const f = fleet();
  if (!(f && f.onEngineStream)) return;
  streamOff = f.onEngineStream((push) => { catchUpOnce(push); });
  seedCatchUp();
};

/**
 * Debounced, coalesced: `refetch` runs once REFETCH_DEBOUNCE_MS after the first
 * matching frame of a burst, and once on every connect's catch-up. `types`
 * may be empty: the view then takes part in the catch-up only (the transcript
 * pane's one read of `smith_run_active`). `filter` is matchesFrame's; its
 * `repo` also says which connection's catch-up the view belongs to.
 */
export function useRefetchOn(types, refetch, filter = {}) {
  const r = useRef(refetch); r.current = refetch;
  const repo = filter && filter.repo ? filter.repo : null;
  // The one function the registry and the debounce key on, stable for the hook's life.
  const stable = useRef(null);
  if (!stable.current) stable.current = (...a) => r.current(...a);
  const fn = stable.current;
  useEffect(() => {
    ensureCatchUp();
    mounted.set(fn, { repo });
    // A view on its own connection (a detail's): that connection may already be
    // catching up when the view mounts; the seed finds it, once per connect.
    if (repo) seedCatchUp();
    return () => {
      mounted.delete(fn);
      const t = pending.get(fn);
      if (t) { clearTimeout(t); pending.delete(fn); }
    };
  }, [fn, repo]);
  useEngineEvents(types, () => {
    if (pending.has(fn)) return;
    pending.set(fn, setTimeout(() => { pending.delete(fn); fn(); }, REFETCH_DEBOUNCE_MS));
  }, filter);
}

/** The registry, for tests: `{ size, clear }`. */
export const refetchRegistry = {
  get size() { return mounted.size; },
  clear() { mounted.clear(); caughtUp.clear(); removed.clear(); for (const t of pending.values()) clearTimeout(t); pending.clear(); if (streamOff) { streamOff(); streamOff = null; } },
};

// ---- the board row carried on `flow`, `ticket` and `created` -------------------

/**
 * `issues` with `row` (a frame's `issue`, the board's row) applied in
 * place: the row with that `number` in that `repo` is replaced, a new one is
 * inserted first. A close is not a row: it arrives as a rowless `ticket`
 * `closed`, which `dropBoardRow` applies. A null row returns `issues`
 * unchanged, and so does a row of another repo than `repo` (the view's
 * selection; `null` or `*` is All repos and takes every row): the one `flow`
 * listener hears a detail's second connection too, whose rows are not the
 * board's.
 */
export const applyBoardRow = (issues, row, repo = null) => {
  const list = Array.isArray(issues) ? issues : [];
  if (!row || row.number == null || !row.repo) return list;
  if (repo && repo !== "*" && !sameRepo(repo, row.repo)) return list;
  const at = list.findIndex((i) => i && Number(i.number) === Number(row.number) && sameRepo(i.repo, row.repo));
  if (at < 0) return [row, ...list];
  const next = list.slice();
  next[at] = row;
  return next;
};

/** The frames that may carry a board row as `data.issue` (engine/README.md, "Events"). */
export const BOARD_ROW_TYPES = ["flow", "ticket", "created"];

/** The board row a frame's `data` carries (`issue`), or null when it carries none (absent, `null`, no number). */
export const carriedRow = (data) => {
  const row = data && data.issue;
  return row && typeof row === "object" && row.number != null && row.repo ? row : null;
};

// ---- a closed issue leaves the board on its frame -------------------------------

/**
 * Whether a frame's `data` is an issue's close: a `ticket` `closed` with no
 * `prNumber` (a pull request's frame is `pr`, and any frame with `prNumber` is
 * about a pull request, whose number an open issue may share).
 */
export const closedIssue = (data) => Boolean(data && data.action === "closed" && data.prNumber == null
  && data.number != null && data.repo && data.repo !== "*");

/** Whether a frame's `data` is an issue's reopen (a `ticket` `reopened`, not a pull request's). */
export const reopenedIssue = (data) => Boolean(data && data.action === "reopened" && data.prNumber == null
  && data.number != null && data.repo);

/**
 * `issues` without the row with `number` in `repo`; unchanged (the same array)
 * when it holds no such row, or when `repo` is not the view's `sel` (`null` or
 * `*` is All repos and takes every repo), as applyBoardRow.
 */
export const dropBoardRow = (issues, repo, number, sel = null) => {
  const list = Array.isArray(issues) ? issues : [];
  if (number == null || !repo) return list;
  if (sel && sel !== "*" && !sameRepo(sel, repo)) return list;
  const next = list.filter((i) => !(i && Number(i.number) === Number(number) && sameRepo(i.repo, repo)));
  return next.length === list.length ? list : next;
};

// The issues the app removed on a close, per repo: lower-cased `owner/name` ->
// Map(number -> { at: when removed, ms; closed: the issue as the Done group
// draws it, or null when no view held its row }). `GET /issues` (and `GET /needs`, whose
// authorize rows come from the same listing) is GitHub's open-issue listing,
// read live, which lags a close by seconds (#1065: #1027's webhook came 2 s
// after the close and the 250 ms refetch still listed it), so a list read
// after the frame is filtered against this. A number is remembered for
// REMOVED_KEEP_MS, 2 minutes: an order of magnitude past the lag seen, and
// short enough that a stale entry outlives no real change, since a reopen
// takes it out at once and so does any frame carrying its row (a row is read
// by number, which GitHub answers only for an open issue). It expires when
// read, never on a timer, and every connect's catch-up empties it for its repo.
export const REMOVED_KEEP_MS = 2 * 60 * 1000;
const removed = new Map();
const repoKey = (repo) => String(repo || "").toLowerCase();

/** Remember `number` in `repo` as closed at `now`; `closed` (a closed issue for the Done group) is kept if given. */
export const rememberRemoved = (repo, number, now = Date.now(), closed = null) => {
  if (!repo || number == null) return;
  const k = repoKey(repo);
  if (!removed.has(k)) removed.set(k, new Map());
  const m = removed.get(k);
  const was = m.get(Number(number));
  m.set(Number(number), { at: was ? was.at : now, closed: closed || (was && was.closed) || null });
};

/** Forget `number` in `repo` (a reopen, a row); with no number, all of `repo`; `*` or no repo, every repo. */
export const forgetRemoved = (repo, number = null) => {
  if (!repo || repo === "*") { if (number == null) removed.clear(); else for (const m of removed.values()) m.delete(Number(number)); return; }
  const m = removed.get(repoKey(repo));
  if (!m) return;
  if (number == null) removed.delete(repoKey(repo));
  else m.delete(Number(number));
};

/** Whether `number` in `repo` was removed on a close less than REMOVED_KEEP_MS before `now`; an older entry is dropped. */
export const isRemoved = (repo, number, now = Date.now()) => {
  const m = removed.get(repoKey(repo));
  if (!m || number == null) return false;
  const e = m.get(Number(number));
  if (!e) return false;
  if (now - e.at < REMOVED_KEEP_MS) return true;
  m.delete(Number(number));
  return false;
};

/**
 * `list` (a `GET /issues` answer's issues, the tree's, or the Needs rows,
 * whose number is `ticket`: anything with `repo` and the `key` field) without
 * what a close removed; the same array when nothing is filtered.
 */
export const withoutRemoved = (list, key = "number", now = Date.now()) => {
  if (!Array.isArray(list) || removed.size === 0) return list;
  const next = list.filter((i) => !(i && isRemoved(i.repo, i[key], now)));
  return next.length === list.length ? list : next;
};

/** The closed issues still remembered (with what the Done group draws) of `repo`, or of every repo when it is null or `*`. */
export const removedClosed = (repo = null, now = Date.now()) => {
  const out = [];
  for (const [k, m] of removed) {
    if (repo && repo !== "*" && k !== repoKey(repo)) continue;
    for (const [n, e] of m) if (e.closed && isRemoved(k, n, now)) out.push(e.closed);
  }
  return out;
};

/**
 * The Issues board and the Tickets tree kept by the stream: a `flow`,
 * `ticket` or `created` frame that carries its issue's board row has it
 * handed to `apply(row, frame)` (the caller's `applyBoardRow`s) and costs no
 * read of `GET /issues`, whose list lags a just-created issue by seconds; the
 * row was read by number, and the number leaves the removed set. An issue's
 * close (`closedIssue`) is handed to `close(repo, number, frame)` (the
 * caller's `dropBoardRow`s), its number is remembered (above) so a later read
 * of the same lagging list does not bring it back, and it costs no read. A
 * `reopened` frame forgets the number first, then is a row or a refetch like
 * any other. A `ticket` or `incident` frame without a row (a comment, a pull
 * request, a reopen, a row the engine could not read) runs `refetch` through
 * `useRefetchOn`, debounced; a `flow` or `created` one without a row runs it
 * at once, as a rowless `flow` always has (no `flow` frame says its issue
 * closed). `refetch` also takes part in the catch-up. The caller filters what
 * `refetch` reads with `withoutRemoved`.
 */
export function useBoardRows(apply, refetch, close = null) {
  const a = useRef(apply); a.current = apply;
  const r = useRef(refetch); r.current = refetch;
  const c = useRef(close); c.current = close;
  useRefetchOn(["ticket", "incident"], refetch, { where: (d) => !carriedRow(d) && !closedIssue(d) });
  useEngineEvents(BOARD_ROW_TYPES, (frame) => {
    const d = frame.data;
    if (frame.type === "ticket" && closedIssue(d)) {
      rememberRemoved(d.repo, d.number);
      if (c.current) c.current(d.repo, d.number, frame);
      return;
    }
    if (frame.type === "ticket" && reopenedIssue(d)) forgetRemoved(d.repo, d.number);
    const row = carriedRow(d);
    if (row) { forgetRemoved(row.repo, row.number); a.current(row, frame); }
    else if (frame.type !== "ticket") r.current();
  });
}

// ---- the stream's state: the status bar and the stale line ----------------------

/**
 * The selected connection's state, `{ repo, state, since, lastFrameAt }`:
 * `engineStreamState()` once, then every `onEngineStream` push for the same
 * connection (a detail's second connection does not move the badge). `null`
 * until known, and outside the app.
 */
export function useStreamState() {
  const [state, setState] = useState(null);
  useEffect(() => {
    const f = fleet();
    if (!(f && f.onEngineStream)) return undefined;
    let live = true;
    if (f.engineStreamState) {
      Promise.resolve(f.engineStreamState()).then((s) => { if (live && s) setState((prev) => prev || s); }).catch(() => {});
    }
    const off = f.onEngineStream((push) => {
      if (!live || !push) return;
      setState((prev) => (prev && prev.repo && push.repo !== prev.repo && f.engineStreamState ? prev : { ...prev, ...push }));
      // The selection moved: the new connection's pushes name another repo; re-read which is selected.
      if (f.engineStreamState) Promise.resolve(f.engineStreamState()).then((s) => { if (live && s) setState(s); }).catch(() => {});
    });
    return () => { live = false; if (typeof off === "function") off(); };
  }, []);
  return state;
}

/**
 * `● live`, `● catching up` or `○ down · <age>`: the status bar's phrase for a
 * stream state. `restarting` (an engine update in flight, #1053): a stream
 * that is not live reads as the expected restart, muted, never red.
 */
export const RESTARTING = "restarting for update";
export const streamText = (s, now = Date.now(), restarting = false) => {
  if (restarting && !(s && s.state === "live")) return { text: `stream ◌ ${RESTARTING}`, level: "muted" };
  if (!s || !s.state) return { text: "stream ·", level: "muted" };
  if (s.state === "live") return { text: "stream ● live", level: "ok" };
  if (s.state === "catching-up") return { text: "stream ● catching up", level: "muted" };
  const at = s.lastFrameAt || s.since;
  return { text: `stream ○ down${at ? ` · ${fmtAge(now - at)}` : ""}`, level: "bad" };
};

/** "12s", "2m", "3h": the age of the last frame, in the status bar's register. */
export const fmtAge = (ms) => {
  const s = Math.max(0, Math.round((ms || 0) / 1000));
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  return `${Math.floor(s / 3600)}h`;
};

/** What a view draws in place of its engine-unreachable error while an update restarts the engine (#1053). */
export const RESTARTING_VIEW = `engine ${"restarting for update"}; this view reloads when the engine is back`;

/** The stale line's text for a state, or null while the stream is not down; during an update, the restart's. */
export const staleText = (s, restarting = false) => {
  if (!s || s.state !== "down") return null;
  if (restarting) return `engine ${RESTARTING}; the views catch up when it is back`;
  const at = s.lastFrameAt || s.since;
  return `stale since ${at ? fmtClock(at) : "?"}, reconnecting`;
};

/**
 * One line above a moved view's content while the stream is `down`: "stale
 * since 14:02, reconnecting"; nothing while it is live or catching up. `state`
 * may be given (a test's, or the parent's one read); else the hook's.
 */
export const StaleLine = ({ state = null, restarting = false }) => {
  const live = useStreamState();
  const text = staleText(state || live, restarting);
  if (!text) return null;
  if (restarting) return createElement("div", { className: "staleLine restarting",
    title: "The engine is being updated; its event stream reconnects when the new engine starts." }, text);
  return createElement("div", { className: "staleLine",
    title: "The engine's event stream is down; what is shown is as of the last frame. Nothing polls meanwhile." }, text);
};
