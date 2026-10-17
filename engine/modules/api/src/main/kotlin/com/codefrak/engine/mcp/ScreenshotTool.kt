package com.codefrak.engine.mcp

import com.codefrak.engine.firstmate.DesktopChannel
import java.util.Base64

/**
 * screenshot_desktop (#100105, engine/docs/desktop-channel.md), on [FrakworksMcp] for First mate's role only, with a
 * login: asks the caller's login's desktop app, over its [DesktopChannel], for a PNG of its window or one pane, and
 * answers it as an image block. A Smith neither lists nor may call it. The image is handed to the model only: the
 * engine keeps nothing of it.
 */
class ScreenshotTool(private val channel: DesktopChannel?) {
    companion object {
        const val NAME = "screenshot_desktop"

        val TOOL = McpTool(NAME,
            "Take a screenshot of the user's desktop app, when its \"${DesktopChannel.SETTING}\" setting is on: the whole " +
                "window, or one pane (left: the Explorer, middle: the main work area, right: First mate). Answers a PNG " +
                "(JPEG when a PNG would be over 5 MiB); refused when the setting is off or no desktop is connected, and " +
                "after ${DesktopChannel.TIMEOUT.seconds} s without an answer.",
            obj("pane" to prop("string", "left, middle or right; omit for the whole window.", "enum" to DesktopChannel.PANES)))
    }

    fun call(args: Map<String, Any?>, caller: McpCaller.Nobody): McpToolResult {
        refuseOthers(NAME, args, listOf("pane"))?.let { return it }
        val pane = args["pane"]
        if (pane != null && pane !in DesktopChannel.PANES) return McpToolResult.error("pane is left, middle or right, or omitted for the whole window")
        val login = login(caller) ?: return noLogin("take a screenshot")
        val ch = channel ?: return McpToolResult.error("this engine has no desktop channel")
        return when (val c = ch.capture(login, pane as String?)) {
            is DesktopChannel.Capture.Refused -> McpToolResult.error(c.why)
            is DesktopChannel.Capture.Image -> McpToolResult.of(
                linkedMapOf("pane" to (pane ?: "window"), "mimeType" to c.mimeType, "bytes" to c.bytes.size))
                .copy(image = McpImage(Base64.getEncoder().encodeToString(c.bytes), c.mimeType))
        }
    }
}
