package com.codefrak.engine.github

import java.time.OffsetDateTime

data class IssueSnapshot(val number: Long, val title: String, val body: String, val author: String)

/** One entry of an issue's timeline of events: `labeled`, `renamed`, … `label` is set on (un)labeled. */
data class IssueEvent(val id: Long, val event: String, val actor: String, val at: OffsetDateTime, val label: String? = null)

/**
 * One open issue as GitHub lists it: what a board shows before the engine's own state is joined on.
 * `body` is null when GitHub gave none; the board does not show it, the intake's duplicate check reads it.
 */
data class IssueListing(val number: Long, val title: String, val author: String, val labels: List<String>, val url: String,
                        val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime, val body: String? = null)

/**
 * One issue another is "blocked by", GitHub's native relation. `state` is `open` or `closed`;
 * `stateReason`, and the PR whose merge closed it (`closedByPr`, `closedByPrMerged`), are what
 * GitHub says about the close, null when it does not say (the REST fallback, or a close by hand).
 */
data class Blocker(val repo: String, val number: Long, val state: String, val stateReason: String? = null,
                   val closedByPr: Long? = null, val closedByPrMerged: Boolean? = null) {
    fun stillOpen() = state == "open"
    /** `owner/name#n`, or `#n` for one in `repo`. */
    fun ref(from: String) = if (repo.equals(from, ignoreCase = true)) "#$number" else "$repo#$number"
}
