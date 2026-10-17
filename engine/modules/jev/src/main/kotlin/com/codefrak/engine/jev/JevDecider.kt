package com.codefrak.engine.jev

import com.codefrak.engine.engineJson
import com.codefrak.engine.accounts.AiAccounts
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One question to Jev, in TypeSafe's shape (https://docs.typesafe.ai/api):
 * `type` noul, choice or score; `instructions` (a string, or an object that
 * refers to the state); `criteria` as the type wants it.
 */
data class JevQuestion(val type: String, val instructions: Any, val criteria: Any? = null) {
    companion object {
        /** Yes or no, answered as `noul` 0 (no) to 1 (yes); `whenTrue`/`whenFalse` describe each side. */
        fun noul(instructions: Any, whenTrue: String? = null, whenFalse: String? = null) = JevQuestion("noul", instructions,
            if (whenTrue == null && whenFalse == null) null else mapOf("true" to whenTrue, "false" to whenFalse))

        /** One of up to 255 named options, each with a description (or null). */
        fun choice(instructions: Any, options: Map<String, String?>) = JevQuestion("choice", instructions, options)

        /** An ordered scale of 2 to 10 described levels. */
        fun score(instructions: Any, levels: List<String>) = JevQuestion("score", instructions, levels)
    }
}

/**
 * One answer: `noul` for a noul question; `choice`, its `probabilities` and
 * `confidence` for a choice; `score` (between levels), its `probabilities`
 * by level index and `confidence` for a score.
 */
data class JevAnswer(
    val type: String, val noul: Double? = null, val choice: String? = null, val score: Double? = null,
    val probabilities: Map<String, Double> = emptyMap(), val confidence: Double? = null,
)

/** Where a call comes from, for its `engine.jev_call` row. */
data class JevContext(val processInstance: String?, val repo: String?, val issue: Long?)

/** The call's `engine.jev_call` id, and the answers by question key: null for "no answer", which the caller's rule covers. */
data class JevDecision(val callId: String, val answers: Map<String, JevAnswer>?)

/** Asks Jev; tests use a fake. Never throws for Jev's sake: any failure is a decision with no answers. */
interface JevDecider {
    fun decide(useCase: String, context: JevContext, state: Any, questions: Map<String, JevQuestion>, threshold: Double?): JevDecision
}

/**
 * The engine's Jev client (engine/README.md, "Jev accounts"): `POST
 * {engine.jev.upstream}/v1/systemone` with the model pinned
 * (`engine.jev.model`), billed to [AiAccounts.jevCredential]. Each attempt
 * times out after `engine.jev.timeout-ms`; a 429 or 529 is tried again after
 * `engine.jev.backoff-ms`, doubling, up to `engine.jev.attempts` in all. Any
 * other status, a timeout, an unreadable answer or no usable jev account is
 * no answer. Every call writes one `engine.jev_call` row ([JevCalls]), with
 * the request as sent and never the key.
 */
@Component
class HttpJevDecider(
    private val accounts: AiAccounts,
    private val calls: JevCalls,
    @Value("\${engine.jev.upstream:https://api.typesafe.ai}") private val upstream: String,
    @Value("\${engine.jev.model:jev-1.13.0}") private val model: String,
    @Value("\${engine.jev.timeout-ms:5000}") private val timeoutMs: Long,
    @Value("\${engine.jev.attempts:3}") private val attempts: Int,
    @Value("\${engine.jev.backoff-ms:500}") private val backoffMs: Long,
) : JevDecider {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build()

    companion object {
        /** The call row's `error` when no enabled, credentialed jev account exists: no attempt is made. A caller that waits for an answer otherwise (the friction grouper) reads it to stop waiting. */
        const val NO_ACCOUNT = "no usable jev account"
    }

    override fun decide(useCase: String, context: JevContext, state: Any, questions: Map<String, JevQuestion>, threshold: Double?): JevDecision {
        val request = engineJson.writeValueAsString(mapOf("model" to model, "state" to state, "questions" to questions.mapValues { (_, q) ->
            buildMap { put("type", q.type); put("instructions", q.instructions); q.criteria?.let { put("criteria", it) } }
        }))
        val key = runCatching { accounts.jevCredential() }.getOrNull()
            ?: return JevDecision(calls.record(useCase, context, null, model, request, null, NO_ACCOUNT, null, threshold), null)
        val started = System.nanoTime()
        var failure = ""
        var attempt = 1
        while (true) {
            val response = try {
                client.send(HttpRequest.newBuilder(URI(upstream.trimEnd('/') + "/v1/systemone"))
                    .header("authorization", "Bearer ${key.key}").header("content-type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs)).POST(HttpRequest.BodyPublishers.ofString(request)).build(),
                    HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                failure = "no answer: ${e.message ?: e.javaClass.simpleName}"; break
            }
            val status = response.statusCode()
            if (status in 200..299) {
                val latency = (System.nanoTime() - started) / 1_000_000
                val parsed = runCatching { parse(response.body()) }
                val answers = parsed.getOrNull()?.second
                val answered = parsed.getOrNull()?.first ?: model
                val error = if (answers == null) "unreadable answer: ${parsed.exceptionOrNull()?.message ?: "no answers"}" else null
                val id = calls.record(useCase, context, key.account, answered, request, response.body(), error, latency, threshold)
                return JevDecision(id, answers)
            }
            failure = "jev answered $status: ${response.body().take(500)}"
            if ((status != 429 && status != 529) || attempt >= attempts) break
            Thread.sleep(backoffMs shl (attempt - 1))
            attempt++
        }
        val latency = (System.nanoTime() - started) / 1_000_000
        log.warn("jev {} call for {}#{} gave no answer after {} attempt(s): {}", useCase, context.repo, context.issue, attempt, failure.take(200))
        return JevDecision(calls.record(useCase, context, key.account, model, request, null, failure, latency, threshold), null)
    }

    /** The model that answered and the answers by key; throws on a body that is not Jev's answer. */
    private fun parse(body: String): Pair<String?, Map<String, JevAnswer>> {
        val parsed = engineJson.readValue<Map<String, Any?>>(body)
        val answers = parsed["answers"] as? Map<*, *> ?: error("no answers in the body")
        return parsed["model"] as? String to answers.entries.associate { (k, v) ->
            val a = v as? Map<*, *> ?: error("answer $k is not an object")
            k.toString() to JevAnswer(
                type = a["type"] as? String ?: error("answer $k has no type"),
                noul = (a["noul"] as? Number)?.toDouble(),
                choice = a["choice"] as? String,
                score = (a["score"] as? Number)?.toDouble(),
                probabilities = (a["probabilities"] as? Map<*, *>).orEmpty().entries
                    .mapNotNull { (o, p) -> (p as? Number)?.let { o.toString() to it.toDouble() } }.toMap(),
                confidence = (a["confidence"] as? Number)?.toDouble(),
            ).also { if (it.type == "noul" && it.noul == null) error("noul answer $k has no noul") }
        }
    }
}
