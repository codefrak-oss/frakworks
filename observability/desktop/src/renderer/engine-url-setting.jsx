// engine-url-setting.jsx — Config > frakworks > Engine URL (#100144): where
// this install's engine is, kept by main under userData
// (src/engine-url-setting.js). No default: unset, the app makes no engine
// call and says so here. A save relaunches the app on the new URL. Desktop
// only: the web version's engine is the page's own origin.

import { useEffect, useState } from "react";

export const ENGINE_URL_UNSET = "No engine URL is set: the app makes no engine call until you enter one.";

export const EngineUrlPanel = ({ onLoad, onSave }) => {
  const [url, setUrl] = useState(null);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    Promise.resolve(onLoad()).then((r) => { const u = (r && r.url) || ""; setUrl(u); setDraft(u); },
      (e) => setError(String(e && e.message ? e.message : e)));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const save = async () => {
    setError(null); setSaving(true);
    try {
      const r = await onSave(draft.trim());
      if (r && r.error) setError(r.error);
      else setUrl((r && r.url) || "");
    } catch (e) { setError(String(e && e.message ? e.message : e)); }
    setSaving(false);
  };
  return (
    <section className="acSetting">
      <div className="acSettingHead">
        <span className="acName">Engine URL</span>
      </div>
      <p className="fmmHint">The engine this app reads and writes, e.g. https://engine.example, or the local stack's http://localhost:&lt;port&gt;. Kept on this machine; saving relaunches the app.</p>
      {url === "" && <div className="err">{ENGINE_URL_UNSET}</div>}
      {error && <div className="err">{error}</div>}
      {url == null
        ? <div className="fmmEmpty">reading…</div>
        : (
          <form onSubmit={(e) => { e.preventDefault(); save(); }}>
            <input type="url" aria-label="Engine URL" placeholder="https://…" value={draft}
              onChange={(e) => setDraft(e.target.value)} />
            {" "}<button type="submit" disabled={saving || draft.trim() === url}>Save</button>
          </form>
        )}
    </section>
  );
};
