package com.codefrak.engine.friction

import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.repos.TargetRepos
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A friction group's fixed-by link (engine/README.md, "Friction notes", "Fixed by"): the one ticket that fixes the group,
 * set when the engine files a ticket naming `friction group g<id>` ([filed]), by the [sync] over the target repos' issues
 * (the one-time backfill too), or by a user or First mate ([link], [clear]); its state follows the ticket on the
 * [sync], every `engine.doctor.bug-sync`, or on demand ([syncNow]: `POST /friction/fixes/sync` and the diagnostics
 * server's `sync_friction_fixes`), one pass at a time. A note after the fix merged marks the group recurred ([FrictionGroupFixes.recur]).
 */
@Component
class FrictionFixes(private val groups: FrictionGroups, private val groupFixes: FrictionGroupFixes, private val repos: TargetRepos,
                    private val details: IssueDetails, private val intakes: IntakeFates) : FrictionFixLinks, FrictionFixSync, FrictionFixFilings {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)

    companion object {
        /** The closed issues the sync reads per repo, for links to tickets that do not name their group. */
        const val CLOSED_LIMIT = 100

        /** The labels of an open ask (an intake's ask item, `ask`; an older intake issue, `intake`), which names groups in its ask but is not their fix. */
        val INTAKE_LABELS = setOf("ask", "intake")

        fun key(repo: String, issue: Long) = "$repo#$issue"
    }

    /**
     * The engine filed [repo]#[issue] with [body]: each group the body names is linked to it as `fix-filed`, unless it
     * has no row or is linked to another ticket (both logged). Answers the groups linked.
     */
    override fun filed(repo: String, issue: Long, body: String): List<Long> = groupsFrom(repo, issue, FrictionFixRules.groupsNamed(body), null, Pass())
        .linked.map { it.groupId }

    /**
     * Intake [intake] filed [repo]#[issue]: each group it is the pending candidate fix of is linked to that ticket as
     * `fix-filed` (engine/README.md, "Friction notes", "Fixed by"). Answers the groups linked.
     */
    override fun intakeFiled(intake: String, repo: String, issue: Long): List<Long> = groupFixes.pendings().filter { it.intake == intake }.map {
        groupFixes.setFix(it.groupId, repo, issue, FrictionFix.FILED, null)
        log.info("friction fixes: {}'s pending intake {} filed {}#{}", FrictionGroupCheck.name(it.groupId), intake, repo, issue)
        it.groupId
    }

    /**
     * A Smith's, First mate's or a user's link of a candidate fix by intake id. The group is [groupId], or [noteId]'s.
     * An intake that already filed links its ticket at once (`fix-filed`); one still running makes the group
     * `fix-pending`; one that ended without a ticket is refused. A group linked to a ticket is refused: its link is
     * cleared first (`PATCH /friction/groups/{id}` with `fix: null`), never overwritten by a pending one.
     */
    override fun linkIntake(groupId: Long?, noteId: String?, intake: String, by: String): FrictionGroup {
        val id = groupId ?: noteId?.let { n ->
            val note = groups.note(n) ?: throw FixIntakeRefused("no friction note $n", notFound = true)
            note.groupId ?: throw FixIntakeRefused("friction note $n is not grouped yet; try again once the grouper has placed it", notFound = false)
        } ?: throw FixIntakeRefused("a group or a note is needed", notFound = false)
        if (!groups.exists(id)) throw FixIntakeRefused("no friction group ${FrictionGroupCheck.name(id)}", notFound = true)
        groupFixes.fix(id)?.let { throw FixIntakeRefused("${FrictionGroupCheck.name(id)} is already fixed by ${it.repo}#${it.issue}; clear that link first", notFound = false) }
        when (val fate = intakes.of(intake)) {
            IntakeFate.Unknown -> throw FixIntakeRefused("no intake $intake", notFound = true)
            IntakeFate.Ended -> throw FixIntakeRefused("intake $intake ended without filing a ticket", notFound = false)
            is IntakeFate.Filed -> groupFixes.setFix(id, fate.repo, fate.issue, FrictionFix.FILED, null)
            is IntakeFate.Running -> groupFixes.setPending(id, intake, fate.repo)
        }
        log.info("friction fixes: {}'s candidate fix is intake {}, set by @{}", FrictionGroupCheck.name(id), intake, by)
        return groups.get(id)!!
    }

    /**
     * The pending pass of [sync]: an intake that filed resolves to its ticket (missed at filing), one that ended
     * without a ticket clears its link (reported under `changed`, `to` null), one still running waits.
     */
    private fun pending(pass: Pass) {
        for (p in groupFixes.pendings()) {
            val read = runCatching { intakes.of(p.intake) }
            val fate = read.getOrNull()
            if (fate == null) {
                skip(pass, p.groupId, p.repo, null, "cannot read intake ${p.intake}: ${read.exceptionOrNull()?.message}"); continue
            }
            when (fate) {
                is IntakeFate.Filed -> {
                    groupFixes.setFix(p.groupId, fate.repo, fate.issue, FrictionFix.FILED, null)
                    pass.linked += FixSyncReport.Linked(p.groupId, fate.repo, fate.issue, FrictionFix.FILED)
                }
                IntakeFate.Ended, IntakeFate.Unknown -> {
                    groupFixes.clearPending(p.groupId)
                    log.info("friction fixes: {}'s pending intake {} ended without a ticket; cleared", FrictionGroupCheck.name(p.groupId), p.intake)
                    pass.changed += FixSyncReport.Changed(p.groupId, p.repo, null, FrictionFix.PENDING, null, null)
                }
                is IntakeFate.Running -> {}
            }
        }
    }

    /** What a pass has done so far, built into a [FixSyncReport]. */
    private class Pass {
        val linked = mutableListOf<FixSyncReport.Linked>()
        val changed = mutableListOf<FixSyncReport.Changed>()
        val skipped = mutableListOf<FixSyncReport.Skipped>()
        fun report() = FixSyncReport(linked.toList(), changed.toList(), emptyList(), skipped.toList())
    }

    private fun skip(pass: Pass, groupId: Long?, repo: String, issue: Long?, reason: String) {
        pass.skipped += FixSyncReport.Skipped(groupId, repo, issue, reason)
    }

    private fun groupsFrom(repo: String, issue: Long, ids: List<Long>, ticket: FixTicket?, pass: Pass): Pass {
        for (id in ids) {
            if (!groups.exists(id)) {
                log.info("friction fixes: {}#{} names {}, which has no row", repo, issue, FrictionGroupCheck.name(id))
                skip(pass, id, repo, issue, "names ${FrictionGroupCheck.name(id)}, which has no row"); continue
            }
            val fix = groupFixes.fix(id)
            if (fix != null) {
                if (fix.repo != repo || fix.issue != issue) {
                    log.info("friction fixes: {}#{} names {}, already fixed by {}#{}; not relinked", repo, issue, FrictionGroupCheck.name(id), fix.repo, fix.issue)
                    skip(pass, id, repo, issue, "names ${FrictionGroupCheck.name(id)}, already fixed by ${fix.repo}#${fix.issue}; not relinked")
                } else if (ticket != null) follow(fix, ticket, pass)
                continue
            }
            if (groupFixes.fixDismissed(id) == key(repo, issue)) continue
            val next = ticket?.let { FrictionFixRules.transition(null, it) } ?: FixTransition(FrictionFix.FILED)
            val state = next.state ?: continue
            groupFixes.setFix(id, repo, issue, state, next.mergedAt)
            log.info("friction fixes: {} fixed by {}#{} ({})", FrictionGroupCheck.name(id), repo, issue, state)
            pass.linked += FixSyncReport.Linked(id, repo, issue, state)
        }
        return pass
    }

    /** A user's or First mate's link: group [groupId] is fixed by [repo]#[issue], `fix-filed` until the sync reads it. Null for no such group. */
    override fun link(groupId: Long, repo: String, issue: Long, by: String): FrictionGroup? {
        if (!groups.exists(groupId)) return null
        val was = groupFixes.fix(groupId)
        if (was == null || was.repo != repo || was.issue != issue) groupFixes.setFix(groupId, repo, issue, FrictionFix.FILED, null)
        log.info("friction fixes: {} fixed by {}#{}, set by @{}", FrictionGroupCheck.name(groupId), repo, issue, by)
        return groups.get(groupId)
    }

    /** A user's or First mate's clear: group [groupId] is open again, and the sync does not link its ticket again. Null for no such group. */
    override fun clear(groupId: Long, by: String): FrictionGroup? {
        if (!groups.exists(groupId)) return null
        val was = groupFixes.fix(groupId)
        groupFixes.clearFix(groupId, was?.let { key(it.repo, it.issue) } ?: groupFixes.fixDismissed(groupId))
        if (was != null) log.info("friction fixes: {}'s link to {}#{} cleared by @{}", FrictionGroupCheck.name(groupId), was.repo, was.issue, by)
        return groups.get(groupId)
    }

    /** Brings [fix] in step with [ticket], noting a change or a logged reason in [pass]. */
    private fun follow(fix: FrictionFix, ticket: FixTicket, pass: Pass) = follow(fix, FrictionFixRules.transition(fix.state, ticket), pass)

    /** Brings [fix] to [next], noting a change or a logged reason in [pass]. */
    private fun follow(fix: FrictionFix, next: FixTransition, pass: Pass) {
        next.why?.let {
            log.info("friction fixes: {}#{} for {}: {}", fix.repo, fix.issue, FrictionGroupCheck.name(fix.groupId), it)
            if (next.state != null) skip(pass, fix.groupId, fix.repo, fix.issue, it)
        }
        val state = next.state
        if (state == null) {
            groupFixes.clearFix(fix.groupId, key(fix.repo, fix.issue))
            pass.changed += FixSyncReport.Changed(fix.groupId, fix.repo, fix.issue, fix.state, null, null); return
        }
        if (state == fix.state && next.mergedAt?.toInstant() == fix.mergedAt?.toInstant()) return
        groupFixes.setFix(fix.groupId, fix.repo, fix.issue, state, next.mergedAt)
        pass.changed += FixSyncReport.Changed(fix.groupId, fix.repo, fix.issue, fix.state, state, next.mergedAt)
    }

    /**
     * One pass now, unless one is running (the scheduled one too): then [FixSyncRunning] at once, nothing waits.
     * The running mark is released however the pass ends.
     */
    override fun syncNow(): FixSyncReport {
        if (!running.compareAndSet(false, true)) throw FixSyncRunning()
        try { return sync() } finally { running.set(false) }
    }

    /**
     * One pass over every target repo: GitHub's search for issues whose body names `friction group` links the groups
     * they name that have no link (first ticket by number wins; a later one is logged) and brings linked ones in step;
     * a link to a ticket that does not name its group (set by a user) follows the repo's newest closed issues, and one
     * not among them is read by number, issue or PR, whatever its age ([FrictionFixRules.transition] of an [IssueOrPull]). The
     * first pass after deploy is the backfill. A repo GitHub cannot read is tried at the next pass. Answers the pass's report. Callers outside tests go through
     * [syncNow], so two passes never overlap.
     */
    fun sync(): FixSyncReport {
        val pass = Pass()
        pending(pass)
        val seen = mutableSetOf<String>()
        for (repo in repos.list().map { it.fullName }) {
            val issues = runCatching { details.issuesNaming(repo, FrictionFixRules.PHRASE) }
                .onFailure {
                    log.warn("friction fixes: cannot search {}'s issues: {}", repo, it.message)
                    skip(pass, null, repo, null, "cannot search the repo's issues: ${it.message}")
                }.getOrNull() ?: continue
            // An open ask names its groups in the ask; the ticket it is refined into is the fix (2026-10-06, the
            // first backfill linked 23 groups to closed intakes, which never reach fixed). A link to one is dropped.
            val (intakes, tickets) = issues.partition { it.labels.any { l -> l in INTAKE_LABELS } }
            val intakeKeys = intakes.map { key(repo, it.number) }.toSet()
            for (fix in groupFixes.fixes().filter { key(it.repo, it.issue) in intakeKeys }) {
                groupFixes.clearFix(fix.groupId, null)
                log.info("friction fixes: {}'s link to intake {}#{} dropped", FrictionGroupCheck.name(fix.groupId), fix.repo, fix.issue)
                skip(pass, fix.groupId, fix.repo, fix.issue, "a link to an intake issue was dropped: its ticket is the fix")
            }
            for (issue in tickets.sortedBy { it.number }) {
                seen += key(repo, issue.number)
                groupsFrom(repo, issue.number, FrictionFixRules.groupsNamed(issue.body),
                    FixTicket(issue.state, issue.stateReason, issue.closedBy), pass)
            }
        }
        for ((repo, fixes) in groupFixes.fixes().filter { key(it.repo, it.issue) !in seen }.groupBy { it.repo }) {
            val closed = runCatching { details.closedIssues(repo, CLOSED_LIMIT) }
                .onFailure {
                    log.warn("friction fixes: cannot read {}'s closed issues: {}", repo, it.message)
                    skip(pass, null, repo, null, "cannot read the repo's closed issues: ${it.message}")
                }.getOrNull()?.associateBy { it.number } ?: continue
            for (fix in fixes) {
                val c = closed[fix.issue]
                if (c != null) { follow(fix, FixTicket("CLOSED", c.stateReason, c.closedBy), pass); continue }
                val read = runCatching { details.issueOrPull(repo, fix.issue) }
                val ref = read.getOrNull()
                if (ref == null) {
                    val why = read.exceptionOrNull()?.let { "cannot read the issue or PR: ${it.message}" } ?: "no such issue or PR"
                    log.warn("friction fixes: {}#{} for {}: {}", repo, fix.issue, FrictionGroupCheck.name(fix.groupId), why)
                    skip(pass, fix.groupId, repo, fix.issue, why); continue
                }
                follow(fix, FrictionFixRules.transition(ref), pass)
            }
        }
        return pass.report()
    }
}
