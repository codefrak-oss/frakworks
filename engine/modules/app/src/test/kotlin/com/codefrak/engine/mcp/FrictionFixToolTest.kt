package com.codefrak.engine.mcp

import com.codefrak.engine.friction.FrictionFix
import com.codefrak.engine.friction.FrictionFixLinks
import com.codefrak.engine.friction.FrictionGroup
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.tier.SeededTiers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** link_friction_fix on the frakworks server: First mate's only, as the caller's login, through [FrictionFixLinks]. */
class FrictionFixToolTest {
    private class FakeLinks : FrictionFixLinks {
        val groups = mutableMapOf(6L to FrictionGroup(6, "a title", OffsetDateTime.parse("2026-10-01T00:00:00Z"), emptyList(), 0))
        val calls = mutableListOf<String>()
        override fun link(groupId: Long, repo: String, issue: Long, by: String): FrictionGroup? {
            calls += "link g$groupId $repo#$issue by $by"
            val g = groups[groupId] ?: return null
            return g.copy(fix = FrictionFix(groupId, repo, issue, FrictionFix.FILED)).also { groups[groupId] = it }
        }
        override fun clear(groupId: Long, by: String): FrictionGroup? {
            calls += "clear g$groupId by $by"
            val g = groups[groupId] ?: return null
            return g.copy(fix = null).also { groups[groupId] = it }
        }
        override fun linkIntake(groupId: Long?, noteId: String?, intake: String, by: String): FrictionGroup =
            throw UnsupportedOperationException("not link_friction_fix's")
    }

    private val links = FakeLinks()
    private val mcp = FrakworksMcp(FakeEngine(), SeededTiers.table(), FakeFrictionLog(), links)
    private val mate = McpCaller.Nobody("127.0.0.1", "someone")
    private val smith = McpCaller.Smith(SmithCaller("run-1", 7, "acme/widgets"))

    private fun body(r: McpToolResult): Map<*, *> = mcpJson.readValue(r.text, Map::class.java)
    private fun error(r: McpToolResult): String { assertEquals(true, r.isError, r.text); return body(r)["error"] as String }

    @Test
    fun `First mate links and clears a group's fix as its login`() {
        assertTrue(mcp.tools(mate).any { it.name == FrictionFixTool.NAME })
        val linked = body(mcp.call(FrictionFixTool.NAME, mapOf("group" to 6, "repo" to "acme/widgets", "issue" to 1234), mate))
        assertEquals("fix-filed", linked["state"])
        assertEquals(mapOf("repo" to "acme/widgets", "issue" to 1234, "state" to "fix-filed", "recurredAt" to null), linked["fix"])
        val cleared = body(mcp.call(FrictionFixTool.NAME, mapOf("group" to 6, "issue" to null), mate))
        assertEquals(listOf("open", null), listOf(cleared["state"], cleared["fix"]))
        assertEquals(listOf("link g6 acme/widgets#1234 by someone", "clear g6 by someone"), links.calls)
    }

    @Test
    fun `a Smith is refused, and bad arguments and a missing group are errors`() {
        assertTrue(mcp.tools(smith).none { it.name == FrictionFixTool.NAME })
        assertTrue(error(mcp.call(FrictionFixTool.NAME, mapOf("group" to 6, "repo" to "acme/widgets", "issue" to 1), smith)).startsWith("a Smith sandbox (run run-1) may not call link_friction_fix"))
        assertEquals("no friction group g9", error(mcp.call(FrictionFixTool.NAME, mapOf("group" to 9, "repo" to "acme/widgets", "issue" to 1), mate)))
        assertTrue(error(mcp.call(FrictionFixTool.NAME, mapOf("group" to 6, "repo" to "nope", "issue" to 1), mate)).startsWith("not an owner/name repo"))
        assertTrue(error(mcp.call(FrictionFixTool.NAME, mapOf("group" to 6, "repo" to "acme/widgets"), mate)).startsWith("issue is"))
        assertTrue(error(mcp.call(FrictionFixTool.NAME, mapOf("group" to 6, "repo" to "acme/widgets", "issue" to 1, "x" to 1), mate)).startsWith("link_friction_fix takes only"))
        assertTrue(error(mcp.call(FrictionFixTool.NAME, mapOf("group" to 6, "issue" to 1, "repo" to "acme/widgets"), McpCaller.Nobody("127.0.0.1"))).startsWith("no GitHub login to link as"))
        assertTrue(links.calls.size == 1, "only the g9 call reached the links: ${links.calls}")
    }
}
