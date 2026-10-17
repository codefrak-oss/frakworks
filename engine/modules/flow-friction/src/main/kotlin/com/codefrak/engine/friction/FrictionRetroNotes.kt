package com.codefrak.engine.friction

import com.codefrak.engine.smith.RetroNotes
import org.springframework.stereotype.Component

/** A run's retro notes (`collect smith`, [RetroNotes]) as friction notes of source [FrictionNote.RETRO]. */
@Component
class FrictionRetroNotes(private val friction: FrictionLog) : RetroNotes {
    override fun record(repo: String?, issue: Long?, runId: String, item: Any?, jobId: String?): String? {
        @Suppress("UNCHECKED_CAST")
        val parsed = (item as? Map<String, Any?>)?.let { FrictionInput.parse(it, "a retro note") }
        return when (parsed) {
            is FrictionInput.Parsed.Ok -> { friction.record(repo, issue, runId, parsed.input, FrictionNote.RETRO, jobId = jobId); null }
            is FrictionInput.Parsed.Bad -> parsed.why
            null -> "not an object"
        }
    }
}
