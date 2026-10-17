package com.codefrak.engine.smith

import com.codefrak.engine.engineJson
import com.codefrak.engine.feature.ChildDraft
import com.codefrak.engine.tier.TierRow
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.Variables
import org.operaton.bpm.engine.variable.value.BytesValue
import tools.jackson.module.kotlin.readValue

/** `part N of M of #<parent>`, the line every filed child's body carries: the depth marker a design run may not split past. */
val PART_LINE = Regex("""(?i)\bpart (\d+) of (\d+) of #(\d+)""")

/** One thing a reviewer wants changed. The engine gives it `id` and `review` (correctness or security). */
data class Finding(
    @PassAlong val ask: String,
    val severity: String = "medium",
    val file: String? = null,
    val line: Int? = null,
    val id: String = "",
    val review: String = "",
)

data class PullRequest(val number: Long, val url: String, val branch: String)

/** One question a refining Smith asked and the answer the engine took for it (engine/README.md, "Intake"). */
data class Answer(val question: String, val answer: String, val by: String)

/** One late addition the user made to a filed ticket (engine/README.md, "Intake", "A follow-up to a ticket"); `at` ISO-8601. */
data class Amendment(val by: String, val at: String, val text: String)

/** One steer from a split parent's replan (engine/README.md, "Steer"): who sent it (`#<parent>`), when, and what it says. */
data class SteerEntry(val by: String, val at: String, val message: String)

/** The ticket as it was approved (labelled): the Smith works from this, never the live issue. */
data class Ticket(val number: Long, val title: String, val body: String, val author: String)

/**
 * What the engine tells a Smith, [BRIEF_FILE] in [INPUT_DIR] (smith/run-smith.sh):
 * the approved ticket, and what the rounds before it left.
 */
