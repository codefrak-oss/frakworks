package com.codefrak.engine

import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.repos.TokenResolution
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.web.util.WebUtils
import java.time.OffsetDateTime

/**
 * The bearer token a test request carries now that a request that is no Smith's needs one (BearerTokenFilter;
 * engine/README.md, "Tokens"). Two ways in:
 *
 * - Over MockMvc, every request gets `Authorization: Bearer` for [DEFAULT_LOGIN] ([Config]'s filter), unless
 *   it carries its own `Authorization` header (`bearer(login)` for another login) or the [NO_TOKEN] attribute (a test of
 *   the refusal or of the bootstrap door). The token row and the login's trust are written when missing, so a test that
 *   empties `engine.engine_token` or `engine.engine_access` still gets one on its next request.
 * - A controller called directly gets [asLogin]: the request as the filter leaves it for a valid token.
 */
object TestTokens {
    const val DEFAULT_LOGIN = "lundmatt1"
    /** Request attribute: send this MockMvc request without the default token. */
    const val NO_TOKEN = "test.no-token"

    fun tokenOf(login: String) = "frk_test-${login.lowercase()}"

    /** Writes the token of [login] and, with [trust], trusts the login, when either is missing; answers the token. */
    fun ensure(jdbc: JdbcClient, tokens: EngineTokens, access: Access, login: String, trust: Boolean = true): String {
        if (trust && !access.trusted(login)) access.add(login, "test")
        val token = tokenOf(login)
        if (tokens.resolve(token) !is TokenResolution.Ok) {
            jdbc.sql("DELETE FROM engine.engine_token WHERE hash = :h").param("h", EngineTokens.hash(token)).update()
            jdbc.sql("""INSERT INTO engine.engine_token (id, login, name, prefix, hash, created_at, created_by, expires_at)
                VALUES (:id, :login, 'test', :prefix, :hash, :now, :login, NULL)""")
                .param("id", "test-${login.lowercase()}").param("login", login).param("prefix", EngineTokens.prefixOf(token))
                .param("hash", EngineTokens.hash(token)).param("now", OffsetDateTime.now()).update()
        }
        return token
    }

    /** A direct controller call's request, as the bearer filter leaves it for a valid token of [login]. */
    fun asLogin(login: String = DEFAULT_LOGIN, from: String = "127.0.0.1"): HttpServletRequest = MockHttpServletRequest().apply {
        remoteAddr = from
        setAttribute(TOKEN_LOGIN_ATTRIBUTE, login)
        setAttribute(TOKEN_ID_ATTRIBUTE, "test-${login.lowercase()}")
    }

    /** A MockMvc request with no token at all. */
    val noToken = RequestPostProcessor { it.setAttribute(NO_TOKEN, true); it }

    /**
     * Scanned with the application in every full-context test: the default token on every MockMvc request. A filter, not
     * MockMvc's default request: a default request's processors run before the request's own (`noToken`, `bearer`), so
     * only a filter, run at perform time, sees what the request carries. Ordered before [BearerTokenFilter]; a request
     * that is no MockMvc mock (a test on a real port) is left alone.
     */
    @Configuration
    class Config {
        @Bean
        fun testBearerToken(jdbc: JdbcClient, tokens: EngineTokens, access: Access): Filter {
            beans = Triple(jdbc, tokens, access)
            return DefaultToken(jdbc, tokens, access)
        }
    }

    /** Adds `Authorization: Bearer` for [DEFAULT_LOGIN] to a MockMvc request that has none and no [NO_TOKEN]. */
    class DefaultToken(private val jdbc: JdbcClient, private val tokens: EngineTokens, private val access: Access) : Filter, Ordered {
        override fun getOrder() = Ordered.HIGHEST_PRECEDENCE
        override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
            val mock = WebUtils.getNativeRequest(request, MockHttpServletRequest::class.java)
            if (mock != null && mock.getHeader("Authorization") == null && mock.getAttribute(NO_TOKEN) == null)
                mock.addHeader("Authorization", "Bearer ${ensure(jdbc, tokens, access, DEFAULT_LOGIN)}")
            chain.doFilter(request, response)
        }
    }

    private var beans: Triple<JdbcClient, EngineTokens, Access>? = null

    /** `Authorization: Bearer` for [login] on a MockMvc request of a full-context test, its token written when missing. */
    fun bearer(login: String, trust: Boolean = true): RequestPostProcessor = RequestPostProcessor { request ->
        val (jdbc, tokens, access) = checkNotNull(beans) { "no full-context MockMvc built yet" }
        bearer(jdbc, tokens, access, login, trust).postProcessRequest(request)
    }

    /** A standalone MockMvc request (no filter) as the bearer filter leaves it for a valid token of [login]. */
    fun proven(login: String): RequestPostProcessor = RequestPostProcessor { request ->
        request.setAttribute(TOKEN_LOGIN_ATTRIBUTE, login); request.setAttribute(TOKEN_ID_ATTRIBUTE, "test-${login.lowercase()}"); request
    }

    /** `Authorization: Bearer` for [login], replacing any the request carries. */
    fun bearer(jdbc: JdbcClient, tokens: EngineTokens, access: Access, login: String, trust: Boolean = true) = RequestPostProcessor { request ->
        request.removeHeader("Authorization")
        request.addHeader("Authorization", "Bearer ${ensure(jdbc, tokens, access, login, trust)}")
        request
    }
}
