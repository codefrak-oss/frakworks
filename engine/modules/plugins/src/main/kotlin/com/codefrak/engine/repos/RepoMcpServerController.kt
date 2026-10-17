package com.codefrak.engine.repos

import com.codefrak.engine.badRequest
import com.codefrak.engine.provenLogin
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.smith.SmithRefusal.refuseSmith
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * A target repo's MCP servers (#1801, #2449; engine/README.md, "First mate's MCP servers"), by [RepoMcpServers]: list,
 * put (add or replace, with the `audience` that says whether a First mate, a Smith run or both get it), delete and test
 * under `/repos/{owner}/{name}/mcp-servers`, and `.../mcp-servers/config`, the First mate-audience servers with their
 * secrets opened as `--mcp-config` takes them, for the desktop's spawn. The list is open like every
 * read; every write, the test and the config are refused to a Smith sandbox by its source address (403; 503 when Docker
 * cannot be asked), as `PUT /repos/{owner}/{name}/refactor` is. A write puts a `repo` frame `changed` with field
 * `mcpServers` on the stream.
 */
@RestController
class RepoMcpServerController(private val servers: RepoMcpServers, private val probe: McpProbe, private val sandbox: SmithSandbox,
                              private val events: EngineEvents) {
    companion object {
        /**
         * What a write of a row with role `ops` runs before anything is stored (#100008): the probe, and a 400
         * [OpsContract.refusal] when the server does not conform to `ops/1` (or cannot be reached: the missing list is then
         * the probe's error). A row without a role is not probed.
         */
        fun opsCheck(probe: McpProbe): (McpServerConfig) -> Unit = { config ->
            if (config.role == McpRole.OPS) {
                val result = probe.test(config)
                val contract = result.contract ?: OpsContract.Conformance(OpsContract.VERSION, false, listOf(result.error ?: "the server could not be tested"))
                if (!contract.conforms) throw badRequest(OpsContract.refusal(contract))
            }
        }
    }
    private val log = LoggerFactory.getLogger(RepoMcpServerController::class.java)

    @GetMapping("/repos/{owner}/{name}/mcp-servers")
    fun list(@PathVariable owner: String, @PathVariable name: String): List<Map<String, Any?>> = servers.list("$owner/$name")

    /**
     * `{"mcpServers": {...}}`, the repo's First mate-audience servers (`first-mate` and `both` rows, never a `smith` one)
     * as `claude --mcp-config` takes them, secrets opened, for the desktop's spawn. Never a Smith's: a Smith gets its own
     * through its run's Secret ("A Smith's repo MCP servers").
     */
    @GetMapping("/repos/{owner}/{name}/mcp-servers/config")
    fun config(@PathVariable owner: String, @PathVariable name: String, request: HttpServletRequest?): Map<String, Any?> {
        refuseSmith(sandbox, request, log, "read a repo's MCP server config")
        return mapOf("mcpServers" to servers.config("$owner/$name", audience = McpAudience.FIRST_MATE).associate { it.name to it.entry() })
    }

    @GetMapping("/repos/{owner}/{name}/mcp-servers/{server}")
    fun get(@PathVariable owner: String, @PathVariable name: String, @PathVariable server: String): Map<String, Any?> =
        servers.get("$owner/$name", server) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$owner/$name has no MCP server $server")

    /**
     * Adds or replaces the server: 201 when new, 200 when replaced, the read view either way. A row with role `ops` is
     * probed first and refused 400 `ops server does not conform to ops/1: ...` unless it conforms ([opsCheck]).
     */
    @PutMapping("/repos/{owner}/{name}/mcp-servers/{server}")
    fun put(@PathVariable owner: String, @PathVariable name: String, @PathVariable server: String, @RequestBody body: Any?,
            request: HttpServletRequest?): ResponseEntity<Map<String, Any?>> {
        refuseSmith(sandbox, request, log, "set a repo's MCP servers")
        val (view, created) = servers.put("$owner/$name", server, body, request?.provenLogin(), opsCheck(probe))
        events.publish(EngineEvent.Repo(view["repo"] as String, "changed", listOf("mcpServers")))
        return ResponseEntity.status(if (created) HttpStatus.CREATED else HttpStatus.OK).body(view)
    }

    /** 204, or 404 when the repo has no server by that name. */
    @DeleteMapping("/repos/{owner}/{name}/mcp-servers/{server}")
    fun delete(@PathVariable owner: String, @PathVariable name: String, @PathVariable server: String, request: HttpServletRequest?): ResponseEntity<Unit> {
        refuseSmith(sandbox, request, log, "remove a repo's MCP servers")
        val repo = servers.repo("$owner/$name")
        if (!servers.delete(repo, server)) return ResponseEntity.notFound().build()
        events.publish(EngineEvent.Repo(repo, "changed", listOf("mcpServers")))
        return ResponseEntity.noContent().build()
    }

    /**
     * `{reached, tools, toolSpecs, error, contract?}`: an MCP initialize and tools/list against an http or sse server; a
     * stdio one the desktop tests. For a row with role `ops`, `contract` is `{version: "ops/1", conforms, missing}`.
     */
    @PostMapping("/repos/{owner}/{name}/mcp-servers/{server}/test")
    fun test(@PathVariable owner: String, @PathVariable name: String, @PathVariable server: String, request: HttpServletRequest?): McpProbeResult {
        refuseSmith(sandbox, request, log, "test a repo's MCP servers")
        val config = servers.config("$owner/$name", server).firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$owner/$name has no MCP server $server")
        return probe.test(config)
    }
}
