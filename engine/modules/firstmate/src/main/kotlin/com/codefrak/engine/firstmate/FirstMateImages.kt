package com.codefrak.engine.firstmate

import com.codefrak.engine.badRequest
import java.util.Base64

/**
 * The images a message to a first-mate session may carry (engine/README.md, "First-mate sessions"): the desktop's
 * allowlist (`observability/desktop/src/first-mate-images.js`) as the engine enforces it on `POST
 * /first-mate/sessions/{id}/messages`, with the same wording. An image is `{media_type, data}`, `data` the base64 of
 * the bytes: one of [TYPES], at most [MAX_BYTES] decoded, at most [MAX_COUNT] per message, and its first bytes those
 * of its declared type.
 */
object FirstMateImages {
    val TYPES: List<String> = listOf("image/png", "image/jpeg", "image/gif", "image/webp")
    const val MAX_BYTES: Long = 5L * 1024 * 1024
    const val MAX_COUNT = 5
    private val BASE64 = Regex("^[A-Za-z0-9+/]*={0,2}$")

    /** Decoded byte length of a base64 string, without decoding it. */
    fun decodedBytes(b64: String): Long {
        val pad = if (b64.endsWith("==")) 2 else if (b64.endsWith("=")) 1 else 0
        return b64.length.toLong() * 3 / 4 - pad
    }

    private fun mb(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))

    /** Whether the decoded [head] (the first bytes) starts as a file of [type] does. */
    private fun magic(type: String, head: ByteArray): Boolean {
        fun starts(vararg b: Int) = head.size >= b.size && b.indices.all { head[it] == b[it].toByte() }
        return when (type) {
            "image/png" -> starts(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0d, 0x0a, 0x1a, 0x0a)
            "image/jpeg" -> starts(0xff, 0xd8, 0xff)
            "image/gif" -> head.size >= 6 && String(head, 0, 6, Charsets.ISO_8859_1).let { it == "GIF87a" || it == "GIF89a" }
            "image/webp" -> head.size >= 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP"
            else -> false
        }
    }

    /** [image]'s refusal, or null when it may go; [index] is only for the message. */
    fun error(image: Any?, index: Int = 0): String? {
        val n = "image ${index + 1}"
        val m = image as? Map<*, *> ?: return "$n is not an image"
        val type = m["media_type"] as? String
        if (type == null || type !in TYPES) return "$n is ${type?.takeIf { it.isNotEmpty() } ?: "of no type"}; only PNG, JPEG, GIF and WebP are accepted"
        val data = m["data"] as? String
        if (data.isNullOrEmpty() || data.length % 4 != 0 || !BASE64.matches(data)) return "$n is not base64 data"
        val bytes = decodedBytes(data)
        if (bytes > MAX_BYTES) return "$n is ${mb(bytes)}; the limit is ${mb(MAX_BYTES)}"
        val head = try { Base64.getDecoder().decode(data.substring(0, minOf(24, data.length))) } catch (e: IllegalArgumentException) { ByteArray(0) }
        if (!magic(type, head)) return "$n is not a ${type.substring(6).uppercase()} file; only PNG, JPEG, GIF and WebP are accepted"
        return null
    }

    /**
     * The message's images as `{media_type, data}` maps, each checked; empty for null. Anything else is a 400 with
     * the desktop's wording.
     */
    fun validate(images: Any?): List<Map<String, String>> {
        if (images == null) return emptyList()
        val list = images as? List<*> ?: throw badRequest("images must be a list")
        if (list.size > MAX_COUNT) throw badRequest("${list.size} images; at most $MAX_COUNT go with one message")
        list.forEachIndexed { i, im -> error(im, i)?.let { throw badRequest(it) } }
        return list.map { im -> (im as Map<*, *>).let { mapOf("media_type" to it["media_type"] as String, "data" to it["data"] as String) } }
    }
}
