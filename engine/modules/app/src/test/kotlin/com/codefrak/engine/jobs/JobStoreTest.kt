package com.codefrak.engine.jobs

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `engine.job` after V55 (engine/README.md, "Refactoring jobs"): the `owner` column, kind `refactor`, and `updateSignal`. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class JobStoreTest {
    @Autowired lateinit var store: JobStore
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var jdbc: JdbcClient

    private val json = jacksonObjectMapper()
    private val repo = "acme/jobstore"
    private val t0 = Instant.parse("2026-10-06T10:00:00Z")

    @BeforeEach fun setUp() { repos.remove(repo); repos.add(repo) }
    @AfterEach fun tearDown() { repos.remove(repo) }

    @Test
    fun `an insert is the user's unless told otherwise, and a system job reads back as system`() {
        val user = store.insert("mine", JobKind.PROMPT, repo, json.readTree("""{"prompt":"hi"}"""), Signal.Execute, "alice", t0)
        assertEquals(JobOwner.USER, user.owner)
        assertEquals("user", jdbc.sql("SELECT owner FROM engine.job WHERE id = :id").param("id", user.id).query(String::class.java).single())
        // A second later: `list` orders by created_at, then id, and two rows at the same instant would sort by their random ids.
        val system = store.insert("refactor $repo", JobKind.REFACTOR, repo, json.readTree("{}"), Signal.Every(604800), "alice", t0.plusSeconds(1), owner = JobOwner.SYSTEM)
        assertEquals(JobOwner.SYSTEM, system.owner); assertEquals(JobKind.REFACTOR, system.kind)
        assertEquals(listOf(JobOwner.USER, JobOwner.SYSTEM), store.list(repo).map { it.owner })
    }

    @Test
    fun `updateSignal rewrites the signal and the next run from it`() {
        val job = store.insert("refactor $repo", JobKind.REFACTOR, repo, json.readTree("{}"), Signal.Every(604800), "alice", t0, owner = JobOwner.SYSTEM)
        assertEquals(t0.plusSeconds(604800), job.nextRunAt!!.toInstant())
        store.updateSignal(job.id, Signal.Every(86400), t0.plusSeconds(10))
        val stored = store.get(job.id)!!
        assertEquals(Signal.Every(86400), stored.parsedSignal())
        assertEquals(t0.plusSeconds(86410), stored.nextRunAt!!.toInstant())
        store.updateSignal(job.id, Signal.Execute, t0)
        assertEquals(null, store.get(job.id)!!.nextRunAt, "a signal with no clock leaves nothing due")
    }

    @Test
    fun `the V55 checks accept kind refactor and refuse an unknown owner or kind`() {
        fun raw(kind: String, owner: String) = jdbc.sql("""INSERT INTO engine.job (id, name, kind, repo, config, signal, created_at, owner)
            VALUES (:id, 'raw', :k, :r, '{}', '{"kind":"execute"}', CURRENT_TIMESTAMP, :o)""")
            .param("id", JobStore.newId()).param("k", kind).param("r", repo).param("o", owner).update()
        assertEquals(1, raw("refactor", "system"))
        assertFailsWith<DataIntegrityViolationException> { raw("prompt", "robot") }
        assertFailsWith<DataIntegrityViolationException> { raw("cron", "user") }
        // A row from before V55 has no owner in its insert: the default is user.
        val legacy = JobStore.newId()
        jdbc.sql("""INSERT INTO engine.job (id, name, kind, repo, config, signal, created_at) VALUES (:id, 'old', 'prompt', :r, '{"prompt":"x"}', '{"kind":"execute"}', CURRENT_TIMESTAMP)""")
            .param("id", legacy).param("r", repo).update()
        assertEquals(JobOwner.USER, store.get(legacy)!!.owner)
    }
}
