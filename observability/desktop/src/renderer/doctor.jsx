// doctor.jsx — the Doctor page (Explorer > Analytics > Doctor, #706): the
// doctor's runs across every ticket over a window, each classified as a
// success or a failure, with the totals in a top strip. Native React over the
// engine's read-only GET /doctor/runs?since= (engine/README.md, "Doctor"),
// through main (window.fleet.doctorRuns); no Grafana. The window switch and
// the strip follow the Jev decisions page (jev.jsx) and share its classes.
import { useCallback, useEffect, useRef, useState } from "react";
import { WINDOWS, DEFAULT_WINDOW, sinceOf, fmtPct } from "./jev.jsx";

export { WINDOWS, DEFAULT_WINDOW, sinceOf };

// ---- the success classification ------------------------------------------------
//
// A run's verdict (observability/desktop/README.md, "Analytics > Doctor"):
// - `applied` is "succeeded" when no later doctor run exists on the same root
//   instance at the same leaves (the position minus its `incident:<id>`
//   entries: a retried rejected-result incident reruns the Smith and reopens
//   under a new id), and "failed" when one does (the ticket got stuck again
//   where the doctor "fixed" it);
// - `failed` (the doctor's own Smith run failed before any remedy) is "failed";
// - `none` (remedy `healthy` or `unfixable`) is "none", no intervention;
// - `running` is "running", not judged yet.
// Anything else is "unknown" and counted in none of them.

/** A run's position as one key: the engine's leaves and incidents, comma-joined (TicketPosition.key). */
export const positionKey = (run) => (Array.isArray(run && run.position) ? run.position.join(",") : String((run && run.position) ?? ""));

/**
 * A run's leaves as one key: the position's entries that are not
 * `incident:<id>`, sorted and comma-joined. A position with no leaf entries
 * (only incidents, or empty) keys on its full position instead, so two such
 * runs never compare equal on an empty key by accident.
 */
export const leavesKey = (run) => {
  const position = Array.isArray(run && run.position) ? run.position : [];
  const leaves = position.filter((p) => !String(p).startsWith("incident:")).sort();
  return leaves.length ? `leaves:${leaves.join(",")}` : `position:${positionKey(run)}`;
};

const at = (iso) => { const t = Date.parse(iso); return Number.isFinite(t) ? t : 0; };

/** Newest first, as the engine orders them: startedAt, then id, descending. */
export const newestFirst = (a, b) => (at(b.startedAt) - at(a.startedAt)) || String(b.id).localeCompare(String(a.id));

/**
 * Each run with its `verdict` ("succeeded" | "failed" | "none" | "running" |
 * "unknown") and, for a failure, `why`, newest first. Pure: it reads the runs
 * given and nothing else, so the window must reach now (it does: GET
 * /doctor/runs answers from `since` to now) for every later run to be in it.
 */
export const classifyDoctorRuns = (runs) => {
  const sorted = [...(runs || [])].filter(Boolean).sort(newestFirst);
  // The newest run seen so far per root instance and leaves: walking newest
  // first, a run whose key was already seen has a later run there.
  const seen = new Set();
  return sorted.map((run) => {
    const key = `${run.rootInstanceId}\u0000${leavesKey(run)}`;
    const again = seen.has(key);
    seen.add(key);
    switch (run.outcome) {
      case "applied": return again ? { ...run, verdict: "failed", why: "stuck again at the same leaves" } : { ...run, verdict: "succeeded" };
      case "failed": return { ...run, verdict: "failed", why: "the doctor run failed (no remedy was applied)" };
      case "none": return { ...run, verdict: "none" };
      case "running": return { ...run, verdict: "running" };
      default: return { ...run, verdict: "unknown" };
    }
  });
};

/**
 * The strip's counts over classified runs: runs, distinct tickets, and each
 * verdict; `judged` is succeeded + failed, `rate` succeeded / judged or null
 * when nothing could be judged.
 */
export const doctorStats = (classified) => {
  const list = classified || [];
  const n = (v) => list.filter((r) => r.verdict === v).length;
  const succeeded = n("succeeded"), failed = n("failed");
  const judged = succeeded + failed;
  return {
    runs: list.length,
    tickets: new Set(list.map((r) => `${r.repo}#${r.issue}`)).size,
    succeeded, failed, none: n("none"), running: n("running"),
    judged, rate: judged ? succeeded / judged : null,
  };
};

// ---- formatting --------------------------------------------------------------

