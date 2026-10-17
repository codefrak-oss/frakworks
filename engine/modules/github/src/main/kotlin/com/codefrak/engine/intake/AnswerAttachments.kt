package com.codefrak.engine.intake

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** One attachment's listing: its name, type and size in bytes. */
data class AttachmentInfo(val name: String, val mime: String, val size: Int, val createdAt: OffsetDateTime, val createdBy: String)

/** An attachment refused by [AnswerAttachments.check]: [tooLarge] for a cap (413), else a type (415) or a name (400). */
class AttachmentRefused(message: String, val tooLarge: Boolean = false, val badType: Boolean = false) : RuntimeException(message)

/**
 * Answer attachments (engine/README.md, "Answer attachments"): files the user attaches to an answer, `engine.answer_attachment`,
 * keyed by the root flow that asked. The flow's Smith reads them through the frakworks MCP server; nothing here reaches GitHub.
 * Text (UTF-8) and the images [IMAGE_TYPES] are taken; any other binary is refused, as is a file over [MAX_BYTES] or a flow
 * whose files would pass [MAX_FLOW_BYTES].
 */
@Repository
class AnswerAttachments(private val jdbc: JdbcClient) {
    companion object {
        /** A file's name: letters, digits, `.`, `_`, `-` and spaces, not only dots. */
        val NAME = Regex("""^(?!\.+$)[A-Za-z0-9._ -]{1,100}$""")
        const val MAX_BYTES = 1024 * 1024
        const val MAX_FLOW_BYTES = 5 * 1024 * 1024
        val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        /** Non-`text/...` types that are text. */
        val TEXT_TYPES = setOf("application/json", "application/xml", "application/yaml", "application/x-yaml", "application/toml")

        fun isImage(mime: String) = mime in IMAGE_TYPES

        /** The bytes as UTF-8 text, or null when they are not valid UTF-8 or hold a NUL. */
        fun textOf(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().takeIf { '\u0000' !in it }
        } catch (_: CharacterCodingException) { null }

        /**
         * The type it is stored as: an image type as given, else text (`text/plain` when none or a generic binary type
         * was given) when the bytes are UTF-8 text. Throws [AttachmentRefused] for a bad name, a cap or a binary.
         */
        fun check(name: String, mime: String?, bytes: ByteArray): String {
            if (!NAME.matches(name)) throw AttachmentRefused("not a file name: \"$name\" (letters, digits, '.', '_', '-' and spaces, at most 100)")
            if (bytes.size > MAX_BYTES) throw AttachmentRefused("$name is ${bytes.size} bytes: an attachment holds at most $MAX_BYTES bytes (1 MiB)", tooLarge = true)
            val type = mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (type != null && isImage(type)) return type
            if (type != null && type.startsWith("image/"))
                throw AttachmentRefused("$name is $type: an image must be one of ${IMAGE_TYPES.sorted().joinToString(", ")}", badType = true)
            if (textOf(bytes) == null)
                throw AttachmentRefused("$name is not UTF-8 text: only text files and ${IMAGE_TYPES.sorted().joinToString(", ")} images are taken", badType = true)
            return if (type == null || type == "application/octet-stream" || !(type.startsWith("text/") || type in TEXT_TYPES)) "text/plain" else type
        }
    }

    fun list(flow: String): List<AttachmentInfo> =
        jdbc.sql("SELECT name, mime, size, created_at, created_by FROM engine.answer_attachment WHERE flow = :f ORDER BY name")
            .param("f", flow)
            .query { rs, _ -> AttachmentInfo(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getObject(4, OffsetDateTime::class.java), rs.getString(5)) }.list()

    /** The file's type and bytes, or null when the flow has none by that name. */
    fun read(flow: String, name: String): Pair<String, ByteArray>? =
        jdbc.sql("SELECT mime, content FROM engine.answer_attachment WHERE flow = :f AND name = :n").param("f", flow).param("n", name)
            .query { rs, _ -> rs.getString(1) to rs.getBytes(2) }.optional().orElse(null)

    /** Checks and puts the file against [flow], replacing one by the same name; answers its stored listing. */
    fun save(flow: String, name: String, mime: String?, bytes: ByteArray, by: String): AttachmentInfo {
        val type = check(name, mime, bytes)
        val others = list(flow).filter { it.name != name }.sumOf { it.size.toLong() }
        if (others + bytes.size > MAX_FLOW_BYTES)
            throw AttachmentRefused("the flow's attachments would be ${others + bytes.size} bytes: at most $MAX_FLOW_BYTES (5 MiB) per flow", tooLarge = true)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        jdbc.sql("DELETE FROM engine.answer_attachment WHERE flow = :f AND name = :n").param("f", flow).param("n", name).update()
        jdbc.sql("INSERT INTO engine.answer_attachment (flow, name, mime, size, content, created_at, created_by) VALUES (:f, :n, :m, :s, :c, :t, :b)")
            .param("f", flow).param("n", name).param("m", type).param("s", bytes.size).param("c", bytes).param("t", now).param("b", by).update()
        return AttachmentInfo(name, type, bytes.size, now, by)
    }
}
