package com.codefrak.engine.mcp

import com.codefrak.engine.EngineBuild
import com.codefrak.engine.TestTokens
import com.codefrak.engine.TOKEN_ID_ATTRIBUTE
import com.codefrak.engine.TOKEN_LOGIN_ATTRIBUTE
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import com.codefrak.engine.tier.SeededTiers
import com.codefrak.engine.trigger.IssueApprovals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** A sandbox that knows only which address is which run; `down` is Docker not answering. */
private class AddressSandbox(val runs: Map<String, SmithCaller> = emptyMap(), var down: Boolean = false) : SmithSandbox {
    override fun start(spec: SmithSpec): String = error("unused")
    override fun status(handle: String): SandboxStatus = error("unused")
    override fun logs(handle: String, maxLines: Int): String = ""
    override fun output(handle: String, path: String): ByteArray? = null
    override fun remove(handle: String) {}
    override fun runAt(address: String): SmithCaller? = if (down) error("docker is down") else runs[address]
}

/**
 * `/mcp/frakworks` over [EngineMcpController] (engine/README.md, "Engine-served MCP servers"): the role
 * is the source address, First mate's login is the one its bearer token proved (`BearerTokenFilter`, not in this standalone setup) and only First mate's,
 * and a browser's `Origin` is refused.
 */
class FrakworksMcpHttpTest {
    private val smithAddress = "172.18.0.9"
    private val sandbox = AddressSandbox(mapOf(smithAddress to SmithCaller("run-1", 7, "acme/widgets")))
    private val engine = FakeEngine()
    private val friction = FakeFrictionLog()
    /** As VersionCommitTest sets `engine.build.commit`: a known commit, and Spring Boot's build version. */
    private val build = EngineBuild(StaticListableBeanFactory(mapOf("build" to BuildProperties(Properties().apply { setProperty("version", "0.0.1") })))
        .getBeanProvider(BuildProperties::class.java), "0123456789abcdef0123456789abcdef01234567")
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(
        EngineMcpController(listOf(FrakworksMcp(engine, SeededTiers.table(), friction, build = build)), sandbox)).build()

