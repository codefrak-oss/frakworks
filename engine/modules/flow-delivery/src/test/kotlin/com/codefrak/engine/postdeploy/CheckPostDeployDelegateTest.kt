package com.codefrak.engine.postdeploy

import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.value.TypedValue
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [CheckPostDeployDelegate]: the section read from the work's summary, and what blocks the merge, on a mocked execution. Plain JUnit. */
class CheckPostDeployDelegateTest {
    private val vars = mutableMapOf<String, Any?>("repo" to "acme/tickets", Vars.PR to 7L, Vars.PR_URL to "https://github.com/acme/tickets/pull/7")
    private val execution = mock(DelegateExecution::class.java).also { e ->
        `when`(e.processDefinitionId).thenReturn("chore:3:abc")
        `when`(e.getVariable(anyString())).thenAnswer { vars[it.getArgument(0)] }
        doAnswer {
            val v = it.getArgument<Any?>(1)
            vars[it.getArgument(0)] = if (v is TypedValue) v.value else v
            null
        }.`when`(e).setVariable(anyString(), any())
        doAnswer { vars.remove(it.getArgument<String>(0)); null }.`when`(e).removeVariable(anyString())
    }
    private val withServer = mutableSetOf<String>()
    private val delegate = CheckPostDeployDelegate { withServer.contains(it) }

    private fun summary(text: String) {
        vars[Vars.WORK] = com.codefrak.engine.engineJson.writeValueAsBytes(mapOf("outcome" to "done", "title" to "Tidy", "summary" to text, "addressed" to emptyList<Any>()))
    }
    private val section = "Tidied it.\n\n## POST-DEPLOYMENT-CHECK\n\nThe next question's answer lands in the new table.\nRe-check: 4h\n"

    @Test
    fun `a summary without the section leaves nothing set and nothing blocked`() {
        summary("Tidied it.")
        vars[Vars.POST_DEPLOY_BLOCKED] = "stale from an earlier pass"
        delegate.execute(execution)
        assertNull(vars[Vars.POST_DEPLOY_CHECK]); assertNull(vars[Vars.POST_DEPLOY_BLOCKED])
    }

    @Test
    fun `a section on a repo with an ops server is set, and the merge goes ahead`() {
        summary(section); withServer += "acme/tickets"
        delegate.execute(execution)
        val check = PostDeployCheck.of(vars[Vars.POST_DEPLOY_CHECK] as ByteArray)!!
        assertEquals("The next question's answer lands in the new table.", check.what)
        assertEquals(Duration.ofHours(4), check.recheck)
        assertNull(vars[Vars.POST_DEPLOY_BLOCKED])
    }

    @Test
    fun `a section on a repo with no ops server blocks the merge, naming the call and the contract`() {
        summary(section)
        delegate.execute(execution)
        assertTrue(vars[Vars.POST_DEPLOY_CHECK] is ByteArray, "the section is kept for the pass after the server is added")
        val why = vars[Vars.POST_DEPLOY_BLOCKED] as String
        assertTrue("add_repo_mcp_server(repo: \"acme/tickets\", role: \"ops\"" in why && "ops/1" in why && "is_deployed" in why, why)
    }

    @Test
    fun `the engine's own repo needs no ops server`() {
        summary(section)
        val own = RepoOpsServers(mock(com.codefrak.engine.repos.RepoMcpServers::class.java), " codefrak-oss/frakworks ")
        vars["repo"] = "codefrak-oss/frakworks"
        CheckPostDeployDelegate(own).execute(execution)
        assertTrue(vars[Vars.POST_DEPLOY_CHECK] is ByteArray)
        assertNull(vars[Vars.POST_DEPLOY_BLOCKED])
    }

    @Test
    fun `a section that cannot be read blocks the merge with the parse error, and sets no check`() {
        summary("## POST-DEPLOYMENT-CHECK\n\nWatch it.\nRe-check: soon\n"); withServer += "acme/tickets"
        delegate.execute(execution)
        assertNull(vars[Vars.POST_DEPLOY_CHECK])
        val why = vars[Vars.POST_DEPLOY_BLOCKED] as String
        assertTrue("https://github.com/acme/tickets/pull/7" in why && "'soon' is not a duration" in why, why)
    }
}
