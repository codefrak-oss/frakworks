package com.codefrak.engine.doctor

import com.codefrak.engine.apart
import com.codefrak.engine.camunda.IssueInstances
import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import com.codefrak.engine.workitems.flag
import com.codefrak.engine.workitems.item
import com.codefrak.engine.workitems.snapshot
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** The doctor's "Repeats" step ([DoctorBugs] KDoc): [repeat], its one public function, and the priority and restart it takes. */
@Component
class DoctorBugRepeats(
    private val store: DoctorBugStore, private val history: DoctorHistory, private val repos: TargetRepos,
    private val workItems: Tickets, private val instances: IssueInstances,
    private val flows: FlowStarter,
) {
    private val log = LoggerFactory.getLogger(DoctorBugRepeats::class.java)

    companion object {
        /** The reproduces the doctor restarts on one `needs-repro` bug, at most. */
        const val MAX_RESTARTS = 2
        /** The priority in the trailer the intake files every ticket with (intake/TicketFiler.kt). */
        private val PRIORITY = Regex("""(<!-- engine: [^>]*?·\s*)p([2-5])\b""")

        /** The trailer's priority stepped down one, never above p2: the new body paired with the stepped label, or null when there was none to step. */
        fun stepPriority(body: String): Pair<String, String>? {
            val match = PRIORITY.find(body) ?: return null
            val n = match.groupValues[2].toInt()
            if (n <= 2) return null
            val stepped = "p${n - 1}"
            return body.replaceRange(match.range, match.groupValues[1] + stepped) to stepped
        }
    }

    /**
     * A sighting lumped into [bug] from a stuck ticket it had no sighting from yet (engine/README.md, "Doctor", "Repeats"),
     * on a filed `open` or `needs-repro` bug, both repos on: the 2nd ticket comments on the bug, linking the stuck one;
     * the 3rd also steps its priority up one, never above p2; the 4th also adds `needs-review`, which `GET /needs`
     * shows as a `sighting` row. Each step is taken once ([DoctorBugStore.stepRepeat]). A bug with a flow running gets
     * the comment and the priority, never `needs-review` or a flow; a `needs-repro` one with none gets its reproduce restarted
     * ([restart]), at most [MAX_RESTARTS] times. A declined bug only counts.
     */
    fun repeat(new: NewSighting, seen: DoctorSighting, bug: DoctorBugRef) {
        val issue = bug.issue ?: return
        if (bug.state !in setOf("open", "needs-repro")) return
        if (!repos.doctorBugs(new.repo) || !repos.doctorBugs(bug.repo)) return
        if (!store.newTicket(bug.id, seen.id, new.repo, new.issue)) return
        val (tickets, runs) = store.counts(bug.id)
        val running = instances.roots(bug.repo, issue).any { it.endTime == null }
        val restarted = bug.state == "needs-repro" && !running && restart(bug, issue)
        if (tickets < 2 || !store.stepRepeat(bug.id, tickets)) {
            if (restarted) workItems.commentOn(bug.repo, issue, "Seen on ${new.repo}#${new.issue}; reproduce restarted with every sighting in its brief.\n\n— ${DoctorBugs.DOCTOR.name}")
            return
        }
        val since = history.get(new.doctorRunId)?.stuckSince
        val leaves = new.signature.leaves.joinToString(", ") { "`$it`" }.ifEmpty { "no leaf" }
        val notes = buildList {
            if (tickets == 3L) runCatching { priority(bug.repo, issue) }
                .onFailure { log.warn("doctor bugs: {}#{}'s priority not stepped: {}", bug.repo, issue, it.message) }
                .getOrNull()?.let { add("priority stepped up to $it") }
            if (tickets == 4L && !running) {
                workItems.flag(bug.repo, issue, DoctorBugs.NEEDS_REVIEW)
                store.review(bug.id)
                add("`${DoctorBugs.NEEDS_REVIEW}`: still open after 4 stuck tickets")
            }
            if (restarted) add("reproduce restarted with every sighting in its brief")
            if (running) add("its flow is running, so the doctor starts nothing")
        }
        workItems.commentOn(bug.repo, issue, "Seen again: ${new.repo}#${new.issue}, stuck at $leaves${since?.let { " since $it" }.orEmpty()}. " +
            "Seen on $tickets tickets ($runs run${if (runs == 1L) "" else "s"})${notes.joinToString("") { "; $it" }}.\n\n— ${DoctorBugs.DOCTOR.name}")
        log.info("doctor run {}: {}#{} seen on {} tickets ({} runs){}", new.doctorRunId, bug.repo, issue, tickets, runs, notes.joinToString("") { "; $it" })
    }

    /** Steps the priority in [repo]#[issue]'s trailer up one, never above p2: the new one, or null when there was none to step. */
    private fun priority(repo: String, issue: Long): String? {
        val item = workItems.item(repo, issue)
        val body = item.body
        val (newBody, stepped) = stepPriority(body) ?: return null
        workItems.editBody(item.id, newBody, DoctorBugs.DOCTOR.name)
        return stepped
    }

    /**
     * Restarts the bug flow on the `needs-repro` bug [repo]#[issue], as the engine's doctor, its ticket the issue with
     * every sighting on the bug below it; false when it had [MAX_RESTARTS] already or the flow did not start.
     */
    private fun restart(bug: DoctorBugRef, issue: Long): Boolean {
        if (!store.restarted(bug.id, MAX_RESTARTS)) return false
        return try {
            val ticket = workItems.snapshot(bug.repo, issue)
            val body = buildString {
                append(ticket.body.trimEnd()).append("\n\n## Sightings (the engine's doctor)\n\n")
                store.seen(bug.id).forEach { s ->
                    append("- ${s.repo}#${s.issue}, `${s.kind}` at ${s.seenAt}${s.summary?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { ": ${it.trim()}" }.orEmpty()}\n")
                }
            }
            apart(DoctorBugs.START_TIMEOUT_SECONDS) { flows.start("bug", bug.repo, IssueSnapshot(ticket.number, ticket.title, body.trimEnd(), ticket.author), DoctorBugs.DOCTOR.name) }
            log.info("doctor bugs: {}#{} needs a repro; reproduce restarted", bug.repo, issue)
            true
        } catch (e: Exception) {
            log.warn("doctor bugs: {}#{}'s reproduce not restarted: {}", bug.repo, issue, e.message)
            store.unrestarted(bug.id)
            false
        }
    }
}
