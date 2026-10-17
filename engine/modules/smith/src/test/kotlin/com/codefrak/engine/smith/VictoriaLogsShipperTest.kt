package com.codefrak.engine.smith

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VictoriaLogsShipper against a stub `/insert/jsonline`, constructed directly.
 *
 * The shipper's thread starts in the constructor and polls the queue while the
 * test is still offering, so the three lines may land in one post or several:
 * the test asserts every line arrives, in order, with its labels, and not how
 * many posts carried them (the batch count was a flaky assertion, #2159).
 */
class VictoriaLogsShipperTest {
    @Test
    fun `offered lines are posted in order once finished`() {
        val rows = CopyOnWriteArrayList<Map<String, String>>()
        val requests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/insert/jsonline") { ex ->
                requests.incrementAndGet()
                val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                body.lines().filter { it.isNotBlank() }.forEach { rows += jacksonObjectMapper().readValue<Map<String, String>>(it) }
                ex.sendResponseHeaders(204, -1); ex.close()
            }
            start()
        }
        try {
            val labels = RunLabels("run-shipper-1", 42, "acme/widgets", "claude-opus-5-5", "work", "engine-test")
            val shipper = VictoriaLogsShipper(labels, "http://127.0.0.1:${server.address.port}", Duration.ofSeconds(2), HttpClient.newHttpClient())
            listOf("line-1", "line-2", "line-3").forEach { shipper.offer(it) }
            shipper.finish()
            shipper.thread.join(5_000)

            assertTrue(requests.get() in 1..3, "expected one to three posts, got ${requests.get()}")
            assertEquals(listOf("line-1", "line-2", "line-3"), rows.map { it["_msg"] })
            for (row in rows) {
                assertEquals("42", row["issue"]); assertEquals("engine-test", row["host"])
                assertEquals("work", row["phase"]); assertEquals("claude-opus-5-5", row["model"])
            }
        } finally {
            server.stop(0)
        }
    }
}
