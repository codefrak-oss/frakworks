package com.codefrak.engine.repos

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

data class TrustedLogin(val login: String)
data class AccessChange(val changedAt: OffsetDateTime, val change: String, val login: String, val changedBy: String?)

/**
 * `engine.engine_access` and its audit: the one engine-wide list of trusted GitHub logins. A login on it is
 * trusted on every target repo: its labels, answers, cancels, retries and First mate actions count
 * (engine/README.md, "Starting a flow from an issue"). There is no kind and no repo in the check.
 */
@Repository
class Access(private val jdbc: JdbcClient) {
    fun list(): List<TrustedLogin> =
        jdbc.sql("SELECT login FROM engine.engine_access ORDER BY LOWER(login)").query { rs, _ -> TrustedLogin(rs.getString(1)) }.list()

    /** GitHub logins are case-insensitive. */
    fun trusted(login: String): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM engine.engine_access WHERE LOWER(login) = LOWER(:l)").param("l", login)
            .query(Int::class.java).single() > 0

    /** False when it was already on the list. */
    @Transactional
    fun add(login: String, by: String?): Boolean {
        val added = jdbc.sql("""INSERT INTO engine.engine_access (login) SELECT :l
            WHERE NOT EXISTS (SELECT 1 FROM engine.engine_access WHERE LOWER(login) = LOWER(:l))""")
            .param("l", login).update() == 1
        if (added) audit("grant", login, by)
        return added
    }

    /** False when it was not on the list. */
    @Transactional
    fun remove(login: String, by: String?): Boolean {
        val removed = jdbc.sql("DELETE FROM engine.engine_access WHERE LOWER(login) = LOWER(:l)").param("l", login).update() >= 1
        if (removed) audit("revoke", login, by)
        return removed
    }

    /** Every change, oldest first; rows from before V54 (per-kind grants) show their login alone. */
    fun history(): List<AccessChange> =
        jdbc.sql("SELECT changed_at, change, login, changed_by FROM engine.engine_access_audit ORDER BY id")
            .query { rs, _ -> AccessChange(rs.getObject(1, OffsetDateTime::class.java), rs.getString(2), rs.getString(3), rs.getString(4)) }
            .list()

    private fun audit(change: String, login: String, by: String?) {
        jdbc.sql("INSERT INTO engine.engine_access_audit (change, login, changed_by) VALUES (:c, :l, :b)")
            .param("c", change).param("l", login).param("b", by).update()
    }
}
