// jev.jsx — the Jev decisions page (Explorer > Analytics > Jev decisions) and
// the ticket detail's Jev section (#553). Native React over the engine's
// read-only GET /jev/summary, /jev/calls and /jev/calls/{id} (engine/README.md,
// "Jev decisions"), through main (window.fleet.jevSummary/jevCalls/jevCall);
// no Grafana. Charts follow dashboard.jsx's SVG idiom and colour only through
// the palette's CSS variables and currentColor, so they read on either
// background.
import { useCallback, useEffect, useRef, useState } from "react";
import { JsonView } from "./json-view.jsx";

// The use cases the engine asks Jev about, in the page's order (the engine's
// JevSummaries.USE_CASES), with what each section is titled.
export const USE_CASES = [
  ["acceptance", "Acceptance"],
  ["proposal-decision", "Proposal decision"],
  ["ci-triage", "CI triage"],
  ["refine-pick", "Refine pick"],
  ["incident-triage", "Incident triage"],
  ["intake-duplicate", "Intake duplicate"],
];
const TITLES = Object.fromEntries(USE_CASES);

// The top strip's window switch; 7 days is what the page opens on.
export const WINDOWS = [["24h", 1], ["7d", 7], ["30d", 30], ["90d", 90]];
export const DEFAULT_WINDOW = "7d";
const DAY_MS = 86400000;

/** The window's start as an ISO instant, `now` in ms. */
export const sinceOf = (win, now) => {
  const days = (WINDOWS.find(([k]) => k === win) || WINDOWS[1])[1];
  return new Date(now - days * DAY_MS).toISOString();
};

// How many calls each section's table asks for at first, and per "more".
export const CALLS_PAGE = 20;

// The picks chart's cap: a question with more options than this (the
// duplicate check's "#n" tickets) shows its top ones and "other".
export const TOP_PICKS = 6;

// ---- formatting --------------------------------------------------------------

