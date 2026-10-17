package com.codefrak.engine.proposal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pitch page's hard rules (engine/smith/pitch-guide.md). */
class PitchRulesTest {
    private val path = "docs/proposals/widgets.html"

    private fun refused(html: String, what: String) {
        val problems = PitchRules.problems(path, html)
        assertTrue(problems.any { what in it && PitchRules.GUIDE in it && it.startsWith("$path: ") }, "$what: $problems")
    }

    /** [EXAMPLE] with `from` replaced by `to`. */
    private fun example(from: String, to: String): String {
        assertTrue(from in EXAMPLE, from)
        return EXAMPLE.replace(from, to)
    }

    @Test
    fun `the guide's own example page passes`() {
        assertEquals(emptyList(), PitchRules.problems(path, EXAMPLE))
    }

    @Test
    fun `links, data URLs, fragments and inline script pass`() {
        val page = example("</main>", """<p><a href="https://github.com/acme/widgets/pull/7">the PR</a> <a
            href='//example.com/x'>x</a></p>
            <div style="background: url(data:image/png;base64,AAAA)"></div><div style="fill: url( '#arrow' )"></div>
            <img src="data:image/svg+xml;base64,AAAA" alt=""><script>document.title = 'x'</script>
            </main>""")
        assertEquals(emptyList(), PitchRules.problems(path, page))
    }

    @Test
    fun `a script src is refused`() {
        refused(example("</body>", "<script src=\"https://cdn.example.com/x.js\"></script></body>"), "no <script src>")
        refused(example("</body>", "<SCRIPT type=module SRC=app.js></SCRIPT></body>"), "no <script src>")
    }

    @Test
    fun `a remote stylesheet is refused`() {
        refused(example("<style>", "<link rel=\"stylesheet\" href=\"https://cdn.example.com/x.css\">\n<style>"), "no <link>")
        refused(example("<style>", "<link rel=stylesheet href=local.css>\n<style>"), "no <link>")
    }

    @Test
    fun `an import is refused`() {
        refused(example("<style>", "<style>\n  @import 'theme.css';"), "no @import")
    }

    @Test
    fun `a remote font url is refused`() {
        refused(example("<style>", "<style>\n  @font-face { font-family: X; src: url(\"https://fonts.example.com/x.woff2\") format('woff2'); }"),
            "no remote url()")
        refused(example("<style>", "<style>\n  @font-face { font-family: X; src: url(//fonts.example.com/x.woff2); }"), "no remote url()")
    }

    @Test
    fun `a remote image or embedded href is refused`() {
        refused(example("</main>", "<img src=\"https://example.com/x.png\" alt=\"\"></main>"), "no remote src")
        refused(example("</g>\n</svg>", "</g><image href=\"https://example.com/x.png\"/>\n</svg>"), "no remote href")
    }

    @Test
    fun `a page with no svg is refused`() {
        val start = EXAMPLE.indexOf("<svg"); val end = EXAMPLE.indexOf("</svg>") + "</svg>".length
        refused(EXAMPLE.removeRange(start, end), "no inline <svg>")
    }

    @Test
    fun `a page with no dark scheme rule is refused`() {
        refused(example("prefers-color-scheme: dark", "prefers-color-scheme: light"), "no @media (prefers-color-scheme: dark)")
        // Commented out is not there.
        refused(example("@media (prefers-color-scheme: dark) {", "/* @media (prefers-color-scheme: dark) */ @media print {"),
            "no @media (prefers-color-scheme: dark)")
    }

    @Test
    fun `a page with no viewport meta is refused`() {
        refused(example("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">", ""), "no <meta name=\"viewport\">")
    }

    private val options = """<section data-option-id="a"><h2>A</h2><button disabled data-choose="a">Choose this one</button></section>
        <section data-option-id='b'><h2>B</h2><button type="button" data-choose="b" disabled>Choose this one</button></section></main>"""

    @Test
    fun `declared options with their blocks and disabled placeholders pass`() {
        assertEquals(emptyList(), PitchRules.problems(path, example("</main>", options), listOf("a", "b")))
    }

    @Test
    fun `an option with no block or no choose button is refused`() {
        val page = example("</main>", options)
        val problems = PitchRules.problems(path, page, listOf("a", "b", "c"))
        assertTrue(problems.any { "option 'c' has no data-option-id" in it }, problems.toString())
        assertTrue(problems.any { "option 'c' has no <button disabled data-choose" in it }, problems.toString())
    }

    @Test
    fun `an option in the page but not declared, or a live choose button, is refused`() {
        refused(example("</main>", options), "option 'a' is in the page but not among the result's options")
        val live = example("</main>", options.replace("<button disabled data-choose=\"a\">", "<button data-choose=\"a\">"))
        assertTrue(PitchRules.problems(path, live, listOf("a", "b")).any { "option 'a' is not disabled" in it })
    }

    companion object {
        /** The minimal compliant page in engine/smith/pitch-guide.md, copied verbatim: keep the two the same. */
        val EXAMPLE = """
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
""".trimStart('\n')
    }
}
