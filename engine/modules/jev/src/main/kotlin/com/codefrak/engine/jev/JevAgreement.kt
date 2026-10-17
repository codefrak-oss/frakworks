package com.codefrak.engine.jev

import org.springframework.stereotype.Component

/**
 * Whether a call's recorded outcome bore its pick out, for `GET /jev/summary` (engine/README.md, "Jev decisions"),
 * read from the text each use case's writer already records, so rows written before the summary count too. Each use
 * case's reading is a [JevAgreementRule] its own package registers:
 *
 * - `acceptance`: `confirmed accepted` agrees with a `yes`, `not accepted` with a `no` (asked only on a feature
 *   parked at the user's acceptance before #583, and on rows written then);
 * - `ci-triage`: `the re-run passed` agrees with `infrastructure` or `flaky`, `the re-run failed too` with `code`;
 * - `incident-triage`: `retried without a Smith: retried` with no `tripped again` agrees with `retry`, any other
 *   end of that retry does not; the Fix Smith's remedy agrees when it is the pick;
 * - `intake-duplicate`: `cancelled at the duplicate question` agrees with a ticket, `not cancelled` with `none`;
 * - `doctor-sighting`: the pick against the bug the outcome shows was right (`SightingCheck.truth`): the one a lump was
 *   kept on or moved to, the one a filed bug was a duplicate of or moved to, `new` for a filed bug kept;
 * - `friction-group`: the pick against the group the outcome shows was right (`FrictionGroupCheck.truth`): the one a
 *   join was kept on, the one a founded group was merged into, `new` for a founded group kept or a note pulled out;
 *   a two-things outcome on it (`two things: kept`, `two things: cleared by @x`) judges its `two-things` answer instead;
 * - `friction-two-things`: `two things: kept` agrees with a `yes`, `cleared by` with a `no` (`FrictionGroupCheck.twoThingsVerdict`);
 * - `proposal-decision`: `verb as written` agrees unless it says `(jev picked …)`; the answer to a re-ask agrees when
 *   it read as the pick;
 * - `refine-pick`: the `started as` prefix against Jev's pick on each field (`RefinePickOutcome.agreed`).
 *
 * Anything else (the work Smith's free-text answer to a CI finding, say) has an outcome but no verdict: null.
 */
@Component
class JevAgreement(rules: List<JevAgreementRule>) {
    private val byUseCase: Map<String, JevAgreementRule> = rules.flatMap { rule -> rule.useCases.map { it to rule } }.toMap()

    fun verdict(useCase: String, answers: Map<String, JevAnswer>?, outcome: String?): Boolean? {
        if (outcome == null || answers == null) return null
        return byUseCase[useCase]?.verdict(useCase, answers, outcome)
    }

    /** A run Jev's pick saved: a CI re-run that then passed, or an incident retried without a Smith that did not trip again. */
    fun saved(useCase: String, outcome: String?): Boolean =
        outcome != null && (byUseCase[useCase]?.takeIf { it.saves }?.saved(useCase, outcome) ?: false)

    /** The use cases whose picks can save a run. */
    val saves: Set<String> = rules.filter { it.saves }.flatMap { it.useCases }.toSet()
}
