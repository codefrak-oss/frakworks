package com.codefrak.engine.accounts

import com.codefrak.engine.engineJson
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [TitleSummarizer] as one short Anthropic Messages call on an existing AI account
 * ([AiAccounts.peek], so the Smiths' rotation is unaffected), sent the way
 * [HttpCredentialVerifier] sends its probe. [token] gives the account's credential, or null.
 */
class AnthropicTitles(
    private val upstream: String,
    private val timeout: Duration,
    private val token: () -> String?,
) : TitleSummarizer {
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    override fun summarize(ask: String): TitleAnswer {
        val credential = runCatching { token() }.getOrNull() ?: return TitleAnswer(null, "no usable anthropic account")
        val request = engineJson.writeValueAsString(mapOf(
            "model" to MODEL, "max_tokens" to 40,
            "system" to "You are Claude Code, Anthropic's official CLI for Claude.",
            "messages" to listOf(mapOf("role" to "user", "content" to "$PROMPT\n\n<ask>\n$ask\n</ask>")),
        ))
        val response = try {
            client.send(HttpRequest.newBuilder(URI(upstream.trimEnd('/') + "/v1/messages"))
                .header("authorization", "Bearer $credential")
                .header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", "oauth-2025-04-20")
                .header("content-type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(request)).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) { return TitleAnswer(null, "anthropic unreachable: ${e.message ?: e.javaClass.simpleName}") }
        if (response.statusCode() != 200) return TitleAnswer(null, "anthropic answered ${response.statusCode()}")
        val text = runCatching {
            val content = engineJson.readValue<Map<String, Any?>>(response.body())["content"] as? List<*>
            content.orEmpty().mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("")
        }.getOrNull()
        return sanitize(text)?.let { TitleAnswer(it, null) } ?: TitleAnswer(null, "an empty answer")
    }

    companion object {
        const val MODEL = "claude-haiku-4-5"
        const val PROMPT = "Write a title of at most 70 characters naming the change a user of the product would see, " +
            "not the implementation. Answer with the title alone."

        /** The answer's first non-empty line, without a `Title:` label, quotes or trailing punctuation; null when nothing is left. */
        fun sanitize(text: String?): String? {
            var line = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
            line = line.removePrefix("Title:").trim()
            while (true) {
                val next = line.trim('"', '\'', '`', '*', '“', '”').trim().trimEnd('.', '!', ';', ':').trim()
                if (next == line) break
                line = next
            }
            return line.ifEmpty { null }
        }
    }
}
