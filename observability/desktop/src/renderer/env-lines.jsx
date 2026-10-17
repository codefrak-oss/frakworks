// env-lines.jsx — Config > frakworks > .env (#912), below Intake refiner tier:
// the deployment's `.env` keys that `.env.example` does not declare (unused)
// and the ones it declares that `.env` lacks (missing), from main's
// `env:check` (src/env-lines.js). Key names only: the renderer never receives
// a value. Remove sends the unused keys over `env:remove`; main asks in a
// native confirm naming the file and the keys, and nothing is deleted before
// the user says yes.
// Shape shared with refiner-tier.jsx: acSetting > acSettingHead, fmmHint, err/notice lines, then the content.

import { useState } from "react";

export const REVOKE_NOTE = "Removing a line does not revoke a token or password it held; revoke the credential where it was issued.";
export const CLEAN_HINT = "Every line in .env is declared in .env.example.";
const HINT = "Keys in the deployment's .env that .env.example does not declare, which nothing reads.";

/** "1 unused line in .env" / "3 unused lines in .env". */
export const unusedPhrase = (n) => `${n} unused ${n === 1 ? "line" : "lines"} in .env`;
/** "Removed 1 line" / "Removed 3 lines". */
export const removedPhrase = (n) => `Removed ${n} ${n === 1 ? "line" : "lines"}`;

/**
 * The section, drawing only. `result` is main's `env:check` answer,
 * `{ unused, missing, file }` or `{ error }`, or `{ loading: true }`; `notice`
 * the last removal's `{ ok, text }`; `busy` while one is in flight. Without
 * `onRemove` there is no Remove button.
 */
export const EnvLines = ({ result, notice, busy, onRemove }) => {
  const r = result || { loading: true };
  const unused = Array.isArray(r.unused) ? r.unused : [];
  const missing = Array.isArray(r.missing) ? r.missing : [];
  const clean = !r.loading && !r.error && !unused.length && !missing.length;
  return (
    <section className="acSetting envLines">
      <div className="acSettingHead">
        <span className="acName">.env</span>
      </div>
      <p className="fmmHint">{clean ? CLEAN_HINT : HINT}</p>
      {r.error && <div className="err">{`.env check unavailable: ${r.error}`}</div>}
      {notice && <div className={notice.ok ? "acNotice" : "err"}>{notice.text}</div>}
      {r.loading && <div className="fmmEmpty">reading .env…</div>}
      {unused.length > 0 && (
        <div className="envGroup">
          <div className="envCount">{unusedPhrase(unused.length)}</div>
          {r.file && <div className="envFile">{r.file}</div>}
          <ul className="envKeys">{unused.map((k) => <li key={k}>{k}</li>)}</ul>
          {onRemove && (
            <div className="acActions">
              <button type="button" className="acBtn danger" disabled={busy} onClick={() => onRemove(unused)}>
                {busy ? "Removing…" : "Remove"}
              </button>
            </div>
          )}
          <p className="envNote">{REVOKE_NOTE}</p>
        </div>
      )}
      {missing.length > 0 && (
        <div className="envGroup">
          <div className="envCount">Missing from .env</div>
          <ul className="envKeys">{missing.map((k) => <li key={k}>{k}</li>)}</ul>
        </div>
      )}
    </section>
  );
};

/**
 * The frakworks page's .env part: the section with its Remove. `onRemove(keys)`
 * is window.fleet.envRemove, answering `{ removed, check }`, `{ cancelled }`
 * or `{ error }`; on a removal the section redraws from the fresh check main
 * returns and says how many lines went. `onRefresh` asks main to check again.
 */
export const EnvLinesPanel = ({ result, onRemove, onRefresh }) => {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null); // { ok, text }
  const [fresh, setFresh] = useState(null); // the check main ran after a removal, until the next read
  const remove = async (keys) => {
    setBusy(true); setNotice(null);
    let r;
    try { r = await onRemove(keys); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    if (r && r.cancelled) return;
    if (!r || r.error) { setNotice({ ok: false, text: (r && r.error) || "no answer from the main process" }); return; }
    setNotice({ ok: true, text: removedPhrase(r.removed) });
    if (r.check) setFresh({ check: r.check, over: result });
    if (onRefresh) onRefresh();
  };
  const shown = fresh && fresh.over === result ? fresh.check : result;
  return <EnvLines result={shown} notice={notice} busy={busy} onRemove={onRemove ? remove : null} />;
};
