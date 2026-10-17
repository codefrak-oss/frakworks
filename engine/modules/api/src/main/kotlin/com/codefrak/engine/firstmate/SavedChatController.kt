package com.codefrak.engine.firstmate

import com.codefrak.engine.engineJson
import com.codefrak.engine.errorResponse
import com.codefrak.engine.utc
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Saved First mate chats over HTTP (engine/README.md, "Saved First mate chats"). Unauthenticated, like the rest of
 * the engine's API: any desktop pointed at this engine sees every saved chat.
 */
@RestController
class SavedChatController(private val chats: SavedChats) {
    /** Newest saved first, without `messages`. */
    @GetMapping("/saved-chats")
    fun list(): List<Map<String, Any?>> = chats.list().map { row(it) }

    /** One chat with its `messages` in order; 404 `{error}` when unknown. */
    @GetMapping("/saved-chats/{id}")
    fun one(@PathVariable id: String): ResponseEntity<Any> {
        val chat = chats.get(id) ?: return notFound(id)
        return ResponseEntity.ok(row(chat) + mapOf("messages" to engineJson.readValue<List<Any?>>(chat.messages ?: "[]")))
    }

    /**
     * Body: `{title, startedAt, repo?, tier?, claudeSessionId?, savedFrom?, messages}`, `messages` the strip's history
     * (1 to 200 `{role, text, at, images?}`). 201 with the list row when created, 200 when it updated an earlier save;
     * 400 `{error}` and nothing stored for a malformed body, an id that is not a UUID, or a body over 1 MiB.
     */
    @PutMapping("/saved-chats/{id}")
    fun save(@PathVariable id: String, @RequestBody body: String): ResponseEntity<Any> {
        val write = try {
            parse(id, body)
        } catch (e: IllegalArgumentException) {
            return errorResponse(HttpStatus.BAD_REQUEST, e.message)
        }
        val created = chats.save(id, write)
        val stored = chats.list().first { it.id == id }
        return ResponseEntity.status(if (created) HttpStatus.CREATED else HttpStatus.OK).body(row(stored))
    }

    /** 204, or 404 `{error}` when unknown. */
    @DeleteMapping("/saved-chats/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Any> =
        if (chats.delete(id)) ResponseEntity.noContent().build() else notFound(id)

    private fun notFound(id: String): ResponseEntity<Any> = errorResponse(HttpStatus.NOT_FOUND, "no saved chat $id")

    private fun row(chat: SavedChat): Map<String, Any?> = linkedMapOf(
        "id" to chat.id, "title" to chat.title, "startedAt" to chat.startedAt?.let { utc(it) }, "savedAt" to utc(chat.savedAt),
        "repo" to chat.repo, "tier" to chat.tier, "claudeSessionId" to chat.claudeSessionId, "savedFrom" to chat.savedFrom,
        "turns" to chat.turns, "messageCount" to chat.messageCount,
    )

    private fun parse(id: String, body: String): SavedChatWrite {
        require(runCatching { UUID.fromString(id) }.isSuccess && id.length == 36) { "the id is a UUID" }
        require(body.toByteArray().size <= MAX_BODY) { "the body is over 1 MiB" }
        val map = runCatching { engineJson.readValue<Any?>(body) }.getOrNull() as? Map<*, *>
            ?: throw IllegalArgumentException("the body is a JSON object")
        val unknown = map.keys.map { it.toString() } - KEYS
        require(unknown.isEmpty()) { "unknown key(s): ${unknown.joinToString()}" }
        val title = (map["title"] as? String)?.trim()
        require(!title.isNullOrEmpty() && title.length <= 200) { "title is a non-blank string of at most 200 characters" }
        val messages = map["messages"] as? List<*> ?: throw IllegalArgumentException("messages is an array")
        require(messages.size in 1..MAX_MESSAGES) { "messages has 1 to $MAX_MESSAGES entries" }
        messages.forEach { m ->
            require(m is Map<*, *> && m.keys.all { (it as? String) in MESSAGE_KEYS } && (m["role"] as? String) in ROLES && m["text"] is String && m["at"] is Number &&
                (m["images"] == null || whole(m["images"]) != null)) { "each message is {role: user|assistant|system, text, at, images?}" }
        }
        val tier = map["tier"]?.let { whole(it) ?: throw IllegalArgumentException("tier is an integer") }
        return SavedChatWrite(
            title = title, startedAt = map["startedAt"]?.let { at(it) }, repo = optionalString(map, "repo"), tier = tier,
            claudeSessionId = optionalString(map, "claudeSessionId"), savedFrom = optionalString(map, "savedFrom"),
            turns = messages.count { (it as Map<*, *>)["role"] == "user" }, messageCount = messages.size,
            messages = engineJson.writeValueAsString(messages),
        )
    }

    private fun optionalString(map: Map<*, *>, key: String): String? = when (val v = map[key]) {
        null -> null
        is String -> v.ifBlank { null }?.also { require(it.length <= 200) { "$key is at most 200 characters" } }
        else -> throw IllegalArgumentException("$key is a string")
    }

    private companion object {
        const val MAX_BODY = 1024 * 1024
        const val MAX_MESSAGES = 200
        val KEYS = setOf("title", "startedAt", "repo", "tier", "claudeSessionId", "savedFrom", "messages")
        val MESSAGE_KEYS = setOf("role", "text", "at", "images")
        val ROLES = setOf("user", "assistant", "system")

        fun whole(v: Any?): Int? = when (v) {
            is Int -> v
            is Long, is Short, is Byte -> (v as Number).toLong().takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        }

        /** An ISO instant or offset date-time, or epoch milliseconds. */
        fun at(v: Any): OffsetDateTime = when (v) {
            is Number -> Instant.ofEpochMilli(v.toLong()).atOffset(ZoneOffset.UTC)
            is String -> runCatching { OffsetDateTime.parse(v) }.recoverCatching { Instant.parse(v).atOffset(ZoneOffset.UTC) }
                .getOrElse { throw IllegalArgumentException("startedAt is an ISO instant or epoch milliseconds") }
            else -> throw IllegalArgumentException("startedAt is an ISO instant or epoch milliseconds")
        }
    }
}
