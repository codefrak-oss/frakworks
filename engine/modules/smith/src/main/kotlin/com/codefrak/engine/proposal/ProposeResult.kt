package com.codefrak.engine.proposal

import com.codefrak.engine.engineJson
import com.codefrak.engine.feature.PlanQuestion
import com.codefrak.engine.github.BundleChanges
import com.codefrak.engine.smith.PassAlong
import tools.jackson.module.kotlin.readValue

/** An option the pitch lays out, which the user can pick with "Choose this one": its id (`a`) and a short label. */
data class PitchOption(val id: String, val label: String)

/**
 * What a propose Smith writes (smith/run-smith.sh, propose mode; engine/README.md, "Proposal"):
 * `drafted`, with the doc it created or rewrote ([path]) and its pitch page ([pitch], the same slug as
 * `.html`, by smith/pitch-guide.md) committed on its branch, and the [questions] it assumed an answer to,
 * in the design mode's shape; or `ask` when it cannot draft without guessing.
 */
data class ProposeResult(
    val outcome: String,
    val path: String? = null,
    val pitch: String? = null,
    @PassAlong val summary: String? = null,
    val questions: List<PlanQuestion> = emptyList(),
    @PassAlong val question: String? = null,
    val options: List<PitchOption> = emptyList(),
) {
    /** The shape: a `drafted` names a doc under docs/proposals/, its pitch beside it, and says what it is; an `ask` asks. */
    fun problems(): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        if (outcome == "drafted") {
            if (path.isNullOrBlank()) add("outcome 'drafted' needs the doc's path")
            else if (!DOC.matches(path)) add("path '$path' is not a doc in $DIR: $DIR<slug>.md")
            if (pitch.isNullOrBlank()) add("outcome 'drafted' needs the pitch's path: $DIR<slug>.html, by ${PitchRules.GUIDE}")
            else if (!PITCH.matches(pitch)) add("pitch '$pitch' is not a page in $DIR: $DIR<slug>.html")
            else if (path != null && DOC.matches(path) && pitch != pitchOf(path)) add("pitch '$pitch' is not the doc's: ${pitchOf(path)}")
            if (summary.isNullOrBlank()) add("outcome 'drafted' needs a summary")
            addAll(PlanQuestion.problems(questions))
            options.filterNot { OPTION.matches(it.id) }.forEach { add("option id '${it.id}' is not one letter a-z") }
            options.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { add("option id '$it' is given more than once") }
            options.filter { it.label.isBlank() }.forEach { add("option '${it.id}' needs a label") }
        }
        if (outcome == "ask" && question.isNullOrBlank()) add("outcome 'ask' needs a question")
    }

    /**
     * The rules on what a `drafted` committed, `changes` being its bundle's (null: it left no bundle): the
     * bundle touches docs/proposals/ only, changes [path] and [pitch] and keeps both, on a revisit (the doc is
     * on the default branch) the new doc keeps every line of the old one's decision log, and the pitch passes
     * [PitchRules].
     */
    fun changeProblems(changes: BundleChanges?): List<String> = buildList {
        if (outcome != "drafted") return@buildList
        if (changes == null) { add("outcome 'drafted' needs commits: the run left no bundle"); return@buildList }
        changes.paths.filterNot { it.startsWith(DIR) }.takeIf { it.isNotEmpty() }
            ?.let { add("the bundle touches paths outside $DIR: ${it.joinToString(", ")}") }
        if (path !in changes.paths) add("the bundle does not change $path")
        val after = changes.after
        if (after == null) add("the bundle leaves no $path")
        val before = changes.before
        if (before != null && after != null) {
            val kept = decisionLog(after).toSet()
            decisionLog(before).filterNot { it in kept }.takeIf { it.isNotEmpty() }
                ?.let { add("the new $path drops decision-log lines; keep every one verbatim: ${it.joinToString("; ")}") }
        }
        if (pitch != null) {
            if (pitch !in changes.paths) add("the bundle does not change $pitch")
            val pitchText = changes.pitch
            if (pitchText == null) add("the bundle leaves no $pitch")
            else addAll(PitchRules.problems(pitch, pitchText, options.map { it.id }))
        }
    }

    /** The questions put to the user before review, as confirm mode does a design's. */
    fun important(): List<PlanQuestion> = questions.filter { it.important }

    /** The important questions as one question for the user, or null when none is important. */
    fun userQuestion(): String? = PlanQuestion.userQuestion(important(), "proposal") { things ->
        "The draft is ready, but the propose Smith is not sure enough to assume $things. Answer each; the doc is redrafted from the answers."
    }

    companion object {
        val OUTCOMES = setOf("drafted", "ask")
        /** An option's id: one lowercase letter, stable across redrafts (a, b, c…). */
        val OPTION = Regex("""^[a-z]$""")
        const val DIR = "docs/proposals/"
        /** One doc directly in [DIR]: no subdirectory, so no `..` either. */
        private val DOC = Regex("""^docs/proposals/[^/]+\.md$""")
        /** The pitch page, likewise directly in [DIR]. */
        private val PITCH = Regex("""^docs/proposals/[^/]+\.html$""")
        val LOG_HEADER = Regex("""^\s*Decision log:\s*$""", RegexOption.IGNORE_CASE)
        val LIST_ITEM = Regex("""^\s*[-*+]\s+\S""")

        fun parse(bytes: ByteArray): ProposeResult = engineJson.readValue(bytes)

        /** The pitch page for the doc at `path`: the same slug, `.html`. */
        fun pitchOf(path: String): String = path.removeSuffix(".md") + ".html"

        /** A doc's decision log: the list items right under its `Decision log:` line, each trimmed; empty when it has none. */
        fun decisionLog(doc: String): List<String> =
            doc.lineSequence().dropWhile { !LOG_HEADER.matches(it) }.drop(1)
                .takeWhile { LIST_ITEM.containsMatchIn(it) }.map { it.trim() }.toList()
    }
}
