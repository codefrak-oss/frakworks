package com.codefrak.engine.workitems

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `engine.work_item` and its dependency and entry tables (V88, engine/docs/work-items.md). An item is created `blocked`
 * when it has an undone blocker and `ready` otherwise; [WorkItemGate] moves it on. Every change appends a `history` entry.
 * A create and every state change are handed to each [WorkItemListener] (the GitHub publisher, which does nothing for a
 * repo that has not opted in). It is the engine's [Tickets]: a state change is also handed to each [TicketStateListener],
 * after it commits.
 */
@Repository
class WorkItemStore(
    private val jdbc: JdbcClient,
    private val listeners: List<WorkItemListener> = emptyList(),
    private val stateListeners: List<TicketStateListener> = emptyList(),
) : Tickets {
    private fun changed(id: String) = listeners.forEach { l -> get(id)?.let { l.changed(it) } }

    private fun now() = OffsetDateTime.now(ZoneOffset.UTC)

    @Transactional
    override fun create(new: NewWorkItem): WorkItem {
        if (new.kind !in WorkItem.KINDS) throw WorkItemRefused("kind is one of ${WorkItem.KINDS.joinToString()}")
        if (new.source.kind !in WorkItemSourceRef.KINDS) throw WorkItemRefused("source is one of ${WorkItemSourceRef.KINDS.joinToString()}")
        if (new.tier != null && new.tier !in 1..10) throw WorkItemRefused("tier is 1 to 10")
        if (new.description.isBlank()) throw WorkItemRefused("description is empty")
        val id = UUID.randomUUID().toString()
        val at = now()
        // Every item is a ticket: its number from V94's sequence unless the caller gave one.
        val ticket = new.ticket ?: nextTicket()
        jdbc.sql("""INSERT INTO engine.work_item (id, full_name, description, kind, tier, mode, state, source_kind, source_ref,
                    trusted, ticket, author, refactor, authorized, needs_repro, needs_review, created_at, updated_at)
                    VALUES (:id, :r, :d, :k, :tier, :m, :s, :sk, :sr, :tr, :t, :au, :rf, :az, :nrp, :nrv, :at, :at)""")
            .param("id", id).param("r", new.repo).param("d", new.description).param("k", new.kind).param("tier", new.tier)
            .param("m", new.mode).param("s", WorkItem.READY).param("sk", new.source.kind).param("sr", new.source.ref)
            .param("tr", new.trusted).param("t", ticket).param("au", new.author)
            .param("rf", new.flags.refactor).param("az", new.flags.authorized).param("nrp", new.flags.needsRepro)
            .param("nrv", new.flags.needsReview)
            .param("at", at).update()
        new.blockedBy.forEach { addDependency(id, it) }
        appendEntry(id, WorkItemEntry.HISTORY, "engine", "created from ${new.source.kind}", true)
        changed(id)
        return get(id)!!
    }

    private fun nextTicket(): Long =
        jdbc.sql("SELECT nextval('engine.work_item_ticket')").query(Long::class.java).single()

    /** Replaces [id]'s description (its ticket's title or body edited), with a `history` entry saying [why]. */
    fun describe(id: String, description: String, why: String) {
        if (description.isBlank()) throw WorkItemRefused("description is empty")
        jdbc.sql("UPDATE engine.work_item SET description = :d, updated_at = :at WHERE id = :id")
            .param("d", description).param("at", now()).param("id", id).update()
        appendEntry(id, WorkItemEntry.HISTORY, "engine", why, true)
        changed(id)
    }

    /** [item] no longer waits on [blocker]. */
    fun removeDependency(item: String, blocker: String) {
        jdbc.sql("DELETE FROM engine.work_item_dependency WHERE item = :i AND blocker = :b").param("i", item).param("b", blocker).update()
    }

    /** [repo]'s open items (not done or cancelled), newest first, without their entries. */
    override fun open(repo: String): List<WorkItem> = list(repo).filter { it.open }

    /** The description starts `# <title>`; the exact title is checked on the parsed item, so a longer title is no match. */
    override fun newestTitled(repo: String, title: String): WorkItem? =
        jdbc.sql("SELECT id FROM engine.work_item WHERE LOWER(full_name) = LOWER(:r) AND LEFT(description, LENGTH(:p)) = :p ORDER BY created_at DESC, id")
            .param("r", repo).param("p", "# $title").query(String::class.java).list()
            .filterNotNull().firstNotNullOfOrNull { id -> get(id)?.takeIf { it.title == title } }

    fun get(id: String): WorkItem? =
        jdbc.sql("SELECT * FROM engine.work_item WHERE id = :id").param("id", id).query { rs, _ -> row(rs) }.optional()
            .map { it.copy(blockedBy = blockers(id), entries = entries(id)) }.orElse(null)

    /** [repo]'s items, newest first, without their entries. */
    fun list(repo: String): List<WorkItem> =
        jdbc.sql("SELECT * FROM engine.work_item WHERE LOWER(full_name) = LOWER(:r) ORDER BY created_at DESC, id")
            .param("r", repo).query { rs, _ -> row(rs) }.list().map { it.copy(blockedBy = blockers(it.id)) }

    /** The item a Smith run of [repo]'s issue [ticket] works, if one was made for it. */
    override fun forTicket(repo: String, number: Long): WorkItem? =
        jdbc.sql("SELECT id FROM engine.work_item WHERE LOWER(full_name) = LOWER(:r) AND ticket = :t ORDER BY created_at LIMIT 1")
            .param("r", repo).param("t", number).query(String::class.java).optional().map { get(it) }.orElse(null)

    fun blockers(id: String): List<String> =
        jdbc.sql("SELECT blocker FROM engine.work_item_dependency WHERE item = :id ORDER BY blocker").param("id", id)
            .query(String::class.java).list().filterNotNull()

    fun dependents(id: String): List<String> =
        jdbc.sql("SELECT item FROM engine.work_item_dependency WHERE blocker = :id ORDER BY item").param("id", id)
            .query(String::class.java).list().filterNotNull()

    /**
     * [item] is blocked by [blocker]: refused for an unknown item, itself, another repo's item or a cycle. An undone
     * blocker moves a `ready` item to `blocked`.
     */
    @Transactional
    fun addDependency(item: String, blocker: String) {
        val a = get(item) ?: throw WorkItemRefused("no work item $item")
        val b = get(blocker) ?: throw WorkItemRefused("no work item $blocker")
        if (item == blocker) throw WorkItemRefused("a work item cannot block itself")
        if (!a.repo.equals(b.repo, ignoreCase = true)) throw WorkItemRefused("$blocker is in ${b.repo}, not ${a.repo}")
        if (reaches(blocker, item)) throw WorkItemRefused("$blocker already waits on $item: a cycle")
        jdbc.sql("""INSERT INTO engine.work_item_dependency (item, blocker) SELECT :i, :b
                    WHERE NOT EXISTS (SELECT 1 FROM engine.work_item_dependency WHERE item = :i AND blocker = :b)""")
            .param("i", item).param("b", blocker).update()
        if (a.state == WorkItem.READY && b.state != WorkItem.DONE) setState(item, WorkItem.BLOCKED, "blocked by $blocker")
    }

    /** Whether [from] waits, directly or through others, on [to]. */
    private fun reaches(from: String, to: String): Boolean {
        val seen = mutableSetOf<String>()
        val todo = ArrayDeque(listOf(from))
        while (todo.isNotEmpty()) {
            val next = todo.removeFirst()
            if (next == to) return true
            if (seen.add(next)) todo.addAll(blockers(next))
        }
        return false
    }

    fun setState(id: String, state: String, why: String) {
        if (state !in WorkItem.STATES) throw WorkItemRefused("state is one of ${WorkItem.STATES.joinToString()}")
        val from = jdbc.sql("SELECT state FROM engine.work_item WHERE id = :id").param("id", id).query(String::class.java)
            .optional().orElse(null)
        jdbc.sql("UPDATE engine.work_item SET state = :s, updated_at = :at WHERE id = :id")
            .param("s", state).param("at", now()).param("id", id).update()
        appendEntry(id, WorkItemEntry.HISTORY, "engine", "$state: $why", true)
        changed(id)
        if (from != null && from != state && stateListeners.isNotEmpty()) {
            val item = get(id) ?: return
            afterCommit { stateListeners.forEach { it.onStateChanged(item, from, state) } }
        }
    }

    /** Runs [action] after the current transaction commits, or at once outside one. */
    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return action()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }

    override fun setState(id: String, state: TicketState, why: String) {
        known(id)
        setState(id, state.state, why)
    }

    private fun known(id: String): WorkItem = get(id) ?: throw WorkItemRefused("no work item $id")

    override fun comment(id: String, author: String, body: String): WorkItemEntry {
        known(id)
        return appendEntry(id, WorkItemEntry.CONVERSATION, author, body, true)
    }

    override fun setKind(id: String, kind: String) {
        known(id)
        if (kind !in WorkItem.KINDS || kind == WorkItem.ASK) throw WorkItemRefused("kind is one of ${(WorkItem.KINDS - WorkItem.ASK).joinToString()}")
        update(id, "kind", kind, "kind: $kind")
    }

    override fun setMode(id: String, mode: String?) {
        known(id)
        if (mode != null && mode.isBlank()) throw WorkItemRefused("mode is empty")
        update(id, "mode", mode, "mode: ${mode ?: "none"}")
    }

    override fun setTier(id: String, tier: Int?) {
        known(id)
        if (tier != null && tier !in 1..10) throw WorkItemRefused("tier is 1 to 10")
        update(id, "tier", tier, "tier: ${tier ?: "none"}")
    }

    /** Sets one of the fixed columns [update] is called with, never a caller's name. */
    private fun update(id: String, column: String, value: Any?, why: String) {
        jdbc.sql("UPDATE engine.work_item SET $column = :v, updated_at = :at WHERE id = :id")
            .param("v", value).param("at", now()).param("id", id).update()
        appendEntry(id, WorkItemEntry.HISTORY, "engine", why, true)
        changed(id)
    }

    override fun editBody(id: String, body: String, author: String) {
        val item = known(id)
        jdbc.sql("UPDATE engine.work_item SET description = :d, updated_at = :at WHERE id = :id")
            .param("d", "# ${item.title}\n\n${body.trimEnd()}").param("at", now()).param("id", id).update()
        appendEntry(id, WorkItemEntry.HISTORY, author, "body edited (was ${Tickets.bodyRev(item.body)})", true)
        changed(id)
    }

    override fun retitle(id: String, title: String, author: String) {
        val item = known(id)
        if (title.isBlank()) throw WorkItemRefused("title is empty")
        jdbc.sql("UPDATE engine.work_item SET description = :d, updated_at = :at WHERE id = :id")
            .param("d", "# ${title.trim()}\n\n${item.body.trimEnd()}").param("at", now()).param("id", id).update()
        appendEntry(id, WorkItemEntry.HISTORY, author, "title edited (was ${item.title})", true)
        changed(id)
    }

    override fun block(id: String, blocker: String) = addDependency(id, blocker)

    override fun unblock(id: String, blocker: String) = removeDependency(id, blocker)

    override fun details(id: String): WorkItem? = get(id)

    override fun kind(id: String): String? = get(id)?.kind

    override fun mode(id: String): String? = get(id)?.mode

    override fun state(id: String): String? = get(id)?.state

    override fun conversation(id: String): List<WorkItemEntry> = entries(id).filter { it.kind == WorkItemEntry.CONVERSATION }

    override fun history(id: String): List<WorkItemEntry> = entries(id).filter { it.kind == WorkItemEntry.HISTORY }

    /** Replaces [id]'s flags (a ticket's flag labels added or set). */
    override fun setFlags(id: String, flags: WorkItemFlags) {
        jdbc.sql("""UPDATE engine.work_item SET refactor = :rf, authorized = :az, needs_repro = :nrp, needs_review = :nrv,
                    updated_at = :at WHERE id = :id""")
            .param("rf", flags.refactor).param("az", flags.authorized).param("nrp", flags.needsRepro)
            .param("nrv", flags.needsReview).param("at", now()).param("id", id).update()
    }

    /**
     * Refines ask item [id] into the ticket the refiner drafted: its [kind] (not `ask`), [tier] and [mode] as fields and
     * [description] in place of the ask, with a `history` entry. Refused for an unknown item or one that is not an ask.
     */
    @Transactional
    fun refine(id: String, kind: String, tier: Int?, mode: String?, description: String): WorkItem {
        val item = get(id) ?: throw WorkItemRefused("no work item $id")
        if (item.kind != WorkItem.ASK) throw WorkItemRefused("$id is a ${item.kind}, not an ask")
        if (kind !in WorkItem.KINDS || kind == WorkItem.ASK) throw WorkItemRefused("kind is one of feature, bug, chore")
        if (tier != null && tier !in 1..10) throw WorkItemRefused("tier is 1 to 10")
        if (description.isBlank()) throw WorkItemRefused("description is empty")
        jdbc.sql("UPDATE engine.work_item SET kind = :k, tier = :tier, mode = :m, description = :d, updated_at = :at WHERE id = :id")
            .param("k", kind).param("tier", tier).param("m", mode).param("d", description).param("at", now()).param("id", id).update()
        appendEntry(id, WorkItemEntry.HISTORY, "engine", "refined into a $kind${tier?.let { " at tier $it" }.orEmpty()}", true)
        changed(id)
        return get(id)!!
    }

    /** The item made by importing [sourceRef] (e.g. `github:owner/name#12`), if one was. */
    fun forSourceRef(kind: String, sourceRef: String): WorkItem? =
        jdbc.sql("SELECT id FROM engine.work_item WHERE source_kind = :k AND LOWER(source_ref) = LOWER(:r) ORDER BY created_at LIMIT 1")
            .param("k", kind).param("r", sourceRef).query(String::class.java).optional().map { get(it) }.orElse(null)

    /** The item made from [sourceRef] in [repo], if one was: an intake's ask item there. */
    fun forSourceRef(kind: String, sourceRef: String, repo: String): WorkItem? =
        jdbc.sql("""SELECT id FROM engine.work_item WHERE source_kind = :k AND LOWER(source_ref) = LOWER(:r) AND LOWER(full_name) = LOWER(:repo)
                    ORDER BY created_at LIMIT 1""")
            .param("k", kind).param("r", sourceRef).param("repo", repo).query(String::class.java).optional().map { get(it) }.orElse(null)

    fun appendEntry(item: String, kind: String, author: String, body: String, trusted: Boolean): WorkItemEntry {
        if (kind !in WorkItemEntry.KINDS) throw WorkItemRefused("entry kind is one of ${WorkItemEntry.KINDS.joinToString()}")
        val e = WorkItemEntry(UUID.randomUUID().toString(), kind, author, body, trusted, now())
        jdbc.sql("""INSERT INTO engine.work_item_entry (id, item, entry_kind, author, body, trusted, created_at)
                    VALUES (:id, :i, :k, :a, :b, :t, :at)""")
            .param("id", e.id).param("i", item).param("k", kind).param("a", author).param("b", body).param("t", trusted)
            .param("at", e.createdAt).update()
        return e
    }

    fun entries(id: String): List<WorkItemEntry> =
        jdbc.sql("SELECT * FROM engine.work_item_entry WHERE item = :id ORDER BY created_at, id").param("id", id)
            .query { rs, _ ->
                WorkItemEntry(rs.getString("id"), rs.getString("entry_kind"), rs.getString("author"), rs.getString("body"),
                    rs.getBoolean("trusted"), rs.getObject("created_at", OffsetDateTime::class.java))
            }.list()

    private fun row(rs: ResultSet) = WorkItem(
        rs.getString("id"), rs.getString("full_name"), rs.getString("description"), rs.getString("kind"),
        rs.getObject("tier") as? Int ?: (rs.getObject("tier") as? Number)?.toInt(), rs.getString("mode"), rs.getString("state"),
        WorkItemSourceRef(rs.getString("source_kind"), rs.getString("source_ref")), rs.getBoolean("trusted"),
        rs.getObject("ticket")?.let { (it as Number).toLong() },
        rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("updated_at", OffsetDateTime::class.java),
        publishedRef = rs.getString("published_ref"), author = rs.getString("author"),
        flags = WorkItemFlags(rs.getBoolean("refactor"), rs.getBoolean("authorized"), rs.getBoolean("needs_repro"),
            rs.getBoolean("needs_review")))
}

/** Told of every work item create and state change, after the store wrote it; [WorkItemPublisher] is the one. */
fun interface WorkItemListener {
    fun changed(item: WorkItem)
}
