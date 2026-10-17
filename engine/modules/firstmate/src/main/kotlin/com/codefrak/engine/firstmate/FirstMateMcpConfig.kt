package com.codefrak.engine.firstmate

import com.codefrak.engine.repos.McpAudience
import com.codefrak.engine.repos.McpServerConfig
import com.codefrak.engine.repos.McpServerKind
import com.codefrak.engine.repos.RepoMcpServers
import com.codefrak.engine.repos.localMcpUrl
import org.springframework.stereotype.Component
import java.io.File

/** A server left off a session's config, and why. */
data class DroppedMcpServer(val name: String, val reason: String)

/** A session pod's `--mcp-config` `mcpServers`, and the repo's servers it could not carry. */
data class FirstMateMcp(val servers: Map<String, Map<String, Any?>>, val dropped: List<DroppedMcpServer>) {
    /** The chat's first line naming what was dropped; null when nothing was. */
    fun firstLine(): String? = dropped.takeIf { it.isNotEmpty() }
        ?.joinToString(", ", prefix = "Not on this chat: ", postfix = ".") { "${it.name} (${it.reason})" }

    /**
     * This config with the engine's own servers at [engineUrl] (the pod's in-cluster engine, `FM_ENGINE_URL`) instead
     * of the caller's address: a phone's request comes in through the Ingress, which the pod's NetworkPolicy cannot
     * reach. Unchanged when [engineUrl] is blank.
     */
    fun rebasedOn(engineUrl: String): FirstMateMcp {
        val base = engineUrl.trimEnd('/').takeIf { it.isNotBlank() } ?: return this
        return copy(servers = servers.mapValues { (name, entry) ->
            if (name in FirstMateMcpConfig.ENGINE_SERVERS) LinkedHashMap(entry).apply { put("url", "$base/mcp/$name") } else entry
        })
    }
}

/**
 * Builds a first-mate session pod's MCP config (engine/README.md, "First-mate
 * sessions"): the engine's two servers with the session's token in `Authorization`, plus the repo's own servers read
 * in-process from [RepoMcpServers], the First mate-audience ones (`first-mate` and `both`; a `smith` row is a Smith run's
 * alone, #2449). A pod is neither the phone nor the desktop, so a stdio server whose command is not in the image, and an
 * http/sse server on a loopback or local host, are dropped and named.
 */
@Component
class FirstMateMcpConfig(private val repoServers: RepoMcpServers) {
    companion object {
        /** The engine's own servers, at `<engine>/mcp/<name>`. */
        val ENGINE_SERVERS = listOf("frakworks", "frakworks-engine-diagnostics")

        /** Whether [url]'s host is loopback, `localhost` or a `*.local` name ([localMcpUrl], shared with a Smith's `SmithMcpServers`). */
        fun local(url: String?): Boolean = localMcpUrl(url)

        /** Whether [command] runs in this image: an absolute path that is executable, or a name found on `PATH`. */
        fun onPath(command: String, path: String? = System.getenv("PATH")): Boolean {
            if (command.contains('/')) return File(command).canExecute()
            return path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotEmpty() }.any { File(it, command).canExecute() }
        }

        /** The config from the repo's [repo] servers; [runs] says whether a stdio command is in the image. */
        fun build(engineUrl: String, token: String, repo: List<McpServerConfig>, runs: (String) -> Boolean): FirstMateMcp {
            val base = engineUrl.trimEnd('/')
            val servers = linkedMapOf<String, Map<String, Any?>>()
            for (name in ENGINE_SERVERS)
                servers[name] = linkedMapOf("type" to "http", "url" to "$base/mcp/$name", "headers" to mapOf("Authorization" to "Bearer $token"))
            val dropped = mutableListOf<DroppedMcpServer>()
            for (s in repo) when {
                s.name in servers -> dropped += DroppedMcpServer(s.name, "the engine's own server's name")
                s.kind == McpServerKind.STDIO && !runs(s.command.orEmpty()) -> dropped += DroppedMcpServer(s.name, "${s.command} is not in the image")
                s.kind != McpServerKind.STDIO && local(s.url) -> dropped += DroppedMcpServer(s.name, "${s.url} is a local address")
                else -> servers[s.name] = s.entry()
            }
            return FirstMateMcp(servers, dropped)
        }
    }

    /** [repo]'s session config, its First mate-audience servers with their secret values opened. */
    fun forSession(engineUrl: String, token: String, repo: String): FirstMateMcp =
        build(engineUrl, token, repoServers.config(repo, audience = McpAudience.FIRST_MATE)) { onPath(it) }
}
