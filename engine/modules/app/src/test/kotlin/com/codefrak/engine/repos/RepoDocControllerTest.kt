package com.codefrak.engine.repos

import com.codefrak.engine.TestTokens
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.mcp.FrakworksMcp
import com.codefrak.engine.mcp.McpCaller
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A sandbox that knows only which address is which run. */
private class DocSandbox(val runs: Map<String, SmithCaller>) : SmithSandbox {
    override fun start(spec: SmithSpec): String = error("unused")
    override fun status(handle: String): SandboxStatus = error("unused")
    override fun logs(handle: String, maxLines: Int): String = ""
    override fun output(handle: String, path: String): ByteArray? = null
    override fun remove(handle: String) {}
    override fun runAt(address: String): SmithCaller? = runs[address]
}

/** Private documents (#1711): `/repos/{o}/{n}/docs` for the user, list_private_docs and read_private_doc for a Smith, scoped by repo. */
@SpringBootTest
@Import(ReadFakes::class)
class RepoDocControllerTest {
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var docs: RepoDocs
    @Autowired lateinit var mcp: FrakworksMcp

    // Repos of this test's own: the H2 is shared with the other Spring tests.
    private val repo = "acme/repodocs"
    private val other = "acme/repodocs-other"
    private val smithAddress = "172.18.0.9"
    private lateinit var mvc: MockMvc
    private fun from(address: String) = RequestPostProcessor { it.remoteAddr = address; it }

    @BeforeEach
    fun reset() {
        listOf(repo, other).forEach { repos.remove(it); repos.add(it) }
        mvc = MockMvcBuilders.standaloneSetup(RepoDocController(repos, docs,
            DocSandbox(mapOf(smithAddress to SmithCaller("run-docs", 7, repo))))).build()
    }

    @Test
    fun `the store puts, replaces, lists, reads and deletes a repo's docs, each repo its own`() {
        assertTrue(docs.put(repo, "design", "v1", "someone"))
        assertFalse(docs.put(repo, "design", "v2 text", "someone"))
        assertEquals("v2 text", docs.read(repo, "design"))
        assertEquals(listOf("design" to 7), docs.list(repo).map { it.name to it.chars })
        assertNull(docs.read(other, "design"))
        assertTrue(docs.list(other).isEmpty())
        assertTrue(docs.delete(repo, "design"))
        assertFalse(docs.delete(repo, "design"))
        // A removed repo takes its docs with it.
        docs.put(other, "spec", "x", "someone")
        repos.remove(other)
        assertTrue(docs.list(other).isEmpty())
    }

    @Test
    fun `the user PUTs a doc with a login and GETs it back, while a Smith, an unknown repo, no login and a bad name are refused`() {
        mvc.put("/repos/$repo/docs/design") { contentType = MediaType.TEXT_PLAIN; content = "# Design\nsecret plan"; with(TestTokens.proven("someone")) }
            .andExpect { status { isCreated() } }
        mvc.put("/repos/$repo/docs/design") { contentType = MediaType.TEXT_PLAIN; content = "# Design\nsecret plan v2"; with(TestTokens.proven("someone")) }
            .andExpect { status { isNoContent() } }
        mvc.get("/repos/$repo/docs/design").andExpect { status { isOk() }; content { string("# Design\nsecret plan v2") } }
        mvc.get("/repos/$repo/docs").andExpect { status { isOk() }; jsonPath("$[0].name") { value("design") }; jsonPath("$[0].updatedBy") { value("someone") } }
        mvc.get("/repos/$repo/docs/nope").andExpect { status { isNotFound() } }

        mvc.put("/repos/$repo/docs/design") { contentType = MediaType.TEXT_PLAIN; content = "x" }.andExpect { status { isUnauthorized() } }
        mvc.put("/repos/acme/not-a-target/docs/design") { contentType = MediaType.TEXT_PLAIN; content = "x"; with(TestTokens.proven("someone")) }
            .andExpect { status { isNotFound() } }
        mvc.put("/repos/$repo/docs/bad!name") { contentType = MediaType.TEXT_PLAIN; content = "x"; with(TestTokens.proven("someone")) }
            .andExpect { status { isBadRequest() } }
        mvc.get("/repos/$repo/docs/design") { with(from(smithAddress)) }.andExpect { status { isForbidden() } }
        mvc.put("/repos/$repo/docs/design") { with(from(smithAddress)); contentType = MediaType.TEXT_PLAIN; content = "x"; with(TestTokens.proven("someone")) }
            .andExpect { status { isForbidden() } }
        mvc.delete("/repos/$repo/docs/design") { with(from(smithAddress)); with(TestTokens.proven("someone")) }.andExpect { status { isForbidden() } }

        mvc.delete("/repos/$repo/docs/design") { with(TestTokens.proven("someone")) }.andExpect { status { isNoContent() } }
        assertNull(docs.read(repo, "design"))
    }

    @Test
    fun `a Smith lists and reads its own repo's docs, and never another repo's`() {
        docs.put(repo, "design", "the whole design", "someone")
        docs.put(other, "secret", "not yours", "someone")
        val mine = McpCaller.Smith(SmithCaller("run-a", 7, repo))
        val list = mcp.call("list_private_docs", emptyMap(), mine)
        assertFalse(list.isError, list.text)
        assertTrue("- design (16 characters" in list.text, list.text)
        assertFalse("secret" in list.text, list.text)
        val read = mcp.call("read_private_doc", mapOf("name" to "design"), mine)
        assertFalse(read.isError, read.text)
        assertTrue(read.text.endsWith("the whole design"), read.text)

        val bad = mcp.call("read_private_doc", mapOf("name" to "secret"), mine)
        assertTrue(bad.isError)
        assertTrue("$repo has no private document secret" in bad.text, bad.text)
        val passed = mcp.call("read_private_doc", mapOf("name" to "secret", "repo" to other), mine)
        assertTrue(passed.isError)
        assertTrue("takes only" in passed.text, passed.text)
        assertTrue(mcp.call("list_private_docs", mapOf("repo" to other), mine).isError)
    }

    @Test
    fun `First mate lists and reads any target repo's docs by repo, and is refused without one or for a non-target repo`() {
        docs.put(repo, "protocol.md", "# Protocol\nthe steps", "someone")
        val mate = McpCaller.Nobody("127.0.0.1", "someone")
        val list = mcp.call("list_private_docs", mapOf("repo" to repo), mate)
        assertFalse(list.isError, list.text)
        assertTrue("- protocol.md (" in list.text, list.text)
        val read = mcp.call("read_private_doc", mapOf("repo" to repo, "name" to "protocol.md"), mate)
        assertFalse(read.isError, read.text)
        assertTrue(read.text.endsWith("# Protocol\nthe steps"), read.text)

        val none = mcp.call("list_private_docs", emptyMap(), mate)
        assertTrue(none.isError)
        assertTrue("is required (owner/name)" in none.text, none.text)
        val foreign = mcp.call("read_private_doc", mapOf("repo" to "acme/not-a-target", "name" to "protocol.md"), mate)
        assertTrue(foreign.isError)
        assertTrue("acme/not-a-target is not a target repo (the target repos: " in foreign.text, foreign.text)
    }
}
