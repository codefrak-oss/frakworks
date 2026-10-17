package com.codefrak.engine.jev

import com.codefrak.engine.hitlp.HitlpRequest
import com.codefrak.engine.hitlp.HitlpRequests
import com.codefrak.engine.hitlp.JudgeEscalation
import com.codefrak.engine.hitlp.SshVerdictsController
import com.codefrak.engine.hitlp.StubHitlpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /jev/ssh-verdicts` ([SshVerdictsController]) against H2 and [StubHitlpServer]: ssh-judge rows newest first,
 * joined through `engine.hitlp_request.judge_call_id` to the escalation's task as `tasks/get` answers it, its
 * recommendation from `asserted.recommendation` or, on an older request, `context.data.judge`.
 */
@SpringBootTest
class SshVerdictsTest {
    companion object {
        val stub = StubHitlpServer()
        @JvmStatic @DynamicPropertySource fun hitlp(registry: DynamicPropertyRegistry) = stub.register(registry)
        @JvmStatic @AfterAll fun stop() = stub.stop()
    }

    @Autowired lateinit var requests: HitlpRequests
    @Autowired lateinit var calls: JevCalls
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var controller: SshVerdictsController

    private val rubric = "## Allow\n- Read-only commands: ls, cat.\n## Escalate\n- Restarts."
    private val repo = "verdicts-${UUID.randomUUID()}/ops"

    private fun judge(command: String, verdict: String): Judgment {
        val fake = FakeJevDecider(calls)
        fake.answers["verdict"] = JevAnswer("choice", choice = verdict, probabilities = mapOf(verdict to 0.95), confidence = 0.95)
        fake.answers["passage"] = JevAnswer("choice", choice = "p2", probabilities = mapOf("p2" to 0.9), confidence = 0.9)
        val judgment = SshJudge(fake, calls, jdbc).judge(JudgeRequest(rubric, command, "why", "goal", emptyList(), emptyList(), "nas", "run-1"), repo)
        // The fake records its state as toString(); the real decider writes JSON with the state under "state".
        jdbc.sql("UPDATE engine.jev_call SET request = :r WHERE id = :id")
            .param("r", com.codefrak.engine.engineJson.writeValueAsString(mapOf("state" to mapOf("host" to "nas", "command" to command))))
            .param("id", judgment.callId).update()
        return judgment
    }

    /** Files [callId]'s escalation as `/mcp/judge` does and has the stub decide it [outcome]; [asserted] picks the request's shape. */
    private fun decide(callId: String, outcome: String, asserted: Boolean = true): String {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val taskId = "t-${UUID.randomUUID()}"
        requests.save(HitlpRequest(taskId, "judge:$callId", JudgeEscalation.processInstance(callId), JudgeEscalation.loginOwner("ops"),
            JudgeEscalation.KIND, 10, now, now.plusHours(1), judgeCallId = callId))
        val judge = if (asserted) mapOf("callId" to callId, "rubricPath" to "ops/ssh.md", "repo" to repo)
            else mapOf("callId" to callId, "verdict" to "escalate", "confidence" to 0.4, "rubricPath" to "ops/ssh.md", "repo" to repo)
        val request = mapOf("action" to "run", "payload" to emptyMap<String, Any?>(), "context" to mapOf("data" to mapOf("judge" to judge))) +
            (if (asserted) mapOf("asserted" to mapOf("recommendation" to mapOf("verdict" to "escalate", "confidence" to 0.95))) else emptyMap())
        stub.decide(taskId, mapOf("requestId" to taskId, "primitive" to "approve", "outcome" to outcome, "decidedAt" to "2026-10-11T12:00:00Z",
            "decidedBy" to mapOf("type" to "human", "id" to "lundmatt1"), "request" to request))
        return taskId
    }

    @Test
    fun `lists the calls newest first with the fields and the decision where one followed`() {
        val first = judge("systemctl restart nginx", "escalate")
        jdbc.sql("UPDATE engine.jev_call SET called_at = :at WHERE id = :id")
            .param("at", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)).param("id", first.callId).update()
        val second = judge("ls /etc", "allow")
        decide(first.callId, "approved")
        @Suppress("UNCHECKED_CAST")
        val rows = controller.list(repo, 100)["verdicts"] as List<Map<String, Any?>>
        assertEquals(listOf(second.callId, first.callId), rows.map { it["id"] })
        val escalated = rows[1]
        assertEquals("nas", escalated["host"])
        assertEquals("systemctl restart nginx", escalated["command"])
        assertEquals("escalate", escalated["verdict"])
        assertEquals(0.95, escalated["confidence"])
        assertEquals("Restarts.", escalated["cited_passage"])
        assertTrue((escalated["reasoning"] as String).isNotBlank())
        val decision = escalated["decision"] as Map<*, *>
        assertEquals("approved", decision["outcome"])
        assertEquals("completed", decision["status"])
        assertEquals(mapOf("verdict" to "escalate", "confidence" to 0.95), decision["recommendation"])
        assertNull(rows[0]["decision"])
        assertTrue(stub.calls("tasks/get").isNotEmpty(), "the decision is read from the HITLP server")
    }

    @Test
    fun `an older request's recommendation is read from context data judge`() {
        val call = judge("reboot", "escalate")
        val taskId = decide(call.callId, "rejected", asserted = false)
        @Suppress("UNCHECKED_CAST")
        val decision = ((controller.list(repo, 100)["verdicts"] as List<Map<String, Any?>>).single()["decision"]) as Map<*, *>
        assertEquals(taskId, decision["task_id"])
        assertEquals("rejected", decision["outcome"])
        assertEquals(mapOf("verdict" to "escalate", "confidence" to 0.4), decision["recommendation"])
    }
}
