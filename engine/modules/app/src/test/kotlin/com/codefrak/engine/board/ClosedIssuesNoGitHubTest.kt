package com.codefrak.engine.board

import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemDetails
import com.codefrak.engine.workitems.WorkItemSourceRef
import com.codefrak.engine.workitems.WorkItemStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/**
 * The done list and its Done search (`q`) with no GitHub client wired (#100053): [ClosedIssues] is built over the store
 * alone, its [WorkItemDetails] given an [IssueDetails] that fails any call, so a GitHub read would fail the test.
 */
@SpringBootTest
class ClosedIssuesNoGitHubTest(
    @Autowired val store: WorkItemStore, @Autowired val repos: TargetRepos, @Autowired val handled: HandledIssues,
    @Autowired val history: HistoryService, @Autowired val jdbc: JdbcClient,
) {
    private val noGitHub = Proxy.newProxyInstance(IssueDetails::class.java.classLoader, arrayOf(IssueDetails::class.java)) { _, m, _ ->
        throw AssertionError("GitHub called: ${m.name}")
    } as IssueDetails
    private val closed by lazy { ClosedIssues(repos, handled, history, WorkItemDetails(store, noGitHub)) }

    @BeforeEach
    fun reset() {
        listOf("engine.target_repo", "engine.handled_issue", "engine.work_item").forEach { jdbc.sql("DELETE FROM $it").update() }
        repos.add("acme/one"); repos.add("acme/two")
    }

    private fun ended(repo: String, title: String, body: String, state: String = WorkItem.DONE) {
        val item = store.create(NewWorkItem(repo, "# $title\n\n$body", "chore", WorkItemSourceRef(WorkItemSourceRef.RAISED)))
        store.setState(item.id, state, "closed")
    }

    @Test
    fun `the closed listing reads the store alone`() {
        ended("acme/one", "Blue widget", "Make it blue")
        ended("acme/two", "Drop the cache", "not needed", WorkItem.CANCELLED)
        store.create(NewWorkItem("acme/one", "# Still open\n\nwidget", "chore", WorkItemSourceRef(WorkItemSourceRef.RAISED)))
        val board = closed.list(50)
        assertEquals(setOf("Blue widget", "Drop the cache"), board.issues.map { it.title }.toSet())
        assertEquals(emptyList(), board.failures)
        assertEquals(listOf("Blue widget"), closed.list(50, repo = "acme/one").issues.map { it.title })
    }

    @Test
    fun `the Done search finds by title or description, case-insensitive, with no GitHub read`() {
        ended("acme/one", "Explorer search", "the tree")
        ended("acme/two", "Unrelated", "about the EXPLORER pane")
        ended("acme/two", "Other", "nothing here")
        assertEquals(setOf("Explorer search", "Unrelated"), closed.list(50, q = "explorer").issues.map { it.title }.toSet())
        assertEquals(listOf("Explorer search"), closed.list(50, repo = "acme/one", q = "EXPLORER").issues.map { it.title })
        assertEquals(emptyList(), closed.list(50, q = "absent").issues)
    }
}
