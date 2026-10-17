package com.codefrak.engine.intake

import com.codefrak.engine.events.CreatedEvents
import com.codefrak.engine.feature.FeatureMode
import com.codefrak.engine.github.CreatedIssue
import com.codefrak.engine.github.issueUrl
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemFlags
import com.codefrak.engine.workitems.WorkItemSourceRef
import org.springframework.stereotype.Component

/**
 * Files a ticket the way a split feature does for each child (`FileChildDelegate`),
 * and an intake's ask as a work item ([fileWorkItem]): the body with the engine's
 * trailer, the kind label, and a feature's `feature:<mode>` label. No
 * `authorized`, no `bot-task`: nothing claims it by itself. Each filed ticket's
 * board row goes on the stream at once, as a `created` frame ([CreatedEvents]).
 *
 * Every ticket is an engine work item ([Tickets.create]): its kind, a feature's mode and its flags as typed fields. A
 * `refactor-scan` ticket is one of kind `refactor-scan`, and one filed unlabeled for triage ([fileUnlabeled]) one of kind
 * `triage`. No GitHub call.
 */
@Component
class TicketFiler(private val tickets: Tickets, private val events: CreatedEvents,
                  private val workItems: WorkItemFiling? = null) {
    /**
     * The body as filed: the draft, then the `<!-- engine: … -->` trailer (`origin`, e.g. `intake <id>`,
     * `split of #n` or `proposal #n`); with no tier the trailer has no `· tier` part, and the start falls
     * back to the default. `jev` is the refine-pick call that chose the kind and tier (`· jev <id>`), whose
     * outcome the flow's start records ([RefinePickOutcome.started]). `triage` ends it with `· triage`: a ticket
     * filed unlabeled for the user to triage by hand ([fileUnlabeled]), which `Needs` lists until it has a kind.
     * `by` is who asked for the ticket (`· by <login>`, right after the priority): the intake's `submittedBy`, or a
     * parent flow's `askedBy` ([Vars.ASKED_BY]); left out when null or blank, as on tickets from before it. [askedByOf] reads it.
     * `points` is the story-point estimate (the refiner's, or a split's for its child) (`· points <n>`, right after the tier; bots/story-points.md), left
     * out when null (a triage ticket, or one from before it). [pointsOf] reads it.
     * `key` is a refactoring proposal's stable key (`· key <k>`, right after `by`; "Refactoring jobs"), what a later run
     * reads from the repo's open `refactor` issues so it does not file the same work twice; left out when null. [keyOf] reads it.
     */
    fun body(draft: String, origin: String, tier: Int?, priority: String, by: String? = null, jev: String? = null, triage: Boolean = false,
             points: Int? = null, key: String? = null): String = buildString {
        append(draft.trimEnd()).append("\n\n")
        val asker = by?.trim()?.takeIf { it.isNotEmpty() }?.let { " · by $it" }.orEmpty()
        val keyed = key?.trim()?.takeIf { it.isNotEmpty() }?.let { " · key $it" }.orEmpty()
        append("<!-- engine: $origin${tier?.let { " · tier $it" } ?: ""}${points?.let { " · points $it" }.orEmpty()} · $priority$asker$keyed${jev?.let { " · jev $it" }.orEmpty()}${if (triage) " $TRIAGE_MARK" else ""} -->\n")
    }

    /**
     * A feature carries its mode as a second label, the way one authorized by hand must (IssueScanner); `extraLabels`
     * (a refactoring ticket's `refactor`) come after, in order, none twice.
     */
    fun labels(kind: String, featureMode: String?, extraLabels: List<String> = emptyList()): List<String> =
        (listOfNotNull(kind, featureMode?.takeIf { kind == "feature" }?.let { FeatureMode.label(it) }) + extraLabels).distinct()

    fun file(repo: String, title: String, body: String, kind: String, featureMode: String?, extraLabels: List<String> = emptyList()): CreatedIssue {
        require(kind in WORK_KINDS) { "kind is one of ${WORK_KINDS.joinToString()}" }
        val made = tickets.create(NewWorkItem(repo, "# $title\n\n${body.trimEnd()}", kind, WorkItemSourceRef(WorkItemSourceRef.RAISED),
            mode = featureMode?.takeIf { kind == "feature" }, author = ENGINE, flags = WorkItemFlags.of(extraLabels)))
        val number = made.ticket!!
        return CreatedIssue(number, issueUrl(repo, number), number).also { events.created(repo, it.number, it.url) }
    }

    /**
     * The intake's filing ([WorkItemFiling]): files intake [intake]'s ask as a work item in [repo],
     * [title] and [body] as its description and [tier] and [featureMode] as fields, never a trailer. No GitHub call.
     */
    fun fileWorkItem(repo: String, intake: String, ask: String, title: String, body: String, kind: String, featureMode: String?,
                     tier: Int?): FiledWorkItem {
        val filing = workItems ?: error("no work-item store in this engine")
        val item = filing.askItem(repo, intake, ask)
        return filing.file(item, kind, featureMode?.takeIf { kind == "feature" }, tier, "# $title\n\n${body.trimEnd()}")
    }

    /** Of kind `triage` ([WorkItem.TRIAGE]): no work flow starts on it, publish never sends it to GitHub, and no `authorized`. */
    fun fileUnlabeled(repo: String, title: String, body: String): CreatedIssue =
        file(repo, title, body, WorkItem.TRIAGE, featureMode = null)

    companion object {
        /** The kinds a filed ticket may have. */
        val WORK_KINDS = setOf("chore", "bug", "feature", "proposal", "research", WorkItem.REFACTOR_SCAN, WorkItem.TRIAGE)
        private const val ENGINE = "engine"
        const val TRIAGE_HEADING = "## Needs human triage"
        /** The trailer's last part on a ticket filed for human triage ([body]). */
        const val TRIAGE_MARK = "· triage"
        private val TRIAGE_TRAILER = Regex("""<!--\s*engine:[^>]*?·\s*triage\s*-->""")
        /** The trailer's `· by <login>` ([body]); a login is letters, digits and dashes. */
        private val BY_TRAILER = Regex("""<!--\s*engine:[^>]*?·\s*by\s+([A-Za-z0-9-]+)""")
        /** The trailer's `· points <n>` ([body]). */
        private val POINTS_TRAILER = Regex("""<!--\s*engine:[^>]*?·\s*points\s+(\d+)""")
        /** The trailer's `· key <k>` ([body]); a key is letters, digits, dots, slashes, underscores and dashes. */
        private val KEY_TRAILER = Regex("""<!--\s*engine:[^>]*?·\s*key\s+([A-Za-z0-9._/-]+)""")
        private val BLOCKER_LINE = Regex("""^\*\*Blocker:\*\*\s*(.+)$""", RegexOption.MULTILINE)
        private val REASON_LINE = Regex("""^\*\*Reason:\*\*\s*(.*)$""", RegexOption.MULTILINE)

        /**
         * The section the unlabeled ticket carries before its trailer: the refiner's [blocker] (left out when it
         * gave none), its [reason], and the [kind] it would have filed it as.
         */
        fun triageSection(blocker: String?, reason: String?, kind: String?): String = buildString {
            append(TRIAGE_HEADING).append("\n\n")
            append("The refiner could not file this as work the fleet can do with its current permissions, so the engine filed it ")
            append("unlabeled. Label it with a kind (and `authorized`) once it can be done, or close it.\n\n")
            blocker?.trim()?.takeIf { it.isNotEmpty() }?.let { append("**Blocker:** ").append(it.lineSequence().joinToString(" ") { l -> l.trim() }).append("\n\n") }
            append("**Reason:** ").append(reason?.trim().orEmpty().ifEmpty { "none given" }).append("\n")
            kind?.let { append("\nThe refiner would have filed it as a `").append(it).append("`.\n") }
        }

        /** Who asked for the ticket, from its trailer's `by <login>`; null on a body with none (a hand-filed issue, or one from before `by`). */
        fun askedByOf(body: String?): String? = body?.let { BY_TRAILER.find(it)?.groupValues?.get(1) }

        /** A refactoring ticket's proposal key, from its trailer's `key <k>`; null on a body with none. */
        fun keyOf(body: String?): String? = body?.let { KEY_TRAILER.find(it)?.groupValues?.get(1) }

        /** The ticket's story points, from its trailer's `points <n>`; null on a body with none. */
        fun pointsOf(body: String?): Int? = body?.let { POINTS_TRAILER.find(it)?.groupValues?.get(1)?.toIntOrNull() }

        /** Whether [body] is a ticket filed for human triage: its engine trailer ends with [TRIAGE_MARK]. */
        fun isTriage(body: String?): Boolean = body != null && TRIAGE_TRAILER.containsMatchIn(body)

        /** What a triage ticket waits on, in a line: its blocker, else its reason's first line. */
        fun triageWanted(body: String): String {
            val section = body.substringAfterLast(TRIAGE_HEADING, "")
            BLOCKER_LINE.find(section)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(200) }
            val reason = REASON_LINE.find(section)?.let { m -> (m.groupValues[1] + "\n" + section.substring(m.range.last + 1)) }
            return reason?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && !it.startsWith("<!--") }?.take(200)
                ?: "triage it: the refiner could not file it with the fleet's current permissions"
        }
    }
}
