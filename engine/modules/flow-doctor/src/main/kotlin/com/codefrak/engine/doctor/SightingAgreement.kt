package com.codefrak.engine.doctor

import com.codefrak.engine.jev.JevAgreementRule
import com.codefrak.engine.jev.JevAnswer
import org.springframework.stereotype.Component

/** `doctor-sighting` for [com.codefrak.engine.jev.JevAgreement]: the pick against the bug the outcome shows was right ([SightingCheck.truth]). */
@Component
class SightingAgreement : JevAgreementRule {
    override val useCases = setOf(SightingCheck.USE_CASE)

    override fun verdict(useCase: String, answers: Map<String, JevAnswer>, outcome: String): Boolean? {
        val pick = JevAgreementRule.pick(answers) ?: return null
        return SightingCheck.truth(outcome)?.let { it == pick }
    }
}
