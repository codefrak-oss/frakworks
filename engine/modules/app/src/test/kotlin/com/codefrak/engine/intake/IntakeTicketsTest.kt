package com.codefrak.engine.intake

import com.codefrak.engine.smith.IntakeRef
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
class IntakeTicketsTest {
    @Autowired lateinit var links: IntakeTickets
    @Autowired lateinit var backfill: IntakeTicketBackfill
    @Autowired lateinit var refs: IntakeRefs
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var jdbc: JdbcClient

    private val repo = "acme/links"
    private fun url(n: Long) = "https://github.com/$repo/issues/$n"

    @BeforeEach
    fun clean() {
        jdbc.sql("DELETE FROM engine.intake_ticket").update()
    }

    @Test
    fun `opened then filed writes one row, and a retried open keeps it`() {
        links.opened("i-1", repo, 10, url(10))
        assertNull(links.get("i-1")!!.ticket)
        links.opened("i-1", repo, 10, url(10))
        links.filed("i-1", 11)
        assertEquals(IntakeTicket("i-1", repo, 10, url(10), 11), links.get("i-1"))
        assertEquals("i-1", links.byIssue("ACME/links", 10)!!.intake)
        assertEquals("i-1", links.byIssueUrl(url(10))!!.intake)
        assertEquals(setOf(11L), links.filedIn(repo).keys)
        assertEquals(emptyMap(), links.filedIn("acme/other"))
    }

    @Test
    fun `the backfill copies history's links once, and a second run changes nothing`() {
        val filed = runtime.createProcessInstanceByKey("intake").startBeforeActivity("end-intake-closed")
            .setVariables(mapOf("repo" to repo, "issue" to 20L, "issueUrl" to url(20), "ticket" to 21L)).execute()
        val refining = runtime.createProcessInstanceByKey("intake").startBeforeActivity("end-intake-closed")
            .setVariables(mapOf("repo" to repo, "issue" to 22L, "issueUrl" to url(22))).execute()
        // No issue yet: nothing to link.
        val bare = runtime.createProcessInstanceByKey("intake").startBeforeActivity("end-intake-closed")
            .setVariables(mapOf("repo" to repo)).execute()
        // Already in the table: the row stays as written.
        val known = runtime.createProcessInstanceByKey("intake").startBeforeActivity("end-intake-closed")
            .setVariables(mapOf("repo" to repo, "issue" to 24L, "issueUrl" to url(24), "ticket" to 99L)).execute()
        links.opened(known.id, repo, 24, url(24)); links.filed(known.id, 25)

        backfill.run()
        assertEquals(IntakeTicket(filed.id, repo, 20, url(20), 21), links.get(filed.id))
        assertEquals(IntakeTicket(refining.id, repo, 22, url(22), null), links.get(refining.id))
        assertNull(links.get(bare.id))
        assertEquals(25L, links.get(known.id)!!.ticket)
        val before = jdbc.sql("SELECT intake, full_name, issue, issue_url, ticket, created_at, filed_at FROM engine.intake_ticket ORDER BY intake")
            .query().listOfRows()

        assertEquals(0, backfill.run())
        assertEquals(before, jdbc.sql("SELECT intake, full_name, issue, issue_url, ticket, created_at, filed_at FROM engine.intake_ticket ORDER BY intake")
            .query().listOfRows())

        val ref = assertNotNull(refs.resolve(repo, "#20"))
        assertEquals(IntakeRef.FILED, ref.state); assertEquals(21L, ref.ticket); assertEquals(filed.id, ref.intake)
        assertEquals(mapOf(21L to IntakeView(filed.id, 20, url(20)), 25L to IntakeView(known.id, 24, url(24))), refs.filedBy(repo))
    }
}
