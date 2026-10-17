package com.codefrak.engine.sdk

import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevCalls
import com.codefrak.sdk.JevQuestion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The SDK's `Jev`, called as a plugin would, reaches the engine's decider with the repo in its context, and the call is logged in `engine.jev_call`. */
@SpringBootTest
class EngineJevTest(@Autowired val calls: JevCalls) {
    @Test
    fun `a decision goes through the decider with the use case, questions, state and threshold, and is logged with the repo`() {
        val decider = FakeJevDecider(calls)
        decider.choice = "retry" to 0.9
        val jev = EngineJevs(decider).forJob("acme/widgets", "pi-1")
        val questions = mapOf("remedy" to JevQuestion.choice("What now?", mapOf("retry" to null, "none" to null)))
        val decision = jev.decide("plugin-remedy", questions, mapOf("incident" to "i-1"), 0.8)

        val (useCase, context, state) = decider.asked.single()
        assertEquals("plugin-remedy", useCase)
        assertEquals("acme/widgets" to "pi-1", context.repo to context.processInstance)
        assertEquals(mapOf("incident" to "i-1"), state)
        assertEquals("choice" to "What now?", decider.questionSets.single().getValue("remedy").let { it.type to it.instructions })
        assertEquals("retry" to 0.9, decision.answers?.getValue("remedy")?.let { it.choice to it.confidence })
        val row = assertNotNull(calls.get(decision.callId), "the call is in engine.jev_call")
        assertEquals("acme/widgets" to "plugin-remedy", row.repo to row.useCase)
        assertEquals(0.8, row.threshold)
    }
}
