package com.codefrak.engine.repos

import com.codefrak.engine.accounts.SecretsKey
import com.codefrak.engine.badRequest
import com.codefrak.engine.board.targetRepoOr404
import com.codefrak.engine.refuseUnknownFields
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.provenLogin
import com.codefrak.engine.github.RepoPermissions
import com.codefrak.engine.github.RepoSetupGitHub
import com.codefrak.engine.github.smithTokenRefusal
import com.codefrak.engine.smith.SmithRefusal.refuseSmith
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.trigger.ScanCosts
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClientException
import org.springframework.web.server.ResponseStatusException

/**
 * `ciCheck` and `ciPaths` are the repo's engine check (engine/README.md, "Chore": `wait for the engine check`); both optional.
 * `securityReview` false: its chores, bugs and features skip the security review; on when absent.
 * `scanIntervalMinutes`: how often the engine scans it on its own, 1 to 1440; [DEFAULT_SCAN_INTERVAL_MINUTES] when absent.
 * `doctorBugs`, `engineDefects` and `frictionBugs` are refused (400): a repo is always added with all three off, and only `PATCH` turns them on.
 */
data class AddRepo(val repo: String, val ciCheck: String? = null, val ciPaths: List<String>? = null, val securityReview: Boolean = true,
                   val scanIntervalMinutes: Int? = null, val doctorBugs: Boolean? = null, val engineDefects: Boolean? = null,
                   val frictionBugs: Boolean? = null)

/** `PUT /repos/{o}/{n}/webhook-secret`: the secret GitHub signs the repo's webhook deliveries with. */
data class SetWebhookSecret(val secret: String) {
    override fun toString() = "SetWebhookSecret()"
}

/** `PUT /repos/{o}/{n}/token`: the repo's own GitHub token; `verify` false stores it without asking GitHub whether it reads the repo. */
data class SetRepoToken(val token: String, val verify: Boolean = true) {
    override fun toString() = "SetRepoToken(verify=$verify)"
}

/** `PUT /repos/{o}/{n}/smith-token`: the token a Smith sandbox gets as GH_TOKEN; always tried against GitHub. */
data class SetSmithToken(val token: String) {
    override fun toString() = "SetSmithToken()"
}

