package com.codefrak.engine.smith

import com.codefrak.engine.engineJson
import tools.jackson.module.kotlin.readValue

/** One draft a `split` work result hands back, in the design run's draft shape (the feature flow's `ChildDraft`). */
data class SplitDraft(
    val title: String,
    val body: String,
    val kind: String,
    val featureMode: String? = null,
    val dependsOn: List<Int> = emptyList(),
    val points: Int? = null,
)

/** The design run's rules for a split's drafts, registered by the feature flow so the smith layer imports none of it. */
fun interface SplitDraftRules {
    fun problems(children: List<SplitDraft>): List<String>
}

data class Addressed(val finding: String, @PassAlong val how: String = "")
data class Declined(val finding: String, @PassAlong val why: String = "")

/**
 * What a work Smith writes to `/work/out/result.json` (smith/run-smith.sh,
 * work mode). With `done` it has also left its commits at [BUNDLE_PATH].
 * `nothing-to-do` says the ticket needs no change, with the `reason`, and
 * commits nothing: the flow closes the ticket with the reason. `split`, on a child of a split
 * feature only, hands ordered `children` drafts back to the parent's design run, which files them in
 * this ticket's place (engine/README.md, "A child that splits"); it commits nothing either.
 */
data class WorkResult(
    val outcome: String,
    val title: String? = null,
    @PassAlong val summary: String? = null,
    val addressed: List<Addressed> = emptyList(),
    val declined: List<Declined> = emptyList(),
    @PassAlong val question: String? = null,
    @PassAlong val reason: String? = null,
    val children: List<SplitDraft> = emptyList(),
    /** What the merged work changed, 10-50 words ([SUMMARY_WORDS]); kept per ticket and shown once it closes by merge (engine/README.md, "Implementation summary"). */
    val implementationSummary: String? = null,
) {
    /**
     * Every rule the engine enforces before acting; empty when the result can be acted on. [parent] is the
     * split feature this ticket is a child of (its part line), null when it has none: then `split` is refused.
     */
    fun problems(findings: List<Finding>, hasBundle: Boolean, parent: Long? = null, rules: SplitDraftRules? = null): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        if (outcome == "done") {
            if (title.isNullOrBlank()) add("outcome 'done' needs a title")
            if (summary.isNullOrBlank()) add("outcome 'done' needs a summary")
            if (!hasBundle && !ciNotMine(findings)) add("outcome 'done' left no commits ($BUNDLE_PATH); with only engine-check findings " +
                "open, decline every one (the failure is not from this change) to have the check re-run instead")
            val answered = addressed.map { it.finding } + declined.map { it.finding }
            findings.map { it.id }.filter { it !in answered }.let { if (it.isNotEmpty()) add("findings not addressed or declined: $it") }
        }
        if (outcome == NOTHING_TO_DO) {
            if (reason.isNullOrBlank()) add("outcome '$NOTHING_TO_DO' needs a reason")
            if (hasBundle) add("outcome '$NOTHING_TO_DO' left commits ($BUNDLE_PATH); say 'done' to have them reviewed")
            if (findings.isNotEmpty()) add("outcome '$NOTHING_TO_DO' with findings open; answer them with 'done'")
        }
        if (outcome == SPLIT) {
            if (parent == null) add("outcome '$SPLIT' is only for a child of a split feature; this ticket has no parent, so ask the user instead")
            addAll(rules?.problems(children) ?: listOf("outcome '$SPLIT' cannot be checked: no split rules are registered"))
            if (summary.isNullOrBlank()) add("outcome '$SPLIT' needs a summary: why this ticket splits")
            if (hasBundle) add("outcome '$SPLIT' left commits ($BUNDLE_PATH); a split commits nothing")
            if (findings.isNotEmpty()) add("outcome '$SPLIT' with findings open; answer them with 'done'")
        }
        if (outcome != SPLIT && children.isNotEmpty()) add("children are only for outcome '$SPLIT'")
        if (outcome == "ask" && question.isNullOrBlank()) add("outcome 'ask' needs a question")
        if (implementationSummary != null) {
            if (outcome != "done") add("implementationSummary is only for outcome 'done'")
            val words = implementationSummary.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            if (words !in SUMMARY_WORDS) add("implementationSummary has $words words; write ${SUMMARY_WORDS.first}-${SUMMARY_WORDS.last}, what the diff changed")
        }
    }

    /**
     * A round that changes nothing because the engine check's failure is not from this change (a flaky or
     * unrelated test): `done`, the round's findings all from the check (`review` `ci`), every one declined
     * with its why, none addressed. The flow re-runs the check on the same commits instead of pushing
     * (engine/README.md, "Chore", "A ci finding the work declines").
     */
    fun ciNotMine(findings: List<Finding>): Boolean = outcome == "done" && findings.isNotEmpty() &&
        findings.all { it.review == "ci" } && addressed.isEmpty() &&
        findings.all { f -> declined.any { it.finding == f.id && it.why.isNotBlank() } }

    companion object {
        const val NOTHING_TO_DO = "nothing-to-do"
        const val SPLIT = "split"
        val OUTCOMES = setOf("done", NOTHING_TO_DO, "ask", SPLIT)
        val SUMMARY_WORDS = 10..50
        fun parse(bytes: ByteArray): WorkResult = engineJson.readValue(bytes)
    }
}

