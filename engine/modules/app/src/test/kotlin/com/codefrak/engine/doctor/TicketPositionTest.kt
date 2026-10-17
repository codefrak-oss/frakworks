package com.codefrak.engine.doctor

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TicketPositions] at the post-deployment check's waits (bpmn/post-deploy-check.bpmn): the wait for the deploy (with
 * its 12 h safety poll), the wait on a bus frame and the wait for a time are scheduled; the deadline's question is the
 * user's. Each flow is placed at its wait with no step before it run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class TicketPositionTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var positions: TicketPositions

    private val placed = mutableListOf<String>()

    @AfterEach
    fun cleanUp() { placed.forEach { runCatching { runtime.deleteProcessInstance(it, "test done") } } }

    private fun at(activity: String, issue: Long): TicketPosition {
        val later = Date.from(Instant.now().plus(Duration.ofDays(1)))
        val id = runtime.createProcessInstanceByKey("post-deploy-check")
            // Process variables before the first instruction: the subprocess's deadline timer reads them.
            .setVariables(mapOf(Vars.REPO to "acme/tickets", Vars.ISSUE to issue, Vars.MERGE_SHA to "abc1234",
                Vars.CHECK_WAKE_AT to later, Vars.CHECK_DEADLINE_AT to later, Vars.CHECK_WAKE_EVENT to "needs"))
            .startBeforeActivity(activity)
            .execute(true, true).id
        placed += id
        return positions.of(id)
    }

    private fun leaf(position: TicketPosition) = position.leaves.single()

    @Test
    fun `the deploy wait, with its safety poll, is scheduled`() {
        val position = at("receive-deploy-reached", 801)
        assertEquals("receive-deploy-reached" to Wait.SCHEDULED, leaf(position).let { it.activityId to it.wait })
        assertTrue(position.waiting, "a scheduled wait is never stuck")
        // The poll is a timer on that wait, never a leaf of its own; should one ever be, it is scheduled too.
        assertTrue("post-deploy-check:event-safety-poll" in TicketPositions.SCHEDULED_WAITS)
    }

    @Test
    fun `the wait between check runs, for a time or for a bus frame, is scheduled`() {
        assertEquals("event-check-at" to Wait.SCHEDULED, leaf(at("event-check-at", 802)).let { it.activityId to it.wait })
        assertEquals("receive-check-event" to Wait.SCHEDULED, leaf(at("receive-check-event", 803)).let { it.activityId to it.wait })
    }

    @Test
    fun `the deadline's question waits on the user`() {
        val position = at("user-task-answer-deadline", 804)
        assertEquals("user-task-answer-deadline" to Wait.USER, leaf(position).let { it.activityId to it.wait })
        assertTrue(position.waiting)
    }
}
