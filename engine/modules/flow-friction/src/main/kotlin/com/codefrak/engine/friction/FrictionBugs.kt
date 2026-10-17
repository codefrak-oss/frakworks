package com.codefrak.engine.friction

import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import com.codefrak.engine.apart
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.camunda.Actor
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.github.issueUrl
import com.codefrak.engine.intake.AUTHORIZED_BY_ENGINE
import com.codefrak.engine.intake.FRICTION_ACTOR
import com.codefrak.engine.intake.FRICTION_BUG
import com.codefrak.engine.intake.SUBMITTED_BY
import com.codefrak.engine.jev.JevAnswer
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** What [FrictionBugs.mark] decided inside the mark's transaction, for [FrictionBugs.after] once it has committed. */
sealed interface Marked {
    /** The note was not marked, or nothing follows the mark. */
    object None : Marked
    /** The group's bug was claimed `filing`: start its intake. */
    data class File(val groupId: Long, val repo: String) : Marked
    /** The group has an open bug: comment the mark on it. */
    data class Comment(val bug: FrictionBugRef) : Marked
}

/**
 * The two-things mark and the bug it files (engine/README.md, "Friction
 * notes", "Two things"). A note Jev marks as describing two different things (the `two-things` `noul` at
 * `engine.jev.friction-two-things` or above) keeps its group and gets `two_things`; the first mark on a group with no
 * bug, in a repo with `frictionBugs` on, claims the group's bug as `filing` in the mark's transaction ([mark]) and,
 * once that has committed, starts an `intake` in the note's repo as `DoctorBugs.file` does ([after]): `submittedBy`
 * `friction`, authorized as the engine's `friction` actor, `Kind: bug` in the ask, which asks why the author wrote
 * two things in one note. A start that fails unclaims. `FileTicketDelegate` and `FileUnlabeledTicketDelegate` write
 * the refined ticket's number and `open` ([filed]). A later mark on a group with an open bug comments on it; a bug the
 * engine sees closed ([sync], every `engine.doctor.bug-sync`) is `closed`, and the next mark files afresh. With the
 * switch off, or a note with no repo, the mark alone is written and `bug_state` stays null.
 */
