package com.codefrak.engine.firstmate.push

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One push: the APNs path (`/3/device/<token>`), its headers (`authorization`, `apns-topic`, ...) and the JSON payload. */
data class ApnsRequest(val environment: String, val path: String, val headers: Map<String, String>, val payload: String)

/** What APNs answered: delivered, the device token is gone (drop the row), or a failure with its status and reason. */
sealed class ApnsResult {
    data object Sent : ApnsResult()
    data class Gone(val status: Int, val reason: String?) : ApnsResult()
    data class Failed(val status: Int?, val reason: String) : ApnsResult()
}

/** Sends one push to APNs (engine/README.md, "Push notifications"); never throws, a failure is its [ApnsResult]. */
fun interface ApnsClient {
    fun send(request: ApnsRequest): ApnsResult

    companion object {
        /** The reasons that mean the device token is no longer valid for the topic (Apple's "Handling notification responses"). */
        val GONE_REASONS = setOf("Unregistered", "BadDeviceToken", "DeviceTokenNotForTopic", "ExpiredToken")

        /** [status] and APNs's `{"reason"}` [body] classified. */
        fun classify(status: Int, body: String?): ApnsResult {
            if (status == 200) return ApnsResult.Sent
            val reason = runCatching { jacksonObjectMapper().readTree(body ?: "").get("reason")?.asString() }.getOrNull()
            return if (status == 410 || reason in GONE_REASONS) ApnsResult.Gone(status, reason) else ApnsResult.Failed(status, reason ?: "no reason")
        }
    }
}

/**
 * APNs over HTTP/2 with the JDK's own `HttpClient` (no dependency: the engine's JDK speaks HTTP/2 and signs ES256, see
 * [ApnsJwt]). [hosts] maps an environment to its base URL; tests point it at a port that refuses.
 */
@Component
class JdkApnsClient(private val hosts: Map<String, String>) : ApnsClient {
    @Autowired constructor() : this(APPLE)

    companion object {
        val APPLE = mapOf(PushDevices.PRODUCTION to "https://api.push.apple.com", PushDevices.SANDBOX to "https://api.sandbox.push.apple.com")
    }

    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(10)).build()

    override fun send(request: ApnsRequest): ApnsResult {
        val base = hosts[request.environment] ?: return ApnsResult.Failed(null, "no APNs host for environment ${request.environment}")
        return try {
            val builder = HttpRequest.newBuilder(URI.create(base + request.path)).timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(request.payload))
            request.headers.forEach { (k, v) -> builder.header(k, v) }
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            ApnsClient.classify(response.statusCode(), response.body())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); ApnsResult.Failed(null, "interrupted")
        } catch (e: Exception) {
            ApnsResult.Failed(null, "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }
}
