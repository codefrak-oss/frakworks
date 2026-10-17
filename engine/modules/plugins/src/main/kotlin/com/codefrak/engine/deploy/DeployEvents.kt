package com.codefrak.engine.deploy

import com.codefrak.engine.board.targetRepoOr404
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.repos.TargetRepos
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * What a deploy wakes (#100008): the flow part registers the real one (a flow waiting for a commit to be in
 * production); here nothing listens, and [DeployEvents.post] calls every bean of this type, none by default.
 */
fun interface DeployWaits {
    /** [sha] is now in production for [repo] (the deploy event's row is written and its frame published). */
    fun wake(repo: String, sha: String)

    /** The no-op: what the engine has until the flow part registers one. */
    object None : DeployWaits { override fun wake(repo: String, sha: String) {} }
}

/** One row of `engine.deploy_event`, as `GET /repos/{owner}/{name}/deploys` lists it. */
data class DeployEvent(val id: Long, val repo: String, val sha: String, val source: String?, val at: Instant)

/**
 * The engine's record of deploys (#100008; engine/README.md, "Deploys"): `engine.deploy_event` (V96), one row per
 * `POST /repos/{owner}/{name}/deploys` and per engine start for its own repo. [post] writes the row, publishes the
 * `deploy` frame ([EngineEvent.Deploy]) and wakes every [DeployWaits]; [postOnce] does so only when the repo has no
 * row for the sha yet (the engine's own start, once per commit).
 */
@Component
class DeployEvents(private val jdbc: JdbcClient, private val repos: TargetRepos, private val events: EngineEvents,
                   private val waits: List<DeployWaits>) {
    private val log = LoggerFactory.getLogger(DeployEvents::class.java)

    /** The rows' `at`; a test sets a fake. */
    var clock: Clock = Clock.systemUTC()

    companion object {
        /** A commit as the endpoint takes it: 7 to 40 hex digits, either case. */
        val SHA = Regex("^[0-9a-fA-F]{7,40}$")
        const val SHA_400 = "sha is required: 7 to 40 hex digits, the commit now in production"
        const val MAX_SOURCE = 100
    }

    /** 404 for a repo that is not a target repo. */
    fun repo(fullName: String): String = targetRepoOr404(repos, fullName).fullName

    /** Records the deploy of [sha] (already checked against [SHA]) on [repo] (a target repo's row name), and tells everyone. */
    fun post(repo: String, sha: String, source: String?): DeployEvent {
        val at = clock.instant()
        val clean = sha.lowercase()
        val src = source?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_SOURCE)
        val keys = GeneratedKeyHolder()
        jdbc.sql("INSERT INTO engine.deploy_event (repo, sha, source, at) VALUES (:r, :s, :o, :t)")
            .param("r", repo).param("s", clean).param("o", src).param("t", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .update(keys, "id")
        val row = DeployEvent((keys.key as? Number)?.toLong() ?: -1L, repo, clean, src, at)
        events.publish(EngineEvent.Deploy(repo, clean, src, at))
        for (w in waits) runCatching { w.wake(repo, clean) }.onFailure { log.warn("deploy {} {}: {} failed: {}", repo, clean, w.javaClass.simpleName, it.message) }
        return row
    }

    /** [post], unless the repo already has a row for [sha]: null then. */
    fun postOnce(repo: String, sha: String, source: String?): DeployEvent? {
        val had = jdbc.sql("SELECT COUNT(*) FROM engine.deploy_event WHERE repo = :r AND sha = :s")
            .param("r", repo).param("s", sha.lowercase()).query(Long::class.java).single()
        return if (had > 0) null else post(repo, sha, source)
    }

    /** The repo's last [limit] deploys, newest first. */
    fun list(repo: String, limit: Int): List<DeployEvent> =
        jdbc.sql("SELECT id, repo, sha, source, at FROM engine.deploy_event WHERE repo = :r ORDER BY at DESC, id DESC LIMIT :l")
            .param("r", repo).param("l", limit)
            .query { rs, _ -> DeployEvent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime::class.java).toInstant()) }
            .list()
}
