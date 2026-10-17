// refactor-runs.jsx — Analytics > Refactoring (#1781): the refactoring job's run
// history (#1653), moved off Config > frakworks > Refactoring. With one repo in
// the switcher, that repo's job; in All repos, one section per target repo
// that has a refactoring job (GET /jobs?repo= through window.fleet.jobs, kind
// `refactor`). Each job's runs come from GET /jobs/{id}/runs through
// window.fleet.jobRuns, newest first, each with its outcome, the tickets it
// filed as frakworks://ticket links, and `did nothing: <reason>` when it filed
// none (`runLine`). A `job` frame for a shown job re-reads its runs; no poll.
//
// The pure parts (`runLine`, `RefactorRuns`, `RefactorRunsView`) take their
// inputs as arguments, so a test drives them without an engine.
import { useCallback, useEffect, useRef, useState } from "react";
import { useRefetchOn } from "./engine-events.js";
import { useRepoName } from "./repo-name-form.jsx";

export const RUNS_LIMIT = 20;

const SKIP_WORDS = { open: "open", oversize: "oversize", overCap: "over the cap", malformed: "malformed" };
const skipText = (skipped) => Object.entries(skipped || {}).filter(([, n]) => n > 0).map(([k, n]) => `${n} ${SKIP_WORDS[k] || k}`).join(", ");
const after = (text, prefix) => (typeof text === "string" && text.startsWith(prefix) ? text.slice(prefix.length) : text) || "";

/**
 * One run of the refactoring job as the page shows it: `{ key, at, badge,
 * filed: [numbers], text }`. `badge` is running, filed, scanning, nothing or
 * failed; `text` is `did nothing: <reason>` whenever the run filed no ticket.
 */
export const runLine = (run) => {
  const r = run || {};
  const base = { key: r.instance || r.startedAt || "?", at: r.startedAt || null, endedAt: r.endedAt || null, scanIssue: r.scanIssue ?? null, filed: [] };
  if (!r.status) return { ...base, badge: "running", text: "running" };
  if (r.status === "failed") return { ...base, badge: "failed", text: `did nothing: ${after(r.outcome, "failed: ") || "failed"}` };
  if (r.status === "nothing") return { ...base, badge: "nothing", text: `did nothing: ${after(r.summary, "filed nothing: ") || "nothing filed"}` };
  if (r.scanIssue == null) return { ...base, badge: "filed", text: r.summary || "done" };
  const filed = Array.isArray(r.filed) ? r.filed.filter((n) => Number.isSafeInteger(n) && n > 0) : [];
  const skips = skipText(r.skipped);
  if (filed.length) return { ...base, badge: "filed", filed, text: skips ? `skipped ${skips}` : "" };
  if (!r.scanEndedAt) return { ...base, badge: "scanning", text: "scanning" };
  if (r.foundNothing) return { ...base, badge: "nothing", text: "did nothing: the scan found nothing to propose" };
  if (r.scanOutcome && r.scanOutcome !== "done") return { ...base, badge: "failed", text: `did nothing: the scan ended ${r.scanOutcome}` };
  return { ...base, badge: "nothing", text: `did nothing: every proposal was skipped${skips ? ` (${skips})` : ""}` };
};

const when = (t) => (t ? new Date(t).toLocaleString() : "?");

/**
 * One repo's runs: `runs` is { runs } | { error } | { loading }; `repo` names
 * the ticket links; `onOpenTicket(repo, n)` opens one in the app.
 */
export const RefactorRuns = ({ repo, runs, onOpenTicket }) => {
  const r = runs || { loading: true };
  const name = repo ? repo.split("/").pop() : "";
  const link = (n, text) => (
    <a key={n} href={`frakworks://ticket/${repo}/${n}`}
       onClick={(e) => { if (onOpenTicket) { e.preventDefault(); onOpenTicket(repo, n); } }}>{text}</a>
  );
  return (
    <div className="rfRuns">
      {r.loading && <div className="fmmEmpty">asking the engine…</div>}
      {r.error && <div className="err">{`Runs unavailable: ${r.error}`}</div>}
      {Array.isArray(r.runs) && !r.runs.length && <div className="fmmEmpty">no runs yet</div>}
      {Array.isArray(r.runs) && r.runs.map(runLine).map((l) => (
        <div className="rfRun" key={l.key}>
          <span className={`rfBadge ${l.badge}`}>{l.badge}</span>{" "}
          <span className="muted">{when(l.at)}{l.endedAt ? ` – ${when(l.endedAt)}` : ""}</span>{" "}
          {l.scanIssue != null && <>scan {link(l.scanIssue, `${name}#${l.scanIssue}`)}{" "}</>}
          {l.filed.length > 0 && <>filed {l.filed.map((n, i) => <span key={n}>{i ? ", " : ""}{link(n, `#${n}`)}</span>)}{" "}</>}
          <span>{l.text}</span>
        </div>
      ))}
    </div>
  );
};

