package com.codefrak.engine.intake

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** One row of `engine.intake_dependency`: [ticket] waits on the ticket [intake] has not filed yet, blocked by [intakeIssue] meanwhile. */
data class IntakeDependency(val repo: String, val ticket: Long, val intake: String, val intakeIssue: Long)

/** `engine.intake_dependency`: tickets filed blocked by an earlier intake's ask item, to re-point when that intake files. */
@Repository
class IntakeDependencies(private val jdbc: JdbcClient) {
    fun record(dep: IntakeDependency) {
        jdbc.sql("""INSERT INTO engine.intake_dependency (full_name, ticket, intake, intake_issue) SELECT :r, :t, :p, :i
                    WHERE NOT EXISTS (SELECT 1 FROM engine.intake_dependency WHERE full_name = :r AND ticket = :t AND intake = :p)""")
            .param("r", dep.repo).param("t", dep.ticket).param("p", dep.intake).param("i", dep.intakeIssue).update()
    }

    /** Every ticket waiting on [intake]'s ticket. */
    fun on(intake: String): List<IntakeDependency> =
        jdbc.sql("SELECT full_name, ticket, intake, intake_issue FROM engine.intake_dependency WHERE intake = :p ORDER BY ticket")
            .param("p", intake).query { rs, _ -> IntakeDependency(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4)) }.list()

    fun remove(dep: IntakeDependency) {
        jdbc.sql("DELETE FROM engine.intake_dependency WHERE full_name = :r AND ticket = :t AND intake = :p")
            .param("r", dep.repo).param("t", dep.ticket).param("p", dep.intake).update()
    }
}
