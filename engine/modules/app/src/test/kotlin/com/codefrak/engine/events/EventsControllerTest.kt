package com.codefrak.engine.events

import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.SandboxCallers
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A sandbox that knows only which address is which run; `down` is Docker not answering (as `repos/TargetRepoControllerTest.kt` fakes it). */
private class AddressSandbox(val runs: Map<String, SmithCaller>, @Volatile var down: Boolean = false) : SmithSandbox {
    override fun start(spec: SmithSpec): String = error("unused")
    override fun status(handle: String): SandboxStatus = error("unused")
    override fun logs(handle: String, maxLines: Int): String = ""
    override fun output(handle: String, path: String): ByteArray? = null
    override fun remove(handle: String) {}
    override fun runAt(address: String): SmithCaller? = if (down) error("docker is down") else runs[address]
}

/**
 * `GET /events` over a standalone MockMvc: the controller over the context's [TargetRepos], a bus of the test's own
 * (published into directly) and an [AddressSandbox]. The stream is read as the response body written so far.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class EventsControllerTest {
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var json: ObjectMapper

    private val repo = "Acme/Stream"
    private val sandbox = AddressSandbox(mapOf("172.18.0.7" to SmithCaller("run-7", 7, repo)))
    private lateinit var bus: EngineEvents
    private lateinit var mvc: MockMvc
    private val at = Instant.parse("2026-10-05T10:00:00Z")

    @BeforeEach
    fun setUp() {
        repos.remove(repo); repos.add(repo)
        sandbox.down = false
        bus = EngineEvents(json)
        mvc = MockMvcBuilders.standaloneSetup(EventsController(bus, repos, SandboxCallers(sandbox), Duration.ofMillis(50))).build()
    }

    @AfterEach
    fun tearDown() { repos.remove(repo) }

    private fun from(address: String) = RequestPostProcessor { it.remoteAddr = address; it }

    private fun open(query: String = "repo=$repo"): MvcResult =
        mvc.get("/events?$query").andExpect { request { asyncStarted() }; status { isOk() } }.andReturn()

    /** The body written so far, once it contains `needle` or 5 s pass. */
    private fun bodyWith(result: MvcResult, needle: String): String {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until) {
            val body = result.response.contentAsString
            if (needle in body) return body
            Thread.sleep(20)
        }
        return result.response.contentAsString
    }

    /** The body's event frames, without the `: ping` comments a slow runner lets in between them (the test pings every 50 ms). */
    private fun eventBlocks(body: String) = body.split("\n\n").filter { it.isNotBlank() && !it.trimStart().startsWith(":") }

    /** One `event:` / `data:` frame as its type and parsed data. */
    private fun frame(text: String): Pair<String, Map<String, Any?>> {
        val lines = text.trim().lines()
        assertEquals(2, lines.size, text)
        assertTrue(lines[0].startsWith("event: ") && lines[1].startsWith("data: "), text)
        return lines[0].removePrefix("event: ") to json.readValue<Map<String, Any?>>(lines[1].removePrefix("data: "))
    }

    @Test
    fun `opens a text-event-stream that is not cached or buffered, and hello is its first frame`() {
        val result = open()
        assertTrue(result.response.contentType!!.startsWith("text/event-stream"), result.response.contentType)
        assertEquals("no-cache", result.response.getHeader("Cache-Control"))
        assertEquals("no", result.response.getHeader("X-Accel-Buffering"))
        val body = bodyWith(result, "event: hello")
        val (type, data) = frame(body.split("\n\n")[0])
        assertEquals("hello", type)
        assertEquals(repo, data["repo"])
        Instant.parse(data["at"] as String)
        assertEquals(1, bus.subscriberCount, "subscribed")
    }

    @Test
    fun `a frame published after hello is on the stream, and only its repo's`() {
        val result = open()
        bodyWith(result, "event: hello")
        bus.publish(EngineEvent.Ticket("acme/other", 1, "opened", at = at))
        bus.publish(EngineEvent.Ticket("acme/stream", 7, "labeled", label = "authorized", at = at))
        val body = bodyWith(result, "event: ticket")
        val frames = eventBlocks(body).map(::frame)
        assertEquals(listOf("hello", "ticket"), frames.map { it.first }, body)
        assertEquals(mapOf("repo" to "acme/stream", "number" to 7, "action" to "labeled", "label" to "authorized", "prNumber" to null,
            "issue" to null, "at" to "2026-10-05T10:00:00Z"), frames[1].second)
    }

    @Test
    fun `an idle stream gets a ping comment`() {
        val result = open()
        val body = bodyWith(result, ": ping\n\n")
        assertTrue(body.contains("\n\n: ping\n\n"), body)
    }

    @Test
    fun `star subscribes to every repo`() {
        val result = open("repo=*")
        bodyWith(result, "event: hello")
        assertTrue(result.response.contentAsString.contains("\"repo\":\"*\""))
        bus.publish(EngineEvent.Setting("intake.tier", 6, at = at))
        val (type, data) = frame(eventBlocks(bodyWith(result, "event: setting")).last())
        assertEquals("setting", type)
        assertEquals(mapOf("repo" to "*", "name" to "intake.tier", "value" to 6, "at" to "2026-10-05T10:00:00Z"), data)
    }

    @Test
    fun `without repo it is 400 naming the target repos`() {
        val reason = mvc.get("/events").andExpect { status { isBadRequest() } }.andReturn().response.errorMessage
        assertTrue(reason!!.contains(repo), reason)
        assertEquals(0, bus.subscriberCount)
    }

    @Test
    fun `a repo that is not a target repo is refused as GET needs refuses it`() {
        mvc.get("/events?repo=acme/nowhere").andExpect { status { isBadRequest() } }
        assertEquals(0, bus.subscriberCount)
    }

    @Test
    fun `a request with an Origin header is 403`() {
        mvc.get("/events?repo=$repo") { header("Origin", "https://evil.example") }.andExpect { status { isForbidden() } }
        assertEquals(0, bus.subscriberCount)
    }

    @Test
    fun `a Smith sandbox is 403, and 503 when Docker cannot say`() {
        mvc.get("/events?repo=$repo") { with(from("172.18.0.7")) }.andExpect { status { isForbidden() } }
        mvc.get("/events?repo=*") { with(from("172.18.0.7")) }.andExpect { status { isForbidden() } }
        sandbox.down = true
        mvc.get("/events?repo=$repo") { with(from("10.0.0.5")) }.andExpect { status { isServiceUnavailable() } }
        sandbox.down = false
        assertEquals(0, bus.subscriberCount)
        open("repo=$repo").also { bodyWith(it, "event: hello") }
        assertEquals(1, bus.subscriberCount, "a caller that is no Smith is let in")
    }
}
