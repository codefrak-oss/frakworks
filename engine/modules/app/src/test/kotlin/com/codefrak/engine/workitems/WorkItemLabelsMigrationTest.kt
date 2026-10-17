package com.codefrak.engine.workitems

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V98 on H2, in the shape of FrictionGroupMigrationTest: V94's labels carried into kind, mode and the typed flags, then dropped. */
class WorkItemLabelsMigrationTest {
    @Test
    fun `V98 carries a feature's mode and the flags out of labels and drops the column`() {
        val db = SingleConnectionDataSource("jdbc:h2:mem:v98-${UUID.randomUUID()}", "sa", "", true)
        fun flyway(target: String) = Flyway.configure().dataSource(db).locations("classpath:db/migration").target(target).load()
        flyway("97").migrate()
        val old = JdbcClient.create(db)
        fun insert(id: String, kind: String, mode: String?, labels: String?) = old.sql(
            """INSERT INTO engine.work_item (id, full_name, description, kind, mode, state, source_kind, trusted, labels, created_at, updated_at)
               VALUES (:id, 'acme/a', 'd', :k, :m, 'ready', 'raised', TRUE, :l, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""")
            .param("id", id).param("k", kind).param("m", mode).param("l", labels).update()
        insert("modeless", "feature", null, "feature,feature:confirm,refactor")
        insert("kept", "feature", "auto", "feature,feature:auto,authorized")
        insert("bug", "bug", null, "bug,needs-repro,needs-review,stray")
        insert("ask", "ask", null, null)

        flyway("98").migrate()
        fun row(id: String) = old.sql("SELECT mode, refactor, authorized, needs_repro, needs_review FROM engine.work_item WHERE id = :id")
            .param("id", id).query { rs, _ ->
                listOf(rs.getString("mode"), rs.getBoolean("refactor"), rs.getBoolean("authorized"), rs.getBoolean("needs_repro"),
                    rs.getBoolean("needs_review"))
            }.single()
        assertEquals(listOf("confirm", true, false, false, false), row("modeless"))
        assertEquals(listOf("auto", false, true, false, false), row("kept"))
        assertEquals(listOf(null, false, false, true, true), row("bug"))
        assertEquals(listOf(null, false, false, false, false), row("ask"))
        assertTrue(runCatching { old.sql("SELECT labels FROM engine.work_item").query(String::class.java).list() }.isFailure, "no labels column after V98")
        db.destroy()
    }
}
