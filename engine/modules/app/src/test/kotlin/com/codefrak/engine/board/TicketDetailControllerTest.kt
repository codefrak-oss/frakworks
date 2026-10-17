package com.codefrak.engine.board

import com.codefrak.engine.github.FakeIssueDetails
import com.codefrak.engine.github.PullFiles
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.github.TicketComment
import com.codefrak.engine.github.TicketDetail
import com.codefrak.engine.github.TicketPull
import com.codefrak.engine.github.TicketPulls
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemDetails
import com.codefrak.engine.workitems.WorkItemEntry
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
import java.time.OffsetDateTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class TicketDetailControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var details: FakeIssueDetails
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var handled: HandledIssues
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var store: WorkItemStore

    private val t0 = OffsetDateTime.parse("2026-09-24T10:00:00Z")

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM engine.target_repo").update()
        jdbc.sql("DELETE FROM engine.handled_issue").update()
        jdbc.sql("DELETE FROM engine.work_item").update()
        details.onHead.clear()
        details.tickets.clear(); details.failing.clear(); details.restComments.clear(); details.files.clear(); details.pulls.clear()
        repos.add("o/r")
    }

    @Test
    fun `returns every field of the ticket, rendered HTML and closing PRs included`() {
        details.tickets["o/r" to 5L] = TicketDetail("I_5", 5, "Fix it", "the **body**", "<p>the <strong>body</strong></p>", "CLOSED",
            "https://github.com/o/r/issues/5", listOf("chore", "authorized"), t0, t0.plusHours(1),
            listOf(TicketComment("IC_1", "https://github.com/o/r/issues/5#issuecomment-1", "fleet-bot", t0, t0.plusMinutes(1),
                "Done.", "<p>Done.</p>")),
            listOf(TicketPull(9, "Fix it", "https://github.com/o/r/pull/9", "MERGED", false, "APPROVED")))
        mvc.get("/ticket/o/r/5").andExpect {
            status { isOk() }
            jsonPath("$.repo") { value("o/r") }
            jsonPath("$.nodeId") { value("I_5") }
            jsonPath("$.title") { value("Fix it") }
            jsonPath("$.body") { value("the **body**") }
            jsonPath("$.bodyHTML") { value("<p>the <strong>body</strong></p>") }
            jsonPath("$.state") { value("CLOSED") }
            jsonPath("$.url") { value("https://github.com/o/r/issues/5") }
            jsonPath("$.labels[1]") { value("authorized") }
            jsonPath("$.createdAt") { exists() }
            jsonPath("$.closedAt") { exists() }
            jsonPath("$.comments[0].id") { value("IC_1") }
            jsonPath("$.comments[0].url") { value("https://github.com/o/r/issues/5#issuecomment-1") }
            jsonPath("$.comments[0].author") { value("fleet-bot") }
            jsonPath("$.comments[0].createdAt") { exists() }
            jsonPath("$.comments[0].updatedAt") { exists() }
            jsonPath("$.comments[0].body") { value("Done.") }
            jsonPath("$.comments[0].bodyHTML") { value("<p>Done.</p>") }
            jsonPath("$.prs[0].number") { value(9) }
            jsonPath("$.prs[0].title") { value("Fix it") }
            jsonPath("$.prs[0].url") { value("https://github.com/o/r/pull/9") }
            jsonPath("$.prs[0].state") { value("MERGED") }
            jsonPath("$.prs[0].isDraft") { value(false) }
            jsonPath("$.prs[0].reviewDecision") { value("APPROVED") }
            jsonPath("$.askedBy") { value(null as Any?) }
            jsonPath("$.points") { value(null as Any?) }
        }
        handled.claim("o/r", 5, 1, "chore", "started", null, "alice", 8)
        mvc.get("/ticket/o/r/5").andExpect { status { isOk() }; jsonPath("$.askedBy") { value("alice") }; jsonPath("$.points") { value(8) } }
    }

    @Test
    fun `an unknown issue or a repo that is not a target repo is a 404, and GitHub failing a 502`() {
        mvc.get("/ticket/o/r/6").andExpect { status { isNotFound() }; jsonPath("$.error") { value("no issue o/r#6") } }
        mvc.get("/ticket/o/elsewhere/5").andExpect { status { isNotFound() }; jsonPath("$.error") { exists() } }
        details.failing += "o/r"
        mvc.get("/ticket/o/r/5").andExpect { status { isBadGateway() }; jsonPath("$.error") { value("GitHub: GitHub 502") } }
    }

    @Test
    fun `the timeline route still resolves beside it`() {
        mvc.get("/ticket/o/r/5/timeline").andExpect { status { isNotFound() }; jsonPath("$.error") { value("no process instances for o/r#5") } }
    }

    private fun restComment(id: Long, body: String) = mapOf(
        "id" to id, "node_id" to "IC_$id", "html_url" to "https://github.com/o/r/issues/5#issuecomment-$id",
        "user" to mapOf("login" to "fleet-bot", "id" to 7), "created_at" to "2026-09-24T10:00:00Z",
        "updated_at" to "2026-09-24T10:01:00Z", "body" to body, "author_association" to "NONE")

    @Test
    fun `comments are GitHub's REST objects, unaltered, in order`() {
        details.restComments["o/r" to 5L] = listOf(restComment(3300000001, "Claimed."), restComment(3300000002, "Blocked: which host?"))
        mvc.get("/ticket/o/r/5/comments").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].id") { value(3300000001L) }
            jsonPath("$[1].id") { value(3300000002L) }
            jsonPath("$[1].node_id") { value("IC_3300000002") }
            jsonPath("$[1].html_url") { value("https://github.com/o/r/issues/5#issuecomment-3300000002") }
            jsonPath("$[1].user.login") { value("fleet-bot") }
            jsonPath("$[1].created_at") { value("2026-09-24T10:00:00Z") }
            jsonPath("$[1].updated_at") { value("2026-09-24T10:01:00Z") }
            jsonPath("$[1].body") { value("Blocked: which host?") }
            jsonPath("$[1].author_association") { value("NONE") }
        }
        details.restComments["o/r" to 6L] = emptyList()
        mvc.get("/ticket/o/r/6/comments").andExpect { status { isOk() }; jsonPath("$.length()") { value(0) } }
    }

    @Test
    fun `one comment reads by its REST id`() {
        details.restComments["o/r" to 5L] = listOf(restComment(3300000001, "Claimed."), restComment(3300000002, "Blocked: which host?"))
        mvc.get("/ticket/o/r/comments/3300000002").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(3300000002L) }
            jsonPath("$.updated_at") { value("2026-09-24T10:01:00Z") }
            jsonPath("$.body") { value("Blocked: which host?") }
        }
    }

    @Test
    fun `comment reads 404 on an unknown issue, comment or repo, and 502 when GitHub fails`() {
        mvc.get("/ticket/o/r/7/comments").andExpect { status { isNotFound() }; jsonPath("$.error") { value("no issue o/r#7") } }
        mvc.get("/ticket/o/r/comments/9").andExpect { status { isNotFound() }; jsonPath("$.error") { value("no comment 9 in o/r") } }
        mvc.get("/ticket/o/elsewhere/5/comments").andExpect { status { isNotFound() }; jsonPath("$.error") { value("o/elsewhere is not a target repo") } }
        mvc.get("/ticket/o/elsewhere/comments/9").andExpect { status { isNotFound() }; jsonPath("$.error") { value("o/elsewhere is not a target repo") } }
        details.failing += "o/r"
        mvc.get("/ticket/o/r/5/comments").andExpect { status { isBadGateway() }; jsonPath("$.error") { value("GitHub: GitHub 502") } }
        mvc.get("/ticket/o/r/comments/9").andExpect { status { isBadGateway() }; jsonPath("$.error") { value("GitHub: GitHub 502") } }
    }

    /** A proposal flow on o/r#n that ran through, with its stored propose result and the variables given. */
    private fun proposal(n: Long, result: String?, vararg vars: Pair<String, Any?>, flow: String = "proposal") {
        val variables = mutableMapOf(*vars)
        result?.let { variables["proposal"] = Variables.byteArrayValue(it.toByteArray()) }
        val instance = runtime.startProcessInstanceByKey("hello", variables)
        handled.claim("o/r", n, 500 + n, flow, "started", null)
        handled.started("o/r", n, 500 + n, instance.id)
    }

    private val drafted = """{"outcome":"drafted","path":"docs/proposals/x.md","pitch":"docs/proposals/x.html","summary":"X."}"""

    @Autowired lateinit var research: com.codefrak.engine.research.ResearchAnswers

    @Test
    fun `a research ticket's latest answer is served as an html pitch with its round, with no flow running`() {
        research.keep("o/r", 40, 1, "p-40", "First.", "# Q\n\nA1", "<html>round 1</html>")
        research.keep("o/r", 40, 2, "p-40", "Second.", "# Q\n\nA2", "<html>round 2</html>")
        mvc.get("/ticket/o/r/40/pitch").andExpect {
            status { isOk() }
            jsonPath("$.kind") { value("html") }
            jsonPath("$.path") { value(null) }
            jsonPath("$.prUrl") { value("") }
            jsonPath("$.content") { value("<html>round 2</html>") }
            jsonPath("$.round") { value(2) }
        }
        jdbc.sql("DELETE FROM engine.research_answer").update()
    }

    @Test
    fun `a proposal's pitch page is read at its branch`() {
        proposal(20, drafted, "prUrl" to "https://github.com/o/r/pull/21", "branch" to "smith/issue-20")
        details.files[Triple("o/r", "docs/proposals/x.html", "smith/issue-20")] = "<html><svg></svg></html>"
        details.files[Triple("o/r", "docs/proposals/x.html", null)] = "an older page"
        mvc.get("/ticket/o/r/20/pitch").andExpect {
            status { isOk() }
            jsonPath("$.kind") { value("html") }
            jsonPath("$.path") { value("docs/proposals/x.html") }
            jsonPath("$.prUrl") { value("https://github.com/o/r/pull/21") }
            jsonPath("$.content") { value("<html><svg></svg></html>") }
        }
    }

    @Test
    fun `a proposal with no pitch is its doc rendered, from the default branch once its branch is gone`() {
        proposal(22, """{"outcome":"drafted","path":"docs/proposals/y.md","summary":"Y."}""", "prUrl" to "https://github.com/o/r/pull/23")
        details.files[Triple("o/r", "docs/proposals/y.md", null)] = "# Y"
        mvc.get("/ticket/o/r/22/pitch").andExpect {
            status { isOk() }
            jsonPath("$.kind") { value("markdown") }
            jsonPath("$.path") { value("docs/proposals/y.md") }
            jsonPath("$.prUrl") { value("https://github.com/o/r/pull/23") }
            jsonPath("$.content") { value("<rendered># Y</rendered>") }
        }
        // With the branch there (the default one, smith/issue-<n>, when no branch variable was set), it wins.
        details.files[Triple("o/r", "docs/proposals/y.md", "smith/issue-22")] = "# Y, revised"
        mvc.get("/ticket/o/r/22/pitch").andExpect { jsonPath("$.content") { value("<rendered># Y, revised</rendered>") } }
    }

    @Test
    fun `a ticket that is not a proposal, a proposal with no PR or nothing drafted, or a missing file is a 404, and GitHub failing a 502`() {
        mvc.get("/ticket/o/r/30/pitch").andExpect { status { isNotFound() }; jsonPath("$.error") { value("no proposal with a PR, and no research answer, on o/r#30") } }
        proposal(31, drafted, "prUrl" to "https://github.com/o/r/pull/1", flow = "chore")
        mvc.get("/ticket/o/r/31/pitch").andExpect { status { isNotFound() }; jsonPath("$.error") { exists() } }
        proposal(32, drafted)
        mvc.get("/ticket/o/r/32/pitch").andExpect { status { isNotFound() }; jsonPath("$.error") { value("no proposal with a PR, and no research answer, on o/r#32") } }
        proposal(33, """{"outcome":"ask","question":"Which?"}""", "prUrl" to "https://github.com/o/r/pull/2")
        mvc.get("/ticket/o/r/33/pitch").andExpect { status { isNotFound() } }
        proposal(34, drafted, "prUrl" to "https://github.com/o/r/pull/3")
        mvc.get("/ticket/o/r/34/pitch").andExpect { status { isNotFound() } }
        mvc.get("/ticket/o/elsewhere/34/pitch").andExpect { status { isNotFound() }; jsonPath("$.error") { value("o/elsewhere is not a target repo") } }
        details.failing += "o/r"
        mvc.get("/ticket/o/r/34/pitch").andExpect { status { isBadGateway() }; jsonPath("$.error") { value("GitHub: GitHub 502") } }
    }

    /** A PR closing a ticket, its files all added. */
    private fun pull(number: Long, state: String, head: String, vararg files: String) =
        PullFiles(number, "https://github.com/o/r/pull/$number", state, head, files.associateWith { "ADDED" })

    @Test
    fun `with no kept record, the pitch page a ticket's open PR adds is read from its branch`() {
        details.pulls["o/r" to 40L] = TicketPulls("main", listOf(
            pull(41, "OPEN", "smith/issue-40", "docs/proposals/z.md", "docs/proposals/z.html", "docs/proposals/other.md")))
        details.files[Triple("o/r", "docs/proposals/z.html", "smith/issue-40")] = "<html>branch</html>"
        details.files[Triple("o/r", "docs/proposals/z.html", "main")] = "<html>main</html>"
        mvc.get("/ticket/o/r/40/pitch").andExpect {
            status { isOk() }
            jsonPath("$.kind") { value("html") }
            jsonPath("$.path") { value("docs/proposals/z.html") }
            jsonPath("$.prUrl") { value("https://github.com/o/r/pull/41") }
            jsonPath("$.content") { value("<html>branch</html>") }
        }
    }

    @Test
    fun `with no kept record, a merged PR's pitch page is read from the default branch`() {
        details.pulls["o/r" to 42L] = TicketPulls("trunk", listOf(pull(43, "MERGED", "smith/issue-42", "docs/proposals/m.html")))
        details.files[Triple("o/r", "docs/proposals/m.html", "smith/issue-42")] = "<html>stale branch</html>"
        details.files[Triple("o/r", "docs/proposals/m.html", "trunk")] = "<html>merged</html>"
        mvc.get("/ticket/o/r/42/pitch").andExpect {
            status { isOk() }
            jsonPath("$.content") { value("<html>merged</html>") }
            jsonPath("$.prUrl") { value("https://github.com/o/r/pull/43") }
        }
    }

    @Test
    fun `with no kept record, a PR with no page, or several pages the file list cannot tell apart, is a 404`() {
        details.pulls["o/r" to 44L] = TicketPulls("main", listOf(pull(45, "OPEN", "smith/issue-44", "docs/proposals/a.md", "src/x.kt")))
        mvc.get("/ticket/o/r/44/pitch").andExpect { status { isNotFound() }; jsonPath("$.error") { value("no proposal with a PR, and no research answer, on o/r#44") } }
        details.pulls["o/r" to 46L] = TicketPulls("main", listOf(pull(47, "OPEN", "smith/issue-46", "docs/proposals/a.html", "docs/proposals/b.html")))
        details.files[Triple("o/r", "docs/proposals/a.html", "smith/issue-46")] = "<html>a</html>"
        mvc.get("/ticket/o/r/46/pitch").andExpect { status { isNotFound() } }
        details.pulls["o/r" to 48L] = TicketPulls("main", listOf(pull(49, "OPEN", "smith/issue-48", "docs/proposals/../../secret.html")))
        mvc.get("/ticket/o/r/48/pitch").andExpect { status { isNotFound() } }
    }

    @Test
    fun `a work item is read from the store, its PRs by its smith branch, and a missing item is a 404`() {
        val item = store.create(NewWorkItem("o/r", "# Fix the view\n\nthe body", "bug", WorkItemSourceRef(WorkItemSourceRef.ASK)))
        val n = item.ticket!!
        val said = store.appendEntry(item.id, WorkItemEntry.CONVERSATION, "engine", "Bug started.", true)
        store.appendEntry(item.id, WorkItemEntry.HISTORY, "engine", "created", true)
        details.onHead["o/r" to "smith/issue-$n"] = listOf(TicketPull(12, "Fix the view", "https://github.com/o/r/pull/12", "OPEN", false, null))
        mvc.get("/ticket/o/r/$n").andExpect {
            status { isOk() }
            jsonPath("$.number") { value(n) }
            jsonPath("$.title") { value("Fix the view") }
            jsonPath("$.body") { value("the body") }
            jsonPath("$.state") { value("OPEN") }
            jsonPath("$.labels[0]") { value("bug") }
            jsonPath("$.comments.length()") { value(1) }
            jsonPath("$.comments[0].author") { value("engine") }
            jsonPath("$.comments[0].body") { value("Bug started.") }
            jsonPath("$.prs[0].number") { value(12) }
        }
        val id = WorkItemDetails.restId(said.id)
        mvc.get("/ticket/o/r/$n/comments").andExpect {
            status { isOk() }; jsonPath("$.length()") { value(1) }; jsonPath("$[0].id") { value(id) }; jsonPath("$[0].body") { value("Bug started.") }
        }
        mvc.get("/ticket/o/r/comments/$id").andExpect { status { isOk() }; jsonPath("$.body") { value("Bug started.") } }
        store.setState(item.id, WorkItem.DONE, "closed")
        mvc.get("/ticket/o/r/$n").andExpect { status { isOk() }; jsonPath("$.state") { value("CLOSED") }; jsonPath("$.closedAt") { exists() } }
        mvc.get("/ticket/o/r/${n + 50}").andExpect { status { isNotFound() }; jsonPath("$.error") { value("no issue o/r#${n + 50}") } }
        mvc.get("/ticket/o/r/${n + 50}/comments").andExpect { status { isNotFound() } }
    }
}
