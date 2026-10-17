package com.codefrak.engine.ci

import com.codefrak.engine.jev.JevAgreementRule
import com.codefrak.engine.jev.JevAnswer
import org.springframework.stereotype.Component

/** `ci-triage` for [com.codefrak.engine.jev.JevAgreement]: `the re-run passed` agrees with `infrastructure` or `flaky`, `the re-run failed too` with `code`; a re-run that passed saved a run. */
@Component
class CiTriageAgreement : JevAgreementRule {
    override val useCases = setOf(CiTriage.USE_CASE)
    override val saves = true

    override fun verdict(useCase: String, answers: Map<String, JevAnswer>, outcome: String): Boolean? {
        val pick = JevAgreementRule.pick(answers) ?: return null
        return when {
            outcome.startsWith(RERUN_PASSED) -> pick != CiTriage.CODE
            outcome.startsWith("the re-run failed too") -> pick == CiTriage.CODE
            else -> null
        }
    }

    override fun saved(useCase: String, outcome: String): Boolean = outcome.startsWith(RERUN_PASSED)

    private companion object {
        const val RERUN_PASSED = "the re-run passed"
    }
}
