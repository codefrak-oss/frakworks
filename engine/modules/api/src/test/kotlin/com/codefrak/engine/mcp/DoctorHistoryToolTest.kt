package com.codefrak.engine.mcp

import com.codefrak.engine.doctor.DoctorRun
import com.codefrak.engine.doctor.DoctorSighting
import com.codefrak.engine.doctor.DoctorTicket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

internal class FakeDoctor : DoctorReads {
    var doctorTicketRows = listOf<DoctorTicket>()
    var doctorRunRows = mapOf<Pair<String, Long>, List<DoctorRun>>()
    override fun tickets() = doctorTicketRows
    override fun runs(repo: String, ticket: Long) = doctorRunRows[repo to ticket].orEmpty()
}

/** read_doctor_history (#655), on frakworks-engine-diagnostics since #658; its listing over HTTP is in [DiagnosticsMcpServerTest]. */
class DoctorHistoryToolTest {
    private val doctor = FakeDoctor()
    private val tool = DoctorHistoryTool(doctor)

    private fun call(name: String, args: Map<String, Any?>): McpToolResult { assertEquals(DoctorHistoryTool.NAME, name); return tool.call(args) }
    private fun body(result: McpToolResult): Map<*, *> = mcpJson.readValue(result.text, Map::class.java)
    private fun error(result: McpToolResult): String {
        assertEquals(true, result.isError, result.text)
        return body(result)["error"] as String
    }

    @Test
    fun `the tool keeps its schema`() {
        val t = DoctorHistoryTool.TOOL
        assertTrue(t.description.isNotBlank())
        val props = t.inputSchema["properties"] as Map<*, *>
        assertEquals(setOf("ticket", "repo"), props.keys)
        assertEquals("integer", (props["ticket"] as Map<*, *>)["type"])
        assertEquals(1, (props["ticket"] as Map<*, *>)["minimum"])
        assertEquals("string", (props["repo"] as Map<*, *>)["type"])
        assertNull(t.inputSchema["required"])
    }

    private val t0 = OffsetDateTime.parse("2026-09-27T10:00:00Z")
    private fun run(id: String, repo: String, issue: Long, at: OffsetDateTime, outcome: String) =
        DoctorRun(id, repo, issue, "root", listOf("chore:task-open-pr@none"), t0, at, null, null, null, null, outcome, null)

    @Test
    fun `read_doctor_history with nothing run is an empty list`() {
        val r = body(call("read_doctor_history", emptyMap()))
        assertEquals(0, r["count"])
        assertEquals(emptyList<Any>(), r["tickets"])
        assertEquals(0, (body(call("read_doctor_history", mapOf("ticket" to 5, "repo" to "acme/tickets")))["runs"] as List<*>).size)
    }

    @Test
    fun `read_doctor_history lists the tickets with runs, as the engine orders them`() {
        doctor.doctorTicketRows = listOf(DoctorTicket("acme/widgets", 9, 1, t0.plusHours(1), "running"),
            DoctorTicket("acme/tickets", 4, 3, t0, "applied"))
        val rows = body(call("read_doctor_history", emptyMap()))["tickets"] as List<*>
        assertEquals(2, rows.size)
        val first = rows[0] as Map<*, *>
        assertEquals(listOf("repo", "issue", "runs", "lastStartedAt", "lastOutcome"), first.keys.toList())
        assertEquals("acme/widgets", first["repo"])
        assertEquals(9, first["issue"])
        assertEquals("2026-09-27T11:00:00Z", first["lastStartedAt"])
        assertEquals(3, (rows[1] as Map<*, *>)["runs"])
    }

    @Test
    fun `read_doctor_history for a ticket reads its runs in the repo given, and refuses a ticket with no repo or a bad one`() {
        doctor.doctorRunRows = mapOf(("acme/tickets" to 4L) to listOf(run("b", "acme/tickets", 4, t0.plusMinutes(5), "running"),
            run("a", "acme/tickets", 4, t0, "applied")), ("acme/widgets" to 4L) to listOf(run("w", "acme/widgets", 4, t0, "failed")))
        assertEquals("\"ticket\" needs \"repo\" (owner/name): the target repo the ticket is in", error(call("read_doctor_history", mapOf("ticket" to 4))))
        val r = body(call("read_doctor_history", mapOf("ticket" to 4, "repo" to "acme/tickets")))
        assertEquals("acme/tickets", r["repo"])
        val runs = r["runs"] as List<*>
        assertEquals(listOf("b", "a"), runs.map { (it as Map<*, *>)["id"] })
        assertEquals(listOf("id", "position", "stuckSince", "startedAt", "endedAt", "diagnosis", "remedy", "target", "outcome", "detail", "sighting"),
            (runs[0] as Map<*, *>).keys.toList())
        assertNull((runs[0] as Map<*, *>)["sighting"])
        assertEquals(listOf("w"), (body(call("read_doctor_history", mapOf("ticket" to 4, "repo" to "acme/widgets")))["runs"] as List<*>)
            .map { (it as Map<*, *>)["id"] })
        assertTrue(error(call("read_doctor_history", mapOf("ticket" to "4"))).startsWith("not a ticket number"))
        assertTrue(error(call("read_doctor_history", mapOf("ticket" to 0))).startsWith("not a ticket number"))
        assertTrue(error(call("read_doctor_history", mapOf("repo" to "acme/widgets"))).contains("only with"))
        assertEquals("read_doctor_history takes only \"ticket\", \"repo\", not \"kind\"", error(call("read_doctor_history", mapOf("kind" to "x"))))
    }

    @Test
    fun `read_doctor_history gives a run its sighting`() {
        doctor.doctorRunRows = mapOf(("acme/tickets" to 6L) to listOf(
            run("s", "acme/tickets", 6, t0, "none").copy(sighting = DoctorSighting(7, "unfixable", "engine", null, 2, 3, "ab12"))))
        val runs = body(call("read_doctor_history", mapOf("ticket" to 6, "repo" to "acme/tickets")))["runs"] as List<*>
        val sighting = (runs[0] as Map<*, *>)["sighting"] as Map<*, *>
        assertEquals(listOf("id", "kind", "where", "bug", "tickets", "runs", "signature"), sighting.keys.toList())
        assertEquals(7, sighting["id"])
        assertEquals("unfixable", sighting["kind"])
        assertEquals("engine", sighting["where"])
        assertNull(sighting["bug"])
        assertEquals(2, sighting["tickets"])
        assertEquals(3, sighting["runs"])
        assertEquals("ab12", sighting["signature"])
    }
}
