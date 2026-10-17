package com.codefrak.engine.jev

import org.springframework.stereotype.Component

/**
 * One use case's reading of its recorded outcomes for [JevAgreement]: a Spring bean the package that owns the use
 * case registers (engine/README.md, "Jev decisions", "Engine modules"), so engine-jev knows none of them.
 */
interface JevAgreementRule {
    /** The use cases this rule judges. */
    val useCases: Set<String>

    /** Whether a call's pick can save a run (the two triage use cases), so `GET /jev/summary` counts [saved]. */
    val saves: Boolean get() = false

    /** Whether `outcome` bore the call's `answers` out; null when it cannot say. */
    fun verdict(useCase: String, answers: Map<String, JevAnswer>, outcome: String): Boolean?

    /** Whether Jev's pick saved a run; asked only of a rule that [saves]. */
    fun saved(useCase: String, outcome: String): Boolean = false

    companion object {
        /** The call's one pick, as `GET /jev/calls` shows it ([JevAnswers.summary]); null when it has none. */
        fun pick(answers: Map<String, JevAnswer>): String? = JevAnswers.summary(JevAnswers.picks(answers)).first
    }
}

/** `acceptance`: `confirmed accepted` agrees with a `yes`, `not accepted` with a `no`. No package asks it any more (#583), so it lives here. */
@Component
class AcceptanceAgreement : JevAgreementRule {
    override val useCases = setOf("acceptance")

    override fun verdict(useCase: String, answers: Map<String, JevAnswer>, outcome: String): Boolean? {
        val pick = JevAgreementRule.pick(answers) ?: return null
        return when {
            outcome.startsWith("confirmed accepted") -> pick == "yes"
            outcome.startsWith("not accepted") -> pick == "no"
            else -> null
        }
    }
}
