package com.codefrak.engine.jev

import com.codefrak.engine.camunda.IncidentTriageAgreement
import com.codefrak.engine.ci.CiTriageAgreement
import com.codefrak.engine.doctor.SightingAgreement
import com.codefrak.engine.friction.FrictionGroupAgreement
import com.codefrak.engine.intake.DuplicateAgreement
import com.codefrak.engine.intake.RefinePickAgreement
import com.codefrak.engine.proposal.ProposalDecisionAgreement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each use case's outcome, as its writer records it today, judged against the pick (JevAgreement, over the rules each package registers). */
class JevAgreementTest {
    private val agreement = JevAgreement(listOf(
        AcceptanceAgreement(), CiTriageAgreement(), IncidentTriageAgreement(), DuplicateAgreement(), RefinePickAgreement(),
        SightingAgreement(), FrictionGroupAgreement(), ProposalDecisionAgreement(),
    ))
    private fun choice(q: String, c: String) = mapOf(q to JevAnswer("choice", choice = c, probabilities = mapOf(c to 0.9), confidence = 0.9))
    private fun noul(v: Double) = mapOf("accepts" to JevAnswer("noul", noul = v))
    private fun verdict(useCase: String, answers: Map<String, JevAnswer>?, outcome: String?) = agreement.verdict(useCase, answers, outcome)

    @Test
    fun `the two triage rules save runs, and a use case no rule names has no verdict`() {
        assertEquals(setOf("ci-triage", "incident-triage"), agreement.saves)
        assertNull(verdict("nobody-asks-this", noul(0.95), "confirmed accepted: @u: yes"))
        assertNull(JevAgreement(emptyList()).verdict("acceptance", noul(0.95), "confirmed accepted: @u: yes"))
    }

    @Test
    fun `no outcome or no answer is no verdict`() {
        assertNull(verdict("acceptance", noul(0.95), null))
        assertNull(verdict("acceptance", null, "confirmed accepted: @u: yes"))
    }

    @Test
    fun `doctor-sighting agrees when the outcome names the pick as the right bug, or new for a filed bug kept`() {
        val lumped = choice("sighting", "#412")
        assertEquals(true, verdict("doctor-sighting", lumped, "lumped #412: kept"))
        assertEquals(false, verdict("doctor-sighting", lumped, "lumped #412: moved to #430 by @u"))
        assertEquals(true, verdict("doctor-sighting", choice("sighting", "new"), "new: filed #430, kept"))
        assertEquals(false, verdict("doctor-sighting", choice("sighting", "new"), "new: filed #430, closed as duplicate of #412 by @u"))
        assertEquals(true, verdict("doctor-sighting", lumped, "new: filed #430, closed as duplicate of #412 by @u"), "a pick below the floor that was right")
        assertNull(verdict("doctor-sighting", lumped, "lumped #412: moved to no bug by @u"))
        assertNull(verdict("doctor-sighting", lumped, "lumped #412: moved to acme/other#9 by @u"))
    }

    @Test
    fun `friction-two-things agrees when a kept mark was a yes or a cleared one a no, on a lone call or a friction-group one`() {
        val yes = mapOf("two-things" to JevAnswer("noul", noul = 0.86))
        val no = mapOf("two-things" to JevAnswer("noul", noul = 0.3))
        assertEquals(true, verdict("friction-two-things", yes, "two things: kept"))
        assertEquals(false, verdict("friction-two-things", yes, "two things: cleared by @u"))
        assertEquals(true, verdict("friction-two-things", no, "two things: cleared by @u"))
        assertNull(verdict("friction-two-things", yes, "something else"))
        val both = choice("group", "g12") + yes
        assertEquals(false, verdict("friction-group", both, "two things: cleared by @u"), "the two-things outcome on a group call judges the noul")
        assertEquals(true, verdict("friction-group", both, "joined g12: kept"), "the group outcome judges the pick")
        assertNull(verdict("friction-group", choice("group", "g12"), "two things: kept"), "no noul on the call")
    }

    @Test
    fun `friction-group agrees when the outcome names the pick as the right group, or new for a founded group kept or a note pulled out`() {
        val joined = choice("group", "g12")
        assertEquals(true, verdict("friction-group", joined, "joined g12: kept"))
        assertEquals(false, verdict("friction-group", joined, "joined g12: pulled out to g40 by @u"))
        assertEquals(true, verdict("friction-group", choice("group", "new"), "new g31: kept"))
        assertEquals(false, verdict("friction-group", choice("group", "new"), "new g31: merged into g12 by @u"))
        assertEquals(true, verdict("friction-group", joined, "new g31: merged into g12 by @u"), "a pick below the floor that was right")
        assertNull(verdict("friction-group", joined, "something else"))
    }

