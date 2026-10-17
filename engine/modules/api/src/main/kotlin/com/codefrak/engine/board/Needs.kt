package com.codefrak.engine.board

import com.codefrak.engine.at
import com.codefrak.engine.camunda.RootCauseIncidents
import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.doctor.DoctorBugStore
import com.codefrak.engine.doctor.DoctorVars
import com.codefrak.engine.events.FlowIssues
import com.codefrak.engine.github.issueUrl
import com.codefrak.engine.intake.AUTHORIZED_BY
import com.codefrak.engine.intake.TicketFiler
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.repos.rootProcessInstanceId
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.trigger.IssueScanner
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.openListings
import com.fasterxml.jackson.annotation.JsonProperty
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * One thing waiting on a person (engine/README.md, "What needs you"). `kind` is `incident`,
 * `insufficient-permissions`, `authorize`, `reauthorize` or `sighting`; a flow's question, acceptance or decision is
 * no row: it is decided on the HITLP server the engine files it on. `wanted` says what is wanted in a line (for
 * `insufficient-permissions`, the refiner's blocker); `actionId` is what to act on: the incident id
 * `POST /camunda/incidents/{id}/retry` takes, or the issue's URL to label, triage or review; for the `sighting` row
 * with no repo for engine defects, `engineDefects`, the `PATCH /repos/{owner}/{name}` field to set. A doctor run's
 * incident has its ticket's `repo` and `number`. `repo` and `number` are null for an incident on a flow tied to no
 * issue, and for that `sighting` row. `question` is an ask's question in full; else null. The HITLP server's tasks are
 * no rows: the engine is no surface (#100013), the desktop and the phone read them from the server directly.
 */
data class Need(val repo: String?, val number: Long?, val title: String, val kind: String, val wanted: String,
                val since: OffsetDateTime?, val actionId: String, val question: String? = null,
                /**
                 * Whom the row is for, JSON `for` (engine/README.md, "What needs you"); null: the row is everyone's.
                 * Who is expected to answer, not who may: access is unchanged.
                 */
                @param:JsonProperty("for") @get:JsonProperty("for") val forLogin: String? = null)

/**
 * Everything waiting on a person, from the engine's own state: Operaton's open incidents, deferred approvals whose blocker closed
 * without a merge, and (the one GitHub read, the board's listing, bodies included) open issues with one
 * kind label waiting for `authorized` and intakes' ask items waiting for a start, and open issues an intake filed unlabeled for human triage
 * (`insufficient-permissions`: the `· triage` trailer mark and no kind label), and a `sighting` while some repo has
 * `doctorBugs` on and none takes engine defects, and one for each doctor bug still open that the doctor put
 * `needs-review` on (its 4th stuck ticket), in a repo with `doctorBugs` on. Read-only; nothing here is stored.
 */
@Component
class Needs(
    private val repos: TargetRepos,
    private val tickets: Tickets,
    private val handled: HandledIssues,
    private val deferred: DeferredIssues,
    private val runtime: RuntimeService,
    private val doctorBugs: DoctorBugStore,
) {
    private val log = LoggerFactory.getLogger(Needs::class.java)

    companion object {
        /** A label that gives an issue a kind: a triage ticket with one is no longer waiting on triage. */
        /** An intake's ask item's one label, its kind (`WorkItem.ASK`). */
        private const val ASK_KIND = "ask"
        /** What the `sighting` row with no repo for engine defects wants. */
        const val NO_DEFECTS_REPO = "mark a repo for engine defects"
        /** What a doctor bug's `sighting` row wants once it has hit 4 stuck tickets. */
        const val REVIEW_BUG = "review the doctor's bug: 4 stuck tickets hit it and it is still open"
    }

    /**
     * Oldest first; a need with no time last. With `only`, only that repo's needs (matched ignoring case); a need with no repo is then
     * left out. With `forLogin`, only the rows whose `for` is that login (ignoring case) or null: what waits on that person.
     */
    fun list(only: String? = null, forLogin: String? = null): List<Need> {
        // A repo GitHub cannot list loses only its `authorize` rows and its titles.
        val rows = repos.list()
        val targets = rows.map { it.fullName }.filter { only == null || it.equals(only, ignoreCase = true) }
        val listings = targets.associateWith { repo ->
            runCatching { tickets.openListings(repo) }.onFailure { log.warn("needs {}: {}", repo, it.message) }.getOrNull()
        }
        val byIssue = listings.flatMap { (repo, listed) -> listed.orEmpty().map { (repo.lowercase() to it.number) to it } }.toMap()
        fun title(repo: String?, number: Long?, fallback: () -> String?) =
            byIssue[repo.orEmpty().lowercase() to number]?.title ?: runCatching { fallback() }.getOrNull() ?: ""

        val needs = mutableListOf<Need>()
        needs += incidents(::title)
        // The doctor files engine defects from some repo, but no repo takes them (engine/README.md, "Doctor"): one row, in no repo.
        if (rows.any { it.doctorBugs } && rows.none { it.engineDefects })
            needs += Need(null, null, "The doctor has no repo for engine defects", "sighting", NO_DEFECTS_REPO, null, "engineDefects")
        // A doctor bug still open after its 4th stuck ticket (engine/README.md, "Doctor"): one row each, on the bug.
        val bugsOn = rows.filter { it.doctorBugs }.map { it.fullName.lowercase() }.toSet()
        for ((bug, at) in doctorBugs.reviewing()) {
            val issue = bug.issue ?: continue
            if (bug.repo.lowercase() !in bugsOn) continue
            val url = byIssue[bug.repo.lowercase() to issue]?.url ?: issueUrl(bug.repo, issue)
            needs += Need(bug.repo, issue, title(bug.repo, issue) { null }, "sighting", REVIEW_BUG, at, url)
        }
        for ((repo, listed) in listings) {
            if (listed == null) continue
            val approved = handled.list(repo).map { it.issue }.toSet()
            val waiting = deferred.list(repo).map { it.issue }.toSet()
            for (issue in listed) {
                if (WorkItem.TRIAGE in issue.labels) {
                    needs += Need(repo, issue.number, issue.title, "insufficient-permissions",
                        TicketFiler.triageWanted(issue.body.orEmpty()), issue.createdAt, issue.url)
                    continue
                }
                // An intake's ask item (kind `ask`) waits for a trusted login's start, never a label.
                val ask = issue.labels == listOf(ASK_KIND)
                if (!ask && (issue.labels.count { it in ProcessFlowStarter.FLOWS } != 1 || IssueScanner.AUTHORIZED in issue.labels)) continue
                if (issue.number in approved || issue.number in waiting || intakeAuthorized(issue.url)) continue
                needs += Need(repo, issue.number, issue.title, "authorize", if (ask) "start the ask" else "add the authorized label", issue.createdAt, issue.url)
            }
        }
        for (d in deferred.list()) {
            if (d.stalled.isEmpty() || d.blockers.any { it.stillOpen() }) continue
            val url = byIssue[d.repo.lowercase() to d.issue]?.url ?: issueUrl(d.repo, d.issue)
            needs += Need(d.repo, d.issue, title(d.repo, d.issue) { d.snapshot.title }, "reauthorize",
                "blocker ${d.stalled.sorted().joinToString(", ")} closed without a merge: re-authorize to proceed",
                d.lastChecked ?: d.firstSeen, url)
        }
        return needs.filter { only == null || it.repo.equals(only, ignoreCase = true) }
            .filter { forLogin == null || it.forLogin == null || it.forLogin.equals(forLogin, ignoreCase = true) }
            .sortedWith(compareBy(nullsLast()) { it.since })
    }

    /** One per root cause: an incident a failure propagated up a call activity is the same stall. */
    private fun incidents(title: (String?, Long?, () -> String?) -> String): List<Need> {
        val open = runtime.createIncidentQuery().list()
        val ids = open.map { it.id }.toSet()
        return open.filter { RootCauseIncidents.isRootCause(it.rootCauseIncidentId, it.id, ids) }.map { i ->
            val root = runtime.rootProcessInstanceId(i.processInstanceId)
            val (repo, number) = issueOf(root)
            val what = listOfNotNull(i.activityId, i.incidentMessage?.let(::firstLine)).joinToString(": ")
            Need(repo, number, title(repo, number) { ticketTitle(root) }, "incident", "retry or cancel: $what",
                i.incidentTimestamp?.at(), i.id)
        }
    }

    private fun running(instance: String): Boolean =
        runtime.createProcessInstanceQuery().processInstanceId(instance).count() > 0

    /**
     * A flow's issue: its `repo` and `issue`, a doctor run's ticket (`doctorRepo` and `doctorIssue`), or an intake's
     * `issueUrl` (the ask it filed) or `ticketUrl`.
     */
    private fun issueOf(instance: String): Pair<String?, Long?> {
        if (!running(instance)) return null to null
        val vars = runCatching { runtime.getVariables(instance) }.getOrDefault(emptyMap())
        return FlowIssues.ticketIn(vars, listOf(Vars.REPO to Vars.ISSUE, DoctorVars.REPO to DoctorVars.ISSUE)) ?: (null to null)
    }

    /**
     * An intake `POST /intake` started for a submitter who may authorize records that only as
     * `authorizedBy` on its instance, found by the `issueUrl` it filed (as `IssueBoard.intakeFlowView`
     * does): its issue gets no label and no `handled_issue` row, but needs no one. Only while it has filed no
     * `ticket`: the issue it rewrote into the ticket (one issue per ask) needs authorizing at once
     * when the authorization did not carry over.
     */
    private fun intakeAuthorized(issueUrl: String): Boolean =
        runtime.createProcessInstanceQuery().processDefinitionKey("intake").variableValueEquals("issueUrl", issueUrl).list()
            .any { runtime.getVariable(it.id, AUTHORIZED_BY) != null && runtime.getVariable(it.id, "ticket") == null }

    private fun ticketTitle(instance: String): String? =
        (runtime.getVariable(instance, Vars.TICKET) as? ByteArray)?.let { Brief.ticket(it).title }
}

/** The first non-blank line of a question, action or incident message, trimmed to 200 characters, as a need's title or `wanted` shows it. */
internal fun firstLine(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(200)