export const fmtPct = (x) => (x == null ? "–" : `${Math.round(x * 100)}%`);
export const fmtMs = (ms) => (ms == null ? "–" : ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`);
export const fmtConf = (c) => (c == null ? "–" : Number(c).toFixed(2));
// UTC, as the engine stores it, so every viewer reads the same minute.
export const fmtAt = (iso) => (iso ? `${String(iso).replace("T", " ").slice(0, 16)} UTC` : "–");

/** A question's counts as bars: the top `n`, the rest summed as "other". */
export const topPicks = (counts, n = TOP_PICKS) => {
  const all = Object.entries(counts || {}).filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]);
  if (all.length <= n) return all;
  const rest = all.slice(n - 1).reduce((s, [, v]) => s + v, 0);
  return [...all.slice(0, n - 1), ["other", rest]];
};

/** Every UTC day from `since` to `until` with its call count, zeros included. */
export const daysOf = (perDay, since, until) => {
  const out = [];
  const from = Date.parse(String(since).slice(0, 10) + "T00:00:00Z");
  const to = Date.parse(until);
  if (!Number.isFinite(from) || !Number.isFinite(to)) return Object.entries(perDay || {});
  for (let t = from; t <= to && out.length < 400; t += DAY_MS) {
    const d = new Date(t).toISOString().slice(0, 10);
    out.push([d, (perDay || {})[d] || 0]);
  }
  return out;
};

// The refine-pick call an intake names in the refined ticket's body trailer,
// `<!-- engine: intake … · jev <uuid> -->`. For an intake filed since one issue
// per ask its row's issue is this one (the ask item refined into the
// ticket); an older ticket's row carries its separate intake issue, so the
// trailer still merges it in.
const TRAILER_JEV = /<!-- engine: intake [^>]*?·\s*jev\s+([0-9a-f-]{36})/i;
export const jevCallIdOf = (body) => {
  const m = TRAILER_JEV.exec(String(body || ""));
  return m ? m[1].toLowerCase() : null;
};

/** Calls from several reads as one list, each once, oldest first. */
export const mergeCalls = (...lists) => {
  const byId = new Map();
  for (const list of lists) for (const c of list || []) if (c && c.id && !byId.has(c.id)) byId.set(c.id, c);
  return [...byId.values()].sort((a, b) => String(a.called_at).localeCompare(String(b.called_at)));
};

// ---- charts ------------------------------------------------------------------

const W = 520, CH = 70;
const AXIS = { stroke: "currentColor", strokeOpacity: 0.15 };
const BAR = "var(--p-impl)";

const NoData = ({ h = CH }) => (
  <svg width={W} height={h} className="jevChart"><text x={W / 2} y={h / 2} textAnchor="middle">no calls in the window</text></svg>
);

/** Calls per UTC day, one bar a day across the window. */
export const PerDayBars = ({ perDay, since, until }) => {
  const days = daysOf(perDay, since, until);
  const max = Math.max(0, ...days.map(([, v]) => v));
  if (!max) return <NoData />;
  const bw = W / days.length, y = (v) => CH - (v / max) * (CH - 12);
  return (
    <svg width={W} height={CH + 14} className="jevChart">
      <text x={W - 2} y={9} textAnchor="end">{max}</text>
      <line x1="0" x2={W} y1={CH} y2={CH} style={AXIS} />
      {days.map(([d, v], i) => (
        <rect key={d} x={i * bw + 1} width={Math.max(1, bw - 2)} y={y(v)} height={CH - y(v)} style={{ fill: BAR, fillOpacity: 0.85 }}>
          <title>{`${d}: ${v} call${v === 1 ? "" : "s"}`}</title>
        </rect>
      ))}
      <text x={0} y={CH + 11} textAnchor="start">{days[0][0]}</text>
      <text x={W} y={CH + 11} textAnchor="end">{days[days.length - 1][0]}</text>
    </svg>
  );
};

/** One question's picks as horizontal bars, labelled and counted. */
export const PickBars = ({ question, counts }) => {
  const rows = topPicks(counts);
  if (!rows.length) return null;
  const max = Math.max(...rows.map(([, v]) => v));
  const LW = 120, RH = 14;
  return (
    <div className="jevPicks">
      <div className="jevQ">{question}</div>
      <svg width={W} height={rows.length * RH + 2} className="jevChart">
        {rows.map(([opt, v], i) => (
          <g key={opt}>
            <text x={LW - 6} y={i * RH + 10} textAnchor="end">{opt}</text>
            <rect x={LW} y={i * RH + 2} height={RH - 4} width={Math.max(1, ((W - LW - 30) * v) / max)}
                  style={{ fill: opt === "other" ? "currentColor" : BAR, fillOpacity: opt === "other" ? 0.3 : 0.85 }} />
            <text x={LW + ((W - LW - 30) * v) / max + 4} y={i * RH + 10}>{v}</text>
          </g>
        ))}
      </svg>
    </div>
  );
};

/** Ten 0.1 buckets of confidence, the floor in force drawn as a line. */
export const ConfidenceHistogram = ({ confidence, floor }) => {
  const b = confidence || [];
  const max = Math.max(0, ...b);
  if (!max) return <NoData />;
  const bw = W / 10, y = (v) => CH - (v / max) * (CH - 12);
  return (
    <svg width={W} height={CH + 14} className="jevChart">
      <text x={W - 2} y={9} textAnchor="end">{max}</text>
      <line x1="0" x2={W} y1={CH} y2={CH} style={AXIS} />
      {b.map((v, i) => (
        <rect key={i} x={i * bw + 2} width={bw - 4} y={y(v)} height={CH - y(v)} style={{ fill: BAR, fillOpacity: 0.85 }}>
          <title>{`${(i / 10).toFixed(1)}–${((i + 1) / 10).toFixed(1)}: ${v}`}</title>
        </rect>
      ))}
      {floor != null && (
        <g className="jevFloor">
          <line x1={floor * W} x2={floor * W} y1={0} y2={CH} style={{ stroke: "var(--sev-warn)", strokeWidth: 1.5, strokeDasharray: "3 2" }} />
          <text x={floor * W + (floor > 0.85 ? -3 : 3)} y={9} textAnchor={floor > 0.85 ? "end" : "start"}
                style={{ fill: "var(--sev-warn)" }}>{`floor ${fmtConf(floor)}`}</text>
        </g>
      )}
      {[0, 0.5, 1].map((f) => (
        <text key={f} x={f * W} y={CH + 11} textAnchor={f === 0 ? "start" : f === 1 ? "end" : "middle"}>{f.toFixed(1)}</text>
      ))}
    </svg>
  );
};

// ---- the calls table and the drawer ------------------------------------------

/**
 * Calls as rows: when, ticket (a link that opens the ticket detail; left out
 * with `noTicket`), use case (with `withUseCase`), pick, confidence, action,
 * outcome. A row opens the call in the drawer.
 */
export const JevCallsTable = ({ calls, onOpenCall, onOpenTicket, noTicket, withUseCase }) => (
  <table className="jevCalls">
    <thead>
      <tr>
        <th>called</th>{!noTicket && <th>ticket</th>}{withUseCase && <th>use case</th>}
        <th>pick</th><th className="num">conf.</th><th>action</th><th>outcome</th>
      </tr>
    </thead>
    <tbody>
      {calls.map((c) => (
        <tr key={c.id} className={"jevRow" + (c.no_answer ? " noAnswer" : "")} title="Open this call"
            onClick={() => onOpenCall && onOpenCall(c.id)}>
          <td className="jevWhen">{fmtAt(c.called_at)}</td>
          {!noTicket && (
            <td>{c.issue != null
              ? <a href={`https://github.com/${c.repo}/issues/${c.issue}`} title={`${c.repo}#${c.issue}`}
                   onClick={(e) => { e.stopPropagation(); if (onOpenTicket && !(e.metaKey || e.ctrlKey || e.shiftKey)) { e.preventDefault(); onOpenTicket(c.repo, c.issue); } }}>#{c.issue}</a>
              : <span className="muted">–</span>}</td>
          )}
          {withUseCase && <td>{TITLES[c.use_case] || c.use_case}</td>}
          <td>{c.no_answer ? <span className="muted">no answer</span> : c.pick || "–"}</td>
          <td className="num">{fmtConf(c.confidence)}</td>
          <td className="jevText" title={c.action || ""}>{c.action || <span className="muted">–</span>}</td>
          <td className="jevText" title={c.outcome || ""}>{c.outcome || <span className="muted">–</span>}</td>
        </tr>
      ))}
    </tbody>
  </table>
);


