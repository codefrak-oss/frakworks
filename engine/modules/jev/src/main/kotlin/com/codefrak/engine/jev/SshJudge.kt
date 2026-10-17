package com.codefrak.engine.jev

import com.codefrak.engine.engineSetting
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/** One `judge.command` request: the rubric and what the requester wants to run, and why. */
data class JudgeRequest(
    val rubric: String, val command: String, val why: String, val ticketGoal: String, val priorCommands: List<String>,
    val files: List<String>, val host: String, val requester: String,
)

/** One passage of the rubric a verdict may cite: a non-blank, non-heading line (bullet marker cut), and its `## ` section. */
data class RubricPassage(val section: String?, val text: String)

/**
 * The judge's answer. [noAnswer] true is fail-closed: [verdict] is then null, never `allow`, and [reason] says why;
 * otherwise [verdict] is allow, escalate or deny and [citedPassage] is a substring of the rubric.
 */
data class Judgment(
    val callId: String, val noAnswer: Boolean, val verdict: String?, val confidence: Double?, val citedPassage: String?,
    val reasoning: String, val reason: String? = null,
) {
    fun toMap(): Map<String, Any?> = if (noAnswer) mapOf("noAnswer" to true, "callId" to callId, "reason" to reason,
        "confidence" to confidence, "reasoning" to reasoning)
    else mapOf("verdict" to verdict, "confidence" to confidence, "citedPassage" to citedPassage, "reasoning" to reasoning, "callId" to callId)
}

/**
 * The SSH command judge (engine/README.md, "The judge server"), Jev use case [USE_CASE]: asks Jev two choice questions
 * over the rubric and the request, the `verdict` (allow / escalate / deny) and the `passage` it rests on, an option per
 * [RubricPassage] (Jev cannot write free text, so the cited passage is picked, not quoted). The reasoning is composed
 * here from the answers. Fails closed ([evaluate]): no answer, a confidence below the threshold (setting [THRESHOLD],
 * default [DEFAULT_THRESHOLD]) or a passage that is not a substring of the rubric is `noAnswer`, never `allow`. The
 * call's `engine.jev_call` row gets the outcome as its action and the judgment merged into its response ([JevCalls.annotate]).
 */
@Component
class SshJudge(private val decider: JevDecider, private val calls: JevCalls, private val jdbc: JdbcClient) {
    companion object {
        const val USE_CASE = "ssh-judge"
        /** `engine.engine_setting` row of the judge's threshold; no row is [DEFAULT_THRESHOLD]. */
        const val THRESHOLD = "jev.ssh-judge.threshold"
        const val DEFAULT_THRESHOLD = 0.8
        val VERDICTS = listOf("allow", "escalate", "deny")
        /** Jev's cap on a choice's options. */
        const val MAX_PASSAGES = 255
        private val BULLET = Regex("^([-*+]|\\d+[.)])\\s+")

        /** The rubric's passages, in order, at most [MAX_PASSAGES]: each text is a substring of [rubric]. */
        fun passages(rubric: String): List<RubricPassage> {
            var section: String? = null
            val out = mutableListOf<RubricPassage>()
            for (raw in rubric.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) { section = line.trimStart('#').trim(); continue }
                val text = line.replace(BULLET, "").trim()
                if (text.isNotEmpty() && out.size < MAX_PASSAGES) out += RubricPassage(section, text)
            }
            return out
        }

        /** The two questions; the passage options are `p1`..`pn`, keys of [options]. */
        fun questions(options: Map<String, RubricPassage>): Map<String, JevQuestion> = mapOf(
            "verdict" to JevQuestion.choice("Judge the SSH command in the state against the rubric (its Principles, Allow, Escalate, " +
                "Never and Decisions sections), given why it is run, the ticket's goal, the commands before it, the files and the host.",
                mapOf("allow" to "The rubric's Allow section or a Decision covers it and nothing in Never or Escalate applies",
                    "escalate" to "The rubric's Escalate section covers it, or the rubric does not clearly allow it: a human decides",
                    "deny" to "The rubric's Never section forbids it, or a Decision refused it")),
            "passage" to JevQuestion.choice("Which passage of the rubric does that verdict rest on most?",
                options.mapValues { (_, p) -> (p.section?.let { "[$it] " } ?: "") + p.text }),
        )

        /** Fail-closed reading of [answers] (null: no answer); [options] maps a passage key to its passage. */
        fun evaluate(callId: String, answers: Map<String, JevAnswer>?, options: Map<String, RubricPassage>, rubric: String, threshold: Double): Judgment {
            fun no(reason: String, confidence: Double? = null) =
                Judgment(callId, true, null, confidence, null, "No verdict ($reason): treat the command as not allowed.", reason)
            if (answers == null) return no("Jev gave no answer")
            val v = answers["verdict"] ?: return no("no verdict answer")
            val verdict = v.choice?.takeIf { it in VERDICTS } ?: return no("verdict ${v.choice} is not allow, escalate or deny")
            val vc = v.confidence ?: return no("verdict has no confidence")
            if (vc < threshold) return no("verdict $verdict at confidence $vc is below the threshold $threshold", vc)
            val p = answers["passage"] ?: return no("no passage answer", vc)
            val pc = p.confidence ?: return no("passage has no confidence", vc)
            if (pc < threshold) return no("passage at confidence $pc is below the threshold $threshold", vc)
            val passage = p.choice?.let { options[it] } ?: return no("passage ${p.choice} is not one of the rubric's", vc)
            if (!rubric.contains(passage.text)) return no("the cited passage is not in the rubric", vc)
            val reasoning = "Verdict $verdict at confidence $vc (" +
                VERDICTS.joinToString(", ") { "$it ${v.probabilities[it] ?: 0.0}" } + "), resting on " +
                (passage.section?.let { "the rubric's $it section" } ?: "the rubric") + " (passage confidence $pc): \"${passage.text}\""
            return Judgment(callId, false, verdict, vc, passage.text, reasoning)
        }
    }

    fun threshold(): Double = jdbc.engineSetting(THRESHOLD)?.toDoubleOrNull() ?: DEFAULT_THRESHOLD

    /** Asks Jev for [request] and records the call in [repo]'s `engine.jev_call` row. */
    fun judge(request: JudgeRequest, repo: String): Judgment {
        val threshold = threshold()
        val options = passages(request.rubric).mapIndexed { i, p -> "p${i + 1}" to p }.toMap()
        val state = mapOf("rubric" to request.rubric, "command" to request.command, "why" to request.why,
            "ticketGoal" to request.ticketGoal, "priorCommands" to request.priorCommands, "files" to request.files,
            "host" to request.host, "requester" to request.requester)
        val decision = decider.decide(USE_CASE, JevContext(null, repo, null), state, questions(options), threshold)
        val judgment = if (options.isEmpty()) evaluate(decision.callId, null, options, request.rubric, threshold)
            .copy(reason = "the rubric has no passages", reasoning = "No verdict (the rubric has no passages): treat the command as not allowed.")
        else evaluate(decision.callId, decision.answers, options, request.rubric, threshold)
        calls.setAction(decision.callId, judgment.verdict ?: "no-answer")
        calls.annotate(decision.callId, "judge", judgment.toMap())
        return judgment
    }
}