@Component
class FrictionBugs(
    private val groups: FrictionGroups, private val groupBugs: FrictionGroupBugs, private val repos: TargetRepos,
    private val runtime: RuntimeService, private val history: HistoryService, private val tickets: Tickets,
    private val details: IssueDetails, private val events: EngineEvents, transactions: PlatformTransactionManager,
) : FrictionBugFilings {
    private val log = LoggerFactory.getLogger(javaClass)
    private val own = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    companion object {
        /** The engine actor a friction intake is authorized as: "the engine's friction". */
        const val ACTOR = FRICTION_ACTOR
        val FRICTION = Actor.System(ACTOR)
        /** The evidence the ask carries, at most (`DoctorBugAsk.MAX_DIAGNOSIS`). */
        const val MAX_EVIDENCE = 4000
        /** The closed issues [sync] reads per repo. */
        const val CLOSED_LIMIT = 100
        /** The longest the intake's start may take. */
        const val START_TIMEOUT_SECONDS = 120L
        /** The line of `bots/smith.md` "Friction notes" the ask cites. */
        const val RULE = "`bots/smith.md`, \"Friction notes\": one thing per note; a second thing is a second note."
    }

    /** The collaborators a test builds a variant with. */
    fun history() = history
    fun events() = events

    /** Whether [answer] marks the note: a `noul` at [floor] or above. */
    fun marks(answer: JevAnswer?, floor: Double): Double? = answer?.noul?.takeIf { it >= floor }

    /**
     * Inside the caller's transaction: the mark on [note] (now in group [groupId]) with [value] by [call], and the
     * claim of the group's bug when the note's repo has `frictionBugs` on and the group has none. Answers what
     * [after] does once the transaction has committed.
     */
    fun mark(note: FrictionNote, groupId: Long, value: Double, call: String): Marked {
        groups.mark(note.id, value, call)
        val repo = note.repo ?: return Marked.None
        if (!repos.frictionBugs(repo)) return Marked.None
        val bug = groupBugs.bug(groupId)
        return when {
            bug == null || bug.state == FrictionBugRef.CLOSED -> {
                // A closed bug makes way: the next mark files afresh, as a regression does.
                if (bug != null) groupBugs.bugClosedAway(groupId)
                if (groupBugs.claimBug(groupId, repo)) Marked.File(groupId, repo) else Marked.None
            }
            bug.state == FrictionBugRef.OPEN && bug.issue != null -> Marked.Comment(bug)
            else -> Marked.None
        }
    }

    /** After the mark's transaction: the intake start for a [Marked.File], the comment for a [Marked.Comment]. Never throws. */
    fun after(note: FrictionNote, marked: Marked) {
        when (marked) {
            is Marked.File -> start(note, marked)
            is Marked.Comment -> runCatching { comment(note, marked.bug) }
                .onFailure { log.warn("friction note {}: no comment on {}#{}: {}", note.id, marked.bug.repo, marked.bug.issue, it.message) }
            Marked.None -> {}
        }
    }

    private fun start(note: FrictionNote, file: Marked.File) {
        val title = groups.get(file.groupId)?.title ?: note.summary
        val vars = mapOf<String, Any>("ask" to Brief.textValue(ask(note, file.groupId, title)), "dryRun" to false, Vars.REPO to file.repo,
            SUBMITTED_BY to FRICTION.part, AUTHORIZED_BY_ENGINE to FRICTION.part, FRICTION_BUG to file.groupId)
        val intake = try {
            // On a thread of its own, so in a transaction of its own: the intake's ask item is created in it, and its failure is not the grouper's.
            apart(START_TIMEOUT_SECONDS) { runtime.createMessageCorrelation("ask submitted").setVariables(vars).correlateStartMessage().id }
        } catch (e: Exception) {
            log.warn("friction note {}: the two-things intake for {} did not start; the next mark tries again: {}", note.id, FrictionGroupCheck.name(file.groupId), e.message)
            own.executeWithoutResult { groupBugs.unclaimBug(file.groupId) }
            return
        }
        own.executeWithoutResult { groupBugs.bugStarted(file.groupId, intake) }
        events.publishAfterCommit(EngineEvent.Intake(file.repo, intake, "submitted"))
        log.info("friction note {}: two things; {}'s bug filing through intake {} in {}", note.id, FrictionGroupCheck.name(file.groupId), intake, file.repo)
    }

    /** The ask: the note, where it came from, and the question. Its first line is the intake's title; `Kind: bug` makes the refiner file a bug. */
    fun ask(note: FrictionNote, groupId: Long, title: String): String = buildString {
        val what = note.summary.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: note.summary
        appendLine("Friction note carries two things: $what")
        appendLine("Kind: bug").appendLine()
        appendLine("The engine's friction grouper marked a friction note as describing two different things, each of which a different " +
            "change would spare (Jev, question `two-things`; engine/README.md, \"Friction notes\", \"Two things\"), and filed this ask " +
            "itself, one per group of notes. The note is honoured as one thing, the one its key line is about, and stays in its group; " +
            "this bug asks for the cause, not the symptom.").appendLine()
        appendLine("**The question:** why did this run write two things in one friction note, and what in its instructions or prompt " +
            "led it to? $RULE Find what made the author put two in one (the manual, the retro's wording, the tool's description, a " +
            "habit) and fix that.").appendLine()
        appendLine("- Note: `${note.id}`, `${note.kind}`${note.severity?.let { ", severity $it" }.orEmpty()}, source `${note.source}`")
        appendLine("- Group: ${FrictionGroupCheck.name(groupId)}, \"$title\"")
        when {
            note.issue != null && note.repo != null -> appendLine("- From: ${issueUrl(note.repo, note.issue)}, Smith run `${note.runId}`" +
                note.processInstanceId?.let { ", flow `$it`" }.orEmpty())
            note.login != null -> appendLine("- From: First mate, @${note.login}${note.repo?.let { ", in $it" }.orEmpty()}")
            else -> appendLine("- From: run `${note.runId}`")
        }
        appendLine().appendLine("Summary (the note's author):").appendLine().appendLine(note.summary.trim())
        note.evidence?.takeIf { it.isNotBlank() }?.let { appendLine().appendLine("Evidence (the note's author):").appendLine().appendLine(it.trim().take(MAX_EVIDENCE)) }
    }.trimEnd()

    private fun comment(note: FrictionNote, bug: FrictionBugRef) {
        val from = when {
            note.issue != null && note.repo != null -> "${note.repo}#${note.issue}, run `${note.runId}`"
            note.login != null -> "First mate, @${note.login}"
            else -> "run `${note.runId}`"
        }
        val line = note.summary.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: note.summary
        tickets.commentOn(bug.repo, bug.issue!!, "Another note marked as two things: `${note.id}` from $from: $line\n\n— ${FRICTION.name}")
    }

    /** Group [groupId]'s intake filed its bug as [issue]: `open`. */
    override fun filed(groupId: Long, issue: Long) = groupBugs.bugFiled(groupId, issue)

    /**
     * Brings the groups' bugs in step: an `open` bug the engine sees closed on GitHub (one read of each repo's newest
     * closed issues) is `closed`, so the next mark files afresh; a `filing` bug whose intake ended without filing (cancelled
     * before it filed) is unclaimed. A repo GitHub cannot read is tried at the next sync. Answers how many changed.
     */
    fun sync(): Int {
        var changed = 0
        for ((repo, bugs) in groupBugs.bugs(FrictionBugRef.OPEN).groupBy { it.repo }) {
            val closed = runCatching { details.closedIssues(repo, CLOSED_LIMIT) }
                .onFailure { log.warn("friction bugs: cannot read {}'s closed issues: {}", repo, it.message) }
                .getOrNull()?.map { it.number }?.toSet() ?: continue
            for (bug in bugs) if (bug.issue in closed) {
                groupBugs.bugClosed(bug.groupId)
                log.info("friction bugs: {}#{} closed; {} files afresh on its next mark", repo, bug.issue, FrictionGroupCheck.name(bug.groupId))
                changed++
            }
        }
        for (bug in groupBugs.bugs(FrictionBugRef.FILING)) {
            val intake = bug.intakeId ?: continue
            val ended = runCatching { history.createHistoricProcessInstanceQuery().processInstanceId(intake).singleResult()?.endTime != null }.getOrDefault(false)
            if (ended && bug.issue == null) {
                groupBugs.unclaimBug(bug.groupId)
                log.info("friction bugs: intake {} ended without filing; {} has no bug again", intake, FrictionGroupCheck.name(bug.groupId))
                changed++
            }
        }
        return changed
    }
}
