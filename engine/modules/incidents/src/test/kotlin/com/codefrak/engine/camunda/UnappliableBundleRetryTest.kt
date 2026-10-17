package com.codefrak.engine.camunda

import com.codefrak.engine.camunda.IncidentRetrier.Companion.SMITH_RUN
import com.codefrak.engine.github.GitPush
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An unappliable bundle (engine/README.md, "Incidents") is a rejected result: its retry reruns the Smith, not the job. */
class UnappliableBundleRetryTest {
    private val message = "the smith's result breaks the rules: the bundle cannot be applied: its prerequisite commit(s) " +
        "7a7dc87b1bb9711545a3aaf8beab904a51203827 are not on any branch of acme/widgets (the Smith's bundle starts from a commit that never reached the remote)"

    @Test
    fun `a smith-run whose result is success but whose bundle is unappliable is rerun, and its stored bundle dropped`() {
        assertTrue(IncidentRetrier.reruns(SMITH_RUN, "success", message))
        assertTrue(IncidentRetrier.unappliable(message))
    }

    @Test
    fun `the old unprefixed message, or any other failure of a successful run, retries the job`() {
        val old = message.removePrefix("the smith's result breaks the rules: ")
        assertFalse(IncidentRetrier.reruns(SMITH_RUN, "success", old))
        assertFalse(IncidentRetrier.unappliable(old))
        assertFalse(IncidentRetrier.reruns(SMITH_RUN, "success", "GitHub answered 500"))
        assertFalse(IncidentRetrier.reruns("chore", "success", message))
        assertFalse(IncidentRetrier.unappliable("the smith's result breaks the rules: a done needs a bundle"))
    }

    private val missing = "the smith's result breaks the rules: the bundle cannot be applied: it has no refs/heads/smith/issue-1471 " +
        "(it has refs/heads/other; the Smith's bundle was not made from its branch)"

    @Test
    fun `a push of a bundle without the branch reruns the work call activity, and its stored bundle is dropped`() {
        assertTrue(missing.startsWith(GitPush.MISSING_BRANCH))
        assertEquals("call-do-work", IncidentRetrier.pushRerun("bug", "task-open-pr", missing))
        assertEquals("call-do-work", IncidentRetrier.pushRerun("chore", "task-open-pr", missing))
        assertEquals("call-draft", IncidentRetrier.pushRerun("proposal", "task-open-pr", missing))
        assertTrue(IncidentRetrier.unappliable(missing))
    }

    @Test
    fun `a push of a bundle that rewrites the branch reruns the work call activity, and its stored bundle is dropped`() {
        val rewrites = "the smith's result breaks the rules: the bundle cannot be applied: it rewrites smith/issue-2165 (the branch is at " +
            "aa4358a; the bundle's HEAD at 1234567 does not contain it; the Smith's bundle was not made on top of the branch)"
        assertTrue(rewrites.startsWith(GitPush.REWRITES))
        assertEquals("call-do-work", IncidentRetrier.pushRerun("feature", "task-open-pr", rewrites))
        assertEquals("call-draft", IncidentRetrier.pushRerun("proposal", "task-open-pr", rewrites))
        assertTrue(IncidentRetrier.unappliable(rewrites))
        assertNull(IncidentRetrier.pushRerun("bug", "task-merge", rewrites))
    }

    @Test
    fun `any other push failure, or the message elsewhere, retries the job`() {
        assertNull(IncidentRetrier.pushRerun("bug", "task-open-pr", "git fetch failed (128): fatal: couldn't find remote ref refs/heads/smith/issue-1471"))
        assertNull(IncidentRetrier.pushRerun("bug", "task-open-pr", message))
        assertNull(IncidentRetrier.pushRerun("bug", "task-merge", missing))
        assertNull(IncidentRetrier.pushRerun("intake", "task-open-pr", missing))
    }

    @Test
    fun `a rerun after a refused push carries a push finding that quotes the refusal and says to merge, not rewrite`() {
        val rewrites = "the smith's result breaks the rules: the bundle cannot be applied: it rewrites smith/issue-2165 (the branch is at aa4358a)"
        val first = IncidentRetrier.pushFinding(1, "smith/issue-2165", rewrites)
        assertEquals("push-1-1", first.id)
        assertEquals("push", first.review)
        assertTrue(rewrites in first.ask)
        assertTrue("fast-forward" in first.ask && "merge origin/" in first.ask && "`ask`" in first.ask)
        assertEquals("push-2-1", IncidentRetrier.pushFinding(2, "smith/issue-2165", missing).id)
    }
}
