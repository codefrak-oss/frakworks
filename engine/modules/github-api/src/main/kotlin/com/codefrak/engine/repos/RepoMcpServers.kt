package com.codefrak.engine.repos

import java.net.InetAddress
import java.net.URI

/** A server's kind: the `type` Claude Code's `--mcp-config` document takes. */
enum class McpServerKind(val wire: String) {
    HTTP("http"), SSE("sse"), STDIO("stdio");
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } }
}

/**
 * Who gets a repo's MCP server (#2449): a First mate (the desktop's spawn and a first-mate pod), a Smith run of the repo,
 * or both. The same server is typically two rows, a laptop instance for the First mate and a cluster one for Smiths,
 * because the Mac cannot resolve cluster DNS and a Smith pod cannot reach the Mac's loopback.
 */
enum class McpAudience(val wire: String) {
    SMITH("smith"), FIRST_MATE("first-mate"), BOTH("both");
    /** Whether a row of this audience is handed to [who]. */
    fun serves(who: McpAudience) = this == BOTH || this == who
    companion object {
        fun of(s: String?) = entries.firstOrNull { it.wire == s }
        val WIRES = entries.joinToString(", ") { it.wire }
    }
}

/**
 * Whether [url]'s host is loopback (127.0.0.0/8, ::1), `localhost` or a `*.local` mDNS name; a malformed URL counts.
 * Neither a first-mate pod nor a Smith pod can reach one. A Kubernetes service name (`*.cluster.local`, the cluster
 * DNS zone) is not local: it is exactly what a Smith pod reaches a cluster instance by (#2449).
 */
fun localMcpUrl(url: String?): Boolean {
    val host = runCatching { URI(url!!.trim()).host }.getOrNull()?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return true
    if (host == "localhost" || host.endsWith(".localhost")) return true
    if ((host == "local" || host.endsWith(".local")) && !host.endsWith(".cluster.local")) return true
    if (host.contains(':')) return runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(true)
    val octets = host.split('.')
    return octets.size == 4 && octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true } && octets[0] == "127"
}

/**
 * A server's role (#100008; engine/docs/feature.md, "The ops role"): `ops`, a server that conforms to the `ops/1`
 * contract (a tool `is_deployed(sha)` answering `{state: deployed|not_yet}`), which `DeployedChecks` asks whether a
 * commit is in production for the repo. A row without a role is a plain server.
 */
enum class McpRole(val wire: String) {
    OPS("ops");
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } }
}

/** One server, its secrets opened: what the spawn's config and the engine's test need, never what a read answers. */
data class McpServerConfig(val name: String, val kind: McpServerKind, val url: String?, val headers: Map<String, String>,
                           val command: String?, val args: List<String>, val env: Map<String, String>,
                           val audience: McpAudience = McpAudience.FIRST_MATE, val role: McpRole? = null) {
    /** The server's entry in `--mcp-config`'s `mcpServers`. */
    fun entry(): Map<String, Any?> = when (kind) {
        McpServerKind.STDIO -> linkedMapOf("type" to "stdio", "command" to command, "args" to args, "env" to env)
        else -> linkedMapOf("type" to kind.wire, "url" to url, "headers" to headers)
    }
}

/**
 * A target repo's MCP servers (#1801, #2449; engine/README.md, "First mate's MCP servers", "A Smith's repo MCP servers"):
 * the servers added to a First mate's or a Smith run's `--mcp-config` for that repo, beside the engine's three, each
 * row saying which of the two gets it ([McpAudience]). A header or env value marked secret is sealed, and a read answers
 * only `{secret: true, set, since}` for it; [config] opens them, for the desktop's spawn and the engine's test alone.
 * The engine's is `StoredRepoMcpServers` (engine-github), `engine.repo_mcp_server`.
 */
interface RepoMcpServers {
    companion object {
        /** A server's name: it becomes `mcp__<name>__*` in First mate's allow list. */
        val NAME = Regex("^[A-Za-z0-9_-]{1,64}$")
        /** The engine's own servers, on every First mate and every Smith already. */
        val RESERVED = setOf("frakworks", "frakworks-engine-diagnostics")
        val KEY = Regex("^[A-Za-z0-9_.-]{1,100}$")
        const val MAX_VALUE = 8000
        const val AUDIENCE_400 = "audience is required: smith, first-mate or both (who gets the server)"
        const val ROLE_400 = "role is ops or absent"
        /** A stdio row cannot carry the ops role: the engine, not the user's machine, calls an ops server. */
        const val OPS_STDIO_400 = "a stdio server cannot have role ops: it runs on the user's machine, and the engine calls an ops server itself; give it an http or sse url"
    }

    /** The repo's row name, or 404 for one that is not a target repo. */
    fun repo(fullName: String): String

    fun checkName(server: String): String

    /** `GET`: the repo's servers, name order, no secret value among them. */
    fun list(fullName: String): List<Map<String, Any?>>

    /** One server's read view, or null. */
    fun get(fullName: String, server: String): Map<String, Any?>?

    /**
     * `PUT`: adds the server or replaces it whole. `kind` and `audience` (`smith`, `first-mate` or `both`: who gets it)
     * are required; `url` (and `headers`) for http and sse, `command` (and `args`, `env`) for stdio. A header or env value is a string (plain), `{"value": "...", "secret": true}` (sealed),
     * or `{"secret": true}` alone, which keeps the value already sealed under that key. `role` is `ops` or absent
     * ([McpRole]; 400 [ROLE_400] otherwise): an ops row is http or sse (400 [OPS_STDIO_400] for stdio) and the only
     * one of the repo for its audience (400 naming the other). 400 names the field; 503 when a
     * secret is to be sealed and `ENGINE_SECRETS_KEY` is unset. [check] sees a validated role row with its secrets
     * opened before anything is written (a plain row is not checked), and refuses it by throwing (the ops conformance probe, `OpsContract`): nothing
     * is stored when it does. Returns the read view and whether the server was new.
     */
    fun put(fullName: String, server: String, body: Any?, login: String?, check: (McpServerConfig) -> Unit = {}): Pair<Map<String, Any?>, Boolean>

    /** `DELETE`: false when the repo has no server by that name. */
    fun delete(fullName: String, server: String): Boolean

    /**
     * The repo's servers with their secrets opened, every one or only those [audience] gets (its own and `both`): 503 when
     * one is sealed and the key is unset. Never for a read.
     */
    fun config(fullName: String, server: String? = null, audience: McpAudience? = null): List<McpServerConfig>

    /** Whether the repo has an ops server (role `ops`), any audience, without opening its secrets. */
    fun hasOpsServer(fullName: String): Boolean

    /**
     * The repo's ops server (role `ops`), secrets opened, for the engine to call: the row the engine can reach from the
     * cluster first (`smith`, then `both`, then `first-mate`); null when the repo has none.
     */
    fun opsServer(fullName: String): McpServerConfig?
}
