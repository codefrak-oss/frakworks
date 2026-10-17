package com.codefrak.engine.board

import com.codefrak.engine.github.FakeIssueDetails
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.github.RepoTree
import com.codefrak.engine.github.RepoTreeEntry
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import kotlin.test.assertEquals

/** A sandbox that knows only which address is which run; `down` is Docker not answering. */
private class AddressSandbox(val runs: Map<String, SmithCaller>, @Volatile var down: Boolean = false) : SmithSandbox {
    override fun start(spec: SmithSpec): String = error("unused")
    override fun status(handle: String): SandboxStatus = error("unused")
    override fun logs(handle: String, maxLines: Int): String = ""
    override fun output(handle: String, path: String): ByteArray? = null
    override fun remove(handle: String) {}
    override fun runAt(address: String): SmithCaller? = if (down) error("docker is down") else runs[address]
}

/**
 * The context's [RepoFiles] and [TargetRepos] over faked GitHub reads, behind a controller whose sandbox is
 * [AddressSandbox]: the context's own is Docker's, which a test cannot ask who is calling.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class RepoFilesControllerTest {
    @Autowired lateinit var files: RepoFiles
    @Autowired lateinit var details: FakeIssueDetails
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var jdbc: JdbcClient

    /** A commit and blob SHAs of this test's own, since RepoFiles keeps what it read for the whole context. */
    private lateinit var head: String
    private lateinit var blob: String
    private val sandbox = AddressSandbox(mapOf(
        "172.18.0.7" to SmithCaller("run-own", 7, "O/R"), "172.18.0.8" to SmithCaller("run-other", 8, "o/elsewhere")))
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun reset() {
        mvc = MockMvcBuilders.standaloneSetup(RepoFilesController(files, repos, sandbox)).build()
        sandbox.down = false
        jdbc.sql("DELETE FROM engine.target_repo").update()
        details.failing.clear(); details.commits.clear(); details.repoTrees.clear(); details.raw.clear()
        details.commitReads.clear(); details.treeReads.clear(); details.contentReads.clear()
        repos.add("o/r")
        head = UUID.randomUUID().toString().replace("-", "").padEnd(40, '0')
        blob = UUID.randomUUID().toString()
        details.commits["o/r" to null] = head
        details.commits["o/r" to "feature/x"] = head
        details.repoTrees["o/r" to head] = RepoTree(false, listOf(
            RepoTreeEntry("docs", "tree", "d1"), RepoTreeEntry("docs/a.md", "blob", blob), RepoTreeEntry("bin/tool", "blob", "$blob-2")))
        details.raw[Triple("o/r", "docs/a.md", head)] = "# A".toByteArray()
        details.raw[Triple("o/r", "bin/tool", head)] = byteArrayOf(0, 1, 2)
    }

    @Test
    fun `the tree is the default branch's, or the ref's, with every entry`() {
        mvc.get("/repos/O/R/tree").andExpect {
            status { isOk() }
            jsonPath("$.commit") { value(head) }
            jsonPath("$.truncated") { value(false) }
            jsonPath("$.entries.length()") { value(3) }
            jsonPath("$.entries[1].path") { value("docs/a.md") }
            jsonPath("$.entries[1].type") { value("blob") }
            jsonPath("$.entries[1].sha") { value(blob) }
        }
        mvc.get("/repos/o/r/tree?ref=feature/x").andExpect { status { isOk() }; jsonPath("$.commit") { value(head) } }
        assertEquals(1, details.treeReads.size, "one git/trees request for the same head")
    }

    @Test
    fun `a tree of a repo that is not a target repo or a ref GitHub cannot resolve is a 404, and GitHub failing a 502`() {
        mvc.get("/repos/o/elsewhere/tree").andExpect { status { isNotFound() }; jsonPath("$.error") { value("o/elsewhere is not a target repo") } }
        mvc.get("/repos/o/r/tree?ref=nope").andExpect { status { isNotFound() }; jsonPath("$.error") { value("o/r has no ref nope") } }
        details.failing += "o/r"
        mvc.get("/repos/o/r/tree").andExpect { status { isBadGateway() }; jsonPath("$.error") { value("GitHub: GitHub 502") } }
    }

    @Test
    fun `a file is its bytes, typed by its extension, and read from GitHub once per blob`() {
        mvc.get("/repos/o/r/file?path=docs/a.md").andExpect {
            status { isOk() }
            content { contentType("text/markdown") }
            content { bytes("# A".toByteArray()) }
            header { string("X-Blob-Sha", blob) }
        }
        mvc.get("/repos/o/r/file?path=docs/a.md&ref=$head").andExpect { status { isOk() }; content { bytes("# A".toByteArray()) } }
        assertEquals(1, details.contentReads.size, "the second read makes no contents request")
        mvc.get("/repos/o/r/file?path=bin/tool").andExpect {
            status { isOk() }
            content { contentType("application/octet-stream") }
            content { bytes(byteArrayOf(0, 1, 2)) }
        }
    }

    @Test
    fun `a missing path, a directory, a bad ref or a repo that is not a target repo is a 404, and GitHub failing a 502`() {
        mvc.get("/repos/o/r/file?path=docs/none.md").andExpect { status { isNotFound() }; jsonPath("$.error") { exists() } }
        mvc.get("/repos/o/r/file?path=docs").andExpect { status { isNotFound() }; jsonPath("$.error") { exists() } }
        mvc.get("/repos/o/r/file?path=docs/a.md&ref=nope").andExpect { status { isNotFound() }; jsonPath("$.error") { exists() } }
        mvc.get("/repos/o/elsewhere/file?path=docs/a.md").andExpect { status { isNotFound() }; jsonPath("$.error") { exists() } }
        details.failing += "o/r"
        mvc.get("/repos/o/r/file?path=docs/a.md").andExpect { status { isBadGateway() }; jsonPath("$.error") { value("GitHub: GitHub 502") } }
    }

    private fun from(address: String) = RequestPostProcessor { it.remoteAddr = address; it }

    @Test
    fun `a Smith sandbox reads its own repo's tree and files, and another repo's is a 403`() {
        mvc.get("/repos/o/r/tree") { with(from("172.18.0.7")) }.andExpect { status { isOk() }; jsonPath("$.commit") { value(head) } }
        mvc.get("/repos/o/r/file?path=docs/a.md") { with(from("172.18.0.7")) }.andExpect { status { isOk() } }
        mvc.get("/repos/o/r/tree") { with(from("172.18.0.8")) }.andExpect {
            status { isForbidden() }; jsonPath("$.error") { value("a Smith sandbox (run run-other) reads its own repo's files only") }
        }
        mvc.get("/repos/o/r/file?path=docs/a.md") { with(from("172.18.0.8")) }.andExpect { status { isForbidden() }; jsonPath("$.error") { exists() } }
        assertEquals(1, details.contentReads.size, "the refused read asked GitHub nothing")
    }

    @Test
    fun `when Docker cannot say who is calling, both reads are a 503`() {
        sandbox.down = true
        mvc.get("/repos/o/r/tree").andExpect {
            status { isServiceUnavailable() }; jsonPath("$.error") { value("cannot tell whether the caller is a Smith sandbox") }
        }
        mvc.get("/repos/o/r/file?path=docs/a.md").andExpect { status { isServiceUnavailable() }; jsonPath("$.error") { exists() } }
        assertEquals(0, details.treeReads.size + details.contentReads.size)
    }
}
