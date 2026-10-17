package com.codefrak.engine.workitems

import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One-way publish: off by default (V89), so no GitHub write; on, one create then edits and a close; never a read. */
@SpringBootTest
class WorkItemPublishTest(@Autowired val repos: TargetRepos, @Autowired val jdbc: JdbcClient) {
    private val github = RecordingGitHubIssueApi(mutableMapOf(1L to IssueSnapshot(1, "t", "b", "a")))
    private val store by lazy { WorkItemStore(jdbc, listOf(WorkItemPublisher(repos, GitHubWorkItemSource(github), jdbc))) }
    private val gate by lazy { WorkItemGate(store) }

    @BeforeEach
    fun seed() { jdbc.sql("DELETE FROM engine.work_item").update(); repos.add("acme/published") }

    @AfterEach
    fun drop() { repos.remove("acme/published") }

    private fun item() = store.create(NewWorkItem("acme/published", "Ship it\n\nthe details", "feature", WorkItemSourceRef(WorkItemSourceRef.ASK)))

    @Test
    fun `a repo at the migrated default publishes nothing`() {
        assertFalse(repos.find("acme/published")!!.publishWorkItems)
        val a = item()
        gate.start(a.id); gate.finish(a.id)
        assertEquals(emptyList(), github.calls)
        assertEquals(null, store.get(a.id)!!.publishedRef)
    }

    @Test
    fun `a repo that opts in gets one issue, edited as the item moves and closed when it is done, and nothing is read`() {
        repos.setPublishWorkItems("acme/published", true)
        val a = item()
        assertEquals("acme/published#100", store.get(a.id)!!.publishedRef)
        gate.start(a.id); gate.finish(a.id)
        assertEquals(listOf("create acme/published#100", "edit 100", "edit 100", "close 100 completed"), github.calls)
        assertTrue(github.calls.none { it.startsWith("issue ") }, "publish read GitHub: ${github.calls}")
    }
}
