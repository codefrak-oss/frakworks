package com.codefrak.engine.repos

import com.codefrak.engine.accounts.SecretsKey
import com.codefrak.engine.badRequest
import com.codefrak.engine.board.targetRepoOr404
import com.codefrak.engine.refuseUnknownFields
import com.codefrak.engine.repos.RepoMcpServers.Companion.AUDIENCE_400
import com.codefrak.engine.repos.RepoMcpServers.Companion.KEY
import com.codefrak.engine.repos.RepoMcpServers.Companion.MAX_VALUE
import com.codefrak.engine.repos.RepoMcpServers.Companion.NAME
import com.codefrak.engine.repos.RepoMcpServers.Companion.OPS_STDIO_400
import com.codefrak.engine.repos.RepoMcpServers.Companion.RESERVED
import com.codefrak.engine.repos.RepoMcpServers.Companion.ROLE_400
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * [RepoMcpServers] in `engine.repo_mcp_server`: a header or env value marked secret is sealed with [SecretsKey], the
 * repo, server and key as associated data.
 */
@Repository
class StoredRepoMcpServers(private val jdbc: JdbcClient, private val secrets: SecretsKey, private val repos: TargetRepos) : RepoMcpServers {
    companion object {
        private val FIELDS = listOf("kind", "audience", "role", "url", "headers", "command", "args", "env")
        private val JSON = jacksonObjectMapper()
    }

    override fun repo(fullName: String): String = targetRepoOr404(repos, fullName).fullName

    override fun checkName(server: String): String {
        if (!NAME.matches(server)) throw badRequest("name is 1 to 64 letters, digits, '_' or '-'")
        if (server in RESERVED) throw badRequest("name $server is the engine's own server, on every First mate and Smith already")
        if (server == "config") throw badRequest("name config is taken: GET .../mcp-servers/config is the spawn's config")
        return server
    }

    private data class Row(val name: String, val kind: McpServerKind, val url: String?, val command: String?, val args: List<String>,
                           val headers: Map<String, Map<String, Any?>>, val env: Map<String, Map<String, Any?>>,
                           val updatedAt: OffsetDateTime, val updatedBy: String?, val audience: McpAudience, val role: McpRole?)

    private fun rows(repo: String, name: String? = null): List<Row> =
        jdbc.sql("SELECT name, kind, url, command, args, headers, env, updated_at, updated_by, audience, role FROM engine.repo_mcp_server WHERE repo = :r" +
            (if (name != null) " AND name = :n" else "") + " ORDER BY name")
            .param("r", repo).apply { if (name != null) param("n", name) }
            .query { rs, _ -> Row(rs.getString(1), McpServerKind.of(rs.getString(2))!!, rs.getString(3), rs.getString(4),
                JSON.readValue(rs.getString(5)), JSON.readValue(rs.getString(6)), JSON.readValue(rs.getString(7)),
                rs.getObject(8, OffsetDateTime::class.java), rs.getString(9), McpAudience.of(rs.getString(10))!!, McpRole.of(rs.getString(11))) }.list()

    /** A stored value as a read shows it: a plain one whole, a secret one only whether it is set and since when. */
    private fun shown(entries: Map<String, Map<String, Any?>>): Map<String, Map<String, Any?>> = entries.mapValues { (_, e) ->
        if (e["secret"] == true) linkedMapOf("secret" to true, "set" to (e["sealed"] != null), "since" to e["setAt"])
        else linkedMapOf("secret" to false, "value" to e["value"])
    }

    private fun view(repo: String, r: Row): Map<String, Any?> =
        linkedMapOf<String, Any?>("repo" to repo, "name" to r.name, "kind" to r.kind.wire, "audience" to r.audience.wire, "role" to r.role?.wire).apply {
        if (r.kind == McpServerKind.STDIO) { put("command", r.command); put("args", r.args); put("env", shown(r.env)) }
        else { put("url", r.url); put("headers", shown(r.headers)) }
        put("updatedAt", r.updatedAt); put("updatedBy", r.updatedBy)
    }

