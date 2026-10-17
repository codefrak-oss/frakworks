// first-mate-model.jsx — Config > frakworks (#616): its first setting is
// First mate model (issue #240, part 2 of #226): the standing tier First mate
// runs at, picked from a drop-down. Drawing only, like
// first-mate.jsx: no fetch, no timers, no `window.` reference. index.jsx
// reads window.firstMate.tierState() and hands it over as `state`, and
// `onSetTier(n)` goes to window.firstMate.setTier. The rows are
// the engine's tier map's, as window.fleet.tiers() resolved them (`tiers`); only a
// tier number ever leaves here, never a model or an effort.
// Shape shared with refiner-tier.jsx: acSetting > acSettingHead, fmmHint, err/notice lines, an acInput select that saves on change.

// src/first-mate-tier.js DEFAULT_FIRST_MATE_TIER: what an unset config means.
export const DEFAULT_TIER = 8;

/** A picker row's text: `{ tier: 5, model: "opus", effort: "medium" }` -> "5 · opus medium". */
export const tierLabel = (row) => `${row.tier} · ${row.model} ${row.effort}`;

/**
 * `state` is tierState()'s answer (`{ standing, effective, error }`) or null
 * while loading; `busy` while a save is in flight; `error` the last save's
 * refusal. `children` are the page's further settings, drawn below this one
 * (index.jsx passes the Intake refiner tier, #617).
 */
export const FirstMateModel = ({ tiers, state, busy, error, onSetTier, children }) => {
  const rows = Array.isArray(tiers) ? tiers : [];
  const tiersError = tiers && !Array.isArray(tiers) ? tiers.error || "no tier table" : null;
  const standing = (state && state.standing) || DEFAULT_TIER;
  // The select is controlled by the saved tier, so a refused save leaves it on that tier.
  return (
    <div className="fmm">
      <div className="head">
        <h1>frakworks</h1>
      </div>
      <section className="acSetting">
        <div className="acSettingHead">
          <span className="acName">First mate model</span>
        </div>
        <p className="fmmHint">
          The tier First mate runs at.
        </p>
        {tiersError && <div className="err">{`could not load the tiers: ${tiersError}`}</div>}
        {state && state.error && <div className="err">{`could not read the config: ${state.error}`}</div>}
        {error && <div className="err">{`not saved: ${error}`}</div>}
        {!tiers && <div className="fmmEmpty">loading the tiers…</div>}
        {rows.length > 0 && (
          <select className="acInput" aria-label="first mate tier" value={standing} disabled={busy}
                  onChange={(e) => onSetTier(Number(e.target.value))}>
            {rows.map((r) => <option key={r.tier} value={r.tier}>{tierLabel(r)}</option>)}
          </select>
        )}
      </section>
      {children}
    </div>
  );
};
