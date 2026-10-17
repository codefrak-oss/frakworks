package com.codefrak.engine.intake

import com.codefrak.engine.events.CreatedEvents
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.github.CreatedIssue
import com.codefrak.engine.github.NewIssue
import com.codefrak.engine.workitems.FakeTickets
import com.codefrak.engine.workitems.WorkItem
import tools.jackson.module.kotlin.jacksonObjectMapper

/** A filer whose `created` frames go to a bus of its own with no rows: for the tests that only read [TicketFiler.body]. */
fun testFiler(github: TicketRecorder = TicketRecorder()) =
    TicketFiler(github.tickets(), CreatedEvents(EngineEvents(jacksonObjectMapper())) { _, _ -> null })

/**
 * A [FakeTickets] that adopts any ticket number and mirrors what the engine does to its tickets onto this recorder: each new
 * item is this recorder's create (its number, and its labels as a ticket's: kind, a feature's mode, flags), each comment its
 * comment, each body edit its body edit, each new blocker its `blockBy`, each flag set its label, and each end its close (`completed` for done, `not_planned` for cancelled). The flow
 * tests' [com.codefrak.engine.workitems.Tickets] bean, so their `created`, `comments`, `labelled`, `blockedBy`, `closed` and
 * `closedAs` checks see what the flows did. [reuseClosed] (default off, as most flow test classes share a number
 * across tests in the same class, `ProcessFakes.Fresh`) is for a class that [FakeTickets.reset]s between its own
 * tests and needs a ticket closed within one of them to stay found by number, not minted again as a fresh chore.
 */
fun TicketRecorder.tickets(reuseClosed: Boolean = false) = FakeTickets(adopt = true, reuseClosed = reuseClosed, numbering = { n ->
    val title = n.description.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().removePrefix("# ").trim()
    val body = n.description.lineSequence().dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()
    val labels = listOfNotNull(n.kind, n.mode?.takeIf { n.kind == "feature" }?.let { "feature:$it" }) + n.flags.labels
    create(n.repo, NewIssue(title, body, labels)).number
}).also { t ->
    t.listeners += com.codefrak.engine.workitems.TicketStateListener { item, _, to ->
        close(item.repo, item.ticket!!, if (to == WorkItem.CANCELLED) "not_planned" else "completed")
    }
    t.onComment = { item, body -> comment(item.repo, item.ticket!!, body) }
    t.onEditBody = { item, body -> editBody(item.repo, item.ticket!!, body) }
    // The engine's blocker goes to this fake alone, as `blockBy` did; a flow test links its own with `link`.
    t.onBlock = { item, blocker -> blockBy(item.repo, item.ticket!!, blocker.ticket!!); false }
    t.onFlags = { item, flags ->
        (flags.labels - item.flags.labels.toSet()).takeIf { it.isNotEmpty() }?.let { label(item.repo, item.ticket!!, *it.toTypedArray()) }
    }
}

/**
 * What the engine did to its tickets, as [tickets] reports it: each create (its title, body and labels), comment, close,
 * label, body edit and blocker. No GitHub client: tickets are store work items. Numbers tickets from 100.
 */
class TicketRecorder {
    val created: MutableList<Pair<String, NewIssue>> = java.util.Collections.synchronizedList(mutableListOf())
    val comments: MutableList<Triple<String, Long, String>> = java.util.Collections.synchronizedList(mutableListOf())
    val closed: MutableList<Pair<String, Long>> = java.util.Collections.synchronizedList(mutableListOf())
    /** Each close's `state_reason`, by issue: the last one wins. */
    val closedAs = mutableMapOf<Pair<String, Long>, String>()
    /** Called on each comment before it is recorded: a test's failure of that write. */
    var onComment: ((String, Long, String) -> Unit)? = null
    fun comment(repo: String, number: Long, body: String) { onComment?.invoke(repo, number, body); comments += Triple(repo, number, body) }
    fun commentsOn(issue: Long) = comments.filter { it.second == issue }.map { it.third }
    /** Called on each close before it is recorded: a test's injected failure of the close call. */
    var onClose: ((String, Long) -> Unit)? = null
    fun close(repo: String, number: Long, reason: String = "completed") { onClose?.invoke(repo, number); closed += repo to number; closedAs[repo to number] = reason }
    val labelled = mutableListOf<Triple<String, Long, List<String>>>()
    /** Called on each label write, so a test's fake reader can list the labeled event. */
    var onLabel: ((String, Long, String) -> Unit)? = null
    fun label(repo: String, number: Long, vararg labels: String) {
        labelled += Triple(repo, number, labels.toList())
        labels.forEach { onLabel?.invoke(repo, number, it) }
    }
    /** Called on each create, on the creating thread: a test's probe of what the call holds. */
    var onCreate: (() -> Unit)? = null
    /** Called after each create is recorded: a test's failure once the ticket exists (its answer lost). */
    var afterCreate: ((String, Long, NewIssue) -> Unit)? = null
    fun create(repo: String, issue: NewIssue): CreatedIssue {
        onCreate?.invoke()
        val number = synchronized(created) { created += repo to issue; (99L + created.size).also { held[it] = repo to issue } }
        afterCreate?.invoke(repo, number, issue)
        return CreatedIssue(number, "https://github.com/$repo/issues/$number", 1_000_000 + number)
    }
    /** (repo, blocked issue, blocking issue) as written. */
    val blockedBy: MutableList<Triple<String, Long, Long>> = java.util.Collections.synchronizedList(mutableListOf())
    fun blockBy(repo: String, number: Long, blockedBy: Long, blockedById: Long? = null) { this.blockedBy += Triple(repo, number, blockedBy) }
    /** (repo, blocked issue, blocking issue) as removed. */
    val unblocked = mutableListOf<Triple<String, Long, Long>>()
    fun unblock(repo: String, number: Long, blockedBy: Long) { unblocked += Triple(repo, number, blockedBy) }
    /** (repo, issue, new body) as edited. */
    val edited = mutableListOf<Triple<String, Long, String>>()
    fun editBody(repo: String, number: Long, body: String) { edited += Triple(repo, number, body) }
    /** (repo, issue, title, body, labels) as `edit` was called (`Tickets.update`). */
    val edits = mutableListOf<Triple<Pair<String, Long>, Pair<String?, String?>, List<String>?>>()
    /** Called after each edit is recorded: a test's failure once the edit was made. */
    var afterEdit: ((String, Long, List<String>?) -> Unit)? = null
    fun edit(repo: String, number: Long, title: String? = null, body: String? = null, labels: List<String>? = null) {
        edits += Triple(repo to number, title to body, labels)
        // The ticket as the store would now hold it: an intake rewrites its own issue into the ticket.
        synchronized(created) {
            held[number]?.takeIf { it.first == repo }?.let { (r, old) ->
                held[number] = r to NewIssue(title ?: old.title, body ?: old.body, labels ?: old.labels)
            }
        }
        afterEdit?.invoke(repo, number, labels)
    }
    /** Each ticket as the store holds it now, by number: `created` is what each create sent, `edit` changes only this. */
    val held: MutableMap<Long, Pair<String, NewIssue>> = java.util.Collections.synchronizedMap(mutableMapOf())
    /** The issue the fake filed last, as it is held now. */
    fun lastHeld(): Pair<String, NewIssue> = synchronized(created) { held.getValue(99L + created.size) }
}
