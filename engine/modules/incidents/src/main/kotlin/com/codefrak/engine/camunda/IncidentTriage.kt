package com.codefrak.engine.camunda

import com.codefrak.engine.jev.JevQuestion

/**
 * Jev's pre-triage of an incident the user pressed Fix on (engine/README.md, "Fix and Propose a fix"),
 * use case `incident-triage`: a `choice` over the incident's activity and message of `retry`, `unfixable`
 * or `investigate`. Only `retry` at the floor or above changes anything: the incident is retried at once,
 * with no Smith run. Every other reading starts the Fix Smith, as before Jev.
 */
object IncidentTriage {
    const val USE_CASE = "incident-triage"
    const val QUESTION = "remedy"
    const val RETRY = "retry"; const val UNFIXABLE = "unfixable"; const val INVESTIGATE = "investigate"

    val REMEDIES_BY_NAME = mapOf(
        RETRY to "A passing failure a plain retry gets past: an overloaded or rate-limited API (429, 529), no usable " +
            "account for the moment, a lost wake, a timeout, a network or Docker hiccup",
        UNFIXABLE to "A retry would fail the same way: the ticket, the repo or the flow's own data is wrong, and the " +
            "engine cannot put it right by trying again",
        INVESTIGATE to "Not clear from the activity and the message alone: someone has to read the logs and the code",
    )
    val REMEDIES = JevQuestion.choice(
        "The state is an incident in a process engine that runs coding agents on tickets: the step (`activityId`, " +
            "`activityName`) of the flow (`processDefinition`, under `rootFlow`) that stopped, and the error `message` " +
            "it stopped with. What should be done about it?",
        REMEDIES_BY_NAME,
    )

    /** What Fix does with Jev's reading: retry at once (no Smith), or start the Fix Smith; and the call's action. */
    data class Triage(val retry: Boolean, val action: String)

    /** The rule on Jev's `pick` (a remedy, or null for no answer) and its `confidence`. */
    fun read(pick: String?, confidence: Double?, floor: Double): Triage = when {
        pick == null || confidence == null -> Triage(false, "no answer: Fix Smith")
        pick != RETRY -> Triage(false, "$pick: Fix Smith")
        confidence < floor -> Triage(false, "$pick below floor $floor: Fix Smith")
        else -> Triage(true, "$pick: retried without a Smith")
    }

    /** The Fix's diagnosis when Jev's retry stood in for the Smith. */
    fun diagnosis(confidence: Double?, floor: Double?) =
        "Jev (TypeSafe's decision model) read the incident as retryable (confidence ${confidence?.let { "%.2f".format(it) } ?: "?"}" +
            "${floor?.let { ", floor $it" }.orEmpty()}), so it was retried without a Smith run."
}
