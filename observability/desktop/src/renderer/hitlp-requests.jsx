// hitlp-requests.jsx — HITLP Requests and its Closed child: the HITLP server's
// tasks, from any requester and whoever they wait on (the repo switcher does
// not filter them). The rows are src/needs-you.js `fetchHitlpTasks`'s, the
// one read the window shares with the explorer badge and the urgent popup.
//
// HITLP Requests lists the open tasks (Age, Kind, Priority, Requester,
// Question) under a filter row; a click opens the task below the table with
// the one task view (hitlp-task.jsx `HitlpTask`), as the popup and a ticket's detail draw it. Closed
// lists the decided ones with the decision record's outcome, who, channel and
// when (HITLP spec/schemas/decision-record.schema.json).
import { useEffect, useMemo, useState } from "react";
import { HitlpTask } from "./hitlp-task.jsx";

const nowSecs = () => Math.floor(Date.now() / 1000);

/** "2d 4h" / "3h 07m" / "17m 04s" / "42s": how long a task has waited, to the second under an hour. */
export const fmtAge = (secs) => {
  const s = Math.max(0, Math.floor(secs || 0));
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  const p2 = (n) => String(n).padStart(2, "0");
  if (d) return `${d}d ${h}h`;
  if (h) return `${h}h ${p2(m)}m`;
  if (m) return `${m}m ${p2(r)}s`;
  return `${r}s`;
};

/** "8:12:29" / "31:05:00": H:MM:SS, hours unpadded and past 24 (Closed's time since the decision). */
export const fmtClock = (secs) => {
  const s = Math.max(0, Math.floor(secs || 0));
  const p2 = (n) => String(n).padStart(2, "0");
  return `${Math.floor(s / 3600)}:${p2(Math.floor((s % 3600) / 60))}:${p2(s % 60)}`;
};

