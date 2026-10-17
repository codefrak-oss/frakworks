package com.codefrak.engine.doctor

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A doctor bug as a sighting meets it: its row's id, repo, issue (null while its intake refines) and state. */
data class DoctorBugRef(val id: Long, val repo: String, val issue: Long?, val state: String)

/** One signature on a doctor bug, as `GET /doctor/bugs` shows it. */
data class DoctorBugSignature(val signature: String, val where: String, val leaves: List<String>, val firstSeen: OffsetDateTime)

/**
 * One row of `GET /doctor/bugs` (engine/README.md, "Doctor"): the bug's repo, issue (null while its intake refines, and
 * for one declined before it filed), `state` (`filing`, `open`, `needs-repro`, `fixed` or `declined`), the intake that
 * filed it, its signatures, `tickets` (the distinct stuck tickets with a sighting on it) and `runs` (its sightings), and
 * `lastSeen`, its newest sighting.
 */
data class DoctorBug(
    val id: Long, val repo: String, val issue: Long?, val state: String, val intakeId: String?, val createdAt: OffsetDateTime,
    val closedAt: OffsetDateTime?, val signatures: List<DoctorBugSignature>, val tickets: Long, val runs: Long, val lastSeen: OffsetDateTime?,
)

/** `engine.doctor_bug` (engine/README.md, "Doctor"), and the sightings and signatures lumped into one. */
@Repository
class DoctorBugStore(private val jdbc: JdbcClient) : DoctorBugLinks {
    private companion object {
        val REF = { rs: ResultSet, _: Int -> DoctorBugRef(rs.getLong(1), rs.getString(2), rs.getObject(3, Long::class.javaObjectType), rs.getString(4)) }
    }

    /** The bug the signature [hash] is on, or null when it is on none. */
    fun ofSignature(hash: String): DoctorBugRef? =
        jdbc.sql("""SELECT b.id, b.repo, b.issue, b.state FROM engine.doctor_signature s JOIN engine.doctor_bug b ON b.id = s.bug_id
            WHERE s.signature = :h""").param("h", hash).query(REF).optional().orElse(null)

    /** The doctor bug on [repo]#[issue], or null when that issue is none. */
    fun ofIssue(repo: String, issue: Long): DoctorBugRef? =
        jdbc.sql("SELECT id, repo, issue, state FROM engine.doctor_bug WHERE LOWER(repo) = LOWER(:r) AND issue = :i ORDER BY id")
            .param("r", repo).param("i", issue).query(REF).list().firstOrNull()

