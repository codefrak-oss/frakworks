package com.codefrak.engine.firstmate

import com.codefrak.engine.BearerTokenFilter
import com.codefrak.engine.EngineApplication
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.EngineTokens
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.core.type.classreading.MetadataReaderFactory
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The pod protocol served by two engine replicas on one database: the phone posts on B, the pod polls its inbox on A, posts its output to B, and the phone reads the
 * transcript on A; a retried outbox batch and a retried message (same clientId) are stored once. The two contexts share
 * one named H2 database, as the test build runs H2 (the event relay is `noop` there, so A's poll finds B's message by
 * its database backstop, as it would after a lost frame).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FirstMatePodTwoReplicaTest {
    private val json = jacksonObjectMapper()
    private val db = "jdbc:h2:mem:first-mate-replicas-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
    private lateinit var a: ConfigurableApplicationContext
    private lateinit var b: ConfigurableApplicationContext

    /** What a Spring test leaves out of the scan: test configurations and the configurations nested in test classes. */
    class TestsExcluded : TypeExcludeFilter() {
        override fun match(reader: MetadataReader, factory: MetadataReaderFactory): Boolean {
            if (reader.annotationMetadata.hasAnnotation(TestConfiguration::class.java.name)) return true
            val enclosing = reader.classMetadata.enclosingClassName ?: return false
            return factory.getMetadataReader(enclosing).annotationMetadata.hasAnnotation(SpringBootTest::class.java.name)
        }
        override fun equals(other: Any?) = other is TestsExcluded
        override fun hashCode() = TestsExcluded::class.hashCode()
    }

    private fun replica(): ConfigurableApplicationContext =
        SpringApplicationBuilder(EngineApplication::class.java, ProcessFakes::class.java)
            .initializers({ ctx -> ctx.beanFactory.registerSingleton("testsExcluded", TestsExcluded()) })
            // As arguments, not .properties(): those are defaults, which application.properties overrides.
            .run("--spring.datasource.url=$db", "--engine.smith.sandbox=fake", "--server.port=0")

    @BeforeAll fun start() { a = replica(); b = replica() }
    @AfterAll fun stop() { if (::b.isInitialized) b.close(); if (::a.isInitialized) a.close() }

    private fun mvc(ctx: ConfigurableApplicationContext): MockMvc =
        MockMvcBuilders.webAppContextSetup(ctx as WebApplicationContext).addFilters<DefaultMockMvcBuilder>(ctx.getBean(BearerTokenFilter::class.java)).build()

    private fun post(mvc: MockMvc, path: String, body: String, token: String): MvcResult = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON; content = body; header("Authorization", "Bearer $token")
    }.andReturn()
    private fun get(mvc: MockMvc, path: String, token: String): MvcResult = mvc.get(path) { header("Authorization", "Bearer $token") }.andReturn()
    private fun read(r: MvcResult): Any? = json.readValue<Any?>(r.response.contentAsString)

    @Test
    fun `a message posted on B reaches the pod polling A, its output posted to B reads on A, and retries store nothing twice`() {
        val mvcA = mvc(a); val mvcB = mvc(b)
        a.getBean(Access::class.java).let { if (!it.trusted("alice")) it.add("alice", "test") }
        val tokens = a.getBean(EngineTokens::class.java)
        val phone = tokens.mint("alice", "iPhone", null, "alice", EngineTokens.FIRST_MATE)
        val session = a.getBean(FirstMateSessions::class.java).start("alice", phone.row.id, "acme/firstmate")
        val pod = tokens.mint("alice", "pod", null, "engine", EngineTokens.FIRST_MATE_POD, session.id).token

        val message = """{"text":"what's on the board?","clientId":"c-1"}"""
        assertEquals(201, post(mvcB, "/first-mate/sessions/${session.id}/messages", message, phone.token).response.status)
        assertEquals(200, post(mvcB, "/first-mate/sessions/${session.id}/messages", message, phone.token).response.status, "a retry stores nothing")

        @Suppress("UNCHECKED_CAST")
        val inbox = read(get(mvcA, "/first-mate/pod/${session.id}/inbox?after=0&wait=10", pod)) as List<Map<String, Any?>>
        assertEquals(listOf(mapOf("text" to "what's on the board?")), inbox.map { it["body"] })
        val seq = inbox.single()["seq"]
        assertEquals(seq, (read(post(mvcA, "/first-mate/pod/${session.id}/inbox/ack", """{"seq":$seq}""", pod)) as Map<*, *>)["deliveredSeq"])

        val batch = """{"from":1,"lines":["{\"type\":\"assistant\",\"text\":\"three tickets\"}","{\"type\":\"result\"}"]}"""
        assertEquals(2, (read(post(mvcB, "/first-mate/pod/${session.id}/outbox", batch, pod)) as Map<*, *>)["accepted"])
        assertEquals(2, (read(post(mvcA, "/first-mate/pod/${session.id}/outbox", batch, pod)) as Map<*, *>)["accepted"], "the retry, on the other replica")

        @Suppress("UNCHECKED_CAST")
        val messages = (read(get(mvcA, "/first-mate/sessions/${session.id}/transcript", phone.token)) as Map<String, Any?>)["messages"] as List<Map<String, Any?>>
        assertEquals(listOf("user", "assistant", "assistant"), messages.map { it["role"] })
        assertEquals(listOf(1, 2, 3), messages.map { it["seq"] })
        assertEquals(emptyList<Any?>(), read(get(mvcA, "/first-mate/pod/${session.id}/inbox?after=$seq&wait=0", pod)))
    }
}
