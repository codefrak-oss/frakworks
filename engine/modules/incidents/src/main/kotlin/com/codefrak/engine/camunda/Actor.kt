package com.codefrak.engine.camunda

import com.codefrak.engine.doctor.DoctorVars
import com.codefrak.engine.repos.Access
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.issueOf
import com.codefrak.engine.smith.repoOf
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.springframework.stereotype.Component

/**
 * Who retries an incident or cancels a flow (engine/README.md, "The diagnostics server"): a person
 * by GitHub login, what the HTTP endpoints take, or a running Smith sandbox the engine found by its
 * address, what frakworks-engine-diagnostics acts for, or a part of the engine itself (the doctor).
 */
sealed interface Actor {
    /** Who, where a login goes: a question's `answered_by`, the reason a deleted instance keeps. */
    val name: String
    /** Who, as a GitHub comment names them. */
    val label: String

    /** A login; it must be a trusted login ([Access]). */
    data class Login(val by: String) : Actor {
        override val name get() = by
        override val label get() = "@$by"
    }

    /** A Smith; it may act only on flows in its own repo, and never on the flow it runs under. */
    data class Smith(val caller: SmithCaller) : Actor {
        override val name get() = "Smith run ${caller.runId}"
        override val label get() = "Smith run `${caller.runId}`" +
            (caller.issue?.let { " working ${caller.repo.orEmpty()}#$it" } ?: caller.repo?.let { " on $it" }.orEmpty())
    }

    /** The engine itself, `part` naming what in it acts (the doctor); no access check, since no user asked. */
    data class System(val part: String) : Actor {
        override val name get() = "the engine's $part"
        override val label get() = "The engine's $part"
    }
}

/**
 * The ticket a root flow acts on, and the kind access to it is checked for: a ticket flow's own `repo`, `issue` and
 * definition key; a doctor run's ticket's (engine/README.md, "Doctor"): `doctorRepo`, `doctorIssue`, and the kind of
 * the ticket's root flow (`doctorRoot`, running or ended), as `doctor` is no flow a repo grants. [flow] is the root's own key.
 */
data class FlowTicket(val repo: String?, val issue: Long?, val kind: String, val flow: String) {
    val doctor: Boolean get() = flow == DoctorVars.PROCESS
    /** The flow as a refusal names it: `a chore`, or `a doctor run on a chore`. */
    val described: String get() = if (doctor) "doctor run on a $kind" else kind
}

/** Whether an [Actor] may act on a flow, and the 403 text when not. */
@Component
class ActorAccess(private val access: Access, private val runtime: RuntimeService, private val history: HistoryService) {
    /** The ticket [root] acts on ([FlowTicket]); a doctor run whose ticket's flow is unknown keeps `doctor` as its kind, which no login is granted. */
    fun ticketOf(root: ProcessInstance): FlowTicket {
        val flow = root.processDefinitionId.substringBefore(':')
        if (flow != DoctorVars.PROCESS) return FlowTicket(runtime.repoOf(root.id), runtime.issueOf(root.id), flow, flow)
        val vars = runtime.getVariables(root.id, listOf(DoctorVars.REPO, DoctorVars.ISSUE, DoctorVars.ROOT))
        val ticketRoot = vars[DoctorVars.ROOT] as? String
        val kind = ticketRoot?.let { id ->
            runtime.createProcessInstanceQuery().processInstanceId(id).singleResult()?.processDefinitionId?.substringBefore(':')
                ?: history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult()?.processDefinitionKey
        } ?: flow
        return FlowTicket(vars[DoctorVars.REPO] as? String, (vars[DoctorVars.ISSUE] as? Number)?.toLong(), kind, flow)
    }

    /** Whether [actor] may act on a [kind] on [repo] whose root flows are [roots]. */
    fun allowed(actor: Actor, repo: String, kind: String, roots: Collection<String>): Boolean = when (actor) {
        is Actor.Login -> access.trusted(actor.by)
        is Actor.Smith -> ownRepo(actor, repo) && ownRoot(actor.caller.runId).let { it == null || it !in roots }
        is Actor.System -> true
    }

    /** Why [actor] may not [verb] a [kind] on [repo]; a login's text is the HTTP endpoints' own. */
    fun refusal(actor: Actor, verb: String, kind: String, repo: String?): String = when (actor) {
        is Actor.Login -> "@${actor.by} is not a trusted login"
        is Actor.Smith -> if (repo != null && ownRepo(actor, repo)) "${actor.name} may not $verb the $kind it runs under"
            else "${actor.name} may $verb only flows in its own repo (${actor.caller.repo ?: "none"}), not a $kind on ${repo ?: "an unknown repo"}"
        is Actor.System -> "${actor.name} may not $verb a $kind on ${repo ?: "an unknown repo"}"
    }

    private fun ownRepo(actor: Actor.Smith, repo: String) = actor.caller.repo?.equals(repo, ignoreCase = true) == true

    /** The root flow a Smith run (a `smith-run` instance) runs under, or null when it is not running. */
    private fun ownRoot(runId: String): String? {
        var id = runtime.createProcessInstanceQuery().processInstanceId(runId).singleResult()?.id ?: return null
        while (true) id = runtime.createProcessInstanceQuery().subProcessInstanceId(id).singleResult()?.id ?: return id
    }
}