    /**
     * A new `filing` bug in [repo] holding the signature [hash], when the signature is on no bug yet (or, for a
     * regression, still on the fixed bug [regressionOf], which it moves from and names); null when another run took it first (its update then finds `bug_id` set and this bug goes again). In its own transaction, so the
     * claim is there for the intake started next, on its own thread.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(repo: String, hash: String, regressionOf: Long? = null): Long? {
        val from = if (regressionOf == null) "bug_id IS NULL" else "bug_id = $regressionOf"
        val id = insertTaking(repo, null, "filing", hash, from) ?: return null
        if (regressionOf != null) jdbc.sql("UPDATE engine.doctor_bug SET regression_of = :o WHERE id = :id").param("o", regressionOf).param("id", id).update()
        return id
    }

    /**
     * A `fixed` bug in [repo] on [issue] (the ticket the merged fix closed, or the fix's PR), closed at [mergedAt], that
     * takes the signature [hash] and the sighting [sightingId] (`lumped_by: merged-fix`): a fix that merged after the
     * episode started, so the next sighting is a leftover until the engine restarts on it and a regression after
     * ([DoctorBugs] "After a fix"). Null when another run put the signature on a bug first; nothing is changed then.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fixedBy(sightingId: Long, hash: String, repo: String, issue: Long, mergedAt: OffsetDateTime): DoctorBugRef? {
        val id = insertTaking(repo, issue, "fixed", hash, "bug_id IS NULL") ?: return null
        jdbc.sql("UPDATE engine.doctor_bug SET closed_at = :at WHERE id = :id").param("at", mergedAt).param("id", id).update()
        lump(sightingId, id, "merged-fix")
        return DoctorBugRef(id, repo, issue, "fixed")
    }

    /** Undoes [claim] for an intake that did not start: its signature is back on no bug (or the fixed bug it regressed), its sightings on none, and the row goes. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun unclaim(id: Long) {
        jdbc.sql("""UPDATE engine.doctor_signature SET bug_id = (SELECT regression_of FROM engine.doctor_bug WHERE id = :b)
                WHERE bug_id = :b""").param("b", id).update()
        jdbc.sql("UPDATE engine.doctor_sighting SET bug_id = NULL, lumped_by = NULL WHERE bug_id = :b").param("b", id).update()
        jdbc.sql("DELETE FROM engine.doctor_bug WHERE id = :id").param("id", id).update()
    }

    /** The intake that files the bug [id]. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun started(id: Long, intake: String) {
        jdbc.sql("UPDATE engine.doctor_bug SET intake_id = :in WHERE id = :id").param("in", intake).param("id", id).update()
    }

    /** Lumps the sighting [sightingId] into the bug [bugId]; `by` is `exact` or `new`, and [call] the Jev call that said `new`. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun lump(sightingId: Long, bugId: Long, by: String, call: String? = null) {
        jdbc.sql("UPDATE engine.doctor_sighting SET bug_id = :b, lumped_by = :by, jev_call_id = :c WHERE id = :id")
            .param("b", bugId).param("by", by).param("c", call).param("id", sightingId).update()
    }

    /**
     * Jev's lump (`lumped_by: jev`): the sighting [sightingId] into [repo]#[issue]'s doctor bug, made (`open`) when the
     * issue has none, and the signature [hash] onto that bug, so the next sighting of it is exact. Null when another run
     * put the signature on a bug first; nothing is changed then.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun lumpByJev(sightingId: Long, hash: String, repo: String, issue: Long, call: String): DoctorBugRef? {
        val known = ofIssue(repo, issue)
        val id = insertTaking(repo, issue, "open", hash, "bug_id IS NULL", existing = known?.id) ?: return null
        jdbc.sql("UPDATE engine.doctor_sighting SET bug_id = :b, lumped_by = 'jev', jev_call_id = :c WHERE id = :id")
            .param("b", id).param("c", call).param("id", sightingId).update()
        return known ?: DoctorBugRef(id, repo, issue, "open")
    }

    /**
     * The doctor's filed bugs in [repo] closed at or after [since], newest close first, each with its sightings'
     * summaries (the `doctor-sighting` options, [SightingCheck.ClosedBug]; the title is GitHub's, filled by the caller).
     */
    fun closedSince(repo: String, since: OffsetDateTime): List<SightingCheck.ClosedBug> {
        val bugs = jdbc.sql("""SELECT id, issue FROM engine.doctor_bug WHERE LOWER(repo) = LOWER(:r) AND issue IS NOT NULL
                AND state IN ('fixed', 'declined') AND closed_at >= :s ORDER BY closed_at DESC, id DESC""")
            .param("r", repo).param("s", since).query { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
        return bugs.map { (id, issue) ->
            SightingCheck.ClosedBug(issue, null, jdbc.sql("SELECT summary FROM engine.doctor_sighting WHERE bug_id = :b AND summary IS NOT NULL ORDER BY id DESC")
                .param("b", id).query { rs, _ -> rs.getString(1) }.list())
        }
    }

    /** The Jev calls behind the sightings on the bug [bugId], as (`lumped_by`, call): `jev` lumps and the `new` that filed it. */
    fun jevCalls(bugId: Long): List<Pair<String, String>> =
        jdbc.sql("""SELECT lumped_by, jev_call_id FROM engine.doctor_sighting WHERE bug_id = :b AND jev_call_id IS NOT NULL
                AND lumped_by IN ('jev', 'new') ORDER BY id""")
            .param("b", bugId).query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()

    /** One sighting as `PATCH /doctor/sightings/{id}` reads it: its run, stuck ticket, signature, bug, how it was lumped and its Jev call. */
    data class Lumped(
        val id: Long, val doctorRunId: String, val repo: String, val signature: String, val bug: DoctorBugRef?,
        val lumpedBy: String?, val call: String?,
    )

    fun lumped(sightingId: Long): Lumped? =
        jdbc.sql("""SELECT s.id, s.doctor_run_id, s.repo, s.signature, b.id, b.repo, b.issue, b.state, s.lumped_by, s.jev_call_id
                FROM engine.doctor_sighting s LEFT JOIN engine.doctor_bug b ON b.id = s.bug_id WHERE s.id = :id""")
            .param("id", sightingId).query { rs, _ ->
                val bug = rs.getObject(5, Long::class.javaObjectType)?.let {
                    DoctorBugRef(it, rs.getString(6), rs.getObject(7, Long::class.javaObjectType), rs.getString(8))
                }
                Lumped(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), bug, rs.getString(9), rs.getString(10))
            }.optional().orElse(null)

