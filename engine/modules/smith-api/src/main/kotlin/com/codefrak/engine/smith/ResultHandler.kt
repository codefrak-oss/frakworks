package com.codefrak.engine.smith

import org.operaton.bpm.engine.delegate.DelegateExecution

/**
 * The result rules of a flow's own Smith modes (engine/README.md, "Engine modules"): `read result`
 * ([ReadResultDelegate]) checks the smith-owned modes itself (work, reproduce, verify, review, security, job) and
 * hands every other mode's `result.json`, already checked against its schema, to the one handler bean whose [modes]
 * hold it. Each flow package registers its own (refine, design, propose, research, doctor, fix and diagnose), so the
 * smith layer imports none of them.
 */
interface ResultHandler {
    /** The modes this handler reads; two handlers claiming one mode is a startup error. */
    val modes: Set<String>

    /**
     * Checks [bytes] by [mode]'s rules, through [enforce] (a broken rule is the run's incident), sets the variables the
     * flow reads after the run, and answers the run's `outcome`.
     */
    fun handle(execution: DelegateExecution, mode: String, bytes: ByteArray, enforce: (List<String>) -> Unit): String

    /** For a mode in [INCIDENT_MODES]: why its result cannot be used ([enforce]'s checks), empty when it can. */
    fun problems(mode: String, bytes: ByteArray): List<String> = emptyList()
}

/** Told of every work result `read result` accepted, after its variables are set (the ci triage's Jev outcome). */
fun interface WorkResultListener {
    fun accepted(execution: DelegateExecution, work: WorkResult)
}

/** The modes [ReadResultDelegate] reads itself; no [ResultHandler] may claim one. */
val SMITH_MODES = setOf("work", "reproduce", "verify", "review", "security", "job")

/** [handlers] by mode: refused when two claim one mode, or one claims a smith-owned mode ([SMITH_MODES]). */
fun resultHandlersByMode(handlers: List<ResultHandler>): Map<String, ResultHandler> {
    val claims = handlers.flatMap { h -> h.modes.map { it to h } }
    val duplicate = claims.groupBy({ it.first }, { it.second::class.simpleName }).filterValues { it.size > 1 }
    require(duplicate.isEmpty()) { "result handlers claim one mode twice: $duplicate" }
    val smithOwned = claims.map { it.first }.filter { it in SMITH_MODES }
    require(smithOwned.isEmpty()) { "result handlers claim modes read result reads itself: $smithOwned" }
    return claims.toMap()
}
