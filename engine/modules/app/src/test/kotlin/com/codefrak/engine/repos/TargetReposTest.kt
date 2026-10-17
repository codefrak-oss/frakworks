package com.codefrak.engine.repos

import com.codefrak.engine.ci.CiCheck
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against H2 with Flyway's migrations applied, the same scripts Postgres runs. */
@SpringBootTest
class TargetReposTest(@Autowired val repos: TargetRepos, @Autowired val access: Access, @Autowired val jdbc: JdbcClient) {
    @Test
    fun `a repo's webhook secret is sealed on its row apart from its token, opened by name ignoring case, and cleared`() {
        repos.add("acme/hooked")
        assertNull(repos.webhookSecret("acme/hooked"))
        assertFalse(repos.list().single { it.fullName == "acme/hooked" }.hasWebhookSecret)
        assertFalse(repos.setWebhookSecret("acme/nowhere", "0123456789abcdef0123"), "no such row")
        assertTrue(repos.setWebhookSecret("acme/hooked", "0123456789abcdef0123"))
        assertEquals("0123456789abcdef0123", repos.webhookSecret("ACME/Hooked"), "looked up ignoring case")
        val row = repos.list().single { it.fullName == "acme/hooked" }
        assertTrue(row.hasWebhookSecret); assertTrue(row.webhookSecretSetAt != null)
        assertFalse(row.hasToken, "the token is another column")
        assertNull(repos.token("acme/hooked"))
        val stored = jdbc.sql("SELECT webhook_secret FROM engine.target_repo WHERE full_name = 'acme/hooked'").query { rs, _ -> rs.getBytes(1) }.single()
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("0123456789abcdef"), "ciphertext, not the secret")
        assertTrue(repos.clearWebhookSecret("acme/hooked"))
        assertNull(repos.webhookSecret("acme/hooked"))
        assertFalse(repos.list().single { it.fullName == "acme/hooked" }.hasWebhookSecret)
        repos.remove("acme/hooked")
    }

    @Test
    fun `a repo's token is sealed on its row, opened by name ignoring case, and cleared`() {
        repos.add("acme/tokened")
        assertNull(repos.token("acme/tokened"))
        assertFalse(repos.list().single { it.fullName == "acme/tokened" }.hasToken)

        assertTrue(repos.setToken("acme/tokened", "github_pat_secret"))
        assertEquals("github_pat_secret", repos.token("acme/tokened"))
        assertEquals("github_pat_secret", repos.token("ACME/Tokened"), "looked up ignoring case")
        val row = repos.list().single { it.fullName == "acme/tokened" }
        assertTrue(row.hasToken); assertTrue(row.tokenSetAt != null)
        val stored = jdbc.sql("SELECT token FROM engine.target_repo WHERE full_name = 'acme/tokened'").query { rs, _ -> rs.getBytes(1) }.single()
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("github_pat"), "ciphertext, not the token")

        assertTrue(repos.clearToken("acme/tokened"))
        assertNull(repos.token("acme/tokened"))
        assertFalse(repos.list().single { it.fullName == "acme/tokened" }.hasToken)
        assertFalse(repos.setToken("acme/nowhere", "x"))
        assertFalse(repos.clearToken("acme/nowhere"))
        assertNull(repos.token("acme/nowhere"))
        repos.remove("acme/tokened")
    }

    @Test
    fun `V32 gives every existing repo the hourly scan's 60 minutes, and the column takes no null and nothing out of range`() {
        // A database of its own at V31, with a row from before the interval, then migrated on.
        val db = SingleConnectionDataSource("jdbc:h2:mem:v32-${UUID.randomUUID()}", "sa", "", true)
        fun flyway(target: String) = Flyway.configure().dataSource(db).locations("classpath:db/migration").target(target).load()
        flyway("31").migrate()
        val old = JdbcClient.create(db)
        old.sql("INSERT INTO engine.target_repo (full_name) VALUES ('acme/before')").update()
        flyway("32").migrate()
        assertEquals(60, old.sql("SELECT scan_interval_minutes FROM engine.target_repo WHERE full_name = 'acme/before'").query { rs, _ -> rs.getInt(1) }.single())
        assertFailsWith<DataIntegrityViolationException> {
            old.sql("INSERT INTO engine.target_repo (full_name) VALUES ('acme/after')").update()
        }
        for (bad in listOf(0, 1441)) assertFailsWith<DataIntegrityViolationException> {
            old.sql("UPDATE engine.target_repo SET scan_interval_minutes = :i").param("i", bad).update()
        }
        db.destroy()
    }

    @Test
    fun `a repo's scan interval is written when it is added and set apart from its other settings`() {
        assertTrue(repos.add("acme/scanned"))
        assertTrue(repos.add("acme/scanned-fast", scanIntervalMinutes = 5))
        assertEquals(60, repos.list().single { it.fullName == "acme/scanned" }.scanIntervalMinutes)
        assertEquals(5, repos.list().single { it.fullName == "acme/scanned-fast" }.scanIntervalMinutes)
        assertTrue(repos.setScanInterval("acme/scanned", 1440))
        assertTrue(repos.update("acme/scanned", "build", emptyList(), true))
        assertEquals(1440, repos.list().single { it.fullName == "acme/scanned" }.scanIntervalMinutes, "update leaves it")
        assertFalse(repos.setScanInterval("acme/nowhere", 15))
        assertFailsWith<IllegalArgumentException> { repos.setScanInterval("acme/scanned", 0) }
        repos.remove("acme/scanned"); repos.remove("acme/scanned-fast")
    }

    @Test
    fun `adds lists and removes a repo`() {
        assertFalse(repos.contains("acme/widgets"))
        assertTrue(repos.add("acme/widgets"))
        assertFalse(repos.add("acme/widgets"))
        assertTrue(repos.contains("acme/widgets"))
        // Other tests seed repos of their own in the shared H2 (the chore's acme/tickets), so membership, not the whole table.
        assertTrue("acme/widgets" in repos.list().map { it.fullName })
        assertTrue(repos.remove("acme/widgets"))
        assertFalse(repos.remove("acme/widgets"))
        assertFalse(repos.contains("acme/widgets"))
        assertFalse("acme/widgets" in repos.list().map { it.fullName })
    }

    @Test
    fun `a repo is added with or without its check settings, and they are updated`() {
        assertTrue(repos.add("acme/plain"))
        val plain = repos.list().single { it.fullName == "acme/plain" }
        assertNull(plain.ciCheck)
        assertEquals(emptyList(), plain.ciPaths)
        assertNull(repos.ciCheck("acme/plain"), "a row with no ci_check falls through to the property")

        assertTrue(repos.add("acme/checked", "build", listOf("src/", "lib/")))
        val checked = repos.list().single { it.fullName == "acme/checked" }
        assertEquals("build" to listOf("src/", "lib/"), checked.ciCheck to checked.ciPaths)
        assertEquals(CiCheck("build", listOf("src/", "lib/")), repos.ciCheck("Acme/Checked"), "looked up ignoring case")

        assertTrue(repos.update("acme/plain", "site", emptyList(), true))
        assertEquals(CiCheck("site", emptyList()), repos.ciCheck("acme/plain"))
        assertTrue(repos.update("acme/checked", null, emptyList(), true))
        assertNull(repos.ciCheck("acme/checked"))
        assertFalse(repos.update("acme/nowhere", "build", emptyList(), true))
        assertNull(repos.ciCheck("acme/nowhere"))

        repos.remove("acme/plain"); repos.remove("acme/checked")
    }

    @Test
    fun `security reviews are on unless a row turns them off, and a repo with no row has them`() {
        assertTrue(repos.add("acme/reviewed"))
        assertTrue(repos.add("acme/unreviewed", securityReview = false))
        assertTrue(repos.list().single { it.fullName == "acme/reviewed" }.securityReview, "on by default")
        assertFalse(repos.securityReview("Acme/Unreviewed"), "looked up ignoring case")
        assertTrue(repos.securityReview("acme/nowhere"), "no row: on, as before the setting")

        assertTrue(repos.update("acme/reviewed", null, emptyList(), false))
        assertFalse(repos.securityReview("acme/reviewed"))
        assertTrue(repos.update("acme/reviewed", null, emptyList(), true))
        assertTrue(repos.securityReview("acme/reviewed"))

        repos.remove("acme/reviewed"); repos.remove("acme/unreviewed")
    }

    @Test
    fun `the trusted list is engine-wide, removing a repo leaves it`() {
        repos.add("acme/gadgets")
        access.add("alice", "matt")
        assertTrue(repos.remove("acme/gadgets"))
        assertTrue(access.trusted("alice"))
        access.remove("alice", "test")
    }
}
