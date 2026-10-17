package com.codefrak.engine.friction

import com.codefrak.engine.intake.DuplicateCheck
import com.codefrak.engine.jev.HttpJevDecider
import com.codefrak.engine.jev.JevAnswer
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.jev.JevDecider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Groups friction notes after they are written (engine/README.md, "Friction notes"):
 * one worker thread, `friction-grouper`, the only thing that ever groups a note. A [wake] queues one
 * [drain] on it when none is queued; [FrictionNotes.insert] pokes it after its transaction commits and
 * [FrictionGroupTick] every `engine.friction.group-interval`, and neither drains on its own thread. A drain takes the
 * ungrouped notes due now, oldest first, and for each runs the steps in [group], the writes in one transaction of
 * their own, so a failing note rolls back alone and the next proceeds:
 *
 * 1. a key already in `engine.friction_signature` joins its group as `exact`, with no Jev call;
 * 2. a new key with no groups founds one as `new`, with no call;
 * 3. otherwise one `friction-group` call ([FrictionGroupCheck]): its probabilities are summed over clusters of
 *    near-duplicate groups ([FrictionGroupCheck.clusters]), and when the winning cluster clearly beats `new` (at least
 *    `engine.jev.friction-group-beats-new` times `new`'s probability) the note joins the cluster's oldest group as `jev`
 *    and adds the key to that group's signatures; otherwise it founds a group as `new`;
 * 4. no answer: the note waits, its `group_attempts` up by one and `next_attempt_at` set by [backoff]; after
 *    `engine.friction.group-give-up` from `noted_at` it founds its own group as `fallback`; with no usable jev account
 *    it founds its own group at once as `no-jev`.
 *
 * The Jev call is made outside the note's transaction, so a group write that fails after an answer leaves the call row
 * (one duplicate in the audit on the re-drain, marked by its action) and never a duplicate group: step 1 reads the
 * signature table first, and step 3 writes the group and the signature together. The engine runs as one instance; if
 * that ever differs, the drain takes `SELECT … FOR UPDATE SKIP LOCKED` on the row.
 *
 * The call whose pick counts also carries the `two-things` `noul` ([FrictionGroupCheck.twoThingsQuestion]); a note that
 * makes no group call (steps 1 and 2) gets it alone, use case `friction-two-things`. At `engine.jev.friction-two-things`
 * or above the note is marked in the same transaction as its grouping, and the mark's bug follows ([FrictionBugs]); no
 * answer is no mark and is not retried.
 *
 * A drain ends with the [sweep]: `kept` on every `friction-group` call 30 days old with no outcome whose note still
 * sits where the call put it, and `two things: kept` on every lone `friction-two-things` call that old whose mark stands.
 *
 * `engine.friction.group-enabled` false (the tests' properties) makes [wake] a no-op and leaves [FrictionGroupTick]
 * out, so a test drains by hand.
 */
@Component
class FrictionGrouper(
    private val groups: FrictionGroups,
    private val jev: JevDecider,
    private val calls: JevCalls,
    private val bugs: FrictionBugs,
    transactions: PlatformTransactionManager,
    @Value("\${engine.jev.friction-group-beats-new:2.0}") private val beatsNew: Double = 2.0,
    @Value("\${engine.jev.friction-two-things:0.8}") private val twoThingsFloor: Double = 0.8,
    @Value("\${engine.friction.group-give-up:PT24H}") private val giveUp: Duration = Duration.ofHours(24),
    @Value("\${engine.friction.group-enabled:true}") private val enabled: Boolean = true,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, THREAD).apply { isDaemon = true } }
    private val queued = AtomicBoolean(false)
    private val write = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    companion object {
        const val THREAD = "friction-grouper"
        /** What [group] answers for a note left for a later drain. */
        const val DEFERRED = "deferred"
        private val BACKOFF = listOf(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1))
        /** How old a `friction-group` call with no outcome is when the [sweep] writes `kept` on it. */
        val KEPT_AFTER: Duration = Duration.ofDays(30)

        /** How long a note waits after its [attempts]th no answer: 1, 5, 15 and 60 minutes, then hourly. */
        fun backoff(attempts: Int): Duration = BACKOFF[(attempts - 1).coerceIn(0, BACKOFF.size - 1)]
    }

    /** Queues a drain on the worker thread unless one is queued already (the queued one will see the row). */
    fun wake() {
        if (!enabled) return
        if (!queued.compareAndSet(false, true)) return
        worker.execute {
            queued.set(false)
            runCatching { drain() }.onFailure { log.warn("friction grouper drain failed: {}", it.message) }
        }
    }

    /** Waits until the worker thread has run everything queued before now; for tests. */
    fun settle(timeout: Duration = Duration.ofSeconds(30)) {
        worker.submit {}.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** Groups every ungrouped note due at [now], oldest first, then runs the [sweep]; answers how many notes it looked at. */
    fun drain(now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): Int {
        val due = groups.due(now)
        for (pending in due) {
            runCatching { group(pending, now) }
                .onFailure { log.warn("friction note {} not grouped this drain: {}", pending.note.id, it.message) }
        }
        runCatching { sweep(now) }.onFailure { log.warn("friction kept sweep failed: {}", it.message) }
        return due.size
    }

    /**
     * The 30-day `kept` sweep (engine/README.md, "Friction notes"): every `friction-group` call [KEPT_AFTER] old
     * with no outcome whose note is still in the group the call put it in (its action's group, [FrictionGroupCheck.placed])
     * gets `joined g12: kept` for a `jev` note and `new g31: kept` for a `new` one. A note moved since (a merge) is
     * left for no later sweep: its group is gone. Answers how many outcomes it wrote.
     */
    fun sweep(now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): Int {
        var written = 0
        for (u in groups.unjudged(now.minus(KEPT_AFTER))) {
            val placed = FrictionGroupCheck.placed(u.action) ?: continue
            if (placed != FrictionGroupCheck.name(u.groupId)) continue
            val outcome = if (u.groupedBy == GroupedBy.JEV) FrictionGroupCheck.kept(placed) else FrictionGroupCheck.newKept(placed)
            if (runCatching { calls.recordOutcome(u.call, outcome) }.onFailure { log.warn("jev friction-group {}: outcome not recorded: {}", u.call, it.message) }.getOrDefault(false)) written++
        }
        for (call in groups.unjudgedTwoThings(now.minus(KEPT_AFTER))) {
            if (runCatching { calls.recordOutcome(call, FrictionGroupCheck.twoThingsKept()) }.onFailure { log.warn("jev friction-two-things {}: outcome not recorded: {}", call, it.message) }.getOrDefault(false)) written++
        }
        return written
    }

    /** One answer to the `two-things` question and the call it came on; [mark] is the value when it marks the note. */
    private class TwoThings(val call: String, val answer: JevAnswer?, floor: Double, bugs: FrictionBugs) {
        val mark: Double? = bugs.marks(answer, floor)
    }

    /** The lone `friction-two-things` call for a note that makes no group call; made outside the transaction, as the group call is. */
    private fun askTwoThings(note: FrictionNote, context: JevContext): TwoThings {
        val decision = jev.decide(FrictionGroupCheck.TWO_THINGS_USE_CASE, context, FrictionGroupCheck.state(note),
            mapOf(FrictionGroupCheck.TWO_THINGS to FrictionGroupCheck.twoThingsQuestion()), twoThingsFloor)
        return TwoThings(decision.callId, decision.answers?.get(FrictionGroupCheck.TWO_THINGS), twoThingsFloor, bugs)
    }

    /** Inside the note's transaction: the mark when [two] marks, and the bug's claim; null when nothing was marked. */
    private fun mark(note: FrictionNote, groupId: Long, two: TwoThings?): Marked? =
        two?.mark?.let { bugs.mark(note, groupId, it, two.call) }

    /** After the transaction: the lone call's action, and what the mark starts. */
    private fun marked(note: FrictionNote, two: TwoThings?, marked: Marked?, lone: Boolean) {
        if (two == null) return
        if (lone) act(two.call, if (two.mark != null) "marked" + FrictionGroupCheck.twoThingsAction(two.mark) else "no mark")
        marked?.let { bugs.after(note, it) }
    }

    /** The steps above for one note; answers its `grouped_by`, or [DEFERRED]. */
    fun group(pending: PendingNote, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): String {
        val note = pending.note
        val signature = note.signature ?: FrictionKey.of(note.summary, note.evidence)
        val context = JevContext(note.processInstanceId, note.repo, note.issue)
        groups.groupOf(signature)?.let { known ->
            val two = askTwoThings(note, context)
            val marked = write.execute { groups.join(note.id, known, GroupedBy.EXACT, null); mark(note, known, two) }
            marked(note, two, marked, lone = true)
            return GroupedBy.EXACT
        }
        val options = groups.options()
        if (options.isEmpty()) {
            val two = askTwoThings(note, context)
            val marked = write.execute { mark(note, groups.found(note, GroupedBy.NEW, null, now), two) }
            marked(note, two, marked, lone = true)
            return GroupedBy.NEW
        }
        // The `two-things` answer of the call whose pick counts, kept beside the stages' one answer.
        var two: TwoThings? = null
        val verdict = FrictionGroupCheck.run(FrictionGroupCheck.candidates(options),
            ask = { batch, final ->
                val questions = mapOf(FrictionGroupCheck.QUESTION to FrictionGroupCheck.question(batch)) +
                    if (final) mapOf(FrictionGroupCheck.TWO_THINGS to FrictionGroupCheck.twoThingsQuestion()) else emptyMap()
                val decision = jev.decide(FrictionGroupCheck.USE_CASE, context, FrictionGroupCheck.state(note), questions, beatsNew)
                if (final) two = TwoThings(decision.callId, decision.answers?.get(FrictionGroupCheck.TWO_THINGS), twoThingsFloor, bugs)
                DuplicateCheck.Asked(decision.callId, decision.answers?.get(FrictionGroupCheck.QUESTION))
            },
            act = ::act)
        val joined = verdict.joins(beatsNew)
        // What the group action gains when the note is marked.
        val suffix = two?.mark?.let { FrictionGroupCheck.twoThingsAction(it) }.orEmpty()
        return when {
            verdict.noAnswer -> {
                val noAccount = runCatching { calls.get(verdict.call)?.error }.getOrNull() == HttpJevDecider.NO_ACCOUNT
                val by = when {
                    noAccount -> GroupedBy.NO_JEV
                    !now.isBefore(note.notedAt.plus(giveUp)) -> GroupedBy.FALLBACK
                    else -> DEFERRED
                }
                val founded = if (by == DEFERRED) {
                    val attempts = pending.attempts + 1
                    write.executeWithoutResult { groups.defer(note.id, attempts, now.plus(backoff(attempts))) }
                    null
                } else write.execute { groups.found(note, by, verdict.call, now) }
                // No answer to the group question is no answer to the second: nothing is marked.
                verdict.action(beatsNew, founded)?.let { act(verdict.call, it) }
                by
            }
            joined != null -> {
                val marked = write.execute {
                    groups.join(note.id, joined.id, GroupedBy.JEV, verdict.call)
                    groups.addSignature(note, joined.id, now)
                    mark(note, joined.id, two)
                }
                verdict.action(beatsNew, null)?.let { act(verdict.call, it + suffix) }
                marked(note, two, marked, lone = false)
                GroupedBy.JEV
            }
            else -> {
                val (founded, marked) = write.execute { groups.found(note, GroupedBy.NEW, verdict.call, now).let { it to mark(note, it, two) } }!!
                verdict.action(beatsNew, founded)?.let { act(verdict.call, it + suffix) }
                marked(note, two, marked, lone = false)
                GroupedBy.NEW
            }
        }
    }

    /** The audit is not the decision: a failed write must not throw the grouping away. */
    private fun act(call: String, action: String) {
        runCatching { calls.setAction(call, action) }
            .onFailure { log.warn("jev friction-group {}: action not recorded: {}", call, it.message) }
    }

    override fun destroy() {
        worker.shutdownNow()
    }
}
