package com.codefrak.engine.events

import com.codefrak.engine.lease.InstanceId
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.sql.DriverManager
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The event relay over Postgres LISTEN/NOTIFY (engine/README.md, "Replicas and the lease"). [send] NOTIFYs channel
 * [CHANNEL] with `{"o": origin, "r": repo, "f": frame}`, in publish order from one sender thread; a frame whose payload
 * would pass Postgres' 8000-byte limit goes into `engine.event_relay` and the notification carries its id
 * (`{"o", "id"}`) instead. A listener thread holds a connection of its own (outside the pool, so it never counts as a
 * leak), LISTENs, and hands each notification another instance sent to the bus; its own (`o` is this instance) it skips.
 * A dropped connection is reopened after [RETRY]; frames sent meanwhile are lost, as a client's own reconnect loses them.
 */
@Component
@ConditionalOnProperty("engine.events.relay", havingValue = "postgres", matchIfMissing = true)
class PostgresEventRelay(
    private val template: JdbcTemplate,
    private val instance: InstanceId,
    private val json: ObjectMapper,
    @Value("\${spring.datasource.url}") private val url: String,
    @Value("\${spring.datasource.username:}") private val username: String,
    @Value("\${spring.datasource.password:}") private val password: String,
) : EventRelay, SmartLifecycle {
    private val log = LoggerFactory.getLogger(PostgresEventRelay::class.java)
    @Volatile private var handler: (String, String) -> Unit = { _, _ -> }
    @Volatile private var running = false
    @Volatile private var listening = false
    private var listener: Thread? = null
    private val sender: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "event-relay-send").apply { isDaemon = true } }

    override fun listen(handler: (String, String) -> Unit) { this.handler = handler }

    override fun listening() = listening

    override fun send(repo: String, frame: String) {
        if (!running) return
        runCatching { sender.execute { runCatching { notify(repo, frame) }.onFailure { log.warn("events: frame not relayed: {}", it.message) } } }
    }

    private fun notify(repo: String, frame: String) {
        var payload = json.writeValueAsString(mapOf("o" to instance.name, "r" to repo, "f" to frame))
        if (payload.toByteArray().size > MAX_PAYLOAD) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            template.update("DELETE FROM engine.event_relay WHERE at < ?", now.minus(KEEP))
            val id = template.queryForObject("INSERT INTO engine.event_relay (origin, repo, frame, at) VALUES (?, ?, ?, ?) RETURNING id",
                Long::class.java, instance.name, repo, frame, now)
            payload = json.writeValueAsString(mapOf("o" to instance.name, "id" to id))
        }
        template.queryForList("SELECT pg_notify(?, ?)", CHANNEL, payload)
    }

    /** One notification's payload: to the bus unless this instance sent it. */
    fun receive(payload: String) {
        val m = json.readValue(payload, Map::class.java)
        if (m["o"] == instance.name) return
        val id = (m["id"] as? Number)?.toLong()
        if (id == null) { handler(m["r"] as String, m["f"] as String); return }
        template.query("SELECT repo, frame FROM engine.event_relay WHERE id = ?", { rs, _ -> rs.getString(1) to rs.getString(2) }, id)
            .firstOrNull()?.let { (repo, frame) -> handler(repo, frame) }
            ?: log.warn("events: relayed frame {} is gone", id)
    }

    private fun loop() {
        while (running) {
            try {
                DriverManager.getConnection(url, username, password).use { c ->
                    c.autoCommit = true
                    c.createStatement().use { it.execute("LISTEN $CHANNEL") }
                    val pg = c.unwrap(PGConnection::class.java)
                    listening = true
                    log.info("events: relay listening on {} as {}", CHANNEL, instance.name)
                    while (running) {
                        val notes = pg.getNotifications(POLL_MILLIS) ?: continue
                        for (n in notes) runCatching { receive(n.parameter) }.onFailure { log.warn("events: relayed frame dropped: {}", it.message) }
                    }
                }
            } catch (e: Exception) {
                listening = false
                if (!running) break
                log.warn("events: relay listener down ({}); reconnecting in {}", e.message, RETRY)
                try { Thread.sleep(RETRY.toMillis()) } catch (ie: InterruptedException) { break }
            }
        }
        listening = false
    }

    override fun start() {
        running = true
        listener = Thread(::loop, "event-relay-listen").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        listener?.interrupt()
        sender.shutdown()
    }

    override fun isRunning() = running

    companion object {
        const val CHANNEL = "engine_events"
        /** Under Postgres' 8000-byte NOTIFY payload limit. */
        const val MAX_PAYLOAD = 7900
        const val POLL_MILLIS = 1000
        val RETRY: Duration = Duration.ofSeconds(2)
        /** How long a large frame's row is kept for the other replicas to read. */
        val KEEP: Duration = Duration.ofMinutes(1)
    }
}
