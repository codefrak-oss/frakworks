package com.codefrak.engine.feature

import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.PassAlong
import tools.jackson.module.kotlin.readValue

/**
 * What a split feature's live parent Smith writes when the last open child closed (smith/run-smith.sh, parent mode,
 * child-work mode `live`): a replan's `continue`, `revise` or `done` under [replanRules], or `ended`, the session
 * ended before the children did (its turn budget, or a run that failed: `ReadResultDelegate` reads a failed, reaped
 * or result-less parent run as `ended` too). An `ended` result says why in [summary] and carries no [children].
 */
data class ParentResult(val outcome: String, val children: List<ChildDraft> = emptyList(), val steer: List<Steer> = emptyList(), @PassAlong val summary: String? = null) {
    fun problems(filed: List<FiledChild>, drafts: List<ChildDraft> = emptyList()): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        if (outcome == ENDED) {
            if (summary.isNullOrBlank()) add("outcome 'ended' needs a summary saying why the session ended")
            if (children.isNotEmpty()) add("outcome 'ended' carries no children: revise them, or end and let the next parent run decide")
        }
        addAll(replanRules(outcome, children, steer, filed, drafts))
    }

    companion object {
        const val ENDED = "ended"
        val OUTCOMES = ReplanResult.OUTCOMES + ENDED
        fun parse(bytes: ByteArray): ParentResult = engineJson.readValue(bytes)
    }
}
