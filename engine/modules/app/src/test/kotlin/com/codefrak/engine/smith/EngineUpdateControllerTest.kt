package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.net.ConnectException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.Optional
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession
import kotlin.concurrent.thread
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** `GET/POST /engine/update`: the desktop's Deploy button; the engine updates itself in a cluster, its host otherwise. */
class EngineUpdateControllerTest {
    @TempDir lateinit var dir: File

    private val restarted = mutableListOf<String>()
    private var registryAsks = 0
    private var latest = EngineBuild("sha256:new", "b".repeat(40), "https://github.com/codefrak-oss/frakworks")
    private var clock = Instant.parse("2026-10-11T10:00:00Z")
    private val compared = mutableListOf<Triple<String, String, String>>()

    private fun controller(inPod: Boolean, commit: String = "a".repeat(40)): EngineUpdateController {
        val inCluster = InCluster(File(dir, "token").also { if (inPod) it.writeText("t") })
        val restart = EngineRestartController(inCluster, FakeSandbox(), { restarted += it }, { _, _, _ -> true }, { "frakworks" })
        return EngineUpdateController(inCluster, restart, { "ghcr.io/codefrak-oss/frakworks-engine:main" },
            { registryAsks++; latest }, { s, b, h -> compared += Triple(s, b, h); 3 }, { 2 }, commit, { "frakworks" }, { clock })
    }

    @Test
    fun `in a cluster the status names both builds, how far behind, and that the engine updates itself`() {
        val s = controller(inPod = true).status()
        assertEquals(EngineUpdateController.KUBERNETES, s.mode)
        assertTrue(s.selfUpdate)
        assertEquals("a".repeat(40), s.running)
        assertEquals("ghcr.io/codefrak-oss/frakworks-engine:main", s.image)
        assertEquals(latest, s.latest)
        assertEquals(3, s.behind)
        assertTrue(s.updateAvailable)
        assertEquals(2, s.openSessions)
        assertNull(s.error)
        assertEquals(listOf(Triple("https://github.com/codefrak-oss/frakworks", "a".repeat(40), "b".repeat(40))), compared)
    }

    @Test
    fun `the same commit is zero behind and nothing to update`() {
        latest = latest.copy(commit = "a".repeat(40))
        val s = controller(inPod = true).status()
        assertEquals(0, s.behind)
        assertFalse(s.updateAvailable)
        assertEquals(emptyList(), compared)
    }

    @Test
    fun `the status is kept a minute unless fresh is asked`() {
        val c = controller(inPod = true)
        c.status(); c.status()
        assertEquals(1, registryAsks)
        c.status(fresh = true)
        assertEquals(2, registryAsks)
        clock = clock.plusSeconds(61)
        c.status()
        assertEquals(3, registryAsks)
    }

    @Test
    fun `a registry that cannot be read is an error, not a guess`() {
        val inCluster = InCluster(File(dir, "token").also { it.writeText("t") })
        val restart = EngineRestartController(inCluster, FakeSandbox(), { restarted += it }, { _, _, _ -> true }, { "frakworks" })
        val s = EngineUpdateController(inCluster, restart, { "ghcr.io/o/frakworks-engine:main" }, { error("down") }, { _, _, _ -> 1 }, { 0 },
            "a".repeat(40), { "frakworks" }, { clock }).status()
        assertNull(s.latest)
        assertFalse(s.updateAvailable)
        assertTrue(s.error!!.contains("registry: down"))
    }

    @Test
    fun `in a cluster the update restarts the engine's own deployment`() {
        assertEquals(EngineRestartResponse("frakworks", "engine"), controller(inPod = true).update(MockHttpServletRequest()))
        assertEquals(listOf("frakworks"), restarted)
    }

    @Test
    fun `a Compose engine says its host updates it, and refuses the update`() {
        val c = controller(inPod = false)
        val s = c.status()
        assertEquals(EngineUpdateController.HOST, s.mode)
        assertFalse(s.selfUpdate)
        assertEquals(0, registryAsks, "a host engine asks no registry")
        assertTrue(s.error!!.contains("its host updates it"))
        val e = assertFailsWith<ResponseStatusException> { c.update(MockHttpServletRequest()) }
        assertEquals(HttpStatus.CONFLICT, e.statusCode)
        assertEquals(emptyList(), restarted)
    }

    @Test
    fun `an image reference splits into registry and repository`() {
        assertEquals("ghcr.io" to "o/frakworks-engine", OciEngineRegistry.parse("ghcr.io/o/frakworks-engine:main"))
        assertEquals("localhost:5000" to "frakworks-engine", OciEngineRegistry.parse("localhost:5000/frakworks-engine:main"))
        assertEquals("ghcr.io" to "o/e", OciEngineRegistry.parse("ghcr.io/o/e@sha256:abc"))
        assertEquals("registry-1.docker.io" to "library/engine", OciEngineRegistry.parse("engine:main"))
    }

    private val config = "sha256:" + "c".repeat(64)
    private val manifest = """{"schemaVersion":2,"config":{"digest":"$config"}}"""
    private val blob = """{"config":{"Labels":{"org.opencontainers.image.revision":"${"b".repeat(40)}","org.opencontainers.image.source":"https://github.com/codefrak-oss/frakworks"}}}"""

