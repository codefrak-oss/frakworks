package com.codefrak.engine.repos

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Against H2 with Flyway's migrations applied, the same scripts Postgres runs. */
@SpringBootTest
class AccessTest(@Autowired val repos: TargetRepos, @Autowired val access: Access) {
    @BeforeEach @AfterEach fun none() { access.list().forEach { access.remove(it.login, null) } }

    @Test
    fun `a login is trusted once added, with no kind and no repo, until removed, and every change is audited`() {
        assertFalse(access.trusted("alice"))
        assertTrue(access.add("alice", "matt"))
        assertFalse(access.add("Alice", "matt"), "logins are case-insensitive")
        assertTrue(access.trusted("alice"))
        assertTrue(access.trusted("ALICE"))
        assertEquals(listOf(TrustedLogin("alice")), access.list())

        assertTrue(access.remove("ALICE", "matt"))
        assertFalse(access.remove("alice", "matt"))
        assertFalse(access.trusted("alice"))
        assertEquals(listOf("grant" to "matt", "revoke" to "matt"), access.history().takeLast(2).map { it.change to it.changedBy })
    }

    @Test
    fun `a trusted login holds on every target repo, current and future`() {
        access.add("carol", null)
        repos.add("acme/later")
        try { assertTrue(access.trusted("carol")) } finally { repos.remove("acme/later") }
    }

    @Test
    fun `V54 carries every distinct non-jobs login from the per-repo and engine-wide grants, with a migration entry each`() {
        val db = SingleConnectionDataSource("jdbc:h2:mem:v53-${UUID.randomUUID()}", "sa", "", true)
        fun flyway(target: String) = Flyway.configure().dataSource(db).locations("classpath:db/migration").target(target).load()
        flyway("53").migrate()
        val old = JdbcClient.create(db)
        old.sql("INSERT INTO engine.target_repo (full_name, scan_interval_minutes) VALUES ('acme/a', 60), ('acme/b', 60)").update()
        for ((repo, flow, login) in listOf(Triple("acme/a", "chore", "alice"), Triple("acme/a", "bug", "alice"), Triple("acme/b", "intake", "Alice"),
            Triple("acme/b", "research", "bob"), Triple("acme/a", "jobs", "jobsonly")))
            old.sql("INSERT INTO engine.repo_access (full_name, flow, login) VALUES (:r, :f, :l)").param("r", repo).param("f", flow).param("l", login).update()
        for ((flow, login) in listOf("*" to "carol", "feature" to "bob", "jobs" to "jobsonly2"))
            old.sql("INSERT INTO engine.engine_access (flow, login) VALUES (:f, :l)").param("f", flow).param("l", login).update()
        old.sql("INSERT INTO engine.engine_access_audit (change, flow, login, changed_by) VALUES ('grant', '*', 'carol', 'matt')").update()

        flyway("54").migrate()
        val logins = old.sql("SELECT login FROM engine.engine_access").query(String::class.java).list().map { it!!.lowercase() }.sorted()
        assertEquals(listOf("alice", "bob", "carol"), logins, "distinct, case-insensitively; jobs-only logins dropped")
        assertEquals(listOf("alice", "bob", "carol"),
            old.sql("SELECT login FROM engine.engine_access_audit WHERE change = 'migration' AND flow IS NULL").query(String::class.java).list().map { it!!.lowercase() }.sorted())
        assertEquals(1, old.sql("SELECT COUNT(*) FROM engine.engine_access_audit WHERE change = 'grant' AND flow = '*'").query(Int::class.javaObjectType).single(),
            "the old audit rows stay")
        assertTrue(runCatching { old.sql("SELECT COUNT(*) FROM engine.repo_access").query(Int::class.javaObjectType).single() }.isFailure, "repo_access is gone")
        assertTrue(runCatching { old.sql("SELECT flow FROM engine.engine_access").query(String::class.java).list() }.isFailure, "engine_access has no flow")
        db.destroy()
    }
}
