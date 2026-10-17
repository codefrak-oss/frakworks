package com.codefrak.engine.github

import com.codefrak.engine.promLabel
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpResponse
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * [GitHubCallMeter] rendered on `GET /metrics` as `frak_github_requests_total{caller,endpoint,status}` and
 * `frak_github_ratelimit_{remaining,limit,reset_seconds}{repo,resource}`, so the budget each part of the engine
 * spends, and what is left of it, is a Grafana query.
 */
@Component
class PrometheusGitHubCallMeter : GitHubCallMeter {
    private data class Call(val caller: String, val endpoint: String, val status: String)
    private data class Budget(val remaining: Long, val limit: Long, val reset: Long)

    private val calls = ConcurrentHashMap<Call, LongAdder>()
    private val budgets = ConcurrentHashMap<Pair<String, String>, Budget>()

    override fun intercept(request: HttpRequest, body: ByteArray, execution: ClientHttpRequestExecution): ClientHttpResponse {
        val caller = caller()
        val endpoint = endpoint(request.method.name(), request.uri.path)
        val response = try { execution.execute(request, body) } catch (e: Exception) {
            count(caller, endpoint, "error"); throw e
        }
        count(caller, endpoint, response.statusCode.value().toString())
        val h = response.headers
        val remaining = h.getFirst("X-RateLimit-Remaining")?.toLongOrNull()
        val limit = h.getFirst("X-RateLimit-Limit")?.toLongOrNull()
        if (remaining != null && limit != null) {
            budgets[repoOf(request.uri.path) to (h.getFirst("X-RateLimit-Resource") ?: "core")] =
                Budget(remaining, limit, h.getFirst("X-RateLimit-Reset")?.toLongOrNull() ?: 0)
        }
        return response
    }

    private fun count(caller: String, endpoint: String, status: String) =
        calls.computeIfAbsent(Call(caller, endpoint, status)) { LongAdder() }.increment()

    override fun render(): String = buildString {
        if (calls.isNotEmpty()) {
            append("# HELP frak_github_requests_total GitHub REST calls the engine made, by the engine class that made it, endpoint and status.\n")
            append("# TYPE frak_github_requests_total counter\n")
            for ((c, n) in calls.entries.sortedWith(compareBy({ it.key.caller }, { it.key.endpoint }, { it.key.status }))) {
                append("frak_github_requests_total{caller=\"").append(promLabel(c.caller)).append("\",endpoint=\"").append(promLabel(c.endpoint))
                    .append("\",status=\"").append(c.status).append("\"} ").append(n.sum()).append('\n')
            }
        }
        if (budgets.isNotEmpty()) {
            val sorted = budgets.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            for ((name, help, value) in listOf(
                Triple("remaining", "Requests left in the token's rate-limit window, from GitHub's last answer.", { b: Budget -> b.remaining }),
                Triple("limit", "The token's requests per window, from GitHub's last answer.", { b: Budget -> b.limit }),
                Triple("reset_seconds", "When the token's window resets, unix seconds, from GitHub's last answer.", { b: Budget -> b.reset }),
            )) {
                append("# HELP frak_github_ratelimit_").append(name).append(' ').append(help).append('\n')
                append("# TYPE frak_github_ratelimit_").append(name).append(" gauge\n")
                for ((k, b) in sorted) {
                    append("frak_github_ratelimit_").append(name).append("{repo=\"").append(promLabel(k.first))
                        .append("\",resource=\"").append(promLabel(k.second)).append("\"} ").append(value(b)).append('\n')
                }
            }
        }
    }

    companion object {
        private val HTTP_LAYER = setOf("com.codefrak.engine.github.PrometheusGitHubCallMeter", "com.codefrak.engine.github.RestGitHub",
            "com.codefrak.engine.github.RestRepoSetupGitHub", "com.codefrak.engine.ci.RestCheckRuns")

        /** The first engine class on the stack above the GitHub clients: who spent the call. */
        fun caller(): String = StackWalker.getInstance().walk { frames ->
            frames.map { it.className }
                .filter { it.startsWith("com.codefrak.") && it.substringBefore('$') !in HTTP_LAYER }
                .findFirst().orElse(null)
        }?.substringAfterLast('.')?.substringBefore('$') ?: "unknown"

        private val REPO = Regex("^/repos/([^/]+/[^/]+)")

        /** `METHOD /path` with the repo prefix cut and numbers and commit SHAs as `:n` and `:sha`. */
        fun endpoint(method: String, path: String): String {
            val rest = path.replace(REPO, "").ifEmpty { "/" }
                .replace(Regex("/[0-9a-f]{40}(?=/|$)"), "/:sha")
                .replace(Regex("/\\d+(?=/|$)"), "/:n")
            return "$method $rest"
        }

        fun repoOf(path: String): String = REPO.find(path)?.groupValues?.get(1) ?: "-"
    }
}
