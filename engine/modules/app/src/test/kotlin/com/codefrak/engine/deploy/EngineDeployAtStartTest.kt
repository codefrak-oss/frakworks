package com.codefrak.engine.deploy

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** The engine's own deploy event at start (#100008): one row per build commit, however many starts. */
@SpringBootTest
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class EngineDeployAtStartTest {
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var deploys: DeployEvents

    private val repo = "acme/engine-build"
    private val a = "a".repeat(40); private val b = "b".repeat(40)

    @BeforeEach fun setUp() { repos.remove(repo); repos.add(repo); jdbc.sql("DELETE FROM engine.deploy_event WHERE repo = :r").param("r", repo).update() }
    @AfterEach fun tearDown() { repos.remove(repo); deploys.clock = Clock.systemUTC() }

    @Test
    fun `two starts on one commit post one row, a start on the next commit a second, with the clock's time`() {
        val t1 = Instant.parse("2026-10-13T08:00:00Z"); val t2 = Instant.parse("2026-10-13T09:00:00Z"); val t3 = Instant.parse("2026-10-14T08:00:00Z")
        deploys.clock = Clock.fixed(t1, ZoneOffset.UTC)
        EngineDeployAtStart(deploys, repo, a).started()
        deploys.clock = Clock.fixed(t2, ZoneOffset.UTC)
        EngineDeployAtStart(deploys, repo, a).started()
        assertEquals(listOf(a to t1), deploys.list(repo, 10).map { it.sha to it.at }, "a restart of the same image adds no row")
        deploys.clock = Clock.fixed(t3, ZoneOffset.UTC)
        EngineDeployAtStart(deploys, repo, b.uppercase()).started()
        assertEquals(listOf(b to t3, a to t1), deploys.list(repo, 10).map { it.sha to it.at })
        assertEquals(listOf(EngineDeployAtStart.SOURCE, EngineDeployAtStart.SOURCE), deploys.list(repo, 10).map { it.source })

        EngineDeployAtStart(deploys, "", a).started()
        EngineDeployAtStart(deploys, repo, "").started()
        EngineDeployAtStart(deploys, "acme/not-a-target", a).started()
        assertEquals(2, deploys.list(repo, 10).size, "no build repo, no commit or a non-target repo posts nothing")
    }
}
