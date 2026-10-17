package com.codefrak.engine.hitlp

import java.time.LocalDate

/**
 * The judge rubric's teach line (engine/README.md, "Teaching the judge"): what `POST /judge/teach` appends under the
 * rubric's `## Decisions` heading for a judge escalation the human decided. Pure: no GitHub, no clock but [date].
 */
object RubricTeacher {
    const val HEADING = "## Decisions"

    /** A rubric without the [HEADING] it must be taught under: an error, never a heading the engine adds. */
    class NoDecisions(message: String) : IllegalArgumentException(message)

    /**
     * What the judge said on an escalation, as `POST /judge/teach` and `GET /jev/ssh-verdicts` read it from the task's
     * request ([of]): the Jev [callId], its [verdict] (null when it gave none), its [confidence] when it had one.
     */
    data class Judge(val callId: String, val verdict: String?, val confidence: Any?, val by: Any? = null) {
        companion object {
            /**
             * The judge of a judge-escalation [request] (as the HITLP server keeps it): `context.data.judge` names the
             * call; the verdict and confidence come from `asserted.recommendation` ([recommendation] first, the record's
             * own when the server answers one), falling back to `context.data.judge` ({verdict | noAnswer, confidence}),
             * the shape a request filed before the engine filed its own escalations carries. Null without `context.data.judge`.
             */
            fun of(request: Map<*, *>, recommendation: Map<*, *>? = null): Judge? {
                val data = judgeData(request) ?: return null
                val rec = recommendation ?: ((request["asserted"] as? Map<*, *>)?.get("recommendation") as? Map<*, *>)
                val callId = data["callId"]?.toString() ?: ""
                val verdict = rec?.get("verdict")?.toString()?.takeIf { it != "none" }
                if (verdict != null) return Judge(callId, verdict, rec?.get("confidence"), rec?.get("by"))
                return Judge(callId, if (data["noAnswer"] == true) null else data["verdict"]?.toString(), data["confidence"])
            }

            /** A request's `context.data.judge`, or null. */
            fun judgeData(request: Map<*, *>?): Map<*, *>? =
                ((request?.get("context") as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("judge") as? Map<*, *>
        }
    }

    /**
     * `- YYYY-MM-DD allow|deny `<command>` on <host> — <why>; judge: <verdict> (<confidence>), call <callId>[; note]`.
     * The judge's verdict is `no answer` when it gave none; the confidence is left out when it has none.
     */
    fun line(date: LocalDate, decision: String, command: String, host: String, why: String, judge: Judge, note: String?): String {
        val verdict = judge.verdict?.let(::oneLine) ?: "no answer"
        val confidence = (judge.confidence as? Number)?.let { " ($it)" } ?: ""
        val tail = note?.trim()?.takeIf { it.isNotEmpty() }?.let { "; ${oneLine(it)}" } ?: ""
        return "- $date $decision `${oneLine(command)}` on ${oneLine(host)} — ${oneLine(why)}; judge: $verdict$confidence, call ${oneLine(judge.callId)}$tail"
    }

    /**
     * [rubric] with [line] as the last item of its `## Decisions` section (before the next `## ` heading or at the
     * end), blank lines after the section's last item kept where they were. Throws [NoDecisions] without the heading.
     */
    fun append(rubric: String, line: String, path: String): String {
        val lines = rubric.split("\n").toMutableList()
        val start = lines.indexOfFirst { it.trim() == HEADING }
        if (start < 0) throw NoDecisions("$path has no `$HEADING` heading to teach under")
        var end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") || lines[it].startsWith("# ") } ?: lines.size
        while (end > start + 1 && lines[end - 1].isBlank()) end--
        lines.add(end, line)
        val text = lines.joinToString("\n")
        return if (text.endsWith("\n")) text else "$text\n"
    }

    /** [s] on one line: every run of whitespace holding a line break (`\n` or `\r`) becomes one space. */
    fun oneLine(s: String) = s.replace(Regex("\\s*[\\r\\n]\\s*"), " ").trim()

    /** Whether [path] is a plain relative path to a Markdown file: no empty, `.` or `..` segment, ending in `.md`. */
    fun plainMarkdownPath(path: String) = path.endsWith(".md") && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}
