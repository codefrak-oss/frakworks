package com.codefrak.engine.firstmate

import com.codefrak.engine.badRequest
import com.codefrak.engine.engineSetting
import com.codefrak.engine.upsertEngineSetting
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Reads [session]'s pod into a snapshot through [exec] (`sh -c` script and stdin to stdout, null with no pod): the
 * working folder's size, the Claude session file at `$HOME/`[claudeSessionPath] (null when there is no Claude id yet or
 * no file), and the folder as a tar.gz unless it is over [FirstMateSnapshot.CAP_BYTES], when it is dropped. Null when
 * the session has no pod.
 */
fun takeSnapshot(session: FirstMateSession, exec: (String, ByteArray?) -> ByteArray?): FirstMateSnapshot? {
    val cwd = session.cwd ?: FIRST_MATE_CWD
    val size = exec("du -sb $cwd | cut -f1", null)?.toString(Charsets.UTF_8)?.trim()?.toLongOrNull() ?: return null
    val file = session.claudeSessionId?.let { exec("cat \"\$HOME/${claudeSessionPath(cwd, it)}\" 2>/dev/null || true", null) }?.takeIf { it.isNotEmpty() }
    val tar = if (size > FirstMateSnapshot.CAP_BYTES) null else exec("tar -czf - -C $cwd .", null)
    return FirstMateSnapshot(file, tar, size)
}

/**
 * The optional expiry of frozen first-mate sessions (engine/README.md, "First-mate sessions"): row
 * [NAME] of `engine.engine_setting`, whole days; absent, the default, keeps frozen sessions until deleted.
 */
@Repository
class FirstMateExpiry(private val jdbc: JdbcClient) {
    companion object { const val NAME = "firstmate.frozen-expiry-days" }

    fun get(): Int? = jdbc.engineSetting(NAME)?.toIntOrNull()

    /** Stores [days], or deletes the row (off) for null. */
    fun set(days: Int?) {
        if (days == null) jdbc.sql("DELETE FROM engine.engine_setting WHERE name = :n").param("n", NAME).update()
        else jdbc.upsertEngineSetting(NAME, days.toString())
    }
}

/** `GET`/`PUT /settings/first-mate-expiry`: `{days}`, a whole number of days at least 1, or null (off, the default). */
@RestController
class FirstMateExpiryController(private val expiry: FirstMateExpiry) {
    @GetMapping("/settings/first-mate-expiry")
    fun get(): Map<String, Int?> = mapOf("days" to expiry.get())

    @PutMapping("/settings/first-mate-expiry")
    fun set(@RequestBody body: Map<String, Any?>): Map<String, Int?> {
        if (body.keys != setOf("days")) throw badRequest("the body is {\"days\": <whole days, at least 1, or null for off>}")
        val days = when (val d = body["days"]) {
            null -> null
            is Int -> d.takeIf { it >= 1 } ?: throw badRequest("days is at least 1, or null for off")
            else -> throw badRequest("days is a whole number or null")
        }
        expiry.set(days)
        return mapOf("days" to days)
    }
}

/**
 * Freezes idle first-mate sessions and expires frozen ones (engine/README.md, "First-mate sessions",
 * "Freeze"), every minute: an open session whose
 * last transcript row is [IDLE] old is marked freezing, so its pod's inbox answers `{"freeze": true}`; the pod's
 * `fm-relay` closes claude's stdin, flushes its outbox and posts `status exited`. Once it has (or [handshake] passes
 * with no answer: no pod, or one without a relay) the pod is read into a [FirstMateSnapshot] ([takeSnapshot]), the
 * snapshot saved, the session marked frozen and its pod, Secret and pod token deleted. With [FirstMateExpiry] set, frozen
 * sessions frozen longer ago are deleted with their transcript and snapshot.
 */
@Component
class FirstMateFreezer(
    private val sessions: FirstMateSessions, private val snapshots: FirstMateSnapshots,
    private val pods: FirstMateSessionPods, private val expiry: FirstMateExpiry,
) {
    private val log = LoggerFactory.getLogger(FirstMateFreezer::class.java)

    /** The clock the idle and expiry ages are read on; a test sets a fixed one. */
    var clock: Clock = Clock.systemUTC()

    /** How long a freeze waits for the relay's `status exited`; a test sets a short one. */
    var handshake: Duration = Duration.ofSeconds(30)

    companion object {
        /** How long a session goes without a message before it is frozen. */
        val IDLE: Duration = Duration.ofMinutes(15)
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    fun tick() {
        freezeIdle()
        expire()
    }

    /** Freezes every session idle for [IDLE]; the ids frozen. */
    fun freezeIdle(): List<String> {
        val now = OffsetDateTime.now(clock)
        val cutoff = now.minus(IDLE)
        return sessions.idleSince(cutoff).mapNotNull { s ->
            try {
                val before = sessions.lastSeq(s.id)
                if (!sessions.setFreezing(s.id, cutoff)) return@mapNotNull null
                awaitRelay(s, before)
                val snapshot = takeSnapshot(s) { script, stdin -> pods.exec(s, script, stdin) }
                snapshot?.let { snapshots.save(s.id, it) }
                if (!sessions.freeze(s.id, cutoff, snapshot?.dropped ?: false, now)) return@mapNotNull null
                pods.stop(s)
                log.info("first-mate session {} frozen ({} bytes of folder{})", s.id, snapshot?.size ?: 0, if (snapshot?.dropped == true) ", dropped" else "")
                s.id
            } catch (e: Exception) {
                log.warn("first-mate session {}: freeze failed: {}", s.id, e.message)
                null
            }
        }
    }

    /** Waits up to [handshake] for [s]'s relay to post `status exited` after transcript seq [after]; whether it did. */
    private fun awaitRelay(s: FirstMateSession, after: Int): Boolean {
        val until = System.nanoTime() + handshake.toNanos()
        while (true) {
            if (sessions.podExitedAfter(s.id, after)) return true
            if (System.nanoTime() >= until) {
                log.info("first-mate session {}: no relay answered the freeze in {}; reading the pod as it is", s.id, handshake)
                return false
            }
            Thread.sleep(200)
        }
    }

    /** Deletes the sessions frozen longer ago than the expiry, when one is set; the number deleted. */
    fun expire(): Int {
        val days = expiry.get() ?: return 0
        return sessions.deleteFrozenBefore(OffsetDateTime.now(clock).minusDays(days.toLong()))
            .also { if (it > 0) log.info("{} frozen first-mate sessions older than {} days deleted", it, days) }
    }
}
