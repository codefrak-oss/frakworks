package com.codefrak.engine.mcp

import com.codefrak.engine.friction.FrictionFilter
import com.codefrak.engine.friction.FrictionFix
import com.codefrak.engine.friction.FrictionFixReads
import com.codefrak.engine.friction.FrictionLog
import com.codefrak.engine.friction.PendingFix
import com.codefrak.engine.friction.FrictionNote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** `engine.friction_note` in memory: what was inserted, listed newest first, and each run's root by a fixed map. */
private class FakeFrictionLog : FrictionLog {
    val rows = mutableListOf<FrictionNote>()
    val roots = mutableMapOf("run-1" to "root-1")
    var insertFails: Exception? = null
    var asked: FrictionFilter? = null
    override fun insert(note: FrictionNote) { insertFails?.let { throw it }; rows += note }
    override fun list(filter: FrictionFilter): List<FrictionNote> {
        asked = filter
        return rows.filter {
            (filter.repo == null || it.repo == filter.repo) && (filter.issue == null || it.issue == filter.issue) &&
                (filter.kind == null || it.kind == filter.kind) && (filter.source == null || it.source == filter.source) &&
                (filter.since == null || !it.notedAt.isBefore(filter.since))
        }.sortedWith(compareByDescending<FrictionNote> { it.notedAt }.thenByDescending { it.id }).take(filter.limit)
    }
    override fun rootOf(runId: String) = roots[runId]
}

/** read_friction_notes (#876), on frakworks-engine-diagnostics; its listing over HTTP is in [DiagnosticsMcpServerTest]. */
class FrictionNotesToolTest {
    private val log = FakeFrictionLog()
    private val confidences = mutableMapOf<String, Double>()
    private val asked = mutableListOf<Collection<String>>()
    private val tool = FrictionNotesTool(log, confidence = { ids -> asked += ids; confidences.filterKeys { it in ids } })

    private fun note(id: String, at: String, repo: String = "acme/widgets", issue: Long = 7, kind: String = "docs", source: String = FrictionNote.AS_YOU_GO) =
        FrictionNote(id, repo, issue, "run-$id", "root-$id", OffsetDateTime.parse(at), source, kind, "summary $id", null, null)

    private fun body(r: McpToolResult): Map<*, *> = mcpJson.readValue(r.text, Map::class.java)
    private fun ids(r: McpToolResult) = (body(r)["notes"] as List<*>).map { (it as Map<*, *>)["id"] }
    private fun error(r: McpToolResult): String { assertEquals(true, r.isError, r.text); return body(r)["error"] as String }

    @Test
    fun `with no arguments it returns count and every note newest first, with every column`() {
        log.rows += note("a", "2026-10-01T10:00:00Z")
        log.rows += note("b", "2026-10-03T10:00:00Z", source = FrictionNote.RETRO).copy(signature = "k", groupId = 3, groupedBy = "jev", jevCallId = "c1")
        log.rows += note("c", "2026-10-02T10:00:00Z").copy(groupId = 3, groupedBy = "jev", jevCallId = "c1")
        log.rows += note("d", "2026-10-01T09:00:00Z").copy(groupId = 4, groupedBy = "user", groupedLogin = "someone", jevCallId = "c2")
        confidences["c1"] = 0.91
        val r = tool.call(emptyMap())
        assertEquals(4, body(r)["count"])
        assertEquals(listOf("b", "c", "a", "d"), ids(r))
        val rows = (body(r)["notes"] as List<*>).map { it as Map<*, *> }
        val first = rows.first()
        assertEquals(listOf("id", "repo", "issue", "runId", "processInstanceId", "notedAt", "source", "kind", "summary", "evidence", "severity", "login",
            "signature", "groupId", "groupedBy", "jevCallId", "groupedLogin", "jevConfidence", "twoThings", "twoThingsCallId", "groupState", "groupFix"), first.keys.toList())
        assertEquals(null, first["login"])
        assertEquals(listOf("k", 3, "jev", "c1", null, 0.91), listOf(first["signature"], first["groupId"], first["groupedBy"], first["jevCallId"], first["groupedLogin"], first["jevConfidence"]))
        assertEquals(0.91, rows[1]["jevConfidence"], "the same call, the same confidence")
        assertEquals(listOf(null, null), listOf(rows[2]["jevConfidence"], rows[2]["groupedLogin"]), "no call: no confidence")
        assertEquals(listOf("someone", null), listOf(rows[3]["groupedLogin"], rows[3]["jevConfidence"]), "a call with no readable answer: null")
        assertEquals(listOf(setOf("c1", "c2")), asked.map { it.toSet() }, "the confidences are read once per listing, over the distinct calls")
        assertEquals(listOf("acme/widgets", 7, "run-b", "root-b", "2026-10-03T10:00:00Z", "retro", "docs", "summary b"),
            listOf(first["repo"], first["issue"], first["runId"], first["processInstanceId"], first["notedAt"], first["source"], first["kind"], first["summary"]))
        assertEquals(100, log.asked!!.limit)
    }

