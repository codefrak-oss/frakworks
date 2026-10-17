package com.codefrak.engine

import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.repos.TokenResolution
import com.codefrak.engine.smith.RunTokens
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

/**
 * The engine's one door for a caller that is no Smith (engine/README.md, "Who is calling",
 * "Tokens"): every request but `GET /health`, `GET /ready`, `GET /lease/await-standby` (loopback only, the preStop),
 * `POST /hooks/github`, the run identity token's JWKS and introspection (`RunTokenController`) and the frakpanel tile reads ([TILE_READ]) is checked. A tile is loaded by a panel slot by
 * bare URL and cannot send a header; its pages and `state` answer only the tile DTOs, and 404 while the tiles are off. The source address is checked first: a Smith sandbox is its run, never a login, so
 * its `Authorization` header (if any) is ignored and its `SmithCaller` is left under [SMITH_AT_ADDRESS_ATTRIBUTE] so the
 * controller does not look it up again (a loopback address is never one, and the sandbox is not asked); a provider that cannot be asked is 503, as `CallerRefusal` answers. Anyone else
 * needs `Authorization: Bearer <token>`: none, or an unknown, revoked or expired one, is 401 `no such token` (logged
 * with its prefix); a token whose login is no longer on the trusted list is 401 `@<login> is not a trusted login`; a
 * valid one puts the login and the token's id on the request ([TOKEN_LOGIN_ATTRIBUTE], [TOKEN_ID_ATTRIBUTE]), where
 * `Actor.Login` and `McpCaller.Nobody.login` are built from, and bumps `last_used_at` at most once a minute.
 *
 * A `first-mate-pod` token (`EngineTokens.FIRST_MATE_POD`) is scoped to its session: it reaches only
 * `/first-mate/pod/{its session}/` ([podPath]), and anything else is 403 [POD_SCOPE].
 *
 * Two exceptions to the 401 (and the 503): a `POST /mcp/{server}` refusal is left under [REFUSAL_ATTRIBUTE] for `EngineMcpController`
 * to answer as JSON-RPC error `-32001` with the request's id; and the three `/tokens` endpoints pass without a token
 * while no unrevoked token exists (the bootstrap door, `TokenController`).
 *
 * Registered by [BearerTokenFilterConfig], not as a scanned `@Component`: a `@WebMvcTest` slice (`HealthControllerTest`,
 * `EchoControllerTest`) picks up every `Filter` component but has no `SmithSandbox` or database to build this one from.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class BearerTokenFilter(private val sandbox: SmithSandbox, private val tokens: EngineTokens, private val access: Access) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(BearerTokenFilter::class.java)

    companion object {
        const val AUTHORIZATION = "Authorization"
        const val NO_SUCH_TOKEN = "no such token"
        const val POD_SCOPE = "a first-mate-pod token reaches only /first-mate/pod/{its session}/"
        /** The paths a first-mate-pod token of [sessionId] may reach: its own session's (a null session reaches none). */
        fun podPath(sessionId: String?) = if (sessionId == null) "/first-mate/pod/\u0000" else "/first-mate/pod/$sessionId/"
        const val CANNOT_TELL = "cannot tell whether the caller is a Smith sandbox"
        /** Method and path pairs the filter never touches. */
        val EXEMPT = setOf("GET /health", "GET /ready", "POST /hooks/github", "GET /lease/await-standby",
            "GET /.well-known/jwks.json", "POST /run-tokens/introspect")
        /** A Smith run's identity token (`RunTokens`) offered as a bearer token: it attests a run and grants nothing (#2453). */
        const val RUN_TOKEN = "a run identity token is not an engine token: it cannot call the engine or answer HITLP"
        /** A frakpanel tile's page or its `state` poll (engine/README.md, "frakpanel tiles"); not `/frakpanel/tiles/enabled`. */
        val TILE_READ = Regex("/frakpanel/tiles/(ai-accounts|frakworks-issues)(/|/state)?")
        /** Request attribute: why a `POST /mcp/{server}` request was refused, answered by the controller as `-32001`. */
        const val REFUSAL_ATTRIBUTE = "frakworks.token.refusal"
        /** The JSON-RPC error code of an MCP request without a valid token. */
        const val MCP_UNAUTHORIZED = -32001
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        "${request.method} ${request.requestURI}" in EXEMPT || (request.method == "GET" && TILE_READ.matches(request.requestURI))

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val address = request.remoteAddr
        if (address != null && !loopback(address)) {
            val smith = try { sandbox.runAt(address) } catch (e: Exception) {
                log.warn("could not tell whether {} is a Smith sandbox: {}", address, e.message)
                return refuse(request, response, chain, CANNOT_TELL, HttpStatus.SERVICE_UNAVAILABLE)
            }
            if (smith != null) {
                log.debug("Authorization header from Smith sandbox {} (run {}) ignored", address, smith.runId)
                request.setAttribute(SMITH_AT_ADDRESS_ATTRIBUTE, smith)
                return chain.doFilter(request, response)
            }
        }
        val header = request.getHeader(AUTHORIZATION)?.trim()
        if (header == null && bootstrap(request)) return chain.doFilter(request, response)
        val token = header?.takeIf { it.length > 7 && it.substring(0, 7).equals("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        if (token.isNullOrEmpty()) {
            log.info("{} {}: {}", request.method, request.requestURI, if (header == null) "no Authorization header" else "Authorization is not a bearer token")
            return refuse(request, response, chain, NO_SUCH_TOKEN)
        }
        when (val r = tokens.resolve(token)) {
            is TokenResolution.NoSuchToken -> {
                if (RunTokens.looksLikeRunToken(token)) {
                    log.info("{} {}: a run identity token offered as a bearer token", request.method, request.requestURI)
                    return refuse(request, response, chain, RUN_TOKEN)
                }
                log.info("{} {}: no such token prefix={}", request.method, request.requestURI, r.prefix)
                return refuse(request, response, chain, NO_SUCH_TOKEN)
            }
            is TokenResolution.Ok -> {
                if (!access.trusted(r.row.login)) {
                    log.info("{} {}: token {} is bound to @{}, not a trusted login", request.method, request.requestURI, r.row.prefix, r.row.login)
                    return refuse(request, response, chain, "@${r.row.login} is not a trusted login")
                }
                if (r.row.purpose == EngineTokens.FIRST_MATE_POD && !request.requestURI.startsWith(podPath(r.row.sessionId))) {
                    log.info("{} {}: first-mate-pod token {} reaches only {}", request.method, request.requestURI, r.row.prefix, podPath(r.row.sessionId))
                    return refuse(request, response, chain, POD_SCOPE, HttpStatus.FORBIDDEN)
                }
                request.setAttribute(TOKEN_LOGIN_ATTRIBUTE, r.row.login)
                request.setAttribute(TOKEN_ID_ATTRIBUTE, r.row.id)
                runCatching { tokens.touch(r.row.id) }.onFailure { log.warn("could not bump last_used_at of token {}: {}", r.row.id, it.message) }
                chain.doFilter(request, response)
            }
        }
    }

    /** The engine's own host (a port-forward, a local call): never a Smith sandbox, so the sandbox is not asked. */
    private fun loopback(address: String): Boolean =
        runCatching { java.net.InetAddress.getByName(address).isLoopbackAddress }.getOrDefault(false)

    /** `/tokens` while no unrevoked token exists: the bootstrap door the first token is minted through. */
    private fun bootstrap(request: HttpServletRequest): Boolean =
        (request.requestURI == "/tokens" || request.requestURI.startsWith("/tokens/")) && !tokens.anyActive()

    /** [status] (401) [message]; a `POST /mcp/{server}` goes on with [REFUSAL_ATTRIBUTE] set, for the controller's `-32001`. */
    private fun refuse(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain, message: String,
                       status: HttpStatus = HttpStatus.UNAUTHORIZED) {
        if (request.method == "POST" && request.requestURI.startsWith("/mcp/")) {
            request.setAttribute(REFUSAL_ATTRIBUTE, message)
            return chain.doFilter(request, response)
        }
        refuse(response, status, message)
    }

    private fun refuse(response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(engineJson.writeValueAsString(mapOf("error" to message)))
        response.writer.flush()
    }
}

/**
 * Makes [BearerTokenFilter] a bean of the full application only: Boot registers every `Filter` bean on every path, in
 * its `@Order`, and a `@WebMvcTest` slice does not scan a `@Configuration`.
 */
@Configuration
class BearerTokenFilterConfig {
    @Bean
    fun bearerTokenFilter(sandbox: SmithSandbox, tokens: EngineTokens, access: Access) = BearerTokenFilter(sandbox, tokens, access)
}
