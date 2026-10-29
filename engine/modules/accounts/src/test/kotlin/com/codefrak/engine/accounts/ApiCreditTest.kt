package com.codefrak.engine.accounts

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The credit cycle's arithmetic, the cents summing and the cost_report client against a stub. Plain JUnit, no Spring. */
class ApiCreditTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `the cycle starts on the anchor's day this month, or last month's when that is still ahead`() {
        assertEquals(d("2026-10-14"), cycleStart(d("2026-01-14"), d("2026-10-29")))
        assertEquals(d("2026-09-14"), cycleStart(d("2026-01-14"), d("2026-10-13")))
        assertEquals(d("2026-10-14"), cycleStart(d("2026-01-14"), d("2026-10-14")), "today the anchor's day: a new cycle")
        assertEquals(d("2026-11-14"), cycleEnd(d("2026-01-14"), d("2026-10-14")))
    }

    @Test
    fun `a day past the month's end clamps to its last day`() {
        assertEquals(d("2026-02-28"), cycleStart(d("2026-01-31"), d("2026-02-28")))
        assertEquals(d("2026-01-31"), cycleStart(d("2026-01-31"), d("2026-02-27")))
        assertEquals(d("2028-02-29"), cycleStart(d("2026-01-31"), d("2028-03-01")), "a leap February")
        assertEquals(d("2026-04-30"), cycleStart(d("2026-01-31"), d("2026-05-15")), "a 31st anchor in a 30-day month")
        assertEquals(d("2026-03-31"), cycleEnd(d("2026-01-31"), d("2026-02-28")), "the next cycle goes back to the 31st")
    }

    @Test
    fun `a cycle crosses the year end`() {
        assertEquals(d("2026-12-20"), cycleStart(d("2025-06-20"), d("2027-01-05")))
        assertEquals(d("2027-01-20"), cycleEnd(d("2025-06-20"), d("2026-12-20")))
    }

    @Test
    fun `cents strings are summed exactly`() {
        assertEquals(BigDecimal("3712.4567"), sumCents(listOf("123.4567", "3589", "0.0000")))
        assertEquals(BigDecimal.ZERO, sumCents(emptyList()))
    }

    @Test
    fun `cost_report is read page by page from the cycle start with the admin key, and every amount summed`() {
        val seen = mutableListOf<Pair<String?, String?>>()
        val stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex ->
            seen += ex.requestURI.rawQuery to ex.requestHeaders.getFirst("x-api-key")
            val body = if (ex.requestURI.rawQuery.contains("page=p2"))
                """{"data":[{"starting_at":"2026-10-15T00:00:00Z","results":[{"amount":"0.5","currency":"USD"}]}],"has_more":false,"next_page":null}"""
            else """{"data":[{"starting_at":"2026-10-14T00:00:00Z","results":[{"amount":"123.4567","currency":"USD"},{"amount":"10","currency":"USD"}]}],"has_more":true,"next_page":"p2"}"""
            val bytes = body.toByteArray(); ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        stub.start()
        try {
            val spent = HttpCostReport("http://127.0.0.1:${stub.address.port}").spentCents("sk-ant-admin01-k", d("2026-10-14"))
            assertEquals(BigDecimal("133.9567"), spent)
            assertEquals(2, seen.size)
            assertTrue(seen[0].first!!.contains("starting_at=2026-10-14T00%3A00%3A00Z") && seen[0].first!!.contains("bucket_width=1d"), seen[0].first)
            assertEquals("sk-ant-admin01-k", seen[0].second)
        } finally { stub.stop(0) }
    }

    @Test
    fun `a refusal is a failure with anthropic's message and never the key`() {
        val stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex ->
            val bytes = """{"type":"error","error":{"type":"permission_error","message":"not an admin key"}}""".toByteArray()
            ex.sendResponseHeaders(403, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        stub.start()
        try {
            val e = assertFailsWith<CostReportFailed> { HttpCostReport("http://127.0.0.1:${stub.address.port}").spentCents("sk-ant-usr-secret", d("2026-10-14")) }
            assertEquals("cost_report answered 403: not an admin key", e.message)
        } finally { stub.stop(0) }
    }
}
