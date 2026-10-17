# The pitch page

A propose Smith writes two files per proposal: `docs/proposals/<slug>.md`, the
record, and `docs/proposals/<slug>.html`, the pitch: one self-contained page
in the style of a Claude artifact that shows the same proposal at a glance.
The user opens it from the ready-for-decision comment, on GitHub or a phone.
The engine lints it (`PitchRules`, engine/README.md "Proposal") and refuses a
draft whose pitch breaks a hard rule below.

## Hard rules (the engine refuses a page that breaks one)

1. **One file.** Everything is in `docs/proposals/<slug>.html`, the `.md`'s
   slug with `.html`, directly in `docs/proposals/`. Nothing beside it.
2. **Inline CSS and inline SVG only.** Styles in one `<style>` block (or
   `style=` attributes); every diagram an `<svg>` element in the page.
3. **Nothing remote.** No `<script src>`, no `<link>` (stylesheets or
   otherwise), no `@import`, no `url()` to `http:`, `https:` or `//` (a
   `data:` URL or a `#fragment` is fine), no web fonts (`@font-face` with a
   remote `src`), and no `src`, `srcset` or `href` on a remote URL except a
   plain `<a href>` link. Use system fonts: `font-family: system-ui,
   -apple-system, "Segoe UI", sans-serif`. The page needs no JavaScript; write
   none.
4. **A viewport meta tag:** `<meta name="viewport" content="width=device-width, initial-scale=1">`.
5. **A dark scheme:** a `@media (prefers-color-scheme: dark)` rule that
   swaps the colours. Define colours as CSS custom properties on `:root` and
   override them in the dark rule, and use `currentColor` or the properties
   in SVG `fill`/`stroke`, so the diagrams switch too.
6. **Works at phone width** (360px): fluid widths, a `max-width` on the main
   column, every `<svg>` with a `viewBox` and `width: 100%; height: auto`, and
   wide tables in a wrapper with `overflow-x: auto`.

## Content, in this order

1. **The question**, in a sentence or two, and the doc's `Status:`.
2. **The proposal**: what it is and how it works, with **at least one
   architecture or flow diagram of the mechanism** as inline SVG: boxes for
   the parts, arrows for what flows between them, labels in plain words.
3. **The alternatives** as a comparison table: the proposal and each
   alternative as rows, the criteria that decide between them as columns,
   and a "why not" for each alternative.
4. **Open questions**, as a list.
5. **The implementation outline**, numbered as in the doc.
6. **The cost** (tiers, runs), as in the doc.

## Options and "Choose this one"

When the proposal lays out options for the user to pick from (A, B, C…),
every option carries a choose button, and the engine refuses a page that
gets this wrong (`PitchRules`):

- List each option in `result.json` as `"options": [{"id": "a", "label":
  "Blue widgets"}, …]`: the id one lowercase letter, unique, and **stable**
  across redrafts (option B stays `b`); the label a few words.
- Wrap each option's part of the page in an element with
  `data-option-id="<id>"`, and put inside it the placeholder
  `<button disabled data-choose="<id>">Choose this one</button>`. It must be
  `disabled`: the page is shown sandboxed, with no script, so the button
  does nothing; the desktop reads the options from the engine and draws the
  options beside the page, and the user decides the proposal's open
  question or decision on the HITLP server.
- No `data-option-id` or `data-choose` for an id the result does not list.
- A page with no options to pick (one proposal, alternatives only compared)
  lists none and marks none.

`check-result propose` checks the page against `options` with the engine's
own rules and messages, so run it after writing the page and the result. A
result the engine still refuses runs the draft again once, with a new Smith
told why; a second refusal parks on an incident.

## Keep it true to the `.md`

- The `.md` is the record and wins. The pitch says the same thing in less
  space; it adds no claim, option, number or step the `.md` does not have,
  and draws nothing the `.md` does not say.
- Rewrite the pitch on every draft, redraft and revisit, in the same commit
  as the `.md`, so the two never disagree. The review checks they agree.
- Link to the `.md` on GitHub with a plain `<a href>` if you like.

## Design

- One column, `max-width: 760px`, generous line height (1.5), 16px base.
- Section headings as in the doc; a short lead paragraph under the title.
- Few colours: one text colour, one muted, one accent, one surface. Contrast
  must hold in both schemes.
- Diagrams: rounded rectangles (`rx="6"`), 1.5px strokes, text at 13–14px
  with `text-anchor="middle"`, arrowheads from one `<marker>` in `<defs>`.
  Lay flows left to right or top to bottom; keep to about seven boxes. Give
  each `<svg>` a `role="img"` and an `aria-label` saying what it shows.
- Tables: header row on the surface colour, cells padded, borders thin.

## A minimal compliant page

The engine's tests (`PitchRulesTest`) lint this page verbatim; a lint change
that refuses it is a bug in the lint or in this guide.

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Widgets: should we build them?</title>
<style>
  :root { --fg: #1f2328; --muted: #59636e; --accent: #0969da; --surface: #f6f8fa; --bg: #ffffff; }
  @media (prefers-color-scheme: dark) {
    :root { --fg: #e6edf3; --muted: #9198a1; --accent: #4493f8; --surface: #151b23; --bg: #0d1117; }
  }
  body { margin: 0; background: var(--bg); color: var(--fg); font: 16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
  main { max-width: 760px; margin: 0 auto; padding: 16px; }
  .muted { color: var(--muted); }
  svg { width: 100%; height: auto; }
  .table { overflow-x: auto; }
  table { border-collapse: collapse; width: 100%; }
  th, td { border: 1px solid var(--muted); padding: 6px 8px; text-align: left; vertical-align: top; }
  th { background: var(--surface); }
</style>
</head>
<body>
<main>
<h1>Widgets: should we build them?</h1>
<p class="muted">Status: proposed · <a href="https://github.com/acme/widgets/blob/main/docs/proposals/widgets.md">the doc</a></p>
<h2>The question</h2>
<p>Should the engine make widgets, and how?</p>
<h2>The proposal</h2>
<p>The engine asks the maker for a widget and stores it.</p>
<svg viewBox="0 0 480 90" role="img" aria-label="The engine asks the maker, which stores the widget">
  <defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10z" fill="currentColor"/></marker></defs>
  <g fill="none" stroke="currentColor" stroke-width="1.5">
    <rect x="10" y="20" width="120" height="50" rx="6"/><rect x="180" y="20" width="120" height="50" rx="6"/><rect x="350" y="20" width="120" height="50" rx="6"/>
    <path d="M130 45H178" marker-end="url(#arrow)"/><path d="M300 45H348" marker-end="url(#arrow)"/>
  </g>
  <g fill="currentColor" font-size="14" text-anchor="middle">
    <text x="70" y="50">Engine</text><text x="240" y="50">Maker</text><text x="410" y="50">Store</text>
  </g>
</svg>
<h2>Alternatives, and why not</h2>
<div class="table"><table>
  <tr><th>Option</th><th>Cost</th><th>Why not</th></tr>
  <tr><td>The proposal: the engine makes widgets</td><td>2 runs</td><td>—</td></tr>
  <tr><td>Buy widgets</td><td>none</td><td>No supplier fits.</td></tr>
</table></div>
<h2>Open questions</h2>
<ul><li>How many widgets a day?</li></ul>
<h2>Implementation outline</h2>
<ol><li>Add the maker.</li><li>Store what it makes.</li></ol>
<h2>Cost</h2>
<p>One feature at tier 6: a design run, two work runs and their reviews.</p>
</main>
</body>
</html>
```
