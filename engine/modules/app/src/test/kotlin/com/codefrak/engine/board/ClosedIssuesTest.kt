package com.codefrak.engine.board

import com.codefrak.engine.feature.FiledChild
import com.codefrak.engine.feature.SplitState
import com.codefrak.engine.github.FakeIssueDetails
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.ListedTickets
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemSourceRef
import com.codefrak.engine.workitems.WorkItemStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.variable.Variables
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class ClosedIssuesTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var details: FakeIssueDetails
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var handled: HandledIssues
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var store: WorkItemStore

    private val t0 = java.time.OffsetDateTime.parse("2026-09-24T10:00:00Z")

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM engine.target_repo").update()
        jdbc.sql("DELETE FROM engine.handled_issue").update()
        jdbc.sql("DELETE FROM engine.work_item").update()
        details.failing.clear()
        repos.add("acme/one"); repos.add("acme/two")
    }

    private fun closed(repo: String, title: String, body: String = "", cancelled: Boolean = false): WorkItem {
        val item = store.create(NewWorkItem(repo, "# $title\n\n$body", "chore", WorkItemSourceRef(WorkItemSourceRef.ASK)))
        store.setState(item.id, if (cancelled) WorkItem.CANCELLED else WorkItem.DONE, "closed")
        return item
    }

    @Test
    fun `done and cancelled work items of every target repo, newest closed first, cut to the limit, and no GitHub read`() {
        closed("acme/one", "First", "Body of first")
        val two = closed("acme/two", "Second", cancelled = true)
        val three = closed("acme/one", "Third")
        store.create(NewWorkItem("acme/one", "# Open item\n\nwidget", "chore", WorkItemSourceRef(WorkItemSourceRef.ASK)))
        details.failing += "acme/one"; details.failing += "acme/two"
        mvc.get("/issues?state=closed&limit=2").andExpect {
            status { isOk() }
            jsonPath("$.issues.length()") { value(2) }
            jsonPath("$.issues[0].number") { value(three.ticket) }
            jsonPath("$.issues[0].repo") { value("acme/one") }
            jsonPath("$.issues[0].stateReason") { value("completed") }
            jsonPath("$.issues[1].number") { value(two.ticket) }
            jsonPath("$.issues[1].stateReason") { value("not_planned") }
            jsonPath("$.failures.length()") { value(0) }
        }
        mvc.get("/issues?state=closed&repo=acme/one").andExpect {
            jsonPath("$.issues.length()") { value(2) }
            jsonPath("$.issues[1].title") { value("First") }
            jsonPath("$.issues[1].body") { value("Body of first") }
        }
    }

    @Test
    fun `with q the closed work items whose title or description holds it, case-insensitive, and blank or absent q lists`() {
        val title = closed("acme/one", "Explorer search")
        val body = closed("acme/two", "Other", "the EXPLORER pane")
        closed("acme/one", "Unrelated")
        details.failing += "acme/one"; details.failing += "acme/two"
        mvc.get("/issues?state=closed&limit=50&q=explorer").andExpect {
            status { isOk() }
            jsonPath("$.issues.length()") { value(2) }
            jsonPath("$.issues[0].number") { value(body.ticket) }
            jsonPath("$.issues[1].number") { value(title.ticket) }
            jsonPath("$.failures.length()") { value(0) }
        }
        mvc.get("/issues?state=closed&limit=50&repo=acme/one&q=EXPLORER").andExpect {
            jsonPath("$.issues.length()") { value(1) }
            jsonPath("$.issues[0].number") { value(title.ticket) }
        }
        mvc.get("/issues?state=closed&limit=1&q=explorer").andExpect { jsonPath("$.issues.length()") { value(1) } }
        mvc.get("/issues?state=closed&limit=50").andExpect { jsonPath("$.issues.length()") { value(3) } }
        // A param, not a "%20%20" in the template: MockMvc would encode the % and send it as text.
        mvc.get("/issues") { param("state", "closed"); param("limit", "50"); param("q", "  ") }
            .andExpect { jsonPath("$.issues.length()") { value(3) } }
    }

    @Test
    fun `a bad state or a repo that is not a target repo is a 400`() {
        mvc.get("/issues?state=bogus").andExpect { status { isBadRequest() } }
        mvc.get("/issues?state=closed&repo=acme/elsewhere").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `without parameters it is still the board of open issues`() {
        ListedTickets(store, jdbc).list("acme/one", 5, "Open 5", listOf("chore"), t0, t0)
        mvc.get("/issues").andExpect {
            status { isOk() }
            jsonPath("$.issues[0].number") { value(5) }
            jsonPath("$.issues[0].kind") { value("chore") }
        }
    }

    @Test
    fun `a closed part of a split feature carries its parent, and any other closed item carries none`() {
        val part = closed("acme/one", "Part 1")
        val other = closed("acme/one", "Other")
        val split = SplitState(filed = listOf(
            FiledChild(0, "Part 1", part.ticket!!, "https://github.com/acme/one/issues/${part.ticket}", "chore", SplitState.MERGED, 7)))
        val feature = runtime.startProcessInstanceByKey("hello", mapOf("split" to Variables.byteArrayValue(split.bytes())))
        handled.claim("acme/one", 20, 2_000, "feature", "started", null)
        handled.started("acme/one", 20, 2_000, feature.id)
        mvc.get("/issues?state=closed&repo=acme/one").andExpect {
            status { isOk() }
            jsonPath("$.issues[0].number") { value(other.ticket) }
            jsonPath("$.issues[0].parent") { value(null as Any?) }
            jsonPath("$.issues[1].number") { value(part.ticket) }
            jsonPath("$.issues[1].parent.number") { value(20) }
            jsonPath("$.issues[1].parent.url") { value("https://github.com/acme/one/issues/20") }
        }
    }
}
