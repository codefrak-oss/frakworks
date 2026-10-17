package com.codefrak.engine.smith

import java.time.Duration

fun eventually(what: String, check: () -> Boolean) {
    val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    while (!check()) { if (System.nanoTime() > until) error("timed out waiting for $what"); Thread.sleep(20) }
}
