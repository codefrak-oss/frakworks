// highlight-languages.js — which highlight.js language a repo file is drawn in
// (#675), chosen by its extension alone. One small pure map: the renderer
// (src/renderer/highlight.js) registers exactly the languages named here, so a
// language added to the map is also imported and registered there.
"use strict";

const { extensionOf } = require("./repo-files");

/** Extension (lower case, no dot) → highlight.js language name. */
const LANGUAGE_BY_EXTENSION = Object.freeze({
  js: "javascript", mjs: "javascript", cjs: "javascript", jsx: "javascript",
  ts: "typescript", mts: "typescript", cts: "typescript", tsx: "typescript",
  py: "python",
  kt: "kotlin", kts: "kotlin",
  java: "java",
  sh: "bash", bash: "bash", zsh: "bash",
  yaml: "yaml", yml: "yaml",
  toml: "ini", ini: "ini",
  json: "json",
  css: "css",
  html: "xml", htm: "xml", xml: "xml", svg: "xml", bpmn: "xml",
  md: "markdown", markdown: "markdown",
  dockerfile: "dockerfile",
  sql: "sql",
  txt: "plaintext",
});

/** File names with no telling extension that still have a language. */
const LANGUAGE_BY_NAME = Object.freeze({ dockerfile: "dockerfile", containerfile: "dockerfile" });

/** Every language the map can answer, plaintext included: what the renderer registers. */
const LANGUAGES = Object.freeze([...new Set([...Object.values(LANGUAGE_BY_EXTENSION), ...Object.values(LANGUAGE_BY_NAME)])].sort());

/** The language `path` is highlighted as; an unknown extension is "plaintext". */
const languageFor = (path) => {
  const name = String(path || "").split("/").pop().toLowerCase();
  return LANGUAGE_BY_NAME[name] || LANGUAGE_BY_EXTENSION[extensionOf(path)] || "plaintext";
};

module.exports = { LANGUAGE_BY_EXTENSION, LANGUAGE_BY_NAME, LANGUAGES, languageFor };