/**
 * What a bug's reproduce Smith writes (smith/run-smith.sh, reproduce mode),
 * from the default branch, committing nothing. Two outcomes go on to a fix:
 * `reproduced`, with a `repro` (a command, or exact steps) that shows the bug
 * now, and `understood`: not reproduced here (a phone, a car, a race), but the
 * report, the logs and the code agree on a cause, with the `evidence`. Both
 * name the `rootCause` and say whether a regression test can catch it
 * (`testable`, and why not). `not-reproduced` is neither: tried, and no cause.
 */
data class ReproduceResult(
    val outcome: String,
    @PassAlong val rootCause: String? = null,
    @PassAlong val repro: String? = null,
    @PassAlong val evidence: String? = null,
    val testable: Boolean? = null,
    @PassAlong val untestableWhy: String? = null,
    @PassAlong val summary: String? = null,
    @PassAlong val question: String? = null,
    /** Set by the engine, not the Smith: why /work/out/repro was not carried to the later runs (past its cap). */
    @PassAlong val filesDropped: String? = null,
) {
    fun problems(): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        if (outcome in FIXABLE) {
            if (rootCause.isNullOrBlank()) add("outcome '$outcome' needs a rootCause")
            if (testable == null) add("outcome '$outcome' needs testable: true or false")
            if (testable == false && untestableWhy.isNullOrBlank()) add("testable false needs an untestableWhy")
        }
        if (outcome == "reproduced" && repro.isNullOrBlank()) add("outcome 'reproduced' needs a repro")
        if (outcome == "understood" && evidence.isNullOrBlank()) add("outcome 'understood' needs the evidence for its root cause")
        if (outcome == "not-reproduced" && summary.isNullOrBlank()) add("outcome 'not-reproduced' needs a summary of what was tried")
        if (outcome == "ask" && question.isNullOrBlank()) add("outcome 'ask' needs a question")
    }

    companion object {
        val OUTCOMES = setOf("reproduced", "understood", "not-reproduced", "ask")
        /** The outcomes that go on to a fix. */
        val FIXABLE = setOf("reproduced", "understood")
        fun parse(bytes: ByteArray): ReproduceResult = engineJson.readValue(bytes)
        /** The Smith's result.json with the engine's `filesDropped` added, the rest as written. */
        fun withFilesDropped(bytes: ByteArray, why: String): ByteArray =
            engineJson.writeValueAsBytes(engineJson.readValue<Map<String, Any?>>(bytes) + ("filesDropped" to why))
    }
}

/**
 * What a bug's verify Smith writes after the merge: `fixed` (the repro no
 * longer shows it), `still-broken`, or `unverified` (nothing it can run here
 * would show it, e.g. an `understood` bug on a phone): the ticket closes
 * saying so.
 */
/**
 * What a job Smith writes (smith/schemas/job.schema.json; engine/README.md, "Custom jobs and the SDK"): `done` or
 * `failed`, and a summary the calling process reads as `CallableSmith.RESULT` with the whole JSON.
 */
data class JobResult(val outcome: String, @PassAlong val summary: String? = null) {
    fun problems(): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        if (summary.isNullOrBlank()) add("a job result needs a summary")
    }

    companion object {
        val OUTCOMES = setOf("done", "failed")
        fun parse(bytes: ByteArray): JobResult = engineJson.readValue(bytes)
    }
}

data class VerifyResult(val outcome: String, @PassAlong val summary: String? = null) {
    fun problems(): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        if (summary.isNullOrBlank()) add("a verify result needs a summary")
    }

    companion object {
        val OUTCOMES = setOf("fixed", "still-broken", "unverified")
        fun parse(bytes: ByteArray): VerifyResult = engineJson.readValue(bytes)
    }
}

/**
 * What a reviewing Smith writes (review or security mode): approve, or send
 * it back with findings. `review` passes with `approved`, `security` with `passed`.
 */
data class ReviewResult(val outcome: String, @PassAlong val summary: String? = null, val findings: List<Finding> = emptyList()) {
    fun problems(mode: String): List<String> = buildList {
        val (pass, fail) = OUTCOMES.getValue(mode)
        if (outcome != pass && outcome != fail) add("outcome '$outcome' is not $pass or $fail")
        if (outcome == fail && findings.isEmpty()) add("outcome '$fail' needs findings")
        if (outcome == pass && findings.isNotEmpty()) add("outcome '$pass' has findings; send it back instead")
        findings.filter { it.ask.isBlank() }.let { if (it.isNotEmpty()) add("${it.size} findings have no ask") }
    }

    /** The findings with the ids and review name the engine gives them, e.g. `security-2-1` (the 2nd review's 1st). */
    fun stamped(mode: String, review: Int): List<Finding> =
        findings.mapIndexed { i, f -> f.copy(id = "$mode-$review-${i + 1}", review = if (mode == "review") "correctness" else mode) }

    companion object {
        /** mode to (pass, send back) */
        val OUTCOMES = mapOf("review" to ("approved" to "changes"), "security" to ("passed" to "findings"))
        fun parse(bytes: ByteArray): ReviewResult = engineJson.readValue(bytes)
    }
}
