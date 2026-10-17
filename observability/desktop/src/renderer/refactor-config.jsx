// refactor-config.jsx — Config > Repos, Edit… > Refactoring (#1399, #1943): a
// repo's refactoring config (engine/README.md, "Refactoring jobs"): enabled,
// schedule, values, max tickets, max size, approval and the refactor-scan ticket's
// tier (#1491, #1542: none files no scan; the tier map's rows from window.fleet.tiers, GET /tiers). Reads
// GET /repos/{owner}/{name}/refactor and saves PUT on the same path through
// main (window.fleet.refactorConfig, saveRefactorConfig), as main's login; the
// engine's 400 (naming the field) is shown inline, verbatim, and the modal stays
// open; a save closes it, as the modal's other pages do (repos.jsx `savePage`).
//
// Live: a `repo` frame `changed` with field `refactor` for the repo re-reads it.
//
// The job's run history (#1653) is on Analytics > Refactoring (refactor-runs.jsx, #1781).
//
// The pure parts (`schedulePreset`, `presetSeconds`, `configForm`,
// `refactorPatch`, `saveRefactorPage`, `RefactorView`) take their inputs as arguments, so a test
// drives them without an engine.
import { useCallback, useEffect, useRef, useState } from "react";
import { useRefetchOn } from "./engine-events.js";

const DAY = 86400;
/** The schedule drop-down: named periods over the engine's `schedule` seconds, then custom. */
export const SCHEDULE_PRESETS = [
  { id: "daily", label: "daily", seconds: DAY },
  { id: "weekly", label: "weekly", seconds: 7 * DAY },
  { id: "monthly", label: "every 30 days", seconds: 30 * DAY },
  { id: "custom", label: "custom (seconds)", seconds: null },
];
export const MIN_SCHEDULE = 60;
export const APPROVALS = ["human", "none"];

/** The preset id for `seconds`: one whose period it is, else `custom`. */
export const schedulePreset = (seconds) => {
  const p = SCHEDULE_PRESETS.find((x) => x.seconds !== null && x.seconds === Number(seconds));
  return p ? p.id : "custom";
};

/** A preset's seconds, or null for `custom` and an unknown id. */
export const presetSeconds = (id) => {
  const p = SCHEDULE_PRESETS.find((x) => x.id === id);
  return p ? p.seconds : null;
};

/** The form's values from the engine's config. `reset` marks Reset to default until a save. */
export const configForm = (c) => ({
  enabled: !!(c && c.enabled),
  preset: schedulePreset(c && c.schedule),
  seconds: c && c.schedule != null ? String(c.schedule) : "",
  values: (c && c.values) || "",
  reset: false,
  maxTickets: c && c.maxTickets != null ? String(c.maxTickets) : "",
  maxLines: c && c.maxLines != null ? String(c.maxLines) : "",
  approval: (c && c.approval) || "human",
  tier: c && c.tier != null ? String(c.tier) : "",
});

const whole = (v, lo, hi, what) => {
  const t = String(v).trim();
  if (!/^\d+$/.test(t) || Number(t) < lo || (hi != null && Number(t) > hi)) return { error: `${what}: a whole number${hi != null ? ` from ${lo} to ${hi}` : `, ${lo} or more`}` };
  return { n: Number(t) };
};

/**
 * PUT's body: only the keys whose value differs from `config`, with Reset as
 * `values: null`; or `{ error }` for what the engine could not be sent. An
 * empty `patch` means nothing changed.
 */
