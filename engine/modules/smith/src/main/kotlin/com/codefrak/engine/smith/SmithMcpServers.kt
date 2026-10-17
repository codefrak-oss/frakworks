package com.codefrak.engine.smith

import com.codefrak.engine.repos.McpAudience
import com.codefrak.engine.repos.McpServerConfig
import com.codefrak.engine.repos.McpServerKind
import com.codefrak.engine.repos.RepoMcpServers
import com.codefrak.engine.repos.localMcpUrl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.jacksonObjectMapper

/** A repo server left off a Smith run, and why; the reason never carries a header value. */
data class SmithMcpDrop(val name: String, val reason: String)

/**
 * The repo's MCP servers a Smith run gets beside the engine's two (#2449; engine/README.md, "A Smith's repo MCP
 * servers"): the `smith` and `both` rows of `engine.repo_mcp_server`, read in-process with their secrets opened, each
 * `http`/`sse` entry stamped `X-Frakworks-Run: <run id>` so a server such as ops-server can name the run in HITLP
 * `attested.onBehalfOf`, and `X-Frakworks-Run-Token: <its run identity token>` ([RunTokens], #2453), minted for that
 * server's URL alone, so the server can verify the claim instead of trusting it. The result is one JSON object, `{<name>: <--mcp-config entry>}`, that [sandboxEnv] hands the
 * sandbox as `SMITH_MCP_SERVERS` through the run's Secret (Kubernetes) or the container's env (Docker) and nowhere
 * else: never the Job spec, the brief, the prompt or a log line. In the image, `frakworks-mcp-config` merges it into
 * the engine's two (an engine name wins) and `run-smith.sh` allows `mcp__<name>` per server.
 */
@Component
class SmithMcpServers(private val repoServers: RepoMcpServers?, private val runTokens: RunTokens? = null) {
    companion object {
        /** The env var the sandbox reads the JSON from. */
        const val ENV = "SMITH_MCP_SERVERS"
        /** The header every http/sse server a Smith gets carries, its value the run's id (`SMITH_ID`). */
        const val RUN_HEADER = "X-Frakworks-Run"
        /** The engine's own servers, in every Smith's config already (`smith/frakworks-mcp-config.sh`). */
        val ENGINE_SERVERS = listOf("frakworks", "frakworks-engine-diagnostics")
        private val JSON = jacksonObjectMapper()

        /**
         * The entries for [runId] from the repo's [repo] servers: `smith` and `both` rows kept, a `first-mate` row, an
         * engine server's name and a loopback or local http/sse URL (a Smith pod cannot reach it) dropped with a reason;
         * every http/sse entry gets [RUN_HEADER] set to [runId], replacing a row header of that name; a stdio entry
         * passes through as it is. [token] mints the run's identity token for a server URL; each http/sse entry gets its
         * own as [RunTokens.HEADER] (a row header of that name dropped), none when [token] answers null.
         */
        fun build(runId: String, repo: List<McpServerConfig>, token: (String) -> String? = { null }): Pair<Map<String, Map<String, Any?>>, List<SmithMcpDrop>> {
            val servers = linkedMapOf<String, Map<String, Any?>>()
            val dropped = mutableListOf<SmithMcpDrop>()
            for (s in repo) when {
                !s.audience.serves(McpAudience.SMITH) -> dropped += SmithMcpDrop(s.name, "a First mate's, not a Smith's")
                s.name in ENGINE_SERVERS -> dropped += SmithMcpDrop(s.name, "the engine's own server's name")
                s.kind != McpServerKind.STDIO && localMcpUrl(s.url) -> dropped += SmithMcpDrop(s.name, "${s.url} is a local address a Smith pod cannot reach")
                s.kind == McpServerKind.STDIO -> servers[s.name] = s.entry()
                else -> {
                    val headers = linkedMapOf<String, String>()
                    s.headers.forEach { (k, v) -> if (!k.equals(RUN_HEADER, ignoreCase = true) && !k.equals(RunTokens.HEADER, ignoreCase = true)) headers[k] = v }
                    headers[RUN_HEADER] = runId
                    s.url?.let(token)?.let { headers[RunTokens.HEADER] = it }
                    servers[s.name] = linkedMapOf("type" to s.kind.wire, "url" to s.url, "headers" to headers)
                }
            }
            return servers to dropped
        }

        /** [servers] as the env value: one JSON object, name to entry. */
        fun json(servers: Map<String, Map<String, Any?>>): String = JSON.writeValueAsString(servers)
    }

    private val log = LoggerFactory.getLogger(SmithMcpServers::class.java)

    /**
     * The `SMITH_MCP_SERVERS` value for [spec]'s run, or null when the repo has no Smith-audience server it can carry.
     * Each drop is one WARN line naming the server and the reason; a store that cannot open a sealed value (503 without
     * `ENGINE_SECRETS_KEY`) costs the run its repo servers, said in one WARN line, and the run still starts. The INFO
     * line names the servers, never a header value. A token that cannot be minted (no `ENGINE_SECRETS_KEY`) is one WARN
     * line, and the servers go without [RunTokens.HEADER].
     */
    fun forRun(spec: SmithSpec): String? {
        val store = repoServers ?: return null
        val rows = try {
            store.config(spec.repo, audience = McpAudience.SMITH)
        } catch (e: ResponseStatusException) {
            log.warn("smith {} ({}): no repo MCP servers this run: {}", spec.runId, spec.repo, e.reason ?: e.message)
            return null
        }
        var tokenFailure: String? = null
        val (servers, dropped) = build(spec.runId, rows) { url ->
            if (tokenFailure != null) null
            else try { runTokens?.mint(spec, url) } catch (e: Exception) { tokenFailure = e.message ?: e.javaClass.simpleName; null }
        }
        tokenFailure?.let { log.warn("smith {} ({}): no run identity token this run: {}", spec.runId, spec.repo, it) }
        for (d in dropped) log.warn("smith {} ({}): repo MCP server {} left off: {}", spec.runId, spec.repo, d.name, d.reason)
        if (servers.isEmpty()) return null
        log.info("smith {} ({}): {} repo MCP server(s): {}", spec.runId, spec.repo, servers.size, servers.keys.joinToString(", "))
        return json(servers)
    }
}
