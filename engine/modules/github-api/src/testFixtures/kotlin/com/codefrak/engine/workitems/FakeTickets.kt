package com.codefrak.engine.workitems

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * An in-memory [Tickets] for tests: items numbered from 100000 up (`WorkItemTickets.FIRST`), a `history` entry on every
 * change, and an unknown id refused with a [WorkItemRefused], as `WorkItemStore` does. A state change tells each of
 * [listeners] at once (there is no transaction to wait for); one that throws puts the old state back and rethrows, as a
 * close that failed leaves its ticket open. With [adopt], [forTicket] answers an open `chore` for a ticket number it
 * has not seen, or whose item [ended] elsewhere, so a flow test can end a ticket it never filed here, and flow tests
 * sharing a context (one Spring context outlives a test class, `ProcessFakes.Fresh`) can reuse a number an earlier
 * test in the same class ended, as a fresh, open duplicate. [reuseClosed] turns that off for a number this fixture
 * closed itself: [forTicket] then always answers that same, now-closed item, never minting a duplicate for it — for
 * a test class that resets ([reset]) between its own tests and needs a ticket's close, once committed, to stay
 * found (a child closing itself on its own flow's own merge or no-op close, read back right after by the parent's
 * wake). [numbering] picks a new item's ticket number in place of the
 * running count, and [onComment], [onFlags] and [onBlock] see each comment, flag change and new blocker before it is recorded (one that throws
 * records nothing): how a flow test's fake GitHub still sees what the engine files, says and labels.
 */
