package com.codefrak.engine.feature

import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.PassAlong
import tools.jackson.module.kotlin.readValue

/**
 * What a split feature's replan Smith writes after a child merged (smith/run-smith.sh,
 * replan mode): `continue` (file the next draft as drafted), `revise` (the remaining
 * drafts rewritten: [children] replaces them), or `done` (drop the rest; nothing more is
 * filed). The replan after the last child is the coherence check and returns `continue`.
 */
data class ReplanResult(val outcome: String, val children: List<ChildDraft> = emptyList(), val steer: List<Steer> = emptyList(), @PassAlong val summary: String? = null) {
    /**
     * `filed` are the children filed already (with the revised drafts they must stay within [ChildDraft.MAX]);
     * `drafts` the remaining drafts a `continue` files as they are. The drafts left to file, revised or
     * continued, must each be a real ticket ([ChildDraft.replanProblems]).
     */
    fun problems(filed: List<FiledChild>, drafts: List<ChildDraft> = emptyList()): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        addAll(replanRules(outcome, children, steer, filed, drafts))
    }

    companion object {
        val OUTCOMES = setOf("continue", "revise", "done")
        fun parse(bytes: ByteArray): ReplanResult = engineJson.readValue(bytes)
    }
}

/** A replan's course change for filed child [child], still open: [message] binds its later runs. */
data class Steer(val child: Long, val message: String)

/**
 * The rules a replan-shaped result keeps beside its outcome set (a replan's [ReplanResult], a parent's [ParentResult]):
 * a `revise` rewrites the drafts left within [ChildDraft.MAX], a `continue` files [drafts] that are real tickets, and
 * each [steer] names a filed child still open with a message.
 */
fun replanRules(outcome: String, children: List<ChildDraft>, steer: List<Steer>, filed: List<FiledChild>, drafts: List<ChildDraft>): List<String> = buildList {
    if (outcome == "revise") {
        if (children.isEmpty()) add("outcome 'revise' needs the remaining children, rewritten (or say 'done' to drop them)")
        if (filed.size + children.size > ChildDraft.MAX)
            add("a split has at most ${ChildDraft.MAX} parts over its life: ${filed.size} filed plus ${children.size} revised is ${filed.size + children.size}")
        addAll(ChildDraft.problems(children, filed.size))
        addAll(ChildDraft.replanProblems(children, filed, filed.size))
    }
    if (outcome == "continue") addAll(ChildDraft.replanProblems(drafts, filed, filed.size))
    steer.forEachIndexed { i, s ->
        val child = filed.firstOrNull { it.number == s.child }
        when {
            child == null -> add("steer[$i]: #${s.child} is not a filed child of this split")
            child.state != "open" -> add("steer[$i]: #${s.child} is ${child.state}, not open: only an open child can be steered")
        }
        if (s.message.isBlank()) add("steer[$i]: the message to #${s.child} is blank")
    }
}
