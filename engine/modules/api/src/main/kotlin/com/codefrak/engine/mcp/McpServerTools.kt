package com.codefrak.engine.mcp

import com.codefrak.engine.repos.McpProbe
import com.codefrak.engine.repos.OpsContract
import com.codefrak.engine.repos.RepoMcpServerController
import com.codefrak.engine.repos.RepoMcpServers
import org.springframework.web.server.ResponseStatusException

/**
 * First mate's tools on [FrakworksMcp] for a target repo's MCP servers (#1801, #2449; engine/README.md, "First mate's MCP
 * servers"): read, add, edit, remove and test the servers a First mate for that repo, a Smith run of it, or both get
 * beside the engine's three; each row's `audience` says which.
 * Each takes a required `repo`, refused naming the target repos without one; a Smith's call is refused naming its run.
 * A read never answers a secret value; the test answers `{reached, tools, error}`, and for a stdio server that the
 * desktop tests it (the Configure First Mate modal's Test button). A row with `role: "ops"` (#100008) is probed on add
 * and edit and refused unless it conforms to `ops/1` ([OpsContract]); the test reports its contract.
 */
class McpServerTools(private val servers: RepoMcpServers?, private val probe: McpProbe?, private val engine: FrakworksEngine) {
    companion object {
        const val READ = "read_repo_mcp_servers"
        const val ADD = "add_repo_mcp_server"
        const val EDIT = "edit_repo_mcp_server"
        const val REMOVE = "remove_repo_mcp_server"
        const val TEST = "test_repo_mcp_server"
        val NAMES = listOf(READ, ADD, EDIT, REMOVE, TEST)

        private val REPO = "repo" to prop("string", "owner/name: the target repo whose First mate or Smith runs get the servers.")
        private val NAME = "name" to prop("string", "The server's name: 1 to 64 letters, digits, '_' or '-'; its tools are mcp__<name>__*.")
        private const val VALUES = "Name to value: a string, or {\"value\": \"...\", \"secret\": true} to seal it (never shown again), " +
            "or {\"secret\": true} alone to keep the value already sealed."
        private val SERVER = arrayOf(REPO, NAME,
            "kind" to prop("string", "http, sse or stdio.", "enum" to listOf("http", "sse", "stdio")),
            "audience" to prop("string", "Who gets the server: first-mate (the desktop's First mate on the user's machine), smith (a Smith " +
                "run of the repo, in its sandbox on the cluster; it gets an X-Frakworks-Run header naming the run), or both. The laptop and " +
                "cluster instances of one server are two rows: a Smith cannot reach the Mac's loopback and the Mac cannot resolve cluster DNS.",
                "enum" to listOf("smith", "first-mate", "both")),
            "role" to prop("string", "ops, or absent: an ops server answers whether a commit is in production for the repo. It must " +
                "conform to ${OpsContract.VERSION}: a tool ${OpsContract.TOOL} taking sha (a required string) and answering {state: deployed|not_yet}, " +
                "deployed meaning the sha is the running commit or an ancestor of it; it is probed on add and edit and refused unless it " +
                "conforms, and it is http or sse (the engine calls it). One per repo and audience.", "enum" to listOf("ops")),
            "url" to prop("string", "http and sse: the server's URL."),
            "headers" to prop("object", "http and sse: request headers. $VALUES"),
            "command" to prop("string", "stdio: the program to run, on the user's machine."),
            "args" to prop("array", "stdio: its arguments.", "items" to mapOf("type" to "string")),
            "env" to prop("object", "stdio: its environment. $VALUES"))

        val TOOLS = listOf(
            McpTool(READ, "Read-only: the MCP servers a target repo's First mate or Smith runs get beside the engine's three: each one's " +
                "name, kind, audience (first-mate, smith or both), url and headers (http, sse) or command, args and env (stdio). A secret " +
                "value is never shown, only whether it is set and since when. Always pass `repo`.", obj(REPO, required = listOf("repo"))),
            McpTool(ADD, "Add an MCP server to a target repo; the audience says who gets it: its next First mate, its next Smith run, or " +
                "both. Refused when the repo already has one by that name ($EDIT changes it). Test it with $TEST.",
                obj(*SERVER, required = listOf("repo", "name", "kind", "audience"))),
            McpTool(EDIT, "Replace a target repo's MCP server whole (every field as for $ADD, audience included). Refused when there is none by that name.",
                obj(*SERVER, required = listOf("repo", "name", "kind", "audience"))),
            McpTool(REMOVE, "Remove an MCP server from a target repo (whoever got it).", obj(REPO, NAME, required = listOf("repo", "name"))),
            McpTool(TEST, "Test a target repo's MCP server: an MCP initialize and tools/list against it, answering whether it " +
                "answered and the tools it listed, or the error; for a role ops server, whether it conforms to ${OpsContract.VERSION} and what is " +
                "missing. The engine tests http and sse servers; a stdio server runs on the " +
                "user's machine, so the desktop's Configure First Mate modal tests it.", obj(REPO, NAME, required = listOf("repo", "name"))),
        )
    }

