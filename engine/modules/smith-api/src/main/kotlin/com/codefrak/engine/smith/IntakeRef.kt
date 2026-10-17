package com.codefrak.engine.smith

/**
 * An earlier intake a ticket or an ask names, resolved (engine/README.md, "Blocked by", "An earlier intake"):
 * [ref] as written (`#537` or an intake id), the intake's id, repo and issue, and its [state]:
 * [FILED] with its [ticket] (with its kind, or unlabeled for human triage on `insufficient-permissions`),
 * [REFINING] while it runs without one (a question or a duplicate question waiting counts), or [ENDED]
 * without one, [ended] saying how (cancelled, or an `insufficient-permissions` intake still parked at the
 * incident `task-file-unlabeled` raised before it was built). [ask] is the intake's ask text in every state, cut to
 * `IntakeRefs.ASK_CHARS` (`intake`) with `…` appended when cut, so a refiner reads a refining sibling's ask; null without one.
 * [ticketRepo] is the repo [ticket] is in: [repo] unless the refiner filed it in another target repo.
 */
data class IntakeRef(
    val ref: String, val intake: String, val repo: String, val issue: Long?, val state: String,
    val ticket: Long? = null, val ended: String? = null, val ask: String? = null, val ticketRepo: String = repo,
) {
    companion object {
        const val FILED = "filed"; const val REFINING = "refining"; const val ENDED = "ended"
    }
}
