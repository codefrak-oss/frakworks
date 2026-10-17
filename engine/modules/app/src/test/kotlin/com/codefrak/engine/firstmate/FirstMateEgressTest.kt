package com.codefrak.engine.firstmate

import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A session pod's own egress to its repo's LAN MCP servers (FirstMateEgress). */
class FirstMateEgressTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    private val dns = mapOf(
        "desk.example.com" to listOf(ip("10.0.0.40")),
        "mcp.linear.app" to listOf(ip("34.1.2.3")),
        "engine.example.com" to listOf(ip("10.0.0.240")),
    )
    private fun mcp(vararg servers: Pair<String, Map<String, Any?>>) = FirstMateMcp(linkedMapOf(*servers), emptyList())

    @Test
    fun `only a repo server on a private address gets a hole, at its URL's port`() {
        val m = mcp(
            "frakworks" to mapOf("type" to "http", "url" to "https://engine.example.com/mcp/frakworks"),
            "frakpanel" to mapOf("type" to "http", "url" to "http://desk.example.com:7781/mcp"),
            "linear" to mapOf("type" to "http", "url" to "https://mcp.linear.app/mcp"),
            "git" to mapOf("type" to "stdio", "command" to "git"),
            "gone" to mapOf("type" to "http", "url" to "http://no-such-host/mcp"),
        )
        val holes = FirstMateEgress.holes(m) { dns[it] ?: throw java.net.UnknownHostException(it) }
        assertEquals(listOf(EgressHole("frakpanel", "10.0.0.40", 7781)), holes)
    }

    @Test
    fun `a URL without a port gets its scheme's`() {
        val m = mcp("a" to mapOf("url" to "https://desk.example.com/mcp"), "b" to mapOf("url" to "http://desk.example.com/mcp"))
        assertEquals(listOf(443, 80), FirstMateEgress.holes(m) { dns.getValue(it) }.map { it.port })
    }

    @Test
    fun `the private ranges are the sandbox's`() {
        for (a in listOf("10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.1.1", "169.254.1.1", "100.64.0.1", "100.127.9.9"))
            assertTrue(FirstMateEgress.private(ip(a)), a)
        for (a in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "::1")) assertFalse(FirstMateEgress.private(ip(a)), a)
    }

    @Test
    fun `the policy selects only the session's pod and opens each hole's address and port`() {
        val p = FirstMateEgress.policy("first-mate-s-1", "s-1",listOf(EgressHole("frakpanel", "10.0.0.40", 7781)))
        assertEquals(mapOf(FIRST_MATE_SESSION_LABEL to "s-1"), p.spec.podSelector.matchLabels)
        assertEquals(listOf("Egress"), p.spec.policyTypes)
        val rule = p.spec.egress.single()
        assertEquals("10.0.0.40/32", rule.to.single().ipBlock.cidr)
        assertEquals(7781, rule.ports.single().port.intVal)
        assertEquals("TCP", rule.ports.single().protocol)
    }
}
