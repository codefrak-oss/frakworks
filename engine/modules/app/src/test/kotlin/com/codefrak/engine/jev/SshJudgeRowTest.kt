package com.codefrak.engine.jev

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [SshJudge] against H2: each judgment is one `ssh-judge` jev_call row, its reasoning merged into the response. */
@SpringBootTest
class SshJudgeRowTest {
    @Autowired lateinit var calls: JevCalls
    @Autowired lateinit var jdbc: JdbcClient

    private val rubric = "## Allow\n- Read-only commands: ls, cat.\n## Never\n- rm -rf on /."
    private fun request() = JudgeRequest(rubric, "ls /etc", "look", "audit", emptyList(), listOf("/etc"), "nas", "run-1")

    @Test
    fun `an answered judgment writes one row with the reasoning, listed by use case`() {
        val fake = FakeJevDecider(calls)
        fake.answers["verdict"] = JevAnswer("choice", choice = "allow", probabilities = mapOf("allow" to 0.95), confidence = 0.95)
        fake.answers["passage"] = JevAnswer("choice", choice = "p1", probabilities = mapOf("p1" to 0.9), confidence = 0.9)
        val j = SshJudge(fake, calls, jdbc).judge(request(), "acme/ops")
        assertEquals("allow", j.verdict)
        val row = calls.get(j.callId)!!
        assertEquals(SshJudge.USE_CASE, row.useCase)
        assertEquals("acme/ops", row.repo)
        assertEquals("allow", row.action)
        val judge = jacksonObjectMapper().readValue<Map<String, Any?>>(row.response!!)["judge"] as Map<*, *>
        assertEquals(j.reasoning, judge["reasoning"])
        assertTrue(calls.list(JevFilter(useCase = SshJudge.USE_CASE), 100, 0).first.any { it.id == j.callId })
    }

    @Test
    fun `no answer still writes the row, as no-answer with the reason`() {
        val j = SshJudge(FakeJevDecider(calls), calls, jdbc).judge(request(), "acme/widgets")
        assertTrue(j.noAnswer)
        val row = calls.get(j.callId)!!
        assertEquals("no-answer", row.action)
        assertEquals("acme/widgets", row.repo)
        assertTrue(row.response!!.contains("\"noAnswer\":true"))
    }
}
