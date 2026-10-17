package com.codefrak.engine.proposal

/**
 * The hard rules on a proposal's pitch page (engine/smith/pitch-guide.md, in the Smith image at [GUIDE];
 * engine/README.md, "Proposal"): one self-contained file, so nothing remote (a plain `<a href>` link is
 * fine), with an inline `<svg>`, a `prefers-color-scheme: dark` rule and a viewport meta tag. Regexes on the
 * text, case-insensitive, with HTML and CSS comments stripped first, so a commented-out rule neither
 * refuses nor passes a page. engine/smith/check-result.py ports these rules, with the same messages, so a
 * propose Smith lints its page in the sandbox (`check-result propose`): change the two together.
 */
object PitchRules {
    const val GUIDE = "/etc/smith/pitch-guide.md"

    private val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val COMMENTS = Regex("""<!--.*?-->|/\*.*?\*/""", opts)
    /** A remote target: http:, https: or protocol-relative, after an optional quote and spaces. */
    private const val REMOTE = """\s*['"]?\s*(?:https?:|//)"""

    private val FORBIDDEN = listOf(
        Regex("""<script\b[^>]*\ssrc\s*=""", opts) to "no <script src>: write no script, or inline it",
        Regex("""<link\b""", opts) to "no <link> (a stylesheet or anything else): put the CSS in a <style> block",
        Regex("""@import\b""", opts) to "no @import: put the CSS in the page",
        Regex("""url\($REMOTE""", opts) to "no remote url() (a web font, an image): use data: URLs or inline SVG",
        Regex("""\s(?:src|srcset)\s*=$REMOTE""", opts) to "no remote src or srcset: inline the image as SVG or a data: URL",
        Regex("""<(?!a\b)[a-z][\w:-]*\b[^>]*\s(?:xlink:)?href\s*=$REMOTE""", opts) to "no remote href except a plain <a href> link",
    )

    private val REQUIRED = listOf(
        Regex("""<svg\b""", opts) to "no inline <svg>: draw at least one diagram of the mechanism",
        Regex("""prefers-color-scheme\s*:\s*dark""", opts) to "no @media (prefers-color-scheme: dark) rule",
        Regex("""<meta\b[^>]*\bname\s*=\s*['"]?viewport""", opts) to "no <meta name=\"viewport\"> tag",
    )

    /** An option block's id, `data-option-id="a"`, and its choose placeholder's, `data-choose="a"`. */
    private val OPTION_ID = Regex("""\sdata-option-id\s*=\s*['"]?([^'"\s>]*)""", opts)
    private val CHOOSE = Regex("""<button\b[^>]*\sdata-choose\s*=\s*['"]?([^'"\s>]*)[^>]*>""", opts)

    /**
     * What is wrong with the page `html` at `path`, each naming the rule and the guide; empty when it is fine.
     * `options` are the ids of the options the propose result declares (engine/README.md, "Proposal"): each has
     * a `data-option-id` block and a `<button disabled data-choose>` placeholder in the page, and the page names
     * no other. The page's button stays inert (the frame is sandboxed); the desktop draws the live one.
     */
    fun problems(path: String, html: String, options: List<String> = emptyList()): List<String> {
        val text = COMMENTS.replace(html, " ")
        val blocks = OPTION_ID.findAll(text).map { it.groupValues[1] }.toSet()
        val buttons = CHOOSE.findAll(text).map { it.groupValues[1] to it.value }.toList()
        return FORBIDDEN.filter { (rule, _) -> rule.containsMatchIn(text) }.map { (_, what) -> "$path: $what ($GUIDE)" } +
            REQUIRED.filterNot { (rule, _) -> rule.containsMatchIn(text) }.map { (_, what) -> "$path: $what ($GUIDE)" } +
            options.filterNot { it in blocks }.map { "$path: option '$it' has no data-option-id=\"$it\" block ($GUIDE)" } +
            options.filterNot { id -> buttons.any { it.first == id } }
                .map { "$path: option '$it' has no <button disabled data-choose=\"$it\">Choose this one</button> ($GUIDE)" } +
            buttons.filter { (id, _) -> id in options }.filterNot { (_, tag) -> DISABLED.containsMatchIn(tag) }.map { it.first }.distinct()
                .map { "$path: the choose button of option '$it' is not disabled: the page's button is a placeholder ($GUIDE)" } +
            (blocks + buttons.map { it.first }).filterNot { it in options }.sorted()
                .map { "$path: option '$it' is in the page but not among the result's options ($GUIDE)" }
    }

    private val DISABLED = Regex("""\sdisabled\b""", opts)
}
