// repo-switcher.jsx — the repo switcher (#577):
// the title strip's leftmost item. It reads `[repo icon] owner/name ▾` (the
// Octicon `repo` book, inline SVG) for one target
// repo or `All repos ▾`, and its dropdown lists the engine's GET /repos, each
// with its needs count, then All repos, then "Manage repos…" (Config > Repos).
//
// Every view but a ticket's own detail follows the selection, which lives in
// main (src/repo-selection.js); this file only draws it and hands a pick up.
// Also here: a ticket is `owner/name#n`, and the helpers that spell one.

import { useEffect, useRef } from "react";
import { displayRepoName } from "../repo-names.js";
import { useRepoNameMode } from "./repo-name-form.jsx";

export const ALL_REPOS = "All repos";

// The Octicon `repo` (book) glyph at text height, before a repo name: a
// marker, not a box, so it doesn't read as a checkbox next to aria-checked.
export function RepoIcon() {
  return (
    <svg className="repoIcon" viewBox="0 0 16 16" width="1em" height="1em" aria-hidden="true" fill="currentColor">
      <path d="M2 2.5A2.5 2.5 0 0 1 4.5 0h8.75a.75.75 0 0 1 .75.75v12.5a.75.75 0 0 1-.75.75h-2.5a.75.75 0 0 1 0-1.5h1.75v-2h-8a1 1 0 0 0-.714 1.7.75.75 0 1 1-1.072 1.05A2.495 2.495 0 0 1 2 11.5Zm10.5-1h-8a1 1 0 0 0-1 1v6.708A2.486 2.486 0 0 1 4.5 9h8ZM5 12.25a.25.25 0 0 1 .25-.25h3.5a.25.25 0 0 1 .25.25v3.25a.25.25 0 0 1-.4.2l-1.45-1.087a.249.249 0 0 0-.3 0L5.4 15.7a.25.25 0 0 1-.4-.2Z" />
    </svg>
  );
}

export const titleFor = (repo) => (repo ? repo : ALL_REPOS);

// "owner/name#n", the id a ticket goes by in the explorer and lastSel.
export const ticketId = (repo, number) => `${repo}#${Number(number)}`;

/**
 * A ticket reference as `{ repo, number }`: `owner/name#n`, or a bare `n` (or
 * a number, an old explorer entry) which is in `fallbackRepo`, the selection.
 * Anything else is null.
 */
export const parseTicketRef = (ref, fallbackRepo = null) => {
  if (ref == null || ref === "") return null;
  const m = /^([\w.-]+\/[\w.-]+)#(\d+)$/.exec(String(ref).trim());
  if (m) return { repo: m[1], number: Number(m[2]) };
  const n = Number(ref);
  return Number.isInteger(n) && n > 0 ? { repo: fallbackRepo || null, number: n } : null;
};

// How a ticket is labelled: `#n` when one repo is selected and it is that
// one, `owner/name#n` in All repos or when it is another repo's.
export const ticketLabel = (repo, number, selected) =>
  selected && repo === selected ? `#${number}` : `${repo}#${number}`;

/**
 * `selection` is window.repo.get()'s answer: `{ repo, repos, needs, error }`.
 * `onPick(repo)` gets a repo or null (All repos); `onManage()` opens Config >
 * Repos. The strip around it is a drag region; this control is `no-drag`.
 */
// `nameMode` overrides Config > frakworks > Repo names ("long" or "short"; tests).
export const RepoSwitcher = ({ selection, open, onToggle, onPick, onManage, nameMode }) => {
  const sel = selection || {};
  const repo = sel.repo || null;
  const repos = sel.repos || [];
  const needs = sel.needs || {};
  const stored = useRepoNameMode();
  const name = (r) => displayRepoName(r, nameMode || stored, repos);
  const ref = useRef(null);
  useEffect(() => {
    if (!open || typeof document === "undefined") return undefined;
    const close = (e) => { if (ref.current && !ref.current.contains(e.target)) onToggle && onToggle(false); };
    const esc = (e) => { if (e.key === "Escape") onToggle && onToggle(false); };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", esc);
    return () => { document.removeEventListener("mousedown", close); document.removeEventListener("keydown", esc); };
  }, [open, onToggle]);
  const pick = (r) => { onToggle && onToggle(false); onPick && onPick(r); };
  return (
    <div className="repoSwitcher" ref={ref}>
      <button className={"repoSwitcherBtn" + (open ? " open" : "")} title="Switch repo"
              aria-haspopup="menu" aria-expanded={open ? "true" : "false"}
              onClick={() => onToggle && onToggle(!open)}>
        {repo ? <><RepoIcon />{`${name(repo)} ▾`}</> : `${ALL_REPOS} ▾`}
      </button>
      {open && (
        <div className="repoMenu" role="menu">
          {sel.error && <div className="repoMenuErr">{String(sel.error)}</div>}
          {repos.map((r) => (
            <button key={r} role="menuitemradio" aria-checked={r === repo ? "true" : "false"}
                    className={"repoMenuItem" + (r === repo ? " on" : "")} onClick={() => pick(r)}>
              <span className="repoMenuName"><RepoIcon />{name(r)}</span>
              <span className={"repoBadge" + (needs[r] ? " some" : "")}
                    title={`${needs[r] || 0} waiting on you`}>{needs[r] || 0}</span>
            </button>
          ))}
          <button role="menuitemradio" aria-checked={repo ? "false" : "true"}
                  className={"repoMenuItem all" + (repo ? "" : " on")} onClick={() => pick(null)}>
            <span className="repoMenuName">{ALL_REPOS}</span>
          </button>
          <div className="repoMenuSep" />
          <button role="menuitem" className="repoMenuItem manage"
                  onClick={() => { onToggle && onToggle(false); onManage && onManage(); }}>
            <span className="repoMenuName">Manage repos…</span>
          </button>
        </div>
      )}
    </div>
  );
};
