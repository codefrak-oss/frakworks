package com.codefrak.engine.feature

import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.PassAlong
import com.codefrak.engine.smith.PassAlongItems
import tools.jackson.module.kotlin.readValue

data class PlanStage(@PassAlong val title: String, @PassAlong val detail: String = "")

/** What a design Smith settled before the work: the files, the order, the tests, and how each criterion is checked. */
data class Plan(
    @PassAlong val summary: String,
    @PassAlongItems val files: List<String> = emptyList(),
    val stages: List<PlanStage> = emptyList(),
    @PassAlongItems val tests: List<String> = emptyList(),
    /** The ticket's acceptance criteria, each as one checkable sentence. */
    @PassAlongItems val acceptance: List<String> = emptyList(),
)

/**
 * What a feature's design Smith writes (smith/run-smith.sh, design mode), from
 * the default branch, committing nothing: `planned` with a [Plan] and its open
 * [questions], `split` with a [Plan] and 2-16 [children] when the plan is too
 * big for one run, or `ask` when the ticket cannot be planned without guessing.
 */
data class DesignResult(
    val outcome: String,
    val plan: Plan? = null,
    val questions: List<PlanQuestion> = emptyList(),
    @PassAlong val question: String? = null,
    val children: List<ChildDraft> = emptyList(),
) {
    fun problems(): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        if (outcome == "planned" || outcome == "split") {
            if (plan == null) add("outcome '$outcome' needs a plan")
            plan?.let {
                if (it.summary.isBlank()) add("plan.summary is blank")
                if (it.acceptance.isEmpty()) add("plan.acceptance is empty: every feature has criteria to check after the merge")
            }
            addAll(PlanQuestion.problems(questions))
        }
        if (outcome == "split") {
            if (children.size !in ChildDraft.MIN..ChildDraft.MAX) add("outcome 'split' needs ${ChildDraft.MIN}-${ChildDraft.MAX} children, not ${children.size}")
            addAll(ChildDraft.problems(children))
        }
        if (outcome == "ask" && question.isNullOrBlank()) add("outcome 'ask' needs a question")
    }

    /** The questions confirm mode puts to the user before the work starts. */
    fun important(): List<PlanQuestion> = questions.filter { it.important }

    /**
     * The important questions as one question for the user, the plan's summary
     * (its mockups included) ahead of them, prose then an `ask` block (bots/smith.md, "the ask block"), or null when none is important.
     */
    fun userQuestion(): String? = PlanQuestion.userQuestion(important(), "plan", plan?.summary) { things ->
        "The plan is ready, but this feature runs in confirm mode and the design Smith is not sure enough to assume $things. Answer each; the plan is redone from the answers."
    }

    companion object {
        val OUTCOMES = setOf("planned", "split", "ask")
        val IMPACTS = setOf("low", "medium", "high")
        /** Below this a question is put to the user in confirm mode; decided with the user 2026-09-24. */
        const val CONFIDENCE = 0.7
        fun parse(bytes: ByteArray): DesignResult = engineJson.readValue(bytes)
        fun plan(bytes: ByteArray): Plan = engineJson.readValue(bytes)
        fun planBytes(plan: Plan): ByteArray = engineJson.writeValueAsBytes(plan)
    }
}