export const refactorPatch = (form, config) => {
  const f = form || {};
  const c = config || {};
  const patch = {};
  if (!!f.enabled !== !!c.enabled) patch.enabled = !!f.enabled;
  const s = f.preset === "custom" ? whole(f.seconds, MIN_SCHEDULE, null, "schedule") : { n: presetSeconds(f.preset) };
  if (s.error) return { error: s.error };
  if (s.n == null) return { error: `schedule: no preset ${JSON.stringify(f.preset)}` };
  if (s.n !== c.schedule) patch.schedule = s.n;
  if (f.reset) {
    if (c.values !== c.valuesDefault) patch.values = null;
  } else if (f.values !== c.values) {
    if (!String(f.values || "").trim()) return { error: "values: empty; use Reset to default for the shipped values" };
    patch.values = f.values;
  }
  const t = whole(f.maxTickets, 1, 20, "max tickets");
  if (t.error) return { error: t.error };
  if (t.n !== c.maxTickets) patch.maxTickets = t.n;
  const l = whole(f.maxLines, 10, 5000, "max size (lines)");
  if (l.error) return { error: l.error };
  if (l.n !== c.maxLines) patch.maxLines = l.n;
  if (!APPROVALS.includes(f.approval)) return { error: `approval: one of ${APPROVALS.join(", ")}` };
  if (f.approval !== c.approval) patch.approval = f.approval;
  // "" is the default choice (the job run's usual tier), sent as null.
  const tier = f.tier == null || f.tier === "" ? null : Number(f.tier);
  if (tier !== null && !(Number.isInteger(tier) && tier >= 1 && tier <= 10)) return { error: "tier: 1 to 10, or default" };
  if (tier !== (c.tier == null ? null : c.tier)) patch.tier = tier;
  return { patch };
};

const ipc = async (call) => { try { return await call(); } catch (e) { return { error: `IPC failed: ${e && e.message ? e.message : e}` }; } };
const why = (r) => (r && (r.body || r.error)) || "no response from the main process";

/** Save: `{ config }` (the engine's answer) or `{ error }`; `{ unchanged }` when the form changed nothing. */
export const saveRefactor = async (fleet, repo, form, config) => {
  const p = refactorPatch(form, config);
  if (p.error) return { error: p.error };
  if (!Object.keys(p.patch).length) return { unchanged: true };
  const r = await ipc(() => fleet.saveRefactorConfig(repo, p.patch));
  return r && r.ok && r.config ? { config: r.config } : { error: why(r) };
};

/** A tier picker row's label, e.g. "5 · opus medium"; a row the map lacks is its number alone. */
export const tierOption = (n, tiers) => {
  const row = Array.isArray(tiers) ? tiers.find((t) => t.tier === n) : null;
  return row ? `${n} · ${row.model} ${row.effort}` : String(n);
};

/**
 * The page's Save: nothing changed sends nothing and closes; a refusal (the patch's or the engine's, verbatim)
 * goes to `setError` and the modal stays open; a save closes it. Answers what saveRefactor answered.
 */
export const saveRefactorPage = async (fleet, repo, form, config, setError, onClose) => {
  setError(null);
  const r = await saveRefactor(fleet, repo, form, config);
  if (r.error) setError(r.error);
  else onClose();
  return r;
};

/**
 * The page's body, drawn from props: `result` is { config } | { error } |
 * { loading }; `form` the edited values; `tiers` the tier map's rows (or
 * anything else while unknown); `onForm(patch)`, `onReset()`.
 */
