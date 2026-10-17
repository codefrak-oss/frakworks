package com.codefrak.engine.friction

import com.codefrak.engine.badRequest
import com.codefrak.engine.parseSince
import com.codefrak.engine.refuseBy
import com.codefrak.engine.refuseUnknownFields
import com.codefrak.engine.tokenLogin
import com.codefrak.engine.repos.FULL_NAME
import com.codefrak.engine.smith.SmithRefusal.refuseSmith
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** `POST /friction/groups/{id}/merge`: `into`, the group that receives every note and signature, the merging login is the bearer token's; `by` is refused. */
data class MergeGroup(val into: Long? = null, val by: Any? = null)

/** The body check of `PATCH /friction/groups/{id}`'s `fix` link: a map with a trimmed `owner/name` `repo` and an `issue` (a whole `Number` >= 1, a `Double` refused). 400 otherwise. */
internal fun fixLink(fix: Any): Pair<String, Long> {
    val f = fix as? Map<*, *> ?: throw badRequest("fix is {\"repo\": \"owner/name\", \"issue\": n}, or null")
    val repo = (f["repo"] as? String)?.trim()?.takeIf { FULL_NAME.matches(it) }
        ?: throw badRequest("fix.repo is owner/name: ${f["repo"]}")
    val issue = (f["issue"] as? Number)?.toLong()?.takeIf { it >= 1 && f["issue"] !is Double }
        ?: throw badRequest("fix.issue is a ticket number: ${f["issue"]}")
    return repo to issue
}

/**
 * The friction notes over HTTP (engine/README.md, "Friction notes"): the notes, the groups and the summary the
 * desktop's Analytics > Friction notes page draws, read over `engine.friction_note` through [FrictionLog.list] and over
 * the groups through [FrictionGroups.list]; and the two corrections any login may make ([FrictionCorrections]), refused
 * to a Smith sandbox by its source address.
 */
