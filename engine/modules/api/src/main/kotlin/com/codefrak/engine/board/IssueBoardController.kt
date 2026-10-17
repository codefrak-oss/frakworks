package com.codefrak.engine.board

import com.codefrak.engine.repos.TargetRepos
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

/**
 * Unauthenticated, like the rest of the engine's API: the issues board, what a UI lists first
 * (`state=open`, the default), or the closed issues (`state=closed`, at most `limit`, 1..500),
 * of every target repo or of `repo`, which must be one; `q` searches the closed ones (GitHub's issue search).
 *
 * The open board is built once for all the requests that ask for the same repos while it is being built, and
 * a board finished less than [REUSE] ago is answered as is: on 2026-10-06 the desktop and the live updates
 * asked again before a build of several seconds ended, the builds piled up on the engine's threads, and every
 * one of them timed out (each build also lists the repo's issues from GitHub, out of the rate limit).
 */
@RestController
class IssueBoardController(
    private val board: IssueBoard,
    private val closed: ClosedIssues,
    private val repos: TargetRepos,
    private val reuse: Duration = REUSE,
) {
    @org.springframework.beans.factory.annotation.Autowired
    constructor(board: IssueBoard, closed: ClosedIssues, repos: TargetRepos) : this(board, closed, repos, REUSE)

    private class Build(val board: CompletableFuture<Board>) { @Volatile var done: Instant? = null }
    private val builds = ConcurrentHashMap<String, Build>()

    @GetMapping("/issues")
    fun issues(@RequestParam(defaultValue = "open") state: String, @RequestParam(defaultValue = "100") limit: Int,
               @RequestParam(required = false) repo: String?, @RequestParam(required = false) q: String?): Any {
        if (state != "open" && state != "closed") throw ResponseStatusException(HttpStatus.BAD_REQUEST, "state must be open or closed, not $state")
        val target = repo?.let { targetRepo(repos, it) }
        return if (state == "open") open(target) else closed.list(limit.coerceIn(1, 500), target, q)
    }

    /** The board of [target] (every repo when null): one build at a time per target, reused for [reuse] once done. */
    fun open(target: String?): Board {
        val key = target?.lowercase() ?: ""
        var mine = false
        val build = builds.compute(key) { _, current ->
            val fresh = current?.let { c -> c.done?.let { Duration.between(it, Instant.now()) < reuse } ?: true } ?: false
            if (fresh) current else Build(CompletableFuture()).also { mine = true }
        }!!
        if (mine) {
            try {
                build.board.complete(board.board(target))
            } catch (e: Throwable) {
                // A failed build is not kept: the next request builds again.
                builds.remove(key, build)
                build.board.completeExceptionally(e)
            } finally {
                build.done = Instant.now()
            }
        }
        return try { build.board.get() } catch (e: ExecutionException) { throw e.cause ?: e }
    }

    companion object {
        /** How long a finished board is answered as is. */
        val REUSE: Duration = Duration.ofSeconds(2)
    }
}