    private fun rpc(body: String, from: String = "127.0.0.1", headers: Map<String, String> = emptyMap(), login: String? = null): ResultActionsDsl =
        mvc.post("/mcp/frakworks") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = body
            headers.forEach { (k, v) -> header(k, v) }
            with(RequestPostProcessor { it.remoteAddr = from; it })
            login?.trim()?.takeIf { it.isNotEmpty() }?.let { with(TestTokens.proven(it)) }
        }

    /** A tools/call answer's tool error text; fails when it is no tool error. */
    private fun toolError(r: ResultActionsDsl): String {
        val result = mcpJson.readValue(r.andReturn().response.contentAsString, Map::class.java)["result"] as Map<*, *>
        assertEquals(true, result["isError"], result.toString())
        val text = ((result["content"] as List<*>).first() as Map<*, *>)["text"] as String
        return mcpJson.readValue(text, Map::class.java)["error"] as String
    }

    private val submit = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"submit_ask","arguments":{"ask":"do it"}}}"""
    /** First mate's: a person's ask names its repo; a Smith's ([submit]) is its run's. */
    private val mateSubmit = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"submit_ask","arguments":{"ask":"do it","repo":"acme/widgets"}}}"""

    @Test
    fun `initialize names frakworks, and tools-list is First mate's forty, or a Smith's twenty-two`() {
        rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""").andExpect {
            status { isOk() }
            jsonPath("$.result.serverInfo.name") { value("frakworks") }
            jsonPath("$.result.capabilities.tools") { isMap() }
        }
        rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").andExpect {
            jsonPath("$.result.tools.length()") { value(40) }
            jsonPath("$.result.tools[37].name") { value("read_version") }
            jsonPath("$.result.tools[38].name") { value("read_desktop_release") }
            jsonPath("$.result.tools[39].name") { value("screenshot_desktop") }
            jsonPath("$.result.tools[37].inputSchema.additionalProperties") { value(false) }
            jsonPath("$.result.tools[0].name") { value("submit_ask") }
            jsonPath("$.result.tools[0].inputSchema.properties.tier.maximum") { value(10) }
            jsonPath("$.result.tools[3].name") { value("read_ticket_pr") }
            jsonPath("$.result.tools[4].name") { value("approve") }
            jsonPath("$.result.tools[4].inputSchema.required[0]") { value("ticket") }
            jsonPath("$.result.tools[5].name") { value("reauthorize") }
            jsonPath("$.result.tools[5].inputSchema.required[0]") { value("ticket") }
            jsonPath("$.result.tools[6].name") { value("note_friction") }
            jsonPath("$.result.tools[6].inputSchema.properties.repo.type") { value("string") }
            jsonPath("$.result.tools[7].name") { value("read_repos") }
            jsonPath("$.result.tools[8].name") { value("add_repo") }
            jsonPath("$.result.tools[8].inputSchema.required[0]") { value("repo") }
            jsonPath("$.result.tools[8].inputSchema.additionalProperties") { value(false) }
            jsonPath("$.result.tools[9].name") { value("edit_repo") }
            jsonPath("$.result.tools[10].name") { value("remove_repo") }
            jsonPath("$.result.tools[11].name") { value("read_ci_runs") }
            jsonPath("$.result.tools[12].name") { value("cancel_intake") }
            jsonPath("$.result.tools[12].inputSchema.required[1]") { value("reason") }
            jsonPath("$.result.tools[12].inputSchema.additionalProperties") { value(false) }
            jsonPath("$.result.tools[13].name") { value("amend_intake") }
            jsonPath("$.result.tools[13].inputSchema.required[1]") { value("text") }
            jsonPath("$.result.tools[13].inputSchema.additionalProperties") { value(false) }
            jsonPath("$.result.tools[14].name") { value("amend_ticket") }
            jsonPath("$.result.tools[14].inputSchema.required[2]") { value("text") }
            jsonPath("$.result.tools[15].name") { value("close_ticket") }
            jsonPath("$.result.tools[15].inputSchema.required[2]") { value("reason") }
            jsonPath("$.result.tools[16].name") { value("comment_ticket") }
            jsonPath("$.result.tools[17].name") { value("read_ticket_runs") }
            jsonPath("$.result.tools[18].name") { value("search_tickets") }
            jsonPath("$.result.tools[19].name") { value("read_access") }
            jsonPath("$.result.tools[20].name") { value("add_access") }
            jsonPath("$.result.tools[21].name") { value("remove_access") }
            jsonPath("$.result.tools[22].name") { value("read_tokens") }
            jsonPath("$.result.tools[23].name") { value("mint_token") }
            jsonPath("$.result.tools[23].inputSchema.required[1]") { value("name") }
            jsonPath("$.result.tools[24].name") { value("revoke_token") }
            jsonPath("$.result.tools[25].name") { value("read_ticket") }
            jsonPath("$.result.tools[26].name") { value("list_private_docs") }
            jsonPath("$.result.tools[27].inputSchema.required[0]") { value("repo") }
        }
        rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", from = smithAddress).andExpect {
            jsonPath("$.result.tools.length()") { value(22) }
            jsonPath("$.result.tools[20].name") { value("read_version") }
            jsonPath("$.result.tools[21].name") { value("read_desktop_release") }
            jsonPath("$.result.tools[0].name") { value("submit_ask") }
            jsonPath("$.result.tools[1].name") { value("note_friction") }
            jsonPath("$.result.tools[2].name") { value("read_ci_log") }
            jsonPath("$.result.tools[2].inputSchema.required[0]") { value("check_run") }
            jsonPath("$.result.tools[2].inputSchema.additionalProperties") { value(false) }
            jsonPath("$.result.tools[3].name") { value("read_run_log") }
            jsonPath("$.result.tools[6].name") { value("list_attachments") }
            jsonPath("$.result.tools[7].name") { value("read_attachment") }
            jsonPath("$.result.tools[8].name") { value("steer_child") }
        }
    }

    @Test
    fun `First mate's login header reaches the intake as submittedBy, and a blank one is none`() {
        rpc(mateSubmit, login = "  someone ").andExpect {
            jsonPath("$.result.isError") { doesNotExist() }
            // The ask item's ticket, known at filing (POST /intake's answer), for the reply's link.
            jsonPath("$.result.content[0].text") { value(org.hamcrest.Matchers.containsString(
                "\"repo\":\"acme/widgets\",\"ticket\":100001,\"ticketUrl\":\"https://github.com/acme/widgets/issues/100001\"")) }
        }
        assertEquals("someone", engine.asks.last().submittedBy)
        rpc(mateSubmit, login = " ")
        assertNull(engine.asks.last().submittedBy)
    }

    @Test
    fun `a login a bearer token proved (the filter's request attributes) wins over the header, and reaches the tools as the caller's login`() {
        val proven = RequestPostProcessor { it.setAttribute(TOKEN_LOGIN_ATTRIBUTE, "octocat"); it.setAttribute(TOKEN_ID_ATTRIBUTE, "tok-9"); it }
        mvc.post("/mcp/frakworks") {
            contentType = MediaType.APPLICATION_JSON; accept = MediaType.APPLICATION_JSON; content = mateSubmit
            with(RequestPostProcessor { it.remoteAddr = "127.0.0.1"; it }); with(proven)
        }.andExpect { jsonPath("$.result.isError") { doesNotExist() } }
        assertEquals("octocat", engine.asks.last().submittedBy)
        // revoke_token refuses the token the call itself carries, so it saw the token id too.
        val r = mvc.post("/mcp/frakworks") {
            contentType = MediaType.APPLICATION_JSON; accept = MediaType.APPLICATION_JSON
            content = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"revoke_token","arguments":{"id":"tok-9"}}}"""
            with(RequestPostProcessor { it.remoteAddr = "127.0.0.1"; it }); with(proven)
        }
        assertEquals("revoke_token refuses the token this call carries (tok-9): that would lock you out; nothing was changed", toolError(r))
    }

    @Test
    fun `a Smith's read_tokens is refused naming its run`() {
        val r = rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_tokens","arguments":{}}}""", from = smithAddress)
        r.andExpect { jsonPath("$.error.message") { value("unknown tool: \"read_tokens\"") } }
    }

    @Test
    fun `a Smith's login header is ignored, and First mate's tools are not its`() {
        rpc(submit, from = smithAddress, login = "someone").andExpect {
            jsonPath("$.result.isError") { doesNotExist() }
        }
        assertNull(engine.asks.single().submittedBy)
        assertEquals(false, engine.asks.single().authorize)
        rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"read_needs","arguments":{}}}""", from = smithAddress).andExpect {
            jsonPath("$.error.code") { value(-32602) }
        }
    }

    @Test
    fun `reauthorize goes as First mate's login header, refused without one, and is not a Smith's`() {
        engine.reauthorizable["acme/widgets" to 724L] = IssueApprovals.Reauthorized.Done("resumed", "parent-724", emptyList())
        val call = """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"reauthorize","arguments":{"ticket":724,"repo":"acme/widgets"}}}"""
        rpc(call).andExpect { jsonPath("$.result.isError") { value(true) } }
        assertTrue(engine.reauthorizations.isEmpty(), "no login, no call")
        rpc(call, from = smithAddress, login = "someone").andExpect {
            jsonPath("$.error.code") { value(-32602) }
        }
        assertTrue(engine.reauthorizations.isEmpty())
        rpc(call, login = "someone").andExpect {
            jsonPath("$.result.isError") { doesNotExist() }
        }
        assertEquals(listOf(Triple("acme/widgets", 724L, "someone")), engine.reauthorizations)
    }

    @Test
    fun `cancel_intake and amend_intake go as First mate's login header, and are not a Smith's`() {
        val id = "0a1b2c3d-0000-0000-0000-000000000001"
        val cancel = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"cancel_intake","arguments":{"intake_id":"$id","reason":"not needed"}}}"""
        val amend = """{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"amend_intake","arguments":{"intake_id":"$id","text":"make it green"}}}"""
        assertTrue(toolError(rpc(cancel)).startsWith("no GitHub login to cancel as"))
        for (call in listOf(cancel, amend))
            rpc(call, from = smithAddress, login = "someone").andExpect { jsonPath("$.error.code") { value(-32602) } }
        assertTrue(engine.cancels.isEmpty() && engine.amends.isEmpty())
        rpc(cancel, login = "someone").andExpect {
            jsonPath("$.result.isError") { doesNotExist() }
        }
        rpc(amend, login = "someone").andExpect {
            jsonPath("$.result.isError") { doesNotExist() }
        }
        assertEquals(listOf(Triple(id, "someone", "not needed")), engine.cancels)
        assertEquals(listOf(Triple(id, "someone", "make it green")), engine.amends)
    }

    @Test
    fun `First mate's note_friction is stamped first-mate with its login header and repo, refused without one, and a Smith's takes no repo`() {
        val note = """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"note_friction","arguments":{"kind":"tooling","summary":"no tool reads CI logs","repo":"acme/widgets"}}}"""
        assertTrue(toolError(rpc(note)).startsWith("no GitHub login to note as"))
        assertTrue(friction.rows.isEmpty(), "no login, no row")
        assertEquals("acme/elsewhere is not a target repo",
            toolError(rpc(note.replace("acme/widgets", "acme/elsewhere"), login = "someone")))
        assertTrue(friction.rows.isEmpty())
        rpc(note, login = " someone ").andExpect {
            jsonPath("$.result.isError") { doesNotExist() }
        }
        val row = friction.rows.single()
        assertEquals(listOf("first-mate", "someone", "acme/widgets", null, null, null),
            listOf(row.source, row.login, row.repo, row.issue, row.runId, row.processInstanceId))
        // A Smith's note is stamped from its run: `repo` is refused, and nothing is recorded.
        assertEquals("note_friction takes only \"kind\", \"summary\", \"evidence\", \"severity\", not \"repo\"",
            toolError(rpc(note, from = smithAddress, login = "someone")))
        assertEquals(1, friction.rows.size)
    }

    @Test
    fun `the repo writes go only with First mate's login header, read_repos without one, and none is a Smith's`() {
        val add = """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"add_repo","arguments":{"repo":"acme/new"}}}"""
        assertTrue(toolError(rpc(add)).startsWith("a GitHub login is needed to add a target repo"))
        assertTrue(engine.repoCalls.isEmpty(), "no login, no call")
        rpc("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"read_repos","arguments":{}}}""").andExpect {
            jsonPath("$.result.isError") { doesNotExist() }
        }
        for (tool in FrakworksMcp.REPO_TOOLS)
            rpc("""{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"$tool","arguments":{"repo":"acme/widgets"}}}""", from = smithAddress,
                login = "someone").andExpect { jsonPath("$.error.code") { value(-32602) } }
        assertTrue(engine.repoCalls.isEmpty())
        rpc(add, login = "someone").andExpect { jsonPath("$.result.isError") { doesNotExist() } }
        assertEquals("acme/new", engine.repoRows.last().fullName)
    }

    @Test
    fun `Docker not answering files nothing, and an Origin header is 403`() {
        sandbox.down = true
        rpc(mateSubmit, login = "someone").andExpect { jsonPath("$.result.isError") { value(true) } }
        assertTrue(engine.asks.isEmpty())
        sandbox.down = false
        rpc(mateSubmit, headers = mapOf("Origin" to "https://evil.example")).andExpect { status { isForbidden() } }
        assertTrue(engine.asks.isEmpty())
    }

    @Test
    fun `read_version answers the engine's version and commit to First mate and a Smith, and not to an unplaced caller`() {
        val call = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"read_version","arguments":{}}}"""
        for (r in listOf(rpc(call, login = "someone"), rpc(call, from = smithAddress))) {
            val result = mcpJson.readValue(r.andReturn().response.contentAsString, Map::class.java)["result"] as Map<*, *>
            assertNull(result["isError"], result.toString())
            val text = ((result["content"] as List<*>).first() as Map<*, *>)["text"] as String
            assertEquals(mapOf("version" to "0.0.1", "commit" to "0123456789abcdef0123456789abcdef01234567"), mcpJson.readValue(text, Map::class.java))
        }
        sandbox.down = true
        try {
            assertTrue(toolError(rpc(call, from = smithAddress)).startsWith("cannot tell whether the caller is a Smith sandbox"))
        } finally { sandbox.down = false }
    }
}
