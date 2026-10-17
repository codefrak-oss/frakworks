package com.codefrak.engine.friction

import com.codefrak.engine.jev.JevAnswers
import com.codefrak.engine.jev.JevCalls
import org.springframework.stereotype.Component

/**
 * The `jev_confidence` of a friction note row (engine/README.md, "Friction notes"): the confidence of the `group`
 * answer of the note's `friction-group` call. An interface so `read_friction_notes` is tested without Spring.
 */
fun interface FrictionConfidence {
    /** The confidence by call id for every id in [callIds] whose call has a readable `group` answer; the rest are absent. */
    fun of(callIds: Collection<String>): Map<String, Double>

    companion object {
        /** Knows no call: every row's `jev_confidence` is null. */
        val NONE = FrictionConfidence { emptyMap() }
    }
}

/** The confidences of the distinct calls of [notes], read once per call, not once per row. */
fun FrictionConfidence.forNotes(notes: List<FrictionNote>): Map<String, Double> = of(notes.mapNotNull { it.jevCallId }.toSet())

/** Reads each call through [JevCalls.get] and its `group` answer through [JevAnswers.parse]; a call that is gone or unreadable has no confidence. */
@Component
class JevFrictionConfidence(private val calls: JevCalls) : FrictionConfidence {
    override fun of(callIds: Collection<String>): Map<String, Double> = callIds.toSet().mapNotNull { id ->
        val call = runCatching { calls.get(id) }.getOrNull() ?: return@mapNotNull null
        JevAnswers.parse(call.response)?.get(FrictionGroupCheck.QUESTION)?.confidence?.let { id to it }
    }.toMap()
}
