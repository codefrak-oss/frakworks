package com.codefrak.engine.intake

import com.codefrak.engine.doctor.DoctorBugLinks
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.friction.FrictionBugFilings
import com.codefrak.engine.friction.FrictionFixFilings
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Outside an Operaton command, [EngineEvents.afterCommit] runs at once, and [EngineEvents.afterCommitApart]'s
 * action still starts on a virtual thread the caller does not wait for (`EngineEvents.kt` line 246, 257): the
 * friction assertions below poll for it.
 */
class FiledTicketLinksTest {
    private val repo = "acme/tickets"
    private val intake = "intake-1"
    private val ticket = 42L
    private val body = "the body"

    private class FakeDoctorBugs(private val fail: Boolean = false) : DoctorBugLinks {
        val filed = CopyOnWriteArrayList<Pair<Long, Long>>()
        override fun filed(id: Long, issue: Long) {
            if (fail) error("doctor bug $id gone")
            filed += id to issue
        }
        override fun declineUnfiled(id: Long) {}
        override fun needsRepro(repo: String, issue: Long) = false
    }

    private class FakeFrictionBugs(private val fail: Boolean = false) : FrictionBugFilings {
        val filed = CopyOnWriteArrayList<Pair<Long, Long>>()
        override fun filed(groupId: Long, issue: Long) {
            if (fail) error("friction bug $groupId gone")
            filed += groupId to issue
        }
    }

    private class FakeFrictionFixes(private val failFiled: Boolean = false, private val failIntakeFiled: Boolean = false) : FrictionFixFilings {
        val filedCalls = CopyOnWriteArrayList<Triple<String, Long, String>>()
        val intakeFiledCalls = CopyOnWriteArrayList<Triple<String, String, Long>>()
        override fun filed(repo: String, issue: Long, body: String): List<Long> {
            if (failFiled) error("groups named by #$issue gone")
            filedCalls += Triple(repo, issue, body)
            return emptyList()
        }
        override fun intakeFiled(intake: String, repo: String, issue: Long): List<Long> {
            if (failIntakeFiled) error("groups pending on $intake gone")
            intakeFiledCalls += Triple(intake, repo, issue)
            return emptyList()
        }
    }

    /** Polls [condition] for up to a second: the friction links run on a virtual thread this test does not wait for. */
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 1_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) assertTrue(condition(), "timed out waiting")
            Thread.sleep(10)
        }
    }

    @Test
    fun `a doctor bug is marked filed`() {
        val doctorBugs = FakeDoctorBugs()
        val links = FiledTicketLinks(EngineEvents(jacksonObjectMapper()), doctorBugs, FakeFrictionBugs(), FakeFrictionFixes())
        links.link(7, null, intake, repo, ticket, body)
        assertEquals(listOf(7L to ticket), doctorBugs.filed)
    }

    @Test
    fun `a doctor bug failure is logged, not thrown`() {
        val links = FiledTicketLinks(EngineEvents(jacksonObjectMapper()), FakeDoctorBugs(fail = true), FakeFrictionBugs(), FakeFrictionFixes())
        links.link(7, null, intake, repo, ticket, body) // no throw
    }

    @Test
    fun `frictionBugs filed, frictionFixes filed and frictionFixes intakeFiled are each called once`() {
        val frictionBugs = FakeFrictionBugs()
        val frictionFixes = FakeFrictionFixes()
        val links = FiledTicketLinks(EngineEvents(jacksonObjectMapper()), FakeDoctorBugs(), frictionBugs, frictionFixes)
        links.link(null, 9, intake, repo, ticket, body)
        await { frictionBugs.filed.size == 1 && frictionFixes.filedCalls.size == 1 && frictionFixes.intakeFiledCalls.size == 1 }
        assertEquals(listOf(9L to ticket), frictionBugs.filed)
        assertEquals(listOf(Triple(repo, ticket, body)), frictionFixes.filedCalls)
        assertEquals(listOf(Triple(intake, repo, ticket)), frictionFixes.intakeFiledCalls)
    }

    @Test
    fun `a failure in one friction call does not stop the other two`() {
        val frictionBugs = FakeFrictionBugs(fail = true)
        val frictionFixes = FakeFrictionFixes()
        val links = FiledTicketLinks(EngineEvents(jacksonObjectMapper()), FakeDoctorBugs(), frictionBugs, frictionFixes)
        links.link(null, 9, intake, repo, ticket, body)
        await { frictionFixes.filedCalls.size == 1 && frictionFixes.intakeFiledCalls.size == 1 }
    }
}
