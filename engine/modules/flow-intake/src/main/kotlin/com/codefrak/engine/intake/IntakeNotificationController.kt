package com.codefrak.engine.intake

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** The watcher's report: `by` names the watcher (its host), `result` is what `claude -p` printed, or why it failed. */
data class DeliveredRequest(val by: String, val result: String)

/**
 * The host-side watcher's two calls (engine/README.md, "Intake", "Telling the session that filed it"):
 * what waits to be pushed into a Claude Code session, and that one was. Unauthenticated, like `/intake`.
 */
@RestController
class IntakeNotificationController(private val notifications: IntakeNotifications) {
    /** Every notification not yet delivered, oldest first, each with the `message` the watcher relays verbatim. */
    @GetMapping("/intake/notifications")
    fun pending(): List<IntakeNotification> = notifications.pending()

    /** Records the delivery. 404 for an unknown id, 409 when it was already delivered (so a second watcher stops). */
    @PostMapping("/intake/notifications/{id}/delivered")
    fun delivered(@PathVariable id: Long, @RequestBody body: DeliveredRequest): IntakeNotification {
        val by = body.by.trim().ifEmpty { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "by is blank") }
        notifications.get(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no notification $id")
        if (!notifications.delivered(id, by, body.result)) throw ResponseStatusException(HttpStatus.CONFLICT, "notification $id was already delivered")
        return notifications.get(id)!!
    }
}
