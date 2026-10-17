package com.codefrak.engine.jobs

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.github.JudgeTaught
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RubricConsolidation]: one system prompt job on each repo the judge was taught in, every 604800 s, registered once. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class RubricConsolidationJobTest {
    @Autowired lateinit var consolidation: RubricConsolidation
    @Autowired lateinit var registrar: JobRegistrar
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var events: ApplicationEventPublisher

    private val taught = "acme/rubric-taught"
    private val untaught = "acme/rubric-untaught"

    @BeforeEach fun setUp() { listOf(taught, untaught).forEach { repos.remove(it); repos.add(it) } }
    @AfterEach fun tearDown() { listOf(taught, untaught).forEach { repos.remove(it) } }

    private fun jobs(repo: String) = registrar.list(repo).filter { it.name == RubricConsolidation.NAME }

    @Test
    fun `a teach registers the weekly prompt job once on its repo, listed for the repo`() {
        events.publishEvent(JudgeTaught(taught))
        events.publishEvent(JudgeTaught(taught))
        assertEquals(jobs(taught).single().id, consolidation.register(taught)!!.id)
        val job = jobs(taught).single()
        assertEquals("prompt", job.kind)
        assertEquals("system", job.owner)
        assertEquals(604800L, job.signal["every"].asLong())
        assertTrue(job.config["prompt"].asString().contains("## Decisions"))
    }

    @Test
    fun `a repo never taught has no job, and a teach on no target repo registers nothing`() {
        events.publishEvent(JudgeTaught(taught))
        assertEquals(emptyList(), jobs(untaught))
        repos.remove(untaught)
        events.publishEvent(JudgeTaught(untaught))
        assertNull(consolidation.register(untaught))
    }
}
