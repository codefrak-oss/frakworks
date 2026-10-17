package com.codefrak.engine.mcp

import com.codefrak.engine.intake.AnswerAttachments
import java.util.Base64

/**
 * list_attachments and read_attachment, on [FrakworksMcp] for a Smith only (#1730; engine/README.md, "Answer
 * attachments"): the files the user attached to an answer in the flow the Smith's run belongs to, its root flow by
 * [rootOf]. Neither takes a flow, so a Smith cannot read another flow's files. Text comes in pages as read_private_doc's
 * does, an image as an MCP image block. The content is for the run; `bots/smith.md` forbids quoting it into anything public.
 */
class AttachmentTools(private val store: AnswerAttachments?, private val rootOf: (String) -> String?) {
    companion object {
        const val LIST = "list_attachments"
        const val READ = "read_attachment"

        val LIST_TOOL = McpTool(LIST,
            "Read-only: the files the user attached to an answer to your flow's question (your brief's `attachments`): " +
                "each one's name, type and size. Read one with $READ. Never quote them into an issue, PR, commit or file.",
            obj())

        val READ_TOOL = McpTool(READ,
            "Read-only: one file the user attached to an answer in your flow, by the name $LIST gives. Text comes in " +
                "pages of ${FrakworksMcp.PR_CHAR_BUDGET} characters: when more remains, the page ends with a line naming " +
                "the `offset` to pass for the next page. An image comes whole. Use what it says; never quote it into anything public.",
            obj(
                "name" to prop("string", "The file's name, as $LIST gives it."),
                "offset" to prop("integer", "For text, the character position the returned page starts at (default 0).", "minimum" to 0),
                required = listOf("name"),
            ))

    }

    fun list(args: Map<String, Any?>, caller: McpCaller.Smith): McpToolResult {
        if (args.isNotEmpty()) return McpToolResult.error("$LIST takes no arguments, not ${quoted(args.keys.toList())}")
        val (flow, files) = flowOf(caller) ?: return noFlow(caller)
        val all = files.list(flow)
        if (all.isEmpty()) return McpToolResult("Your flow $flow has no attachments.")
        return McpToolResult("The attachments of your flow $flow:\n\n" +
            all.joinToString("\n") { "- ${it.name} (${it.mime}, ${it.size} bytes, by @${it.createdBy} at ${it.createdAt.toInstant()})" })
    }

    fun read(args: Map<String, Any?>, caller: McpCaller.Smith): McpToolResult {
        val extra = args.keys.filter { it != "name" && it != "offset" }
        if (extra.isNotEmpty()) return McpToolResult.error("$READ takes only \"name\" and \"offset\", not ${quoted(extra)}")
        val name = (args["name"] as? String)?.takeIf { AnswerAttachments.NAME.matches(it) }
            ?: return McpToolResult.error("not an attachment name: ${json(args["name"])} ($LIST gives them)")
        val offset = when (val o = args["offset"]) {
            null -> 0
            else -> wholeNumberOf(o)?.takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt()
                ?: return McpToolResult.error("not an offset: ${json(o)} (a non-negative integer)")
        }
        val (flow, files) = flowOf(caller) ?: return noFlow(caller)
        val (mime, bytes) = files.read(flow, name) ?: return McpToolResult.error("your flow has no attachment $name ($LIST gives its names)")
        if (AnswerAttachments.isImage(mime))
            return McpToolResult("The attachment $name ($mime, ${bytes.size} bytes; do not quote it into anything public):",
                image = McpImage(Base64.getEncoder().encodeToString(bytes), mime))
        val text = AnswerAttachments.textOf(bytes) ?: return McpToolResult.error("$name is not UTF-8 text")
        val page = FrakworksMcp.pageText(text, offset)
            ?: return McpToolResult.error("offset $offset is past the end: $name is ${text.length} characters long")
        return McpToolResult("The attachment $name (do not quote it into anything public):\n\n$page")
    }

    private fun flowOf(caller: McpCaller.Smith): Pair<String, AnswerAttachments>? {
        val files = store ?: return null
        return (rootOf(caller.caller.runId) ?: return null) to files
    }

    private fun noFlow(caller: McpCaller.Smith) = McpToolResult.error(
        if (store == null) "the engine has no attachment store" else "Smith run ${caller.caller.runId} is in no flow the engine knows")
}
