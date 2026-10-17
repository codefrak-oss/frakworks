package com.codefrak.engine.repos

import com.codefrak.engine.ci.CheckRun
import com.codefrak.engine.ci.FakeCheckRuns
import com.codefrak.engine.github.BranchRule
import com.codefrak.engine.github.FakeRepoSetupGitHub
import com.codefrak.engine.github.RepoHook
import com.codefrak.engine.github.RepoSettings
import com.codefrak.engine.github.RepoTokens
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RepoSetupCheck] against a fake GitHub. Plain JUnit, no Spring. */
class RepoSetupCheckTest {
    private val github = FakeRepoSetupGitHub()
    private val runs = FakeCheckRuns()
    private val setup = RepoSetupCheck(github, runs, RepoTokens { "t0k" })
    private val repo = "acme/widgets"
    private val row = TargetRepo(repo, OffsetDateTime.parse("2026-09-26T10:00:00Z"), "build")

    /** Every item in place: labels, an open default branch, the check on its head, the wake hook. */
    private fun fullySetUp() {
        github.repos[repo] = RepoSettings("main", push = true, allowSquashMerge = true)
        github.labels[repo] = RepoSetupCheck.LABELS.keys.map { it.uppercase() }.toMutableList()
        github.commits[repo] = listOf("c3", "c2", "c1")
        runs.bySha["c3"] = CheckRun(1, "completed", "success", "")
        github.hooks[repo] = listOf(RepoHook(RepoSetupCheck.HOOK_EVENTS, true))
    }

    @Test
    fun `a fully set-up repo reports every item in place`() {
        fullySetUp()
        val report = setup.check(row)
        assertEquals(SetupReport(repo, true, true, true, emptyList(), BranchSetup("main", false, requiresReviews = false, forbidsSquash = false),
            CheckHistory("build", true, 3), WebhookSetup("present", github.hooks[repo])), report)
        assertEquals(emptyList(), github.writes, "the check writes nothing")
    }

    @Test
    fun `an empty repo reports its default branch absent and skips the check's history`() {
        fullySetUp()
        github.emptyRepos += repo
        val report = setup.check(row)
        assertEquals(SetupReport(repo, true, true, true, emptyList(), BranchSetup("main", false, null, null, exists = false),
            null, WebhookSetup("present", github.hooks[repo])), report)
        assertEquals(emptyList(), runs.asked, "no check-runs lookup")
        assertEquals(emptyList(), github.writes, "the check writes nothing")
    }

    @Test
    fun `missing labels are listed, and creating them twice creates nothing the second time`() {
        fullySetUp()
        github.labels[repo] = mutableListOf("bug", "Chore", "wontfix")
        assertEquals(listOf("authorized", "feature", "feature:auto", "feature:confirm", "proposal", "research", "intake"), setup.check(row).missingLabels)
        assertEquals(emptyList(), github.writes)

        assertEquals(listOf("authorized", "feature", "feature:auto", "feature:confirm", "proposal", "research", "intake"), setup.createLabels(repo))
        assertEquals(emptyList(), setup.createLabels(repo))
        assertEquals(7, github.writes.size)
        assertEquals(emptyList(), setup.check(row).missingLabels)
    }

    @Test
    fun `required reviews from a ruleset or a classic protection are reported, and unknown when the protection is unreadable`() {
        fullySetUp()
        github.rules[repo] = listOf(BranchRule("pull_request", requiredReviews = 1, allowedMergeMethods = listOf("merge", "rebase")))
        setup.check(row).defaultBranch!!.let {
            assertEquals(true, it.requiresReviews)
            assertEquals(true, it.forbidsSquash, "the ruleset leaves squash out")
        }

        github.rules.remove(repo)
        github.protectedBranches += repo
        github.classicReviews[repo] = 2
        assertEquals(BranchSetup("main", true, requiresReviews = true, forbidsSquash = false), setup.check(row).defaultBranch)

        github.classicReviews.remove(repo)
        assertNull(setup.check(row).defaultBranch!!.requiresReviews, "protected, and the token may not read the protection")

        github.repos[repo] = RepoSettings("main", push = true, allowSquashMerge = false)
        assertEquals(true, setup.check(row).defaultBranch!!.forbidsSquash, "the repo disallows squash merges")
    }

    @Test
    fun `a check that never ran on the default branch, or no check at all`() {
        fullySetUp()
        runs.bySha.clear()
        assertEquals(CheckHistory("build", false, 3), setup.check(row).ciCheck)
        assertEquals(listOf("c3", "c2", "c1"), runs.asked.map { it.second })

        runs.bySha["c1"] = CheckRun(2, "completed", "failure", "")
        assertTrue(setup.check(row).ciCheck!!.ranOnDefaultBranch, "a failed run still ran")

        assertNull(setup.check(row.copy(ciCheck = null)).ciCheck, "a row with no check has no history to look for")
    }

    @Test
    fun `a refused hooks call is unknown, and no wake hook is missing`() {
        fullySetUp()
        github.hooks.remove(repo)
        assertEquals(WebhookSetup("unknown", null), setup.check(row).webhook)

        github.hooks[repo] = listOf(RepoHook(listOf("push"), true))
        assertEquals("missing", setup.check(row).webhook!!.status)
    }

    /**
     * The stream's `ticket` frames need `issue_comment` and `pull_request` too, and its `push` frames need `push`
     * (engine/README.md, "Events"): the old pair alone, or the four without `push`, is missing.
     */
    @Test
    fun `a hook with the wake pair alone or the four without push is missing, one with the five or every event is present, an inactive one counts for nothing`() {
        fullySetUp()
        github.hooks[repo] = listOf(RepoHook(listOf("issues", "check_suite"), true))
        assertEquals("missing", setup.check(row).webhook!!.status)
        github.hooks[repo] = listOf(RepoHook(listOf("issues", "check_suite", "issue_comment", "pull_request"), true))
        assertEquals("missing", setup.check(row).webhook!!.status, "without push: no push frames")
        github.hooks[repo] = listOf(RepoHook(listOf("issues", "check_suite", "pull_request", "push"), true), RepoHook(listOf("issue_comment"), true))
        assertEquals("missing", setup.check(row).webhook!!.status, "one hook must send them all")
        github.hooks[repo] = listOf(RepoHook(listOf("pull_request", "issue_comment", "check_suite", "issues", "push"), true))
        assertEquals("present", setup.check(row).webhook!!.status)
        github.hooks[repo] = listOf(RepoHook(listOf("*"), true))
        assertEquals("present", setup.check(row).webhook!!.status)
        github.hooks[repo] = listOf(RepoHook(RepoSetupCheck.HOOK_EVENTS, false))
        assertEquals("missing", setup.check(row).webhook!!.status)
    }

    @Test
    fun `a repo the token cannot read says so and reads nothing else`() {
        val report = setup.check(row)
        assertFalse(report.readable)
        assertEquals(SetupReport(repo, true, false), report)
        assertFalse(setup.readable(repo))
    }
}