/** The age cell, counting up once a second from `since` (epoch seconds) in `fmt`; `now` pins it (tests). */
export const Age = ({ since, now: fixedNow = null, fmt = fmtAge }) => {
  const [, setTick] = useState(0);
  useEffect(() => {
    if (fixedNow != null || since == null) return undefined;
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [since, fixedNow]);
  if (since == null) return <span className="hrAge">—</span>;
  const now = fixedNow != null ? fixedNow : nowSecs();
  return <span className="hrAge" title={new Date(since * 1000).toISOString()}>{fmt(now - since)}</span>;
};

// The server-attested requester, never the asserted one.
const party = (p) => (typeof p === "string" ? p
  : p && typeof p === "object" ? p.id || p.login || p.name || p.client || null : null);
export const requesterOf = (row) => party(row && row.attested ? row.attested.requester : null) || null;

const summaryOf = (row) => (row.context && typeof row.context.summary === "string" && row.context.summary) || null;

/**
 * The question (an ask) or action (an approve) on one line, and under it the
 * ticket as a link that opens it in the app, or the context summary, or "no ticket".
 */
export const QuestionCell = ({ row, onOpenTicket = null, prefix = "" }) => {
  const text = prefix + ((row.kind === "approve" ? row.action || row.ask : row.question || row.ask) || "");
  const linked = row.ticket != null && row.repo;
  return (
    <div className="hrQuestion">
      <div className="hrQ nmOneLine" title={text || ""}>{text}</div>
      <div className="hrSub nmOneLine">
        {linked
          ? <a className="hrTicket" href={row.url} title={`${row.repo}#${row.ticket}`}
               onClick={(e) => { e.preventDefault(); e.stopPropagation(); if (onOpenTicket) onOpenTicket(row.repo, row.ticket); }}>#{row.ticket}</a>
          : null}
        {linked && summaryOf(row) ? " · " : null}
        {summaryOf(row) || (linked ? null : "no ticket")}
      </div>
    </div>
  );
};

export const PRIORITIES = ["urgent", "high", "normal", "low"];
const requestersIn = (rows) => [...new Set(rows.map(requesterOf).filter(Boolean))].sort();

/** The open rows the filters keep: `requester` and `priority` ("" for any) and `q` over the question or action, case-insensitive. */
export const filterOpen = (rows, { requester = "", priority = "", q = "" } = {}) => {
  const needle = String(q || "").trim().toLowerCase();
  return (rows || []).filter((r) => (!requester || requesterOf(r) === requester)
    && (!priority || (r.priority || "normal") === priority)
    && (!needle || String((r.kind === "approve" ? r.action || r.ask : r.question || r.ask) || "").toLowerCase().includes(needle)));
};

export const WINDOWS = [
  { id: "today", label: "Today" },
  { id: "7d", label: "7 days", secs: 7 * 86400 },
  { id: "30d", label: "30 days", secs: 30 * 86400 },
  { id: "all", label: "All" },
];
/** The epoch second a window starts at; null for all. "today" is since local midnight. */
export const windowStart = (id, now) => {
  if (id === "today") { const d = new Date(now * 1000); d.setHours(0, 0, 0, 0); return Math.floor(d.getTime() / 1000); }
  const w = WINDOWS.find((x) => x.id === id);
  return w && w.secs ? now - w.secs : null;
};

/** The closed rows the filters keep: `requester`, `outcome` ("" for any) and a `window` over the decided time. */
export const filterClosed = (rows, { requester = "", outcome = "", window = "all" } = {}, now = nowSecs()) => {
  const start = windowStart(window, now);
  return (rows || []).filter((r) => (!requester || requesterOf(r) === requester)
    && (!outcome || r.outcome === outcome)
    && (start == null || (r.decidedAt != null && r.decidedAt >= start)));
};

const Select = ({ label, value, onChange, options }) => (
  <label className="hrFilter">{label}{" "}
    <select value={value} onChange={(e) => onChange(e.target.value)} aria-label={label}>
      {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
    </select>
  </label>
);
const anyAnd = (vals) => [{ value: "", label: "Any" }, ...vals.map((v) => ({ value: v, label: v }))];

const Head = ({ title, count, yellow, host, onRefresh, refreshing }) => (
  <div className="head">
    <h1>{title}{count ? <> · <span className={yellow ? "hrCountOpen" : "hrCount"}>{count}</span></> : null}</h1>
    <span className="meta">
      {host && <span className="hrHost">{host}</span>}
      {onRefresh && <button className={"refreshBtn" + (refreshing ? " spinning" : "")} title="Refresh now"
        disabled={refreshing} onClick={() => onRefresh()}>⟳</button>}
    </span>
  </div>
);

// The not-set line, a failed GET /tasks, or loading; null when the rows can be drawn.
const problem = (result) => {
  if (!result || result.loading) return <div className="nmEmpty">asking the HITLP server…</div>;
  if (result.notSet) return <div className="nmEmpty hrNotSet">No HITLP server is set: set one in Config &gt; frakworks &gt; HITLP server.</div>;
  if (result.error) return <div className="err">{String(result.error)}</div>;
  return null;
};

/**
 * HITLP Requests: `result` is `fetchHitlpTasks`'s `{ open, closed, error,
 * notSet, host }`. `sent` holds the ids decided here and not yet dropped by
 * the server (drawn muted), `failed` an error by id; `onSent`, `onFailed`,
 * `onDecided` are index.jsx's. `decide` stands in for the bridge (tests).
 */
export const HitlpRequests = ({ result, now = null, onOpenTicket = null, onRefresh = null, refreshing = false,
  sent = null, failed = {}, onSent = null, onFailed = null, onDecided = null, decide = null, initialOpen = null }) => {
  const [requester, setRequester] = useState("");
  const [priority, setPriority] = useState("");
  const [q, setQ] = useState("");
  const [openId, setOpenId] = useState(initialOpen);
  const all = (result && result.open) || [];
  const rows = useMemo(() => filterOpen(all, { requester, priority, q }), [all, requester, priority, q]);
  const head = <Head title="HITLP Requests" count={all.length} yellow host={result && result.host} onRefresh={onRefresh} refreshing={refreshing} />;
  const p = problem(result);
  if (p) return <div className="nmView hrView">{head}{p}</div>;
  const task = openId ? all.find((r) => r.actionId === openId) || null : null;
  return (
    <div className="nmView hrView">
      {head}
      <div className="hrFilters">
        <Select label="Requester" value={requester} onChange={setRequester} options={anyAnd(requestersIn(all))} />
        <Select label="Priority" value={priority} onChange={setPriority} options={anyAnd(PRIORITIES)} />
        <input className="acInput hrSearch" type="search" placeholder="search the question" aria-label="Search the question"
          value={q} onChange={(e) => setQ(e.target.value)} />
      </div>
      {!all.length ? <div className="nmEmpty">No HITLP task is open.</div> : (
        <table className="hrTable">
          <thead><tr><th>Age</th><th>Kind</th><th>Priority</th><th>Requester</th><th>Question</th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.key} data-task={r.actionId} className={"hrRow" + (r.actionId === openId ? " on" : "") + (sent && sent.has(r.actionId) ? " muted" : "")}
                  onClick={() => setOpenId(r.actionId === openId ? null : r.actionId)}>
                <td className="hrMono"><Age since={r.since} now={now} /></td>
                <td>{r.kind}</td>
                <td><span className={"hrPrio" + (r.priority === "urgent" ? " urgent" : "")}>{r.priority || "normal"}</span></td>
                <td className="hrMono">{requesterOf(r) || "—"}</td>
                <td><QuestionCell row={r} onOpenTicket={onOpenTicket} /></td>
              </tr>
            ))}
            {!rows.length && <tr><td colSpan={5} className="nmEmpty">No open task matches the filters.</td></tr>}
          </tbody>
        </table>
      )}
      {task && (
        <div className="hrDetail" data-open-task={task.actionId}>
          <div className="hrDetailHead">
            <span className="hrMono">task {task.actionId}</span>
            {task.ticket != null && task.repo && <> · <a className="hrTicket" href={task.url}
              onClick={(e) => { e.preventDefault(); if (onOpenTicket) onOpenTicket(task.repo, task.ticket); }}>{task.repo}#{task.ticket}</a></>}
          </div>
          <HitlpTask key={task.actionId} row={task} now={now} decide={decide} initialError={failed[task.actionId] || null}
            onSent={onSent} onDecided={onDecided} onFailed={onFailed} />
        </div>
      )}
    </div>
  );
};

