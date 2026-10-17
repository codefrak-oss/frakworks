import React from "react";

// A JSON value as colour-coded, 2-space pretty-printed React elements (#607):
// keys, strings, numbers and booleans/null each get their own class. Built
// from elements, never innerHTML, so engine data cannot inject markup. A
// string value at the top is shown as plain text (the engine stores a
// non-JSON response as text).

const pad = (n) => "  ".repeat(n);

const scalar = (v, key) => {
  if (v === null || typeof v === "boolean") return <span key={key} className="jsonLit">{String(v)}</span>;
  if (typeof v === "number") return <span key={key} className="jsonNum">{String(v)}</span>;
  return <span key={key} className="jsonStr">{JSON.stringify(String(v))}</span>;
};

const walk = (v, depth, out) => {
  if (Array.isArray(v) || (v && typeof v === "object")) {
    const arr = Array.isArray(v);
    const entries = arr ? v.map((x, i) => [i, x]) : Object.entries(v);
    const [open, close] = arr ? ["[", "]"] : ["{", "}"];
    if (!entries.length) { out.push(open + close); return; }
    out.push(open + "\n");
    entries.forEach(([k, x], i) => {
      out.push(pad(depth + 1));
      if (!arr) out.push(<span key={out.length} className="jsonKey">{JSON.stringify(k)}</span>, ": ");
      walk(x, depth + 1, out);
      out.push((i < entries.length - 1 ? "," : "") + "\n");
    });
    out.push(pad(depth) + close);
    return;
  }
  out.push(scalar(v, out.length));
};

/** `value` highlighted in a <pre className="jevPre jevJson">; a top-level string as plain text. */
export const JsonView = ({ value }) => {
  if (value === undefined) return <pre className="jevPre jevJson">–</pre>;
  if (typeof value === "string") return <pre className="jevPre jevJson jsonText">{value}</pre>;
  const out = [];
  walk(value, 0, out);
  return <pre className="jevPre jevJson">{out}</pre>;
};