export const DASH = "—";
/** An ISO date-time as UTC to the minute, so every viewer reads the same one; `—` for none. */
export const fmtUtc = (iso) => {
  const t = Date.parse(iso);
  return iso && Number.isFinite(t) ? `${new Date(t).toISOString().replace("T", " ").slice(0, 16)} UTC` : DASH;
};
/** How long ago `iso` was, from `now`: "02:36 ago", "3:07:12 ago", "4d 2h ago"; never negative; `—` for none. */
export const fmtSinceStarted = (iso, now) => {
  const t = Date.parse(iso);
  if (!iso || !Number.isFinite(t)) return DASH;
  const s = Math.max(0, Math.floor((now - t) / 1000));
  const p = (n) => String(n).padStart(2, "0");
  if (s < 3600) return `${p(Math.floor(s / 60))}:${p(s % 60)} ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}:${p(Math.floor(s / 60) % 60)}:${p(s % 60)} ago`;
  return `${Math.floor(s / 86400)}d ${Math.floor(s / 3600) % 24}h ago`;
};
const orDash = (v) => (v == null || v === "" ? DASH : v);

export const VERDICT_LABELS = { succeeded: "succeeded", failed: "failed", none: "no intervention", running: "running", unknown: DASH };

// ---- the page ----------------------------------------------------------------

/** The top strip: the window switch, runs, tickets, "succeeded / judged" (or "–") and the four counts. */
export const DoctorStrip = ({ stats, win, onWindow, onRefresh, refreshing }) => {
  const s = stats || {};
  const num = (v) => (v != null ? v : "–");
  return (
    <div className="jevStrip doctorStrip">
      <div className="jevWindows">
        {WINDOWS.map(([k]) => (
          <button key={k} className={k === win ? "on" : ""} onClick={() => onWindow && onWindow(k)}>{k}</button>
        ))}
        {onRefresh && <button className={"refreshBtn" + (refreshing ? " spinning" : "")} title="Refresh now"
                              disabled={refreshing} onClick={() => onRefresh()}>⟳</button>}
      </div>
      <div className="jevStat"><b>{num(s.runs)}</b><span>runs</span></div>
      <div className="jevStat"><b>{num(s.tickets)}</b><span>tickets</span></div>
      <div className="jevStat" title={s.judged ? `${s.succeeded} of ${s.judged} judged runs moved the ticket on (${fmtPct(s.rate)})` : "no run could be judged yet"}>
        <b>{s.judged ? `${s.succeeded} / ${s.judged}` : "–"}</b><span>{s.judged ? `succeeded / judged · ${fmtPct(s.rate)}` : "succeeded / judged"}</span>
      </div>
      <div className="jevStat doctorOk"><b>{num(s.succeeded)}</b><span>succeeded</span></div>
      <div className="jevStat doctorBad"><b>{num(s.failed)}</b><span>failed</span></div>
      <div className="jevStat"><b>{num(s.none)}</b><span>no intervention</span></div>
      <div className="jevStat"><b>{num(s.running)}</b><span>running</span></div>
    </div>
  );
};

const COLUMNS = 7;

/** One run as a row, and when open, a second row with its detail and stuckSince. */
const DoctorRunRow = ({ run, open, onToggle, onOpenTicket, now }) => (
  <>
    <tr className={`jevRow doctorRow verdict-${run.verdict}${open ? " open" : ""}`} title={open ? "Hide this run's detail" : "Show this run's detail"}
        onClick={() => onToggle && onToggle(run.id)}>
      <td className="jevWhen" title={fmtUtc(run.startedAt)}>{fmtSinceStarted(run.startedAt, now)}</td>
      <td className="jevText" title={run.issue != null ? `${run.repo}#${run.issue}` : ""}>{run.issue != null
        ? <a href={`https://github.com/${run.repo}/issues/${run.issue}`} title={`${run.repo}#${run.issue}`}
             onClick={(e) => { e.stopPropagation(); if (onOpenTicket && !(e.metaKey || e.ctrlKey || e.shiftKey)) { e.preventDefault(); onOpenTicket(run.repo, run.issue); } }}>{run.repo}#{run.issue}</a>
        : DASH}</td>
      <td className="jevText" title={positionKey(run)}>{orDash(positionKey(run))}</td>
      <td className="jevText" title={run.diagnosis || ""}>{orDash(run.diagnosis)}</td>
      <td className="jevText" title={run.remedy ? (run.target ? `${run.remedy} → ${run.target}` : run.remedy) : ""}>{run.remedy ? (run.target ? `${run.remedy} → ${run.target}` : run.remedy) : DASH}</td>
      <td className="jevText" title={run.outcome || ""}>{orDash(run.outcome)}</td>
      <td className="doctorVerdict" title={run.why || ""}>{VERDICT_LABELS[run.verdict] || DASH}{run.why ? <span className="muted"> · {run.why}</span> : null}</td>
    </tr>
    {open && (
      <tr className="doctorDetail">
        <td colSpan={COLUMNS}>
          <div><span className="muted">stuck since</span> {fmtUtc(run.stuckSince)}
            <span className="muted"> · ended</span> {fmtUtc(run.endedAt)}
            <span className="muted"> · root instance</span> {orDash(run.rootInstanceId)}
            <span className="muted"> · run</span> {orDash(run.id)}</div>
          <div className="doctorDetailText"><span className="muted">detail</span> {orDash(run.detail)}</div>
        </td>
      </tr>
    )}
  </>
);

