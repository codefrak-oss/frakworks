package com.codefrak.engine.summarize

import com.codefrak.engine.engineJson
import com.codefrak.engine.accounts.ClaudeTokens
import com.codefrak.engine.accounts.ClaudeUsage
import com.codefrak.engine.accounts.ClaudeUsageSink
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime

/**
 * One Anthropic `/v1/messages` call per summary, on `engine.summarize.model` (default
 * `claude-haiku-4-5`), sent the way [com.codefrak.engine.accounts.HttpCredentialVerifier]
 * sends it: an OAuth bearer, the oauth beta header, and Claude Code's system
 * line first. The digest and its last turns go as two marked sections; the
 * model answers JSON `{"summary","now"}`, and the word limits are
 * enforced on what comes back. The token goes nowhere else and is never logged.
 */
@Component
class HttpSessionSummarizer(
    private val credential: SummaryCredential,
    @Value("\${engine.anthropic.upstream:https://api.anthropic.com}") private val upstream: String,
    @Value("\${engine.summarize.model:claude-haiku-4-5}") private val model: String,
    /** Where each answered call's `usage` goes (engine/README.md, "Consumption ledger"). */
    private val usage: ClaudeUsageSink = ClaudeUsageSink.NONE,
) : SessionSummarizer {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun summarize(context: String, digest: String, recent: String): SessionSummary {
        val token = credential.anthropic()
            ?: throw SummaryUnavailable("no enabled anthropic account with a credential to summarize with")
        val request = HttpRequest.newBuilder(URI(upstream.trimEnd('/') + "/v1/messages"))
            .header("authorization", "Bearer $token")
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "oauth-2025-04-20")
            .header("content-type", "application/json")
            .timeout(Duration.ofSeconds(15)) // under the desktop app's 20 s, so its answer is the engine's
            .POST(HttpRequest.BodyPublishers.ofString(body(context, digest, recent))).build()
        val sent = System.nanoTime()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            catch (e: Exception) { throw SummaryFailed("could not reach anthropic: ${e.message ?: e.javaClass.simpleName}") }
        // No cost: the engine has no price table (#1009).
        usage.record(ClaudeUsage(caller = "summarizer", model = model, ended = OffsetDateTime.now(),
            durationMs = (System.nanoTime() - sent) / 1_000_000, tokens = ClaudeTokens.ofReply(response.body())))
        if (response.statusCode() !in 200..299) throw SummaryFailed("anthropic answered ${response.statusCode()}: ${reason(response.body())}")
        return parse(response.body()).limited()
    }

    /** The request body: [CLAUDE_CODE] then [PROMPT] as the system; the context, digest and [RECENT] section as the one user turn. */
    fun body(context: String, digest: String, recent: String): String = engineJson.writeValueAsString(linkedMapOf(
        "model" to model,
        "max_tokens" to 400,
        "system" to listOf(mapOf("type" to "text", "text" to CLAUDE_CODE), mapOf("type" to "text", "text" to PROMPT)),
        "messages" to listOf(mapOf("role" to "user", "content" to "$context\n\nThe session so far:\n$digest\n\n$RECENT\n$recent")),
    ))

    private fun parse(body: String): SessionSummary {
        val text = runCatching {
            (engineJson.readValue<Map<String, Any?>>(body)["content"] as List<*>)
                .mapNotNull { (it as? Map<*, *>)?.takeIf { c -> c["type"] == "text" }?.get("text") as? String }.joinToString("")
        }.getOrNull() ?: throw SummaryFailed("anthropic answered no text")
        val answer = runCatching { engineJson.readValue<Map<String, Any?>>(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)) }
            .getOrNull() ?: throw SummaryFailed("the model did not answer JSON: ${text.take(200)}")
        val summary = answer["summary"] as? String
        val now = answer["now"] as? String
        if (summary.isNullOrBlank() || now.isNullOrBlank()) throw SummaryFailed("the model's answer lacks summary or now: ${text.take(200)}")
        return SessionSummary(summary, now)
    }

    private fun reason(body: String): String = runCatching {
        ((engineJson.readValue<Map<String, Any?>>(body)["error"] as Map<*, *>)["message"] as String)
    }.getOrNull() ?: body.take(200).ifBlank { "no reason given" }

    companion object {
        /** An OAuth token is for Claude Code: its system line goes first. */
        const val CLAUDE_CODE = "You are Claude Code, Anthropic's official CLI for Claude."

        /** What the model is asked; a test asserts this is what is sent. */
        const val PROMPT = """You are watching an autonomous coding agent (a "Smith") work a ticket, and you tell a person watching it what it is up to.
You get a digest of its session so far: what it said, and the tools it called, oldest first. After it, a section headed "The most recent turns:" repeats its last few steps.
Explain at a high level what is being done and why, in plain words for someone who knows the project but not the code.
Never list file names, paths, functions or individual edits: write "it is adding a database table for sessions", not "the Smith modified FooBarBaz.kt".
Answer with JSON only, no prose around it: {"summary": "...", "now": "..."}
- "summary": at most 20 words, the whole session so far: what it is doing and why.
- "now": fewer than 15 words, what it is working on at this moment, answered from the most recent turns only."""

        /** The heading of the request's last-turns section, which `now` is answered from. */
        const val RECENT = "The most recent turns:"
    }
}
