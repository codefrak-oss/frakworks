// builds.jsx — Explorer > Analytics > Builds (#1935): a target repo's GitHub
// Actions workflow runs, running and past, each labelled by kind. Reads the
// engine's GET /repos/{owner}/{name}/ci/runs through window.fleet.ciRuns
// (main's `engine:ci-runs`) for the repo switcher's repo, every 15 s while open.
//
// Each run's `area` (the workflow file's name: engine, desktop-release,
// images...) and `trigger` (pr, main, branch, or the event name) come from the
// engine; a chip per distinct value toggles its rows in both sections.

import { useCallback, useEffect, useRef, useState } from "react";
import { elapsedLabel } from "./build-strip.jsx";
import { fmtUtc, fmtSinceStarted, DASH } from "./doctor.jsx";

export const LIMITS = [25, 50, 100];
export const DEFAULT_LIMIT = 50;
/** How often the page re-reads while open, as the Build gate strip does. */
export const POLL_MS = 15000;

const at = (iso) => { const t = Date.parse(iso); return Number.isFinite(t) ? t : 0; };
/** A kind's value as its chip names it; a run with none is `—`. */
const kindKey = (v) => (v == null || v === "" ? DASH : String(v));

/** The runs whose status is not `completed` (queued, in_progress, waiting...), oldest first. */
export const runningRuns = (runs) => (runs || []).filter((r) => r && r.status !== "completed")
  .sort((a, b) => at(a.createdAt) - at(b.createdAt));
/** The completed runs, newest first. */
export const historyRuns = (runs) => (runs || []).filter((r) => r && r.status === "completed")
  .sort((a, b) => at(b.startedAt || b.createdAt) - at(a.startedAt || a.createdAt));
/** The distinct areas and triggers in the answer, in first-seen order. */
export const kindsOf = (runs) => ({
  area: [...new Set((runs || []).map((r) => kindKey(r && r.area)))],
  trigger: [...new Set((runs || []).map((r) => kindKey(r && r.trigger)))],
});
/** The runs whose area and trigger are both on; `hidden` is { "area:engine": true, "trigger:pr": true, ... }. */
export const shownRuns = (runs, hidden = {}) => (runs || [])
  .filter((r) => r && !hidden[`area:${kindKey(r.area)}`] && !hidden[`trigger:${kindKey(r.trigger)}`]);

/** Seconds as `m:ss` (`h:mm:ss` from an hour on), `—` when not known. */
const fmtDuration = (s) => (Number.isFinite(Number(s)) && s != null ? elapsedLabel(s) : DASH);

/** Runs `fn` every `ms` and answers the function that stops it; `timers` is for tests. */
export const pollEvery = (fn, ms = POLL_MS, timers = globalThis) => {
  const id = timers.setInterval(fn, ms);
  return () => timers.clearInterval(id);
};

const Chips = ({ label, kind, values, hidden, onToggle }) => (
  <div className="cibChips"><span className="muted">{label}</span>
    {values.map((v) => {
      const off = !!hidden[`${kind}:${v}`];
      return <button key={v} className={"cibChip" + (off ? " off" : "")} data-kind={`${kind}:${v}`}
                     title={off ? `Show ${label} ${v}` : `Hide ${label} ${v}`} onClick={() => onToggle(`${kind}:${v}`)}>{v}</button>;
    })}
  </div>
);

