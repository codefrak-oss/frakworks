package com.codefrak.engine.intake

import com.codefrak.engine.jev.JevAgreementRule
import com.codefrak.engine.jev.JevAnswer
import org.springframework.stereotype.Component

/** `intake-duplicate` for [com.codefrak.engine.jev.JevAgreement]: `cancelled at the duplicate question` agrees with a ticket, `not cancelled` with `none`. */
@Component
class DuplicateAgreement : JevAgreementRule {
    override val useCases = setOf(DuplicateCheck.USE_CASE)

    override fun verdict(useCase: String, answers: Map<String, JevAnswer>, outcome: String): Boolean? {
        val pick = JevAgreementRule.pick(answers) ?: return null
        return when {
            outcome.startsWith("cancelled at the duplicate question") -> pick != DuplicateCheck.NONE
            outcome.startsWith("not cancelled") -> pick == DuplicateCheck.NONE
            else -> null
        }
    }
}

/** `refine-pick` for [com.codefrak.engine.jev.JevAgreement]: the `started as` prefix against Jev's pick on each field ([RefinePickOutcome.agreed]). */
@Component
class RefinePickAgreement : JevAgreementRule {
    override val useCases = setOf(RefinePicker.USE_CASE)

    override fun verdict(useCase: String, answers: Map<String, JevAnswer>, outcome: String): Boolean? = RefinePickOutcome.agreed(answers, outcome)
}
