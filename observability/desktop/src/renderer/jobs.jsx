// jobs.jsx — Config > Jobs (#1228): the selected repo's custom jobs
// (engine/README.md, "Custom jobs and the SDK"), a form to register one, and
// Run now and Delete on each row. A system job (owner `system`, a repo's
// refactoring job, #1399) shows a System badge and Run now, never Delete. Reads GET /jobs?repo= and writes POST /jobs,
// POST /jobs/{id}/execute and DELETE /jobs/{id} through main
// (window.fleet.jobs, jobRegister, jobExecute, jobDelete), each write as main's
// login; the engine's 400, 403 and 409 are shown inline, verbatim.
//
// Live: the stream's `job` frames (`started`, `ended`) update a row's last
// run, outcome and running state. Registration and deletion publish no frame,
// so the list is read on entering the view and after the view's own writes;
// there is no timer here.
//
// The pure parts (`signalText`, `jobBody`, `applyJobFrame`, `JobsView`) and
// the writes as functions of a `fleet` (`registerJob`, `runJobNow`,
// `deleteJobRow`) take their inputs as arguments, so a test drives them
// without an engine.
import { ModalOverlay } from "./modal-overlay.jsx";
import { useCallback, useEffect, useRef, useState } from "react";
import { useEngineEvents } from "./engine-events.js";
import { ProcessInstance } from "./process.jsx";

export const JOB_KINDS = ["prompt", "bean", "flow"];
/** The config keys each kind takes, in the form's order. */
export const CONFIG_KEYS = { prompt: ["prompt"], bean: ["jar", "bean"], flow: ["jar", "process"] };
export const EMPTY_FORM = { name: "", kind: "prompt", signal: "every", every: "", prompt: "", jar: "", bean: "", process: "" };

// The engine's row keeps config and signal as JSON; read either an object or its text.
const asObject = (v) => {
  if (v && typeof v === "object") return v;
  if (typeof v === "string") { try { return JSON.parse(v); } catch { return null; } }
  return null;
};

