package com.codefrak.engine.github

import org.springframework.http.client.ClientHttpRequestInterceptor

/**
 * Counts the engine's GitHub REST calls and keeps GitHub's rate-limit headers (engine/README.md, "GitHub calls and
 * the rate limit"): every GitHub REST client (`RestGitHub`, `com.codefrak.engine.ci.RestCheckRuns`, `RestRepoSetupGitHub`,
 * ...) passes its calls through this interceptor, and `GET /metrics` appends [render]. Git's own fetches and pushes
 * are not REST calls and are not here. The engine's is `PrometheusGitHubCallMeter` (engine-github).
 */
interface GitHubCallMeter : ClientHttpRequestInterceptor {
    /** The Prometheus text of the call and rate-limit families; empty before the first call. */
    fun render(): String
}
