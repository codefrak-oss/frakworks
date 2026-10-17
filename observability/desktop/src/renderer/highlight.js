// highlight.js — the repo file view's syntax highlighting (#675): highlight.js
// core with only the languages src/highlight-languages.js maps to, not the
// whole library. Its output is highlight.js's own escaped HTML (every `<`, `&`
// and quote of the file is an entity; the only tags are its `<span
// class="hljs-…">`), so a file containing `<script>` draws it as text. The
// colours are styles.css's `.repoCode` rules, dark and light.

import hljs from "highlight.js/lib/core";
import javascript from "highlight.js/lib/languages/javascript";
import typescript from "highlight.js/lib/languages/typescript";
import python from "highlight.js/lib/languages/python";
import kotlin from "highlight.js/lib/languages/kotlin";
import java from "highlight.js/lib/languages/java";
import bash from "highlight.js/lib/languages/bash";
import yaml from "highlight.js/lib/languages/yaml";
import ini from "highlight.js/lib/languages/ini";
import json from "highlight.js/lib/languages/json";
import css from "highlight.js/lib/languages/css";
import xml from "highlight.js/lib/languages/xml";
import markdown from "highlight.js/lib/languages/markdown";
import dockerfile from "highlight.js/lib/languages/dockerfile";
import sql from "highlight.js/lib/languages/sql";
import plaintext from "highlight.js/lib/languages/plaintext";
import { LANGUAGES, languageFor } from "../highlight-languages.js";

export const REGISTERED = { javascript, typescript, python, kotlin, java, bash, yaml, ini, json, css, xml, markdown, dockerfile, sql, plaintext };
for (const [name, lang] of Object.entries(REGISTERED)) hljs.registerLanguage(name, lang);

/** Languages the map names that are not registered here: none, or the map and this file disagree. */
export const unregistered = () => LANGUAGES.filter((l) => !hljs.getLanguage(l));

/** `text` as escaped, highlighted HTML in `language` (plaintext when it is not one of ours). */
export const highlightHtml = (text, language) => {
  const lang = language && hljs.getLanguage(language) ? language : "plaintext";
  return hljs.highlight(String(text ?? ""), { language: lang, ignoreIllegals: true }).value;
};

/** `text` highlighted as the language of `path` says: `{ language, html }`. */
export const highlightFile = (path, text) => {
  const language = languageFor(path);
  return { language, html: highlightHtml(text, language) };
};
