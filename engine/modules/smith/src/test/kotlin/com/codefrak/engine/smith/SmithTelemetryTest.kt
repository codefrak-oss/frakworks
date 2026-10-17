package com.codefrak.engine.smith

import com.codefrak.engine.accounts.AccountView
import com.codefrak.engine.accounts.AiAccount
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SmithTelemetry against a stub VictoriaLogs and a sandbox whose stdout the test writes. */
class SmithTelemetryTest {
    /** A stub `/insert/jsonline`: records each request's query and body, answers [status]. */
    private class StubLogs(var status: Int = 204) : AutoCloseable {
        val queries = CopyOnWriteArrayList<String>()
        val rows = CopyOnWriteArrayList<Map<String, String>>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/insert/jsonline") { ex ->
                queries += ex.requestURI.rawQuery
                val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                if (status in 200..299) body.lines().filter { it.isNotBlank() }.forEach { rows += jacksonObjectMapper().readValue<Map<String, String>>(it) }
                ex.sendResponseHeaders(status, -1); ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private val stubs = mutableListOf<AutoCloseable>()
    @AfterEach fun stop() = stubs.forEach { it.close() }
    private fun stub(status: Int = 204) = StubLogs(status).also { stubs += it }

    private fun telemetry(sandbox: FakeSandbox, logsUrl: String) =
        SmithTelemetry(sandbox, logsUrl, metricsEnabled = true, instance = com.codefrak.engine.lease.InstanceId("engine-test"), timeout = Duration.ofSeconds(2))

    private fun spec(runId: String, issue: Long = 386, mode: String = "work", model: String = "claude-opus-5-5", account: String? = null) =
        SmithSpec(runId, "acme/widgets", issue, model, "medium", dryRun = false, mode = mode, account = account)

    @Test
    fun `each stdout line becomes one jsonline row with the raw line and the five fields, while the run is live`() {
        val logs = stub()
        val sandbox = FakeSandbox()
        val t = telemetry(sandbox, logs.url)
        t.started(spec("run-aaaa-1111"), "container-386")
        val lines = listOf("""{"type":"system","subtype":"init"}""", """{"type":"assistant","message":{"content":[]}}""", "not json")
        lines.forEach { sandbox.print("container-386", it) }

        eventually("three rows") { logs.rows.size == 3 }
        assertTrue(t.isActive("run-aaaa-1111"), "rows arrive while the run is still live")
        assertEquals(lines, logs.rows.map { it["_msg"] })
        for (row in logs.rows) {
            assertEquals("386", row["issue"]); assertEquals("engine-test", row["host"])
            assertEquals("work", row["phase"]); assertEquals("claude-opus-5-5", row["model"])
            assertEquals("issue-386-work-run-aaaa-1111.log", row["filename"])
        }
        val times = logs.rows.map { Instant.parse(it["_time"]) }
        assertEquals(times.sorted(), times); assertEquals(3, times.toSet().size)
        assertTrue(logs.queries.all { "_msg_field=_msg" in it && "_time_field=_time" in it && "_stream_fields=issue,host,filename" in it })
    }

    @Test
    fun `filename is one value per run and differs between runs of one issue`() {
        val logs = stub()
        val sandbox = FakeSandbox()
        val t = telemetry(sandbox, logs.url)
        t.started(spec("run-1"), "container-a"); sandbox.print("container-a", "a1"); sandbox.print("container-a", "a2")
        t.started(spec("run-2"), "container-b"); sandbox.print("container-b", "b1")
        eventually("three rows") { logs.rows.size == 3 }
        val byLine = logs.rows.associate { it["_msg"] to it["filename"] }
        assertEquals(byLine["a1"], byLine["a2"])
        assertNotEquals(byLine["a1"], byLine["b1"])
    }

    @Test
    fun `the gauge is on metrics while the run is live and gone once its sandbox exits`() {
        val sandbox = FakeSandbox()
        val t = telemetry(sandbox, logsUrl = "")
        t.started(spec("run-g", issue = 77, mode = "security"), "container-77")
        sandbox.started += spec("run-g", issue = 77, mode = "security")

        val series = """smith_run_active{host="engine-test",issue="77",kind="security-review",model="claude-opus-5-5",repo="acme/widgets"} 1"""
        assertTrue(series in t.metrics().lines(), t.metrics())
        assertTrue("""smith_fleet_last_scrape_timestamp_seconds{host="engine-test"}""" in t.metrics())

        sandbox.exit("container-77", 0)
        eventually("the series to leave /metrics") { !t.isActive("run-g") }
        assertFalse(t.metrics().lines().any { it.startsWith("smith_run_active{") }, t.metrics())
    }

