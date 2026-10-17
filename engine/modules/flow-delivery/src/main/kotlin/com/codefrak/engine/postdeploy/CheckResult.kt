package com.codefrak.engine.postdeploy

import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.PassAlong
import tools.jackson.module.kotlin.readValue
import java.time.Instant

/**
 * What a check Smith writes (smith/schemas/check.schema.json; bots/smith.md, "The check Smith"): `passed`, `failed`
 * with the [bug] to file, or `not_yet` with an optional [wake] hint, when to look again: `at`, an ISO-8601 instant,
 * or `event`, `<type>[:<field>=<value>]` over the engine's bus frames (engine/docs/events.md), one of the two.
 */
data class CheckResult(val outcome: String, @PassAlong val summary: String? = null, val bug: CheckBug? = null, val wake: WakeHint? = null) {
    fun problems(): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        if (summary.isNullOrBlank()) add("a check result needs a summary")
        if (outcome == FAILED && bug == null) add("outcome '$FAILED' needs a bug {title, body}")
        if (outcome != FAILED && bug != null) add("only outcome '$FAILED' files a bug")
        bug?.let { if (it.title.isBlank() || it.body.isBlank()) add("the bug needs a title and a body") }
        if (outcome != NOT_YET && wake != null) add("only outcome '$NOT_YET' takes a wake hint")
        wake?.let { addAll(it.problems()) }
    }

    /** [wake]'s `at` as an [Instant]; null without one, or when it does not parse ([problems] names that). */
    fun wakeAt(): Instant? = wake?.at?.let { runCatching { Instant.parse(it) }.getOrNull() }

    companion object {
        const val PASSED = "passed"; const val FAILED = "failed"; const val NOT_YET = "not_yet"
        val OUTCOMES = setOf(PASSED, FAILED, NOT_YET)
        fun parse(bytes: ByteArray): CheckResult = engineJson.readValue(bytes)
    }
}

/** The bug a `failed` check files (FileCheckBugDelegate): its title and body as the Smith wrote them. */
data class CheckBug(val title: String = "", @PassAlong val body: String = "")

/** A `not_yet` run's hint when to look again: exactly one of [at] (an ISO-8601 instant) and [event]. */
data class WakeHint(val at: String? = null, val event: String? = null) {
    fun problems(): List<String> = buildList {
        if ((at == null) == (event == null)) add("a wake hint is {\"at\": instant} or {\"event\": \"<type>[:<field>=<value>]\"}, one of the two")
        at?.let { if (runCatching { Instant.parse(it) }.isFailure) add("the wake hint's at '$it' is not an ISO-8601 instant") }
        event?.let { if (!EVENT.matches(it)) add("the wake hint's event '$it' is not <type>[:<field>=<value>]") }
    }

    companion object {
        /** `<type>` or `<type>:<field>=<value>`: a frame type, optionally one field it must carry with that value. */
        val EVENT = Regex("""^[a-z][a-z0-9-]*(:[A-Za-z][A-Za-z0-9_.-]*=[^\s=]+)?$""")
    }
}

/** One check run's answer in the flow's `checkHistory` (Vars.CHECK_HISTORY), oldest first; the brief carries the list. */
data class CheckRun(val run: String, val at: String, val outcome: String, val summary: String?, val wakeAt: String? = null, val wakeEvent: String? = null) {
    companion object {
        fun list(bytes: ByteArray?): List<CheckRun> = bytes?.let { engineJson.readValue<List<CheckRun>>(it) }.orEmpty()
        fun bytes(runs: List<CheckRun>): ByteArray = engineJson.writeValueAsBytes(runs)
    }
}
