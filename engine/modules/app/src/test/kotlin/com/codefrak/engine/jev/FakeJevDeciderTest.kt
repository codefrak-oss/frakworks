package com.codefrak.engine.jev

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [FakeJevDecider] stores its answers as the JSON [HttpJevDecider] would, so [JevAnswers.parse] reads them back (#1155). */
@SpringBootTest
class FakeJevDeciderTest(@Autowired val calls: JevCalls) {
    private val jev = FakeJevDecider(calls)
    private val context = JevContext("pi-1", "acme/tickets", 1)
    private val questions = mapOf("kind" to JevQuestion.choice("Which kind?", mapOf("bug" to null, "chore" to null)))

    @Test
    fun `a faked choice answer reads back with its choice and confidence`() {
        jev.choice = "chore" to 0.72
        val decision = jev.decide("intake", context, "{}", questions, null)
        val row = calls.get(decision.callId)!!
        val answers = JevAnswers.parse(row.response)
        assertEquals("chore", answers?.get("kind")?.choice)
        assertEquals(0.72, answers?.get("kind")?.confidence)
        assertNull(row.error)
    }

    @Test
    fun `a faked noul answer reads back with its noul value`() {
        val nouls = mapOf("accepts" to JevQuestion.noul("Does it accept?"))
        jev.noul = 0.86
        val decision = jev.decide("intake", context, "{}", nouls, null)
        val row = calls.get(decision.callId)!!
        val answers = JevAnswers.parse(row.response)
        assertEquals(0.86, answers?.get("accepts")?.noul)
        assertNull(row.error)
    }

    @Test
    fun `a faked call with no answer records a null response and the fake's error, and parses to null`() {
        val decision = jev.decide("intake", context, "{}", questions, null)
        val row = calls.get(decision.callId)!!
        assertNull(row.response)
        assertEquals("no answer (fake)", row.error)
        assertNull(JevAnswers.parse(row.response))
    }
}
