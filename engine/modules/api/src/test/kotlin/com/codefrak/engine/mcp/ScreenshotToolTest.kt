package com.codefrak.engine.mcp

import com.codefrak.engine.firstmate.DesktopChannel
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.friction.FrictionLog
import com.codefrak.engine.tier.TierTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

/** screenshot_desktop on the frakworks server: First mate's with a login, never a Smith's; a round trip with a stubbed desktop. */
class ScreenshotToolTest {
    /** Every member answers null: screenshot_desktop reaches only the [DesktopChannel] (a Smith's list asks smithRun, null here). */
    private inline fun <reified T> unused(): T = java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
        if (m.name == "toString") "unused" else null } as T

    /** A [TierTable] its init never ran for (it reads the database): only the ask tools read it. */
    private fun tierTable(): TierTable {
        val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (f.get(null) as sun.misc.Unsafe).allocateInstance(TierTable::class.java) as TierTable
    }
    private val channel = DesktopChannel(Duration.ofSeconds(5))
    private val mcp = FrakworksMcp(unused<FrakworksEngine>(), tierTable(), unused<FrictionLog>(), desktops = channel)
    private val mate = McpCaller.Nobody("127.0.0.1", "someone")
    private val smith = McpCaller.Smith(SmithCaller("run-1", 7, "acme/widgets"))

    private fun error(r: McpToolResult): String { assertTrue(r.isError, r.text); return mcpJson.readValue(r.text, Map::class.java)["error"] as String }

    @Test
    fun `a Smith neither lists nor may call it`() {
        assertFalse(mcp.tools(smith).any { it.name == ScreenshotTool.NAME })
        channel.connect("someone", true)
        val e = error(mcp.call(ScreenshotTool.NAME, emptyMap(), smith))
        assertTrue(e.contains("may not call screenshot_desktop"), e)
    }

    @Test
    fun `First mate lists it and is refused with no desktop or the setting off`() {
        assertTrue(mcp.tools(mate).any { it.name == ScreenshotTool.NAME })
        assertEquals(DesktopChannel.NO_DESKTOP, error(mcp.call(ScreenshotTool.NAME, emptyMap(), mate)))
        channel.connect("someone", false)
        assertEquals(DesktopChannel.SETTING_OFF, error(mcp.call(ScreenshotTool.NAME, emptyMap(), mate)))
        assertTrue(error(mcp.call(ScreenshotTool.NAME, mapOf("pane" to "top"), mate)).contains("left, middle or right"))
        assertTrue(error(mcp.call(ScreenshotTool.NAME, emptyMap(), McpCaller.Nobody("127.0.0.1", null))).contains("no GitHub login"))
    }

    @Test
    fun `a stubbed desktop answers the pane it was asked for as an image block`() {
        val d = channel.connect("Someone", true)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val desktop = Thread.ofVirtual().start {
            val frame = d.frames.poll(5, TimeUnit.SECONDS)!!
            assertEquals("left", frame["pane"])
            assertEquals(null, channel.answer("someone", frame["requestId"] as String, Base64.getEncoder().encodeToString(png), "image/png", null))
        }
        val r = mcp.call(ScreenshotTool.NAME, mapOf("pane" to "left"), mate)
        desktop.join()
        assertFalse(r.isError, r.text)
        assertNotNull(r.image)
        assertEquals("image/png", r.image!!.mimeType)
        assertTrue(Base64.getDecoder().decode(r.image!!.data).contentEquals(png))
    }
}
