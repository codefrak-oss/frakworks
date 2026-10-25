package com.codefrak.engine.mcp

import com.codefrak.engine.EngineBuild
import com.codefrak.engine.ci.CheckRuns
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.firstmate.DesktopChannel
import com.codefrak.engine.friction.FrictionFixLinks
import com.codefrak.engine.friction.FrictionLog
import com.codefrak.engine.github.GitHubCompare
import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.hitlp.HitlpRequester
import com.codefrak.engine.intake.AnswerAttachments
import com.codefrak.engine.repos.CompareCommits
import com.codefrak.engine.repos.McpProbe
import com.codefrak.engine.repos.RepoDocs
import com.codefrak.engine.repos.RepoMcpServers
import com.codefrak.engine.repos.TicketTitles
import com.codefrak.engine.tier.TierTable
import com.codefrak.engine.workitems.WorkItemStore
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.Locale

/**
 * The frakworks MCP server, `POST /mcp/frakworks` (engine/README.md,
 * "Engine-served MCP servers"): First mate's write path (issue #290), transcript reader (#352, [TranscriptTool])
 * and a Smith's way to file discovered work. Moved here from the desktop's stdio `src/frakworks-mcp.js`
 * (issue #541) with the same tools, schemas and error texts; act_on_incident
 * (#539) and read_doctor_history (#658) live on [DiagnosticsMcpServer].
 * read_ticket_pr (#742) reads a ticket's PR: its files, one file's text, or its diff.
 * approve (#799) authorizes an ask item as the caller's login, in-process, not by a label.
 * cancel_intake, amend_intake (#1173) and amend_ticket (#1362) live in [IntakeTools]: First mate's only.
 * reauthorize (#850) does the same for a ticket whose blocker closed without a merge (a `reauthorize` need).
 * read_ticket_runs (#1162, [TicketRunsTool]) lists a ticket's Smith runs, each review's findings with it; read_transcript's `run` reads one of them.
 * read_repos, add_repo, edit_repo and remove_repo (#1021) are GET, POST, PATCH and DELETE /repos, in-process
 * ([FrakworksEngine.repos] and on): First mate's only, the writes with a login. No tool sets or clears a repo's
 * token or webhook secret: a secret passed through a tool call lands in First mate's context.
 * read_ci_runs (#1629) reads a target repo's Actions workflow runs and their median duration ([CiRunsTool]): First mate's only.
 * read_ci_jobs, read_ci_job and search_ci_log (#2188) read a run's jobs and steps and a job's log, bounded ([CiJobsTool]), and
 * rerun_ci_jobs (#2249) re-runs failed jobs: First mate's on any target repo, a Smith's on its run's repo only (#100023).
 * close_ticket (#1170) closes a ticket with no running flow as the outcome the caller names, `completed` or `not_planned`
 * (required, no default), as the caller's login, where [Access][com.codefrak.engine.repos.Access]
 * lets it act on the ticket's kind; a ticket whose flow runs is act_on_incident's cancel.
 * read_access, add_access and remove_access (#1636) are GET, POST and DELETE /access, in-process ([AccessTool]):
 * First mate's only, the writes `by` the caller's login, and remove_access refuses the caller's own.
 * comment_ticket (#1627) posts the user's comment on a ticket with the repo's token, signed with the caller's login:
 * First mate's only; a Smith's call is an unknown tool, as close_ticket's is.
 * search_tickets (#1625, [SearchTool]) finds a target repo's work items by their words: First mate's only.
 * list_private_docs and read_private_doc (#1711, [PrivateDocTools]) read a target repo's private docs: a Smith its own run's
 * repo's, with no `repo` argument; First mate (#1735) any target repo's, `repo` required.
 * read_ticket (#1735, [TicketTool]) reads a ticket's title, state, labels and body: First mate's only.
 * list_attachments and read_attachment (#1730, [AttachmentTools]) read the files the user attached to an answer in a Smith's own flow: a Smith's only.
 * steer_child, await_children, note_for_next_parent, and a Smith's read_ticket_runs, read_transcript and read_ticket (#2528, [ParentTools]): a `parent` run's,
 * on its own split's children only; listed to every Smith, refused naming the run for any other.
 * read_needs (#476) reads what needs the user, now in [NeedsTools]; it is decided on the HITLP server.
 * push_chunk (#100043, [PushChunkTool]) pushes a work run's commits so far to its `smith/issue-<n>` branch mid-run, fast-forward
 * only, and publishes the `push` event a live parent's await_children wakes on: listed to a `work` run on a ticket only.
 * screenshot_desktop (#100105, [ScreenshotTool]) asks the caller's desktop app for a screenshot over its desktop channel:
 * First mate's only, with a login; a Smith neither lists it nor may call it.
 * read_version (#100101, [VersionTool]) answers the engine's build version and commit, as `GET /version` does: every role's.
 * read_desktop_release (#100117, [DesktopReleaseTool]) maps a desktop version to its commit and tickets, and a ticket to the first release shipping it: every role's.
 * hitlp_ask, hitlp_approve and hitlp_task (#100138, [HitlpTools]) put a question or an approval to the user on the
 * configured HITLP server, answering the task id at once, and read a task's state and decision: First mate's only;
 * absent (no [HitlpRequester] given) they are not listed.
 *
 * First mate's role ([McpCaller.Nobody]) lists [TOOLS]; its login is the
 * one its bearer token proved. A Smith's role lists [SMITH_TOOLS] only:
 * `submit_ask`, which lives in [AskTool], filed as its run (found by address), with no
 * submitter and never authorized, so it waits for a human's `authorized`
 * label (engine/README.md, "The frakworks server in a Smith"). And `note_friction`, which lives in [FrictionNoteTool]
 * (#876, #968): a Smith's as-you-go note, and First mate's.
 */
