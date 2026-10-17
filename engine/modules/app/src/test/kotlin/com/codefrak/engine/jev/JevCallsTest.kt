package com.codefrak.engine.jev

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `engine.jev_call` against H2 with Flyway's migrations applied, the same scripts Postgres runs. */
@SpringBootTest
class JevCallsTest(@Autowired val calls: JevCalls) {
    @Test
    fun `a call round-trips every column, and its action and outcome are filled later`() {
        val id = calls.record("acceptance", JevContext("pi-1", "acme/tickets", 42), "jev-1", "jev-1.13.0",
            """{"model":"jev-1.13.0"}""", """{"answers":{}}""", null, 123, 0.9)
        val call = assertNotNull(calls.get(id))
        assertEquals(listOf("acceptance", "pi-1", "acme/tickets", 42L, "jev-1", "jev-1.13.0"),
            listOf(call.useCase, call.processInstance, call.repo, call.issue, call.account, call.model))
        assertEquals(Triple("""{"model":"jev-1.13.0"}""", """{"answers":{}}""", null), Triple(call.request, call.response, call.error))
        assertEquals(123L to 0.9, call.latencyMs to call.threshold)
        assertNotNull(call.calledAt)
        assertNull(call.action); assertNull(call.outcome); assertNull(call.outcomeAt)

        assertTrue(calls.setAction(id, "re-asked"))
        assertTrue(calls.recordOutcome(id, "confirmed accepted: @lundmatt1: accepted"))
        val later = calls.get(id)!!
        assertEquals("re-asked" to "confirmed accepted: @lundmatt1: accepted", later.action to later.outcome)
        assertNotNull(later.outcomeAt)
    }

    @Test
    fun `a failed call has no response, and the outcome fills only a call that exists`() {
        val id = calls.record("acceptance", JevContext(null, null, null), null, "jev-1.13.0", "{}", null, "no usable jev account", null, null)
        val call = calls.get(id)!!
        assertEquals(Triple(null, "no usable jev account", null), Triple(call.response, call.error, call.latencyMs))
        assertNull(call.threshold); assertNull(call.issue)
        assertFalse(calls.recordOutcome("no-such-call", "x"))
        assertFalse(calls.setAction("no-such-call", "x"))
        assertNull(calls.get("no-such-call"))
    }

    @Test
    fun `a 150-character action is stored cut to the column's 100 characters`() {
        val id = calls.record("acceptance", JevContext(null, null, null), null, "jev-1.13.0", "{}", null, null, null, null)
        assertTrue(calls.setAction(id, "x".repeat(150)))
        assertEquals("x".repeat(100), calls.get(id)!!.action)
    }
}