data class Brief(
    /** PR pushes so far: 0 before the first. */
    val round: Int,
    /** Null for a flow started without a snapshot (the tests); the Smith then reads the issue itself. */
    val ticket: Ticket? = null,
    val pr: PullRequest? = null,
    /** What the last review that sent it back asked for; a work Smith answers each by id. */
    val findings: List<Finding> = emptyList(),
    /** Every finding raised so far, by any review: what `work`'s ids refer to, for the reviewers. */
    val raised: List<Finding> = emptyList(),
    /** The last work Smith's result: what it addressed and declined, for the reviewers. */
    val work: Map<String, Any?>? = null,
    /** For a refine: what earlier refine runs asked and what the user answered, oldest first. */
    val answers: List<Answer> = emptyList(),
    /** For a bug: the reproduce Smith's result (root cause, repro), for the work, the reviews and the verify. */
    val reproduction: Map<String, Any?>? = null,
    /** For a feature: the design Smith's plan, for the work, the reviews and the acceptance. */
    val plan: Map<String, Any?>? = null,
    /**
     * For a split feature's replan: its `SplitState` (the children filed, with their states and PRs, and the
     * drafts left), plus `closedChild` and `closedChildPr`, the child whose merge this replan follows.
     */
    val split: Map<String, Any?>? = null,
    /** For a design run: the work run before it overflowed its context window, so the ticket should split. */
    val overflowed: Boolean = false,
    /**
     * For a security run on a merge of the default branch into the PR's branch: `default`, `oldHead`
     * (the branch before it, the merge's first parent) and `merge`. Only a security run is told.
     */
    val merge: Map<String, Any?>? = null,
    /**
     * For a fix or diagnose run (engine/README.md, "Fix and Propose a fix"): the incident the ticket's flow is stuck
     * on, `id`, `activityId`, `activityName`, `type`, `message`, `processInstanceId`, `processDefinition`,
     * `rootInstanceId`, `rootFlow`, a failed job's `stacktrace`, and `variables` (`root` and `failing`). Null for any other run.
     */
    val incident: Map<String, Any?>? = null,
    /**
     * For a proposal (engine/README.md, "Proposal"): the last draft's doc, `path` and `summary`, which the
     * review checks against the proposal checklist and a redraft rewrites. Null for any other flow.
     */
    val proposal: Map<String, Any?>? = null,
    /**
     * For a feature that builds an accepted proposal (engine/README.md, "Feature"): the doc's path, from the ticket
     * body's `Builds: docs/proposals/<slug>.md` line. The design plans from it; the work marks it built.
     */
    val builds: String? = null,
    /**
     * For a refine (engine/README.md, "Intake", "Is this ask already a ticket?"): the engine's line that the ask
     * looks like an open ticket, "This looks like #n ...", when Jev matched it; the user's answer is in `answers`.
     */
    val duplicate: String? = null,
    /**
     * Every earlier intake the ticket (for a refine, the ask and its answers) names, by intake id or ask item `#n`,
     * resolved as this run starts (engine/README.md, "Blocked by", "An earlier intake"): `ref`, `intake`, `repo`, `issue`,
     * `state` (`filed`, `refining`, `ended`), and the `ticket` it filed or how it `ended`. Never stored: each start re-resolves.
     */
    val intakes: List<IntakeRef> = emptyList(),
    /**
     * For a doctor run (engine/README.md, "Doctor"): the stuck ticket's `rootInstanceId`, `rootFlow`, `position`, `stuckSince`,
     * its `leaves` (each with its wait and why), open `incidents` (with a failed job's stacktrace), and `variables` (the root's).
     */
    val stuck: Map<String, Any?>? = null,
    /**
     * For a refine: the engine's tier map as the run starts (`TierTable`, engine/README.md, "Intake"), `tier`, `model`
     * (the full id) and `effort` for tiers 1-10, which the refiner chooses an unprescribed ticket's tier from.
     */
    val tiers: List<TierRow> = emptyList(),
    /**
     * For a mode `job` run (engine/README.md, "Custom jobs and the SDK"): the prompt the Smith is given verbatim,
     * `callable-smith`'s `prompt` variable. Null for every other run.
     */
    val prompt: String? = null,
    /**
     * For a research run after the first (engine/README.md, "Research"): every earlier round's `round`, `summary`
     * and `md` (its answer.md), oldest first; the follow-ups that led to each later round are in `answers`. The
     * Smith rewrites the page whole from the thread. Null for round 1 and every other flow.
     */
    val research: List<Map<String, Any?>>? = null,
    /**
     * The user's late additions to the ticket (amend_ticket), oldest first: binding like the ticket, whose body carries
     * them too in its `## Amendments` section. Empty when there are none.
     */
    val amendments: List<Amendment> = emptyList(),
    /**
     * For a child of a split feature: its parent's replan steers (engine/README.md, "Steer"), oldest first; binding
     * like an answered question. Empty when there are none.
     */
    val steer: List<SteerEntry> = emptyList(),
    /**
     * For a work run on a child of a split feature: the parent's number, from the child's part line. Only such a
     * run may return `split` (engine/README.md, "A child that splits"); null on any other ticket.
     */
    val parent: Long? = null,
    /**
     * The private documents the user keeps for the run's repo (engine/README.md, "Private documents"): each one's
     * `name` and `chars`, never its text, which the Smith reads with the frakworks MCP server's read_private_doc.
     * Empty when the repo has none.
     */
    val privateDocs: List<Map<String, Any?>> = emptyList(),
    /**
     * The files the user attached to an answer in this run's flow (engine/README.md, "Answer attachments"): each one's
     * `name`, `mime` and `size` in bytes, never its content, which the Smith reads with the frakworks MCP server's
     * read_attachment. Empty when there are none.
     */
    val attachments: List<Map<String, Any?>> = emptyList(),
    /**
     * For a refine: true when the ask's repo is docs-first (`engine.target_repo.docs_first`; engine/README.md, "Docs
     * path"), so the refiner defaults its ticket to kind chore at a low tier with no design run (bots/model-choice.md).
     * Null for every other run, and false for a refine in any other repo.
     */
    val docsFirst: Boolean? = null,
    /**
     * A feature's child-work mode, `serial`, `parallel` or `live` (engine/README.md, "Split features"): for a design run
     * the repo's `engine.target_repo.child_mode` as it is now; for any later run on the feature's instance (replan,
     * accept) the mode frozen on the feature instance when its design split (`Vars.CHILD_MODE`). The design
     * prompt steers how a split is cut by it. Null for a run of any other flow.
     */
    val childMode: String? = null,
    /**
     * The HITLP lineage the run works under (engine/README.md, "Lineage"): `asOf`, `links` (the ticket first, then its
     * intake and so on up), `truncated`. Captured once; the Smith passes it unchanged to any MCP server that takes one.
     */
    val lineage: Lineage? = null,
    /**
     * The engine work item the run's ticket was made into (engine/docs/work-items.md), as [WorkItemBriefs] gives it:
     * its untrusted text (an imported issue's) inside the untrusted fence. Null while the ticket has none.
     */
    val workItem: Map<String, Any?>? = null,
    /**
     * For a `parent` run (#2596, engine/docs/feature.md "Live parent"): every note the split's earlier parent sessions
     * wrote with note_for_next_parent, oldest first, each `{run, notedAt, note}`; empty when there are none. Null for a
     * run of any other mode.
     */
    val parentNotes: List<Map<String, Any?>>? = null,
    /**
     * For a `parent` run: the instant (ISO-8601) the run's Job is cut at, its start plus `engine.smith.k8s.active-deadline`;
     * await_children answers the time left to it. Null for a run of any other mode.
     */
    val deadline: String? = null,
    /**
     * For a `check` run (engine/docs/smith-runs.md, "The check mode"): the PR's `## POST-DEPLOYMENT-CHECK` section as
     * `what` (the prose to verify), `recheck` and `deadlineAfter` (ISO-8601 durations) and `deadlineAt` (an instant),
     * each null when the section gave none; `pr` (number, url, branch); `mergeSha`; `deployEvent` (the bus frame that
     * said the change is in production, null while none came); and `history`, every earlier run's `run`, `at`,
     * `outcome`, `summary`, `wakeAt` and `wakeEvent`, oldest first. Null for a run of any other mode.
     */
    val postDeployCheck: Map<String, Any?>? = null,
    /**
     * For a work run on a child of a split feature whose live parent is running (#100049; engine/docs/feature.md,
     * "Children ask the parent"): `{number}`, the parent's number. The child's `ask` goes to that parent, which
     * answers it or hands it to the user. Null whenever no live parent runs: a `parallel` or `serial` split, a live
     * split in fallback, a parent run that ended, or no split at all.
     */
    val liveParent: Map<String, Any?>? = null,
    /**
     * For the correctness review (mode `review`) after a passing engine check whose tests needed a retry
     * (engine/README.md, "Flaky tests"): `check` (its name), `outcome` (`passed`), `url` (the check run) and `flaky`,
     * each such test as `<Class> > <method>()`. The reviewer raises one only when the branch changed its code or
     * plausibly caused its flakiness; `main`'s build files the rest. Null for any other run, and when no test needed a retry.
     */
    val ci: Map<String, Any?>? = null,
    /**
     * A flaky-test ticket's bug flow ([Vars.FLAKY_TEST], set by `FlakyTests` alone; engine/README.md, "Flaky tests"):
     * fixed optimistically, with no reproduction, the changed test class run once and the cause named in the summary.
     */
    val flakyTest: Boolean = false,
) {
    fun toBytes(): ByteArray = engineJson.writeValueAsBytes(this)

    companion object {
        fun findings(bytes: ByteArray?): List<Finding> = bytes?.let { engineJson.readValue<List<Finding>>(it) }.orEmpty()
        fun findingsBytes(findings: List<Finding>): ByteArray = engineJson.writeValueAsBytes(findings)

        fun answers(bytes: ByteArray?): List<Answer> = bytes?.let { engineJson.readValue<List<Answer>>(it) }.orEmpty()
        fun answersBytes(answers: List<Answer>): ByteArray = engineJson.writeValueAsBytes(answers)

        fun amendments(bytes: ByteArray?): List<Amendment> = bytes?.let { engineJson.readValue<List<Amendment>>(it) }.orEmpty()
        fun amendmentsBytes(amendments: List<Amendment>): ByteArray = engineJson.writeValueAsBytes(amendments)

        fun steer(bytes: ByteArray?): List<SteerEntry> = bytes?.let { engineJson.readValue<List<SteerEntry>>(it) }.orEmpty()
        fun steerBytes(steer: List<SteerEntry>): ByteArray = engineJson.writeValueAsBytes(steer)
        /** The most characters a String variable holds: Operaton's TEXT_ column is varchar(4000); past it the flush fails. */
        const val STRING_VARIABLE_MAX = 4000

        /**
         * Free text (an intake's `ask`, `duplicateQuestion`, a refactoring run's `prompt`) as a bytes variable: a String
         * variable lives in Operaton's varchar(4000) TEXT_ column and a longer one fails the flush (#1238); bytes go to
         * ACT_GE_BYTEARRAY, unbounded.
         */
        fun textValue(text: String): BytesValue = Variables.byteArrayValue(text.toByteArray(Charsets.UTF_8))
        /** The text [textValue] stored, or a String variable as an intake started before #1238 has it; null otherwise. */
        fun text(value: Any?): String? = when (value) {
            is String -> value
            is ByteArray -> String(value, Charsets.UTF_8)
            else -> null
        }

        fun ticketBytes(ticket: Ticket): ByteArray = engineJson.writeValueAsBytes(ticket)
        fun ticket(bytes: ByteArray): Ticket = engineJson.readValue(bytes)

        fun mergeBytes(default: String, oldHead: String, merge: String): ByteArray =
            engineJson.writeValueAsBytes(mapOf("default" to default, "oldHead" to oldHead, "merge" to merge))
        fun merge(bytes: ByteArray): Map<String, Any?> = engineJson.readValue(bytes)

        fun incidentBytes(incident: Map<String, Any?>): ByteArray = engineJson.writeValueAsBytes(incident)
        fun stuckBytes(stuck: Map<String, Any?>): ByteArray = engineJson.writeValueAsBytes(stuck)

        /** A line of its own, `Builds: docs/proposals/<slug>.md`; `Builds on #200` in prose is not one. */
        private val BUILDS = Regex("""(?m)^Builds:[ \t]*(docs/proposals/\S+\.md)[ \t]*$""")

        /**
         * A split's filed children as the replan reads them: each with its `state`, its `prNumber` (an open child's open PR
         * too, its flow's `pr` variable) and `running`, whether the flow the engine started on it still runs.
         */
        private fun live(execution: DelegateExecution, filed: Any?): List<Map<String, Any?>> =
            (filed as? List<*>).orEmpty().filterIsInstance<Map<String, Any?>>().map { child ->
                val instance = child["instance"] as? String
                val services = runCatching { execution.processEngineServices }.getOrNull()
                val running = instance != null && services != null &&
                    services.runtimeService.createProcessInstanceQuery().processInstanceId(instance).count() > 0
                val pr = child["prNumber"] ?: instance?.takeIf { services != null }?.let {
                    if (running) services!!.runtimeService.getVariable(it, Vars.PR)
                    else services!!.historyService.createHistoricVariableInstanceQuery().processInstanceId(it).variableName(Vars.PR).singleResult()?.value
                }
                child + mapOf("prNumber" to pr, "running" to running)
            }

        /** The split feature a child's body names in its part line (`Part 2 of 3 of #12.`), or null. */
        fun parent(body: String): Long? = PART_LINE.find(body)?.groupValues?.get(3)?.toLongOrNull()

        /** The proposal doc a ticket body says it builds, or null. */
        fun builds(body: String): String? = BUILDS.find(body.replace("\r\n", "\n"))?.groupValues?.get(1)

        /**
         * The ticket from the flow's fenced work-item [section] when the engine took no approved snapshot: the body is the
         * description as the section carries it (untrusted text inside its UNTRUSTED fence), the title its first line when
         * trusted, else a fixed one naming the source, so no untrusted text ever stands outside the fence.
         */
        fun ticketFromWorkItem(number: Long, section: Map<String, Any?>): Ticket? {
            val description = (section["description"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            @Suppress("UNCHECKED_CAST") val source = section["source"] as? Map<String, Any?>
            val kind = source?.get("kind") as? String ?: "engine"
            val title = if (section["trusted"] == true) description.lineSequence().first { it.isNotBlank() }.trim().take(120)
                else "Work item from $kind"
            return Ticket(number, title, description, "engine")
        }

        /** The brief for the run about to start, or null when there is nothing to say; [intakes], a refine's [tiers] and [docsFirst], a feature's [childMode], the repo's [privateDocs], the flow's answer [attachments], the ticket's fenced [workItem], a parent run's [parentNotes] and [deadline], and a child's running [liveParent] as `StartSmithDelegate` read them. */
        fun of(execution: DelegateExecution, intakes: List<IntakeRef> = emptyList(), tiers: List<TierRow> = emptyList(),
               privateDocs: List<Map<String, Any?>> = emptyList(), attachments: List<Map<String, Any?>> = emptyList(),
               docsFirst: Boolean? = null, lineage: Lineage? = null, childMode: String? = null,
               workItem: Map<String, Any?>? = null, parentNotes: List<Map<String, Any?>>? = null,
               deadline: String? = null, liveParent: Long? = null): ByteArray? {
            val round = execution.round()
            val ticket = (execution.getVariable(Vars.TICKET) as? ByteArray)?.let { engineJson.readValue<Ticket>(it) }
                ?: workItem?.let { ticketFromWorkItem(execution.issueOrNull() ?: 0, it) }
            val findings = findings(execution.getVariable(Vars.FINDINGS) as? ByteArray)
            val raised = findings(execution.getVariable(Vars.RAISED) as? ByteArray)
            val answers = answers(execution.getVariable(Vars.ANSWERS) as? ByteArray)
            val reproduction = (execution.getVariable(Vars.REPRODUCTION) as? ByteArray)?.let { engineJson.readValue<Map<String, Any?>>(it) }
            val plan = (execution.getVariable(Vars.PLAN) as? ByteArray)?.let { engineJson.readValue<Map<String, Any?>>(it) }
            val split = (execution.getVariable(Vars.SPLIT) as? ByteArray)?.let { engineJson.readValue<Map<String, Any?>>(it) }?.let { state ->
                state + mapOf("filed" to live(execution, state["filed"]), "max" to ChildDraft.MAX, "closedChild" to execution.getVariable(Vars.CLOSED_CHILD), "closedChildPr" to execution.getVariable(Vars.CLOSED_CHILD_PR),
                    "closedChildSplit" to (execution.getVariable(Vars.CLOSED_CHILD_SPLIT) as? ByteArray)?.let { engineJson.readValue<List<Map<String, Any?>>>(it) })
            }
            val overflowed = execution.getVariable(Vars.OVERFLOWED) == true
            val merge = (execution.getVariable(Vars.MERGE) as? ByteArray)?.takeIf { execution.getVariable(Vars.MODE) == "security" }
                ?.let { engineJson.readValue<Map<String, Any?>>(it) }
            val incident = (execution.getVariable(Vars.INCIDENT) as? ByteArray)?.let { engineJson.readValue<Map<String, Any?>>(it) }
            val proposal = (execution.getVariable(Vars.PROPOSAL) as? ByteArray)?.let { engineJson.readValue<Map<String, Any?>>(it) }
                ?.takeIf { it["path"] != null }?.let { mapOf("path" to it["path"], "summary" to it["summary"]) }
            val builds = ticket?.body?.let { builds(it) }
            val duplicate = (execution.getVariable(Vars.DUPLICATE) as? String)?.takeIf { execution.getVariable(Vars.MODE) == "refine" }
            val stuck = (execution.getVariable(Vars.STUCK) as? ByteArray)?.takeIf { execution.getVariable(Vars.MODE) == "doctor" }
                ?.let { engineJson.readValue<Map<String, Any?>>(it) }
            // A String, or the bytes a long prompt (a refactoring run's) is stored as: `textValue`.
            val prompt = text(execution.getVariable(Vars.PROMPT))?.takeIf { execution.getVariable(Vars.MODE) == "job" }
            val research = (execution.getVariable(Vars.RESEARCH_THREAD) as? ByteArray)?.let { engineJson.readValue<List<Map<String, Any?>>>(it) }?.takeIf { it.isNotEmpty() }
            val amendments = amendments(execution.getVariable(Vars.AMENDMENTS) as? ByteArray)
            val steer = steer(execution.getVariable(Vars.STEER) as? ByteArray)
            val parent = ticket?.body?.takeIf { execution.getVariable(Vars.MODE) == "work" }?.let { parent(it) }
            val pr = (execution.getVariable(Vars.PR) as? Number)?.let {
                PullRequest(it.toLong(), execution.getVariable(Vars.PR_URL) as? String ?: "", execution.getVariable(Vars.BRANCH) as? String ?: "")
            }
            val postDeployCheck = postDeployCheck(execution, pr)
            val ci = (execution.getVariable(Vars.CI_FLAKY) as? String)?.takeIf { it.isNotBlank() && execution.getVariable(Vars.MODE) == "review" &&
                execution.getVariable(Vars.CI_FLAKY_HEAD) == execution.getVariable(Vars.HEAD_SHA) }
                ?.let { mapOf("check" to execution.getVariable(Vars.CI_CHECK), "outcome" to execution.getVariable(Vars.CI_OUTCOME),
                    "url" to execution.getVariable(Vars.CI_RUN_URL), "flaky" to it.lines().filter { l -> l.isNotBlank() }) }
            if (round == 0 && amendments.isEmpty() && steer.isEmpty() && raised.isEmpty() && ticket == null && answers.isEmpty() && reproduction == null && plan == null && split == null && !overflowed && merge == null && incident == null && proposal == null && builds == null && duplicate == null && intakes.isEmpty() && stuck == null && tiers.isEmpty() && prompt == null && research == null && privateDocs.isEmpty() && attachments.isEmpty() && docsFirst == null && lineage == null && childMode == null && workItem == null && parentNotes == null && deadline == null && postDeployCheck == null && liveParent == null && ci == null && execution.getVariable(Vars.FLAKY_TEST) != true) return null
            val work = (execution.getVariable(Vars.WORK) as? ByteArray)?.let { engineJson.readValue<Map<String, Any?>>(it) }
            return Brief(round, ticket, pr, findings, raised, work, answers, reproduction, plan, split, overflowed, merge, incident, proposal, builds, duplicate, intakes, stuck, tiers, prompt, research, amendments, steer, parent, privateDocs, attachments, docsFirst, childMode, lineage, workItem, parentNotes, deadline, postDeployCheck,
                liveParent = liveParent?.takeIf { execution.getVariable(Vars.MODE) == "work" }?.let { mapOf("number" to it) }, ci = ci,
                flakyTest = execution.getVariable(Vars.FLAKY_TEST) == true).toBytes()
        }

        /**
         * A `check` run's [Brief.postDeployCheck]: the section ([Vars.POST_DEPLOY_CHECK], JSON bytes as the postdeploy
         * package writes them), the PR, [Vars.MERGE_SHA], [Vars.DEPLOY_EVENT] and [Vars.CHECK_HISTORY]. Null for any
         * other mode, or when the flow carries no section.
         */
        fun postDeployCheck(execution: DelegateExecution, pr: PullRequest?): Map<String, Any?>? {
            if (execution.getVariable(Vars.MODE) != "check") return null
            val section = (execution.getVariable(Vars.POST_DEPLOY_CHECK) as? ByteArray)?.let { engineJson.readValue<Map<String, Any?>>(it) } ?: return null
            val deploy = (execution.getVariable(Vars.DEPLOY_EVENT) as? ByteArray)?.let { engineJson.readValue<Map<String, Any?>>(it) }
            val history = (execution.getVariable(Vars.CHECK_HISTORY) as? ByteArray)?.let { engineJson.readValue<List<Map<String, Any?>>>(it) }.orEmpty()
            return section + mapOf("pr" to pr, "mergeSha" to (execution.getVariable(Vars.MERGE_SHA) as? String), "deployEvent" to deploy, "history" to history)
        }
    }
}

/**
 * The work item section of a run's brief (engine/docs/work-items.md): the item made for [repo]'s issue [issue], with
 * its untrusted text fenced, or null when there is none. engine-api's work-item store answers it.
 */
fun interface WorkItemBriefs {
    fun section(repo: String, issue: Long): Map<String, Any?>?
}

/**
 * A live parent's notes for the next wave (#2596, engine/docs/feature.md "Live parent"): every note the split's earlier
 * parent sessions wrote with note_for_next_parent, oldest first, each `{run, notedAt, note}`, for the feature instance
 * [instance]. engine-api's parent note store answers it.
 */
fun interface ParentNotes {
    fun notes(instance: String): List<Map<String, Any?>>
}
