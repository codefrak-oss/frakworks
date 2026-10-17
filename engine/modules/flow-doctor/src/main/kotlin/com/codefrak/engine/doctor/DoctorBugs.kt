package com.codefrak.engine.doctor

import com.codefrak.engine.apart
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.camunda.Actor
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.github.MergedPull
import com.codefrak.engine.intake.AUTHORIZED_BY_ENGINE
import com.codefrak.engine.intake.DOCTOR_BUG
import com.codefrak.engine.intake.SUBMITTED_BY
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.jev.JevDecider
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import com.codefrak.engine.workitems.listings
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * What a sighting does besides its row (engine/README.md, "Doctor"):
 *
 * - **Exact lump.** A sighting whose signature is on a doctor bug is lumped into it (`lumped_by: exact`), whatever
 *   its repo: an engine row, no GitHub write.
 * - **The home repo.** A `where: engine` sighting's bug goes to the repo marked for engine defects; a `where: target`
 *   one's to its stuck ticket's own repo. Both the stuck ticket's repo and the home repo have `doctorBugs` on, or
 *   nothing is filed: the sighting is only recorded.
 * - **Jev, for a new signature.** A sighting with a signature on no bug first asks Jev ([SightingCheck], use case
 *   `doctor-sighting`) whether it is one of the home repo's open bugs or the doctor's bugs there closed in the last
 *   30 days. A pick at `engine.jev.doctor-sighting-floor` or above lumps it (`lumped_by: jev`, `jev_call_id`) and puts
 *   its signature on that bug. No options (or none readable) is no call.
 * - **A fix merged since** ([mergedFix]): before filing a `where: engine` sighting, the home repo's PRs merged since
 *   the episode started are read; one whose title or body names the signature's hash, or every activity of its
 *   leaves, is taken as its fix. Nothing is filed: the signature goes onto a `fixed` bug on the ticket that PR closes
 *   (or the PR), and the run's detail recommends a retry after the next deploy. A failed read files as below.
 * - **Filing.** Otherwise (`new`, below the floor, no answer, no call), it claims a `filing` doctor bug in that
 *   repo and starts an `intake` there, in-engine: `submittedBy` `doctor`, authorized by the engine's doctor
 *   ([AUTHORIZED_BY_ENGINE]), `Kind: bug` in the ask; the sighting is lumped `new`, with the Jev call when there was
 *   one. An intake that does not start undoes the claim, so the next sighting tries again.
 * - **Repeats** ([DoctorBugRepeats.repeat]), counted per distinct stuck ticket, with the runs beside it, on a filed `open` or
 *   `needs-repro` bug: the 2nd ticket comments on the bug, linking it; the 3rd also steps its priority up one, never
 *   above p2; the 4th also adds `needs-review` and a `GET /needs` `sighting` row. A declined bug only counts. A bug
 *   with a flow running (by [com.codefrak.engine.camunda.IssueInstances]) never gets a second; a `needs-repro` one hit by a new ticket gets its
 *   reproduce restarted, as the engine's doctor, with every sighting in the brief, at most [DoctorBugRepeats.MAX_RESTARTS] times.
 * - **After a fix** ([afterFix]): a sighting on a bug closed as fixed is a `leftover`, only counted, when its episode
 *   started before the close (for `where: engine`, before the engine's first start after it); otherwise it files a
 *   new bug, "Regression of #n", which takes the signature and names the old bug (`regression_of`); the old one
 *   stays closed.
 * - **The stuck ticket's comment.** In a repo with `doctorBugs` on, once per episode (the first sighting of the
 *   signature on its flow), when the bug's repo is on too and the bug is not closed: "Stuck at … since …; this is
 *   owner/name#n (seen on K tickets)", or "filing a bug".
 * - **`needs-repro`**: the bug flow's `label needs repro` makes an `open` doctor bug `needs-repro` ([DoctorBugStore.needsRepro]);
 *   [DoctorBugSync.sync] makes it `open` again once the label is off.
 * - **Closes** ([DoctorBugSync.sync]): a filed bug closed `completed` is `fixed`; closed `not planned`, `declined` with its
 *   signatures; closed with "Duplicate of #k", its signatures move to #k's doctor bug. Each close writes the
 *   `outcome` of the Jev calls behind the bug's sightings that have none yet: `lumped #n: kept`, `new: filed #m,
 *   kept`, or `new: filed #m, closed as duplicate of #n by @x`.
 *
 * With either repo off, or no repo marked, the sighting is recorded and nothing else happens.
 */
