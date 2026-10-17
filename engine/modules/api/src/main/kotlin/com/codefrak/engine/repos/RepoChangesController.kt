package com.codefrak.engine.repos

import com.codefrak.engine.badRequest
import com.codefrak.engine.github.RepoTokens
import com.codefrak.engine.github.splitRepo
import com.codefrak.engine.workitems.Tickets
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.server.ResponseStatusException

/** One commit of a compare: its sha, its first parent's sha (null for a root) and its subject line. */
data class ComparedCommit(val sha: String, val parent: String?, val subject: String)

/** GitHub's compare of `from...to`: the commits `to` has that `from` has not, in GitHub's order (oldest first). */
fun interface CompareCommits {
    fun commits(repo: String, from: String, to: String): List<ComparedCommit>
}

/** A ticket's title from the engine's own work-item store, null when the store has no item for it (#100091). */
fun interface TicketTitles {
    fun title(repo: String, n: Long): String?
}

/** The compare call with the repo's token, as engine-github's RestGitHubCompare makes it; up to GitHub's 250 commits. */
@Component
class RestCompareCommits(
    private val tokens: RepoTokens,
    @Value("\${engine.github.api-url:https://api.github.com}") apiUrl: String,
) : CompareCommits {
    private val client = RestClient.builder().baseUrl(apiUrl)
        .defaultHeader("Accept", "application/vnd.github+json")
        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
        .build()

    data class Parent(val sha: String)
    data class Detail(val message: String)
    data class Commit(val sha: String, val commit: Detail, val parents: List<Parent> = emptyList())
    data class Comparison(val commits: List<Commit> = emptyList())

    override fun commits(repo: String, from: String, to: String): List<ComparedCommit> {
        val (owner, name) = splitRepo(repo)
        val body = client.get().uri("/repos/{owner}/{name}/compare/{base}...{head}?per_page=250", owner, name, from, to)
            .header("Authorization", "Bearer ${tokens.forRepo(repo)}").retrieve().body<Comparison>()
            ?: error("GitHub returned no comparison of $from...$to for $repo")
        return body.commits.map { ComparedCommit(it.sha, it.parents.firstOrNull()?.sha, it.commit.message.lineSequence().first()) }
    }
}

/** The titles from [Tickets.forTicket]: the description's first line. No GitHub read of any kind. */
@Component
class StoreTicketTitles(private val tickets: Tickets) : TicketTitles {
    override fun title(repo: String, n: Long): String? = tickets.forTicket(repo, n)?.title?.takeIf { it.isNotBlank() }
}

data class RepoChangeTicket(val n: Long, val repo: String, val url: String, val title: String?)
data class RepoChange(val sha: String, val title: String, val pr: Long, val prUrl: String, val tickets: List<RepoChangeTicket>)
data class RepoChangeList(val from: String, val to: String, val changes: List<RepoChange>)

/**
 * What lies between two builds of a target repo (#100091): the squash-merged PRs on `to`'s first-parent line since
 * `from`, newest first, each with the tickets its subject closes and their titles from the store. The subject rule is
 * observability/desktop/src/engine-update-changes.js's `parseSubject`: `Title (#ticket…) (#pr)`, the last `(#n)` the
 * PR; a commit with no `(#n)` (a direct push) is left out.
 */
class RepoChanges(private val compare: CompareCommits, private val titles: TicketTitles) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 400 for a malformed sha, 404 when [repo] is null (not a target repo), 502 when the compare fails. */
    fun answer(repo: String?, from: String, to: String): RepoChangeList {
        if (!SHA.matches(from) || !SHA.matches(to)) throw badRequest("from and to are commit shas, 7 to 40 hex digits")
        repo ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "not a target repo")
        if (from.equals(to, ignoreCase = true)) return RepoChangeList(from, to, emptyList())
        val commits = try { compare.commits(repo, from, to) } catch (e: Exception) {
            log.warn("compare {}...{} of {} failed: {}", from, to, repo, e.message)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub's compare of $from...$to failed: ${e.message}")
        }
        return RepoChangeList(from, to, firstParent(commits, to).mapNotNull { change(repo, it) })
    }

    private fun change(repo: String, c: ComparedCommit): RepoChange? {
        val (title, nums) = parseSubject(c.subject)
        if (nums.isEmpty()) return null
        val pr = nums.last()
        return RepoChange(c.sha, title, pr, "https://github.com/$repo/pull/$pr", nums.dropLast(1).map { n ->
            RepoChangeTicket(n, repo, "frakworks://ticket/$repo/$n", titles.title(repo, n))
        })
    }

    companion object {
        private val SHA = Regex("^[0-9a-fA-F]{7,40}$")
        private val TRAILING = Regex("""\s*\(#(\d+)\)\s*$""")

        /** A squash subject's title and its trailing `(#n)` numbers, in order. */
        fun parseSubject(subject: String): Pair<String, List<Long>> {
            var rest = subject.trim()
            val nums = ArrayDeque<Long>()
            while (true) {
                val m = TRAILING.find(rest) ?: break
                nums.addFirst(m.groupValues[1].toLong())
                rest = rest.substring(0, m.range.first)
            }
            return rest.trim() to nums.toList()
        }

        /** The commits on `to`'s first-parent line, newest first; all of them, newest first, when `to` is not among them by full sha. */
        fun firstParent(commits: List<ComparedCommit>, to: String): List<ComparedCommit> {
            val bySha = commits.associateBy { it.sha.lowercase() }
            val head = commits.lastOrNull { it.sha.lowercase().startsWith(to.lowercase()) } ?: return commits.reversed()
            val out = mutableListOf<ComparedCommit>()
            var c: ComparedCommit? = head
            while (c != null) { out += c; c = c.parent?.let { bySha[it.lowercase()] } }
            return out
        }
    }
}

/** `GET /repos/{owner}/{name}/changes?from=&to=` (engine/README.md, "Repos"): [RepoChanges] for a target repo. */
@RestController
class RepoChangesController(private val repos: TargetRepos, compare: CompareCommits, titles: TicketTitles) {
    private val service = RepoChanges(compare, titles)

    @GetMapping("/repos/{owner}/{name}/changes")
    fun changes(@PathVariable owner: String, @PathVariable name: String,
                @RequestParam from: String, @RequestParam to: String): RepoChangeList =
        service.answer(repos.find("$owner/$name")?.fullName, from, to)
}
