package com.codefrak.engine.repos

import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/** Whether a commit is in production: `deployed` (it is the running commit or an ancestor of it) or `not_yet`. */
enum class DeployState(val wire: String) {
    DEPLOYED("deployed"), NOT_YET("not_yet");
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } }
}

/**
 * The `ops/1` contract an MCP server with role `ops` conforms to (#100008; engine/docs/feature.md, "The ops role"):
 * one tool, `is_deployed`, taking `sha` (a required string) and answering `{state: deployed|not_yet}`, `deployed`
 * meaning the sha is the commit running in production or an ancestor of it. [conformance] reads a `tools/list`
 * ([McpToolSpec]) and says what is missing; [isDeployed] calls the tool through [McpProbe] and reads the answer from
 * the structured content, or the first text content as JSON.
 */
@Component
class OpsContract(private val probe: McpProbe) {
    /** The report `test_repo_mcp_server` and `POST .../test` carry for an ops row, and what a refusal quotes. */
    data class Conformance(val version: String, val conforms: Boolean, val missing: List<String>)

    companion object {
        const val VERSION = "ops/1"
        const val TOOL = "is_deployed"
        const val MISSING_TOOL = "a tool $TOOL"
        const val MISSING_SHA = "$TOOL takes sha, a required string (inputSchema.properties.sha.type string, sha in inputSchema.required)"
        const val MISSING_ANSWER = "$TOOL names its answer, {state: deployed|not_yet}, in its outputSchema (properties.state.enum) or its description"

        /** What a server's tools conform to or miss: [Conformance.conforms] when nothing is missing. */
        fun conformance(tools: List<McpToolSpec>): Conformance {
            val tool = tools.firstOrNull { it.name == TOOL } ?: return Conformance(VERSION, false, listOf(MISSING_TOOL))
            val missing = mutableListOf<String>()
            val sha = ((tool.inputSchema?.get("properties") as? Map<*, *>)?.get("sha") as? Map<*, *>)?.get("type")
            val required = (tool.inputSchema?.get("required") as? List<*>).orEmpty()
            if (sha != "string" || "sha" !in required) missing += MISSING_SHA
            val states = (((tool.outputSchema?.get("properties") as? Map<*, *>)?.get("state") as? Map<*, *>)?.get("enum") as? List<*>).orEmpty()
            val named = states.containsAll(DeployState.entries.map { it.wire }) ||
                DeployState.entries.all { tool.description.orEmpty().contains(it.wire) }
            if (!named) missing += MISSING_ANSWER
            return Conformance(VERSION, missing.isEmpty(), missing)
        }

        /** The 400 a put, add or edit of a role row answers when the server does not conform. */
        fun refusal(c: Conformance) = "ops server does not conform to $VERSION: ${c.missing.joinToString("; ")}"

        private val JSON = jacksonObjectMapper()
    }

    /**
     * Whether [sha] is in production by the ops server [config]: `tools/call is_deployed {sha}`, `state` read from
     * `structuredContent`, else from the first `text` content parsed as JSON. Any other answer (an `isError` result, no
     * `state`, a value that is neither state) is an [IllegalStateException] naming the server.
     */
    fun isDeployed(config: McpServerConfig, sha: String): DeployState {
        val result = try { probe.call(config, TOOL, mapOf("sha" to sha)) }
        catch (e: Exception) { throw IllegalStateException("ops server ${config.name}: $TOOL failed: ${e.message ?: e.javaClass.simpleName}", e) }
        if (result["isError"] == true) throw IllegalStateException("ops server ${config.name}: $TOOL answered an error: ${firstText(result) ?: result}")
        val structured = result["structuredContent"] as? Map<*, *>
        val answer: Map<*, *> = structured ?: firstText(result)?.let { runCatching { JSON.readValue<Map<String, Any?>>(it) }.getOrNull() }
            ?: throw IllegalStateException("ops server ${config.name}: $TOOL answered no structured content and no JSON text: $result")
        return DeployState.of(answer["state"] as? String)
            ?: throw IllegalStateException("ops server ${config.name}: $TOOL answered state '${answer["state"]}', not deployed or not_yet")
    }

    private fun firstText(result: Map<*, *>): String? = (result["content"] as? List<*>).orEmpty()
        .mapNotNull { it as? Map<*, *> }.firstOrNull { it["type"] == "text" }?.get("text") as? String
}
