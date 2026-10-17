package com.codefrak.engine.postdeploy

import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.intake.testFiler
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.value.TypedValue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [FileCheckBugDelegate]: the bug a `failed` check files, its body, labels and trailer, on a mocked execution. Plain JUnit. */
class FileCheckBugDelegateTest {
    private val vars = mutableMapOf<String, Any?>(
        Vars.REPO to "acme/widgets", Vars.ISSUE to 41L, Vars.TIER to 6, Vars.ASKED_BY to "lundmatt1",
        Vars.PR_URL to "https://github.com/acme/widgets/pull/50", Vars.MERGE_SHA to "abc1234",
    )
    private val execution = mock(DelegateExecution::class.java).also { e ->
        `when`(e.processInstanceId).thenReturn("flow-1")
        `when`(e.getVariable(anyString())).thenAnswer { vars[it.getArgument(0)] }
        doAnswer {
            val v = it.getArgument<Any?>(1)
            vars[it.getArgument(0)] = if (v is TypedValue) v.value else v
            null
        }.`when`(e).setVariable(anyString(), any())
    }
    private val github = TicketRecorder()
    private val delegate = FileCheckBugDelegate(testFiler(github))

    @Test
    fun `a failed result's bug is filed in the ticket's repo at the original's tier, p2, by its asker, with the Context`() {
        vars[Vars.OUTPUT] = """{"outcome":"failed","summary":"Wrong.","bug":{"title":"Tile is blank after the deploy","body":"The tile shows nothing.\n\nSteps: open Analytics."}}""".toByteArray()
        delegate.execute(execution)
        val (repo, issue) = github.created.single()
        assertEquals("acme/widgets", repo)
        assertEquals("Tile is blank after the deploy", issue.title)
        assertEquals(listOf("bug"), issue.labels)
        assertEquals(
            "The tile shows nothing.\n\nSteps: open Analytics.\n\n## Context\n\n" +
                "- Found by the post-deployment check of #41 (https://github.com/acme/widgets/pull/50), merged as `abc1234`.\n" +
                "- Filed by the engine from the check Smith's result; #41 stays closed.\n\n" +
                "<!-- engine: check of #41 · tier 6 · p2 · by lundmatt1 -->",
            issue.body,
        )
        assertEquals(100L, vars[Vars.CHECK_BUG_TICKET])
    }

    @Test
    fun `without a tier or an asker the trailer has neither, and the Context has no PR or sha it does not know`() {
        vars.remove(Vars.TIER); vars.remove(Vars.ASKED_BY); vars.remove(Vars.PR_URL); vars.remove(Vars.MERGE_SHA)
        vars[Vars.OUTPUT] = """{"outcome":"failed","summary":"Wrong.","bug":{"title":"Blank","body":"Nothing."}}""".toByteArray()
        delegate.execute(execution)
        val body = github.created.single().second.body
        assertTrue("- Found by the post-deployment check of #41.\n" in body, body)
        assertTrue(body.endsWith("<!-- engine: check of #41 · p2 -->"), body)
    }

    @Test
    fun `a result that is not failed files nothing`() {
        vars[Vars.OUTPUT] = """{"outcome":"passed","summary":"Fine."}""".toByteArray()
        val e = assertFailsWith<IllegalStateException> { delegate.execute(execution) }
        assertTrue("not 'failed'" in e.message!!, e.message)
        assertEquals(emptyList(), github.created)
    }
}
