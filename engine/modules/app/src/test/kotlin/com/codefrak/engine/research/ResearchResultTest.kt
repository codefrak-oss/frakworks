package com.codefrak.engine.research

import com.codefrak.engine.proposal.PitchRulesTest
import com.codefrak.engine.smith.ReadResultDelegate
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The research result's rules, in `problems()` and through `readResult` (no Spring, no engine). */
class ResearchResultTest {
    private val page = PitchRulesTest.EXAMPLE

    private fun execution(vars: Map<String, Any?>): Pair<DelegateExecution, MutableMap<String, Any?>> {
        val set = mutableMapOf<String, Any?>()
        val execution = mock(DelegateExecution::class.java)
        `when`(execution.processInstanceId).thenReturn("research-1")
        `when`(execution.getVariable(anyString())).thenAnswer { vars[it.getArgument<String>(0)] ?: set[it.getArgument<String>(0)] }
        `when`(execution.setVariable(anyString(), org.mockito.ArgumentMatchers.any())).thenAnswer { set[it.getArgument<String>(0)] = it.getArgument(1); null }
        return execution to set
    }

    private fun delegate() = ReadResultDelegate(listOf(ResearchResultHandler()))

    private fun result(html: String = page, md: String = "# Q\n\nA\n\nCitations\n- x", summary: String = "A.") =
        """{"outcome":"answered","summary":"$summary","answer":{"md":${jsonString(md)},"html":${jsonString(html)}},"questions":[]}"""

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun `answered needs md, html passing the pitch rules, and a summary, and ask needs a question`() {
        assertEquals(emptyList(), ResearchResult.parse(result().toByteArray()).problems())
        val bare = ResearchResult("answered", ResearchAnswer("", ""), "")
        assertTrue(bare.problems().any { "answer.md" in it } && bare.problems().any { "answer.html" in it } && bare.problems().any { "summary" in it }, bare.problems().toString())
        val remote = ResearchResult("answered", ResearchAnswer("A", """<html><head><link rel="stylesheet" href="https://x/y.css"></head><body><svg/></body></html>"""), "A.")
        assertTrue(remote.problems().any { "answer.html: no <link>" in it }, remote.problems().toString())
        assertEquals(listOf("outcome 'ask' needs a question"), ResearchResult("ask").problems())
    }

    @Test
    fun `readResult accepts an answered result, refuses a failing page, and refuses a bundle`() {
        val (ok, set) = execution(mapOf(Vars.RESULT to "success", Vars.MODE to "research", Vars.OUTPUT to result().toByteArray()))
        delegate().execute(ok)
        assertEquals("answered", set["outcome"])
        // The delegate sets a typed value (as every delegate does for bytes); the mocked execution keeps it as such.
        assertTrue((set[Vars.RESEARCH] as? org.operaton.bpm.engine.variable.value.BytesValue)?.value is ByteArray, "${set[Vars.RESEARCH]}")

        val (failing, _) = execution(mapOf(Vars.RESULT to "success", Vars.MODE to "research",
            Vars.OUTPUT to result(html = "<html><body>no svg, no dark rule, no viewport</body></html>").toByteArray()))
        val refused = assertFailsWith<IllegalStateException> { delegate().execute(failing) }
        assertTrue("answer.html: no inline <svg>" in refused.message!!, refused.message)

        val (bundled, _) = execution(mapOf(Vars.RESULT to "success", Vars.MODE to "research", Vars.OUTPUT to result().toByteArray(),
            Vars.BUNDLE to "bundle".toByteArray()))
        val commits = assertFailsWith<IllegalStateException> { delegate().execute(bundled) }
        assertTrue("a research run commits nothing" in commits.message!!, commits.message)
    }
}
