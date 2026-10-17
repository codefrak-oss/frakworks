// refiner-tier.jsx — Config > frakworks > Intake refiner tier (#594, moved out
// of AI Accounts; its own Config row until #617 put it on the frakworks page,
// below First mate model): the tier every intake's refining Smith runs at, a setting the
// engine keeps (`GET /intake/tier`, engine/README.md "Intake"). Main reads it
// with `engine:refiner-tier`; changing it is one `onAction`,
// `{ op: "refiner-tier", tier }`, which main sends over
// `engine:account-action` to the engine's `PUT /intake/tier`.
// Shape shared with first-mate-model.jsx: acSetting > acSettingHead, fmmHint, err/notice lines, an acInput select that saves on change.

import { useState } from "react";
import { tierLabel } from "./accounts.jsx";

/**
 * The intake refiner's tier, in First mate model's shape: head, one-line
 * hint, error or notice lines, then a select that saves on change and shows
 * the current tier with its model and effort. `tiers` (the engine's tier
 * map's rows) names the choices; without it the choices are 1-10 bare.
 * Without `run` the select is disabled. `notice` is the last save's
 * `{ ok, text }`; `loading` before main's first answer.
 */
export const RefinerTier = ({ refinerTier, error, loading, notice, tiers, busy, run }) => {
  const base = Array.isArray(tiers) && tiers.length ? tiers : Array.from({ length: 10 }, (_, i) => ({ tier: i + 1 }));
  const current = refinerTier ? refinerTier.tier : null;
  // The current tier is drawn with the engine's model and effort, and listed even without a row.
  const rows = base.some((r) => r.tier === current) ? base.map((r) => (r.tier === current ? refinerTier : r))
    : current != null ? [...base, refinerTier].sort((a, b) => a.tier - b.tier) : base;
  const onChange = (e) => {
    const tier = Number(e.target.value);
    if (tier !== current) return run({ op: "refiner-tier", tier }, `refiner tier set to ${tier}`);
    return undefined;
  };
  return (
    <section className="acSetting">
      <div className="acSettingHead">
        <span className="acName">Intake refiner tier</span>
      </div>
      <p className="fmmHint">The tier every intake's refining Smith runs at, kept in the engine.</p>
      {error && <div className="err">{`refiner tier unavailable: ${error}`}</div>}
      {notice && <div className={notice.ok ? "acNotice" : "err"}>{notice.text}</div>}
      {loading
        ? <div className="fmmEmpty">asking the engine…</div>
        : !error && !refinerTier
        ? <div className="fmmEmpty">main sent no refiner tier</div>
        : refinerTier && (
          <select className="acInput" aria-label="refiner tier" value={current} disabled={busy || !run}
            onChange={onChange}>
            {rows.map((r) => <option key={r.tier} value={r.tier}>{tierLabel(r.tier, r.model, r.effort)}</option>)}
          </select>
        )}
    </section>
  );
};

/**
 * The frakworks page's refiner-tier part: the section with its notice; the
 * page polls it (index.jsx), so it has no ⟳ of its own.
 * `result` is what main's `engine:refiner-tier` returned,
 * `{ refinerTier, error }`, or `{ loading: true }` before the first answer.
 * After a successful save it shows the engine's answer at once and refreshes;
 * a refusal (a 400 for a tier outside the engine's map) is the notice and the
 * shown tier stays. Without `onAction` it draws read-only.
 */
export const RefinerTierPanel = ({ result, onRefresh, onAction, tiers }) => {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null); // { ok, text }
  const [set, setSet] = useState(null); // the engine's answer to the last save, until the next read
  const run = async (action, done) => {
    setBusy(true); setNotice(null);
    let r;
    try { r = await onAction(action); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    if (!r || r.error) { setNotice({ ok: false, text: (r && r.error) || "no answer from the main process" }); return false; }
    setNotice({ ok: true, text: done });
    if (r.refinerTier) setSet({ refinerTier: r.refinerTier, over: result });
    if (onRefresh) onRefresh();
    return true;
  };
  const r = result || {};
  // A save's answer holds until main sends a newer read than the one it was made over.
  const refinerTier = set && set.over === result ? set.refinerTier : r.refinerTier || null;
  return <RefinerTier refinerTier={refinerTier} error={r.error} loading={r.loading} notice={notice}
    tiers={tiers} busy={busy} run={onAction ? run : null} />;
};