@Component
class FrakworksMcp(private val engine: FrakworksEngine, private val tiers: TierTable, friction: FrictionLog,
                   fixes: FrictionFixLinks? = null, checkRuns: CheckRuns? = null, docs: RepoDocs? = null,
                   attachments: AnswerAttachments? = null, mcpServers: RepoMcpServers? = null,
                   probe: McpProbe? = null, childWatch: ChildWatch? = null,
                   workItems: WorkItemStore? = null, parentNotes: ParentNoteLog? = null,
                   git: GitPush? = null, pulls: GitHubPulls? = null, events: EngineEvents? = null,
                   build: EngineBuild? = null, desktops: DesktopChannel? = null,
                   compare: CompareCommits? = null, titles: TicketTitles? = null, gitCompare: GitHubCompare? = null,
                   details: IssueDetails? = null, merges: TicketMerges? = details?.let { d -> TicketMerges { r, n -> d.ticket(r, n)?.prs } },
                   hitlp: HitlpRequester? = null) : EngineMcpServer {
    private val askTool = AskTool(engine, tiers)
    private val frictionNoteTool = FrictionNoteTool(friction, engine)
    private val fixTool = fixes?.let { FrictionFixTool(it) }
    private val fixIntakeTool = fixes?.let { FrictionFixIntakeTool(it) }
    private val repoTool = RepoTool(engine)
    private val accessTool = AccessTool(engine)
    private val tokenTool = TokenTool(engine)
    private val ticketPrTool = TicketPrTool(engine)
    private val ticketWriteTools = TicketWriteTools(engine, workItems)
    private val ticketRunsTool = TicketRunsTool(engine)
    private val approvalTool = ApprovalTool(engine, workItems)
    private val intakeTools = IntakeTools(engine)
    private val needsTools = NeedsTools(engine)
    private val searchTool = SearchTool(engine, workItems)
    private val ciLogTool = CiLogTool(checkRuns)
    private val ciRunsTool = CiRunsTool(engine, checkRuns)
    private val ciJobsTool = CiJobsTool(engine, checkRuns)
    private val runLogTool = RunLogTool(engine)
    private val ticketTool = TicketTool(engine, workItems)
    private val transcriptTool = TranscriptTool(engine)
    private val docTools = PrivateDocTools(docs, engine)
    private val mcpServerTools = McpServerTools(mcpServers, probe, engine)
    private val parentTools = ParentTools(engine, childWatch, parentNotes, workItems = workItems)
    private val attachmentTools = AttachmentTools(attachments, engine::runRoot)
    private val workItemTools = workItems?.let { WorkItemTools(it) }
    private val pushChunkTool = PushChunkTool(engine, git, pulls, events)
    private val versionTool = VersionTool(build)
    private val desktopReleaseTool = DesktopReleaseTool(engine, checkRuns, compare, titles, gitCompare, merges)
    private val screenshotTool = ScreenshotTool(desktops)
    private val hitlpTools = hitlp?.let { HitlpTools(it) }
    override val name = NAME
    override val version = "1"

    companion object {
        /**
         * Most characters of rendered events read_transcript returns: the result lands in First mate's context on
         * every call, and about 6k tokens of the newest events answers "what is it doing now?".
         */
        const val NAME = "frakworks"
        /** Most characters of a file's text or a diff read_ticket_pr returns, for the same reason as [TranscriptTool.TRANSCRIPT_CHAR_BUDGET]. */
        const val PR_CHAR_BUDGET = 24000
        const val MAX_ANSWER_CHARS = com.codefrak.engine.intake.MAX_ANSWER_CHARS

        /** owner/name; neither part only dots. */
        val REPO_FULL = com.codefrak.engine.mcp.REPO_FULL

        /** First mate's repo tools: a Smith's call to one is refused naming its run. */
        val REPO_TOOLS = RepoTool.NAMES
        /** First mate's access tools: a Smith's call to one is refused naming its run. */
        val ACCESS_TOOLS = AccessTool.NAMES
        /** First mate's token tools ([TokenTool.NAMES]): a Smith's call to one is refused naming its run. */
        val TOKEN_TOOLS = TokenTool.NAMES
        /** First mate's intake tools ([IntakeTools.INTAKE_TOOLS]): a Smith's call to one is refused naming its run. */
        val INTAKE_TOOLS = IntakeTools.INTAKE_TOOLS
        /** First mate's other tools a Smith's call is refused, naming its run: friction groups are the user's to judge. */
        val MATE_ONLY_TOOLS = listOf(FrictionFixTool.NAME, CiRunsTool.NAME)

        val TOOLS: List<McpTool> = listOf(
            AskTool.TOOL,
            TranscriptTool.TOOL,
            NeedsTools.READ_NEEDS,
        ) + TicketPrTool.TOOL + listOf(
            ApprovalTool.APPROVE,
            ApprovalTool.REAUTHORIZE,
            FrictionNoteTool.TOOL,
        ) + RepoTool.TOOLS + CiRunsTool.TOOL + IntakeTools.TOOL + TicketWriteTools.TOOL + listOf(
            TicketRunsTool.READ_TICKET_RUNS_TOOL,
            SearchTool.TOOL,
        ) + AccessTool.TOOLS + TokenTool.TOOLS + listOf(TicketTool.TOOL, PrivateDocTools.MATE_LIST_TOOL, PrivateDocTools.MATE_READ_TOOL) +
            McpServerTools.TOOLS + CiJobsTool.TOOLS + VersionTool.TOOL + DesktopReleaseTool.TOOL + ScreenshotTool.TOOL

        /** The tools a Smith may call (engine/README.md, "The frakworks server in a Smith"). */
        val SMITH_TOOLS: List<McpTool> = listOf(
            AskTool.SMITH_TOOL,
            FrictionNoteTool.SMITH_TOOL,
            CiLogTool.TOOL,
            RunLogTool.TOOL,
            PrivateDocTools.LIST_TOOL,
            PrivateDocTools.READ_TOOL,
            AttachmentTools.LIST_TOOL,
            AttachmentTools.READ_TOOL,
        ) + ParentTools.TOOLS + CiJobsTool.TOOLS + VersionTool.TOOL + DesktopReleaseTool.TOOL

        /** A JSON integer above zero (a whole float too, as JavaScript's `Number.isInteger` takes it), else null. */
        fun ticketOf(v: Any?): Long? = wholeNumberOf(v)?.takeIf { it > 0 }

        /** A page offset: a whole number 0 or more as an Int, else null. */
        fun offsetOf(v: Any?): Int? = wholeNumberOf(v)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()

        /**
         * The page of [text] from [offset], at most [budget] characters: led by a line saying where it starts unless
         * that is 0, and ended by a line naming the next page's offset when text remains. Null for an offset past the end.
         */
        fun pageText(text: String, offset: Int = 0, budget: Int = PR_CHAR_BUDGET): String? {
            if (offset < 0 || (offset > 0 && offset >= text.length)) return null
            val end = minOf(text.length, offset + budget)
            val head = if (offset > 0) "(characters $offset–$end of ${text.length})\n" else ""
            val rest = text.length - end
            val tail = if (rest > 0) "\n… ${"%,d".format(Locale.US, rest)} more characters; pass offset: $end for the next page." else ""
            return head + text.substring(offset, end) + tail
        }
    }

    /**
     * First mate's tools: [TOOLS], and link_friction_fix ([FrictionFixTool]) and link_friction_fix_intake
     * ([FrictionFixIntakeTool]) when the engine has the friction groups.
     */
    /** Tools [EngineMcpController] streams: await_children ([ParentTools.STREAMED]). */
    override val streamed: Set<String> get() = ParentTools.STREAMED + ScreenshotTool.NAME

    override fun tools() = TOOLS + (if (fixTool == null) emptyList() else listOf(FrictionFixTool.TOOL, FrictionFixIntakeTool.TOOL)) +
        (if (hitlpTools == null) emptyList() else HitlpTools.TOOLS)

    /**
     * A Smith's lists [SMITH_TOOLS], and link_friction_fix_intake with the friction groups, [WorkItemTools] with the
     * work-item store and push_chunk for a `work` run on a ticket ([PushChunkTool.listed]); a caller Docker could not
     * place lists [SMITH_TOOLS] only: it fails closed, never as First mate.
     */
    override fun tools(caller: McpCaller) = when {
        caller is McpCaller.Nobody -> tools()
        caller is McpCaller.Smith -> SMITH_TOOLS + listOfNotNull(fixIntakeTool?.let { FrictionFixIntakeTool.TOOL }) +
            (if (workItemTools != null) WorkItemTools.TOOLS else emptyList()) +
            listOfNotNull(PushChunkTool.TOOL.takeIf { pushChunkTool.listed(caller) })
        else -> SMITH_TOOLS
    }

    override fun call(tool: String, args: Map<String, Any?>, caller: McpCaller): McpToolResult = try {
        when (caller) {
            is McpCaller.Unknown -> McpToolResult.error("cannot tell whether the caller is a Smith sandbox: ${caller.why}")
            is McpCaller.Smith -> when (tool) {
                "submit_ask" -> askTool.call(args, caller)
                "note_friction" -> frictionNoteTool.call(args, caller)
                CiLogTool.NAME -> ciLogTool.call(args, caller)
                RunLogTool.NAME -> runLogTool.call(args, caller)
                PrivateDocTools.LIST -> docTools.list(args, caller)
                PrivateDocTools.READ -> docTools.read(args, caller)
                AttachmentTools.LIST -> attachmentTools.list(args, caller)
                AttachmentTools.READ -> attachmentTools.read(args, caller)
                in ParentTools.NAMES -> parentTools.call(tool, args, caller)
                PushChunkTool.NAME -> pushChunkTool.call(args, caller)
                VersionTool.NAME -> versionTool.call(args)
                DesktopReleaseTool.NAME -> desktopReleaseTool.call(args)
                in WorkItemTools.NAMES -> workItemTools?.call(tool, args, caller) ?: McpToolResult.error("unknown tool: ${json(tool)}")
                FrictionFixIntakeTool.NAME -> fixIntakeTool?.call(args, "smith run ${caller.caller.runId}") ?: McpToolResult.error("unknown tool: ${json(tool)}")
                in REPO_TOOLS -> mateOnly(caller, tool, "target repos are First mate's to manage")
                in ACCESS_TOOLS -> mateOnly(caller, tool, "the trusted logins are First mate's to manage")
                in TOKEN_TOOLS -> mateOnly(caller, tool, "engine tokens are First mate's to manage")
                in McpServerTools.NAMES -> mateOnly(caller, tool, "First mate's MCP servers are the user's to manage")
                in IntakeTools.INTAKE_TOOLS -> mateOnly(caller, tool, "intakes are the user's to cancel or amend")
                SearchTool.NAME -> mateOnly(caller, tool, "it is First mate's, for the user")
                ScreenshotTool.NAME -> mateOnly(caller, tool, "the user's desktop is First mate's to see, never a Smith's")
                IntakeTools.AMEND_TICKET -> mateOnly(caller, tool, "tickets are the user's to amend")
                CiRunsTool.NAME -> mateOnly(caller, tool, "CI run history is First mate's to read")
                in HitlpTools.NAMES -> mateOnly(caller, tool, "asking the user through HITLP is First mate's; a Smith asks through its result")
                in CiJobsTool.NAMES -> caller.caller.repo?.let { ciJobsTool.call(tool, args, it) }
                    ?: McpToolResult.error("no run repo for run ${caller.caller.runId}: $tool reads only a Smith's run's repo")
                in MATE_ONLY_TOOLS -> mateOnly(caller, tool, "a friction group's fix is First mate's to set")
                else -> McpToolResult.error("unknown tool: ${json(tool)}")
            }
            is McpCaller.Nobody -> mateCall(tool, args, caller)
        }
    } catch (e: ResponseStatusException) {
        // The engine's own refusal (a 403 on authorize, a 400 on a repo), as the desktop's server passed its error body on.
        McpToolResult.error(e.reason ?: e.statusCode.toString())
    }

    private fun mateOnly(caller: McpCaller.Smith, tool: String, why: String) =
        McpToolResult.error("a Smith sandbox (run ${caller.caller.runId}) may not call $tool: $why")

    private fun mateCall(tool: String, args: Map<String, Any?>, caller: McpCaller.Nobody): McpToolResult {
        return when (tool) {
            "submit_ask" -> askTool.call(args, caller)
            "read_needs" -> needsTools.call(args)
            "cancel_intake", "amend_intake", IntakeTools.AMEND_TICKET -> intakeTools.call(tool, args, caller)
            TranscriptTool.NAME -> transcriptTool.call(args)
            TicketRunsTool.READ_TICKET_RUNS -> ticketRunsTool.call(tool, args)
            "read_ticket_pr" -> ticketPrTool.call(args)
            SearchTool.NAME -> searchTool.call(args)
            TicketTool.NAME -> ticketTool.call(args)
            PrivateDocTools.LIST -> docTools.mateList(args)
            PrivateDocTools.READ -> docTools.mateRead(args)
            "approve" -> approvalTool.approve(caller, args)
            "reauthorize" -> approvalTool.reauthorize(caller, args)
            "note_friction" -> frictionNoteTool.call(args, caller)
            in RepoTool.NAMES -> repoTool.call(tool, args, caller)
            in AccessTool.NAMES -> accessTool.call(tool, args, caller)
            in TokenTool.NAMES -> tokenTool.call(tool, args, caller)
            in McpServerTools.NAMES -> mcpServerTools.call(tool, args, caller.login)
            "close_ticket", "comment_ticket" -> ticketWriteTools.call(tool, args, caller)
            CiRunsTool.NAME -> ciRunsTool.call(args)
            in CiJobsTool.NAMES -> ciJobsTool.call(tool, args)
            VersionTool.NAME -> versionTool.call(args)
            DesktopReleaseTool.NAME -> desktopReleaseTool.call(args)
            ScreenshotTool.NAME -> screenshotTool.call(args, caller)
            in HitlpTools.NAMES -> hitlpTools?.call(tool, args) ?: McpToolResult.error("unknown tool: ${json(tool)}")
            FrictionFixIntakeTool.NAME -> fixIntakeTool?.let { t -> login(caller)?.let { t.call(args, it) } ?: noLogin("link") }
                ?: McpToolResult.error("unknown tool: ${json(tool)}")
            FrictionFixTool.NAME -> fixTool?.let { t -> login(caller)?.let { t.call(args, it) } ?: noLogin("link") }
                ?: McpToolResult.error("unknown tool: ${json(tool)}")
            else -> McpToolResult.error("unknown tool: ${json(tool)}")
        }
    }

}
