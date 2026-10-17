package com.codefrak.engine.board

import com.codefrak.engine.github.FakeIssueDetails
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.github.TreeEntry
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
class ProposalsControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var details: FakeIssueDetails
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM engine.target_repo").update()
        details.failing.clear(); details.files.clear(); details.trees.clear(); details.blobs.clear(); details.blobReads.clear()
        repos.add("o/r"); repos.add("o/s")
        details.trees["o/r" to "docs/proposals"] = listOf(TreeEntry("tree.md", "t1"), TreeEntry("tree.html", "t2"))
        details.blobs["t1"] = "# A tree\n\nStatus: accepted\nDecision log:\n- 2026-09-27 accepted (#569); build filed as #570\nTicket: #569\n"
        details.trees["o/s" to "docs/proposals"] = listOf(TreeEntry("old.md", "s1"))
        details.blobs["s1"] = "# Old\n\nStatus: shelved\nDecision log:\n- 2026-09-20 shelved (#3) — later\nTicket: #3\n"
    }

    @Test
    fun `lists one repo's docs, parsed`() {
        mvc.get("/proposals?repo=O/R").andExpect {
            status { isOk() }
            jsonPath("$.docs.length()") { value(1) }
            jsonPath("$.docs[0].repo") { value("o/r") }
            jsonPath("$.docs[0].slug") { value("tree") }
            jsonPath("$.docs[0].path") { value("docs/proposals/tree.md") }
            jsonPath("$.docs[0].title") { value("A tree") }
            jsonPath("$.docs[0].status") { value("accepted") }
            jsonPath("$.docs[0].decisionLog[0]") { value("2026-09-27 accepted (#569); build filed as #570") }
            jsonPath("$.docs[0].ticket") { value(569) }
            jsonPath("$.docs[0].buildTicket") { value(570) }
            jsonPath("$.docs[0].pitch") { value("docs/proposals/tree.html") }
            jsonPath("$.errors.length()") { value(0) }
        }
    }

    @Test
    fun `lists every target repo's with no repo, a failing one among the errors`() {
        mvc.get("/proposals").andExpect {
            status { isOk() }
            jsonPath("$.docs.length()") { value(2) }
            jsonPath("$.docs[1].status") { value("shelved") }
        }
        details.failing += "o/s"
        mvc.get("/proposals").andExpect {
            status { isOk() }
            jsonPath("$.docs.length()") { value(1) }
            jsonPath("$.errors[0].repo") { value("o/s") }
            jsonPath("$.errors[0].error") { value("GitHub: GitHub 502") }
        }
    }

    @Test
    fun `a repo that is not a target repo is a 404, and GitHub failing a 502`() {
        mvc.get("/proposals?repo=o/elsewhere").andExpect { status { isNotFound() }; jsonPath("$.error") { exists() } }
        details.failing += "o/r"
        mvc.get("/proposals?repo=o/r").andExpect { status { isBadGateway() }; jsonPath("$.error") { value("GitHub: GitHub 502") } }
    }

    @Test
    fun `the page is the pitch when there is one, else the doc rendered`() {
        details.files[Triple("o/r", "docs/proposals/tree.html", null)] = "<html>pitch</html>"
        details.files[Triple("o/r", "docs/proposals/tree.md", null)] = "# A tree"
        mvc.get("/proposals/o/r/tree/page").andExpect {
            status { isOk() }
            jsonPath("$.kind") { value("html") }
            jsonPath("$.path") { value("docs/proposals/tree.html") }
            jsonPath("$.prUrl") { value("") }
            jsonPath("$.content") { value("<html>pitch</html>") }
        }
        details.files.remove(Triple("o/r", "docs/proposals/tree.html", null))
        mvc.get("/proposals/o/r/tree/page").andExpect {
            status { isOk() }
            jsonPath("$.kind") { value("markdown") }
            jsonPath("$.path") { value("docs/proposals/tree.md") }
            jsonPath("$.content") { value("<rendered># A tree</rendered>") }
        }
    }

    @Test
    fun `a bad slug, a missing doc or a repo that is not a target repo is a 404`() {
        mvc.get("/proposals/o/r/.hidden/page").andExpect { status { isNotFound() } }
        mvc.get("/proposals/o/r/nothing/page").andExpect { status { isNotFound() }; jsonPath("$.error") { value("no proposal nothing in o/r") } }
        mvc.get("/proposals/o/elsewhere/tree/page").andExpect { status { isNotFound() } }
        details.failing += "o/r"
        mvc.get("/proposals/o/r/tree/page").andExpect { status { isBadGateway() } }
    }
}