    @Test
    fun `acceptance agrees when the re-ask confirmed what jev read`() {
        assertEquals(true, verdict("acceptance", noul(0.95), "confirmed accepted: @lundmatt1: yes"))
        assertEquals(false, verdict("acceptance", noul(0.95), "not accepted: @lundmatt1: no, the tests are missing"))
        assertNull(verdict("acceptance", noul(0.95), "something else"))
    }

    @Test
    fun `ci-triage agrees when a re-run it asked for passed, and a free-text diagnosis is not judged`() {
        assertEquals(true, verdict("ci-triage", choice("cause", "flaky"), "the re-run passed (success): not the code"))
        assertEquals(false, verdict("ci-triage", choice("cause", "infrastructure"), "the re-run failed too (failure)"))
        assertNull(verdict("ci-triage", choice("cause", "code"), "the work Smith's answer to ci-1-1: addressed: fixed it"))
        assertTrue(agreement.saved("ci-triage", "the re-run passed (success): not the code"))
        assertFalse(agreement.saved("ci-triage", "the re-run failed too (failure)"))
    }

    @Test
    fun `incident-triage agrees with a retry that held, and a retry that tripped again saved nothing`() {
        val retry = choice("remedy", "retry")
        assertEquals(true, verdict("incident-triage", retry, "retried without a Smith: retried"))
        assertTrue(agreement.saved("incident-triage", "retried without a Smith: retried"))
        val tripped = "retried without a Smith: retried; tripped again (fixFailed): incident 1 at task-x"
        assertEquals(false, verdict("incident-triage", retry, tripped))
        assertFalse(agreement.saved("incident-triage", tripped))
        assertEquals(false, verdict("incident-triage", retry, "retried without a Smith: failed (the incident is gone)"))
        assertFalse(agreement.saved("incident-triage", "retried without a Smith: failed (the incident is gone)"))
        assertEquals(true, verdict("incident-triage", choice("remedy", "unfixable"), "the Fix Smith's remedy: unfixable, failed (the smith found it unfixable)"))
        assertEquals(false, verdict("incident-triage", choice("remedy", "unfixable"), "the Fix Smith's remedy: retry, retried"))
        assertNull(verdict("incident-triage", choice("remedy", "investigate"), "the Fix Smith's remedy: retry, retried"))
        assertFalse(agreement.saved("incident-triage", "the Fix Smith's remedy: retry, retried"), "a Smith ran")
        assertFalse(agreement.saved("acceptance", "retried without a Smith: retried"))
    }

    @Test
    fun `intake-duplicate agrees when the user cancelled at a match, not when they went on`() {
        assertEquals(true, verdict("intake-duplicate", choice("duplicate", "#7"), "cancelled at the duplicate question by @u: same work"))
        assertEquals(false, verdict("intake-duplicate", choice("duplicate", "#7"), "not cancelled; @u answered: it is different"))
    }

    @Test
    fun `proposal-decision agrees with a verb written as picked, and with a re-ask read as the pick`() {
        assertEquals(true, verdict("proposal-decision", choice("decision", "accept"), "verb as written: accept"))
        assertEquals(false, verdict("proposal-decision", choice("decision", "shelve"), "verb as written: reject (jev picked shelve)"))
        assertEquals(true, verdict("proposal-decision", choice("decision", "accept and build"), "the answer to the re-ask read as build: @u: accept and build"))
        assertEquals(false, verdict("proposal-decision", choice("decision", "revise"), "the answer to the re-ask read as accept: @u: accept"))
    }

    @Test
    fun `refine-pick agrees when the flow started as jev picked on every field it answered`() {
        val answers = mapOf("kind" to JevAnswer("choice", choice = "feature", confidence = 0.95),
            "featureMode" to JevAnswer("choice", choice = "confirm", confidence = 0.6),
            "tier" to JevAnswer("score", score = 5.0, probabilities = mapOf("5" to 0.9), confidence = 0.9))
        assertEquals(true, verdict("refine-pick", answers, "started as feature confirm tier 6"))
        assertEquals(true, verdict("refine-pick", answers, "started as feature confirm tier 6; jev: agreed"))
        assertEquals(false, verdict("refine-pick", answers, "started as feature auto tier 6"), "a mode below the floor still counts")
        assertEquals(false, verdict("refine-pick", answers, "started as chore tier 6; jev: differs (picked kind feature), relabelled from feature confirm tier 6"))
        assertNull(verdict("refine-pick", answers, "filed and waiting"))
    }
}
