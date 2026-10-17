package com.codefrak.engine.mcp

import com.codefrak.engine.board.LiveParentRestarter
import com.codefrak.engine.repos.Access
import org.springframework.web.server.ResponseStatusException

/**
 * restart_live_parent (#2572): `POST /ticket/{owner}/{repo}/{n}/live-parent` in-process, [LiveParentRestarter] for a
 * trusted login; refused to a Smith and to a caller with no login.
 */
class RestartLiveParentTool(private val restarter: LiveParentRestarter, private val access: Access) {
    companion object {
        const val NAME = "restart_live_parent"
        private val ARGS = setOf("repo", "ticket")

        val TOOL = McpTool(NAME,
            "Restart the live parent of a split feature that fell back to one child at a time (what POST " +
                "/ticket/{owner}/{repo}/{n}/live-parent does): clears liveFallback, drops the parent's deferred row and " +
                "starts a new parent Smith run that watches the children still open; the children keep running. Refused " +
                "(nothing changed) unless the split's childMode is live, it has fallen back and it waits at child closed. " +
                "For a trusted login; never a Smith.",
            mapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "repo" to mapOf("type" to "string", "description" to "The split feature's repo, owner/name."),
                    "ticket" to mapOf("type" to "integer", "minimum" to 1, "description" to "The split feature's ticket (issue) number."),
                ),
                "required" to listOf("repo", "ticket"),
                "additionalProperties" to false,
            ))
    }

    fun call(args: Map<String, Any?>, caller: McpCaller): McpToolResult {
        val login = when (caller) {
            is McpCaller.Smith -> return McpToolResult.error("a Smith sandbox (run ${caller.caller.runId}) may not restart a live parent")
            is McpCaller.Unknown -> return McpToolResult.error("cannot tell whether the caller is a Smith sandbox: ${caller.why}")
            is McpCaller.Nobody -> caller.login?.takeIf { it.isNotBlank() }
                ?: return McpToolResult.error("$NAME runs only for a login; ${caller.address ?: "this caller"} sent none")
        }
        if (!access.trusted(login)) return McpToolResult.error("@$login is not a trusted login")
        val extra = args.keys - ARGS
        if (extra.isNotEmpty()) return McpToolResult.error("$NAME takes only \"repo\" and \"ticket\", not ${extra.joinToString { "\"$it\"" }}")
        val repo = args["repo"] as? String
        if (repo == null || !REPO_FULL.matches(repo)) return McpToolResult.error("not an owner/name repo: ${args["repo"]}")
        val ticket = (args["ticket"] as? Number)?.toLong()?.takeIf { it >= 1 && args["ticket"] !is Double && args["ticket"] !is Float }
            ?: return McpToolResult.error("not a ticket number: ${args["ticket"]} (a positive integer)")
        return try {
            val r = restarter.restart(repo, ticket, login)
            McpToolResult.of(linkedMapOf("ok" to true, "repo" to r.repo, "ticket" to r.issue, "instanceId" to r.instanceId, "by" to r.by))
        } catch (e: ResponseStatusException) {
            McpToolResult.error(e.reason ?: e.message, mapOf("repo" to repo, "ticket" to ticket, "status" to e.statusCode.value()))
        }
    }
}
