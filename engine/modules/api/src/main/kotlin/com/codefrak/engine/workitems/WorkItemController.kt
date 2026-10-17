package com.codefrak.engine.workitems

import com.codefrak.engine.badRequest
import com.codefrak.engine.refuseBy
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.SmithRefusal.refuseSmith
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.tokenLogin
import com.codefrak.engine.trigger.IssueApprovals
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * `POST /work-items/import`: [issue] of target repo [repo] becomes a work item. Kind (feature when absent), tier and mode
 * are the importer's; nothing is read from the issue's text. `by` is refused: the bearer token names the login.
 */
data class ImportWorkItem(val repo: String? = null, val issue: Long? = null, val kind: String? = null, val tier: Int? = null,
                          val mode: String? = null, val by: String? = null)

/**
 * Work items for the desktop and the iOS app (engine/docs/work-items.md): `GET /work-items?repo=` lists a target repo's
 * items, newest first, without entries; `GET /work-items/{id}` reads one with its dependencies and entries, its
 * untrusted text fenced ([UntrustedFence]). `POST /work-items/{id}/entries` adds a login's reply, a trusted
 * conversation entry (refused to a Smith). There is no default repo: a list without `repo` is refused naming the
 * target repos. `POST /work-items/import` brings a GitHub issue in: only a trusted login's bearer token, never a Smith.
 */
@RestController
class WorkItemController(private val store: WorkItemStore, private val repos: TargetRepos, private val access: Access,
                         private val sandbox: SmithSandbox, private val github: GitHubWorkItemSource,
                         private val approvals: IssueApprovals? = null) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Starts item [id]'s flow as the bearer token's login (engine/docs/work-items.md, "Starting work"): the approval
     * First mate's `approve` makes, on its ticket number. 403 for a Smith sandbox and for a login that is not trusted,
     * 401 without a bearer token, 404 for an unknown item, 409 with the reason when it is refused (closed, no single
     * kind, already started); 200 with the flow, what became of it (`started`, `deferred`) and why. On an `ask` item
     * (an intake's, engine/docs/intake.md, "Authorization") it hands the intake waiting on it its authorization instead
     * ([IssueApprovals.approveIntake]): 200 with flow `intake` and the intake's id, 409 when no intake waits on it.
     */
    @PostMapping("/work-items/{id}/start")
    fun start(@PathVariable id: String, request: HttpServletRequest): Map<String, Any> {
        refuseSmith(sandbox, request, log, "start a work item")
        val login = request.tokenLogin()
        if (!access.trusted(login)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "@$login is not a trusted login")
        val item = store.get(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no work item $id")
        val ticket = item.ticket ?: throw ResponseStatusException(HttpStatus.CONFLICT, "work item $id has no ticket number")
        val approve = approvals ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "no approvals in this engine")
        if (item.kind == WorkItem.ASK) {
            val done = approve.approveIntake(item.repo, ticket, login)
                ?: throw ResponseStatusException(HttpStatus.CONFLICT, "no intake on work item $id waits for a start (already started, refined or closed)")
            return mapOf("flow" to "intake", "outcome" to "started", "detail" to "authorized by @$login", "intake" to done.instance, "ticket" to ticket)
        }
        return when (val r = approve.approveTicket(item.repo, ticket, login)) {
            is IssueApprovals.TicketApproved.Done -> mapOf("flow" to r.flow, "outcome" to r.outcome, "detail" to r.detail, "ticket" to ticket)
            is IssueApprovals.TicketApproved.Refused -> throw ResponseStatusException(HttpStatus.CONFLICT, r.why)
        }
    }

    @GetMapping("/work-items")
    fun list(@RequestParam(required = false) repo: String?): List<WorkItem> =
        store.list(target(repo)).map { UntrustedFence.fenced(it) }

    @GetMapping("/work-items/{id}")
    fun read(@PathVariable id: String): WorkItem =
        store.get(id)?.let { UntrustedFence.fenced(it) } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no work item $id")

    /** A login's reply: body `{"body": "..."}`; the author is the bearer token's login, never a field. */
    @PostMapping("/work-items/{id}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    fun reply(@PathVariable id: String, @RequestBody req: ReplyRequest, request: HttpServletRequest): WorkItemEntry {
        refuseSmith(sandbox, request, log, "reply to a work item")
        val login = request.tokenLogin()
        if (!access.trusted(login)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "@$login is not a trusted login")
        store.get(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no work item $id")
        val body = req.body?.takeIf { it.isNotBlank() } ?: throw badRequest("body is required")
        return store.appendEntry(id, WorkItemEntry.CONVERSATION, login, body, true)
    }

    data class ReplyRequest(val body: String? = null)

    /**
     * 403 for a Smith sandbox and for a login that is not trusted, 401 without a bearer token; 400 for a repo that is no
     * target, a missing issue or a bad kind or tier; 409, naming the item, for an issue already imported. The item is
     * untrusted, its text the issue's title and body as GitHub has them, with an `imported by @login` history entry.
     */
    @PostMapping("/work-items/import")
    @ResponseStatus(HttpStatus.CREATED)
    fun import(@RequestBody body: ImportWorkItem, request: HttpServletRequest? = null): WorkItem {
        refuseSmith(sandbox, request, log, "import a work item")
        refuseBy(body.by)
        val login = request.tokenLogin()
        if (!access.trusted(login)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "@$login is not a trusted login")
        val repo = target(body.repo)
        val issue = body.issue?.takeIf { it > 0 } ?: throw badRequest("issue is required: the issue number in $repo")
        val kind = body.kind ?: "feature"
        if (kind !in WorkItem.KINDS) throw badRequest("kind is one of ${WorkItem.KINDS.joinToString()}")
        if (body.tier != null && body.tier !in 1..10) throw badRequest("tier is 1 to 10")
        store.forSourceRef(WorkItemSourceRef.IMPORT, GitHubWorkItemSource.sourceRef(repo, issue))?.let {
            throw ResponseStatusException(HttpStatus.CONFLICT, "$repo#$issue is already work item ${it.id}")
        }
        val read = github.import(repo, "#$issue") ?: throw badRequest("#$issue is not an issue of $repo")
        val item = store.create(read.copy(kind = kind, tier = body.tier, mode = body.mode, trusted = false))
        store.appendEntry(item.id, WorkItemEntry.HISTORY, "engine", "imported by @$login from $repo#$issue", true)
        return UntrustedFence.fenced(store.get(item.id)!!)
    }

    private fun target(repo: String?): String {
        if (repo.isNullOrBlank()) throw badRequest(
            "repo is required: a target repo (${repos.list().joinToString(", ") { it.fullName }.ifEmpty { "none yet" }})")
        return repos.find(repo)?.fullName ?: throw badRequest("$repo is not a target repo")
    }
}