    override fun list(fullName: String): List<Map<String, Any?>> { val repo = repo(fullName); return rows(repo).map { view(repo, it) } }

    override fun get(fullName: String, server: String): Map<String, Any?>? { val repo = repo(fullName); return rows(repo, server).firstOrNull()?.let { view(repo, it) } }

    override fun put(fullName: String, server: String, body: Any?, login: String?, check: (McpServerConfig) -> Unit): Pair<Map<String, Any?>, Boolean> {
        val repo = repo(fullName)
        checkName(server)
        if (body !is Map<*, *>) throw badRequest("the body is an object with kind and its fields: url and headers, or command, args and env")
        refuseUnknownFields(body.keys.map { it.toString() }.sorted(), FIELDS.toSet(), FIELDS.joinToString(", "))
        val kindName = body["kind"] ?: throw badRequest("kind is required: http, sse or stdio")
        val kind = McpServerKind.of(kindName as? String) ?: throw badRequest("kind is http, sse or stdio, not '$kindName'")
        val audienceName = body["audience"] ?: throw badRequest(AUDIENCE_400)
        val audience = McpAudience.of(audienceName as? String) ?: throw badRequest("audience is smith, first-mate or both, not '$audienceName'")
        val role = body["role"]?.let { McpRole.of(it as? String) ?: throw badRequest(ROLE_400) }
        if (role == McpRole.OPS) {
            if (kind == McpServerKind.STDIO) throw badRequest(OPS_STDIO_400)
            rows(repo).firstOrNull { it.name != server && it.role == McpRole.OPS && it.audience == audience }?.let {
                throw badRequest("$repo already has an ops server for audience ${audience.wire}: ${it.name}; a repo has one per audience")
            }
        }
        val old = rows(repo, server).firstOrNull()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        var url: String? = null; var command: String? = null; var args = emptyList<String>()
        var headers = emptyMap<String, Map<String, Any?>>(); var env = emptyMap<String, Map<String, Any?>>()
        if (kind == McpServerKind.STDIO) {
            for (f in listOf("url", "headers")) if (body[f] != null) throw badRequest("$f is for an http or sse server, not a stdio one")
            command = (body["command"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_VALUE }
                ?: throw badRequest("command is required for a stdio server: the program to run")
            args = when (val a = body["args"]) {
                null -> emptyList()
                is List<*> -> a.map { it as? String ?: throw badRequest("args is a list of strings") }
                else -> throw badRequest("args is a list of strings")
            }
            env = values(repo, server, "env", body["env"], old?.env.orEmpty(), now)
        } else {
            for (f in listOf("command", "args", "env")) if (body[f] != null) throw badRequest("$f is for a stdio server, not an ${kind.wire} one")
            url = (body["url"] as? String)?.trim()?.takeIf { u ->
                runCatching { URI(u) }.getOrNull()?.let { (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrEmpty() } == true
            } ?: throw badRequest("url is required for an ${kind.wire} server: an http or https URL")
            headers = values(repo, server, "headers", body["headers"], old?.headers.orEmpty(), now)
        }
        // Only a role row is checked (the ops probe), so a plain write never needs the secrets key to open what it keeps.
        if (role != null) check(McpServerConfig(server, kind, url, open(repo, server, "headers", headers), command, args, open(repo, server, "env", env), audience, role))
        val by = login?.trim()?.takeIf { it.isNotEmpty() }
        val p = mapOf("r" to repo, "n" to server, "k" to kind.wire, "u" to url, "c" to command, "a" to JSON.writeValueAsString(args),
            "h" to JSON.writeValueAsString(headers), "e" to JSON.writeValueAsString(env), "t" to now, "b" to by, "w" to audience.wire, "o" to role?.wire)
        if (old != null) jdbc.sql("UPDATE engine.repo_mcp_server SET kind = :k, url = :u, command = :c, args = :a, headers = :h, env = :e, " +
            "updated_at = :t, updated_by = :b, audience = :w, role = :o WHERE repo = :r AND name = :n").params(p).update()
        else jdbc.sql("INSERT INTO engine.repo_mcp_server (repo, name, kind, url, command, args, headers, env, updated_at, updated_by, audience, role) " +
            "VALUES (:r, :n, :k, :u, :c, :a, :h, :e, :t, :b, :w, :o)").params(p).update()
        return view(repo, rows(repo, server).single()) to (old == null)
    }

    private fun aad(repo: String, server: String, field: String, key: String) = "$repo#mcp/$server/$field/$key"

    /** A stored field's values opened: 503 when one is sealed and the key is unset. */
    private fun open(repo: String, server: String, field: String, m: Map<String, Map<String, Any?>>): Map<String, String> = m.mapValues { (k, e) ->
        if (e["secret"] != true) e["value"] as String
        else {
            if (!secrets.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_SECRETS_KEY is not set")
            secrets.open(Base64.getDecoder().decode(e["sealed"] as String), aad(repo, server, field, k))
        }
    }

    private fun values(repo: String, server: String, field: String, given: Any?, old: Map<String, Map<String, Any?>>,
                       now: OffsetDateTime): Map<String, Map<String, Any?>> {
        if (given == null) return emptyMap()
        if (given !is Map<*, *>) throw badRequest("$field is an object of name to value")
        val out = linkedMapOf<String, Map<String, Any?>>()
        for ((k, v) in given) {
            val key = k.toString()
            if (!KEY.matches(key)) throw badRequest("$field.$key: a name is 1 to 100 letters, digits, '_', '.' or '-'")
            out[key] = when (v) {
                is String -> plain(field, key, v)
                is Map<*, *> -> {
                    val secret = v["secret"] == true
                    val value: String? = v["value"]?.let { it as? String ?: throw badRequest("$field.$key.value is a string") }
                    when {
                        !secret -> plain(field, key, value ?: throw badRequest("$field.$key.value is required unless it is a secret kept as it is"))
                        value == null -> old[key]?.takeIf { it["secret"] == true && it["sealed"] != null }
                            ?: throw badRequest("$field.$key has no secret value set to keep: send its value")
                        else -> {
                            val text: String = value!!
                            if (text.isEmpty() || text.length > MAX_VALUE) throw badRequest("$field.$key.value is 1 to $MAX_VALUE characters")
                            if (!secrets.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_SECRETS_KEY is not set")
                            linkedMapOf("secret" to true, "sealed" to Base64.getEncoder().encodeToString(secrets.seal(text, aad(repo, server, field, key))),
                                "setAt" to now.toString())
                        }
                    }
                }
                else -> throw badRequest("$field.$key is a string, or {\"value\": ..., \"secret\": true}")
            }
        }
        return out
    }

    private fun plain(field: String, key: String, value: String): Map<String, Any?> {
        if (value.length > MAX_VALUE) throw badRequest("$field.$key is at most $MAX_VALUE characters")
        return linkedMapOf("secret" to false, "value" to value)
    }

    override fun delete(fullName: String, server: String): Boolean =
        jdbc.sql("DELETE FROM engine.repo_mcp_server WHERE repo = :r AND name = :n").param("r", repo(fullName)).param("n", server).update() == 1

    override fun config(fullName: String, server: String?, audience: McpAudience?): List<McpServerConfig> {
        val repo = repo(fullName)
        return rows(repo, server).filter { audience == null || it.audience.serves(audience) }.map { r ->
            McpServerConfig(r.name, r.kind, r.url, open(repo, r.name, "headers", r.headers), r.command, r.args, open(repo, r.name, "env", r.env), r.audience, r.role)
        }
    }

    override fun hasOpsServer(fullName: String): Boolean = rows(repo(fullName)).any { it.role == McpRole.OPS }

    override fun opsServer(fullName: String): McpServerConfig? = config(fullName).filter { it.role == McpRole.OPS }
        .minByOrNull { when (it.audience) { McpAudience.SMITH -> 0; McpAudience.BOTH -> 1; McpAudience.FIRST_MATE -> 2 } }
}
