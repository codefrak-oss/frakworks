package com.codefrak.engine.camunda

import com.codefrak.engine.jev.JevAgreementRule
import com.codefrak.engine.jev.JevAnswer
import org.springframework.stereotype.Component

/**
 * `incident-triage` for [com.codefrak.engine.jev.JevAgreement]: `retried without a Smith: retried` with no `tripped again`
 * agrees with `retry`, any other end of that retry does not; the Fix Smith's remedy agrees when it is the pick. A retry
 * without a Smith that did not trip again saved a run.
 */
@Component
class IncidentTriageAgreement : JevAgreementRule {
    override val useCases = setOf(IncidentTriage.USE_CASE)
    override val saves = true

    override fun verdict(useCase: String, answers: Map<String, JevAnswer>, outcome: String): Boolean? {
        val pick = JevAgreementRule.pick(answers) ?: return null
        return when {
            outcome.startsWith(RETRIED) -> pick == IncidentTriage.RETRY && saved(useCase, outcome)
            outcome.startsWith(FIX_REMEDY) -> outcome.removePrefix(FIX_REMEDY).substringBefore(',').trim().let { remedy ->
                if (pick == IncidentTriage.INVESTIGATE) null else remedy == pick
            }
            else -> null
        }
    }

    override fun saved(useCase: String, outcome: String): Boolean = outcome.startsWith("$RETRIED retried") && "tripped again" !in outcome

    private companion object {
        const val RETRIED = "retried without a Smith:"
        const val FIX_REMEDY = "the Fix Smith's remedy:"
    }
}