@Component
class DoctorBugs(
    private val store: DoctorBugStore, private val sightings: DoctorSightings, private val history: DoctorHistory,
    private val repos: TargetRepos, private val runtime: RuntimeService, private val tickets: Tickets,
    private val details: IssueDetails, private val events: EngineEvents,
    private val jev: JevDecider, private val calls: JevCalls, private val processes: HistoryService,
    private val repeats: DoctorBugRepeats,
    @Value("\${engine.jev.doctor-sighting-floor:0.9}") private val floor: Double = 0.9,
) {
    private val log = LoggerFactory.getLogger(DoctorBugs::class.java)

    companion object {
        /** The engine actor a doctor intake is authorized as. */
        val DOCTOR = Actor.System(ApplyDoctorRemedyDelegate.ACTOR)
        /** The longest the intake's start ([apart]) may take. */
        const val START_TIMEOUT_SECONDS = 120L
        /** When this engine (its JVM) started: a fix closed before it is running from it. */
        fun jvmStart(): OffsetDateTime = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().startTime).atOffset(ZoneOffset.UTC)
        /** The bug flow's label for a bug it could not reproduce (bug/BugDelegates.kt). */
        const val NEEDS_REPRO = "needs-repro"
        /** The label the 4th stuck ticket puts on a bug still open. */
        const val NEEDS_REVIEW = "needs-review"

        /**
         * The PR in [merged] that fixes [signature]: the newest merged whose title or body names its hash (12 characters
         * or more), or each activity of its leaves (`key:activity@…`) as a whole word. Null when none does.
         */
        fun fixOf(signature: Signature, merged: List<MergedPull>): MergedPull? {
            val activities = signature.leaves.map { it.substringBefore('@').split(':', '/').last() }.filter { it.isNotBlank() }.distinct()
            val hash = Regex("""\b${signature.hash.take(12)}[0-9a-f]*\b""")
            return merged.sortedByDescending { it.mergedAt }.firstOrNull { pr ->
                val text = "${pr.title}\n${pr.body}"
                hash.containsMatchIn(text) || activities.isNotEmpty() &&
                    activities.all { Regex("""(?<![\w-])${Regex.escape(it)}(?![\w-])""").containsMatchIn(text) }
            }
        }

        /** Doctor runs that never diagnosed through no engine defect: a usage limit, or a sandbox that failed before the Smith started. */
        private val UNFILED = setOf("${Signature.DOCTOR_FAILED} rate-limited", "${Signature.DOCTOR_FAILED} sandbox-failed")
    }

    /** Lumps, files and comments for the sighting [seen] of [new] (above); answers the sighting as it is now. */
    fun act(new: NewSighting, seen: DoctorSighting, diagnosis: String?): DoctorSighting {
        val known = store.ofSignature(new.signature.hash)
        // The doctor's own Smith hit a usage limit or never started: recorded, never filed; the next stuck episode gets a new run.
        if (known == null && new.signature.shape.singleOrNull() in UNFILED) return sightings.of(new.doctorRunId) ?: seen
        val home = if (known == null) home(new) else null
        // Whether the sighting went into a bug that was there before it: a repeat.
        var into = known != null
        val bug: DoctorBugRef? = when {
            known != null && known.state == "fixed" && known.issue != null ->
                afterFix(known, new, seen, diagnosis)?.also { into = false } ?: known
            known != null -> known.also { store.lump(seen.id, it.id, "exact") }
            home != null -> {
                val (lumped, call) = look(home, new, seen, diagnosis)
                into = lumped != null
                lumped ?: mergedFix(home, new, seen) ?: file(home, new, seen, diagnosis, call)
            }
            else -> null
        }
        val now = sightings.of(new.doctorRunId) ?: seen
        val found = bug ?: return now
        if (into) runCatching { repeats.repeat(new, seen, found) }
            .onFailure { log.warn("doctor run {}: the repeat on {}#{} not acted on: {}", new.doctorRunId, found.repo, found.issue, it.message) }
        runCatching { comment(new, seen, now, found) }
            .onFailure { log.warn("doctor run {}: no comment on {}#{}: {}", new.doctorRunId, new.repo, new.issue, it.message) }
        return now
    }

    /**
     * Where [new]'s bug is filed: the repo marked for engine defects for `where: engine`, the stuck ticket's own repo
     * for `where: target`; null (recorded only) when either that repo or the stuck ticket's has `doctorBugs` off.
     */
    private fun home(new: NewSighting): String? {
        if (!repos.doctorBugs(new.repo)) return null
        val home = when (new.where) {
            "engine" -> repos.engineDefectsRepo()
            "target" -> new.repo
            else -> null
        }
        return home?.takeIf { repos.doctorBugs(it) }
    }

    /**
     * A sighting of a signature on [bug], closed as fixed: a `leftover` (counted, nothing else) when its episode started
     * before the close, or, for `where: engine`, before the engine's first start after it, since the fix was not
     * running yet; otherwise a regression, filed as a new bug that takes the signature (null back for the leftover,
     * and for a regression that may not be filed, which is lumped as a count).
     */
    private fun afterFix(bug: DoctorBugRef, new: NewSighting, seen: DoctorSighting, diagnosis: String?): DoctorBugRef? {
        val marks = store.marks(bug.id)
        // The earliest open incident's timestamp when there is one: the doctor's stuckSince restarts with the engine.
        val since = new.incidentSince ?: history.get(new.doctorRunId)?.stuckSince
        val closed = marks.closedAt
        val running = marks.fixRunningAt
        val leftover = since == null || closed == null || since.isBefore(closed) ||
            new.where == "engine" && (running == null || since.isBefore(running))
        if (leftover) {
            store.lump(seen.id, bug.id, "leftover")
            log.info("doctor run {}: a leftover of fixed {}#{}, stuck since {}", new.doctorRunId, bug.repo, bug.issue, since)
            return null
        }
        val home = home(new) ?: run { store.lump(seen.id, bug.id, "exact"); return null }
        return file(home, new, seen, diagnosis, null, bug)
    }

    /**
     * Jev's look ([SightingCheck]): the bug it lumped the sighting into, or null to file; and the call, null when
     * there was none (no options, or GitHub unreadable). Any failure is "file", as no answer is.
     */
    private fun look(home: String, new: NewSighting, seen: DoctorSighting, diagnosis: String?): Pair<DoctorBugRef?, String?> {
        val candidates = runCatching { candidates(home) }
            .onFailure { log.warn("doctor run {}: no Jev look, {}'s bugs unreadable; filing: {}", new.doctorRunId, home, it.message) }
            .getOrNull()
        if (candidates.isNullOrEmpty()) return null to null
        val decision = runCatching {
            jev.decide(SightingCheck.USE_CASE, JevContext(new.doctorRunId, home, null), SightingCheck.state(new, diagnosis, flow(new.rootInstanceId)),
                mapOf(SightingCheck.QUESTION to SightingCheck.question(candidates)), floor)
        }.onFailure { log.warn("doctor run {}: the Jev look failed; filing: {}", new.doctorRunId, it.message) }.getOrNull() ?: return null to null
        val (action, pick) = SightingCheck.read(decision.answers?.get(SightingCheck.QUESTION), candidates, floor)
        var bug = pick?.let { store.lumpByJev(seen.id, new.signature.hash, home, it.number, decision.callId) }
        var done = action
        if (pick != null && bug == null) {
            // Another run put the signature on a bug first: this one lumps into it.
            bug = store.ofSignature(new.signature.hash)?.also { store.lump(seen.id, it.id, "exact") }
            done = "$action, but another run lumped it first"
        }
        // The audit is not the decision: a failed write must not throw the pick away.
        runCatching { calls.setAction(decision.callId, done) }
            .onFailure { log.warn("jev doctor-sighting {}: action not recorded: {}", decision.callId, it.message) }
        if (bug != null) log.info("doctor run {}: Jev lumped signature {} into {}#{}", new.doctorRunId, new.signature.hash.take(12), bug.repo, bug.issue)
        return bug to decision.callId
    }

    /**
     * The fix for [new]'s signature merged in [home] since its episode started ([fixOf]), recorded as a `fixed` bug
     * ([DoctorBugStore.fixedBy]) with a retry after the next deploy in the run's detail; null to file: a `where: target`
     * sighting, no such PR, or GitHub unreadable (logged).
     */
    private fun mergedFix(home: String, new: NewSighting, seen: DoctorSighting): DoctorBugRef? {
        if (new.where != "engine") return null
        val since = new.incidentSince ?: history.get(new.doctorRunId)?.stuckSince ?: return null
        val merged = runCatching { details.mergedPulls(home, since) }
            .onFailure { log.warn("doctor run {}: {}'s merged PRs unreadable; filing: {}", new.doctorRunId, home, it.message) }
            .getOrNull() ?: return null
        val pr = fixOf(new.signature, merged) ?: return null
        val fix = pr.closes.firstOrNull() ?: pr.number
        val bug = store.fixedBy(seen.id, new.signature.hash, home, fix, pr.mergedAt)
            ?: return store.ofSignature(new.signature.hash)?.also { store.lump(seen.id, it.id, "exact") }
        val what = if (pr.closes.isEmpty()) "$home#${pr.number}" else "$home#$fix (PR #${pr.number})"
        history.note(new.doctorRunId, "a fix for this signature merged after the ticket got stuck: $what at ${pr.mergedAt}; " +
            "retry the ticket after the next deploy")
        log.info("doctor run {}: signature {} fixed by {} merged at {}, after the episode started at {}; not filed",
            new.doctorRunId, new.signature.hash.take(12), what, pr.mergedAt, since)
        return bug
    }

    /** The options: [home]'s open `bug` issues, and the doctor's bugs there closed in the last 30 days with GitHub's titles. */
    private fun candidates(home: String): List<SightingCheck.Candidate> {
        val open = tickets.listings(home).sortedByDescending { it.updatedAt }
        var closed = store.closedSince(home, OffsetDateTime.now(ZoneOffset.UTC).minusDays(SightingCheck.CLOSED_DAYS))
        if (closed.isNotEmpty()) {
            val titles = runCatching { details.closedIssues(home, DoctorBugSync.CLOSED_LIMIT) }.getOrNull().orEmpty().associate { it.number to it.title }
            closed = closed.map { it.copy(title = titles[it.issue]) }
        }
        return SightingCheck.candidates(open, closed)
    }

    /** The stuck flow's kind (its process key), or null when the history cannot say. */
    private fun flow(rootInstanceId: String): String? = runCatching {
        processes.createHistoricProcessInstanceQuery().processInstanceId(rootInstanceId).singleResult()?.processDefinitionKey
    }.getOrNull()

    private fun file(home: String, new: NewSighting, seen: DoctorSighting, diagnosis: String?, call: String?, regressionOf: DoctorBugRef? = null): DoctorBugRef? {
        val hash = new.signature.hash
        // Another run claimed it first: this one lumps into its bug.
        val id = store.claim(home, hash, regressionOf?.id) ?: return store.ofSignature(hash)?.also { store.lump(seen.id, it.id, "exact") }
        store.lump(seen.id, id, "new", call)
        val vars = mapOf<String, Any>("ask" to Brief.textValue(DoctorBugAsk.render(new, diagnosis, home, regressionOf, history.get(new.doctorRunId)?.stuckSince)),
            "dryRun" to false, Vars.REPO to home, SUBMITTED_BY to DOCTOR.part,
            AUTHORIZED_BY_ENGINE to DOCTOR.part, DOCTOR_BUG to id)
        val intake = try {
            // On a thread of its own, so in a transaction of its own: the intake's ask item is created in it, and its failure is not the doctor run's.
            apart(START_TIMEOUT_SECONDS) { runtime.createMessageCorrelation("ask submitted").setVariables(vars).correlateStartMessage().id }
        } catch (e: Exception) {
            log.warn("doctor run {}: the intake for signature {} did not start; the next sighting tries again: {}", new.doctorRunId, hash.take(12), e.message)
            store.unclaim(id)
            return null
        }
        store.started(id, intake)
        events.publishAfterCommit(EngineEvent.Intake(home, intake, "submitted"))
        log.info("doctor run {}: signature {} is new; doctor bug {} filing through intake {} in {}", new.doctorRunId, hash.take(12), id, intake, home)
        return DoctorBugRef(id, home, null, "filing")
    }

    private fun comment(new: NewSighting, seen: DoctorSighting, now: DoctorSighting, bug: DoctorBugRef) {
        if (bug.state !in setOf("filing", "open", "needs-repro")) return
        if (!repos.doctorBugs(new.repo) || !repos.doctorBugs(bug.repo)) return
        if (store.seenBefore(new.rootInstanceId, new.signature.hash, seen.id)) return
        val since = history.get(new.doctorRunId)?.stuckSince
        val leaves = new.signature.leaves.joinToString(", ") { "`$it`" }.ifEmpty { "no leaf" }
        val what = if (bug.issue == null) "filing a bug in ${bug.repo}"
            else "this is ${bug.repo}#${bug.issue} (seen on ${now.tickets} ticket${if (now.tickets == 1L) "" else "s"})"
        tickets.commentOn(new.repo, new.issue, "Stuck at $leaves${since?.let { " since $it" }.orEmpty()}; $what.\n\n— ${DOCTOR.name}")
    }
}