    /**
     * A human's move (`lumped_by: human`): the sighting [s], its signature, and the other sightings of that signature
     * on the same bug, to [repo]#[issue]'s doctor bug (made `open` when the issue has none), or off any bug when
     * [repo] is null. Answers the bug they moved to.
     */
    @Transactional
    fun move(s: Lumped, repo: String?, issue: Long?): DoctorBugRef? {
        val to = if (repo == null || issue == null) null else ofIssue(repo, issue) ?: DoctorBugRef(insert(repo, issue, "open"), repo, issue, "open")
        jdbc.sql("UPDATE engine.doctor_signature SET bug_id = CAST(:to AS BIGINT) WHERE signature = :h")
            .param("to", to?.id).param("h", s.signature).update()
        val others = if (s.bug == null) "bug_id IS NULL" else "bug_id = ${s.bug.id}"
        jdbc.sql("""UPDATE engine.doctor_sighting SET bug_id = CAST(:to AS BIGINT), lumped_by = 'human'
                WHERE id = :id OR (signature = :h AND $others)""")
            .param("to", to?.id).param("id", s.id).param("h", s.signature).update()
        return to
    }

    /** The bug [id]'s intake filed it as [issue]: `open`, from `filing`. */
    override fun filed(id: Long, issue: Long) {
        jdbc.sql("UPDATE engine.doctor_bug SET issue = :i, state = 'open' WHERE id = :id AND state = 'filing'")
            .param("i", issue).param("id", id).update()
    }

    /** The bug [id]'s intake was cancelled before it filed: `declined`, with no issue; it keeps its signature. */
    override fun declineUnfiled(id: Long) {
        jdbc.sql("UPDATE engine.doctor_bug SET state = 'declined', closed_at = CURRENT_TIMESTAMP WHERE id = :id AND issue IS NULL")
            .param("id", id).update()
    }

    /** The filed bugs whose close the doctor waits to see: `open` and `needs-repro`. */
    fun watched(): List<DoctorBugRef> =
        jdbc.sql("SELECT id, repo, issue, state FROM engine.doctor_bug WHERE issue IS NOT NULL AND state IN ('open', 'needs-repro') ORDER BY id")
            .query(REF).list()

    /** The bug [id] closed, as [state] (`fixed` or `declined`). */
    fun close(id: Long, state: String, at: OffsetDateTime?) {
        jdbc.sql("UPDATE engine.doctor_bug SET state = :s, closed_at = :at WHERE id = :id")
            .param("s", state).param("at", at ?: OffsetDateTime.now(ZoneOffset.UTC)).param("id", id).update()
    }

    /**
     * The bug [from] closed as a duplicate of [repo]#[issue]: its signatures and sightings move to that issue's doctor
     * bug, made (`open`) when it has none, and [from] is left `declined`, holding nothing. Answers the bug they moved to.
     */
    @Transactional
    fun moveTo(from: Long, repo: String, issue: Long, at: OffsetDateTime?): Long {
        val to = ofIssue(repo, issue)?.id ?: insert(repo, issue, "open")
        if (to == from) return to
        jdbc.sql("UPDATE engine.doctor_signature SET bug_id = :to WHERE bug_id = :from").param("to", to).param("from", from).update()
        jdbc.sql("UPDATE engine.doctor_sighting SET bug_id = :to WHERE bug_id = :from").param("to", to).param("from", from).update()
        close(from, "declined", at)
        return to
    }

    /** Whether the stuck flow [rootInstanceId] had a sighting of [hash] before [sightingId]: the same episode, already commented. */
    fun seenBefore(rootInstanceId: String, hash: String, sightingId: Long): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM engine.doctor_sighting WHERE root_instance_id = :root AND signature = :h AND id < :id")
            .param("root", rootInstanceId).param("h", hash).param("id", sightingId).query(Long::class.java).single() > 0

    /** Whether the sighting [sightingId]'s bug is `fixed`, closed after [since]: the back-off on its place is lifted. */
    fun fixedSince(sightingId: Long, since: OffsetDateTime): Boolean =
        jdbc.sql("""SELECT COUNT(*) FROM engine.doctor_sighting s JOIN engine.doctor_bug b ON b.id = s.bug_id
                WHERE s.id = :id AND b.state = 'fixed' AND b.closed_at > :since""")
            .param("id", sightingId).param("since", since).query(Long::class.java).single() > 0

