package com.codefrak.engine.smith

import com.codefrak.engine.accounts.AiAccounts
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [SmithReruns]' policy, driven through a mocked [DelegateExecution] and [AiAccounts], via a fake [RunCaller]. The run's
 * model is `collect result`'s to pass, from the run's `engine.smith_run_stats` row (#1976), not a variable of the execution.
 */
class SmithRerunsTest {
    private class FakeCaller(private val key: String?, private val instance: String?) : RunCaller {
        override fun key(execution: DelegateExecution): String? = key
        override fun instance(execution: DelegateExecution): String? = instance
    }

    private fun execution(vars: MutableMap<String, Any?> = mutableMapOf()): DelegateExecution {
        val execution = mock(DelegateExecution::class.java)
        `when`(execution.processInstanceId).thenReturn("rerun-1")
        `when`(execution.getVariable(anyString())).thenAnswer { vars[it.getArgument<String>(0)] }
        `when`(execution.setVariable(anyString(), org.mockito.ArgumentMatchers.any())).thenAnswer { vars[it.getArgument<String>(0)] = it.getArgument(1); null }
        return execution
    }

    private fun reruns(caller: RunCaller, accounts: AiAccounts = mock(AiAccounts::class.java)) = SmithReruns(accounts, caller)

    @Test
    fun `the limit rerun lands on another account`() {
        val accounts = mock(AiAccounts::class.java)
        `when`(accounts.peek("anthropic", "claude-sonnet-5")).thenReturn("acct-2")
        val execution = execution()
        reruns(FakeCaller(REUSABLE_SMITH_RUN, "wrapper-1"), accounts)
            .decide(execution, "acct-1", "failed", SmithReruns.SessionLimit("acct-1", OffsetDateTime.now()), 1, model = "claude-sonnet-5")

        assertEquals(true, execution.getVariable(Vars.RERUN))
        assertNull(execution.getVariable(Vars.RERUN_AT))
        assertEquals("acct-1", execution.getVariable(Vars.LIMIT_RERUNS))
    }

    @Test
    fun `the timed rerun lands when every account is blocked`() {
        val accounts = mock(AiAccounts::class.java)
        `when`(accounts.peek("anthropic", "claude-sonnet-5")).thenReturn(null)
        val reset = OffsetDateTime.now().plusHours(1)
        `when`(accounts.blockedUntil("anthropic")).thenReturn(reset)
        val execution = execution()
        reruns(FakeCaller(REUSABLE_SMITH_RUN, "wrapper-1"), accounts)
            .decide(execution, "acct-1", "failed", SmithReruns.SessionLimit("acct-1", OffsetDateTime.now()), 1, model = "claude-sonnet-5")

        assertEquals(true, execution.getVariable(Vars.RERUN))
        assertEquals(java.util.Date.from(reset.toInstant()), execution.getVariable(Vars.RERUN_AT))
    }

    @Test
    fun `no rerun when the account is pinned`() {
        val accounts = mock(AiAccounts::class.java)
        `when`(accounts.peek("anthropic", "claude-sonnet-5")).thenReturn("acct-2")
        val execution = execution(mutableMapOf(Vars.ACCOUNT to "acct-1"))
        reruns(FakeCaller(REUSABLE_SMITH_RUN, "wrapper-1"), accounts)
            .decide(execution, "acct-1", "failed", SmithReruns.SessionLimit("acct-1", OffsetDateTime.now()), 1, model = "claude-sonnet-5")

        assertEquals(false, execution.getVariable(Vars.RERUN))
    }

    @Test
    fun `no rerun when the step already reran on that account`() {
        val accounts = mock(AiAccounts::class.java)
        `when`(accounts.peek("anthropic", "claude-sonnet-5")).thenReturn("acct-2")
        val execution = execution(mutableMapOf(Vars.LIMIT_RERUNS to "acct-1"))
        reruns(FakeCaller(REUSABLE_SMITH_RUN, "wrapper-1"), accounts)
            .decide(execution, "acct-1", "failed", SmithReruns.SessionLimit("acct-1", OffsetDateTime.now()), 1, model = "claude-sonnet-5")

        assertEquals(false, execution.getVariable(Vars.RERUN))
    }

    @Test
    fun `the one failure rerun, and its refusal the second time in the same wrapper`() {
        val execution = execution()
        val policy = reruns(FakeCaller(REUSABLE_SMITH_RUN, "wrapper-1"))
        policy.decide(execution, null, "failed", null, 1)

        assertEquals(true, execution.getVariable(Vars.RERUN))
        assertEquals("wrapper-1", execution.getVariable(Vars.FAIL_RERUN_OF))

        val second = execution(mutableMapOf(Vars.FAIL_RERUN_OF to "wrapper-1"))
        policy.decide(second, null, "failed", null, 1)
        assertEquals(false, second.getVariable(Vars.RERUN))
    }

    @Test
    fun `the eviction rerun, bound at MAX_EVICTION_RERUNS`() {
        val policy = reruns(FakeCaller(REUSABLE_SMITH_RUN, "wrapper-1"))
        var vars = mutableMapOf<String, Any?>()
        repeat(MAX_EVICTION_RERUNS) { i ->
            val execution = execution(vars)
            policy.decide(execution, null, "failed", null, 1, evicted = true)
            assertEquals(true, execution.getVariable(Vars.RERUN))
            vars = mutableMapOf(Vars.EVICTION_RERUNS to execution.getVariable(Vars.EVICTION_RERUNS))
        }
        val execution = execution(vars)
        policy.decide(execution, null, "failed", null, 1, evicted = true)
        assertEquals(false, execution.getVariable(Vars.RERUN))
    }

    @Test
    fun `no rerun for a run not called by reusable-smith-run`() {
        val accounts = mock(AiAccounts::class.java)
        `when`(accounts.peek("anthropic", "claude-sonnet-5")).thenReturn("acct-2")
        val execution = execution()
        reruns(FakeCaller(null, null), accounts)
            .decide(execution, "acct-1", "failed", SmithReruns.SessionLimit("acct-1", OffsetDateTime.now()), 1, model = "claude-sonnet-5")
        assertEquals(false, execution.getVariable(Vars.RERUN))

        val failExecution = execution()
        reruns(FakeCaller(null, null)).decide(failExecution, null, "failed", null, 1)
        assertEquals(false, failExecution.getVariable(Vars.RERUN))
    }
}