/** Unauthenticated, like the rest of the engine's API: the target repos (engine/README.md, "Its own tables"). */
@RestController
class TargetRepoController(private val repos: TargetRepos, private val running: RunningFlows, private val secrets: SecretsKey,
                           private val github: RepoSetupGitHub, private val sandbox: SmithSandbox, private val costs: ScanCosts,
                           private val events: EngineEvents, private val permissions: RepoPermissions? = null) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** One `repo` frame after a write (engine/README.md, "Events"); `fields` names what a `changed` touched. */
    private fun publish(repo: String, state: String, fields: List<String>? = null) = events.publish(EngineEvent.Repo(repo, state, fields))

    /**
     * Each row with what one scan of it costs in GitHub calls ([ScanCosts]), for the desktop's scan interval estimate,
     * and who may do what on it ([RepoPermissions]): `tokenPermission`, and `callerPermission` for the
     * bearer token's login (null for a Smith sandbox, which has none).
     */
    @GetMapping("/repos")
    fun list(request: HttpServletRequest? = null): List<TargetRepo> = listFor(request?.provenLogin())

    /** The rows with [caller]'s permission on each, for a caller whose login is already proven (the MCP tools). */
    fun listFor(caller: String?): List<TargetRepo> {
        return withCosts(repos.list()).map { r ->
            r.copy(tokenPermission = permissions?.token(r), callerPermission = caller?.let { permissions?.caller(r, it) })
        }
    }

    @PostMapping("/repos")
    fun add(@RequestBody body: AddRepo, request: HttpServletRequest? = null): ResponseEntity<Unit> {
        if (!FULL_NAME.matches(body.repo)) throw badRequest("repo is owner/name")
        listOfNotNull("doctorBugs".takeIf { body.doctorBugs != null }, "engineDefects".takeIf { body.engineDefects != null },
            "frictionBugs".takeIf { body.frictionBugs != null }).firstOrNull()?.let {
            throw badRequest("$it is not taken by POST /repos: a repo is added with it off; turn it on with PATCH /repos/{owner}/{name}")
        }
        val check = checkName(body.ciCheck)
        val paths = paths(body.ciPaths)
        val interval = body.scanIntervalMinutes ?: DEFAULT_SCAN_INTERVAL_MINUTES
        if (interval !in SCAN_INTERVAL_RANGE) throw badRequest(INTERVAL_REASON)
        if (!body.securityReview) refuseSmith(sandbox, request, log, "turn security reviews off or on")
        if (body.scanIntervalMinutes != null) refuseSmith(sandbox, request, log, SET_INTERVAL)
        val added = repos.add(body.repo, check, paths, body.securityReview, interval)
        if (added) publish(body.repo, "added")
        return ResponseEntity.status(if (added) HttpStatus.CREATED else HttpStatus.OK).build()
    }

    /**
     * Edits the repo's check, its security reviews, its scan interval and its doctor bugs: a key present sets its column,
     * null clears it, a key absent leaves it. Only `ciCheck` (a string), `ciPaths` (an array of strings), `securityReview` (a
     * boolean, never null), `scanIntervalMinutes` (a whole number of minutes, 1 to 1440, never null), `doctorBugs` and
     * `engineDefects` are allowed.
     * A flow reads `securityReview` at its next push (engine/README.md, "Chore"), so a Smith sandbox may not send it:
     * it could switch off the security review of its own ticket's next round. Nor `scanIntervalMinutes`: it could
     * have its repo scanned every minute, against the token's hourly GitHub limit.
     * `doctorBugs` and `engineDefects` (booleans, never null) say whether the doctor files engine defects from the repo's
     * stuck tickets, and whether they are filed here (engine/README.md, "Doctor"); a Smith sandbox may send neither (it
     * could switch on filing, which starts authorized runs, or send the fleet's bugs elsewhere). `engineDefects: true`
     * needs `doctorBugs` on (400), and is 409 while another repo has it, naming that repo; `doctorBugs: false` clears it.
     * `frictionBugs` (a boolean, never null) says whether the engine files one bug per group of friction notes Jev marks
     * as two things, in the note's repo (engine/README.md, "Friction notes", "Two things"); independent of the other two,
     * and refused to a Smith sandbox for the same reason.
     * `docsFirst` (a boolean, never null) marks the repo docs-first: its tickets default to the light docs path
     * (engine/README.md, "Docs path"); refused to a Smith sandbox, which could lower its own repo's tickets' tier.
     * `childMode` (`serial`, `parallel` or `live`, never null) is the repo's child-work mode (engine/README.md, "Split
     * features"); refused to a Smith sandbox as `docsFirst` is: it could change how its own feature is split.
     * `publishWorkItems` (a boolean, never null; off by default, V89) has the engine write a one-way GitHub issue copy of
     * each of the repo's work items (engine/docs/work-items.md, "Sources"); refused to a Smith sandbox.
     */
    @PatchMapping("/repos/{owner}/{name}")
    fun edit(@PathVariable owner: String, @PathVariable name: String, @RequestBody body: Map<String, Any?>,
             request: HttpServletRequest? = null): TargetRepo {
        refuseUnknownFields(body.keys, setOf("ciCheck", "ciPaths", "securityReview", "scanIntervalMinutes", "doctorBugs", "engineDefects", "frictionBugs", "docsFirst", "childMode", "publishWorkItems"),
            "ciCheck, ciPaths, securityReview, scanIntervalMinutes, doctorBugs, engineDefects, frictionBugs, docsFirst, childMode and publishWorkItems")
        val raw = body["ciCheck"]
        if (raw != null && raw !is String) throw badRequest("ciCheck is a string or null")
        val list = body["ciPaths"]
        if (list != null && (list !is List<*> || list.any { it !is String })) throw badRequest("ciPaths is an array of strings or null")
        val review = body["securityReview"]
        if ("securityReview" in body && review !is Boolean) throw badRequest("securityReview is true or false")
        val interval = if ("scanIntervalMinutes" in body) scanMinutes(body["scanIntervalMinutes"]) else null
        val doctorBugs = body["doctorBugs"]
        if ("doctorBugs" in body && doctorBugs !is Boolean) throw badRequest("doctorBugs is true or false")
        val engineDefects = body["engineDefects"]
        if ("engineDefects" in body && engineDefects !is Boolean) throw badRequest("engineDefects is true or false")
        val frictionBugs = body["frictionBugs"]
        if ("frictionBugs" in body && frictionBugs !is Boolean) throw badRequest("frictionBugs is true or false")
        val docsFirst = body["docsFirst"]
        if ("docsFirst" in body && docsFirst !is Boolean) throw badRequest("docsFirst is true or false")
        val childMode = body["childMode"]
        if ("childMode" in body && (childMode !is String || childMode !in CHILD_MODES)) throw badRequest("childMode is serial, parallel or live")
        val publishWorkItems = body["publishWorkItems"]
        if ("publishWorkItems" in body && publishWorkItems !is Boolean) throw badRequest("publishWorkItems is true or false")
        if ("securityReview" in body) refuseSmith(sandbox, request, log, "turn security reviews off or on")
        if (interval != null) refuseSmith(sandbox, request, log, SET_INTERVAL)
        if ("doctorBugs" in body || "engineDefects" in body) refuseSmith(sandbox, request, log, "set a repo's doctor bugs")
        if ("frictionBugs" in body) refuseSmith(sandbox, request, log, "set a repo's friction bugs")
        if ("docsFirst" in body) refuseSmith(sandbox, request, log, "mark a repo docs-first")
        if ("childMode" in body) refuseSmith(sandbox, request, log, "set a repo's child-work mode")
        if ("publishWorkItems" in body) refuseSmith(sandbox, request, log, "turn work-item publishing on or off")
        val fullName = "$owner/$name"
        val all = repos.list()
        val row = targetRepoOr404(repos, fullName)
        val bugs = doctorBugs as Boolean? ?: row.doctorBugs
        val defects = if (!bugs) {
            if (engineDefects == true) throw badRequest("engineDefects needs doctorBugs on: the repo that takes engine defects files them")
            false
        } else engineDefects as Boolean? ?: row.engineDefects
        if (defects && !row.engineDefects) all.firstOrNull { it.engineDefects && it.fullName != fullName }?.let {
            throw ResponseStatusException(HttpStatus.CONFLICT, "${it.fullName} has engineDefects; only one repo can: turn it off there first")
        }
        if (bugs != row.doctorBugs || defects != row.engineDefects) try {
            repos.setDoctorBugs(fullName, bugs, defects)
        } catch (e: DataIntegrityViolationException) {
            // Another repo took engineDefects since the check above, so nothing is written: the unique index (V36) said so.
            throw ResponseStatusException(HttpStatus.CONFLICT, "another repo has engineDefects; only one repo can", e)
        }
        val check = if ("ciCheck" in body) checkName(raw as String?) else row.ciCheck
        @Suppress("UNCHECKED_CAST")
        val paths = if ("ciPaths" in body) paths(list as List<String>?) else row.ciPaths
        repos.update(fullName, check, paths, review as Boolean? ?: row.securityReview)
        interval?.let { repos.setScanInterval(fullName, it) }
        (frictionBugs as Boolean?)?.let { if (it != row.frictionBugs) repos.setFrictionBugs(fullName, it) }
        (docsFirst as Boolean?)?.let { if (it != row.docsFirst) repos.setDocsFirst(fullName, it) }
        (childMode as String?)?.let { if (it != row.childMode) repos.setChildMode(fullName, it) }
        (publishWorkItems as Boolean?)?.let { if (it != row.publishWorkItems) repos.setPublishWorkItems(fullName, it) }
        // `ciPaths` is part of the check; the frame names it `ciCheck`. `doctorBugs: false` clearing `engineDefects` names both.
        val fields = listOfNotNull("ciCheck".takeIf { "ciCheck" in body || "ciPaths" in body }, "securityReview".takeIf { "securityReview" in body },
            "scanIntervalMinutes".takeIf { interval != null }, "doctorBugs".takeIf { "doctorBugs" in body },
            "engineDefects".takeIf { "engineDefects" in body || defects != row.engineDefects }, "frictionBugs".takeIf { "frictionBugs" in body },
            "docsFirst".takeIf { "docsFirst" in body }, "childMode".takeIf { "childMode" in body },
            "publishWorkItems".takeIf { "publishWorkItems" in body })
        if (fields.isNotEmpty()) publish(fullName, "changed", fields)
        return withCosts(repos.list()).first { it.fullName == fullName }
    }

    /**
     * The repo's own GitHub token (engine/README.md, "Each repo's token"), sealed on its row; it goes in and never
     * comes out (`GET /repos` says only `hasToken`). Tried against GitHub first unless `verify` is false: a token that
     * cannot read the repo is a 400, GitHub unreachable a 502, and nothing is stored either way.
     */
    @PutMapping("/repos/{owner}/{name}/token")
    fun setToken(@PathVariable owner: String, @PathVariable name: String, @RequestBody body: SetRepoToken): ResponseEntity<Unit> {
        if (!secrets.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_SECRETS_KEY is not set")
        val row = targetRepoOr404(repos, "$owner/$name")
        val token = body.token.trim()
        if (token.isEmpty() || token.any { it.isWhitespace() } || token.length > 400) throw badRequest("token is a GitHub token: not blank, no whitespace")
        if (body.verify) {
            val settings = try { github.probe(row.fullName, token) } catch (e: RestClientException) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub: ${e.message}; nothing was saved. To save it unverified, send \"verify\": false")
            } ?: throw badRequest("the token cannot read ${row.fullName}; nothing was saved")
            if (settings.push == false) throw badRequest("the token can read ${row.fullName} but not push to it; nothing was saved")
        }
        return if (repos.setToken(row.fullName, token)) { publish(row.fullName, "changed", listOf("token")); ResponseEntity.noContent().build() }
            else ResponseEntity.notFound().build()
    }

    /** Drops the repo's token: nothing runs on the repo until it is given another. 204, or 404 for a repo that is not a target repo. */
    @DeleteMapping("/repos/{owner}/{name}/token")
    fun clearToken(@PathVariable owner: String, @PathVariable name: String): ResponseEntity<Unit> {
        val row = targetRepoOr404(repos, "$owner/$name")
        return if (repos.clearToken(row.fullName)) { publish(row.fullName, "changed", listOf("token")); ResponseEntity.noContent().build() }
            else ResponseEntity.notFound().build()
    }

    /**
     * The repo's Smith token (engine/README.md, "Each repo's token"): what a Smith sandbox gets as GH_TOKEN, never
     * the repo token. Always tried against GitHub ([smithTokenRefusal]): a token that cannot read or push the repo, or
     * that reads or writes its issues, is a 400, GitHub unreachable a 502, and nothing is stored either way.
     */
    @PutMapping("/repos/{owner}/{name}/smith-token")
    fun setSmithToken(@PathVariable owner: String, @PathVariable name: String, @RequestBody body: SetSmithToken): ResponseEntity<Unit> {
        if (!secrets.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_SECRETS_KEY is not set")
        val row = targetRepoOr404(repos, "$owner/$name")
        val token = body.token.trim()
        if (token.isEmpty() || token.any { it.isWhitespace() } || token.length > 400) throw badRequest("token is a GitHub token: not blank, no whitespace")
        val refusal = try { smithTokenRefusal(github, row.fullName, token) } catch (e: RestClientException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub: ${e.message}; nothing was saved")
        }
        refusal?.let { throw badRequest("$it; nothing was saved") }
        return if (repos.setSmithToken(row.fullName, token)) { publish(row.fullName, "changed", listOf("smithToken")); ResponseEntity.noContent().build() }
            else ResponseEntity.notFound().build()
    }

    /** Drops the repo's Smith token: no Smith runs on the repo until it is given another. 204, or 404 for a repo that is not a target repo. */
    @DeleteMapping("/repos/{owner}/{name}/smith-token")
    fun clearSmithToken(@PathVariable owner: String, @PathVariable name: String): ResponseEntity<Unit> {
        val row = targetRepoOr404(repos, "$owner/$name")
        return if (repos.clearSmithToken(row.fullName)) { publish(row.fullName, "changed", listOf("smithToken")); ResponseEntity.noContent().build() }
            else ResponseEntity.notFound().build()
    }

    /**
     * The repo's own webhook secret (engine/README.md, "When it scans"), sealed on its row; it goes in and never
     * comes out (`GET /repos` says only `hasWebhookSecret`). The same value goes in the repo's webhook on GitHub.
     * A Smith sandbox may not send it: with a secret of its own it could sign deliveries for the repo.
     */
    @PutMapping("/repos/{owner}/{name}/webhook-secret")
    fun setWebhookSecret(@PathVariable owner: String, @PathVariable name: String, @RequestBody body: SetWebhookSecret,
                         request: HttpServletRequest? = null): ResponseEntity<Unit> {
        refuseSmith(sandbox, request, log, "set a repo's webhook secret")
        if (!secrets.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_SECRETS_KEY is not set")
        val row = targetRepoOr404(repos, "$owner/$name")
        val secret = body.secret.trim()
        if (secret.length < 16 || secret.any { it.isWhitespace() } || secret.length > 400)
            throw badRequest("secret is a webhook secret: at least 16 characters, no whitespace")
        return if (repos.setWebhookSecret(row.fullName, secret)) { publish(row.fullName, "changed", listOf("webhookSecret")); ResponseEntity.noContent().build() }
            else ResponseEntity.notFound().build()
    }

    /** Drops the repo's webhook secret: its deliveries are refused and it runs on its scan interval alone. 204, or 404 for a repo that is not a target repo. */
    @DeleteMapping("/repos/{owner}/{name}/webhook-secret")
    fun clearWebhookSecret(@PathVariable owner: String, @PathVariable name: String, request: HttpServletRequest? = null): ResponseEntity<Unit> {
        refuseSmith(sandbox, request, log, "clear a repo's webhook secret")
        val row = targetRepoOr404(repos, "$owner/$name")
        return if (repos.clearWebhookSecret(row.fullName)) { publish(row.fullName, "changed", listOf("webhookSecret")); ResponseEntity.noContent().build() }
            else ResponseEntity.notFound().build()
    }

    /** 409 while a flow still runs on the repo, naming each; its grants and its token go with it and its access history stays, as ever. */
    @DeleteMapping("/repos/{owner}/{name}")
    fun remove(@PathVariable owner: String, @PathVariable name: String): ResponseEntity<Any> {
        val fullName = "$owner/$name"
        if (!repos.contains(fullName)) return ResponseEntity.notFound().build()
        val flows = running.inRepo(fullName)
        if (flows.isNotEmpty()) {
            val named = flows.joinToString(", ") { f -> f.processKey + (f.issue?.let { " #$it" } ?: "") }
            return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf(
                "error" to "$fullName has running flows: $named; cancel or finish them first",
                "flows" to flows))
        }
        return if (repos.remove(fullName)) { publish(fullName, "removed"); ResponseEntity.noContent().build() } else ResponseEntity.notFound().build()
    }

    private fun withCosts(rows: List<TargetRepo>): List<TargetRepo> {
        val calls = costs.of(rows.map { it.fullName })
        return rows.map { it.copy(scanCalls = calls[it.fullName]) }
    }

    private fun checkName(name: String?): String? {
        if (name != null && (name.isBlank() || name.length > 200)) throw badRequest("ciCheck is a check run's name, not blank")
        return name?.trim()
    }

    private fun paths(paths: List<String>?): List<String> {
        val given = paths.orEmpty()
        if (given.any { it.isBlank() || ',' in it }) throw badRequest("ciPaths are path prefixes, not blank, with no commas")
        return given.map { it.trim() }.also { if (it.joinToString(",").length > 2000) throw badRequest("ciPaths are too long") }
    }

    private companion object {
        const val SET_INTERVAL = "set a repo's scan interval"
    }
}