/** One question's probabilities as bars, the pick marked. */
export const Probabilities = ({ pick }) => {
  const rows = Object.entries(pick.probabilities || {}).sort((a, b) => b[1] - a[1]);
  return (
    <div className="jevProbs">
      <div className="jevQ">{pick.question} <span className="muted">· {pick.type} · picked</span> {pick.pick || "–"}
        <span className="muted"> at {fmtConf(pick.confidence)}</span></div>
      {rows.map(([opt, p]) => (
        <div key={opt} className="jevProb">
          <span className="jevProbOpt">{opt}</span>
          <span className="jevProbBar"><i style={{ width: `${Math.round(p * 100)}%` }} /></span>
          <span className="jevProbVal">{fmtConf(p)}</span>
        </div>
      ))}
    </div>
  );
};

/**
 * One call in full, over the page: the state and questions as sent, each
 * question's probabilities, and the outcome or the error. `state` is null
 * while closed, { loading }, { call } or { error }.
 */
export const JevCallDrawer = ({ state, onClose, onOpenTicket }) => {
  if (!state) return null;
  const c = state.call;
  return (
    <div className="jevDrawer" role="dialog">
      <div className="jevDrawerHead">
        <b>{c ? `${TITLES[c.use_case] || c.use_case} · ${fmtAt(c.called_at)}` : "Jev call"}</b>
        <button className="jevClose" title="Close" onClick={() => onClose && onClose()}>×</button>
      </div>
      {state.loading && <div className="muted">asking the engine for the call…</div>}
      {state.error && <div className="err">{String(state.error)}</div>}
      {c && (
        <>
          <div className="jevMeta">
            {c.issue != null && (
              <a href={`https://github.com/${c.repo}/issues/${c.issue}`}
                 onClick={(e) => { if (onOpenTicket) { e.preventDefault(); onOpenTicket(c.repo, c.issue); } }}>{c.repo}#{c.issue}</a>
            )}
            <span>{c.model || "?"}</span>
            <span>{fmtMs(c.latency_ms)}</span>
            {c.threshold != null && <span>floor {fmtConf(c.threshold)}</span>}
          </div>
          <h3>Answer</h3>
          {c.error
            ? <div className="err">{c.error}</div>
            : (c.picks || []).length
              ? c.picks.map((p) => <Probabilities key={p.question} pick={p} />)
              : <div className="muted">no answer</div>}
          <h3>Action</h3>
          <div>{c.action || <span className="muted">none recorded</span>}</div>
          <h3>Outcome</h3>
          <div>{c.outcome
            ? <>{c.outcome}{c.outcome_at && <span className="muted"> · {fmtAt(c.outcome_at)}</span>}</>
            : <span className="muted">not yet known</span>}</div>
          <h3>Questions as sent</h3>
          {Object.entries(c.questions || {}).map(([k, q]) => (
            <div key={k} className="jevQuestion">
              <div className="jevQ">{k} <span className="muted">· {q && q.type}</span></div>
              <JsonView value={q && q.instructions} />
              {q && q.criteria != null && <JsonView value={q.criteria} />}
            </div>
          ))}
          <h3>State as sent</h3>
          {c.state == null ? <pre className="jevPre">–</pre> : <JsonView value={c.state} />}
          <h3>Raw request</h3>
          <div className="jevRawRequest">{c.request == null ? <pre className="jevPre">–</pre> : <JsonView value={c.request} />}</div>
          <h3>Raw response</h3>
          <div className="jevRawResponse">{c.response == null
            ? (c.error ? <pre className="jevPre err">{c.error}</pre> : <pre className="jevPre">–</pre>)
            : <JsonView value={c.response} />}</div>
        </>
      )}
    </div>
  );
};

