// The markdown renderer the ticket detail (ticket.jsx) and the HITLP task view
// (hitlp-task.jsx) share: `marked` plus DOMPurify, inline SVG kept.
import DOMPurify from "dompurify";
import { Marked } from "marked";

export const sanitizeBody = (html) => {
  if (!html || !DOMPurify.isSupported) return null;
  return DOMPurify.sanitize(html, { FORBID_TAGS: ["style", "form", "input", "button"], ADD_ATTR: ["target"] });
};

// A work item's detail (the engine's WorkItemDetails) sends no HTML for its
// body or its comments: their markdown is rendered here, with `marked`, and
// goes through the same sanitiser. Not GitHub's POST /markdown: it strips
// inline SVG, and a design pitch is drawn as one. A link gets target="_blank"
// as GitHub's rendering has, so main.js's will-navigate handler opens it.
const md = new Marked({
  gfm: true, async: false,
  renderer: { link({ href, title, tokens }) {
    const t = title ? ` title="${escapeAttr(title)}"` : "";
    return `<a href="${escapeAttr(href)}"${t} target="_blank" rel="noopener noreferrer">${this.parser.parseInline(tokens)}</a>`;
  } },
});
const escapeAttr = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

export const markdownToHtml = (body) => md.parse(String(body || ""));

/** The HTML to draw for a body: the engine's when it sent some, else its markdown rendered here; null for raw. `sanitize` is injectable for tests. */
export const bodyHtml = (body, html, sanitize = sanitizeBody) => {
  if (html) return sanitize(html);
  const text = String(body || "").trim();
  return text ? sanitize(markdownToHtml(text)) : null;
};

