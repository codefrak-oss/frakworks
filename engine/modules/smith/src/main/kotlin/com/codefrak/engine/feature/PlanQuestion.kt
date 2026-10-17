package com.codefrak.engine.feature

import com.codefrak.engine.smith.PassAlong

/** One candidate answer to a [PlanQuestion]: a short [id] (the answer that comes back) and a button-length [label]. */
data class PlanOption(val id: String = "", val label: String = "")

/**
 * One thing the ticket leaves open, with what the design Smith would assume
 * without an answer and how sure it is. `confidence` is 0 to 1; `impact` is
 * what it costs if the assumption is wrong. [options], when the answer is one
 * of a few candidates, become the buttons of a `kind: choice` ask block.
 */
data class PlanQuestion(
    @PassAlong val question: String,
    @PassAlong val assumption: String? = null,
    val confidence: Double = 0.0,
    val impact: String = "medium",
    val options: List<PlanOption>? = null,
) {
    /** Important enough to stop for, in confirm mode: not sure enough, or too costly to be wrong about. */
    val important get() = confidence < DesignResult.CONFIDENCE || impact == "high"

    companion object {
        /** The rules every question list obeys: a question, an assumption, a confidence 0-1 and a known impact. */
        fun problems(questions: List<PlanQuestion>): List<String> = buildList {
            questions.forEachIndexed { i, q ->
                if (q.question.isBlank()) add("questions[$i] has no question")
                if (q.assumption.isNullOrBlank()) add("questions[$i] has no assumption: say what you would do without an answer")
                if (q.confidence !in 0.0..1.0) add("questions[$i].confidence ${q.confidence} is not 0-1")
                if (q.impact !in DesignResult.IMPACTS) add("questions[$i].impact '${q.impact}' is not one of ${DesignResult.IMPACTS}")
                q.options?.let { options ->
                    // The rules of a choice block's options (bots/smith.md, "The four kinds"); a longer label is shortened on the button.
                    if (options.size !in MIN_OPTIONS..MAX_OPTIONS) add("questions[$i].options has ${options.size} entries, not $MIN_OPTIONS-$MAX_OPTIONS")
                    options.forEachIndexed { j, o ->
                        if (o.id.isBlank()) add("questions[$i].options[$j] has no id")
                        if (o.label.isBlank()) add("questions[$i].options[$j] has no label")
                    }
                    options.map { it.id.trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                        .forEach { add("questions[$i].options has the id '$it' more than once") }
                }
            }
        }

        const val MIN_OPTIONS = 2
        const val MAX_OPTIONS = 5

        /**
         * [asked] as one question for the user, prose then an `ask` block (bots/smith.md, "the ask block"),
         * or null when it is empty. [intro] gets "one thing" or "n things"; [noun] names them in the block.
         * A single question with options is a `kind: choice` block, its options as the buttons; anything
         * else is `kind: text`, as a comment carries one block and several questions are not one menu.
         * [summary], when not blank, goes verbatim (inline SVG mockups included) after the intro under
         * "The plan:", so the user sees what they are asked to confirm.
         */
        fun userQuestion(asked: List<PlanQuestion>, noun: String, summary: String? = null, intro: (String) -> String): String? {
            if (asked.isEmpty()) return null
            return buildString {
                append(intro(if (asked.size == 1) "one thing" else "${asked.size} things")).append("\n\n")
                summary?.trim()?.takeIf { it.isNotEmpty() }?.let { append("The plan:\n\n").append(it).append("\n\n") }
                asked.forEachIndexed { i, q ->
                    append("${i + 1}. **${q.question.trim()}** (confidence ${"%.1f".format(q.confidence)}, impact ${q.impact}). ")
                    append("Without an answer it would assume: ${q.assumption!!.trim()}\n")
                }
                val options = asked.singleOrNull()?.options.orEmpty()
                append("\n```ask\nkind: ${if (options.isEmpty()) "text" else "choice"}\nquestion: ")
                append(if (asked.size == 1) oneLine(asked.single().question) else "Answer the ${asked.size} $noun questions above")
                if (options.isNotEmpty()) {
                    append("\noptions:")
                    options.forEach { append("\n  - id: ${oneLine(it.id)}\n    label: ${oneLine(it.label)}") }
                }
                append("\n```")
            }
        }

        /** A block value is one line: the ask block reads a line per key and a line per option field. */
        private fun oneLine(text: String) = text.trim().replace('\n', ' ')
    }
}
