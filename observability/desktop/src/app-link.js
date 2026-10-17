// app-link.js — `frakworks://ticket/<owner>/<name>/<n>`: a link that opens one
// ticket's detail in the app when clicked outside it (a terminal, a note, a
// chat). The packaged app claims the scheme (package.json, `build.protocols`);
// macOS hands main the URL (`open-url`), and this is all main reads from it.
"use strict";

/** The ticket a link names, as the renderer's `{ repo, number }`; null for anything else. */
const parseAppLink = (url) => {
  const m = /^frakworks:\/\/ticket\/([\w.-]+)\/([\w.-]+)\/(\d+)\/?$/i.exec(String(url == null ? "" : url).trim());
  if (!m) return null;
  const number = Number(m[3]);
  return Number.isSafeInteger(number) && number > 0 ? { repo: `${m[1]}/${m[2]}`, number } : null;
};

module.exports = { parseAppLink };
