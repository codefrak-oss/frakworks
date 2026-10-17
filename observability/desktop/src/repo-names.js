// repo-names.js — comparing target repo names (`owner/name`). Pure, with no
// Node built-in, so the renderer bundle can use it as well as the main process.
"use strict";

// GitHub repo names are case-insensitive.
const sameRepo = (a, b) => String(a || "").toLowerCase() === String(b || "").toLowerCase();

// The name after the owner: `name` of `owner/name` (the whole string when it
// has no slash).
const shortName = (full) => { const s = String(full || ""); const i = s.indexOf("/"); return i < 0 ? s : s.slice(i + 1); };

/**
 * How a repo is drawn (#2429): `full` (`owner/name`) when `mode` is "long"
 * (the default), its name alone when "short" — unless another repo in `repos`
 * shares that name (case-insensitively), when it stays `owner/name` so the two
 * can be told apart. Display only: what is stored or sent is always `full`.
 */
const displayRepoName = (full, mode, repos) => {
  if (!full || mode !== "short") return full;
  const name = shortName(full).toLowerCase();
  const clash = (repos || []).some((r) => !sameRepo(r, full) && shortName(r).toLowerCase() === name);
  return clash ? full : shortName(full);
};

module.exports = { sameRepo, shortName, displayRepoName };
