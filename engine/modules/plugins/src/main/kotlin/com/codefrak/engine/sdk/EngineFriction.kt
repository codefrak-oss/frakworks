package com.codefrak.engine.sdk

import com.codefrak.engine.friction.FrictionInput
import com.codefrak.engine.friction.FrictionLog
import com.codefrak.engine.friction.FrictionNote
import com.codefrak.engine.friction.record
import com.codefrak.sdk.Friction
import com.codefrak.sdk.FrictionFilter
import java.time.ZoneOffset

/**
 * [Friction] over the engine's [FrictionLog] (engine/README.md, "Custom jobs and the SDK"), for one caller: a
 * note is source `as-you-go`, stamped with the caller's [jobId], its [runId] when it has one, its [repo], and
 * never a ticket. The engine makes one per job run ([EngineFrictions]); a plugin never stamps a note itself.
 */
class EngineFriction(private val log: FrictionLog, private val repo: String?, private val runId: String?, private val jobId: String?) : Friction {
    override fun note(kind: String, summary: String, evidence: String?, severity: String?): String {
        val input = when (val p = FrictionInput.parse(mapOf("kind" to kind, "summary" to summary, "evidence" to evidence, "severity" to severity), "a plugin note")) {
            is FrictionInput.Parsed.Bad -> throw IllegalArgumentException(p.why)
            is FrictionInput.Parsed.Ok -> p.input
        }
        return log.record(repo, null, runId, input, FrictionNote.AS_YOU_GO, jobId = jobId).id
    }

    override fun list(filter: FrictionFilter): List<com.codefrak.sdk.FrictionNote> =
        log.list(com.codefrak.engine.friction.FrictionFilter(filter.repo, filter.issue, filter.kind, filter.source,
            filter.since?.atOffset(ZoneOffset.UTC), filter.limit)).map {
            com.codefrak.sdk.FrictionNote(it.id, it.repo, it.issue, it.runId, it.jobId, it.notedAt.toInstant(), it.source, it.kind,
                it.summary, it.evidence, it.severity)
        }
}

/** Makes the [EngineFriction] a job run gets: stamped with its job id, repo and run. */
@org.springframework.stereotype.Component
class EngineFrictions(private val log: FrictionLog) {
    fun forJob(jobId: String, repo: String?, runId: String? = null): Friction = EngineFriction(log, repo, runId, jobId)
}