// Opens a call in the drawer through `fleet.jevCall`; the drawer's states as above.
const useDrawer = () => {
  const [drawer, setDrawer] = useState(null);
  const want = useRef(null);
  const open = useCallback(async (id) => {
    want.current = id;
    setDrawer({ loading: true });
    let r;
    try { r = await window.fleet.jevCall(id); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    if (want.current !== id) return;
    setDrawer(r && r.call ? { call: r.call } : { error: (r && r.error) || "no response from the main process" });
  }, []);
  const close = useCallback(() => { want.current = null; setDrawer(null); }, []);
  return [drawer, open, close];
};

// ---- the page ----------------------------------------------------------------

/** The top strip: the window switch and the totals. Agreement is "–" when nothing could be judged. */
export const JevStrip = ({ totals, win, onWindow, onRefresh, refreshing }) => {
  const t = totals || {};
  return (
    <div className="jevStrip">
      <div className="jevWindows">
        {WINDOWS.map(([k]) => (
          <button key={k} className={k === win ? "on" : ""} onClick={() => onWindow && onWindow(k)}>{k}</button>
        ))}
        {onRefresh && <button className={"refreshBtn" + (refreshing ? " spinning" : "")} title="Refresh now"
                              disabled={refreshing} onClick={() => onRefresh()}>⟳</button>}
      </div>
      <div className="jevStat"><b>{t.calls != null ? t.calls : "–"}</b><span>calls</span></div>
      <div className="jevStat"><b>{t.calls ? fmtPct(t.noAnswer / t.calls) : "–"}</b><span>no answer</span></div>
      <div className="jevStat"><b>{fmtMs(t.medianLatencyMs)}</b><span>median latency</span></div>
      <div className="jevStat" title={t.judged ? `${t.agreed} of ${t.judged} judged outcomes bore the pick out` : "no outcome could be judged yet"}>
        <b>{fmtPct(t.agreement)}</b><span>agreement</span>
      </div>
    </div>
  );
};

/**
 * One use case: calls per day, picks per question, the confidence histogram
 * with its floor, action counts, runs saved (the two triage use cases), and
 * its latest calls. `calls` is { calls, total } or { error } or { loading }.
 */
export const JevUseCase = ({ uc, since, until, calls, onMore, onOpenCall, onOpenTicket }) => {
  const actions = Object.entries(uc.actions || {});
  const list = (calls && calls.calls) || [];
  // Folded or open per use case, remembered per viewer like the ticket detail's comments.
  const key = `jev.open.${uc.useCase}`;
  const [open, setOpen] = useState(() => {
    try { return localStorage.getItem(key) !== "false"; } catch (e) { return true; }
  });
  const flip = () => {
    setOpen(!open);
    try { localStorage.setItem(key, String(!open)); } catch (e) { /* per-viewer nicety only */ }
  };
  return (
    <section className="jevUseCase" id={`jev-${uc.useCase}`}>
      <h2 className="tdFold" onClick={flip} title={open ? "Hide this use case" : "Show this use case"}>
        <span className="tdFoldArrow">{open ? "▾" : "▸"}</span>
        {TITLES[uc.useCase] || uc.useCase}
        <span className="jevUcMeta">
          {uc.calls} call{uc.calls === 1 ? "" : "s"}
          {uc.noAnswer ? ` · ${uc.noAnswer} no answer` : ""}
          {` · agreement ${fmtPct(uc.agreement)}`}
          {uc.runsSaved != null && ` · ${uc.runsSaved} run${uc.runsSaved === 1 ? "" : "s"} saved`}
        </span>
      </h2>
      {open && (uc.calls ? (
        <>
          <div className="jevLabel">Calls per day</div>
          <PerDayBars perDay={uc.perDay} since={since} until={until} />
          <div className="jevLabel">Picks</div>
          {Object.keys(uc.picks || {}).length
            ? Object.entries(uc.picks).map(([q, counts]) => <PickBars key={q} question={q} counts={counts} />)
            : <div className="muted">no picks</div>}
          <div className="jevLabel">Confidence</div>
          <ConfidenceHistogram confidence={uc.confidence} floor={uc.floor} />
          <div className="jevLabel">Actions</div>
          {actions.length
            ? <div className="jevActions">{actions.map(([a, n]) => <span key={a} className="chip">{a} <b>{n}</b></span>)}</div>
            : <div className="muted">none recorded</div>}
          {uc.runsSaved != null && (
            <div className="jevSaved"><b>{uc.runsSaved}</b> run{uc.runsSaved === 1 ? "" : "s"} saved: {uc.useCase === "ci-triage"
              ? "a re-run that passed instead of a Smith"
              : "an incident retried without a Smith that did not trip again"}</div>
          )}
          <div className="jevLabel">Calls</div>
          {calls && calls.loading && <div className="muted">asking the engine for the calls…</div>}
          {calls && calls.error && <div className="err">{calls.error}</div>}
          {list.length > 0 && <JevCallsTable calls={list} onOpenCall={onOpenCall} onOpenTicket={onOpenTicket} />}
          {calls && calls.total > list.length && (
            <button className="tdMore" onClick={() => onMore && onMore(uc.useCase)}>
              {`show more (${list.length} of ${calls.total})`}
            </button>
          )}
        </>
      ) : <div className="muted">no calls in the window</div>)}
    </section>
  );
};

/** The page, drawn from what the loader holds: `result` is { summary } or { error } or { loading }. */
export const JevDecisionsView = ({ result, win, onWindow, onRefresh, refreshing, calls = {}, onMore, drawer, onOpenCall, onCloseCall, onOpenTicket }) => {
  const r = result || { loading: true };
  const s = r.summary;
  return (
    <div className="fleet-root jev">
      <div className="head"><h1>Jev decisions</h1>
        {s && <span className="meta">{fmtAt(s.since)} – {fmtAt(s.until)}</span>}</div>
      <JevStrip totals={s && s.totals} win={win} onWindow={onWindow} onRefresh={onRefresh} refreshing={refreshing} />
      {r.loading && <div className="muted">asking the engine for the summary…</div>}
      {r.error && <div className="err">{String(r.error)}</div>}
      {s && s.useCases.map((uc) => (
        <JevUseCase key={uc.useCase} uc={uc} since={s.since} until={s.until} calls={calls[uc.useCase]}
                    onMore={onMore} onOpenCall={onOpenCall} onOpenTicket={onOpenTicket} />
      ))}
      <JevCallDrawer state={drawer} onClose={onCloseCall} onOpenTicket={onOpenTicket} />
    </div>
  );
};

const ipcError = (e) => ({ error: `IPC failed: ${e && e.message ? e.message : e}` });

/** Explorer > Analytics > Jev decisions: loads the summary and each use case's latest calls for the window. */
export const JevDecisions = ({ onOpenTicket }) => {
  const [win, setWin] = useState(DEFAULT_WINDOW);
  const [result, setResult] = useState({ loading: true });
  const [calls, setCalls] = useState({});
  const [refreshing, setRefreshing] = useState(false);
  const [drawer, openCall, closeCall] = useDrawer();
  const since = useRef(null);
  const loadCalls = useCallback(async (useCase, limit) => {
    let r;
    try { r = await window.fleet.jevCalls({ useCase, since: since.current, limit }); }
    catch (e) { r = ipcError(e); }
    setCalls((prev) => ({ ...prev, [useCase]: r && r.calls ? { calls: r.calls, total: r.total } : { error: (r && r.error) || "no response from the main process" } }));
  }, []);
  const load = useCallback(async () => {
    if (!(typeof window !== "undefined" && window.fleet && window.fleet.jevSummary)) {
      setResult({ error: "this build has no Jev channel" });
      return;
    }
    since.current = sinceOf(win, Date.now());
    setRefreshing(true);
    let r;
    try { r = await window.fleet.jevSummary(since.current); }
    catch (e) { r = ipcError(e); }
    setRefreshing(false);
    setResult(r && r.summary ? { summary: r.summary } : { error: (r && r.error) || "no response from the main process" });
    if (r && r.summary) {
      setCalls(Object.fromEntries(r.summary.useCases.filter((u) => u.calls).map((u) => [u.useCase, { loading: true }])));
      r.summary.useCases.filter((u) => u.calls).forEach((u) => loadCalls(u.useCase, CALLS_PAGE));
    }
  }, [win, loadCalls]);
  useEffect(() => { setResult({ loading: true }); load(); }, [load]);
  const onMore = (useCase) => loadCalls(useCase, Math.min(500, (((calls[useCase] || {}).calls || []).length) + 50));
  return <JevDecisionsView result={result} win={win} onWindow={setWin} onRefresh={load} refreshing={refreshing}
                           calls={calls} onMore={onMore} drawer={drawer} onOpenCall={openCall} onCloseCall={closeCall}
                           onOpenTicket={onOpenTicket} />;
};
