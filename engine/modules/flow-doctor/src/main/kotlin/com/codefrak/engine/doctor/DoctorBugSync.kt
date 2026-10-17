package com.codefrak.engine.doctor

import com.codefrak.engine.github.ClosedIssue
import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.workitems.Tickets
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Brings the filed bugs the doctor waits on ([DoctorBugStore.watched]) in step with GitHub: one read of each repo's
 * newest closed issues, and the comments of a bug closed `not planned` or as a duplicate. Closed `completed`:
 * `fixed`. Closed with GitHub's "Duplicate of #k": its signatures move to #k's doctor bug ([DoctorBugStore.moveTo]).
 * Any other `not planned` or `duplicate` close: `declined`, keeping its signatures, so a later sighting lumps into it
 * as a count. A repo GitHub cannot read is tried at the next sync. Answers how many bugs changed.
 */
@Component
class DoctorBugSync(
    private val store: DoctorBugStore, private val details: IssueDetails, private val tickets: Tickets,
    private val calls: JevCalls,
) {
    private val log = LoggerFactory.getLogger(DoctorBugSync::class.java)

    companion object {
        /** The closed issues [sync] reads per repo: GitHub's newest updated, which a bug closed since the last sync is among. */
        const val CLOSED_LIMIT = 100
        /** GitHub's duplicate close comment. */
        private val DUPLICATE_OF = Regex("""(?im)^\s*Duplicate of #(\d+)\b""")
    }

    fun sync(): Int {
        var changed = 0
        for ((repo, bugs) in store.watched().groupBy { it.repo }) {
            changed += reproOff(repo, bugs)
            val closed = runCatching { details.closedIssues(repo, CLOSED_LIMIT) }
                .onFailure { log.warn("doctor bugs: cannot read {}'s closed issues: {}", repo, it.message) }
                .getOrNull()?.associateBy { it.number } ?: continue
            for (bug in bugs) {
                val issue = closed[bug.issue] ?: continue
                runCatching { if (settle(bug, issue)) changed++ }
                    .onFailure { log.warn("doctor bugs: {}#{} not settled: {}", repo, bug.issue, it.message) }
            }
        }
        return changed
    }

    /** [repo]'s `needs-repro` bugs open on GitHub without the label any more: `open` again. A repo GitHub cannot list is left. */
    private fun reproOff(repo: String, bugs: List<DoctorBugRef>): Int {
        val waiting = bugs.filter { it.state == "needs-repro" }
        if (waiting.isEmpty()) return 0
        val open = runCatching { tickets.open(repo) }.getOrNull()?.associateBy { it.ticket } ?: return 0
        return waiting.count { bug ->
            val listed = open[bug.issue] ?: return@count false
            if (listed.flags.needsRepro) return@count false
            store.reproOff(bug.id)
            true
        }
    }

    /** The engine is up: each bug closed as fixed while it was down, or before this start, has its fix running from this start. */
    @EventListener(ApplicationReadyEvent::class)
    fun started() {
        runCatching { store.fixRunning(DoctorBugs.jvmStart()) }
            .onFailure { log.warn("doctor bugs: fixes not marked running: {}", it.message) }
    }

    private fun settle(bug: DoctorBugRef, issue: ClosedIssue): Boolean {
        when (issue.stateReason) {
            "completed" -> {
                outcomes(bug, null, null)
                store.close(bug.id, "fixed", issue.closedAt)
                // Closed before this start (sync first runs after it): the fix has been running since this start.
                store.fixRunning(DoctorBugs.jvmStart())
            }
            "not_planned", "duplicate" -> {
                val (of, by) = details.restComments(bug.repo, issue.number).orEmpty().mapNotNull { c ->
                    (c["body"] as? String)?.let { DUPLICATE_OF.find(it) }?.groupValues?.get(1)?.toLong()
                        ?.let { it to ((c["user"] as? Map<*, *>)?.get("login") as? String) }
                }.lastOrNull() ?: (null to null)
                outcomes(bug, of?.takeIf { it != issue.number }, by)
                if (of != null && of != issue.number) {
                    val to = store.moveTo(bug.id, bug.repo, of, issue.closedAt)
                    log.info("doctor bugs: {}#{} closed as a duplicate of #{}; its signatures moved to doctor bug {}", bug.repo, issue.number, of, to)
                } else store.close(bug.id, "declined", issue.closedAt)
            }
            else -> return false
        }
        return true
    }

    /**
     * The `outcome` of each Jev call behind [bug]'s sightings that has none yet, as it closes: a `jev` lump `lumped #n:
     * kept`; the `new` that filed it `new: filed #n, kept`, or, closed as a duplicate of #[duplicateOf] by [by], a miss.
     */
    private fun outcomes(bug: DoctorBugRef, duplicateOf: Long?, by: String?) {
        val filed = SightingCheck.name(bug.repo, bug.repo, bug.issue)
        for ((lumpedBy, call) in store.jevCalls(bug.id)) runCatching {
            if (calls.get(call)?.outcome != null) return@runCatching
            calls.recordOutcome(call, when {
                lumpedBy == "jev" -> SightingCheck.kept(filed)
                duplicateOf != null -> SightingCheck.filedDuplicate(filed, "#$duplicateOf", by ?: "unknown")
                else -> SightingCheck.filedKept(filed)
            })
        }.onFailure { log.warn("jev doctor-sighting {}: outcome not recorded: {}", call, it.message) }
    }
}
