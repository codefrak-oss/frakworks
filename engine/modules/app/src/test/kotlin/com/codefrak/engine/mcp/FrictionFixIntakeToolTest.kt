package com.codefrak.engine.mcp

import com.codefrak.engine.friction.FixIntakeRefused
import com.codefrak.engine.friction.FrictionFixLinks
import com.codefrak.engine.friction.FrictionGroup
import com.codefrak.engine.friction.PendingFix
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.tier.SeededTiers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** link_friction_fix_intake on the frakworks server: a Smith's (as its run) and First mate's (as its login), through [FrictionFixLinks]. */
class FrictionFixIntakeToolTest {
    private class FakeLinks : FrictionFixLinks {
        val group = FrictionGroup(6, "a title", OffsetDateTime.parse("2026-10-01T00:00:00Z"), emptyList(), 0)
        val calls = mutableListOf<String>()
        override fun link(groupId: Long, repo: String, issue: Long, by: String): FrictionGroup? = null
        override fun clear(groupId: Long, by: String): FrictionGroup? = null
        override fun linkIntake(groupId: Long?, noteId: String?, intake: String, by: String): FrictionGroup {
            calls += "link ${groupId?.let { "g$it" } ?: "note $noteId"} $intake by $by"
            if (groupId != null && groupId != 6L) throw FixIntakeRefused("no friction group g$groupId", notFound = true)
            if (intake == "i-none") throw FixIntakeRefused("no intake i-none", notFound = true)
            return group.copy(pending = PendingFix(6, intake, "acme/widgets"))
        }
    }

    private val links = FakeLinks()
    private val mcp = FrakworksMcp(FakeEngine(), SeededTiers.table(), FakeFrictionLog(), links)
    private val mate = McpCaller.Nobody("127.0.0.1", "someone")
    private val smith = McpCaller.Smith(SmithCaller("run-1", 7, "acme/widgets"))

    private fun body(r: McpToolResult): Map<*, *> = mcpJson.readValue(r.text, Map::class.java)
    private fun error(r: McpToolResult): String { assertEquals(true, r.isError, r.text); return body(r)["error"] as String }

    @Test
    fun `a Smith and First mate link a group or a note's group to an intake as fix-pending`() {
        assertTrue(mcp.tools(smith).any { it.name == FrictionFixIntakeTool.NAME })
        assertTrue(mcp.tools(mate).any { it.name == FrictionFixIntakeTool.NAME })
        val bySmith = body(mcp.call(FrictionFixIntakeTool.NAME, mapOf("intake" to "i-1", "note" to "n-1"), smith))
        assertEquals("fix-pending", bySmith["state"])
        assertEquals(mapOf("intake" to "i-1", "repo" to "acme/widgets", "issue" to null, "state" to "fix-pending"), bySmith["fix"])
        mcp.call(FrictionFixIntakeTool.NAME, mapOf("intake" to "i-2", "group" to 6), mate)
        assertEquals(listOf("link note n-1 i-1 by smith run run-1", "link g6 i-2 by someone"), links.calls)
    }

    @Test
    fun `bad arguments, an unknown group or intake and no login are errors`() {
        assertEquals("no friction group g9", error(mcp.call(FrictionFixIntakeTool.NAME, mapOf("intake" to "i-1", "group" to 9), mate)))
        assertEquals("no intake i-none", error(mcp.call(FrictionFixIntakeTool.NAME, mapOf("intake" to "i-none", "group" to 6), smith)))
        assertTrue(error(mcp.call(FrictionFixIntakeTool.NAME, mapOf("intake" to "i-1"), mate)).startsWith("pass exactly one"))
        assertTrue(error(mcp.call(FrictionFixIntakeTool.NAME, mapOf("intake" to "i-1", "group" to 6, "note" to "n"), mate)).startsWith("pass exactly one"))
        assertTrue(error(mcp.call(FrictionFixIntakeTool.NAME, mapOf("group" to 6), mate)).startsWith("intake is"))
        assertTrue(error(mcp.call(FrictionFixIntakeTool.NAME, mapOf("intake" to "i-1", "group" to 6, "x" to 1), mate)).startsWith("link_friction_fix_intake takes only"))
        assertTrue(error(mcp.call(FrictionFixIntakeTool.NAME, mapOf("intake" to "i-1", "group" to 6), McpCaller.Nobody("127.0.0.1"))).startsWith("no GitHub login to link as"))
    }
}
