package com.codefrak.engine.mcp

import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.engineJson
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.tier.SeededTiers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** push_chunk (#100043): who is listed it, what it pushes, what it refuses, and the `push` a live parent wakes on. */
class PushChunkToolTest {
    private val engine = FakeEngine()
    private val git = FakeGitPush()
    private val bus = EngineEvents(engineJson)
    private val watch = ChildWatch(bus, { Duration.ofSeconds(2) }, Duration.ofMillis(50))
    private val published = mutableListOf<EngineEvent>()
    private val mcp = FrakworksMcp(engine, SeededTiers.table(), FakeFrictionLog(), childWatch = watch, git = git, pulls = FakeGitHubPulls(), events = bus)
    private val repo = "acme/widgets"
    private val work = McpCaller.Smith(SmithCaller("run-w", 21, repo))
    private val review = McpCaller.Smith(SmithCaller("run-r", 21, repo))
    private val job = McpCaller.Smith(SmithCaller("run-j", null, repo, jobId = "job-1"))

    init {
        bus.onPublish { published += it }
        val started = Instant.now()
        engine.smithRuns["run-w"] = SmithRunRef("run-w", repo, 21, NamedRun("run-w", "work", started, running = true))
        engine.smithRuns["run-r"] = SmithRunRef("run-r", repo, 21, NamedRun("run-r", "review", started, running = true))
        engine.smithRuns["run-j"] = SmithRunRef("run-j", repo, null, NamedRun("run-j", "job", started, running = true))
    }

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun error(result: McpToolResult): String {
        assertEquals(true, result.isError, result.text)
        return mcpJson.readValue(result.text, Map::class.java)["error"] as String
    }

    @Test
    fun `listed to a work run on a ticket only`() {
        assertTrue(mcp.tools(work).any { it.name == "push_chunk" })
        assertEquals(listOf("bundle"), mcp.tools(work).single { it.name == "push_chunk" }.inputSchema["required"])
        assertFalse(mcp.tools(review).any { it.name == "push_chunk" }, "a review run")
        assertFalse(mcp.tools(job).any { it.name == "push_chunk" }, "a job run")
        assertFalse(mcp.tools(McpCaller.Smith(SmithCaller("run-x", 21, repo))).any { it.name == "push_chunk" }, "a run the engine does not know")
        assertFalse(mcp.tools(McpCaller.Nobody("127.0.0.1", "someone")).any { it.name == "push_chunk" }, "First mate")
    }

    @Test
    fun `a work run's chunk is pushed fast-forward to its branch, the push event published, and the head and paths answered`() {
        val cursor = watch.await(repo, setOf(21L), null, Duration.ZERO).cursor
        val result = mcp.call("push_chunk", mapOf("bundle" to b64("chunk one"), "message" to "the store"), work)
        assertEquals(false, result.isError, result.text)
        val answer = mcpJson.readValue(result.text, Map::class.java)
        assertEquals("sha-1", answer["head"])
        assertEquals(listOf("README.md", "src/Main.kt"), answer["paths"])
        assertEquals(listOf(Triple(repo, "smith/issue-21", "chunk one")), git.pushes)
        val push = published.filterIsInstance<EngineEvent.Push>().single()
        assertEquals(listOf(repo, "refs/heads/smith/issue-21", "sha-1", false), listOf(push.repo, push.ref, push.after, push.default))

        val got = watch.await(repo, setOf(21L), cursor, Duration.ofSeconds(2))
        val change = got.changes.single()
        assertEquals("push" to 21L, change.event to change.number)
        assertTrue(change.wakes)
        assertEquals("refs/heads/smith/issue-21", change.data["ref"])
        assertEquals("sha-1", change.data["after"])
    }

    @Test
    fun `every other run is refused naming it, and nothing is pushed or published`() {
        assertEquals("run run-r is a review run: push_chunk is a work run's, on its ticket's branch",
            error(mcp.call("push_chunk", mapOf("bundle" to b64("x")), review)))
        assertEquals("run run-j is a job run: push_chunk is a work run's, on its ticket's branch",
            error(mcp.call("push_chunk", mapOf("bundle" to b64("x")), job)))
        assertEquals("run run-x is not a Smith run the engine knows",
            error(mcp.call("push_chunk", mapOf("bundle" to b64("x")), McpCaller.Smith(SmithCaller("run-x", 21, repo)))))
        assertEquals("unknown tool: \"push_chunk\"", error(mcp.call("push_chunk", mapOf("bundle" to b64("x")), McpCaller.Nobody("127.0.0.1", "someone"))))
        assertEquals(emptyList<Any>(), git.pushes)
        assertTrue(published.none { it is EngineEvent.Push })
    }

    @Test
    fun `a bundle that is not base64, is empty, or is above 32 MiB is refused before any push`() {
        assertTrue(error(mcp.call("push_chunk", mapOf("bundle" to "not base64!!"), work)).startsWith("not a bundle: \"bundle\" is not base64"))
        assertTrue(error(mcp.call("push_chunk", mapOf("bundle" to ""), work)).startsWith("not a bundle:"))
        assertTrue(error(mcp.call("push_chunk", mapOf("bundle" to 5), work)).startsWith("not a bundle:"))
        assertTrue(error(mcp.call("push_chunk", emptyMap(), work)).startsWith("not a bundle:"))
        val huge = "A".repeat((PushChunkTool.MAX_CHARS + 4).toInt())
        val said = error(mcp.call("push_chunk", mapOf("bundle" to huge), work))
        assertTrue(said.startsWith("push_chunk refused: the bundle is about 32 MiB, above the 32 MiB a chunk may be"), said)
        assertTrue(error(mcp.call("push_chunk", mapOf("bundle" to b64("x"), "extra" to 1), work)).contains("extra"))
        assertEquals(emptyList<Any>(), git.pushes)
        assertEquals(emptyList<Any>(), git.attempts)
    }

    @Test
    fun `a bundle that rewrites pushed commits is refused as the done push refuses it, with no event`() {
        git.failures += IllegalStateException("${GitPush.REWRITES}smith/issue-21 (the branch is at abc; the bundle's HEAD at def does not contain it)")
        val said = error(mcp.call("push_chunk", mapOf("bundle" to b64("rewrite")), work))
        assertTrue(said.startsWith("push_chunk refused: the bundle rewrites commits already pushed to smith/issue-21"), said)
        assertTrue("the branch is at abc" in said, said)
        assertEquals(listOf("rewrite"), git.attempts, "the push was tried and refused by git")
        assertEquals(emptyList<Any>(), git.pushes)
        assertTrue(published.none { it is EngineEvent.Push })
    }
}