/** The runs, newest first: started, ticket, position, diagnosis, remedy → target, outcome, result. */
export const DoctorRunsTable = ({ runs, open, onToggle, onOpenTicket, now = Date.now() }) => (
  <table className="jevCalls doctorRuns">
    <thead>
      <tr><th className="jevWhen">started</th><th>ticket</th><th>position</th><th>diagnosis</th><th>remedy → target</th><th>outcome</th><th>result</th></tr>
    </thead>
    <tbody>
      {runs.map((r) => (
        <DoctorRunRow key={r.id} run={r} open={!!(open && open[r.id])} onToggle={onToggle} onOpenTicket={onOpenTicket} now={now} />
      ))}
    </tbody>
  </table>
);

/**
 * The page, drawn from what the loader holds: `result` is { runs, since },
 * { error } (one line, no table) or { loading }; `open` maps a run id to
 * whether its detail row shows.
 */
export const DoctorRunsView = ({ result, win = DEFAULT_WINDOW, onWindow, onRefresh, refreshing, open, onToggle, onOpenTicket, now }) => {
  const r = result || { loading: true };
  const classified = r.runs ? classifyDoctorRuns(r.runs) : null;
  const stats = classified ? doctorStats(classified) : null;
  return (
    <div className="fleet-root jev doctor">
      <div className="head"><h1>Doctor</h1>
        {r.since && <span className="meta">since {fmtUtc(r.since)}</span>}</div>
      <DoctorStrip stats={stats} win={win} onWindow={onWindow} onRefresh={onRefresh} refreshing={refreshing} />
      {r.loading && <div className="muted">asking the engine for the doctor's runs…</div>}
      {r.error && <div className="err">{String(r.error)}</div>}
      {classified && (classified.length
        ? <DoctorRunsTable runs={classified} open={open} onToggle={onToggle} onOpenTicket={onOpenTicket} now={now} />
        : <div className="muted">no doctor runs in the window</div>)}
    </div>
  );
};

/** Ticks every second, so the started column moves without a refetch; stops on unmount. */
const useNow = () => {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  return now;
};

const ipcError = (e) => ({ error: `IPC failed: ${e && e.message ? e.message : e}` });

/** Explorer > Analytics > Doctor: loads the window's runs through `fleet.doctorRuns`; never throws. */
export const DoctorRuns = ({ onOpenTicket }) => {
  const [win, setWin] = useState(DEFAULT_WINDOW);
  const [result, setResult] = useState({ loading: true });
  const [refreshing, setRefreshing] = useState(false);
  const [open, setOpen] = useState({});
  // The latest load; an answer to an older one (a window switched meanwhile, or the page gone) is dropped.
  const seq = useRef(0);
  const load = useCallback(async () => {
    const mine = ++seq.current;
    if (!(typeof window !== "undefined" && window.fleet && window.fleet.doctorRuns)) {
      setResult({ error: "this build has no Doctor channel" });
      return;
    }
    setRefreshing(true);
    let r;
    try { r = await window.fleet.doctorRuns(sinceOf(win, Date.now())); }
    catch (e) { r = ipcError(e); }
    if (mine !== seq.current) return;
    setRefreshing(false);
    setResult(r && Array.isArray(r.runs) ? { runs: r.runs, since: r.since } : { error: (r && r.error) || "no response from the main process" });
  }, [win]);
  useEffect(() => {
    setResult({ loading: true });
    load();
    return () => { seq.current++; };
  }, [load]);
  const now = useNow();
  const onToggle = useCallback((id) => setOpen((prev) => ({ ...prev, [id]: !prev[id] })), []);
  return <DoctorRunsView result={result} win={win} onWindow={setWin} onRefresh={load} refreshing={refreshing}
                         open={open} onToggle={onToggle} onOpenTicket={onOpenTicket} now={now} />;
};