    private fun shown(view: Map<String, Any?>): String = buildString {
        append("- ${view["name"]} (${view["kind"]}, ${view["audience"]}${view["role"]?.let { ", role $it" } ?: ""})")
        if (view["kind"] == "stdio") {
            append(": ${view["command"]} ${(view["args"] as List<*>).joinToString(" ")}")
            values("env", view["env"])
        } else {
            append(": ${view["url"]}")
            values("headers", view["headers"])
        }
    }

    private fun StringBuilder.values(field: String, m: Any?) {
        val entries = (m as? Map<*, *>).orEmpty()
        if (entries.isEmpty()) return
        append("; $field: ")
        append(entries.entries.joinToString(", ") { (k, v) ->
            val e = v as Map<*, *>
            if (e["secret"] == true) "$k (secret, ${if (e["set"] == true) "set since ${e["since"]}" else "not set"})" else "$k=${e["value"]}"
        })
    }

    fun call(tool: String, args: Map<String, Any?>, login: String?): McpToolResult {
        val (repo, refused) = requiredTargetRepo(engine, args)
        if (refused != null) return refused
        repo!!
        val store = servers ?: return McpToolResult.error("the engine has no MCP server store")
        val name = args["name"] as? String
        if (tool != READ && name == null) return McpToolResult.error("\"name\" is required: the server's name")
        return when (tool) {
            READ -> {
                val all = store.list(repo)
                if (all.isEmpty()) McpToolResult("$repo has no MCP servers beyond the engine's three: neither its First mate nor its Smith runs get one.")
                else McpToolResult("The MCP servers $repo's First mate (first-mate), its Smith runs (smith) or both get beside the engine's three:\n\n" + all.joinToString("\n") { shown(it) })
            }
            ADD, EDIT -> {
                val exists = store.get(repo, name!!) != null
                if (tool == ADD && exists) return McpToolResult.error("$repo already has an MCP server $name: $EDIT changes it")
                if (tool == EDIT && !exists) return McpToolResult.error("$repo has no MCP server $name: $ADD adds it")
                // The store's refusal (a 400 naming the field, a 503 with no secrets key) is the tool's error, whoever calls it.
                val (view, _) = try {
                    store.put(repo, name, args.filterKeys { it != "repo" && it != "name" }, login, RepoMcpServerController.opsCheck(probe ?: McpProbe()))
                } catch (e: ResponseStatusException) {
                    return McpToolResult.error(e.reason ?: e.statusCode.toString())
                }
                val who = when (view["audience"]) { "smith" -> "its next Smith run gets it"; "both" -> "its next First mate and its next Smith run get it"; else -> "its next First mate gets it" }
                McpToolResult("${if (tool == ADD) "Added" else "Saved"} on $repo ($who):\n\n${shown(view)}")
            }
            REMOVE -> if (store.delete(repo, name!!)) McpToolResult("Removed the MCP server $name from $repo.")
                else McpToolResult.error("$repo has no MCP server $name")
            TEST -> {
                val config = store.config(repo, name).firstOrNull() ?: return McpToolResult.error("$repo has no MCP server $name")
                val result = (probe ?: McpProbe()).test(config)
                val contract = result.contract?.let { c ->
                    if (c.conforms) "; it conforms to ${c.version}" else "; it does not conform to ${c.version}: ${c.missing.joinToString("; ")}"
                } ?: ""
                if (result.reached) McpToolResult("$name answered; it lists ${result.tools.size} tool(s): ${result.tools.joinToString(", ").ifEmpty { "none" }}$contract")
                else McpToolResult.error("$name: ${result.error}$contract")
            }
            else -> McpToolResult.error("unknown tool: $tool")
        }
    }
}