/**
 * The page, drawn from props: `sections` is { loading } | { error } | an array
 * of `{ repo, jobId, runs }` (jobId null: refactoring off).
 */
export const RefactorRunsView = ({ sections, onOpenTicket }) => {
  const s = sections || { loading: true };
  const repoName = useRepoName(Array.isArray(s) ? s.map((x) => x.repo) : []);
  return (
    <div className="fleet-root rfPage">
      <h2>Refactoring</h2>
      <p className="fmmHint">The refactoring job's recent runs, newest first. Its config is under Config &gt; frakworks &gt; Refactoring.</p>
      {s.loading && <div className="fmmEmpty">asking the engine…</div>}
      {s.error && <div className="err">{`Runs unavailable: ${s.error}`}</div>}
      {Array.isArray(s) && !s.length && <div className="fmmEmpty">no target repos</div>}
      {Array.isArray(s) && s.map((x) => (
        <section className="acSetting rfSetting" key={x.repo}>
          <div className="acSettingHead"><span className="acName">{repoName(x.repo)}</span></div>
          {x.error ? <div className="err">{`Runs unavailable: ${x.error}`}</div>
            : x.jobId ? <RefactorRuns repo={x.repo} runs={x.runs} onOpenTicket={onOpenTicket} />
            : <div className="fmmEmpty">refactoring off</div>}
        </section>
      ))}
    </div>
  );
};

const ipc = async (call) => { try { return await call(); } catch (e) { return { error: `IPC failed: ${e && e.message ? e.message : e}` }; } };
const why = (r) => (r && (r.body || r.error)) || "no response from the main process";

/** One repo's section: its refactor job (or none) and that job's runs. */
export const loadSection = async (fleet, repo) => {
  const j = await ipc(() => fleet.jobs(repo));
  if (!j || j.error || !Array.isArray(j.jobs)) return { repo, error: why(j) };
  const job = j.jobs.find((x) => x && x.kind === "refactor");
  if (!job) return { repo, jobId: null };
  const r = await ipc(() => fleet.jobRuns(job.id, RUNS_LIMIT));
  return { repo, jobId: job.id, runs: r && Array.isArray(r.runs) && !r.error ? { runs: r.runs } : { error: why(r) } };
};

/** Analytics > Refactoring: `repo` the switcher's (null in All repos), `repos` the target repos. */
export const RefactorRunsPage = ({ repo, repos, onOpenTicket }) => {
  const fleet = typeof window !== "undefined" ? window.fleet : null;
  const [sections, setSections] = useState({ loading: true });
  const list = repo ? [repo] : Array.isArray(repos) ? repos : null;
  const key = list ? list.join(",") : "";
  const seq = useRef(0);
  const load = useCallback(async () => {
    const mine = ++seq.current;
    if (!list) return;
    if (!(fleet && fleet.jobs && fleet.jobRuns)) { setSections({ error: "this build has no job runs channel" }); return; }
    const all = await Promise.all(list.map((r) => loadSection(fleet, r)));
    if (mine !== seq.current) return;
    // In All repos a repo with no refactoring job is left out.
    setSections(repo ? all : all.filter((x) => x.jobId || x.error));
  }, [key]);
  useEffect(() => { setSections({ loading: true }); load(); return () => { seq.current++; }; }, [load]);
  const jobIds = Array.isArray(sections) ? sections.map((x) => x.jobId).filter(Boolean) : [];
  useRefetchOn(["job"], load, { where: (d) => jobIds.includes(d.jobId) });
  return <RefactorRunsView sections={sections} onOpenTicket={onOpenTicket} />;
};
