package com.codefrak.engine.accounts

import com.codefrak.engine.engineJson
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * What the provider said about a credential. `valid`: it authenticated (a
 * 429 counts: the token is good, the plan is busy). `reachable`: false when
 * no answer came at all, so nothing was learned about the token.
 */
data class Verdict(val valid: Boolean, val reachable: Boolean, val detail: String)

/** Anthropic's reply to the probe: its HTTP status and headers, the meter among them. */
data class ProbeReply(val status: Int, val headers: Map<String, List<String>>)

/** Anthropic could not be reached, so there is no reply to read; the message never carries the token. */
class ProbeUnreachable(message: String) : RuntimeException(message)

/** Tries a credential against its provider; tests use a fake. */
interface CredentialVerifier {
    fun verify(provider: String, token: String): Verdict

    /**
     * Sends the Anthropic probe, the same request [verify] sends, and returns
     * the reply whatever its status; throws [ProbeUnreachable] when no answer
     * came. `POST /accounts/{name}/usage/refresh` reads the meter off it.
     */
    fun probeAnthropic(token: String): ProbeReply

    /**
     * Tries an Anthropic API key (a one-token `POST /v1/messages`) or Admin API
     * key (`GET /v1/organizations/me`, which costs nothing) before it is stored
     * (engine/docs/ai-accounts.md, "API keys and credit").
     */
    fun verifyKey(kind: ApiKeyKind, key: String): Verdict
}

/**
 * The one request the engine makes of its own to a provider (engine/README.md,
 * "AI accounts"): the smallest that proves a token authenticates. For Anthropic,
 * a one-token message on the cheapest model, sent the way Claude Code sends it
 * with an OAuth token (`claude setup-token`); for OpenAI and Jev (TypeSafe),
 * the model list, which costs nothing (a Jev decision call would spend
 * credits). The token goes nowhere else and is never logged. The Anthropic
 * probe also refreshes an account's usage meter on demand ([probeAnthropic]):
 * one request builder for both, so the two cannot drift apart.
 */
