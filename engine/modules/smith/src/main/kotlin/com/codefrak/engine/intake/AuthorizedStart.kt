package com.codefrak.engine.intake

import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.WorkItemRefused
import org.operaton.bpm.engine.impl.cfg.TransactionState
import org.operaton.bpm.engine.impl.context.Context
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Starts a ticket the engine just filed, authorized by whoever authorized what filed it: an intake's
 * ticket (`StartFlowFromIntakeDelegate`) or a proposal's build (`StartProposalBuildDelegate`). Claims
 * it as label event 0, so a later `authorized` label on it is refused as already started, starts the
 * flow, and says so on the ticket; the claim is released if the start fails. Returns the instance.
 *
 * The comment is a `conversation` entry on the ticket's work item ([Tickets]); an unknown ticket is refused
 * ([WorkItemRefused]) and the claim released, as a refused comment was.
 *
 * `label` (a refactoring job's `authorized`, the visible record of an engine start: a [com.codefrak.engine.workitems.WorkItemFlags]
 * flag, set with [Tickets.setFlags]) is written only after the
 * command commits, never inside it (#1802): the label's webhook wakes a scan at once, and that scan must find the
 * label-event-0 claim committed, or it reads the label as a fresh approval and refuses it. A rolled-back start writes
 * no label; a failed label write is only logged, as the claim and the flow stand.
 */
@Component
class AuthorizedStart(private val handled: HandledIssues, private val starter: FlowStarter, private val tickets: Tickets) {
    private val log = LoggerFactory.getLogger(AuthorizedStart::class.java)

    fun start(repo: String, kind: String, snapshot: IssueSnapshot, by: String, detail: String,
              tier: Int?, mode: String?, dryRun: Boolean, label: String? = null, flakyTest: Boolean = false): String {
        val ticket = snapshot.number
        // Label event 0: no label approved it. A later `authorized` label on it is refused as already started.
        // Who asked: the trailer's `by` (the intake's submitter), else the snapshot's author.
        check(handled.claim(repo, ticket, 0, kind, "started", detail, TicketFiler.askedByOf(snapshot.body) ?: snapshot.author,
            TicketFiler.pointsOf(snapshot.body))) { "$repo#$ticket was already started" }
        try {
            val extra = buildMap<String, Any> {
                if (dryRun) put(Vars.DRY_RUN, true)
                // A flaky-test ticket (FlakyTests alone): bug.bpmn skips its reproduce and verify runs.
                if (flakyTest) put(Vars.FLAKY_TEST, true)
                mode?.let { put(Vars.FEATURE_MODE, it) }
            }
            val instance = starter.start(kind, repo, snapshot, by, tier = tier, extra = extra) ?: error("the $kind did not start")
            handled.started(repo, ticket, 0, instance)
            val inMode = mode?.let { " in $it mode" }.orEmpty()
            tickets.comment(item(repo, ticket).id, ENGINE, "${kind.replaceFirstChar { it.uppercase() }} started$inMode: $detail (`$instance`).\n\n— engine")
            label?.let { afterCommit { runCatching { item(repo, ticket).let { i -> tickets.setFlags(i.id, i.flags.with(it)) } }.onFailure { e -> log.warn("{}#{}: label {} failed: {}", repo, ticket, it, e.message) } } }
            return instance
        } catch (e: Exception) {
            handled.release(repo, ticket, 0)
            throw e
        }
    }

    private fun item(repo: String, ticket: Long) = tickets.forTicket(repo, ticket) ?: throw WorkItemRefused("no ticket $repo#$ticket")

    /** After the current Operaton command commits, or at once when none is bound. */
    private fun afterCommit(action: () -> Unit) {
        val context = Context.getCommandContext() ?: return action()
        context.transactionContext.addTransactionListener(TransactionState.COMMITTED) { action() }
    }

    private companion object {
        const val ENGINE = "engine"
    }
}