    /** What the doctor keeps on a bug besides its state: the repeat steps taken, the reproduces restarted, and its close. */
    data class Marks(val repeatLevel: Int, val restarts: Int, val reviewAt: OffsetDateTime?, val closedAt: OffsetDateTime?, val fixRunningAt: OffsetDateTime?)

    fun marks(id: Long): Marks =
        jdbc.sql("SELECT repeat_level, restarts, review_at, closed_at, fix_running_at FROM engine.doctor_bug WHERE id = :id").param("id", id)
            .query { rs, _ -> Marks(rs.getInt(1), rs.getInt(2), rs.getObject(3, OffsetDateTime::class.java),
                rs.getObject(4, OffsetDateTime::class.java), rs.getObject(5, OffsetDateTime::class.java)) }.single()

    /** Whether [sightingId] is the first sighting on the bug [bugId] from [repo]#[issue]: a new stuck ticket for it. */
    fun newTicket(bugId: Long, sightingId: Long, repo: String, issue: Long): Boolean =
        jdbc.sql("""SELECT COUNT(*) FROM engine.doctor_sighting WHERE bug_id = :b AND id < :id AND LOWER(repo) = LOWER(:r) AND issue = :i""")
            .param("b", bugId).param("id", sightingId).param("r", repo).param("i", issue).query(Long::class.java).single() == 0L

    /** The bug [bugId]'s distinct stuck tickets and its sightings (its runs). */
    fun counts(bugId: Long): Pair<Long, Long> =
        jdbc.sql("""SELECT COUNT(DISTINCT CONCAT(LOWER(repo), '#', CAST(issue AS VARCHAR(20)))), COUNT(*) FROM engine.doctor_sighting WHERE bug_id = :b""")
            .param("b", bugId).query { rs, _ -> rs.getLong(1) to rs.getLong(2) }.single()

    /** Takes the repeat step for [tickets] distinct tickets on the bug [id]; false when it was taken already (by this or another run). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun stepRepeat(id: Long, tickets: Long): Boolean =
        jdbc.sql("UPDATE engine.doctor_bug SET repeat_level = :k WHERE id = :id AND repeat_level < :k")
            .param("k", tickets.toInt()).param("id", id).update() == 1

    /** Counts one restarted reproduce on the bug [id]; false when it had [max] already. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun restarted(id: Long, max: Int): Boolean =
        jdbc.sql("UPDATE engine.doctor_bug SET restarts = restarts + 1 WHERE id = :id AND restarts < :max")
            .param("id", id).param("max", max).update() == 1

    /** Undoes [restarted] for a reproduce that did not start. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun unrestarted(id: Long) {
        jdbc.sql("UPDATE engine.doctor_bug SET restarts = restarts - 1 WHERE id = :id AND restarts > 0").param("id", id).update()
    }

    /** The bug [id] got `needs-review`: its `GET /needs` `sighting` row, from now. */
    fun review(id: Long) {
        jdbc.sql("UPDATE engine.doctor_bug SET review_at = CURRENT_TIMESTAMP WHERE id = :id AND review_at IS NULL").param("id", id).update()
    }

    /** The open bugs the doctor put `needs-review` on, oldest first: `GET /needs`'s `sighting` rows. */
    fun reviewing(): List<Pair<DoctorBugRef, OffsetDateTime>> =
        jdbc.sql("""SELECT id, repo, issue, state, review_at FROM engine.doctor_bug WHERE issue IS NOT NULL AND review_at IS NOT NULL
                AND state IN ('open', 'needs-repro') ORDER BY review_at, id""")
            .query { rs, i -> REF(rs, i) to rs.getObject(5, OffsetDateTime::class.java) }.list()

    /** The bug flow on [repo]#[issue] labelled it `needs-repro`: its doctor bug, if it has one and is `open`, is `needs-repro`. */
    override fun needsRepro(repo: String, issue: Long): Boolean =
        jdbc.sql("UPDATE engine.doctor_bug SET state = 'needs-repro' WHERE LOWER(repo) = LOWER(:r) AND issue = :i AND state = 'open'")
            .param("r", repo).param("i", issue).update() > 0

    /** The `needs-repro` label came off the bug [id]: `open` again. */
    fun reproOff(id: Long) {
        jdbc.sql("UPDATE engine.doctor_bug SET state = 'open' WHERE id = :id AND state = 'needs-repro'").param("id", id).update()
    }