export const RefactorView = ({ repo, result, form, tiers, onForm, onReset, busy }) => {
  const r = result || { loading: true };
  const f = form || configForm(r.config);
  const noTier = !!(f.enabled && f.tier === "");
  const num = (k, label, props = {}) => (
    <label className="rfField"><span className="muted">{label}</span>
      <input className="acInput" aria-label={label} inputMode="numeric" value={f[k]} disabled={busy}
             onChange={(e) => onForm({ [k]: e.target.value })} {...props} /></label>
  );
  return (
    <>
      <div className="erTitle">Refactoring</div>
      <p className="ecProse">A system job that reads {repo}&apos;s code against its values (the refactoring guidance in
        the box below) and files small refactoring tickets, each within max size lines and at most max tickets per run.
        The engine makes the job when you enable it here and removes it when you disable it; on Settings &gt; Jobs it
        carries a System badge and cannot be deleted there.</p>
      <p className="ecProse">Each run first files one refactor-scan ticket at the scan tier: a Smith reads the code and
        answers the findings the job then files. With no scan tier there is no scan, so an enabled job fails every run.
        Approval human leaves the filed tickets for a person to authorize; none authorizes them as they are filed.</p>
      <p className="ecProse">Reset to default puts the shipped values back on save. The engine refuses a bad field with
        a 400 naming it, and a Smith sandbox with a 403: either is shown here and nothing is saved. Run history is on
        Analytics &gt; Refactoring.</p>
      {r.loading && <div className="fmmEmpty">asking the engine…</div>}
      {r.error && <div className="err">{`Refactoring unavailable: ${r.error}`}</div>}
      {r.config && (
        <div className="rfForm">
          <label className="rfField"><span><input type="checkbox" aria-label="enabled" checked={f.enabled} disabled={busy}
            onChange={(e) => onForm({ enabled: e.target.checked })} /> enabled</span></label>
          <div className="rfRow">
            <label className="rfField"><span className="muted">schedule</span>
              <select className="acInput" aria-label="schedule" value={f.preset} disabled={busy}
                      onChange={(e) => onForm(e.target.value === "custom" ? { preset: "custom" } : { preset: e.target.value, seconds: String(presetSeconds(e.target.value)) })}>
                {SCHEDULE_PRESETS.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
              </select></label>
            {f.preset === "custom" && num("seconds", `seconds (at least ${MIN_SCHEDULE})`)}
            {num("maxTickets", "max tickets (1–20)")}
            {num("maxLines", "max size, lines (10–5000)")}
            <label className="rfField"><span className="muted">approval</span>
              <select className="acInput" aria-label="approval" value={f.approval} disabled={busy}
                      onChange={(e) => onForm({ approval: e.target.value })}>
                {APPROVALS.map((a) => <option key={a} value={a}>{a}</option>)}
              </select></label>
            <label className="rfField"><span className="muted">scan tier</span>
              <select className={noTier ? "acInput err" : "acInput"} aria-label="tier" value={f.tier} disabled={busy}
                      onChange={(e) => onForm({ tier: e.target.value })}>
                <option value="">none: no scan</option>
                {Array.from({ length: 10 }, (_, i) => i + 1).map((n) => <option key={n} value={String(n)}>{tierOption(n, tiers)}</option>)}
              </select></label>
          </div>
          {noTier && <div className="err">Enabled with no scan tier: every run fails and no scan is filed. Pick a scan tier.</div>}
          <label className="rfField"><span className="muted">values{f.reset ? " (the shipped default, on save)" : ""}</span>
            <textarea className="acInput rfValues" aria-label="values" rows={12} value={f.values} disabled={busy}
                      onChange={(e) => onForm({ values: e.target.value, reset: false })} /></label>
          <div className="acActions">
            <button className="acBtn" type="button" disabled={busy} onClick={onReset}>Reset to default</button>
          </div>
        </div>
      )}
    </>
  );
};

/**
 * Edit… > Refactoring for `repo` (an owner/name): reads the config when shown, Save sends the changed keys.
 * `Foot` is the modal's page footer (Cancel, Save and the refusal), so the page saves like its siblings.
 */
export const RefactorPage = ({ repo, onClose, Foot, fleet = typeof window !== "undefined" ? window.fleet : null }) => {
  const [result, setResult] = useState({ loading: true });
  const [form, setForm] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [tiers, setTiers] = useState(null);
  useEffect(() => {
    if (fleet && fleet.tiers) ipc(() => fleet.tiers()).then((t) => setTiers(Array.isArray(t) ? t : null));
  }, []);
  const seq = useRef(0);
  const load = useCallback(async () => {
    const mine = ++seq.current;
    if (!(fleet && fleet.refactorConfig)) { setResult({ error: "this build has no refactoring channel" }); return; }
    const r = await ipc(() => fleet.refactorConfig(repo));
    if (mine !== seq.current) return;
    if (r && r.config && !r.error) { setResult({ config: r.config }); setForm(configForm(r.config)); }
    else setResult({ error: why(r) });
  }, [repo]);
  useEffect(() => { setResult({ loading: true }); setForm(null); setError(null); load(); return () => { seq.current++; }; }, [load]);
  useRefetchOn(["repo"], load, { where: (d) => d.repo === repo && Array.isArray(d.fields) && d.fields.includes("refactor") });
  const onSave = async () => {
    if (!result.config) return;
    setBusy(true);
    await saveRefactorPage(fleet, repo, form, result.config, setError, onClose);
    setBusy(false);
  };
  const onReset = () => setForm((f) => ({ ...f, values: (result.config && result.config.valuesDefault) || "", reset: true }));
  return (
    <form className="ecForm erPage" role="tabpanel" onSubmit={(e) => { if (e) e.preventDefault(); onSave(); }}>
      <RefactorView repo={repo} result={result} form={form} tiers={tiers} busy={busy}
                    onForm={(p) => setForm((f) => ({ ...f, ...p }))} onReset={onReset} />
      <Foot error={error} busy={busy} onCancel={onClose} disabled={!result.config} />
    </form>
  );
};
