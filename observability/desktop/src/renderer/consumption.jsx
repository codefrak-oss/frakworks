// consumption.jsx — Analytics > AI Accounts > Account consumption (#1934):
// what the fleet consumed from the AI accounts over a timeframe, one row per
// ticket-round role, then per other Claude caller (doctor, refactor scan, the
// summarizer, the usage probe, …), then the total, with USD, wall clock and
// the four token kinds. Native React over the engine's read-only
// GET /accounts/consumption?window= (engine/README.md, "Consumption ledger"),
// through main (window.fleet.consumption). The timeframe switch shares the
// Jev decisions page's classes (jev.jsx).
import { useCallback, useEffect, useRef, useState } from "react";

/** The timeframes offered, as the engine's `window` takes them, and their names. */
export const TIMEFRAMES = [["1h", "1 hour"], ["4h", "4 hours"], ["1d", "1 day"], ["1w", "1 week"], ["30d", "30 days"]];
export const DEFAULT_TIMEFRAME = "1d";

/** A row's name: a role as the ticket timeline names it, else the caller in words. */
const CALLER_NAMES = {
  doctor: "doctor runs", "refactor-scan": "refactor-scan runs", job: "custom job runs",
  summarizer: "session summarizer", "usage-probe": "usage refresh probe", "credential-verify": "credential check",
  total: "total",
};
export const rowName = (row) => (row.caller === "smith-run" ? row.key : CALLER_NAMES[row.key] || row.key);

/** Unknown is shown as unknown, never as 0: the engine sends null for a figure no call in the row knew. */
export const UNKNOWN = "—";

export const fmtUsd = (v) => (v == null ? UNKNOWN : `$${Number(v).toFixed(v < 1 && v > 0 ? 4 : 2)}`);
export const fmtTokens = (v) => (v == null ? UNKNOWN : Number(v).toLocaleString("en-US"));

/** A wall clock in a human unit: `850 ms`, `42 s`, `3 m 05 s`, `2 h 14 m`. */
export const fmtDuration = (ms) => {
  if (ms == null) return UNKNOWN;
  if (ms < 1000) return `${Math.round(ms)} ms`;
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} m ${String(s % 60).padStart(2, "0")} s`;
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, "0")} m`;
};

const COLUMNS = [
  ["costUsd", "USD", fmtUsd], ["durationMs", "wall clock", fmtDuration],
  ["inputTokens", "input", fmtTokens], ["outputTokens", "output", fmtTokens],
  ["cacheReadTokens", "cache read", fmtTokens], ["cacheWriteTokens", "cache write", fmtTokens],
];

/** One cell; a sum over only some of the row's calls says so in its tooltip. */
const Cell = ({ row, col, fmt }) => {
  const missing = row.unknown && row.unknown[col];
  const title = missing ? `${missing} of ${row.calls} call(s) did not report this` : undefined;
  return <td className={"num" + (row[col] == null ? " muted" : "")} title={title}>{fmt(row[col])}{missing && row[col] != null ? "*" : ""}</td>;
};

export const ConsumptionTable = ({ rows }) => (
  <table className="jevCalls consumption">
    <thead>
      <tr><th>role / caller</th><th className="num">calls</th>{COLUMNS.map(([k, label]) => <th key={k} className="num">{label}</th>)}</tr>
    </thead>
    <tbody>
      {rows.map((r) => (
        <tr key={`${r.caller}:${r.key}`} className={r.key === "total" ? "total" : r.caller === "smith-run" ? "role" : "caller"}>
          <td>{rowName(r)}</td><td className="num">{r.calls}</td>
          {COLUMNS.map(([k, , fmt]) => <Cell key={k} row={r} col={k} fmt={fmt} />)}
        </tr>
      ))}
    </tbody>
  </table>
);

/**
 * The page, drawn from what the loader holds: `result` is { rows, since, until },
 * { error } (one line, no table) or { loading }.
 */
export const ConsumptionView = ({ result, timeframe = DEFAULT_TIMEFRAME, onTimeframe, onRefresh, refreshing }) => {
  const r = result || { loading: true };
  const calls = r.rows ? r.rows.filter((x) => x.key !== "total") : null;
  return (
    <div className="fleet-root jev consumptionPage">
      <div className="head"><h1>Account consumption</h1>
        {r.since && <span className="meta">since {new Date(r.since).toLocaleString()}</span>}</div>
      <div className="jevStrip">
        <div className="jevWindows">
          {TIMEFRAMES.map(([k, name]) => (
            <button key={k} className={k === timeframe ? "on" : ""} title={name} onClick={() => onTimeframe && onTimeframe(k)}>{k}</button>
          ))}
          {onRefresh && <button className={"refreshBtn" + (refreshing ? " spinning" : "")} title="Refresh now"
                                disabled={refreshing} onClick={() => onRefresh()}>⟳</button>}
        </div>
      </div>
      {r.loading && <div className="muted">asking the engine what the accounts consumed…</div>}
      {r.error && <div className="err">{String(r.error)}</div>}
      {calls && (calls.length
        ? <ConsumptionTable rows={r.rows} />
        : <div className="muted">no Claude calls recorded in the window</div>)}
      {calls && calls.length > 0 && <div className="muted note">{UNKNOWN} unknown: the engine has no figure for it (its own calls have no cost: it keeps no price table). * a sum over only some of the calls.</div>}
    </div>
  );
};

const ipcError = (e) => ({ error: `IPC failed: ${e && e.message ? e.message : e}` });

/**
 * Explorer > Analytics > AI Accounts > Account consumption: loads the
 * timeframe's rows through `load` (default `fleet.consumption`), again on
 * every timeframe change; never throws.
 */
export const Consumption = ({ load: loadRows } = {}) => {
  const [timeframe, setTimeframe] = useState(DEFAULT_TIMEFRAME);
  const [result, setResult] = useState({ loading: true });
  const [refreshing, setRefreshing] = useState(false);
  // The latest load; an answer to an older one (a timeframe switched meanwhile, or the page gone) is dropped.
  const seq = useRef(0);
  const load = useCallback(async () => {
    const mine = ++seq.current;
    const fetchRows = loadRows || (typeof window !== "undefined" && window.fleet && window.fleet.consumption);
    if (!fetchRows) { setResult({ error: "this build has no consumption channel" }); return; }
    setRefreshing(true);
    let r;
    try { r = await fetchRows(timeframe); }
    catch (e) { r = ipcError(e); }
    if (mine !== seq.current) return;
    setRefreshing(false);
    setResult(r && Array.isArray(r.rows) ? { rows: r.rows, since: r.since, until: r.until } : { error: (r && r.error) || "no response from the main process" });
  }, [timeframe, loadRows]);
  useEffect(() => {
    setResult({ loading: true });
    load();
    return () => { seq.current++; };
  }, [load]);
  return <ConsumptionView result={result} timeframe={timeframe} onTimeframe={setTimeframe} onRefresh={load} refreshing={refreshing} />;
};
