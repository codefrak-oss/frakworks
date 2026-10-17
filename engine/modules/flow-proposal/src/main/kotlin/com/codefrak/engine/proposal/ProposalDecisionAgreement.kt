package com.codefrak.engine.proposal

import com.codefrak.engine.jev.JevAgreementRule
import com.codefrak.engine.jev.JevAnswer
import org.springframework.stereotype.Component

/** `proposal-decision` for [com.codefrak.engine.jev.JevAgreement]: `verb as written` agrees unless it says `(jev picked …)`; the answer to a re-ask agrees when it read as the pick. */
@Component
class ProposalDecisionAgreement : JevAgreementRule {
    override val useCases = setOf("proposal-decision")

    override fun verdict(useCase: String, answers: Map<String, JevAnswer>, outcome: String): Boolean? {
        val pick = JevAgreementRule.pick(answers) ?: return null
        return when {
            outcome.startsWith("verb as written: ") -> "(jev picked " !in outcome
            outcome.startsWith(REASK_READ) -> outcome.removePrefix(REASK_READ).substringBefore(':').trim() ==
                DecisionVerb.WRITTEN.entries.firstOrNull { it.value == pick }?.key
            else -> null
        }
    }

    private companion object {
        const val REASK_READ = "the answer to the re-ask read as "
    }
}