    /** The engine started at [at]: each bug closed as fixed before it, with no start since its close, has its fix running from [at]. */
    fun fixRunning(at: OffsetDateTime): Int =
        jdbc.sql("UPDATE engine.doctor_bug SET fix_running_at = :at WHERE state = 'fixed' AND fix_running_at IS NULL AND closed_at < :at")
            .param("at", at).update()

    /** One sighting on a bug, as the restarted reproduce's brief lists it. */
    data class Seen(val repo: String, val issue: Long, val kind: String, val seenAt: OffsetDateTime, val summary: String?)

    /** The bug [bugId]'s sightings (repo, issue, kind, seen_at, summary), oldest first. */
    fun seen(bugId: Long): List<Seen> =
        jdbc.sql("SELECT repo, issue, kind, seen_at, summary FROM engine.doctor_sighting WHERE bug_id = :b ORDER BY id")
            .param("b", bugId).query { rs, _ -> Seen(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getObject(4, OffsetDateTime::class.java), rs.getString(5)) }
            .list()

    /** Every doctor bug whose newest sighting (or, with none, its creation) is at or after [since], newest first. */
    fun since(since: OffsetDateTime): List<DoctorBug> {
        val signatures = jdbc.sql("""SELECT bug_id, signature, "where", leaves, first_seen FROM engine.doctor_signature
                WHERE bug_id IS NOT NULL ORDER BY first_seen""")
            .query { rs, _ -> rs.getLong(1) to DoctorBugSignature(rs.getString(2), rs.getString(3),
                rs.getString(4).split(',').filter { it.isNotEmpty() }, rs.getObject(5, OffsetDateTime::class.java)) }
            .list().groupBy({ it.first }, { it.second })
        val list = jdbc.sql("""SELECT b.id, b.repo, b.issue, b.state, b.intake_id, b.created_at, b.closed_at,
                (SELECT COUNT(DISTINCT CONCAT(x.repo, '#', CAST(x.issue AS VARCHAR(20)))) FROM engine.doctor_sighting x WHERE x.bug_id = b.id),
                (SELECT COUNT(*) FROM engine.doctor_sighting x WHERE x.bug_id = b.id),
                (SELECT MAX(x.seen_at) FROM engine.doctor_sighting x WHERE x.bug_id = b.id)
            FROM engine.doctor_bug b""")
            .query { rs, _ ->
                val id = rs.getLong(1)
                DoctorBug(id, rs.getString(2), rs.getObject(3, Long::class.javaObjectType), rs.getString(4), rs.getString(5),
                    rs.getObject(6, OffsetDateTime::class.java), rs.getObject(7, OffsetDateTime::class.java), signatures[id].orEmpty(),
                    rs.getLong(8), rs.getLong(9), rs.getObject(10, OffsetDateTime::class.java))
            }.list()
        return bugsSince(list, since)
    }

    private fun insert(repo: String, issue: Long?, state: String): Long {
        val keys = GeneratedKeyHolder()
        jdbc.sql("INSERT INTO engine.doctor_bug (repo, issue, state) VALUES (:r, CAST(:i AS BIGINT), :s)")
            .param("r", repo).param("i", issue).param("s", state).update(keys, "id")
        return (keys.keys!!.entries.first { it.key.equals("id", ignoreCase = true) }.value as Number).toLong()
    }

    /**
     * The compare-and-set that gives one bug the signature [hash]: a new bug in [repo]#[issue] with [state] (or,
     * with [existing], that bug reused instead of a new one), taking the signature where [from] says it is still
     * free. Answers the bug's id when the signature was taken, or null when another run took it first ([existing]
     * is left in place then; a bug this call inserted is undone).
     */
    private fun insertTaking(repo: String, issue: Long?, state: String, hash: String, from: String, existing: Long? = null): Long? {
        val id = existing ?: insert(repo, issue, state)
        val took = jdbc.sql("UPDATE engine.doctor_signature SET bug_id = :b WHERE signature = :h AND $from")
            .param("b", id).param("h", hash).update() == 1
        if (!took) {
            if (existing == null) jdbc.sql("DELETE FROM engine.doctor_bug WHERE id = :id").param("id", id).update()
            return null
        }
        return id
    }
}

/** [bugs] at or after [since] (by [DoctorBug.lastSeen], falling back to [DoctorBug.createdAt]), newest first, then by id descending. */
internal fun bugsSince(bugs: List<DoctorBug>, since: OffsetDateTime): List<DoctorBug> =
    bugs.filter { !(it.lastSeen ?: it.createdAt).isBefore(since) }
        .sortedWith(compareByDescending<DoctorBug> { it.lastSeen ?: it.createdAt }.thenByDescending { it.id })
