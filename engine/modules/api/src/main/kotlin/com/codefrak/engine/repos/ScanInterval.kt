package com.codefrak.engine.repos

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

val INTERVAL_REASON = "scanIntervalMinutes is a whole number of minutes from ${SCAN_INTERVAL_RANGE.first} to ${SCAN_INTERVAL_RANGE.last} (24 hours)"

/**
 * `scanIntervalMinutes` from a PATCH body (or the frakworks MCP server's add_repo and edit_repo): a JSON integer in
 * [SCAN_INTERVAL_RANGE], else a 400. A fraction (2.5), a string, a boolean or null is refused; so is 2.0, which JSON
 * cannot tell from a fraction once parsed as a double.
 */
fun scanMinutes(value: Any?): Int {
    val whole = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is java.math.BigInteger -> value.toLong()
        else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, INTERVAL_REASON)
    }
    if (whole < SCAN_INTERVAL_RANGE.first || whole > SCAN_INTERVAL_RANGE.last) throw ResponseStatusException(HttpStatus.BAD_REQUEST, INTERVAL_REASON)
    return whole.toInt()
}