class FakeTickets(
    val listeners: MutableList<TicketStateListener> = mutableListOf(),
    private val adopt: Boolean = false,
    private val numbering: ((NewWorkItem) -> Long)? = null,
    private val reuseClosed: Boolean = false,
) : Tickets {
    /** Told of each comment ([item], its body) before it is recorded. */
    var onComment: ((WorkItem, String) -> Unit)? = null
    /** Told of each flag change ([item] before it, the new flags) before it is recorded. */
    var onFlags: ((WorkItem, WorkItemFlags) -> Unit)? = null
    /** Told of each body edit ([item] before it, the new body) before it is recorded. */
    var onEditBody: ((WorkItem, String) -> Unit)? = null
    /**
     * Told of each new blocker ([item] before it, the [blocker]) before it is recorded; not of one [item] already has.
     * Answers whether this fake records it too: false leaves it to the fake GitHub alone, as [link] records one alone.
     */
    var onBlock: ((WorkItem, WorkItem) -> Boolean)? = null
    /**
     * Tickets ((repo, number), the repo lower-cased) [adopt] answers as done rather than open: a ticket already
     * closed. Irrelevant once [reuseClosed] is on: a number this fixture has an item for, open or closed, then
     * always answers that one, never minting a duplicate for it regardless of [ended].
     */
    val ended: MutableSet<Pair<String, Long>> = java.util.Collections.synchronizedSet(mutableSetOf())

    private val items = linkedMapOf<String, WorkItem>()
    private var next = FIRST

    /** Forgets every item: a test's fresh start on a shared fixture. */
    @Synchronized fun reset() { items.clear(); ended.clear(); next = FIRST }

    private fun now() = OffsetDateTime.now(ZoneOffset.UTC)

    private fun known(id: String): WorkItem = items[id] ?: throw WorkItemRefused("no work item $id")

    private fun put(item: WorkItem, why: String): WorkItem {
        val entry = WorkItemEntry(UUID.randomUUID().toString(), WorkItemEntry.HISTORY, "engine", why, true, now())
        return item.copy(updatedAt = now(), entries = item.entries + entry).also { items[it.id] = it }
    }

    @Synchronized override fun create(new: NewWorkItem): WorkItem {
        if (new.kind !in WorkItem.KINDS) throw WorkItemRefused("kind is one of ${WorkItem.KINDS.joinToString()}")
        if (new.description.isBlank()) throw WorkItemRefused("description is empty")
        val ticket = new.ticket ?: numbering?.invoke(new) ?: next++
        val at = now()
        return put(WorkItem(UUID.randomUUID().toString(), new.repo, new.description, new.kind, new.tier, new.mode,
            if (new.blockedBy.any { items[it]?.open == true }) WorkItem.BLOCKED else WorkItem.READY, new.source, new.trusted,
            ticket, at, at, new.blockedBy, author = new.author, flags = new.flags), "created from ${new.source.kind}")
    }

    @Synchronized override fun comment(id: String, author: String, body: String): WorkItemEntry {
        val item = known(id)
        onComment?.invoke(item, body)
        val entry = WorkItemEntry(UUID.randomUUID().toString(), WorkItemEntry.CONVERSATION, author, body, true, now())
        items[id] = item.copy(entries = item.entries + entry)
        return entry
    }

    override fun setState(id: String, state: TicketState, why: String) {
        val (before, after) = synchronized(this) {
            val item = known(id)
            item to put(item.copy(state = state.state), "${state.state}: $why")
        }
        if (before.state == after.state) return
        try {
            listeners.forEach { it.onStateChanged(after, before.state, after.state) }
        } catch (e: Exception) {
            synchronized(this) { items[id] = before }
            throw e
        }
    }

    @Synchronized override fun setKind(id: String, kind: String) {
        if (kind !in WorkItem.KINDS || kind == WorkItem.ASK) throw WorkItemRefused("kind is one of ${(WorkItem.KINDS - WorkItem.ASK).joinToString()}")
        put(known(id).copy(kind = kind), "kind: $kind")
    }

    @Synchronized override fun setMode(id: String, mode: String?) { put(known(id).copy(mode = mode), "mode: ${mode ?: "none"}") }

    @Synchronized override fun setFlags(id: String, flags: WorkItemFlags) {
        val item = known(id)
        onFlags?.invoke(item, flags)
        put(item.copy(flags = flags), "flags: ${flags.labels.joinToString().ifEmpty { "none" }}")
    }

    @Synchronized override fun setTier(id: String, tier: Int?) {
        if (tier != null && tier !in 1..10) throw WorkItemRefused("tier is 1 to 10")
        put(known(id).copy(tier = tier), "tier: ${tier ?: "none"}")
    }

    @Synchronized override fun editBody(id: String, body: String, author: String) {
        val item = known(id)
        onEditBody?.invoke(item, body)
        put(item.copy(description = "# ${item.title}\n\n${body.trimEnd()}"), "body edited by $author (was ${Tickets.bodyRev(item.body)})")
    }

    @Synchronized override fun retitle(id: String, title: String, author: String) {
        val item = known(id)
        if (title.isBlank()) throw WorkItemRefused("title is empty")
        put(item.copy(description = "# ${title.trim()}\n\n${item.body.trimEnd()}"), "title edited by $author (was ${item.title})")
    }

    @Synchronized override fun block(id: String, blocker: String) {
        val item = known(id); val by = known(blocker)
        if (id == blocker) throw WorkItemRefused("a work item cannot block itself")
        if (blocker !in item.blockedBy && onBlock?.invoke(item, by) == false) return
        put(item.copy(blockedBy = (item.blockedBy + blocker).distinct()), "blocked by $blocker")
    }

    /** [block] with no [onBlock]: a test's own link, recorded here alone. */
    @Synchronized fun link(id: String, blocker: String) {
        val item = known(id); known(blocker)
        if (id == blocker) throw WorkItemRefused("a work item cannot block itself")
        put(item.copy(blockedBy = (item.blockedBy + blocker).distinct()), "blocked by $blocker")
    }

    @Synchronized override fun unblock(id: String, blocker: String) {
        val item = known(id)
        put(item.copy(blockedBy = item.blockedBy - blocker), "unblocked by $blocker")
    }

    @Synchronized override fun details(id: String): WorkItem? = items[id]

    @Synchronized override fun conversation(id: String): List<WorkItemEntry> = items[id]?.entries.orEmpty().filter { it.kind == WorkItemEntry.CONVERSATION }

    @Synchronized override fun history(id: String): List<WorkItemEntry> = items[id]?.entries.orEmpty().filter { it.kind == WorkItemEntry.HISTORY }

    @Synchronized override fun forTicket(repo: String, number: Long): WorkItem? {
        val closed = adopt && (repo.lowercase() to number) in ended
        return items.values.lastOrNull { it.ticket == number && it.repo.equals(repo, ignoreCase = true) && (it.open || !adopt || closed || reuseClosed) }
            ?: if (adopt) create(NewWorkItem(repo, "# $repo#$number", "chore", WorkItemSourceRef(WorkItemSourceRef.RAISED), ticket = number))
                .let { if (closed) it.copy(state = WorkItem.DONE).also { done -> items[done.id] = done } else it } else null
    }

    @Synchronized override fun open(repo: String): List<WorkItem> =
        items.values.filter { it.open && it.repo.equals(repo, ignoreCase = true) }.reversed().map { it.copy(entries = emptyList()) }

    @Synchronized override fun newestTitled(repo: String, title: String): WorkItem? =
        items.values.lastOrNull { it.title == title && it.repo.equals(repo, ignoreCase = true) }

    /**
     * A fresh, open item for [repo]'s ticket [number], as though the engine had just adopted it anew: for a test
     * simulating a real-world close (a merge) distinct from this fixture's own earlier close of the same number
     * (e.g. the chore's own internal completion), once [reuseClosed] keeps [forTicket] itself from minting one.
     */
    @Synchronized fun reopen(repo: String, number: Long): WorkItem =
        create(NewWorkItem(repo, "# $repo#$number", "chore", WorkItemSourceRef(WorkItemSourceRef.RAISED), ticket = number))

    @Synchronized override fun kind(id: String): String? = items[id]?.kind

    @Synchronized override fun mode(id: String): String? = items[id]?.mode

    @Synchronized override fun state(id: String): String? = items[id]?.state

    companion object {
        /** `WorkItemTickets.FIRST`, which lives above this module. */
        const val FIRST = 100_000L
    }
}
