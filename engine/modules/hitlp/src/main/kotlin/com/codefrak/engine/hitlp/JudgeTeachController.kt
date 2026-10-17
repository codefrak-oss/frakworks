package com.codefrak.engine.hitlp

import com.codefrak.engine.github.JudgeTaught
import com.codefrak.engine.github.RepoContents
import com.codefrak.engine.provenTokenId
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.smith.SmithRefusal
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.tokenLogin
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `POST /judge/teach`: the decided judge escalation, by its HITLP [taskId] or the Jev [callId] it judged (one of the
 * two), `allow` or `deny` (its decision's), and the human's note.
 */
data class TeachRequest(val taskId: String? = null, val decision: String, val note: String? = null, val callId: String? = null)

/** What the teach committed: the repo, the rubric's path, the new commit's SHA and the line it added. */
data class Taught(val repo: String, val path: String, val commit: String, val line: String)

/**
 * Teaching the judge (engine/README.md, "Teaching the judge"): after the desktop decided a judge escalation `/mcp/judge`
 * filed on the HITLP server, its Allow and teach / Deny and teach buttons post here, and one dated line
 * ([RubricTeacher.line]) is committed under `## Decisions` of the rubric on the repo's default branch, through the
 * contents API with the repo's own GitHub token, authored by the login the caller's token proved.
 *
 * The task is found through `engine.hitlp_request` ([HitlpRequests.byJudgeCall] for a [TeachRequest.callId], the row
 * of [TeachRequest.taskId] otherwise; a row with no `judge_call_id` is no judge escalation) and read with `tasks/get`
 * on the HITLP server ([HitlpRequester.get]), never from `engine.hitlp_task`. Its decision record carries the request
 * as received: `context.data.judge {callId, rubricPath, repo}` says where to commit, and the judge's verdict comes from
 * `asserted.recommendation`, falling back to `context.data.judge` ([RubricTeacher.Judge.of]).
 *
 * Only an engine token of purpose `first-mate` ([EngineTokens.FIRST_MATE]) may teach: a Smith sandbox (by address,
 * whose header the bearer filter ignores) is 403, and so is any other token. A rubric file or `## Decisions` heading
 * that is not there is 422: the engine never creates either. So is an escalation filed for a Smith rather than a login
 * (a Smith may not choose where the engine commits), a rubricPath that is not a plain relative `.md` path, and a repo
 * the engine holds no token for. A commit publishes [JudgeTaught], which registers the repo's rubric consolidation job.
 */
@RestController
class JudgeTeachController(
    private val requests: HitlpRequests, private val requester: HitlpRequester, private val tokens: EngineTokens,
    private val contents: RepoContents, private val sandbox: SmithSandbox,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(JudgeTeachController::class.java)

    companion object {
        /** A teach's decision and the approve outcome it must match. */
        val OUTCOME = mapOf("allow" to "approved", "deny" to "rejected")
    }

    @PostMapping("/judge/teach")
    fun teach(@RequestBody body: TeachRequest, request: HttpServletRequest?): Taught {
        SmithRefusal.smithAt(sandbox, request, log)?.let { throw forbidden("a Smith sandbox (run ${it.runId}) may not teach the judge") }
        val login = request.tokenLogin()
        val token = request?.provenTokenId()?.let { tokens.get(it) }
        if (token?.purpose != EngineTokens.FIRST_MATE) throw forbidden("only a ${EngineTokens.FIRST_MATE} token may teach the judge")
        val outcome = OUTCOME[body.decision] ?: throw status(HttpStatus.BAD_REQUEST, "decision is allow or deny, not ${body.decision}")
        val named = body.callId?.let { "judge call $it" } ?: body.taskId?.let { "HITLP task $it" }
            ?: throw status(HttpStatus.BAD_REQUEST, "name the escalation by taskId or callId")
        val row = (body.callId?.let { requests.byJudgeCall(it) } ?: body.taskId?.let { requests.get(it) })
            ?: throw status(HttpStatus.NOT_FOUND, "no judge escalation for $named")
        if (row.judgeCallId == null || row.kind != JudgeEscalation.KIND)
            throw status(HttpStatus.UNPROCESSABLE_ENTITY, "task ${row.taskId} is no judge escalation")
        if (JudgeEscalation.login(row) == null)
            throw status(HttpStatus.UNPROCESSABLE_ENTITY, "task ${row.taskId} was filed for a Smith, not a login: only a login's may name a rubric")
        val handle = try { requester.get(row.taskId) } catch (e: RuntimeException) {
            throw status(HttpStatus.BAD_GATEWAY, "the HITLP server could not answer tasks/get for ${row.taskId}: ${e.message}")
        }
        val record = handle.record
        if (handle.status != HitlpPrimitives.COMPLETED || record?.get("outcome") != outcome)
            throw status(HttpStatus.CONFLICT, "task ${row.taskId} is not $outcome (decide it first)")
        val task = record?.get("request") as? Map<*, *> ?: throw status(HttpStatus.UNPROCESSABLE_ENTITY, "task ${row.taskId}'s record carries no request")
        val judgeData = RubricTeacher.Judge.judgeData(task)
        val judge = RubricTeacher.Judge.of(task, record?.get("recommendation") as? Map<*, *>)
        if (judgeData == null || judge == null) throw status(HttpStatus.UNPROCESSABLE_ENTITY, "task ${row.taskId} is no judge escalation (no context.data.judge)")
        val repo = judgeData["repo"] as? String ?: throw status(HttpStatus.UNPROCESSABLE_ENTITY, "context.data.judge has no repo")
        val path = judgeData["rubricPath"] as? String ?: throw status(HttpStatus.UNPROCESSABLE_ENTITY, "context.data.judge has no rubricPath")
        if (!RubricTeacher.plainMarkdownPath(path))
            throw status(HttpStatus.UNPROCESSABLE_ENTITY, "rubricPath $path is not a plain relative path to a .md file")
        val payload = task["payload"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val summary = (task["context"] as? Map<*, *>)?.get("summary") as? String
        val line = RubricTeacher.line(
            LocalDate.now(ZoneOffset.UTC), body.decision,
            command = payload["command"]?.toString() ?: task["action"]?.toString() ?: "",
            host = payload["host"]?.toString() ?: "?",
            why = (payload["why"] ?: payload["reason"] ?: summary)?.toString() ?: "",
            judge = judge, note = body.note,
        )
        val file = try { contents.file(repo, path) } catch (e: IllegalStateException) {
            throw status(HttpStatus.UNPROCESSABLE_ENTITY, e.message ?: "no GitHub token for $repo")
        } catch (e: IllegalArgumentException) {
            throw status(HttpStatus.UNPROCESSABLE_ENTITY, e.message ?: "bad repo or path")
        } ?: throw status(HttpStatus.UNPROCESSABLE_ENTITY, "$repo has no rubric file $path on its default branch")
        val text = try { RubricTeacher.append(file.text, line, path) } catch (e: RubricTeacher.NoDecisions) {
            throw status(HttpStatus.UNPROCESSABLE_ENTITY, e.message ?: "no ${RubricTeacher.HEADING}")
        }
        val commit = contents.putFile(repo, path, text, file.sha, "judge: ${body.decision} taught by @$login (call ${RubricTeacher.oneLine(judge.callId)})", login)
        log.info("judge taught on {} {}: {} (commit {})", repo, path, line, commit)
        events.publishEvent(JudgeTaught(repo))
        return Taught(repo, path, commit, line)
    }

    private fun forbidden(message: String) = status(HttpStatus.FORBIDDEN, message)
    private fun status(status: HttpStatus, message: String) = ResponseStatusException(status, message)
}
