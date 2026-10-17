package com.codefrak.engine.tier

import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.util.UUID

/** A fresh H2 with Flyway's migrations applied, the same scripts Postgres runs: the tier map as V42 seeds it, for tests with no Spring context. */
object SeededTiers {
    /** The database alone, migrated up to `target` (all of them by default). */
    fun jdbc(target: String = "latest"): JdbcClient {
        val db = SingleConnectionDataSource("jdbc:h2:mem:tiers-${UUID.randomUUID()}", "sa", "", true)
        Flyway.configure().dataSource(db).locations("classpath:db/migration").target(target).load().migrate()
        return JdbcClient.create(db)
    }

    /** A [TierTable] over a fresh, fully migrated database. */
    fun table(): TierTable = TierTable(jdbc())
}
