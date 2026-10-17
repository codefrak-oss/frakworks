package com.codefrak.engine.intake

import com.codefrak.engine.accounts.TitleSummarizer
import com.codefrak.engine.camunda.Actor
import com.codefrak.engine.events.CreatedEvents
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.github.issueUrl
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.Ticket
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.workitems.Tickets
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `task-open-ask` in bpmn/intake.bpmn: opens the ask as its `ask` work item ([WorkItemFiling.openAsk], engine/docs/work-items.md,
 * "Asks as work items") in the instance's `repo` (POST /intake's; an instance without one fails here) and keeps `repo`,
 * `issue` (the ask item's ticket number) and `issueUrl` for the Smith run. Nothing is written to GitHub: the intake's
 * comments, its question and its close go to the item as entries and state ([com.codefrak.engine.workitems.Tickets]).
 * Its board row goes on the stream at once ([CreatedEvents]). Sets `authorizedBy` to the submitter when they are a
 * trusted login for that repo, else null (the intake then waits until a trusted login starts the ask item,
 * `POST /work-items/{id}/start` or First mate's `approve`; always null when `authorize` is false), and the snapshot
 * the refining Smith works from.
 * An intake the engine started as one of its own actors ([AUTHORIZED_BY_ENGINE]: the doctor, the friction grouper) is
 * authorized by that actor, `authorizedBy` its name ("the engine's doctor", "the engine's friction"), which no login can be.
 * Bound by `${openAskItem}`.
 */
@Component("openAskItem")
class OpenAskItemDelegate(
    private val tickets: Tickets,
    private val items: WorkItemFiling,
    private val repos: TargetRepos,
    private val access: Access,
    private val events: EngineEvents,
    private val createdEvents: CreatedEvents,
    private val links: IntakeTickets,
    private val summarizer: TitleSummarizer,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(OpenAskItemDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val ask = Brief.text(execution.getVariable("ask"))!!
        val title = titleFor(ask, execution.processInstanceId)
        val fromRun = (execution.getVariable(REQUESTED_BY_RUN) as? String)?.let {
            SmithCaller(it, (execution.getVariable(REQUESTED_BY_ISSUE) as? Number)?.toLong(), execution.getVariable(REQUESTED_BY_REPO) as? String)
        }
        val body = body(ask, execution.processInstanceId, fromRun, Brief.text(execution.getVariable(CONTEXT)))
        // POST /intake's `repo`, checked there. There is no default repo: an instance started without one is an incident.
        val repo = execution.getVariable(Vars.REPO) as? String
            ?: throw IllegalStateException("the intake has no `repo` variable: every intake names the target repo it files in")
        val submitter = execution.getVariable(SUBMITTED_BY) as? String
        val item = items.openAsk(repo, execution.processInstanceId, "# $title\n\n${body.trimEnd()}", submitter)
        val url = issueUrl(repo, item.ticket)
        // Its board row once the item is committed: the row is read from the store, which holds it only then.
        events.afterCommit { createdEvents.created(repo, item.ticket, url) }
        execution.setVariable(Vars.REPO, repo)
        execution.setVariable("issue", item.ticket)
        execution.setVariable("issueUrl", url)
        execution.setVariable(ASK_ITEM, item.id)
        links.opened(execution.processInstanceId, repo, item.ticket, url)
        // The submitter authorizes it if they may; otherwise it waits for a trusted login to start the ask item.
        val system = (execution.getVariable(AUTHORIZED_BY_ENGINE) as? String)?.let { Actor.System(it) }
        val authorized = system != null ||
            execution.getVariable(AUTHORIZE) != false && submitter != null && repos.contains(repo) && access.trusted(submitter)
        execution.setVariable(AUTHORIZED_BY, if (system != null) system.name else if (authorized) submitter else null)
        // What the refining Smith works from: the ask as submitted.
        execution.setVariable(Vars.TICKET, Variables.byteArrayValue(Brief.ticketBytes(Ticket(item.ticket, title, body, submitter ?: "engine"))))
        if (system != null) tickets.commentOn(repo, item.ticket, "Authorized by ${system.name}.\n\n— engine")
        else if (authorized) tickets.commentOn(repo, item.ticket, "Authorized by its submitter, @$submitter.\n\n— engine")
        else tickets.commentOn(repo, item.ticket, "Waiting for a trusted login to start it (`POST /work-items/${item.id}/start` or First mate's `approve`)" +
            "${submitter?.let { " (the submitter, @$it, is not a trusted login)" }.orEmpty()}.\n\n— engine")
        log.info("intake {}: opened ask item {} (#{}), {}", execution.processInstanceId, item.id, item.ticket,
            if (system != null) "authorized by ${system.name}" else if (authorized) "authorized by @$submitter" else "waiting")
        // What the composer waits for (engine/README.md, "Events"): the ask item's ticket number and URL, once they are committed.
        events.publishAfterCommit(EngineEvent.Intake(repo, execution.processInstanceId, "issue", item.ticket, url))
    }

    /**
     * `Intake: ` + the summarizer's title of the change asked for; when it has none (no account,
     * an error, a timeout, an empty answer), [title] of the ask and a warn. Never fails the intake.
     */
    private fun titleFor(ask: String, id: String): String {
        val answer = runCatching { summarizer.summarize(withoutTier(ask)) }
            .getOrElse { com.codefrak.engine.accounts.TitleAnswer(null, it.message ?: it.javaClass.simpleName) }
        answer.title?.let { generatedTitle(it) }?.let { return it }
        log.warn("intake {}: title summary unavailable ({}), using the ask's first line", id, answer.why ?: "no answer")
        return title(ask)
    }

    companion object {
        private const val TITLE_PREFIX = "Intake: "
        private const val TITLE_MAX = 80

        /**
         * `Intake: ` + the ask's first non-empty line, cut to 80. Unlike the retired tickets
         * MCP's `render_title` (bots/tickets-mcp, removed 2026-09-27), a leading
         * `Tier: ...` line (the `submit_ask` convention from CLAUDE.md, "Filing
         * tickets: the user names the tier, every time") is skipped in favour of the
         * next non-empty line, so the tier prescription doesn't take the title's
         * place. If the ask is nothing but that line, it is used as-is.
         */
        fun title(ask: String): String {
            val nonEmpty = ask.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.iterator()
            var first = if (nonEmpty.hasNext()) nonEmpty.next() else ""
            if (first.startsWith("Tier:") && nonEmpty.hasNext()) first = nonEmpty.next()
            return TITLE_PREFIX + cut(first.trimEnd('.'))
        }

        /** `Intake: ` + a generated summary, cut like [title]; null for a blank one. */
        fun generatedTitle(summary: String): String? =
            summary.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.trimEnd('.')?.ifEmpty { null }?.let { TITLE_PREFIX + cut(it) }

        /** The ask without a leading `Tier: ...` line, as [title] skips it: what the summarizer reads. */
        fun withoutTier(ask: String): String {
            val lines = ask.trim().lines()
            return if (lines.size > 1 && lines.first().trim().startsWith("Tier:")) lines.drop(1).joinToString("\n").trim() else ask.trim()
        }

        /**
         * [text] fitted to what [TITLE_MAX] leaves after the prefix: a longer one is cut at the last
         * word boundary that leaves room for `…`, never mid-word (mid-word only for a single word longer than the room).
         */
        fun cut(text: String): String {
            val room = TITLE_MAX - TITLE_PREFIX.length
            if (text.length <= room) return text
            val head = text.take(room - 1)
            val boundary = if (text[room - 1].isWhitespace()) head.length else head.lastIndexOf(' ')
            val kept = (if (boundary > 0) head.take(boundary) else head).trimEnd().trimEnd(',', ';', ':', '—', '-').trimEnd()
            return "$kept…"
        }

        fun body(ask: String, intakeId: String, fromRun: SmithCaller? = null, context: String? = null): String = buildString {
            appendLine("## Goal").appendLine()
            appendLine("Turn the ask below into a filed, released bot-task — or ask the user what they meant. " +
                "**Do not do the work the ask describes.** The ask is verbatim.").appendLine()
            ask.trim().lines().forEach { appendLine("> $it".trimEnd()) }
            context?.trim()?.takeIf { it.isNotEmpty() }?.let {
                appendLine().appendLine("### Conversation context").appendLine()
                appendLine("_Written by the filing session from the conversation before the ask, not the user's words. " +
                    "It informs the ask; the quote above binds._").appendLine()
                appendLine(it)
            }
            appendLine().appendLine("## Context").appendLine()
            appendLine("- Filed by the engine's `intake` process, instance `$intakeId` (engine/README.md, \"Intake\").")
            fromRun?.let { r ->
                appendLine("- Filed by Smith run `${r.runId}`${r.issue?.let { " working #$it" }.orEmpty()}${r.repo?.let { " in $it" }.orEmpty()}, " +
                    "not by a person: it waits for a trusted login to start it (engine/README.md, \"The frakworks server in a Smith\").")
            }
        }
    }
}
