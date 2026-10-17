package com.codefrak.engine.smith

/**
 * A ticket's `## Allowed scope` section (its heading to the next heading), read for the two things the engine
 * refuses in a ticket before it is filed: a grant of push to main ([grantsPushToMain]) and a `.github/workflows/`
 * path the repo's token cannot push ([allowsWorkflows]); and for its size ([grantCount]), the scope signal of the
 * stage estimates (engine/README.md, "Stage estimates"). The section's paths are guidance for the work Smith and
 * its reviewers; the engine does not hold a work bundle to them. A line that keeps paths out ("May not touch:
 * `.github/`", "Nothing under ...", "Out of scope: ...") grants nothing, nor do the lines nested under it or
 * the bullets under such a lead-in ending in a colon; on a granting line, what follows "except", "but not",
 * "excluding" or "other than" is not read either.
 */
object AllowedScope {
    /** A heading line: where the Allowed scope section ends. */
    private val HEADING = Regex("""^\s*#{1,6}\s""")

    /** Strips a markdown list marker ("-", "*", "1.") and leading whitespace from a line. */
    private val LIST_MARKER = Regex("""^\s*(?:[-*]|\d+\.)\s*""")

    /** A bulleted line: the marker must be followed by whitespace, so `**bold**` is not one. */
    private val BULLET = Regex("""^\s*(?:[-*+]|\d+[.)])\s+""")

    /** The section's lines after its heading, up to the next heading; empty when the body has none. */
    private fun section(body: String): List<String> {
        val lines = body.lines()
        val start = lines.indexOfFirst { HEADING.containsMatchIn(it) && it.contains("allowed scope", ignoreCase = true) }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { !HEADING.containsMatchIn(it) }
    }

    /** Emphasis and code marks, which a lead-in may carry ("**May not touch:**"). */
    private val MARKS = Regex("[`*_]")

    /**
     * A lead-in that keeps paths out rather than granting them, at the start of a line once its list marker
     * and marks are gone: "May not touch", "Must not", "Do not", "Don't", "Never", "Not", "No", "Nothing
     * under", "Out of scope", "Off limits", "Excluded", "Except", "Forbidden", "Hands off". A word of its
     * own, so the path `no/x.md` is not one.
     */
    private val EXCLUDES = Regex(
        """(?i)^(?:(?:may|must|do|does|should|shall|can|will)\s*(?:not|n['’]t)|cannot|never|not|no|nothing|""" +
            """out[\s-]+of[\s-]+scope|off[\s-]+limits|exclud(?:e|es|ed|ing)|except|forbidden|hands\s+off)(?=[\s:,]|$)"""
    )

    /** Where a granting line turns to what it keeps out: "`bots/` except `bots/smith.md`". A word of its own, never part of a path. */
    private val EXCEPT = Regex("""(?i)(?<![\w/.\-])(?:except|excluding|but\s+not|other\s+than|not\s+including)(?![\w/.\-])""")

    private fun indent(line: String) = line.length - line.trimStart().length

    /**
     * Each of [lines] (a section's) as the text that grants, null for a line that keeps paths out: one that
     * opens with an [EXCLUDES] lead-in, every line nested deeper under it, and, when the lead-in is not a
     * bullet and ends in a colon ("May not touch:"), the bullets that follow it at its own depth too. A
     * granting line is cut at its first [EXCEPT].
     */
    private fun granting(lines: List<String>): List<String?> {
        var under: Int? = null      // the indent of the excluding line the next lines are read against
        var heads = false           // that line is a non-bullet lead-in ending in a colon: its bullets are kept out
        return lines.map { line ->
            if (line.isBlank()) return@map line
            val bullet = BULLET.containsMatchIn(line)
            val depth = indent(line)
            val held = under?.let { depth > it || (heads && bullet && depth == it) } == true
            if (held) return@map null
            val text = line.replaceFirst(BULLET, "").replace(MARKS, "").trim()
            if (EXCLUDES.containsMatchIn(text)) {
                under = depth
                heads = !bullet && text.endsWith(":")
                return@map null
            }
            under = null
            EXCEPT.find(line)?.let { line.substring(0, it.range.first) } ?: line
        }
    }

    /** An affirmative Allowed-scope lead-in: "May push to main", "Push to main" or "Pushes to main", followed
     *  by the colon. Anchored to the start of the (delisted, unemphasized) line so a negated lead-in such as
     *  "May not push to main" never matches. */
    private val PUSH_TO_MAIN_LEAD = Regex("""(?i)^(?:may\s+push\s+to|push\s+to|pushes\s+to)\s+main\s*:\s*(.*)$""")

    /** The truthy word, matched as a whole word anywhere in the tail so a qualifier after it
     *  ("yes, with review") still counts as a grant. */
    private val PUSH_TO_MAIN_TRUTHY = Regex("""(?i)\byes\b""")

    /** True when [body] has an Allowed-scope line that affirmatively grants push-to-main access. */
    fun grantsPushToMain(body: String): Boolean = body.lineSequence().any { rawLine ->
        val unemphasized = rawLine.replace("`", "").replace("*", "")
        val delisted = unemphasized.replaceFirst(LIST_MARKER, "").trim()
        val tail = PUSH_TO_MAIN_LEAD.find(delisted)?.groupValues?.get(1) ?: return@any false
        PUSH_TO_MAIN_TRUTHY.containsMatchIn(tail)
    }

    /** A path under `.github/workflows/`, or a glob over all of `.github/` that takes it in. */
    private val WORKFLOWS_PATH = Regex("""\.github/(?:workflows\b|\*\*)""")

    /**
     * True when [body]'s Allowed scope section (its heading to the next heading) grants a `.github/workflows/`
     * path. A line that keeps one out ("May not touch: `.github/`") is no grant ([granting]).
     */
    fun allowsWorkflows(body: String): Boolean =
        granting(section(body)).filterNotNull().any { WORKFLOWS_PATH.containsMatchIn(it.replace("`", "").replace("\\", "")) }

    /**
     * A path or glob as a ticket writes one: a word with a `/` in it (`engine/src/`, `bots/`, a glob), or a file name with
     * an extension (`README.md`). Not part of a longer word, so a URL's or a sentence's pieces are not read twice.
     */
    private val PATH = Regex("""(?<![\w/.*\-])(?:[\w.*\-{}]+/[\w.*\-/{}]*|[\w\-]{2,}\.[A-Za-z0-9]{1,6})(?![\w/])""")

    /**
     * How many distinct paths and globs [body]'s Allowed scope grants: the path-like words on its granting lines
     * ([granting]: not a `May not touch:` line, nor what follows "except"); 0 when it has no such section. The
     * stage estimates' scope signal (engine/README.md, "Stage estimates").
     */
    fun grantCount(body: String): Int =
        granting(section(body)).filterNotNull().flatMap { line -> PATH.findAll(line.replace("`", "")).map { it.value.trimEnd('.') } }
            .toSet().size
}
