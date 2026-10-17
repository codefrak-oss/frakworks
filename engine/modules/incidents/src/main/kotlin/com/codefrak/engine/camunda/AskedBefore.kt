package com.codefrak.engine.camunda

import com.codefrak.engine.engineJson

/** A Smith run as [AskedBefore.askedBefore] sees it: when it ended, whether it still runs, and its question. */
data class AskedRun(val end: Long, val running: Boolean, val question: String?)

/** The preceding-run question lookup the ticket timeline and [com.codefrak.engine.board.Needs] share. */
object AskedBefore {
    /**
     * The question a user task waiting since [at] asks when `engine.intake_question` has none (a chore's or bug's
     * question): the newest ended run's before the wait began, from its result.json. The timeline's
     * `person-question` wait and [com.codefrak.engine.board.Needs] both read it here.
     */
    fun askedBefore(runs: List<AskedRun>, at: Long): String? =
        runs.filter { it.end <= at && !it.running }.maxByOrNull { it.end }?.question

    /** The question a run's result.json asked, as JSON or as the `output` variable's bytes; null when none. */
    fun questionOf(output: Any?): String? =
        (output as? ByteArray)?.let { runCatching { engineJson.readTree(it) }.getOrNull() }
            ?.get("question")?.takeIf { it.isString }?.asString()
}
