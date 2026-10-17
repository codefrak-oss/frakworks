package com.codefrak.engine.intake

import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.IntakeOrigin
import com.codefrak.engine.smith.IntakeRef
import com.codefrak.engine.smith.Ticket
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.camunda.TicketTimeline
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.springframework.stereotype.Component

/**
 * Resolves an intake id, or an ask item `#N`, to the ticket that intake filed. The link (repo, issue, ticket) is
 * `engine.intake_ticket`'s ([IntakeTickets]); Operaton's history gives only the instance's state, `outcome` and `ask`.
 */
@Component
class IntakeRefs(private val history: HistoryService, private val links: IntakeTickets) {
    /** [ref] (`#N` in [repo], or an intake id) as an intake, or null when it names none. */
    fun resolve(repo: String, ref: String): IntakeRef? {
        val trimmed = ref.trim()
        ISSUE_REF.matchEntire(trimmed)?.let { m ->
            val link = links.byIssue(repo, m.groupValues[1].toLong()) ?: return null
            val instance = history.createHistoricProcessInstanceQuery().processInstanceId(link.intake).singleResult() ?: return null
            return of(trimmed, instance, link)
        }
        if (UUID.matchEntire(trimmed) == null) return null
        val instance = history.createHistoricProcessInstanceQuery().processDefinitionKey(INTAKE).processInstanceId(trimmed).singleResult()
            ?: return null
        return of(trimmed, instance, links.get(trimmed))
    }

    /**
     * Every earlier intake [text] names, by intake id or by `#N` in [repo], resolved; in order, each once. The
     * engine's `<!-- engine: … -->` trailer is skipped, and so is any intake whose issue or ticket is [own]: a
     * ticket's own intake, and a refine's own ask item, are not dependencies.
     */
    fun references(repo: String, text: String, own: Long?): List<IntakeRef> {
        val body = TRAILER.replace(text, "")
        val refs = (UUID.findAll(body).map { it.value } + ISSUE_IN_TEXT.findAll(body).map { "#" + it.groupValues[1] })
            .distinct().filter { it != "#$own" }.toList()
        return refs.mapNotNull { resolve(repo, it) }
            .filter { own == null || it.repo != repo || (it.issue != own && it.ticket != own) }
            .distinctBy { it.intake }
    }

    /**
     * The reverse of [resolve]: every ticket in [repo] an intake filed, to that intake, from `engine.intake_ticket` in
     * one query. Several intakes naming one ticket: the most recently created wins.
     */
    fun filedBy(repo: String): Map<Long, IntakeView> =
        links.filedIn(repo).mapValues { (_, l) -> IntakeView(l.intake, l.issue, l.issueUrl) }

    /**
     * The intake that filed ticket [number] in [repo], or whose own issue it is, with its ask and its filer (the
     * `submittedBy` login, or the Smith run in `requestedByRun` and that run's ticket), for the run's lineage
     * (engine/README.md, "Lineage"). Null for an issue no intake opened or filed.
     */
    fun origin(repo: String, number: Long): IntakeOrigin? {
        val link = links.filedIn(repo)[number] ?: links.byIssue(repo, number) ?: return null
        val vars = history.createHistoricVariableInstanceQuery().processInstanceId(link.intake)
            .variableNameIn("ask", SUBMITTED_BY, REQUESTED_BY_RUN, REQUESTED_BY_ISSUE, REQUESTED_BY_REPO).list().associate { it.name to it.value }
        return IntakeOrigin(link.intake, Brief.text(vars["ask"]), vars[SUBMITTED_BY] as? String, vars[REQUESTED_BY_RUN] as? String,
            vars[REQUESTED_BY_REPO] as? String, (vars[REQUESTED_BY_ISSUE] as? Number)?.toLong())
    }

    /** The ticket Smith run [run] worked: the `approvedTicket` snapshot on its root flow, or null when history has none. */
    fun ticketOfRun(run: String): Ticket? {
        val instance = history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult() ?: return null
        val root = instance.rootProcessInstanceId ?: run
        val bytes = history.createHistoricVariableInstanceQuery().processInstanceId(root).variableName(Vars.TICKET).list()
            .firstNotNullOfOrNull { it.value as? ByteArray } ?: return null
        return Brief.ticket(bytes)
    }

    private fun of(ref: String, instance: HistoricProcessInstance, link: IntakeTicket?): IntakeRef {
        val vars = history.createHistoricVariableInstanceQuery().processInstanceId(instance.id).variableNameIn(*NAMES).list()
            .associate { it.name to it.value }
        val repo = link?.repo ?: ""
        val issue = link?.issue
        val ticket = link?.ticket
        val ask = Brief.text(vars["ask"])?.let { if (it.length > ASK_CHARS) it.take(ASK_CHARS) + "…" else it }
        return when {
            ticket != null -> IntakeRef(ref, instance.id, repo, issue, IntakeRef.FILED, ticket, ask = ask, ticketRepo = link.ticketRepo)
            instance.endTime != null ->
                IntakeRef(ref, instance.id, repo, issue, IntakeRef.ENDED, ended = instance.deleteReason?.takeIf { it.isNotBlank() } ?: "ended without filing", ask = ask)
            // Only an instance still parked at the incident `task-file-unlabeled` raised before it was built (#730):
            // the step now files the ticket unlabeled and records its ticket, which the first branch reports as filed.
            vars["outcome"] in setOf("insufficient-permissions", TicketTimeline.LEGACY_OUTCOME) ->
                IntakeRef(ref, instance.id, repo, issue, IntakeRef.ENDED, ended = "insufficient-permissions: the refiner could not file it", ask = ask)
            else -> IntakeRef(ref, instance.id, repo, issue, IntakeRef.REFINING, ask = ask)
        }
    }

    companion object {
        const val INTAKE = "intake"
        /** An `intakes` entry's ask is cut to this many characters: enough for a refiner to read what it asked. */
        const val ASK_CHARS = 2_000
        private val NAMES = arrayOf("outcome", "ask")
        private val UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
        private val ISSUE_REF = Regex("""#?(\d+)""")
        /** `#N` on its own: not `owner/repo#N`, not `abc#N`. */
        private val ISSUE_IN_TEXT = Regex("""(?<![\w/.-])#(\d+)\b""")
        private val TRAILER = Regex("""<!--\s*engine:.*?-->""", RegexOption.DOT_MATCHES_ALL)
    }
}
