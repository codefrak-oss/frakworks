package com.codefrak.engine.mcp

import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.intake.AnswerAttachments
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.tier.SeededTiers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * list_attachments and read_attachment (#1730; engine/README.md, "Answer attachments") against the real store: the
 * owning flow's Smith reads, another flow's Smith and a non-Smith caller are refused.
 */
@SpringBootTest
@Import(ReadFakes::class)
class AttachmentToolsTest {
    @Autowired lateinit var store: AnswerAttachments
    @Autowired lateinit var jdbc: JdbcClient

    private val engine = FakeEngine()
    private val mcp by lazy { FrakworksMcp(engine, SeededTiers.table(), FakeFrictionLog(), attachments = store) }
    private val owner = McpCaller.Smith(SmithCaller("run-own", 7, "acme/widgets"))
    private val other = McpCaller.Smith(SmithCaller("run-other", 8, "acme/widgets"))
    private val flow = "0a1b2c3d-0000-0000-0000-0000000000a1"

    @BeforeEach
    fun seed() {
        jdbc.sql("DELETE FROM engine.answer_attachment WHERE flow = :f").param("f", flow).update()
        engine.runRoots["run-own"] = flow
        engine.runRoots["run-other"] = "0a1b2c3d-0000-0000-0000-0000000000b2"
        store.save(flow, "spec.md", "text/markdown", ("# Spec\n" + "x".repeat(FrakworksMcp.PR_CHAR_BUDGET + 10)).toByteArray(), "lundmatt1")
        store.save(flow, "shot.png", "image/png", byteArrayOf(1, 2, 3), "lundmatt1")
    }

    private fun body(r: McpToolResult): Map<*, *> = mcpJson.readValue(r.text, Map::class.java)
    private fun error(r: McpToolResult): String { assertEquals(true, r.isError, r.text); return body(r)["error"] as String }

    @Test
    fun `the owning run lists the flow's attachments by name, type and size`() {
        val text = mcp.call("list_attachments", emptyMap(), owner).text
        assertTrue("- spec.md (text/markdown, ${7 + FrakworksMcp.PR_CHAR_BUDGET + 10} bytes" in text, text)
        assertTrue("- shot.png (image/png, 3 bytes" in text, text)
    }

    @Test
    fun `the owning run reads text in pages and an image as an image block`() {
        val first = mcp.call("read_attachment", mapOf("name" to "spec.md"), owner)
        assertEquals(false, first.isError, first.text)
        assertTrue("# Spec" in first.text && "pass offset: ${FrakworksMcp.PR_CHAR_BUDGET} for the next page" in first.text, first.text.takeLast(200))
        val second = mcp.call("read_attachment", mapOf("name" to "spec.md", "offset" to FrakworksMcp.PR_CHAR_BUDGET), owner)
        assertTrue("# Spec" !in second.text && "xxxxxxxxxx" in second.text, second.text)
        assertNull(first.image)
        val image = assertNotNull(mcp.call("read_attachment", mapOf("name" to "shot.png"), owner).image)
        assertEquals(McpImage("AQID", "image/png"), image)
    }

    @Test
    fun `another flow's run sees none of them and cannot read one`() {
        assertTrue("has no attachments" in mcp.call("list_attachments", emptyMap(), other).text)
        assertEquals("your flow has no attachment spec.md (list_attachments gives its names)",
            error(mcp.call("read_attachment", mapOf("name" to "spec.md"), other)))
        val unknown = McpCaller.Smith(SmithCaller("run-gone", 9, "acme/widgets"))
        assertEquals("Smith run run-gone is in no flow the engine knows", error(mcp.call("read_attachment", mapOf("name" to "spec.md"), unknown)))
    }

    @Test
    fun `a non-Smith caller cannot list or read attachments`() {
        val mate = McpCaller.Nobody("127.0.0.1", "someone")
        assertTrue(mcp.tools(mate).none { it.name == "read_attachment" || it.name == "list_attachments" })
        assertEquals("unknown tool: \"read_attachment\"", error(mcp.call("read_attachment", mapOf("name" to "spec.md"), mate)))
        assertEquals("unknown tool: \"list_attachments\"", error(mcp.call("list_attachments", emptyMap(), mate)))
        val unplaced = McpCaller.Unknown("172.18.0.9", "docker is down")
        assertTrue("cannot tell whether the caller is a Smith sandbox" in error(mcp.call("read_attachment", mapOf("name" to "spec.md"), unplaced)))
    }
}
