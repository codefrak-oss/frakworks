// repo-name-form.jsx — Config > frakworks > Repo names (#2429): whether repos
// are drawn `owner/name` (long, the default) or by name alone (short) in the
// repo dropdowns and repo labels. The desktop's own choice, kept in
// localStorage like the palette; never the engine's, never what is stored or
// sent (that is always `owner/name`, src/repo-names.js `displayRepoName`).

import { useEffect, useState } from "react";
import { displayRepoName } from "../repo-names.js";

export const REPO_NAMES_KEY = "repo-names";
const CHANGED = "repo-names-changed";
export const REPO_NAME_MODES = [
  { id: "long", label: "Long (owner/name)" },
  { id: "short", label: "Short (name only)" },
];

export const loadRepoNameMode = () => {
  try { return localStorage.getItem(REPO_NAMES_KEY) === "short" ? "short" : "long"; } catch (e) { return "long"; }
};
export const saveRepoNameMode = (mode) => {
  const m = mode === "short" ? "short" : "long";
  try { localStorage.setItem(REPO_NAMES_KEY, m); } catch (e) { /* private window etc. */ }
  if (typeof window !== "undefined" && window.dispatchEvent && typeof Event !== "undefined") window.dispatchEvent(new Event(CHANGED));
  return m;
};

/** The current mode, redrawn when the section changes it. */
export const useRepoNameMode = () => {
  const [mode, setMode] = useState(loadRepoNameMode);
  useEffect(() => {
    if (typeof window === "undefined" || !window.addEventListener) return undefined;
    const on = () => setMode(loadRepoNameMode());
    window.addEventListener(CHANGED, on);
    return () => window.removeEventListener(CHANGED, on);
  }, []);
  return mode;
};

/** `name(full)`: the display name under the current mode, among `repos`. */
export const useRepoName = (repos) => {
  const mode = useRepoNameMode();
  return (full) => displayRepoName(full, mode, repos);
};

export const RepoNamePanel = () => {
  const mode = useRepoNameMode();
  return (
    <section className="acSetting">
      <div className="acSettingHead">
        <span className="acName">Repo names</span>
      </div>
      <p className="fmmHint">How repos read in the repo dropdowns and repo labels on this desktop; Config &gt; Repos and ticket references stay owner/name.</p>
      <select className="acInput" aria-label="repo names" value={mode}
              onChange={(e) => saveRepoNameMode(e.target.value)}>
        {REPO_NAME_MODES.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}
      </select>
    </section>
  );
};
