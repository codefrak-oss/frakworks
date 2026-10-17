package com.codefrak.engine.friction

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** V39 and V41 on H2, in the shape of TargetReposTest's V32 test: the backfill of every existing note's key, the columns and tables V39 adds, and V41's `grouped_login` (V40 is #1101's Codex account migration on main, in between). */
class FrictionGroupMigrationTest {
    @Test
    fun `V39 backfills every existing note's signature by the insert's function, makes it NOT NULL, and adds the group tables`() {
        val db = SingleConnectionDataSource("jdbc:h2:mem:v39-${UUID.randomUUID()}", "sa", "", true)
        fun flyway(target: String) = Flyway.configure().dataSource(db).locations("classpath:db/migration").target(target).load()
        flyway("38").migrate()
        val old = JdbcClient.create(db)
        fun insert(id: String, summary: String, evidence: String?) = old.sql(
            "INSERT INTO engine.friction_note (id, repo, issue, noted_at, source, kind, summary, evidence) VALUES (:id, 'acme/a', 7, CURRENT_TIMESTAMP, 'as-you-go', 'tooling', :s, :e)")
            .param("id", id).param("s", summary).param("e", evidence).update()
        insert("with-evidence", "not executable", "\$ bots/check /work/out/body.md\nPermission denied")
        insert("same-line", "another summary", "  \$ bots/check   /work/out/body.md\nexit 126")
        insert("no-evidence", "no Gradle in the sandbox\nmore", null)
        insert("blank-evidence", "no Gradle in the sandbox", "  \n ")

        flyway("39").migrate()
        fun signature(id: String) = old.sql("SELECT signature FROM engine.friction_note WHERE id = :id").param("id", id).query(String::class.java).single()
        assertEquals(FrictionKey.of("not executable", "\$ bots/check /work/out/body.md\nPermission denied"), signature("with-evidence"))
        assertEquals(signature("with-evidence"), signature("same-line"), "the key is over the normalised first line of the evidence alone")
        assertEquals(FrictionKey.of("no Gradle in the sandbox\nmore", null), signature("no-evidence"))
        assertEquals(signature("no-evidence"), signature("blank-evidence"), "blank evidence is none: the summary's line")
        assertEquals(4, old.sql("SELECT COUNT(*) FROM engine.friction_note WHERE group_id IS NULL AND grouped_by IS NULL AND jev_call_id IS NULL AND group_attempts = 0 AND next_attempt_at IS NULL")
            .query(Int::class.javaObjectType).single(), "every row left for the grouper's first drain")
        assertFailsWith<DataIntegrityViolationException>("signature is NOT NULL after the backfill") {
            insert("after", "s", null)
        }
        assertEquals(0, old.sql("SELECT COUNT(*) FROM engine.friction_group").query(Int::class.javaObjectType).single())
        assertEquals(0, old.sql("SELECT COUNT(*) FROM engine.friction_signature").query(Int::class.javaObjectType).single())
        old.sql("INSERT INTO engine.friction_group (title, created_at) VALUES ('t', CURRENT_TIMESTAMP)").update()
        val group = old.sql("SELECT id FROM engine.friction_group").query(Long::class.javaObjectType).single()
        old.sql("INSERT INTO engine.friction_signature (signature, group_id, line, first_seen) VALUES ('k', :g, 'l', CURRENT_TIMESTAMP)").param("g", group).update()
        assertFailsWith<DataIntegrityViolationException>("a signature points at a group") {
            old.sql("INSERT INTO engine.friction_signature (signature, group_id, line, first_seen) VALUES ('k2', 999999, 'l', CURRENT_TIMESTAMP)").update()
        }
        assertTrue(old.sql("SELECT group_id FROM engine.friction_note WHERE id = 'with-evidence'").query(Long::class.javaObjectType).optional().isEmpty,
            "the group column is there, and null until the grouper's first drain")
        db.destroy()
    }

    @Test
    fun `V41 adds grouped_login, null on every existing row and written by a user's pull-out alone`() {
        val db = SingleConnectionDataSource("jdbc:h2:mem:v41-${UUID.randomUUID()}", "sa", "", true)
        fun flyway(target: String) = Flyway.configure().dataSource(db).locations("classpath:db/migration").target(target).load()
        flyway("40").migrate()
        val old = JdbcClient.create(db)
        old.sql("INSERT INTO engine.friction_note (id, repo, issue, noted_at, source, kind, summary, signature) VALUES ('before', 'acme/a', 7, CURRENT_TIMESTAMP, 'as-you-go', 'tooling', 's', 'k')").update()
        assertTrue(runCatching { old.sql("SELECT grouped_login FROM engine.friction_note").query(String::class.java).list() }.isFailure, "no such column before V41")

        flyway("41").migrate()
        assertTrue(old.sql("SELECT grouped_login FROM engine.friction_note WHERE id = 'before'").query(String::class.java).optional().isEmpty, "null on an existing row")
        old.sql("INSERT INTO engine.friction_note (id, repo, issue, noted_at, source, kind, summary, signature, grouped_by, grouped_login) " +
            "VALUES ('after', 'acme/a', 7, CURRENT_TIMESTAMP, 'as-you-go', 'tooling', 's2', 'k2', 'user', 'someone')").update()
        assertEquals("someone", old.sql("SELECT grouped_login FROM engine.friction_note WHERE id = 'after'").query(String::class.java).single())
        flyway("45").migrate()
        assertEquals(20, old.sql("SELECT ${FrictionNote.COLUMNS} FROM engine.friction_note WHERE id = 'after'")
            .query { rs, _ -> rs.metaData.columnCount }.single(), "the row reader's columns are all there")
        db.destroy()
    }
}