    @Test
    fun `GET accounts counts live runs per account and model, and an account with none gets an empty list`() {
        val sandbox = FakeSandbox()
        val t = telemetry(sandbox, logsUrl = "")
        t.started(spec("run-1", issue = 1, account = "anthropic-a"), "container-1")
        t.started(spec("run-2", issue = 2, model = "claude-sonnet-5", account = "anthropic-a"), "container-2")
        t.started(spec("run-3", issue = 3, mode = "review", account = "anthropic-a"), "container-3")
        t.started(spec("run-4", issue = 4), "container-4") // a dry run's: no account, counted nowhere

        val added = java.time.OffsetDateTime.parse("2026-10-01T00:00:00Z")
        val rows = AccountView.of(listOf(
            AiAccount("anthropic-a", "anthropic", null, true, true, added, added),
            AiAccount("anthropic-b", "anthropic", null, true, true, added, added),
        ), t.running())
        assertEquals(listOf(ModelCount("claude-opus-5-5", 2), ModelCount("claude-sonnet-5", 1)), rows[0].running)
        assertEquals(emptyList(), rows[1].running)

        t.ended("run-2")
        eventually("run-2 to leave the counts") { t.running()["anthropic-a"] == listOf(ModelCount("claude-opus-5-5", 2)) }
    }

    @Test
    fun `the account is on RunLabels but not on the smith_run_active series`() {
        val t = telemetry(FakeSandbox(), logsUrl = "")
        t.started(spec("run-m", issue = 9, account = "anthropic-a"), "container-9")
        assertEquals("anthropic-a", t.active().single().account)
        val series = """smith_run_active{host="engine-test",issue="9",kind="job",model="claude-opus-5-5",repo="acme/widgets"} 1"""
        assertEquals(listOf(series), t.metrics().lines().filter { it.startsWith("smith_run_active{") })
    }

    @Test
    fun `collect ends the gauge when the stream never said the sandbox exited`() {
        val t = telemetry(FakeSandbox(), logsUrl = "")
        t.started(spec("run-c"), "container-c")
        t.ended("run-c"); t.ended("run-c")
        eventually("the series to leave /metrics") { !t.isActive("run-c") }
    }

    @Test
    fun `a stream that drops while the sandbox runs is followed again from now`() {
        val sandbox = FakeSandbox()
        val t = telemetry(sandbox, logsUrl = "")
        t.started(spec("run-d"), "container-d")
        sandbox.followers.remove("container-d")!!.second(RuntimeException("socket reset"))
        assertTrue(t.isActive("run-d"))
        assertTrue(sandbox.followers.containsKey("container-d"))
    }

    @Test
    fun `a failing or unreachable VictoriaLogs throws nothing, and the gauge still ends`() {
        val failing = stub(status = 500)
        for (url in listOf(failing.url, "http://127.0.0.1:1", "not a url")) {
            val sandbox = FakeSandbox()
            val t = telemetry(sandbox, url)
            t.started(spec("run-f"), "container-f")
            repeat(20) { sandbox.print("container-f", """{"type":"assistant","n":$it}""") }
            sandbox.states["container-f"] = SandboxStatus(false, 0)
            sandbox.followers.remove("container-f")!!.second(null)
            eventually("the series to leave /metrics for $url") { !t.isActive("run-f") }
        }
        assertTrue(failing.queries.isNotEmpty(), "the failing endpoint was tried")
    }

    @Test
    fun `with nothing configured nothing is followed`() {
        val sandbox = FakeSandbox()
        val t = SmithTelemetry(sandbox, "", metricsEnabled = false, instance = com.codefrak.engine.lease.InstanceId("engine"), timeout = Duration.ofSeconds(1))
        t.started(spec("run-n"), "container-n")
        assertFalse(t.isActive("run-n"))
        assertTrue(sandbox.followers.isEmpty())
    }
}
