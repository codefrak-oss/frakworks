package com.codefrak.engine.intake

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** [IntakeQuestions.answerHint]: the one wording every delegate's posted comment shares. */
class IntakeQuestionsTest {
    @Test
    fun `answerHint names the decision URL, or the HITLP task when the server gave none`() {
        assertEquals("Decide it on https://hitlp.example/decide/t-1", IntakeQuestions.answerHint("https://hitlp.example/decide/t-1", "t-1"))
        assertEquals("HITLP task t-2 on the engine's HITLP server", IntakeQuestions.answerHint(null, "t-2"))
        assertEquals("HITLP task t-3 on the engine's HITLP server", IntakeQuestions.answerHint(" ", "t-3"))
    }
}
