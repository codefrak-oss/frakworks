package com.codefrak.engine.intake

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [AskWaits.askBlock]: a choice block's options as HITLP options, a text block with none, no block as null. */
class AskWaitsTest {
    @Test
    fun `a choice block gives its options as value and label`() {
        val question = "Which one?\n\n```ask\nkind: choice\nquestion: Which one?\noptions:\n  - id: a\n    label: Blue widgets\n  - id: b\n    label: Red widgets\n```"
        assertEquals(mapOf("kind" to "choice", "options" to listOf(mapOf("value" to "a", "label" to "Blue widgets"), mapOf("value" to "b", "label" to "Red widgets"))),
            AskWaits.askBlock(question))
    }

    @Test
    fun `a text block has no options, and a question without a block has no block`() {
        assertEquals(mapOf("kind" to "text", "options" to emptyList<Any>()), AskWaits.askBlock("Why?\n\n```ask\nkind: text\nquestion: Why?\n```"))
        assertNull(AskWaits.askBlock("Which widget?"))
    }
}
