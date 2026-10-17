package com.codefrak.engine.intake

import com.codefrak.engine.provenLogin
import com.codefrak.engine.refuseBy
import com.codefrak.engine.tokenLogin
import com.codefrak.engine.at
import com.codefrak.engine.board.StageEstimates
import com.codefrak.engine.camunda.Actor
import com.codefrak.engine.camunda.variablesOf
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.rootProcessInstanceId
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithRefusal
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.Vars
import jakarta.servlet.http.HttpServletRequest
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** Unauthenticated: starts `intake` (bpmn/intake.bpmn) by its "ask submitted" message. A Smith sandbox is recognised by its address (engine/README.md, "The frakworks server in a Smith"). */
@RestController
class IntakeController(
    private val runtime: RuntimeService, private val history: HistoryService,
    private val questions: IntakeQuestions, private val access: Access,
    private val sandbox: SmithSandbox, private val repos: TargetRepos, private val canceller: FlowCanceller,
    private val events: EngineEvents, private val estimates: StageEstimates,
    private val attachments: AnswerAttachments? = null,
) {
    private val log = LoggerFactory.getLogger(IntakeController::class.java)

    @PostMapping("/intake")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun submit(@RequestBody body: AskRequest, request: HttpServletRequest? = null): IntakeStarted {
        val smith = smithAt(request)
        val submittedBy = request?.provenLogin()
        if (smith != null) {
            // A Smith files for its own run, as no one: its ask waits for a human's `authorized` label.
            if (!body.submittedBy.isNullOrBlank()) forbidden("a Smith sandbox may not name a submitter")
            if (body.authorize == true) forbidden("a Smith sandbox may not authorize an intake")
            body.fromRun?.let { from ->
                if (from.runId != smith.runId) forbidden("fromRun ${from.runId} is not the run at this address (${smith.runId})")
            }
        } else if (body.fromRun != null) forbidden("fromRun from an address that is no running Smith sandbox")
        val repo = body.repo?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (!repos.contains(it)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$it is not a target repo")
        } ?: smith?.repo?.takeIf { repos.contains(it) }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "repo is required: the target repo (owner/name) to file in" +
                repos.list().joinToString(", ") { it.fullName }.let { if (it.isEmpty()) "; the engine has no target repos yet" else ", one of $it" })
        if (body.authorize == true) {
            val by = submittedBy
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "authorize is true but submittedBy is missing")
            if (!repos.contains(repo)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "authorize is true but $repo is not a target repo")
            if (!access.trusted(by)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "authorize is true but @$by is not a trusted login")
        }
        val vars = buildMap<String, Any> {
            put("ask", Brief.textValue(body.ask)); put("dryRun", body.dryRun); put(Vars.REPO, repo)
            body.fakeOutcome?.let { put("fakeOutcome", it) }; submittedBy?.let { put(SUBMITTED_BY, it) }
            body.authorize?.let { put(AUTHORIZE, it) }
            body.notifySession?.trim()?.takeIf { it.isNotEmpty() }?.let { put(NOTIFY_SESSION, it) }
            body.context?.takeIf { it.isNotBlank() }?.let { put(CONTEXT, Brief.textValue(it)) }
            smith?.let {
                put(AUTHORIZE, false); put(REQUESTED_BY_RUN, it.runId)
                it.issue?.let { n -> put(REQUESTED_BY_ISSUE, n) }; it.repo?.let { r -> put(REQUESTED_BY_REPO, r) }
            }
        }
        val instance = runtime.createMessageCorrelation("ask submitted").setVariables(vars).correlateStartMessage()
        events.publish(EngineEvent.Intake(repo, instance.id, "submitted"))
        val started = if (instance.isEnded) emptyMap() else runtime.getVariables(instance.id, listOf(Vars.REPO, "issue", "issueUrl"))
        return IntakeStarted(instance.id, started[Vars.REPO] as? String ?: repo, (started["issue"] as? Number)?.toLong(),
            started["issueUrl"] as? String)
    }

    /** Every step taken, every called process's included (a chore's too), in order. */
    @GetMapping("/intake/{id}")
    fun state(@PathVariable id: String): IntakeState {
        val root = history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val ids = generateSequence(listOf(id)) { parents ->
            parents.flatMap { history.createHistoricProcessInstanceQuery().superProcessInstanceId(it).list().map { c -> c.id } }
                .ifEmpty { null }
        }.flatten().toList()
        val activities = ids.flatMap { history.createHistoricActivityInstanceQuery().processInstanceId(it).orderPartiallyByOccurrence().asc().list() }
            .sortedBy { it.startTime } // stable: same-millisecond steps keep their per-process order
        val variables = history.variablesOf(id)
            .mapValues { (name, value) -> if (name in TEXT_VARS) Brief.text(value) else value }
        // One read of smith_run_stats, and only while the flow runs: an ended flow's steps carry no estimate.
        val snapshot = if (root.endTime == null) estimates.snapshot() else null
        val tier = estimates.tierOf(variables, id); val scope = StageEstimates.scopeOf(variables)
        val steps = activities.map { step ->
            val open = step.processInstanceId == id && step.endTime == null && step.startTime != null
            val est = if (!open || snapshot == null) null else {
                // A rework round is estimated from this ticket's own first round of the step, as the board's stage is.
                val first = activities.firstOrNull { it.processInstanceId == id && it.activityId == step.activityId && it.endTime != null }
                snapshot.estimate(step.activityId, root.processDefinitionKey, tier, first?.durationInMillis, scope)
            }
            Step(step.processDefinitionKey, step.activityId, step.activityName, step.startTime?.at(), step.endTime?.at(),
                est?.ms, est?.basis, est?.runs)
        }
        return IntakeState(id, root.endTime != null, steps, variables, questions.pending(id), questions.list(id))
    }

    /** Every question waiting on the user, across intakes, oldest first. */
    @GetMapping("/questions")
    fun open(): List<IntakeQuestion> = questions.open()

    /**
     * Attaches one file to the answer of the question flow [id] waits on (engine/README.md, "Answer attachments"): the
     * desktop uploads each before it posts the answer. Kept in `engine.answer_attachment` against the flow's root, where
     * that flow's Smiths read it through the frakworks MCP server; never written to GitHub. The same caller rules as the
     * answer: no Smith sandbox, a trusted `by`; 409 when nothing waits, 413 over a cap, 415 for a binary.
     */
    @PostMapping("/intake/{id}/attachments")
    fun attach(@PathVariable id: String, @RequestBody body: AttachmentUpload, request: HttpServletRequest? = null): AttachmentStored {
        smithAt(request)?.let { forbidden("a Smith sandbox (run ${it.runId}) may not attach a file to an answer") }
        val store = attachments ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "the engine has no attachment store")
        refuseBy(body.by)
        val by = request.tokenLogin()
        if (!access.trusted(by)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "@$by is not a trusted login")
        questions.pending(id) ?: throw ResponseStatusException(HttpStatus.CONFLICT, "$id is not waiting on a question")
        runtime.createProcessInstanceQuery().processInstanceId(id).singleResult()
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "$id is not waiting on a question")
        val bytes = try { java.util.Base64.getDecoder().decode(body.content.trim()) } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "content is not base64: ${e.message}")
        }
        val stored = try { store.save(runtime.rootProcessInstanceId(id), body.name, body.mime, bytes, by) } catch (e: AttachmentRefused) {
            throw ResponseStatusException(when {
                e.tooLarge -> HttpStatus.PAYLOAD_TOO_LARGE
                e.badType -> HttpStatus.UNSUPPORTED_MEDIA_TYPE
                else -> HttpStatus.BAD_REQUEST
            }, e.message)
        }
        return AttachmentStored(id, stored.name, stored.mime, stored.size)
    }

    /**
     * Cancels a running root flow of any kind as `by` ([FlowCanceller.cancel], engine/README.md, "Cancel and
     * the sweep"). A Smith sandbox may not: frakworks-engine-diagnostics is its way, under its own rule.
     */
    @DeleteMapping("/intake/{id}")
    fun cancel(@PathVariable id: String, @RequestBody body: CancelRequest, request: HttpServletRequest? = null): IntakeState {
        smithAt(request)?.let { forbidden("a Smith sandbox (run ${it.runId}) may not cancel a flow") }
        refuseBy(body.by)
        val failed = canceller.cancel(id, Actor.Login(request.tokenLogin()), body.reason)
        return state(id).copy(failedWrites = failed)
    }

    private fun smithAt(request: HttpServletRequest?): SmithCaller? = SmithRefusal.smithAt(sandbox, request, log)

    private fun forbidden(why: String): Nothing = throw ResponseStatusException(HttpStatus.FORBIDDEN, why)
}

/** The intake's free-text variables, stored as bytes (Brief.textValue) and answered by `GET /intake/{id}` as text. */
private val TEXT_VARS = setOf("ask", CONTEXT, Vars.DUPLICATE_QUESTION)

