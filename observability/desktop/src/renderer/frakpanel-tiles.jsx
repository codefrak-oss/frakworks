// frakpanel-tiles.jsx — Config > frakworks > frakpanel tiles (#1338): whether
// the engine hosts tiles for a frakpanel (codefrak-oss/frakpanel), a setting the
// engine keeps (`GET /frakpanel/tiles/enabled`, engine/README.md "frakpanel
// tiles"), off by default. Main reads it with `engine:frakpanel-tiles`;
// changing it is one `onAction`, `{ op: "frakpanel-tiles", enabled }`.
// Shape shared with refiner-tier.jsx: acSetting > acSettingHead, fmmHint, err/notice lines.

import { useCallback, useEffect, useState } from "react";

/** The frakworks issues tile's URL, beside the AI accounts tile's that main reads (`frakpanelTiles.url`). */
const issuesTileUrl = (url) => String(url || "").replace(/ai-accounts\/?$/, "frakworks-issues");

/**
 * The section: head, hint, error or notice, a checkbox that saves on change,
 * and both tiles' URLs while on (the frakworks issues tile's beside the AI accounts one). Without `run` the checkbox is disabled.
 */
export const FrakpanelTiles = ({ frakpanelTiles, error, loading, notice, busy, run }) => (
  <section className="acSetting">
    <div className="acSettingHead">
      <span className="acName">frakpanel tiles</span>
    </div>
    <p className="fmmHint">Lets the engine serve tiles for a frakpanel slot (a local_tiles.json entry). Off unless you have a panel.</p>
    {error && <div className="err">{`frakpanel tiles unavailable: ${error}`}</div>}
    {notice && <div className={notice.ok ? "acNotice" : "err"}>{notice.text}</div>}
    {loading
      ? <div className="fmmEmpty">asking the engine…</div>
      : frakpanelTiles && (
        <>
          <label>
            <input type="checkbox" aria-label="frakpanel tiles" checked={frakpanelTiles.enabled} disabled={busy || !run}
              onChange={(e) => run({ op: "frakpanel-tiles", enabled: e.target.checked },
                `frakpanel tiles turned ${e.target.checked ? "on" : "off"}`)} />
            {" host frakpanel tiles"}
          </label>
          {frakpanelTiles.enabled && (
            <>
              <p className="fmmHint">{"AI accounts tile: "}<code>{frakpanelTiles.url}</code></p>
              <p className="fmmHint">{"frakworks issues tile: "}<code>{issuesTileUrl(frakpanelTiles.url)}</code></p>
            </>
          )}
        </>
      )}
  </section>
);

/**
 * The frakworks page's part: reads with `onLoad` (main's `engine:frakpanel-tiles`,
 * `{ frakpanelTiles, error }`) on mount and after each save, and saves with `onAction`.
 * `initial` skips the first read (tests).
 */
export const FrakpanelTilesPanel = ({ onLoad, onAction, initial }) => {
  const [result, setResult] = useState(initial || { loading: true });
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null); // { ok, text }
  const load = useCallback(async () => {
    let r;
    try { r = await onLoad(); }
    catch (e) { r = { frakpanelTiles: null, error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setResult(r || { frakpanelTiles: null, error: "no response from the main process" });
  }, [onLoad]);
  useEffect(() => { if (!initial && onLoad) load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const run = async (action, done) => {
    setBusy(true); setNotice(null);
    let r;
    try { r = await onAction(action); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    if (!r || r.error) { setNotice({ ok: false, text: (r && r.error) || "no answer from the main process" }); return false; }
    setNotice({ ok: true, text: done });
    if (r.frakpanelTiles) setResult({ frakpanelTiles: r.frakpanelTiles, error: null });
    return true;
  };
  return <FrakpanelTiles frakpanelTiles={result.frakpanelTiles || null} error={result.error} loading={result.loading}
    notice={notice} busy={busy} run={onAction ? run : null} />;
};