/** `Oct 8, 00:48` in local time (or `timeZone`), `—` for null or an unparsable value. */
export const shortTime = (iso, timeZone) => {
  const d = iso ? new Date(iso) : null;
  if (!d || Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString("en-US", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23", ...(timeZone ? { timeZone } : {}) });
};

/** Seconds as the largest whole unit: `1 d`, `1 h`, `30 min`, else `90 s`. */
export const durationText = (n) => {
  const v = Number(n);
  if (!Number.isFinite(v) || v <= 0) return `${n} s`;
  for (const [u, k] of [["d", 86400], ["h", 3600], ["min", 60]]) if (v % k === 0) return `${v / k} ${u}`;
  return `${v} s`;
};

/** `every 1 h` for `{"kind":"every","every":3600}`, `at <short time>` for `{"kind":"at"}`, `on demand` for `{"kind":"execute"}`. */
export const signalText = (signal, timeZone) => {
  const s = asObject(signal) || {};
  if (s.kind === "every") return `every ${durationText(s.every)}`;
  if (s.kind === "at") return `at ${shortTime(s.at, timeZone)}`;
  if (s.kind === "execute") return "on demand";
  return String(s.kind || "—");
};

const When = ({ iso }) => (iso ? <span title={iso}>{shortTime(iso)}</span> : <span className="muted">—</span>);

/**
 * The form as POST /jobs' body, exactly `{ name, kind, repo, config, signal }`
 * with only the kind's config keys, or `{ error }` for what the engine could
 * not be sent (no repo, a period that is not a whole number of seconds).
 */
export const jobBody = (form, repo) => {
  if (!repo) return { error: "pick a repo in the switcher to register a job in" };
  const f = { ...EMPTY_FORM, ...(form || {}) };
  const keys = CONFIG_KEYS[f.kind] || [];
  const config = Object.fromEntries(keys.map((k) => [k, f[k]]));
  let signal;
  if (f.signal === "execute") signal = { kind: "execute" };
  else {
    const n = String(f.every).trim();
    if (!/^\d+$/.test(n) || Number(n) < 1) return { error: "every: a whole number of seconds, 1 or more" };
    signal = { kind: "every", every: Number(n) };
  }
  return { body: { name: f.name, kind: f.kind, repo, config, signal } };
};

/**
 * `runs` (jobId -> { running, outcome, instance }) after one `job` frame. A
 * frame for a job not in `ids` changes nothing (the repo is the hook's filter).
 */
export const applyJobFrame = (runs, frame, ids) => {
  const d = (frame && frame.data) || {};
  if (!frame || frame.type !== "job" || !ids.has(d.jobId)) return runs;
  const prev = runs[d.jobId] || {};
  if (d.state === "started") return { ...runs, [d.jobId]: { running: true, outcome: null, instance: d.instance || prev.instance || null } };
  if (d.state === "ended") return { ...runs, [d.jobId]: { running: false, outcome: d.outcome || null, instance: d.instance || prev.instance || null } };
  return runs;
};

/** The outcome cell: the last `ended` seen this session, else "running", else "—". */
export const outcomeText = (run) => (run && run.running ? "running" : run && run.outcome ? run.outcome : "—");

/** The view's `job` frames for `repo`, applied to the listed jobs' runs. */
export function useJobRuns(repo, jobs) {
  const [runs, setRuns] = useState({});
  const ids = useRef(new Set()); ids.current = new Set((jobs || []).map((j) => j.id));
  useEngineEvents(["job"], (frame) => setRuns((r) => applyJobFrame(r, frame, ids.current)), { repo: repo || undefined });
  return [runs, setRuns];
}

// The engine's answer as the user should read it: its body when it sent one, else the app's line.
const why = (r) => (r && (r.body || r.error)) || "no response from the main process";
const ipc = async (call) => { try { return await call(); } catch (e) { return { error: `IPC failed: ${e && e.message ? e.message : e}` }; } };

/**
 * Register: `{ form, error, open, jobs? }`; a 201 clears the form, closes the
 * modal (`open` false) and re-reads the list, a failure keeps the form and the modal open.
 */
export const registerJob = async (fleet, form, repo) => {
  const b = jobBody(form, repo);
  if (b.error) return { form, error: b.error, open: true };
  const r = await ipc(() => fleet.jobRegister(b.body));
  if (!r || !r.ok) return { form, error: why(r), open: true };
  const list = await ipc(() => fleet.jobs(repo));
  return { form: EMPTY_FORM, error: null, open: false, jobs: list && Array.isArray(list.jobs) ? list.jobs : null };
};

/** Run now: `{ instance }` or `{ error }`. */
export const runJobNow = async (fleet, job) => {
  const r = await ipc(() => fleet.jobExecute(job.id));
  return r && r.ok ? { instance: r.instance || null } : { error: why(r) };
};

/** Delete behind `confirm` naming the job: `{ cancelled }`, `{ removed }` or `{ error }`. */
export const deleteJobRow = async (fleet, job, confirm) => {
  if (!confirm(`Delete the job "${job.name}"? It stops running and is removed from the engine.`)) return { cancelled: true };
  const r = await ipc(() => fleet.jobDelete(job.id));
  return r && r.ok ? { removed: true } : { error: why(r) };
};

const Lines = ({ text }) => (
  <div className="err jbErr">{String(text).split("\n").map((l, i) => <div key={i}>{l}</div>)}</div>
);

export const InstanceLink = ({ id, onOpen }) => (id
  ? <button className="pvLink" data-instance={id} title={`open process instance ${id}`} onClick={() => onOpen && onOpen(id)}>{String(id).slice(0, 8)}</button>
  : <span className="muted">—</span>);

/** The register form: `form` is its values, `onChange(patch)`, `onSubmit()`, `onCancel()`. */
export const RegisterForm = ({ form, onChange, onSubmit, onCancel, busy, error }) => {
  const f = { ...EMPTY_FORM, ...(form || {}) };
  const field = (k, label, props = {}) => (
    <label className="jbField"><span className="muted">{label}</span>
      <input className="acInput" aria-label={label} value={f[k]} disabled={busy} onChange={(e) => onChange({ [k]: e.target.value })} {...props} /></label>
  );
  return (
    <form className="jbForm" onSubmit={(e) => { e.preventDefault(); onSubmit(); }}>
      <h2>Register a job</h2>
      <div className="jbRowFields">
        {field("name", "name")}
        <label className="jbField"><span className="muted">kind</span>
          <select className="acInput" aria-label="kind" value={f.kind} disabled={busy} onChange={(e) => onChange({ kind: e.target.value })}>
            {JOB_KINDS.map((k) => <option key={k} value={k}>{k}</option>)}
          </select></label>
        <label className="jbField"><span className="muted">signal</span>
          <select className="acInput" aria-label="signal" value={f.signal} disabled={busy} onChange={(e) => onChange({ signal: e.target.value })}>
            <option value="every">every N seconds</option>
            <option value="execute">on demand</option>
          </select></label>
        {f.signal === "every" && field("every", "seconds", { inputMode: "numeric" })}
      </div>
      {f.kind === "prompt" && (
        <label className="jbField"><span className="muted">prompt</span>
          <textarea className="acInput jbPrompt" aria-label="prompt" rows={6} value={f.prompt} disabled={busy}
                    onChange={(e) => onChange({ prompt: e.target.value })} /></label>
      )}
      {f.kind !== "prompt" && (
        <div className="jbRowFields">
          {field("jar", "jar (file name in /var/lib/frakworks/jobs)")}
          {f.kind === "bean" ? field("bean", "bean") : field("process", "process")}
        </div>
      )}
      <div className="acActions">
        {onCancel && <button className="acBtn" type="button" onClick={onCancel}>Cancel</button>}
        <button className="acBtn primary" type="submit" disabled={busy}>Register</button></div>
      {error && <Lines text={error} />}
    </form>
  );
};

/**
 * The page, drawn from props: `result` is { jobs } | { error } | { loading };
 * `runs` the frames' state per job id; `rowErrors` and `lastInstance` per job
 * id (Run now's answer); `busy` the id of a row with a write out.
 */
/**
 * Where the open ⋯ menu goes (#1758). The menu is `position: fixed` so the
 * table's scroll wrapper cannot clip it. No ancestor sets transform, filter
 * or contain: layout, so its containing block is the viewport (container-type
 * does not make one, #1777) and the place is relative to the window (`viewW`
 * wide): below the summary, right-aligned to it, or above it when it
 * would pass the window's bottom (`viewH`, the menu's height `menuH`).
 */
export const jobMenuPos = (summary, viewW, menuH = 0, viewH = Infinity) => {
  const below = summary.bottom + 2;
  const top = below + menuH > viewH ? summary.top - 2 - menuH : below;
  return { top: Math.round(top), right: Math.round(viewW - summary.right) };
};

const placeJobMenu = (e) => {
  const d = e.currentTarget;
  if (!d.open) return;
  const menu = d.querySelector(".jbMenu");
  const p = jobMenuPos(d.querySelector("summary").getBoundingClientRect(), document.documentElement.clientWidth, menu.offsetHeight, window.innerHeight);
  menu.style.top = `${p.top}px`;
  menu.style.right = `${p.right}px`;
};

export const JobsView = ({ repo, result, runs = {}, rowErrors = {}, busy = null, form, formError, formBusy,
  registerOpen = false, onOpenRegister, onCloseRegister, onForm, onSubmit, onRunNow, onDelete, onOpenInstance, onRefresh, open = null }) => {
  const r = result || { loading: true };
  return (
    <div className="fleet-root jobs">
      <div className="head"><h1>Jobs</h1>
        <span className="meta">{repo || "no repo selected"}
          {onRefresh && <button className="refreshBtn" title="Refresh jobs" onClick={onRefresh}>⟳</button>}</span></div>
      {!repo && <div className="muted">Jobs are per repo: pick one in the repo switcher.</div>}
      {repo && r.loading && <div className="muted">asking the engine for the jobs…</div>}
      {repo && r.error && <div className="err">{String(r.error)}</div>}
      {repo && Array.isArray(r.jobs) && (r.jobs.length ? (
        <div className="jbScroll"><table className="jbTable">
          <thead><tr><th>name</th><th className="jbWide">kind</th><th>signal</th><th><span className="jbWide">next run</span><span className="jbMid">when</span></th><th className="jbWide">last run</th><th>outcome</th><th className="jbInst">instance</th><th /></tr></thead>
          <tbody>
            {r.jobs.map((j) => {
              const run = runs[j.id] || {};
              const inst = run.instance || j.lastInstance || null;
              const runNow = <button className="acBtn" disabled={busy === j.id} onClick={() => onRunNow && onRunNow(j)}>Run now</button>;
              const del = j.owner !== "system" && <button className="acBtn danger" disabled={busy === j.id} onClick={() => onDelete && onDelete(j)}>Delete</button>;
              return [
                <tr key={j.id} data-job={j.id}>
                  <td className="jbName"><span className="jbNameText" title={j.name}>{j.name}</span>{j.owner === "system" && <span className="jbSystem" title="System-owned: change it in Config > frakworks > Refactoring">System</span>}<span className="jbKindTag jbMid">{j.kind}</span></td>
                  <td className="jbWide">{j.kind}</td>
                  <td>{signalText(j.signal)}</td>
                  <td><span className="jbWide"><When iso={j.nextRunAt} /></span>
                    <span className="jbMid jbWhen"><span>next <When iso={j.nextRunAt} /></span><span>last <When iso={j.lastRunAt} /></span></span></td>
                  <td className="jbWide"><When iso={j.lastRunAt} /></td>
                  <td className="jbOutcome" title={outcomeText(run)}>{outcomeText(run)}{inst && <span className="jbNarrow"> <InstanceLink id={inst} onOpen={onOpenInstance} /></span>}</td>
                  <td className="jbInst"><InstanceLink id={inst} onOpen={onOpenInstance} /></td>
                  <td className="acActions jbActions">
                    <span className="jbBtns">{runNow}{del}</span>
                    <details className="jbMore jbNarrow" onToggle={placeJobMenu}><summary className="acBtn" title="actions">⋯</summary><div className="jbMenu">{runNow}{del}</div></details>
                  </td>
                </tr>,
                rowErrors[j.id] ? <tr key={`${j.id}:err`}><td colSpan={8}><Lines text={rowErrors[j.id]} /></td></tr> : null,
              ];
            })}
          </tbody>
        </table></div>
      ) : <div className="muted">no jobs registered in {repo}</div>)}
      {repo && <div className="acActions jbRegister"><button className="acBtn primary" onClick={() => onOpenRegister && onOpenRegister()}>Register a job</button></div>}
      {repo && registerOpen && (
        <ModalOverlay label="Register a job" onEscape={onCloseRegister} onBackdrop={onCloseRegister} className="jbPanel">
          <RegisterForm form={form} onChange={onForm} onSubmit={onSubmit} onCancel={onCloseRegister} busy={formBusy} error={formError} />
        </ModalOverlay>
      )}
      {open && <div className="jbInstance"><h2>Process instance</h2><ProcessInstance id={open} now={Date.now()} /></div>}
    </div>
  );
};

/** Explorer > Config > Jobs, for `repo` (the switcher's; null in All repos). */
export const Jobs = ({ repo }) => {
  const fleet = typeof window !== "undefined" ? window.fleet : null;
  const [result, setResult] = useState({ loading: true });
  const [form, setForm] = useState(EMPTY_FORM);
  const [formError, setFormError] = useState(null);
  const [formBusy, setFormBusy] = useState(false);
  const [registerOpen, setRegisterOpen] = useState(false);
  const [rowErrors, setRowErrors] = useState({});
  const [busy, setBusy] = useState(null);
  const [open, setOpen] = useState(null);
  const [runs, setRuns] = useJobRuns(repo, result.jobs);
  const seq = useRef(0);
  const load = useCallback(async () => {
    const mine = ++seq.current;
    if (!repo) return;
    if (!(fleet && fleet.jobs)) { setResult({ error: "this build has no Jobs channel" }); return; }
    const r = await ipc(() => fleet.jobs(repo));
    if (mine !== seq.current) return;
    setResult(r && Array.isArray(r.jobs) && !r.error ? { jobs: r.jobs } : { error: why(r) });
  }, [repo]);
  useEffect(() => { setResult({ loading: true }); setRuns({}); setRowErrors({}); setOpen(null); setRegisterOpen(false); load(); return () => { seq.current++; }; }, [load]);
  const rowError = (id, text) => setRowErrors((e) => ({ ...e, [id]: text }));
  const onSubmit = async () => {
    setFormBusy(true);
    const r = await registerJob(fleet, form, repo);
    setFormBusy(false);
    setForm(r.form); setFormError(r.error); setRegisterOpen(r.open);
    if (r.jobs) setResult({ jobs: r.jobs });
  };
  const onCloseRegister = useCallback(() => setRegisterOpen(false), []);
  const onRunNow = async (j) => {
    setBusy(j.id); rowError(j.id, null);
    const r = await runJobNow(fleet, j);
    setBusy(null);
    if (r.error) rowError(j.id, r.error);
    else setRuns((x) => ({ ...x, [j.id]: { ...(x[j.id] || {}), instance: r.instance } }));
  };
  const onDelete = async (j) => {
    setBusy(j.id); rowError(j.id, null);
    const r = await deleteJobRow(fleet, j, (m) => window.confirm(m));
    setBusy(null);
    if (r.error) rowError(j.id, r.error);
    if (r.removed) await load();
  };
  return <JobsView repo={repo} result={repo ? result : {}} runs={runs} rowErrors={rowErrors} busy={busy}
                   form={form} formError={formError} formBusy={formBusy}
                   registerOpen={registerOpen} onOpenRegister={() => setRegisterOpen(true)} onCloseRegister={onCloseRegister} onForm={(p) => setForm((f) => ({ ...f, ...p }))}
                   onSubmit={onSubmit} onRunNow={onRunNow} onDelete={onDelete} onOpenInstance={setOpen} onRefresh={load} open={open} />;
};
