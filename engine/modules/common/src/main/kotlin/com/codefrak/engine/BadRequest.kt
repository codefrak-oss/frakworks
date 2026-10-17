package com.codefrak.engine

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ResponseStatusException

/** A 400 with a plain reason string: shared by the controllers that otherwise each defined their own `bad(...)`. */
fun badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)

/** A JSON `{"error": ...}` response: shared by the controllers that otherwise each defined their own `error(...)`. */
fun errorResponse(status: HttpStatus, message: String?): ResponseEntity<Any> =
    ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(mapOf("error" to message))

/** 400 for a key outside `allowed`, naming the fields a caller may send. */
fun refuseUnknownFields(keys: Collection<String>, allowed: Set<String>, only: String) {
    val unknown = keys - allowed
    if (unknown.isNotEmpty()) throw badRequest("unknown field(s): ${unknown.joinToString(", ")}; only $only")
}
