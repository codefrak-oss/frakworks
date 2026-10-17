package com.codefrak.engine.db

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import java.sql.SQLTransientConnectionException

/**
 * A request that could not get a database connection within Hikari's `connection-timeout` answers 503 with
 * `Retry-After`, at once, instead of a 500 or a hang (engine/README.md, "Database pool"; #1650). Any other
 * exception is left to the resolvers after this one. The cause chain is walked because Operaton and Spring
 * both wrap Hikari's [SQLTransientConnectionException].
 */
@Component
class PoolExhausted : HandlerExceptionResolver, Ordered {
    private val log = LoggerFactory.getLogger(PoolExhausted::class.java)

    override fun getOrder() = Ordered.HIGHEST_PRECEDENCE

    override fun resolveException(request: HttpServletRequest, response: HttpServletResponse, handler: Any?, ex: Exception): ModelAndView? {
        if (!isPoolExhausted(ex) || response.isCommitted) return null
        log.warn("{} {}: no database connection, answered 503: {}", request.method, request.requestURI, ex.message)
        response.setHeader("Retry-After", "5")
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "the engine's database pool is exhausted; try again shortly")
        return ModelAndView()
    }

    companion object {
        fun isPoolExhausted(ex: Throwable): Boolean =
            generateSequence(ex) { it.cause.takeIf { c -> c !== it } }.take(20)
                .any { it is CannotGetJdbcConnectionException || it is SQLTransientConnectionException }
    }
}
