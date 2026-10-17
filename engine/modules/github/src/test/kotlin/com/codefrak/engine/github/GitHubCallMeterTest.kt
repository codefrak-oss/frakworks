package com.codefrak.engine.github

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [GitHubCallMeter]: each call counted by caller, endpoint and status; the rate-limit headers kept per repo. */
class GitHubCallMeterTest {
    private val meter = PrometheusGitHubCallMeter()

    private fun call(method: HttpMethod, path: String, status: HttpStatus, remaining: String? = "4990") {
        val request = MockClientHttpRequest(method, URI("https://api.github.com$path"))
        meter.intercept(request, ByteArray(0)) { _, _ ->
            MockClientHttpResponse(ByteArray(0), status).apply {
                remaining?.let {
                    headers.add("X-RateLimit-Remaining", it); headers.add("X-RateLimit-Limit", "5000")
                    headers.add("X-RateLimit-Reset", "1791340000"); headers.add("X-RateLimit-Resource", "core")
                }
            }
        }
    }

    @Test
    fun `endpoints drop the repo and number the ids and shas`() {
        assertEquals("GET /issues/:n/comments", PrometheusGitHubCallMeter.endpoint("GET", "/repos/acme/tickets/issues/42/comments"))
        assertEquals("GET /commits/:sha/check-runs",
            PrometheusGitHubCallMeter.endpoint("GET", "/repos/acme/tickets/commits/0123456789abcdef0123456789abcdef01234567/check-runs"))
        assertEquals("GET /user", PrometheusGitHubCallMeter.endpoint("GET", "/user"))
        assertEquals("acme/tickets", PrometheusGitHubCallMeter.repoOf("/repos/acme/tickets/pulls/7"))
        assertEquals("-", PrometheusGitHubCallMeter.repoOf("/user"))
    }

    @Test
    fun `calls are counted by caller, endpoint and status, and the last rate-limit answer is kept`() {
        call(HttpMethod.GET, "/repos/acme/tickets/issues/1", HttpStatus.OK, "4999")
        call(HttpMethod.GET, "/repos/acme/tickets/issues/2", HttpStatus.OK, "4998")
        call(HttpMethod.POST, "/repos/acme/tickets/issues/2/comments", HttpStatus.FORBIDDEN, "0")
        val text = meter.render()

        // The caller is this test class: the first engine frame above the HTTP layer.
        assertTrue("frak_github_requests_total{caller=\"GitHubCallMeterTest\",endpoint=\"GET /issues/:n\",status=\"200\"} 2" in text, text)
        assertTrue("frak_github_requests_total{caller=\"GitHubCallMeterTest\",endpoint=\"POST /issues/:n/comments\",status=\"403\"} 1" in text, text)
        assertTrue("frak_github_ratelimit_remaining{repo=\"acme/tickets\",resource=\"core\"} 0" in text, text)
        assertTrue("frak_github_ratelimit_limit{repo=\"acme/tickets\",resource=\"core\"} 5000" in text, text)
        assertTrue("frak_github_ratelimit_reset_seconds{repo=\"acme/tickets\",resource=\"core\"} 1791340000" in text, text)
    }

    @Test
    fun `nothing is rendered before the first call, and a call that throws counts as error`() {
        assertEquals("", meter.render())
        runCatching {
            meter.intercept(MockClientHttpRequest(HttpMethod.GET, URI("https://api.github.com/user")), ByteArray(0)) { _, _ -> error("connection reset") }
        }
        assertTrue("endpoint=\"GET /user\",status=\"error\"} 1" in meter.render())
    }
}
