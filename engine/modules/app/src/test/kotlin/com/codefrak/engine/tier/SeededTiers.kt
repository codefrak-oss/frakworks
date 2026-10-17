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

    /** One migrated database for the JVM, shared by every [table] caller: they only read the map, so one migration serves them all. */
    private val shared: TierTable by lazy { fresh() }

    /** A [TierTable] over a fully migrated database, shared across tests: for callers that read the map and never write it. */
    fun table(): TierTable = shared

    /** A [TierTable] over its own fresh, fully migrated database, for a test that writes the map. */
    fun fresh(): TierTable = TierTable(jdbc())
}