export const OUTCOMES = ["answered", "approved", "rejected", "timed_out", "cancelled", "failed"];
// The decisions a closed row is colored by; any other outcome stays plain.
const ROW_COLORED = ["approved", "rejected", "answered"];
export const closedRowClass = (outcome) => "hrRow" + (ROW_COLORED.includes(outcome) ? " hrRow-" + outcome : "");
const when = (secs) => (secs == null ? "—" : new Date(secs * 1000).toLocaleString());

/** HITLP Requests > Closed: the decided tasks, newest first, with the decision. */
export const HitlpClosed = ({ result, now = null, onOpenTicket = null, onRefresh = null, refreshing = false }) => {
  const [requester, setRequester] = useState("");
  const [outcome, setOutcome] = useState("");
  const [win, setWin] = useState("all");
  const all = (result && result.closed) || [];
  const at = now != null ? now : nowSecs();
  const rows = filterClosed(all, { requester, outcome, window: win }, at);
  const head = <Head title="Closed" count={all.length} host={result && result.host} onRefresh={onRefresh} refreshing={refreshing} />;
  const p = problem(result);
  if (p) return <div className="nmView hrView">{head}{p}</div>;
  const outcomes = [...new Set([...OUTCOMES.filter((o) => all.some((r) => r.outcome === o)), ...all.map((r) => r.outcome)])];
  return (
    <div className="nmView hrView">
      {head}
      <div className="hrFilters">
        <Select label="Requester" value={requester} onChange={setRequester} options={anyAnd(requestersIn(all))} />
        <Select label="Outcome" value={outcome} onChange={setOutcome} options={anyAnd(outcomes)} />
        <Select label="Window" value={win} onChange={setWin} options={WINDOWS.map((w) => ({ value: w.id, label: w.label }))} />
      </div>
      {!all.length ? <div className="nmEmpty">No HITLP task is closed.</div> : (
        <table className="hrTable">
          <thead><tr><th>Decided</th><th>Requester</th><th>Question</th><th>Outcome</th><th>Decided by</th><th>Channel</th><th>When</th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.key} data-task={r.actionId} className={closedRowClass(r.outcome)}>
                <td className="hrMono"><Age since={r.decidedAt} now={now} fmt={fmtClock} /></td>
                <td className="hrMono">{requesterOf(r) || "—"}</td>
                <td><QuestionCell row={r} onOpenTicket={onOpenTicket} prefix={r.kind ? r.kind + " - " : ""} /></td>
                <td><span className={"hrOutcome hrOutcome-" + r.outcome}>{r.outcome}</span></td>
                <td>{r.decidedBy || "—"}</td>
                <td>{r.channel || "—"}</td>
                <td className="hrMono" title={r.decidedAt != null ? new Date(r.decidedAt * 1000).toISOString() : ""}>{when(r.decidedAt)}</td>
              </tr>
            ))}
            {!rows.length && <tr><td colSpan={7} className="nmEmpty">No closed task matches the filters.</td></tr>}
          </tbody>
        </table>
      )}
    </div>
  );
};
