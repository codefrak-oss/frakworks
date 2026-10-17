package com.codefrak.engine.workitems

import org.springframework.jdbc.core.simple.JdbcClient
import java.time.OffsetDateTime

/**
 * The store's [Tickets] as the board's, the needs' and the frame tests read them: [list] files a ticket with the labels,
 * times and author a test gives, every repo [open] is asked for is recorded in [listed], and a repo in [unlistable] fails
 * its reads as a store that cannot be read would. Every other call goes to [store].
 */
class ListedTickets(private val store: WorkItemStore, private val jdbc: JdbcClient) : Tickets by store {
    val listed = java.util.Collections.synchronizedList(mutableListOf<String>())
    val unlistable = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Forgets every item and what was read. */
    fun reset() {
        jdbc.sql("DELETE FROM engine.work_item").update(); listed.clear(); unlistable.clear()
    }

    override fun open(repo: String): List<WorkItem> {
        listed += repo
        check(repo !in unlistable) { "GitHub 502" }
        return store.open(repo)
    }

    override fun forTicket(repo: String, number: Long): WorkItem? {
        check(repo !in unlistable) { "GitHub 502" }
        return store.forTicket(repo, number)
    }

    /**
     * [repo]'s open ticket [number]: its kind the first of [labels] that is one ([WorkItem.KINDS], `intake` for an ask;
     * `chore` when none is), a feature's `feature:<mode>`, and the flags among them.
     */
    fun list(repo: String, number: Long, title: String, labels: List<String>, createdAt: OffsetDateTime, updatedAt: OffsetDateTime,
             body: String = "", author: String = "someone"): WorkItem {
        val kind = labels.map { if (it == "intake") WorkItem.ASK else it }.firstOrNull { it in WorkItem.KINDS } ?: "chore"
        val mode = labels.firstOrNull { it.startsWith("feature:") }?.removePrefix("feature:")
        val item = store.create(NewWorkItem(repo, "# $title\n\n$body".trimEnd(), kind, WorkItemSourceRef(WorkItemSourceRef.RAISED),
            mode = mode, ticket = number, author = author, flags = WorkItemFlags.of(labels)))
        jdbc.sql("UPDATE engine.work_item SET created_at = :c, updated_at = :u WHERE id = :id")
            .param("c", createdAt).param("u", updatedAt).param("id", item.id).update()
        return store.get(item.id)!!
    }

    /** [repo]'s ticket [number] ended: done, or cancelled. */
    fun close(repo: String, number: Long, state: TicketState = TicketState.DONE) {
        store.end(repo, number, state, "closed")
    }

    /** [repo]'s ticket [number] waits on its ticket [blocker]. */
    fun blockBy(repo: String, number: Long, blocker: Long) {
        store.block(store.forTicket(repo, number)!!.id, store.forTicket(repo, blocker)!!.id)
    }
}