    @Test
    fun `a grouped note carries its group's state and fix, an ungrouped one null`() {
        val fixed = FrictionFix(3, "acme/widgets", 42, FrictionFix.FIXED, OffsetDateTime.parse("2026-10-02T00:00:00Z"), OffsetDateTime.parse("2026-10-03T10:00:00Z"))
        val withFix = FrictionNotesTool(log, fixes = { ids -> mapOf(3L to fixed).filterKeys { it in ids } })
        log.rows += note("a", "2026-10-01T10:00:00Z")
        log.rows += note("b", "2026-10-03T10:00:00Z").copy(groupId = 3, groupedBy = "jev")
        log.rows += note("c", "2026-10-02T10:00:00Z").copy(groupId = 4, groupedBy = "new")
        val rows = (body(withFix.call(emptyMap()))["notes"] as List<*>).map { it as Map<*, *> }.associateBy { it["id"] }
        assertEquals("recurred", rows["b"]!!["groupState"])
        assertEquals(mapOf("repo" to "acme/widgets", "issue" to 42, "state" to "fixed", "recurredAt" to "2026-10-03T10:00:00Z"), rows["b"]!!["groupFix"])
        assertEquals(listOf("open", null), listOf(rows["c"]!!["groupState"], rows["c"]!!["groupFix"]))
        assertEquals(listOf(null, null), listOf(rows["a"]!!["groupState"], rows["a"]!!["groupFix"]))
    }

    @Test
    fun `a group pending on an intake carries fix-pending and the intake as its fix`() {
        val pending = FrictionNotesTool(log, fixes = object : FrictionFixReads {
            override fun fixesOf(groupIds: Collection<Long>) = emptyMap<Long, FrictionFix>()
            override fun pendingOf(groupIds: Collection<Long>) = mapOf(5L to PendingFix(5, "i-1", "acme/widgets")).filterKeys { it in groupIds }
        })
        log.rows += note("p", "2026-10-03T10:00:00Z").copy(groupId = 5, groupedBy = "jev")
        val row = (body(pending.call(emptyMap()))["notes"] as List<*>).single() as Map<*, *>
        assertEquals("fix-pending", row["groupState"])
        assertEquals(mapOf("intake" to "i-1", "repo" to "acme/widgets", "issue" to null, "state" to "fix-pending"), row["groupFix"])
    }

    @Test
    fun `each filter keeps only the matching notes`() {
        log.rows += note("a", "2026-10-01T10:00:00Z")
        log.rows += note("b", "2026-10-02T10:00:00Z", repo = "acme/other")
        log.rows += note("c", "2026-10-03T10:00:00Z", issue = 9, kind = "bug")
        log.rows += note("d", "2026-10-04T10:00:00Z", source = FrictionNote.RETRO)
        assertEquals(listOf("b"), ids(tool.call(mapOf("repo" to "acme/other"))))
        assertEquals(listOf("c"), ids(tool.call(mapOf("ticket" to 9))))
        assertEquals(listOf("c"), ids(tool.call(mapOf("kind" to "bug"))))
        assertEquals(listOf("d"), ids(tool.call(mapOf("source" to "retro"))))
        assertEquals(listOf("d", "c"), ids(tool.call(mapOf("since" to "2026-10-03"))))
        assertEquals(listOf("d", "c"), ids(tool.call(mapOf("since" to "2026-10-03T10:00:00Z"))))
        assertEquals(listOf("d"), ids(tool.call(mapOf("limit" to 1))))
        assertEquals(listOf("a"), ids(tool.call(mapOf("repo" to "acme/widgets", "ticket" to 7, "kind" to "docs", "source" to "as-you-go", "since" to "2026-09-30"))))
    }

    @Test
    fun `source first-mate keeps First mate's notes, each with its login, and every other row carries a null login`() {
        log.rows += note("a", "2026-10-01T10:00:00Z")
        log.rows += note("b", "2026-10-02T10:00:00Z", source = FrictionNote.RETRO)
        log.rows += FrictionNote("m", "acme/widgets", null, null, null, OffsetDateTime.parse("2026-10-03T10:00:00Z"),
            FrictionNote.FIRST_MATE, "tooling", "no tool reads CI logs", null, null, "someone")
        val r = tool.call(mapOf("source" to "first-mate"))
        val row = (body(r)["notes"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("m", "first-mate", "someone", "acme/widgets", null, null, null),
            listOf(row["id"], row["source"], row["login"], row["repo"], row["issue"], row["runId"], row["processInstanceId"]))
        val all = body(tool.call(emptyMap()))["notes"] as List<*>
        assertEquals(listOf("someone", null, null), all.map { (it as Map<*, *>)["login"] })
        assertTrue("first-mate" in ((FrictionNotesTool.TOOL.inputSchema["properties"] as Map<*, *>)["source"] as Map<*, *>)["enum"] as List<*>)
    }

    @Test
    fun `a bad kind, source, since, ticket, repo or limit, or an unknown argument, is a tool error`() {
        assertTrue(error(tool.call(mapOf("kind" to "rant"))).startsWith("kind is one of"))
        assertTrue(error(tool.call(mapOf("source" to "later"))).startsWith("source is one of"))
        assertEquals("since is an ISO-8601 instant or date, not \"last week\"", error(tool.call(mapOf("since" to "last week"))))
        assertTrue(error(tool.call(mapOf("since" to 5))).startsWith("since is an ISO-8601"))
        assertTrue(error(tool.call(mapOf("ticket" to 0))).startsWith("not a ticket number"))
        assertTrue(error(tool.call(mapOf("repo" to "nope"))).startsWith("not an owner/name repo"))
        assertTrue(error(tool.call(mapOf("limit" to 501))).startsWith("limit is an integer from 1 to 500"))
        assertEquals("read_friction_notes takes only \"repo\", \"ticket\", \"kind\", \"source\", \"since\", \"limit\", not \"issue\"",
            error(tool.call(mapOf("issue" to 7))))
        assertEquals(null, log.asked)
    }
}
