// tiers.jsx — Config > frakworks > Tiers (#1099): the engine's tier map, what
// each of the ten tiers runs (a model and an effort), the tier every
// security review runs at, and the tier every post-deployment check runs at.
// The engine keeps all three in its database (`GET /tiers`, `GET /security/tier`,
// `GET /check/tier`, engine/README.md "Tier resolution"); until #1099 the first
// two were bots/tiers.toml. Main reads them with `engine:tiers`,
// `engine:security-tier` and `engine:check-tier`; a change is one `onAction`,
// `{ op: "tier", tier, model, effort }`, `{ op: "security-tier", tier }` or
// `{ op: "check-tier", tier }`, which main sends over `engine:account-action`.
// Always ten rows: a tier is edited, never added or removed.
// Shape shared with refiner-tier.jsx: acSetting > acSettingHead, fmmHint, err/notice lines, acInput selects that save on change.

import { useState } from "react";
import { tierLabel } from "./accounts.jsx";

/** The models a row takes and the efforts each takes: the engine's TierTable.EFFORTS (it refuses anything else). */
export const TIER_EFFORTS = {
  haiku: ["low"],
  sonnet: ["low", "medium", "high", "xhigh", "max"],
  opus: ["low", "medium", "high", "xhigh", "max"],
  fable: ["low", "medium", "high", "xhigh", "max"],
};

/** The effort a model switch keeps: the row's own when the new model takes it, else the model's first. */
export const effortFor = (model, effort) => {
  const efforts = TIER_EFFORTS[model] || [];
  return efforts.includes(effort) ? effort : efforts[0];
};

/**
 * The section: head, hint, error or notice lines, one line per tier with a
 * model and an effort select, then the security tier's select. `tiers` is
 * what `engine:tiers` resolved to (rows, `{ error }`, or null while loading);
 * `security` is `engine:security-tier`'s `{ securityTier, error }`, or null.
 * Without `run` every select is disabled.
 */
export const Tiers = ({ tiers, security, check, notice, busy, run }) => {
  const rows = Array.isArray(tiers) ? tiers : [];
  const error = tiers && !Array.isArray(tiers) ? tiers.error || "no tier table" : null;
  const sec = security && security.securityTier;
  const chk = check && check.checkTier;
  const disabled = busy || !run;
  const setRow = (r, model, effort) => run({ op: "tier", tier: r.tier, model, effort }, `tier ${r.tier} set to ${model} ${effort}`);
  return (
    <section className="acSetting">
      <div className="acSettingHead">
        <span className="acName">Tiers</span>
      </div>
      <p className="fmmHint">The model and effort each tier runs at, kept in the engine; the next run at a tier takes a change.</p>
      {error && <div className="err">{`tiers unavailable: ${error}`}</div>}
      {notice && <div className={notice.ok ? "acNotice" : "err"}>{notice.text}</div>}
      {!tiers && <div className="fmmEmpty">asking the engine…</div>}
      {rows.map((r) => (
        <div key={r.tier} className="tierRow">
          <span className="tierN">{`Tier ${r.tier}`}</span>
          <select className="acInput" aria-label={`tier ${r.tier} model`} value={r.model} disabled={disabled}
            onChange={(e) => setRow(r, e.target.value, effortFor(e.target.value, r.effort))}>
            {!TIER_EFFORTS[r.model] && <option value={r.model}>{r.model}</option>}
            {Object.keys(TIER_EFFORTS).map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
          <select className="acInput" aria-label={`tier ${r.tier} effort`} value={r.effort} disabled={disabled}
            onChange={(e) => setRow(r, r.model, e.target.value)}>
            {!(TIER_EFFORTS[r.model] || []).includes(r.effort) && <option value={r.effort}>{r.effort}</option>}
            {(TIER_EFFORTS[r.model] || []).map((e) => <option key={e} value={e}>{e}</option>)}
          </select>
        </div>
      ))}
      {security && security.error && <div className="err">{`security tier unavailable: ${security.error}`}</div>}
      {sec && <SettingRow label="Security reviews run at" aria="security tier" current={sec} rows={rows} disabled={disabled}
        onPick={(tier) => run({ op: "security-tier", tier }, `security reviews set to tier ${tier}`)} />}
      {check && check.error && <div className="err">{`check tier unavailable: ${check.error}`}</div>}
      {chk && <SettingRow label="Post-deployment checks run at" aria="check tier" current={chk} rows={rows} disabled={disabled}
        onPick={(tier) => run({ op: "check-tier", tier }, `post-deployment checks set to tier ${tier}`)} />}
    </section>
  );
};

/** One tier-setting row (the security tier, the check tier): a select over the ten tiers, labelled by each tier's model and effort. */
const SettingRow = ({ label, aria, current, rows, disabled, onPick }) => (
  <div className="tierRow">
    <span className="tierN">{label}</span>
    <select className="acInput" aria-label={aria} value={current.tier} disabled={disabled}
      onChange={(e) => {
        const tier = Number(e.target.value);
        return tier !== current.tier ? onPick(tier) : undefined;
      }}>
      {(rows.length ? rows : Array.from({ length: 10 }, (_, i) => ({ tier: i + 1 })))
        .map((r) => (r.tier === current.tier ? current : r))
        .map((r) => <option key={r.tier} value={r.tier}>{tierLabel(r.tier, r.model, r.effort)}</option>)}
    </select>
  </div>
);

/**
 * The frakworks page's Tiers part, with its notice. The page refetches both
 * reads on a `setting` frame (index.jsx), so it has no ⟳ of its own; after a
 * successful save it refreshes at once. A refusal (the engine's 400, say an
 * effort the model does not take) is the notice, and the rows stay as read.
 */
export const TiersPanel = ({ tiers, security, check, onRefresh, onAction }) => {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null); // { ok, text }
  const run = async (action, done) => {
    setBusy(true); setNotice(null);
    let r;
    try { r = await onAction(action); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    if (!r || r.error) { setNotice({ ok: false, text: (r && r.error) || "no answer from the main process" }); return false; }
    setNotice({ ok: true, text: done });
    if (onRefresh) onRefresh();
    return true;
  };
  return <Tiers tiers={tiers} security={security} check={check} notice={notice} busy={busy} run={onAction ? run : null} />;
};
