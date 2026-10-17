// screenshot-setting.jsx — Config > frakworks > "Let First mate screenshot
// this app" (#100105): this machine's permission for First mate's
// screenshot_desktop tool, kept by main under userData (src/screenshot.js),
// off until the user turns it on. Desktop only: the web version has no window.fleet.screenshotSetting.

import { useEffect, useState } from "react";

export const ScreenshotSettingPanel = ({ onLoad, onSave }) => {
  const [allowed, setAllowed] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    Promise.resolve(onLoad()).then((r) => setAllowed(Boolean(r && r.allowed)), (e) => setError(String(e && e.message ? e.message : e)));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const save = async (on) => {
    setError(null);
    try { const r = await onSave(on); setAllowed(Boolean(r && r.allowed)); }
    catch (e) { setError(String(e && e.message ? e.message : e)); }
  };
  return (
    <section className="acSetting">
      <div className="acSettingHead">
        <span className="acName">Let First mate screenshot this app</span>
      </div>
      <p className="fmmHint">First mate may take a screenshot of this window, whole or one pane, through the engine, when it calls screenshot_desktop. Off until you turn it on; kept on this machine.</p>
      {error && <div className="err">{error}</div>}
      {allowed == null
        ? <div className="fmmEmpty">reading…</div>
        : (
          <label>
            <input type="checkbox" aria-label="Let First mate screenshot this app" checked={allowed}
              onChange={(e) => save(e.target.checked)} />
            {" allow screenshots"}
          </label>
        )}
    </section>
  );
};
