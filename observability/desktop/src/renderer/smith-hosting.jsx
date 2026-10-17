// smith-hosting.jsx — Config > frakworks > Smith hosting (#1348): where new
// Smith runs start, `docker` or `kubernetes`, a setting the engine keeps
// (`GET /settings/smith-hosting`, engine/README.md "Hosting models"). Main
// reads it with `engine:smith-hosting`; changing it is one `onAction`,
// `{ op: "smith-hosting", hosting }`, which main sends over
// `engine:account-action` to the engine's `PUT /settings/smith-hosting`.
// Shape shared with refiner-tier.jsx: acSetting > acSettingHead, fmmHint, err/notice lines, an acInput select that saves on change.

import { useState } from "react";

/** The two values the engine takes (engine-data.js SMITH_HOSTINGS). */
export const HOSTINGS = ["docker", "kubernetes"];

/**
 * The Smith hosting in the refiner tier's shape: head, one-line hint, error
 * or notice lines, then a select that saves on change. Without `run` the
 * select is disabled. `notice` is the last save's `{ ok, text }`; `loading`
 * before main's first answer.
 */
export const SmithHosting = ({ smithHosting, error, loading, notice, busy, run }) => {
  const current = smithHosting ? smithHosting.hosting : null;
  const onChange = (e) => {
    const hosting = e.target.value;
    if (hosting !== current) return run({ op: "smith-hosting", hosting }, `Smith hosting set to ${hosting}`);
    return undefined;
  };
  return (
    <section className="acSetting">
      <div className="acSettingHead">
        <span className="acName">Smith hosting</span>
      </div>
      <p className="fmmHint">Where new Smith runs start, kept in the engine; a run already started keeps its own.</p>
      {error && <div className="err">{`Smith hosting unavailable: ${error}`}</div>}
      {notice && <div className={notice.ok ? "acNotice" : "err"}>{notice.text}</div>}
      {loading
        ? <div className="fmmEmpty">asking the engine…</div>
        : !error && !smithHosting
        ? <div className="fmmEmpty">main sent no Smith hosting</div>
        : smithHosting && (
          <select className="acInput" aria-label="Smith hosting" value={current} disabled={busy || !run}
            onChange={onChange}>
            {HOSTINGS.map((h) => <option key={h} value={h}>{h}</option>)}
          </select>
        )}
    </section>
  );
};

/**
 * The frakworks page's Smith hosting part, as RefinerTierPanel: `result` is
 * what main's `engine:smith-hosting` returned, `{ smithHosting, error }`, or
 * `{ loading: true }`. After a successful save it shows the engine's answer
 * at once and refreshes; a refusal (a 400, `kubernetes` with a Kubernetes
 * setting blank) or an unreachable engine is the notice and the shown value
 * stays. Without `onAction` it draws read-only.
 */
export const SmithHostingPanel = ({ result, onRefresh, onAction }) => {
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
    if (r.smithHosting) setSet({ smithHosting: r.smithHosting, over: result });
    if (onRefresh) onRefresh();
    return true;
  };
  const r = result || {};
  const smithHosting = set && set.over === result ? set.smithHosting : r.smithHosting || null;
  return <SmithHosting smithHosting={smithHosting} error={r.error} loading={r.loading} notice={notice}
    busy={busy} run={onAction ? run : null} />;
};

// Config > frakworks > First mate hosting (#2521): where a new First mate
// conversation runs, `local` (a `claude` child of this app) or `kubernetes` (a
// session pod on the cluster), the engine's `GET`/`PUT
// /settings/first-mate-hosting`. Same shape as Smith hosting; main reads it
// with `engine:first-mate-hosting` and saves `{ op: "first-mate-hosting", hosting }`.

/** The two values the engine takes (engine-data.js FIRST_MATE_HOSTINGS). */
export const FIRST_MATE_HOSTINGS = ["local", "kubernetes"];

/** The First mate hosting in Smith hosting's shape. */
export const FirstMateHosting = ({ firstMateHosting, error, loading, notice, busy, run }) => {
  const current = firstMateHosting ? firstMateHosting.hosting : null;
  const onChange = (e) => {
    const hosting = e.target.value;
    if (hosting !== current) return run({ op: "first-mate-hosting", hosting }, `First mate hosting set to ${hosting}`);
    return undefined;
  };
  return (
    <section className="acSetting">
      <div className="acSettingHead">
        <span className="acName">First mate hosting</span>
      </div>
      <p className="fmmHint">Where a new First mate conversation runs, kept in the engine; one already started keeps its own.</p>
      {error && <div className="err">{`First mate hosting unavailable: ${error}`}</div>}
      {notice && <div className={notice.ok ? "acNotice" : "err"}>{notice.text}</div>}
      {loading
        ? <div className="fmmEmpty">asking the engine…</div>
        : !error && !firstMateHosting
        ? <div className="fmmEmpty">main sent no First mate hosting</div>
        : firstMateHosting && (
          <select className="acInput" aria-label="First mate hosting" value={current} disabled={busy || !run}
            onChange={onChange}>
            {FIRST_MATE_HOSTINGS.map((h) => <option key={h} value={h}>{h}</option>)}
          </select>
        )}
    </section>
  );
};

/**
 * The page's First mate hosting part, as SmithHostingPanel: `result` is main's
 * `engine:first-mate-hosting` answer or `{ loading: true }`; the engine's
 * answer is shown after a save, a refusal (a 400: `kubernetes` before Smith
 * hosting is set up for it) is the notice and the shown value stays.
 */
export const FirstMateHostingPanel = ({ result, onRefresh, onAction }) => {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);
  const [set, setSet] = useState(null);
  const run = async (action, done) => {
    setBusy(true); setNotice(null);
    let r;
    try { r = await onAction(action); }
    catch (e) { r = { error: `IPC failed: ${e && e.message ? e.message : e}` }; }
    setBusy(false);
    if (!r || r.error) { setNotice({ ok: false, text: (r && r.error) || "no answer from the main process" }); return false; }
    setNotice({ ok: true, text: done });
    if (r.firstMateHosting) setSet({ firstMateHosting: r.firstMateHosting, over: result });
    if (onRefresh) onRefresh();
    return true;
  };
  const r = result || {};
  const firstMateHosting = set && set.over === result ? set.firstMateHosting : r.firstMateHosting || null;
  return <FirstMateHosting firstMateHosting={firstMateHosting} error={r.error} loading={r.loading} notice={notice}
    busy={busy} run={onAction ? run : null} />;
};
