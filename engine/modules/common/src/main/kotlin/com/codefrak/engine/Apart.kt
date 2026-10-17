package com.codefrak.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Runs [what] on another thread, so in a transaction of its own: a remedy that moves another flow (a delivered exit,
 * an executed job) must not share this one, where its failure would roll back the caller's.
 */
fun <T> apart(timeoutSeconds: Long, what: () -> T): T = try {
    CompletableFuture.supplyAsync(what).get(timeoutSeconds, TimeUnit.SECONDS)
} catch (e: ExecutionException) {
    throw e.cause ?: e
}
