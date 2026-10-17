package com.codefrak.engine.doctor

import com.codefrak.engine.github.parseIssueUrl
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.refuseBy
import com.codefrak.engine.tokenLogin
import com.codefrak.engine.repos.Access
import com.codefrak.engine.smith.SmithRefusal.refuseSmith
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** A ticket's doctor history: its runs, newest first; none is `[]`. */
data class DoctorRuns(val issueUrl: String, val runs: List<DoctorRun>)

/** The tickets the doctor has run on, newest last run first; none is `[]`. */
data class DoctorTickets(val tickets: List<DoctorTicket>)

/** Every ticket's runs that started at or after `since`, newest first; none is `[]`. */
data class DoctorRunsSince(val since: OffsetDateTime, val runs: List<DoctorRun>)

/** The doctor's bugs whose newest sighting is at or after `since`, newest sighting first; none is `[]`. */
data class DoctorBugsSince(val since: OffsetDateTime, val bugs: List<DoctorBug>)

/** `PATCH /doctor/sightings/{id}`: the bug to move the sighting to, `owner/name#n`, or null for none; the moving login is the bearer token's, and `by` is refused. */
data class MoveSighting(val bug: String? = null, val by: Any? = null)

/** The doctor's history over HTTP (engine/README.md, "Doctor"). */
@RestController
class DoctorController(
    private val history: DoctorHistory, private val smithRuns: DoctorSmithRuns, private val bugs: DoctorBugStore,
    private val sightings: DoctorSightings, private val access: Access, private val calls: JevCalls, private val sandbox: SmithSandbox,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        val BUG = Regex("""^([\w.-]+/[\w.-]+)#(\d+)$""")
    }

    /** Without `issueUrl`, the tickets with runs ([DoctorTickets]); with it, that ticket's runs ([DoctorRuns]). */
    @GetMapping("/doctor/history")
    fun get(@RequestParam(required = false) issueUrl: String?): Any = if (issueUrl == null) tickets() else history(issueUrl)

    fun tickets() = DoctorTickets(history.tickets())

    /**
     * Each run with its `smithRunId`, read from the engine's history for a run whose row has none (one still going,
     * or one that ended before V28); 400 when `issueUrl` is not `https://github.com/<owner>/<repo>/issues/<n>`.
     */
    fun history(issueUrl: String): DoctorRuns {
        val url = issueUrl.trim()
        val (repo, number) = parseIssueUrl(url)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "issueUrl is https://github.com/<owner>/<repo>/issues/<n>")
        return DoctorRuns(url, history.of(repo, number).map(smithRuns::filled))
    }

    /**
     * The runs across tickets since `since` (an ISO-8601 instant or offset date-time; 7 days ago without one),
     * newest first; 400 when `since` does not parse.
     */
    @GetMapping("/doctor/runs")
    fun runs(@RequestParam(required = false) since: String?): DoctorRunsSince {
        val from = since(since)
        return DoctorRunsSince(from, history.since(from))
    }

    /**
     * The doctor's bugs, in every repo, whose newest sighting (or, with none, its creation) is at or after `since`
     * (as [runs] takes it; 7 days ago without one), newest sighting first, each with its signatures and its ticket
     * and run counts; 400 when `since` does not parse.
     */
    @GetMapping("/doctor/bugs")
    fun bugs(@RequestParam(required = false) since: String?): DoctorBugsSince {
        val from = since(since)
        return DoctorBugsSince(from, bugs.since(from))
    }

    /**
     * Moves a sighting, its signature and the other sightings of that signature on the same bug to the doctor bug of
     * `bug` (`owner/name#n`; made when the issue has none), or off any bug with `bug` null: the unlump
     * (engine/README.md, "Doctor"). The token's login needs `bug` access on the repo of the bug it leaves and of the one it goes to
     * (on the stuck ticket's repo when it is on none and goes to none). When the sighting was lumped by Jev, or filed
     * on Jev's `new`, the call's `outcome` says so. 400 on a body with `by` or a `bug` not `owner/name#n`; 403 for a login
     * without access, or a Smith sandbox; 404 for no such sighting; 409 when it is already
     * there; 503 when the sandbox provider cannot say whether the caller is a Smith.
     */
    @PatchMapping("/doctor/sightings/{id}")
    fun move(@PathVariable id: Long, @RequestBody body: MoveSighting, request: HttpServletRequest? = null): DoctorSighting {
        refuseSmith(sandbox, request, log, "move a sighting")
        refuseBy(body.by)
        val by = request.tokenLogin()
        val target = body.bug?.trim()?.let {
            val (repo, n) = BUG.find(it)?.destructured ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bug is <owner>/<name>#<n>, or null")
            repo to n.toLong()
        }
        val s = bugs.lumped(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no sighting $id")
        val from = s.bug
        if (!access.trusted(by)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "@$by is not a trusted login")
        val same = when {
            target == null -> from == null
            from == null -> false
            else -> from.issue == target.second && from.repo.equals(target.first, ignoreCase = true)
        }
        if (same) throw ResponseStatusException(HttpStatus.CONFLICT, "sighting $id is already on ${body.bug ?: "no bug"}")
        bugs.move(s, target?.first, target?.second)
        val call = s.call
        if (call != null && from != null && s.lumpedBy in setOf("jev", "new")) {
            val was = SightingCheck.name(from.repo, from.repo, from.issue)
            val to = SightingCheck.name(from.repo, target?.first, target?.second)
            val outcome = if (s.lumpedBy == "jev") SightingCheck.moved(was, to, by) else SightingCheck.filedMoved(was, to, by)
            runCatching { calls.recordOutcome(call, outcome) }.onFailure { log.warn("jev doctor-sighting {}: outcome not recorded: {}", call, it.message) }
        }
        log.info("doctor: @{} moved sighting {} from {} to {}", by, id, from?.let { "${it.repo}#${it.issue}" } ?: "no bug", body.bug ?: "no bug")
        return sightings.of(s.doctorRunId)!!
    }

    private fun since(since: String?): OffsetDateTime =
        if (since.isNullOrBlank()) OffsetDateTime.now(ZoneOffset.UTC).minusDays(7) else try {
            OffsetDateTime.parse(since.trim())
        } catch (e: DateTimeParseException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "since is an ISO-8601 date-time, e.g. 2026-09-21T00:00:00Z", e)
        }
}
