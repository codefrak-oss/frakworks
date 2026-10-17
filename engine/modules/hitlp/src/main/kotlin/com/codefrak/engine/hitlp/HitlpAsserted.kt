package com.codefrak.engine.hitlp

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The `asserted` block and `requiresHuman` the engine sends as a HITLP 0.2 requester (#2447; `spec/hitlp.md` §5.1 to
 * §5.4) for a flow wait: what the engine says about the request, evidence the server shows the person and keeps in the
 * record, never authority. Part 2 of the plan in #2443 builds every flow wait's request with [envelope] and files it
 * through [HitlpRequester]. Never `requester` (0.1's field, gone) and never `attested` (the server's, R8):
 * [HitlpSchemas.requesterProblem] refuses both, and every built request passes it.
 *
 * - `why`: one sentence per [Kind], naming what the flow waits for.
 * - `onBehalfOf`: `{kind: run, id: <runId>, ticket: <owner/name#n>}` for a Smith's [Kind.QUESTION] (the run asked it);
 *   `{kind: person, id: <login>}` for every other kind, the login that asked for or submitted the work
 *   ([Wait.askedBy]). The schema requires neither member; the engine always sends `id`.
 * - `recommendation`: `{verdict: none}`. The engine has no merit judgment of its own on a wait (the judge's verdicts
 *   are the ops server's, not these).
 * - `lineage`: `[{kind: ticket, claim: frakworks://ticket/<owner>/<name>/<n>}, {kind: run, claim: <run link>}]`, outermost last as the ticket
 *   prescribes it; the run link is left out when the wait has no run.
 * - `asOf`: when the block was built.
 * - `requiresHuman`: `true` on a decision, an ack, a legacy acceptance and a merge acceptance (the engine's own
 *   rubric already said a person is needed, §5.4); `false` on a Smith's question, which the proxy may answer.
 */
object HitlpAsserted {
    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** The kinds of flow wait part 2 files, each with its `why` and whether a person is required. */
    enum class Kind(val why: String, val requiresHuman: Boolean) {
        /** A Smith's question to the user (`ask`), asked by its run. */
        QUESTION("A Smith run waits for the user's answer to its question before it can go on with its ticket.", false),
        /** The user's decision on a proposal, a design or a research result. */
        DECISION("The flow waits for the user's decision on what a run proposed.", true),
        /** The user's acknowledgement of a finding or an incident. */
        ACK("The flow waits for the user to acknowledge what a run reported.", true),
        /** The legacy acceptance of a finished ticket. */
        ACCEPTANCE("The flow waits for the user to accept the ticket's result as done.", true),
        /** The acceptance that merges a pull request. */
        MERGE("The flow waits for the user to accept the pull request for merge.", true),
    }

    /**
     * One flow wait: its [kind]; the ticket ([repo] `owner/name`, [issue]) and the issue's URL; the run that reached the
     * wait ([runId], its [runLink]), null when none did; the login the work is for ([askedBy]: `askedBy` on a question,
     * `submittedBy` on an intake's decision), required for every kind but a question.
     */
    data class Wait(
        val kind: Kind,
        val repo: String,
        val issue: Long,
        val runId: String? = null,
        val runLink: String? = null,
        val askedBy: String? = null,
    )

    /** The `asserted` block of [wait], built at [now]. */
    fun asserted(wait: Wait, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): Map<String, Any?> = linkedMapOf(
        "why" to wait.kind.why,
        "onBehalfOf" to onBehalfOf(wait),
        "recommendation" to mapOf("verdict" to "none"),
        "lineage" to lineage(wait),
        "asOf" to ISO.format(now.withOffsetSameInstant(ZoneOffset.UTC)),
    )

    /** The envelope members the engine adds to a request for [wait]: `asserted` and `requiresHuman`. */
    fun envelope(wait: Wait, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): Map<String, Any?> =
        linkedMapOf("asserted" to asserted(wait, now), "requiresHuman" to wait.kind.requiresHuman)

    /** [HitlpSchemas.NEVER_SENT] dropped from [request], should a caller have copied them in from a stored request. */
    fun withoutServerMembers(request: Map<String, Any?>): Map<String, Any?> = request.filterKeys { it !in HitlpSchemas.NEVER_SENT }

    private fun onBehalfOf(wait: Wait): Map<String, Any?> = when (wait.kind) {
        Kind.QUESTION -> {
            val run = requireNotNull(wait.runId) { "a Smith's question is asked by its run: runId is required" }
            linkedMapOf("kind" to "run", "id" to run, "ticket" to "${wait.repo}#${wait.issue}")
        }
        else -> {
            val login = requireNotNull(wait.askedBy?.takeIf { it.isNotBlank() }) { "a ${wait.kind.name.lowercase()} wait is on behalf of a person: askedBy is required" }
            linkedMapOf("kind" to "person", "id" to login)
        }
    }

    private fun lineage(wait: Wait): List<Map<String, Any?>> = buildList {
        add(mapOf("kind" to "ticket", "claim" to "frakworks://ticket/${wait.repo}/${wait.issue}"))
        wait.runLink?.let { add(mapOf("kind" to "run", "claim" to it)) }
    }
}