@Component
class HttpCredentialVerifier(
    @Value("\${engine.anthropic.upstream:https://api.anthropic.com}") private val anthropic: String,
    @Value("\${engine.openai.upstream:https://api.openai.com}") private val openai: String,
    @Value("\${engine.jev.upstream:https://api.typesafe.ai}") private val jev: String = "https://api.typesafe.ai",
    /** Where each Anthropic probe's reply `usage` goes (engine/README.md, "Consumption ledger"). */
    private val usage: ClaudeUsageSink = ClaudeUsageSink.NONE,
) : CredentialVerifier {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun verify(provider: String, token: String): Verdict {
        val request = when (provider) {
            "anthropic" -> anthropicProbe(token)
            "openai" -> HttpRequest.newBuilder(URI(openai.trimEnd('/') + "/v1/models"))
                .header("authorization", "Bearer $token").GET()
            "jev" -> HttpRequest.newBuilder(URI(jev.trimEnd('/') + "/v1/models"))
                .header("authorization", "Bearer $token").GET()
            else -> return Verdict(false, true, "no way to verify a $provider credential")
        }.timeout(TIMEOUT).build()
        val sent = System.nanoTime()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            catch (e: Exception) { return Verdict(false, false, "could not reach $provider: ${e.message ?: e.javaClass.simpleName}") }
        if (provider == "anthropic") recordProbe("credential-verify", sent, response.body())
        val status = response.statusCode()
        return when {
            status in 200..299 -> Verdict(true, true, "$provider accepted it")
            status == 429 -> Verdict(true, true, "$provider accepted it, but the account is rate-limited right now (429)")
            status == 401 || status == 403 -> Verdict(false, true, "$provider refused it ($status): ${reason(response.body())}")
            else -> Verdict(false, false, "$provider answered $status, which says nothing about the token: ${reason(response.body())}")
        }
    }

    override fun verifyKey(kind: ApiKeyKind, key: String): Verdict {
        val base = anthropic.trimEnd('/')
        val request = when (kind) {
            ApiKeyKind.API -> HttpRequest.newBuilder(URI("$base/v1/messages")).header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(API_KEY_PROBE))
            ApiKeyKind.ADMIN -> HttpRequest.newBuilder(URI("$base/v1/organizations/me")).GET()
        }.header("x-api-key", key).header("anthropic-version", "2023-06-01").timeout(TIMEOUT).build()
        val sent = System.nanoTime()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            catch (e: Exception) { return Verdict(false, false, "could not reach anthropic: ${e.message ?: e.javaClass.simpleName}") }
        if (kind == ApiKeyKind.API) recordProbe("api-key-verify", sent, response.body())
        val status = response.statusCode()
        return when {
            status in 200..299 -> Verdict(true, true, "anthropic accepted the ${kind.label}")
            status == 429 && kind == ApiKeyKind.API -> Verdict(true, true, "anthropic accepted the ${kind.label}, but it is rate-limited right now (429)")
            status in 400..499 && status != 429 -> Verdict(false, true, "anthropic refused the ${kind.label} ($status): ${reason(response.body())}")
            else -> Verdict(false, false, "anthropic answered $status, which says nothing about the ${kind.label}: ${reason(response.body())}")
        }
    }

    override fun probeAnthropic(token: String): ProbeReply {
        val sent = System.nanoTime()
        val response = try { client.send(anthropicProbe(token).timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString()) }
            catch (e: Exception) { throw ProbeUnreachable("could not reach anthropic: ${e.message ?: e.javaClass.simpleName}") }
        recordProbe("usage-probe", sent, response.body())
        return ProbeReply(response.statusCode(), response.headers().map())
    }

    /** The probe's tokens and model into the ledger; only the reply's `usage` block is read. No cost: the engine has no price table (#1009). */
    private fun recordProbe(caller: String, sentNanos: Long, body: String?) = usage.record(ClaudeUsage(
        caller = caller, model = PROBE_MODEL, ended = java.time.OffsetDateTime.now(),
        durationMs = (System.nanoTime() - sentNanos) / 1_000_000, tokens = ClaudeTokens.ofReply(body),
    ))

    /** The one Anthropic request: [ANTHROPIC_PROBE] on an OAuth bearer, as Claude Code sends it. */
    private fun anthropicProbe(token: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI(anthropic.trimEnd('/') + "/v1/messages"))
            .header("authorization", "Bearer $token")
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "oauth-2025-04-20")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(ANTHROPIC_PROBE))

    /**
     * The provider's own error message, e.g. `invalid x-api-key`: Anthropic and
     * OpenAI put it at error.message, Jev (FastAPI) at detail.message or as a
     * string detail.
     */
    private fun reason(body: String): String = runCatching {
        val parsed = engineJson.readValue<Map<String, Any?>>(body)
        val detail = parsed["detail"]
        listOf((parsed["error"] as? Map<*, *>)?.get("message"), (detail as? Map<*, *>)?.get("message"), detail)
            .firstNotNullOfOrNull { (it as? String)?.ifBlank { null } }
    }.getOrNull() ?: body.take(200).ifBlank { "no reason given" }

    companion object {
        /** Under the desktop app's 20 s, so its answer is the engine's. */
        private val TIMEOUT: Duration = Duration.ofSeconds(15)

        /** A one-token message on the cheapest model. The system line is Claude Code's: an OAuth token is for Claude Code. */
        const val PROBE_MODEL = "claude-haiku-4-5"
        const val ANTHROPIC_PROBE = """{"model":"$PROBE_MODEL","max_tokens":1,""" +
            """"system":"You are Claude Code, Anthropic's official CLI for Claude.",""" +
            """"messages":[{"role":"user","content":"ok"}]}"""

        /** An API key's probe: one token on the cheapest model, with no Claude Code system line (no OAuth here). */
        const val API_KEY_PROBE = """{"model":"$PROBE_MODEL","max_tokens":1,"messages":[{"role":"user","content":"ok"}]}"""
    }
}
