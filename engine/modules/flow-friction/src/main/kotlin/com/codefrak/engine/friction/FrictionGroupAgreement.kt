package com.codefrak.engine.friction

import com.codefrak.engine.jev.JevAgreementRule
import com.codefrak.engine.jev.JevAnswer
import org.springframework.stereotype.Component

/**
 * `friction-group` and `friction-two-things` for [com.codefrak.engine.jev.JevAgreement]: the pick against the group the
 * outcome shows was right ([FrictionGroupCheck.truth]); a two-things outcome on a group call judges its `two-things`
 * answer instead ([FrictionGroupCheck.twoThingsVerdict]).
 */
@Component
class FrictionGroupAgreement : JevAgreementRule {
    override val useCases = setOf(FrictionGroupCheck.USE_CASE, FrictionGroupCheck.TWO_THINGS_USE_CASE)

    override fun verdict(useCase: String, answers: Map<String, JevAnswer>, outcome: String): Boolean? {
        if (useCase == FrictionGroupCheck.TWO_THINGS_USE_CASE || outcome.startsWith(FrictionGroupCheck.TWO_THINGS_OUTCOME)) {
            return FrictionGroupCheck.twoThingsVerdict(answers, outcome)
        }
        // A friction-group call may carry the `two-things` noul beside its pick (#1112): the group outcome judges the pick alone.
        val pick = JevAgreementRule.pick(answers.filterKeys { it == FrictionGroupCheck.QUESTION }) ?: return null
        return FrictionGroupCheck.truth(outcome)?.let { it == pick }
    }
}
