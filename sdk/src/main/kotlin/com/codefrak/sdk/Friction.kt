package com.codefrak.sdk

import java.time.Instant

/** One friction note as the engine stores it (engine/README.md, "Friction notes"). */
data class FrictionNote(
    val id: String, val repo: String?, val issue: Long?, val runId: String?, val jobId: String?, val notedAt: Instant,
    val source: String, val kind: String, val summary: String, val evidence: String?, val severity: String?,
)

/** What [Friction.list] narrows by; a null field is no filter. */
data class FrictionFilter(
    val repo: String? = null, val issue: Long? = null, val kind: String? = null, val source: String? = null,
    val since: Instant? = null, val limit: Int = 100,
)

/**
 * The engine's friction log (engine/README.md, "Friction notes"), for a plugin: what a Smith's `note_friction`
 * and First mate's are to them. A note from a plugin is source `as-you-go`, stamped by the engine with the
 * caller's job id, or its run when it has one, and never a ticket the plugin names; the plugin supplies only
 * the four fields.
 */
interface Friction {
    /**
     * Records one note; answers its id. [kind] is one of `bug`, `design`, `docs`, `tooling`, `other`; [severity],
     * when given, `low`, `medium` or `high`. A summary over 2000 characters or evidence over 8000 is refused.
     */
    fun note(kind: String, summary: String, evidence: String? = null, severity: String? = null): String
    /** Notes newest first, at most [FrictionFilter.limit]. */
    fun list(filter: FrictionFilter = FrictionFilter()): List<FrictionNote>
}
