package com.codefrak.engine.repos

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `ops/1` contract (#100008): what conforms, what is missing, and how `is_deployed`'s answer is read. */
class OpsContractTest {
    private val json = jacksonObjectMapper()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val probe = McpProbe().apply { timeout = Duration.ofSeconds(3) }
    private val ops = OpsContract(probe)

    @AfterEach fun stop() = server.stop(0)

    private fun tool(name: String = "is_deployed", description: String? = "Answers {state: deployed|not_yet} for a sha",
                     input: Map<String, Any?>? = mapOf("type" to "object", "properties" to mapOf("sha" to mapOf("type" to "string")), "required" to listOf("sha")),
                     output: Map<String, Any?>? = null) = McpToolSpec(name, description, input, output)

    @Test
    fun `conformance names what is missing`() {
        assertEquals(OpsContract.Conformance("ops/1", true, emptyList()), OpsContract.conformance(listOf(tool(), McpToolSpec("other"))))
        assertEquals(listOf(OpsContract.MISSING_TOOL), OpsContract.conformance(listOf(McpToolSpec("deployed"))).missing)
        assertEquals(listOf(OpsContract.MISSING_SHA), OpsContract.conformance(listOf(tool(input = mapOf("type" to "object", "properties" to mapOf("sha" to mapOf("type" to "string")))))).missing, "sha not required")
        assertEquals(listOf(OpsContract.MISSING_SHA), OpsContract.conformance(listOf(tool(input = mapOf("type" to "object", "properties" to mapOf("sha" to mapOf("type" to "integer")), "required" to listOf("sha"))))).missing, "sha not a string")
        assertEquals(listOf(OpsContract.MISSING_ANSWER), OpsContract.conformance(listOf(tool(description = "Says whether a sha is live"))).missing, "the answer shape is not named")
        assertEquals(OpsContract.Conformance("ops/1", true, emptyList()),
            OpsContract.conformance(listOf(tool(description = null, output = mapOf("type" to "object", "properties" to mapOf("state" to mapOf("type" to "string", "enum" to listOf("deployed", "not_yet"))))))),
            "an outputSchema naming both states counts")
        val none = OpsContract.conformance(listOf(tool(description = null, input = null)))
        assertEquals(listOf(OpsContract.MISSING_SHA, OpsContract.MISSING_ANSWER), none.missing)
        assertEquals("ops server does not conform to ops/1: ${OpsContract.MISSING_SHA}; ${OpsContract.MISSING_ANSWER}", OpsContract.refusal(none))
    }

    private fun stub(answer: (Map<*, *>) -> Map<String, Any?>): McpServerConfig {
        server.createContext("/mcp") { ex ->
            val msg: Map<String, Any?> = json.readValue(ex.requestBody.readAllBytes())
            val id = msg["id"]
            if (id == null) { ex.sendResponseHeaders(202, -1); ex.close(); return@createContext }
            val result = when (msg["method"]) {
                "initialize" -> mapOf("protocolVersion" to "2025-03-26", "capabilities" to emptyMap<String, Any>(), "serverInfo" to mapOf("name" to "ops", "version" to "1"))
                "tools/call" -> answer((msg["params"] as Map<*, *>)["arguments"] as Map<*, *>)
                else -> emptyMap()
            }
            val body = json.writeValueAsBytes(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body); ex.close()
        }
        server.start()
        return McpServerConfig("ops-server", McpServerKind.HTTP, "http://127.0.0.1:${server.address.port}/mcp", emptyMap(), null, emptyList(), emptyMap(), McpAudience.SMITH, McpRole.OPS)
    }

    @Test
    fun `isDeployed reads state from the structured content or the first text content as JSON`() {
        val config = stub { args -> if (args["sha"] == "deadbee") mapOf("structuredContent" to mapOf("state" to "deployed"))
            else mapOf("content" to listOf(mapOf("type" to "text", "text" to "{\"state\": \"not_yet\"}"))) }
        assertEquals(DeployState.DEPLOYED, ops.isDeployed(config, "deadbee"))
        assertEquals(DeployState.NOT_YET, ops.isDeployed(config, "0123456"))
    }

    @Test
    fun `any other answer is an error naming the server`() {
        val config = stub { args -> when (args["sha"]) {
            "1111111" -> mapOf("content" to listOf(mapOf("type" to "text", "text" to "nope")))
            "2222222" -> mapOf("structuredContent" to mapOf("state" to "maybe"))
            else -> mapOf("isError" to true, "content" to listOf(mapOf("type" to "text", "text" to "no such environment")))
        } }
        for ((sha, part) in listOf("1111111" to "no structured content and no JSON text", "2222222" to "state 'maybe', not deployed or not_yet", "3333333" to "answered an error: no such environment")) {
            val e = assertThrows<IllegalStateException> { ops.isDeployed(config, sha) }
            assertTrue(e.message!!.startsWith("ops server ops-server: is_deployed "), e.message)
            assertTrue(part in e.message!!, e.message)
        }
        val stdio = assertThrows<IllegalStateException> { ops.isDeployed(config.copy(kind = McpServerKind.STDIO, url = null, command = "x"), "4444444") }
        assertTrue(McpProbe.STDIO in stdio.message!!, stdio.message)
    }
}
