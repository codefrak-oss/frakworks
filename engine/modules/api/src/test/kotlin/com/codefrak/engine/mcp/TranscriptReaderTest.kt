package com.codefrak.engine.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

class TranscriptReaderTest {
    private val sent = mutableListOf<Pair<URI, String?>>()

    private fun reader(vm: String = "http://vm:8428", answer: (URI, String?) -> Pair<Int, String>) =
        TranscriptReader("http://vl:9428/", vm, Duration.ofSeconds(1)).also { r ->
            r.http = TranscriptReader.Http { uri, form -> sent += uri to form; answer(uri, form) }
        }

    private fun query(form: String?) = URLDecoder.decode(form!!.removePrefix("query="), StandardCharsets.UTF_8)

    private val init = """{"type":"system","subtype":"init","session_id":"abcdef123456","model":"claude-opus-5-5","timestamp":"2026-09-27T10:00:01.000Z"}"""
    private val say = """{"type":"assistant","timestamp":"2026-09-27T10:00:02.000Z","message":{"content":[{"type":"text","text":"  hello\n  there "},{"type":"tool_use","name":"Bash","input":{"command":"ls"}}]}}"""

    @Test
    fun `a live run is read from its host's newest session, oldest event first`() {
        val r = reader { uri, form ->
            when {
                uri.path == "/api/v1/query" -> 200 to """{"data":{"result":[{"metric":{"host":"engine","kind":"job","model":"claude-opus-5-5","issue":"7"}}]}}"""
                "limit 1 " in query(form) -> 200 to """{"_time":"2026-09-27T10:00:02Z","filename":"issue-7-work-r1.log","host":"engine","model":"m"}"""
                else -> 200 to listOf(
                    mapOf("_time" to "2026-09-27T10:00:03Z", "_msg" to """{"type":"user"}"""),
                    mapOf("_time" to "2026-09-27T10:00:02Z", "_msg" to say),
                    mapOf("_time" to "2026-09-27T10:00:01Z", "_msg" to "not json"),
                    mapOf("_time" to "2026-09-27T10:00:01Z", "_msg" to init),
                ).joinToString("\n") { mcpJson.writeValueAsString(it) }
            }
        }
        val doc = r.read(7)
        assertTrue(doc.live && doc.liveKnown)
        assertEquals("engine", doc.host)
        assertEquals("job", doc.kind)
        assertEquals("claude-opus-5-5", doc.model)
        assertEquals("issue-7-work-r1.log", doc.session)
        assertNull(doc.error)
        assertEquals(listOf(
            listOf("hello there", "→ Bash  ls"),
        ), doc.events.map { it.lines })

        assertEquals("smith_run_active{issue=\"7\"}", URLDecoder.decode(sent[0].first.rawQuery.removePrefix("query="), StandardCharsets.UTF_8))
        assertEquals("http://vl:9428/select/logsql/query", sent[1].first.toString())
        assertEquals("_time:24h issue:=\"7\" AND host:=\"engine\" | sort by (_time) desc | limit 1 | fields _time, filename, host, phase, model", query(sent[1].second))
        assertEquals("_time:24h issue:=\"7\" AND host:=\"engine\" AND filename:=\"issue-7-work-r1.log\" | sort by (_time) desc | limit 500 | fields _time, _msg, filename",
            query(sent[2].second))
    }

    @Test
    fun `a dead VictoriaMetrics costs only the live flag, and no session is no transcript`() {
        val r = reader { uri, _ -> if (uri.path == "/api/v1/query") 502 to "" else 200 to "" }
        val doc = r.read(7)
        assertFalse(doc.liveKnown)
        assertNull(doc.session)
        assertEquals("live-run lookup failed: VictoriaMetrics HTTP 502", doc.error)
        assertEquals("_time:24h issue:=\"7\" | sort by (_time) desc | limit 1 | fields _time, filename, host, phase, model", query(sent[1].second))
    }

    @Test
    fun `no metrics URL skips the live lookup, and a failed log read is said`() {
        val r = reader(vm = "") { _, _ -> 500 to "" }
        val doc = r.read(7)
        assertFalse(doc.liveKnown)
        assertEquals("transcript lookup failed: VictoriaLogs HTTP 500", doc.error)
        assertEquals(1, sent.size)
    }

    @Test
    fun `result and Codex events render as the transcript pane does`() {
        fun f(json: String) = TranscriptFormat.formatEvent(mcpJson.readValue(json, Map::class.java))
        // No time column (#934): a line starts with its text.
        val t = ""
        assertEquals(listOf("→ Read  /work/repo/README.md", "→ Bash  List files", "→ TodoWrite"),
            f("""{"type":"assistant","timestamp":"2026-09-27T10:00:02.000Z","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/work/repo/README.md"}},{"type":"tool_use","name":"Bash","input":{"command":"ls","description":"List files"}},{"type":"tool_use","name":"TodoWrite","input":{}}]}}"""))
        assertEquals(emptyList<String>(), f(init))
        assertEquals(listOf("$t── result: success · 12 turns · 61s · ~$0.1235"),
            f("""{"type":"result","subtype":"success","num_turns":12,"duration_ms":61500,"total_cost_usd":0.123456}"""))
        assertEquals(listOf("$t── result: error_max_turns · is_error · null turns · 0s · ~\$0\n   ↳ out of turns"),
            f("""{"type":"result","subtype":"error_max_turns","is_error":true,"result":"out of turns"}"""))
        assertEquals(listOf("$t── result: success · 30 tokens"), f("""{"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":20}}"""))
        assertEquals(listOf("$t→ command_execution  git status"), f("""{"type":"item.completed","item":{"type":"command_execution","command":"git  status"}}"""))
        assertEquals(emptyList<String>(), f("""{"type":"item.completed","item":{"type":"error","message":"${TranscriptFormat.CODEX_HOOK_TRUST_WARNING}"}}"""))
        assertEquals("ab…", TranscriptFormat.truncate("ab😀c", 2))
        assertEquals("ab😀…", TranscriptFormat.truncate("ab😀c", 3))
    }

    @Test
    fun `a named run started three days ago is queried from its own start, not the 24h window`() {
        val started = Instant.parse("2026-10-03T10:00:00Z")
        val r = reader { _, _ -> 200 to mcpJson.writeValueAsString(mapOf("_time" to "2026-10-03T10:00:02Z", "_msg" to say, "host" to "engine", "model" to "m")) }
        val doc = r.read(7, NamedRun("run-9", "review", started))
        assertEquals(1, sent.size)
        val q = query(sent[0].second)
        assertEquals("_time:>=2026-10-03T09:59:00Z issue:=\"7\" AND filename:=\"issue-7-review-run-9.log\" | sort by (_time) desc | limit 500 | fields _time, _msg, filename, host, model", q)
        assertFalse(q.contains(TranscriptReader.WINDOW))
        assertEquals("issue-7-review-run-9.log", doc.session)
        assertEquals("engine", doc.host)
        assertEquals("review", doc.kind)
        assertEquals(listOf(listOf("hello there", "→ Bash  ls")), doc.events.map { it.lines })
    }

    @Test
    fun `a named run with nothing shipped has no session`() {
        val doc = reader { _, _ -> 200 to "" }.read(7, NamedRun("run-9", "work", Instant.parse("2026-10-03T10:00:00Z")))
        assertNull(doc.session)
        assertNull(doc.error)
    }
}
