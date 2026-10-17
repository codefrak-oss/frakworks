package com.codefrak.engine.webhook

import com.codefrak.engine.lease.EngineLease
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

/**
 * Where a [WebhookWake] runs: on the lease holder alone (engine/README.md, "Replicas and the lease"), as every scan does.
 * A follower that gets a delivery hands the wake over ([hand]: the one `engine.webhook_wake` row) and the holder takes it
 * ([take]) on its next second, so no delivery is lost and no follower scans GitHub alongside the holder (#1802).
 */
interface WakeLeader {
    fun leading(): Boolean
    fun hand()
    /** Whether a follower handed a wake over since the last take; takes it. */
    fun take(): Boolean
}

@Component
class LeaseWakeLeader(private val lease: EngineLease, private val jdbc: JdbcClient) : WakeLeader {
    override fun leading() = lease.held()

    override fun hand() {
        val at = Timestamp.from(Instant.now())
        if (jdbc.sql("UPDATE engine.webhook_wake SET requested_at = :at WHERE id = 1").param("at", at).update() > 0) return
        try {
            jdbc.sql("INSERT INTO engine.webhook_wake (id, requested_at) VALUES (1, :at)").param("at", at).update()
        } catch (_: DuplicateKeyException) {
            // Another follower handed one over meanwhile: one pending wake is all there is.
        }
    }

    override fun take() = jdbc.sql("DELETE FROM engine.webhook_wake WHERE id = 1").update() > 0
}