/** The page drawn from `result` ({ loading } | { error } | { runs }); never throws. */
export const BuildsView = ({ repo, result, limit = DEFAULT_LIMIT, onLimit, onRefresh, refreshing, hidden = {}, onToggle = () => {}, now = Date.now() }) => {
  const r = result || { loading: true };
  const runs = Array.isArray(r.runs) ? r.runs : null;
  const kinds = kindsOf(runs || []);
  const shown = shownRuns(runs || [], hidden);
  const running = runningRuns(shown);
  const history = historyRuns(shown);
  return (
    <div className="fleet-root jev builds">
      <div className="head"><h1>Builds</h1>
        {repo && <span className="meta">{repo}</span>}
        <select className="cibLimit" value={limit} title="How many runs to read" onChange={(e) => onLimit && onLimit(Number(e.target.value))}>
          {LIMITS.map((n) => <option key={n} value={n}>{n}</option>)}
        </select>
        {onRefresh && <button className={"refreshBtn" + (refreshing ? " spinning" : "")} title="Refresh now" onClick={onRefresh}>⟳</button>}
      </div>
      {!repo && <div className="muted">Pick a repo in the switcher to see its builds.</div>}
      {repo && r.loading && <div className="muted">asking the engine for {repo}'s builds…</div>}
      {repo && r.error && <div className="err">{String(r.error)}</div>}
      {repo && runs && <>
        <Chips label="area" kind="area" values={kinds.area} hidden={hidden} onToggle={onToggle} />
        <Chips label="trigger" kind="trigger" values={kinds.trigger} hidden={hidden} onToggle={onToggle} />
        <h2>Running</h2>
        {running.length === 0
          ? <div className="muted cibNone">no builds running</div>
          : <table className="jevCalls cibRunning">
              <thead><tr><th>elapsed</th><th>area</th><th>trigger</th><th>workflow</th><th>branch</th><th>status</th></tr></thead>
              <tbody>{running.map((b) => (
                <tr key={b.id} className="cibRow">
                  <td className="build-elapsed" title={fmtUtc(b.createdAt)}>{elapsedLabel((now - at(b.createdAt)) / 1000)}</td>
                  <td>{kindKey(b.area)}</td><td>{kindKey(b.trigger)}</td>
                  <td>{b.url ? <a href={b.url} title={b.url}>{b.workflow || DASH}</a> : (b.workflow || DASH)}</td>
                  <td>{b.branch || DASH}</td><td>{b.status || DASH}</td>
                </tr>))}</tbody>
            </table>}
        <h2>History</h2>
        {history.length === 0
          ? <div className="muted cibNone">no finished builds</div>
          : <table className="jevCalls cibHistory">
              <thead><tr><th className="jevWhen">started</th><th>area</th><th>trigger</th><th>workflow</th><th>branch</th><th>conclusion</th><th>duration</th><th>run</th></tr></thead>
              <tbody>{history.map((b) => (
                <tr key={b.id} className={"cibRow ci-" + kindKey(b.conclusion)}>
                  <td className="jevWhen" title={fmtUtc(b.startedAt || b.createdAt)}>{fmtSinceStarted(b.startedAt || b.createdAt, now)}</td>
                  <td>{kindKey(b.area)}</td><td>{kindKey(b.trigger)}</td>
                  <td>{b.workflow || DASH}</td><td>{b.branch || DASH}</td>
                  <td className="cibConclusion">{b.conclusion || DASH}</td>
                  <td>{fmtDuration(b.durationSeconds)}</td>
                  <td>{b.url ? <a href={b.url} title={b.url}>open</a> : DASH}</td>
                </tr>))}</tbody>
            </table>}
      </>}
    </div>
  );
};

const ipcError = (e) => ({ error: `IPC failed: ${e && e.message ? e.message : e}` });

/** Explorer > Analytics > Builds for `repo` (the switcher's; null under All repos); never throws. */
export const Builds = ({ repo }) => {
  const [limit, setLimit] = useState(DEFAULT_LIMIT);
  const [result, setResult] = useState({ loading: true });
  const [refreshing, setRefreshing] = useState(false);
  const [hidden, setHidden] = useState({});
  const [now, setNow] = useState(Date.now());
  const seq = useRef(0);
  const load = useCallback(async () => {
    const mine = ++seq.current;
    if (!repo) return;
    if (!(typeof window !== "undefined" && window.fleet && window.fleet.ciRuns)) {
      setResult({ error: "this build has no Builds channel" });
      return;
    }
    setRefreshing(true);
    let r;
    try { r = await window.fleet.ciRuns(repo, limit); }
    catch (e) { r = ipcError(e); }
    if (mine !== seq.current) return;
    setRefreshing(false);
    setResult(r && Array.isArray(r.runs) ? { runs: r.runs } : { error: (r && r.error) || "no response from the main process" });
  }, [repo, limit]);
  useEffect(() => {
    setResult({ loading: true });
    load();
    const stop = pollEvery(load, POLL_MS);
    return () => { seq.current++; stop(); };
  }, [load]);
  useEffect(() => pollEvery(() => setNow(Date.now()), 1000), []);
  const onToggle = useCallback((k) => setHidden((h) => ({ ...h, [k]: !h[k] })), []);
  return <BuildsView repo={repo} result={result} limit={limit} onLimit={setLimit} onRefresh={load} refreshing={refreshing}
                     hidden={hidden} onToggle={onToggle} now={now} />;
};
