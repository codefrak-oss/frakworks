package com.codefrak.engine.repos

import com.codefrak.engine.TestTokens
import com.codefrak.engine.github.ReadFakes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `/access` over MockMvc: the engine-wide list of trusted logins (engine/README.md, "Starting a flow from an issue"). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class EngineAccessControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var access: Access
    @Autowired lateinit var repos: TargetRepos

    @BeforeEach @AfterEach
    fun clean() { access.list().forEach { access.remove(it.login, null) } }

    private fun post(body: String) =
        mvc.post("/access") { contentType = MediaType.APPLICATION_JSON; content = body }.andReturn()

    @Test
    fun `lists, adds and removes as the token's login, 201 then 200, 404 on a login not on the list, and a by is 400`() {
        assertEquals(201, post("""{"login":"grace"}""").response.status)
        assertEquals(200, post("""{"login":"grace"}""").response.status)
        val body = mvc.get("/access").andReturn().response.contentAsString
        assertContains(body, "{\"login\":\"grace\"}")
        assertContains(body, "\"changedBy\":\"${TestTokens.DEFAULT_LOGIN}\"")
        assertEquals(400, post("""{"login":"grace","by":"test"}""").response.status)
        assertEquals(400, mvc.delete("/access/grace?by=test").andReturn().response.status)
        assertEquals(204, mvc.delete("/access/grace").andReturn().response.status)
        assertEquals(404, mvc.delete("/access/grace").andReturn().response.status)
        assertEquals(listOf(TestTokens.DEFAULT_LOGIN), access.list().map { it.login })
    }

    @Test
    fun `a flow field or a bad login is 400`() {
        val flow = post("""{"flow":"chore","login":"grace"}""")
        assertEquals(400, flow.response.status)
        assertContains(flow.response.errorMessage ?: "", "no kinds")
        assertEquals(400, post("""{"login":"not a login"}""").response.status)
        assertEquals(400, post("""{}""").response.status)
        assertEquals(listOf(TestTokens.DEFAULT_LOGIN), access.list().map { it.login })
    }

    @Test
    fun `the per-repo access endpoints are gone`() {
        repos.add("acme/access-gone")
        try {
            assertEquals(404, mvc.get("/repos/acme/access-gone/access").andReturn().response.status)
        } finally { repos.remove("acme/access-gone") }
    }
}