    /** A registry's answer for a path, as the fake registries below give it. */
    private fun answer(path: String): Pair<Int, String> = when (path) {
        "/v2/frakworks-engine/manifests/main" -> 200 to manifest
        "/v2/frakworks-engine/blobs/$config" -> 200 to blob
        else -> 404 to "{}"
    }

    /**
     * The LAN registry: plain HTTP, no TLS, no auth. An https client's ClientHello (a TLS record, first byte 0x16) gets a
     * plain-text 400 and a close, as Go's registry answers it, so the client fails with an `SSLException`.
     */
    private fun plainRegistry(): ServerSocket {
        val server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    socket.use { s ->
                        val input = s.getInputStream().buffered()
                        val out = s.getOutputStream()
                        input.mark(1)
                        if (input.read() == 0x16) {
                            out.write("HTTP/1.0 400 Bad Request\r\n\r\nClient sent an HTTP request to an HTTPS server.\n".toByteArray()); out.flush(); return@use
                        }
                        input.reset()
                        val reader = input.bufferedReader()
                        val path = reader.readLine().split(" ")[1]
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val (status, body) = answer(path)
                        val bytes = body.toByteArray()
                        out.write(("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nDocker-Content-Digest: sha256:main\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                        out.flush()
                    }
                }
            }
        }
        return server
    }

    private fun response(req: HttpRequest, status: Int, body: String): HttpResponse<String> = object : HttpResponse<String> {
        override fun statusCode() = status
        override fun request() = req
        override fun previousResponse() = Optional.empty<HttpResponse<String>>()
        override fun headers(): HttpHeaders = HttpHeaders.of(mapOf("docker-content-digest" to listOf("sha256:main"))) { _, _ -> true }
        override fun body() = body
        override fun sslSession() = Optional.empty<SSLSession>()
        override fun uri(): URI = req.uri()
        override fun version() = HttpClient.Version.HTTP_1_1
    }

    @Test
    fun `the registry lookup reads a plain-HTTP registry over http`() {
        plainRegistry().use { server ->
            val build = OciEngineRegistry().latest("127.0.0.1:${server.localPort}/frakworks-engine:main")
            assertEquals(EngineBuild("sha256:main", "b".repeat(40), "https://github.com/codefrak-oss/frakworks"), build)
        }
    }

    @Test
    fun `a registry that speaks https is read over https alone`() {
        val asked = mutableListOf<URI>()
        val build = OciEngineRegistry { r -> asked += r.uri(); answer(r.uri().path).let { (st, b) -> response(r, st, b) } }.latest("ghcr.io/frakworks-engine:main")
        assertEquals("b".repeat(40), build.commit)
        assertEquals(listOf("https"), asked.map { it.scheme }.distinct())
        assertEquals(2, asked.size)
    }

    @Test
    fun `only a TLS failure retries over http, and a failure over both names both`() {
        for (tls in listOf(SSLException("Unrecognized SSL message, plaintext connection?"), SSLHandshakeException("handshake"))) {
            val asked = mutableListOf<URI>()
            val build = OciEngineRegistry { r -> asked += r.uri()
                if (r.uri().scheme == "https") throw tls else answer(r.uri().path).let { (st, b) -> response(r, st, b) } }.latest("registry.example.com:5000/frakworks-engine:main")
            assertEquals("b".repeat(40), build.commit)
            assertEquals(listOf("https://registry.example.com:5000/v2/frakworks-engine/manifests/main", "http://registry.example.com:5000/v2/frakworks-engine/manifests/main",
                "http://registry.example.com:5000/v2/frakworks-engine/blobs/$config"), asked.map { it.toString() })
        }
        for (failure in listOf<(HttpRequest) -> HttpResponse<String>>({ throw ConnectException("Connection refused") },
            { throw HttpTimeoutException("request timed out") }, { r -> response(r, 404, "{}") })) {
            val asked = mutableListOf<URI>()
            assertFailsWith<Exception> { OciEngineRegistry { r -> asked += r.uri(); failure(r) }.latest("registry.example.com:5000/frakworks-engine:main") }
            assertEquals(listOf("https"), asked.map { it.scheme })
        }
        val both = assertFailsWith<IllegalStateException> {
            OciEngineRegistry { r -> if (r.uri().scheme == "https") throw SSLException("Unrecognized SSL message") else throw ConnectException("Connection refused") }
                .latest("registry.example.com:5000/frakworks-engine:main")
        }
        assertEquals("https: Unrecognized SSL message; http: Connection refused", both.message)
        val c = EngineUpdateController(InCluster(File(dir, "token").also { it.writeText("t") }),
            EngineRestartController(InCluster(File(dir, "token")), FakeSandbox(), { }, { _, _, _ -> true }, { "frakworks" }),
            { "registry.example.com:5000/frakworks-engine:main" }, { error(both.message ?: fail()) }, { _, _, _ -> 0 }, { 0 }, "a".repeat(40), { "frakworks" }, { clock })
        assertEquals("registry: https: Unrecognized SSL message; http: Connection refused", c.status().error)
    }
}
