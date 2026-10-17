package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionDigestTest {
    @Test
    fun `the digest keeps what the Smith said and called, drops tool results, and keeps the tail when over the cap`() {
        val lines = listOf(
            "smith: run=r mode=work",
            """{"type":"system","subtype":"init","tools":["Bash"]}""",
            """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"I will add a table."},""" +
                """{"type":"tool_use","name":"Bash","input":{"command":"ls engine","description":"List the engine"}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","content":"SECRET TOOL OUTPUT"}]}}""",
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/work/repo/a.kt"}}]}}""",
            """{"type":"result","subtype":"success","result":"Done."}""",
        )
        val digest = SessionDigest.digest(lines, 10_000)
        assertEquals(listOf(
            "log: smith: run=r mode=work",
            "said: I will add a table.",
            "tool Bash: List the engine",
            """tool Read: {"file_path":"/work/repo/a.kt"}""",
            "result: success Done.",
        ), digest.lines())

        val many = (1..200).map { """{"type":"assistant","message":{"content":[{"type":"text","text":"step $it"}]}}""" }
        val cut = SessionDigest.digest(many, 400)
        assertTrue(cut.length <= 400 + 40, cut)
        assertTrue(cut.lines().first() == "said: step 1", cut)
        assertTrue(cut.lines().last() == "said: step 200", cut)
        assertTrue(cut.lines().any { it.startsWith("[… ") && it.endsWith("steps left out …]") }, cut)
    }
}