@RestController
class FrictionController(
    private val log: FrictionLog, private val groups: FrictionGroups, private val confidence: FrictionConfidence,
    private val corrections: FrictionCorrections, private val sandbox: SmithSandbox, private val fixes: FrictionFixes,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * The notes newest first, filtered by `since` (an ISO instant or date), `repo`, `kind` and `source`; `limit`
     * defaults to 100 and is capped at 500. 400 for a `since` that does not parse, a `limit` under 1, or a `kind`
     * or `source` that is none of the store's.
     */
    @GetMapping("/friction/notes")
    fun notes(
        @RequestParam(required = false) since: String?,
        @RequestParam(required = false) repo: String?,
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
    ): List<Map<String, Any?>> {
        val notes = log.list(filter(since, repo, kind, source, limit))
        val conf = confidence.forNotes(notes)
        return notes.map { row(it, conf) }
    }

    /**
     * The groups with at least one member passing the same filters, newest passing member first, each `{id, title,
     * count (the members passing), total, newest, oldest, kind (the majority, "mixed" on a tie), repos, severity (the
     * highest), members}`, the members as `/friction/notes` rows. `limit` counts groups: 100 by default, capped at
     * 500; the same 400s as `/friction/notes`. A note not yet grouped is in no group here; `/friction/summary` counts it.
     * Each row carries `bug` (the two-things bug), `fix` (the fixed-by link, `{repo, issue, state, recurredAt}`, null
     * without one) and `state` (`open`, `fix-filed`, `fixed`, `recurred`). `state` filters by it, `unsolved` is every
     * group not `fixed`; absent, every group. 400 for any other `state`.
     */
    @GetMapping("/friction/groups")
    fun groups(
        @RequestParam(required = false) since: String?,
        @RequestParam(required = false) repo: String?,
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
        @RequestParam(required = false) state: String?,
    ): List<Map<String, Any?>> {
        val st = state?.ifBlank { null }?.also { oneOf("state", it, FrictionFix.STATES + FrictionFix.UNSOLVED) }
        val listed = groups.list(filter(since, repo, kind, source, limit).copy(state = st))
        val conf = confidence.forNotes(listed.flatMap { it.members })
        return listed.map { g -> g.json { row(it, conf) } }
    }

    private fun filter(since: String?, repo: String?, kind: String?, source: String?, limit: Int): FrictionFilter {
        if (limit < 1) throw badRequest("limit is 1 or more (at most $MAX_LIMIT are answered)")
        val k = kind?.ifBlank { null }?.also { oneOf("kind", it, FrictionInput.KINDS) }
        val s = source?.ifBlank { null }?.also { oneOf("source", it, FrictionNote.SOURCES) }
        return FrictionFilter(repo = repo?.ifBlank { null }, kind = k, source = s, since = since?.ifBlank { null }?.let { at(it) },
            limit = limit.coerceAtMost(MAX_LIMIT))
    }

    /**
     * Over the window from `since` (default 7 days ago) to now, in one repo or all ([FrictionSummary]), with the
     * window's distinct `groups` and its `ungrouped` notes. 400 for a `since` more than [MAX_SUMMARY_DAYS] days ago:
     * `per_day` has an entry for every day of the window.
     */
    @GetMapping("/friction/summary")
    fun summary(@RequestParam(required = false) since: String?, @RequestParam(required = false) repo: String?): Map<String, Any?> {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val from = since?.ifBlank { null }?.let { at(it) } ?: now.minusDays(7)
        if (from.isBefore(now.minusDays(MAX_SUMMARY_DAYS))) {
            throw badRequest("since is at most $MAX_SUMMARY_DAYS days ago for the summary: $since")
        }
        val notes = log.list(FrictionFilter(repo = repo?.ifBlank { null }, since = from, limit = Int.MAX_VALUE))
        return FrictionSummaries.of(notes, from, now).json()
    }

    /**
     * Two corrections on a note, told apart by the key present (`{"group": null}` and `{"twoThings": null}` would
     * deserialize the same into a class). The login is the bearer token's, any login; 400 on a body with `by`, on a body with both
     * keys or neither (naming the two), or any other key; 403 for a Smith sandbox (by its source address); 503 when
     * the sandbox provider cannot be asked. Answers the note as a `/friction/notes` row.
     *
     * - **Pull out**, `{"group": null}`: the note leaves its group for a new one holding it alone
     *   ([FrictionCorrections.pullOut]), with the signature rule and the Jev outcome described there. `group` must be
     *   null (a note goes out only to a new group); 404 for no such note; 409 for a note not grouped yet or alone in
     *   its group.
     * - **Not two things**, `{"twoThings": null}`: the two-things mark goes ([FrictionCorrections.clearTwoThings]),
     *   nothing is filed or closed, and the mark's call gets its outcome. `twoThings` must be null (a user marks
     *   nothing by hand); 404 for no such note; 409 for a note not marked.
     */
    @PatchMapping("/friction/notes/{id}")
    fun correct(@PathVariable id: String, @RequestBody body: Map<String, Any?>, request: HttpServletRequest? = null): Map<String, Any?> {
        refuseSmith(sandbox, request, logger, "regroup a friction note")
        refuseBy(body["by"])
        refuseUnknownFields(body.keys, setOf(GROUP, TWO_THINGS), "$GROUP and $TWO_THINGS")
        if ((GROUP in body) == (TWO_THINGS in body)) {
            throw badRequest("the body is one of {\"$GROUP\": null} (pull the note out of its group) and {\"$TWO_THINGS\": null} (not two things)")
        }
        val by = request.tokenLogin()
        val note = if (GROUP in body) {
            if (body[GROUP] != null) throw badRequest("$GROUP is null: a note goes out of its group only to a new one")
            corrections.pullOut(id, by)
        } else {
            if (body[TWO_THINGS] != null) throw badRequest("$TWO_THINGS is null: a user clears a mark and never sets one")
            corrections.clearTwoThings(id, by)
        }
        return row(note, confidence.forNotes(listOf(note)))
    }

    /**
     * Merge: every note and signature of group `id` moves into `into`, which keeps its title, and `id` is deleted
     * ([FrictionCorrections.merge]), with the Jev outcomes described there. 400 on a body with `by` or no `into`; 404 for
     * either group missing; 409 for a merge into itself; 403 and 503 as for a pull-out. Answers the receiving group
     * as a `/friction/groups` row, every member in it.
     */
    @PostMapping("/friction/groups/{id}/merge")
    fun merge(@PathVariable id: Long, @RequestBody body: MergeGroup, request: HttpServletRequest? = null): Map<String, Any?> {
        refuseSmith(sandbox, request, logger, "merge friction groups")
        refuseBy(body.by)
        val by = request.tokenLogin()
        val into = body.into ?: throw badRequest("into is the id of the group that receives the notes")
        val group = corrections.merge(id, into, by)
        val conf = confidence.forNotes(group.members)
        return group.json { row(it, conf) }
    }

    /**
     * Fixed by: `{"fix": {"repo": "owner/name", "issue": 1234}}` links group `id` to the ticket that fixes it,
     * `{"fix": null}` clears the link, and the sync does not link that ticket again ([FrictionFixes]). 400 on a
     * body with `by`, a missing `fix`, a malformed repo or issue, or any other key; 404 for no such group; 403 and 503 as
     * for a merge. Answers the group as a `/friction/groups` row, every member in it.
     */
    @PatchMapping("/friction/groups/{id}")
    fun fix(@PathVariable id: Long, @RequestBody body: Map<String, Any?>, request: HttpServletRequest? = null): Map<String, Any?> {
        refuseSmith(sandbox, request, logger, "set a friction group's fix")
        refuseBy(body["by"])
        refuseUnknownFields(body.keys, setOf(FIX), FIX)
        if (FIX !in body) throw badRequest("fix is {\"repo\": \"owner/name\", \"issue\": n}, or null to clear it")
        val by = request.tokenLogin()
        val fix = body[FIX]
        val group = if (fix == null) fixes.clear(id, by) else {
            val (repo, issue) = fixLink(fix)
            fixes.link(id, repo, issue, by)
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no friction group $id")
        val conf = confidence.forNotes(group.members)
        return group.json { row(it, conf) }
    }

    /**
     * A candidate fix by intake id: `{"intake": "<id>"}` links group `id` to the intake `submit_ask` / `POST /intake`
     * answered ([FrictionFixes.linkIntake]): `fix-pending` until it files its ticket, then `fix-filed` as Fixed by. 400 on
     * a blank `intake`, a body with `by`, or any other key; 404 for no such group or intake; 409 for a group already fixed by a
     * ticket (clear it first) or an intake that ended without one; 403 and 503 as for a merge. Answers the group's row.
     */
    @PostMapping("/friction/groups/{id}/fix-intake")
    fun groupFixIntake(@PathVariable id: Long, @RequestBody body: Map<String, Any?>, request: HttpServletRequest? = null): Map<String, Any?> =
        fixIntake(id, null, body, request)

    /** As `POST /friction/groups/{id}/fix-intake`, for note `id`'s group; 404 for no such note, 409 for one not grouped yet. */
    @PostMapping("/friction/notes/{id}/fix-intake")
    fun noteFixIntake(@PathVariable id: String, @RequestBody body: Map<String, Any?>, request: HttpServletRequest? = null): Map<String, Any?> =
        fixIntake(null, id, body, request)

    private fun fixIntake(groupId: Long?, noteId: String?, body: Map<String, Any?>, request: HttpServletRequest?): Map<String, Any?> {
        refuseSmith(sandbox, request, logger, "link a friction group's fix (a Smith calls link_friction_fix_intake)")
        refuseBy(body["by"])
        refuseUnknownFields(body.keys, setOf(INTAKE), INTAKE)
        val intake = (body[INTAKE] as? String)?.trim()?.ifEmpty { null }
            ?: throw badRequest("intake is the id submit_ask or POST /intake answered")
        val by = request.tokenLogin()
        val group = try { fixes.linkIntake(groupId, noteId, intake, by) } catch (e: FixIntakeRefused) {
            throw ResponseStatusException(if (e.notFound) HttpStatus.NOT_FOUND else HttpStatus.CONFLICT, e.message)
        }
        val conf = confidence.forNotes(group.members)
        return group.json { row(it, conf) }
    }

    /**
     * One pass of the fix sync now ([FrictionFixes.syncNow]): no body, no parameters; answers the pass's report
     * ([FixSyncReport.json]). A repo GitHub cannot read is in its `skipped`, not an error. 409 when a pass (on demand
     * or the scheduled one) is already running; 403 and 503 as for a merge.
     */
    @PostMapping("/friction/fixes/sync")
    fun syncFixes(request: HttpServletRequest? = null): Map<String, Any?> {
        refuseSmith(sandbox, request, logger, "run the friction fix sync")
        return try { fixes.syncNow().json() } catch (e: FixSyncRunning) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
        }
    }

    /** A note's row; `jev_confidence` is its call's `group` confidence from [conf], null without one; `two_things` and its call are the mark ("Friction notes", "Two things"). */
    private fun row(n: FrictionNote, conf: Map<String, Double>): Map<String, Any?> = linkedMapOf(
        "id" to n.id, "repo" to n.repo, "issue" to n.issue, "run_id" to n.runId, "process_instance_id" to n.processInstanceId,
        "noted_at" to FrictionSummaries.utc(n.notedAt), "source" to n.source, "kind" to n.kind, "summary" to n.summary,
        "evidence" to n.evidence, "severity" to n.severity, "login" to n.login,
        "signature" to n.signature, "group_id" to n.groupId, "grouped_by" to n.groupedBy, "jev_call_id" to n.jevCallId,
        "grouped_login" to n.groupedLogin, "jev_confidence" to n.jevCallId?.let { conf[it] },
        "two_things" to n.twoThings, "two_things_call_id" to n.twoThingsCallId,
    )

    private companion object {
        const val MAX_LIMIT = 500
        /** The two keys that tell `PATCH /friction/notes/{id}`'s corrections apart. */
        const val GROUP = "group"
        const val TWO_THINGS = "twoThings"
        /** The key of `PATCH /friction/groups/{id}`. */
        const val FIX = "fix"
        /** The key of `POST /friction/{groups,notes}/{id}/fix-intake`. */
        const val INTAKE = "intake"

        /** The summary's longest window: a year and a day, well past the desktop's longest (90 days in jev.jsx `WINDOWS`). */
        const val MAX_SUMMARY_DAYS = 366L

        fun oneOf(name: String, value: String, allowed: List<String>) {
            if (value !in allowed) throw badRequest("$name is one of ${allowed.joinToString(", ")}, not $value")
        }

        /** `since` as an ISO instant, an offset date-time, or a date (its UTC midnight); 400 otherwise. */
        fun at(text: String): OffsetDateTime =
            parseSince(text) ?: throw badRequest("since is an ISO instant or date: $text")
    }
}
