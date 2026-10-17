package com.codefrak.engine.smith

import com.codefrak.engine.github.BundleChanges
import com.codefrak.engine.github.CreatedPull
import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.github.MergeResult
import com.codefrak.engine.github.OpenPull
import com.codefrak.engine.github.Pushed
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution
import kotlin.test.assertEquals

/** [WorkResultReader.read]: the ciNotMine streak and the pushed-ahead bundle, outside the full [ReadResultDelegate]. */
class WorkResultReaderTest {
    private class FakeGitPush(private val bundle: ByteArray) : GitPush {
        override fun push(repo: String, branch: String, bundle: ByteArray, default: String): Pushed = error("not used")
        override fun mergeDefault(repo: String, branch: String, default: String): MergeResult = error("not used")
        override fun headBundle(repo: String, branch: String, default: String): ByteArray = error("not used")
        override fun aheadBundle(repo: String, branch: String, default: String): ByteArray? =
            bundle.takeIf { repo == "o/r" && branch == "smith/issue-9" && default == "main" }
        override fun proposalChanges(repo: String, branch: String, default: String, bundle: ByteArray, path: String, pitch: String?): BundleChanges = error("not used")
        override fun commitEdit(repo: String, branch: String, path: String, message: String, edit: (String) -> String): String? = error("not used")
        override fun commitFiles(repo: String, branch: String, default: String, files: Map<String, String>, message: String): String = error("not used")
    }

    private class FakeGitHubPulls : GitHubPulls {
        override fun defaultBranch(repo: String): String = "main"
        override fun open(repo: String, head: String, base: String, title: String, body: String): CreatedPull = error("not used")
        override fun openOnHead(repo: String, head: String): List<OpenPull> = error("not used")
        override fun merge(repo: String, number: Long, title: String): String = error("not used")
        override fun closePull(repo: String, number: Long) = error("not used")
        override fun reopenPull(repo: String, number: Long) = error("not used")
        override fun comment(repo: String, number: Long, body: String) = error("not used")
        override fun headSha(repo: String, number: Long): String? = error("not used")
    }

    private fun execution(vars: Map<String, Any?>): Pair<DelegateExecution, MutableMap<String, Any?>> {
        val set = mutableMapOf<String, Any?>()
        val execution = mock(DelegateExecution::class.java)
        `when`(execution.processInstanceId).thenReturn("work-1")
        `when`(execution.getVariable(anyString())).thenAnswer { vars[it.getArgument<String>(0)] ?: set[it.getArgument<String>(0)] }
        `when`(execution.setVariable(anyString(), any())).thenAnswer { set[it.getArgument<String>(0)] = it.getArgument(1); null }
        return execution to set
    }

    private fun workResult(outcome: String, declined: List<Declined> = emptyList()) =
        """{"outcome":"$outcome","title":"t","summary":"s","declined":${declined.joinToString(prefix = "[", postfix = "]") {
            """{"finding":"${it.finding}","why":"${it.why}"}"""
        }}}""".toByteArray()

    @Test
    fun `every ci finding declined as not mine sets ciNotMine true and the streak`() {
        val findings = listOf(Finding("a ci failure", id = "f1", review = "ci"))
        val findingsBytes = Brief.findingsBytes(findings)
        val reader = WorkResultReader(emptyList(), null, null, null)

        val (first, firstSet) = execution(mapOf(Vars.FINDINGS to findingsBytes, Vars.PR to 1L))
        val firstRead = reader.read(first, workResult("done", listOf(Declined("f1", "not this change"))))
        assertEquals(WorkRead.Accepted("done"), firstRead)
        assertEquals(true, firstSet[Vars.CI_NOT_MINE])
        assertEquals(1, firstSet[Vars.CI_NOT_MINE_ROUNDS])

        val (second, secondSet) = execution(mapOf(Vars.FINDINGS to findingsBytes, Vars.PR to 1L, Vars.CI_NOT_MINE_ROUNDS to 1))
        val secondRead = reader.read(second, workResult("done", listOf(Declined("f1", "not this change"))))
        assertEquals(WorkRead.Accepted("done"), secondRead)
        assertEquals(true, secondSet[Vars.CI_NOT_MINE])
        assertEquals(2, secondSet[Vars.CI_NOT_MINE_ROUNDS])
    }

    @Test
    fun `a done with no bundle takes the pushed-ahead branch's commits`() {
        val bundleBytes = "the bundle".toByteArray()
        val reader = WorkResultReader(emptyList(), null, FakeGitPush(bundleBytes), FakeGitHubPulls())

        val (execution, set) = execution(mapOf(Vars.REPO to "o/r", Vars.ISSUE to 9L))
        val read = reader.read(execution, workResult("done"))

        assertEquals(WorkRead.Accepted("done"), read)
        assertEquals(bundleBytes.toList(), (set[Vars.BUNDLE] as? org.operaton.bpm.engine.variable.value.BytesValue)?.value?.toList())
    }
}
