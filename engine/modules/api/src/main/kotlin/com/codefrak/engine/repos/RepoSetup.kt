package com.codefrak.engine.repos

import com.codefrak.engine.ci.CheckRuns
import com.codefrak.engine.github.RepoSetupGitHub
import com.codefrak.engine.github.RepoTokens
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.server.ResponseStatusException

data class CreatedLabels(val created: List<String>)

/** The GitHub-side setup of a target repo (engine/README.md, "A new target repo's GitHub setup"), read with the repo's token. */
@Component
class RepoSetupCheck(private val github: RepoSetupGitHub, private val checkRuns: CheckRuns, private val tokens: RepoTokens) {
    companion object {
        /** The labels the flows and the scan use, with the colour a created one gets. */
        val LABELS = linkedMapOf(
            "authorized" to "0e8a16", "chore" to "c5def5", "bug" to "d73a4a", "feature" to "a2eeef",
            "feature:auto" to "a2eeef", "feature:confirm" to "a2eeef", "proposal" to "d4c5f9", "research" to "bfd4f2", "intake" to "fbca04",
        )
        /** How far back on the default branch the check's history is looked for, one check-runs call a commit. */
        const val COMMITS_LOOKED = 30
        /**
         * The events a repo's hook must send: `issues` and `check_suite` wake the scan, with `issue_comment` and
         * `pull_request` they feed the stream's `ticket` frames, and `push` feeds its `push` frames, which First mate's
         * fresh clones follow (engine/README.md, "Events"; "A new target repo's GitHub setup").
         */
        val HOOK_EVENTS = listOf("issues", "check_suite", "issue_comment", "pull_request", "push")
    }

    /** Reads only; no GitHub writes. */
    fun check(row: TargetRepo): SetupReport {
        val repo = row.fullName
        if (tokens.resolve(repo) == null) return SetupReport(repo, hasToken = false, readable = false)
        val settings = github.repo(repo) ?: return SetupReport(repo, hasToken = true, readable = false)
        val branch = branch(repo, settings.defaultBranch, settings.allowSquashMerge)
        return SetupReport(repo, true, true, settings.push, missing(repo), branch,
            row.ciCheck?.takeIf { branch.exists }?.let { history(repo, branch.name, it) }, webhook(repo))
    }

    fun readable(repo: String): Boolean = tokens.resolve(repo) != null && github.repo(repo) != null

    /** Creates the labels [check] would report missing, and says which; a label another caller made meanwhile is left out. */
    fun createLabels(repo: String): List<String> = missing(repo).filter { name ->
        try {
            github.createLabel(repo, name, LABELS.getValue(name)); true
        } catch (e: HttpClientErrorException) {
            if (e.statusCode.value() == 422) false else throw e
        }
    }

    /** GitHub label names are case-insensitive. */
    private fun missing(repo: String): List<String> {
        val have = github.labels(repo).map { it.lowercase() }.toSet()
        return LABELS.keys.filter { it !in have }
    }

    /** The repo was readable, so GitHub's "no such branch" means the branch is absent, not that the token is refused. */
    private fun branch(repo: String, name: String, allowSquash: Boolean?): BranchSetup {
        val protected = github.isProtected(repo, name) ?: return BranchSetup(name, false, null, null, exists = false)
        val rules = github.branchRules(repo, name)
        val pullRules = rules.orEmpty().filter { it.type == "pull_request" }
        val classicReviews = if (protected) github.requiredReviews(repo, name) else 0
        val reviews = when {
            pullRules.any { (it.requiredReviews ?: 0) > 0 } || (classicReviews ?: 0) > 0 -> true
            classicReviews == null || rules == null -> null
            else -> false
        }
        val squash = when {
            allowSquash == false || pullRules.any { r -> r.allowedMergeMethods?.let { "squash" !in it } == true } -> true
            allowSquash == null || rules == null -> null
            else -> false
        }
        return BranchSetup(name, protected, reviews, squash)
    }

    private fun history(repo: String, branch: String, check: String): CheckHistory {
        val shas = github.recentCommits(repo, branch, COMMITS_LOOKED)
        return CheckHistory(check, shas.any { checkRuns.latest(repo, it, check) != null }, shas.size)
    }

    private fun webhook(repo: String): WebhookSetup {
        val hooks = github.hooks(repo) ?: return WebhookSetup("unknown", null)
        val feeds = hooks.any { h -> h.active && ("*" in h.events || h.events.containsAll(HOOK_EVENTS)) }
        return WebhookSetup(if (feeds) "present" else "missing", hooks)
    }
}

/** Unauthenticated, like the rest of the engine's API. Answers only for target repos; 502 when GitHub fails. */
@RestController
class RepoSetupController(private val repos: TargetRepos, private val setup: RepoSetupCheck) {
    @GetMapping("/repos/{owner}/{name}/setup")
    fun check(@PathVariable owner: String, @PathVariable name: String): SetupReport = github { setup.check(row(owner, name)) }

    /** 409 when the token cannot read the repo. */
    @PostMapping("/repos/{owner}/{name}/setup/labels")
    fun labels(@PathVariable owner: String, @PathVariable name: String): CreatedLabels {
        val row = row(owner, name)
        return github {
            if (!setup.readable(row.fullName)) throw ResponseStatusException(HttpStatus.CONFLICT, "the repo's token cannot read ${row.fullName}")
            CreatedLabels(setup.createLabels(row.fullName))
        }
    }

    private fun row(owner: String, name: String) = repos.find("$owner/$name")
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$owner/$name is not a target repo; POST /repos first")

    private fun <T> github(read: () -> T): T = try { read() } catch (e: RestClientException) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub: ${e.message}")
    }
}
