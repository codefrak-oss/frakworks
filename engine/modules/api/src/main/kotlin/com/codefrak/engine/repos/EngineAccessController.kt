package com.codefrak.engine.repos

import org.slf4j.LoggerFactory
import jakarta.servlet.http.HttpServletRequest
import com.codefrak.engine.refuseBy
import com.codefrak.engine.tokenLogin
import tools.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class AccessView(val logins: List<TrustedLogin>, val history: List<AccessChange>)

val LOGIN = Regex("[A-Za-z0-9-]{1,39}")

/**
 * The engine-wide list of trusted logins ([Access]; engine/README.md, "Starting a flow from an issue").
 * The audit records the login the request's bearer token proved; a body or query with `by` is 400, as is a body with
 * a `flow`: there are no kinds.
 */
@RestController
class EngineAccessController(private val access: Access) {
    private val log = LoggerFactory.getLogger(EngineAccessController::class.java)

    @GetMapping("/access")
    fun list() = AccessView(access.list(), access.history())

    @PostMapping("/access")
    fun add(@RequestBody body: JsonNode, request: HttpServletRequest? = null): ResponseEntity<Unit> {
        if (!body.isObject) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "the body is {login}")
        if (body.has("flow")) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "access has no kinds: the body is {login}")
        val login = body.get("login")?.takeIf { it.isString }?.asString().orEmpty()
        if (!LOGIN.matches(login)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "login is a GitHub login")
        refuseBy(body.get("by"))
        val by = request.tokenLogin()
        return ResponseEntity.status(if (access.add(login, by)) HttpStatus.CREATED else HttpStatus.OK).build()
    }

    @DeleteMapping("/access/{login}")
    fun remove(@PathVariable login: String, @RequestParam(required = false) by: String?, request: HttpServletRequest? = null): ResponseEntity<Unit> {
        refuseBy(by)
        return if (access.remove(login, request.tokenLogin())) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }
}
