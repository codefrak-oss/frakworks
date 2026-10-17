package com.codefrak.engine.chore

import com.codefrak.engine.ci.CheckRun
import com.codefrak.engine.ci.CheckRuns
import com.codefrak.engine.ci.CiChecks
import com.codefrak.engine.ci.RerunAlreadyRunning
import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.value.TypedValue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [OpenPrDelegate] on a round that declined the check's failure (`ciNotMine`), with a fake [CheckRuns]. Plain JUnit. */
class OpenPrNotMineTest {
    private val vars = mutableMapOf<String, Any?>(
        Vars.REPO to "o/r", Vars.ISSUE to 7L, Vars.ROUND to 1, Vars.PR to 70L, Vars.CI_NOT_MINE to true,
        Vars.CI_RUN_ID to 11L, Vars.HEAD_SHA to "abc123", Vars.CI_CHECK to "engine", Vars.BUNDLE to byteArrayOf(1),
        Vars.WORK to """{"outcome":"done","title":"t","summary":"s","declined":[{"finding":"ci-1-1","why":"a flaky test"}]}""".toByteArray())
    private val execution = mock(DelegateExecution::class.java).also { e ->
        `when`(e.processDefinitionId).thenReturn("chore:3:abc")
        `when`(e.processInstanceId).thenReturn("pi")
        `when`(e.getVariable(anyString())).thenAnswer { vars[it.getArgument(0)] }
        doAnswer {
            val v = it.getArgument<Any?>(1)
            vars[it.getArgument(0)] = if (v is TypedValue) v.value else v
            null
        }.`when`(e).setVariable(anyString(), any())
    }
    private val pulls = mock(GitHubPulls::class.java)
    private val repos = mock(TargetRepos::class.java).also { `when`(it.securityReview("o/r")).thenReturn(true) }

    private class FakeRuns(val latest: CheckRun?, val answer: () -> Unit) : CheckRuns {
        val reran = mutableListOf<Long>()
        override fun latest(repo: String, sha: String, name: String) = latest
        override fun log(repo: String, checkRunId: Long) = ""
        override fun rerun(repo: String, checkRunId: Long) { reran += checkRunId; answer() }
    }

    private fun delegate(runs: CheckRuns) =
        OpenPrDelegate(mock(GitPush::class.java), pulls, mock(CiChecks::class.java), repos, runs)

    private fun assertWaitsOnTheRerun() {
        assertEquals(11L, vars[Vars.CI_RERUN_PENDING])
        assertEquals(false, vars[Vars.CI_NOT_MINE])
        verify(pulls).comment(anyString(), eq(70L), anyString())
    }

    @Test
    fun `a re-run already in progress on the head is taken, and nothing is posted`() {
        val runs = FakeRuns(CheckRun(12, "in_progress", null, "u")) { error("no re-run should be posted") }
        delegate(runs).execute(execution)
        assertTrue(runs.reran.isEmpty())
        assertWaitsOnTheRerun()
    }

    @Test
    fun `a re-run GitHub refuses as already running is taken, without a throw`() {
        val runs = FakeRuns(CheckRun(11, "completed", "failure", "u")) { throw RerunAlreadyRunning(11, "already running") }
        delegate(runs).execute(execution)
        assertEquals(listOf(11L), runs.reran)
        assertWaitsOnTheRerun()
    }

    @Test
    fun `any other refusal still throws, for Operaton's retries`() {
        val runs = FakeRuns(CheckRun(11, "completed", "failure", "u")) { throw IllegalStateException("boom") }
        assertFailsWith<IllegalStateException> { delegate(runs).execute(execution) }
        assertEquals(true, vars[Vars.CI_NOT_MINE])
    }
}
