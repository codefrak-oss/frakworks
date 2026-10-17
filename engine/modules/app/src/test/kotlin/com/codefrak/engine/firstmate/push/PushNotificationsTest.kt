package com.codefrak.engine.firstmate.push

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.codefrak.engine.TestTokens
import com.codefrak.engine.accounts.SecretsKey
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.firstmate.FirstMateSession
import com.codefrak.engine.firstmate.FirstMateSessions
import com.codefrak.engine.intake.IntakeProcessTest
import com.codefrak.engine.intake.RefiningSandbox
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.repos.MintedToken
import com.codefrak.engine.smith.SmithCaller
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An [ApnsClient] that records each request and answers [answer] (by default 200). */
class RecordingApns : ApnsClient {
    val sent = CopyOnWriteArrayList<ApnsRequest>()
    @Volatile var answer: (ApnsRequest) -> ApnsResult = { ApnsResult.Sent }
    override fun send(request: ApnsRequest): ApnsResult { sent += request; return answer(request) }
}

/**
 * Push notifications (engine/README.md, "Push notifications"): the device API and its refusals, the sealed APNs
 * credentials, the `needs` and chat-reply pushes against a recording APNs, and that a failing APNs never fails a request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntakeProcessTest.Fakes::class, PushNotificationsTest.Fake::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake", "engine.secrets.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="])
class PushNotificationsTest {
    @TestConfiguration
    class Fake { @Bean @Primary fun recordingApns() = RecordingApns() }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: EngineTokens
    @Autowired lateinit var access: Access
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var devices: PushDevices
    @Autowired lateinit var settings: ApnsSettings
    @Autowired lateinit var notifier: PushNotifier
    @Autowired lateinit var apns: RecordingApns
    @Autowired lateinit var events: EngineEvents
    @Autowired lateinit var sessions: FirstMateSessions
    @Autowired lateinit var sandbox: RefiningSandbox

    private val json = jacksonObjectMapper()
    private val smith = "172.18.0.9"
    private val phone = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private lateinit var alice: MintedToken
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val logged = ListAppender<ILoggingEvent>()

    companion object {
        val P8: String = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private.encoded
            .let { "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(it) + "\n-----END PRIVATE KEY-----" }
        val CREDS = ApnsCredentials(P8, "ABCDE12345", "TEAM123456", "com.codefrak.firstmate", PushDevices.PRODUCTION)
    }

    @BeforeEach fun setUp() {
        listOf("engine.push_device", "engine.first_mate_snapshot", "engine.first_mate_message", "engine.first_mate_session").forEach { jdbc.sql("DELETE FROM $it").update() }
        settings.clear()
        if (!access.trusted("alice")) access.add("alice", "test")
        if (!access.trusted("bob")) access.add("bob", "test")
        alice = tokens.mint("alice", "iPhone", null, "alice", EngineTokens.FIRST_MATE)
        apns.sent.clear(); apns.answer = { ApnsResult.Sent }
        sandbox.callers.clear(); sandbox.runAtFails = false
        sandbox.callers[smith] = SmithCaller("run-9", 9, "acme/tickets")
        logged.list.clear(); logged.start(); root.addAppender(logged)
    }

    @AfterEach fun release() { root.detachAppender(logged); logged.stop() }

    private fun register(token: String?, device: String = phone, from: String = "127.0.0.1", extra: String = ""): MvcResult =
        mvc.put("/first-mate/push/device") {
            contentType = MediaType.APPLICATION_JSON; content = """{"deviceToken":"$device"$extra}"""
            token?.let { header("Authorization", "Bearer $it") }; with(TestTokens.noToken); with(RequestPostProcessor { it.remoteAddr = from; it })
        }.andReturn()

    private fun unregister(token: String, device: String = phone): MvcResult =
        mvc.delete("/first-mate/push/device") {
            contentType = MediaType.APPLICATION_JSON; content = """{"deviceToken":"$device"}"""
            header("Authorization", "Bearer $token"); with(TestTokens.noToken)
        }.andReturn()

    private fun count() = jdbc.sql("SELECT COUNT(*) FROM engine.push_device").query(Int::class.java).single()
    private fun <T> body(r: MvcResult): T = json.readValue<Any?>(r.response.contentAsString) as T
    private fun payload(r: ApnsRequest): Map<String, Any?> = json.readValue(r.payload)
    private fun session(login: String = "alice"): FirstMateSession = sessions.start(login, alice.row.id, "acme/firstmate")
    private fun loggedAbove(level: Level) = logged.list.filter { it.level.isGreaterOrEqual(level) && it.loggerName.contains("push") }

    @Test
    fun `a first-mate token registers a device, a repeat updates the one row, and anything else is refused`() {
        val first = register(alice.token)
        assertEquals(201, first.response.status, first.response.contentAsString)
        assertEquals("alice", body<Map<String, Any?>>(first)["login"])
        assertEquals(200, register(alice.token, extra = ""","environment":"sandbox"""").response.status)
        assertEquals(1, count())
        assertEquals(PushDevices.SANDBOX, devices.get(phone)!!.environment)
        assertEquals(alice.row.id, devices.get(phone)!!.tokenId)

        assertEquals(401, register(null).response.status)
        val other = tokens.mint("alice", "laptop", null, "alice").token
        assertEquals(403, register(other).response.status)
        assertEquals(403, register(alice.token, from = smith).response.status)
        assertEquals(400, register(alice.token, device = "not-hex").response.status)
        assertEquals(1, count())
    }

    @Test
    fun `unregister removes the caller's row, and revoking its token removes the token's rows`() {
        register(alice.token)
        val bob = tokens.mint("bob", "iPhone", null, "bob", EngineTokens.FIRST_MATE).token
        assertEquals(200, unregister(bob).response.status, "bob's call does not reach alice's device")
        assertEquals(204, unregister(alice.token).response.status)
        assertNull(devices.get(phone))

        register(alice.token)
        assertEquals(204, mvc.delete("/tokens/${alice.row.id}").andReturn().response.status)
        assertNull(devices.get(phone))
    }

    @Test
    fun `the APNs credentials are sealed, read back only as set and since when, and 503 without the secrets key`() {
        val put = mvc.put("/push/apns") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("p8" to P8, "keyId" to CREDS.keyId, "teamId" to CREDS.teamId, "bundleId" to CREDS.bundleId, "environment" to "production"))
        }.andReturn()
        assertEquals(204, put.response.status, put.response.contentAsString)
        val read = mvc.get("/push/apns").andReturn().response.contentAsString
        assertFalse(read.contains("PRIVATE") || read.contains(CREDS.keyId), read)
        assertEquals(true, json.readValue<Map<String, Any?>>(read)["set"])
        val stored = jdbc.sql("SELECT setting FROM engine.engine_setting WHERE name = :n").param("n", ApnsSettings.NAME).query(String::class.java).single()
        assertFalse(stored.contains("PRIVATE") || stored.contains(CREDS.teamId), "sealed")
        assertEquals(CREDS, settings.get())

        val unkeyed = ApnsSettingsController(ApnsSettings(jdbc, SecretsKey("")), { null })
        assertEquals(503, unkeyed.set(mapOf("p8" to P8), TestTokens.asLogin()).statusCode.value())
        assertEquals(403, mvc.put("/push/apns") { contentType = MediaType.APPLICATION_JSON; content = "{}"; with(RequestPostProcessor { it.remoteAddr = smith; it }) }.andReturn().response.status)
    }

    @Test
    fun `a needs row for a login pushes to that login's devices, one for everyone to every device`() {
        settings.set(CREDS)
        register(alice.token)
        val bob = tokens.mint("bob", "iPhone", null, "bob", EngineTokens.FIRST_MATE).token
        val bobPhone = "ff".repeat(32)
        register(bob, device = bobPhone)

        events.publish(EngineEvent.Needs("acme/firstmate", "question", "added", 12, "act-1", forLogin = "alice"))
        events.publish(EngineEvent.Needs("acme/firstmate", "question", "removed", 12, "act-1", forLogin = "alice"))
        assertTrue(notifier.awaitIdle())
        assertEquals(1, apns.sent.size)
        val r = apns.sent.single()
        assertEquals("/3/device/$phone", r.path)
        assertEquals(CREDS.bundleId, r.headers["apns-topic"])
        assertTrue(r.headers["authorization"]!!.matches(Regex("^bearer [\\w-]+\\.[\\w-]+\\.[\\w-]+$")), r.headers["authorization"])
        val p = payload(r)
        assertEquals("A question waits for you: acme/firstmate#12", ((p["aps"] as Map<*, *>)["alert"] as Map<*, *>)["body"])
        assertEquals(mapOf("type" to "needs", "kind" to "question", "repo" to "acme/firstmate", "number" to 12, "actionId" to "act-1"), p["fm"])

        apns.sent.clear()
        events.publish(EngineEvent.Needs(EngineEvent.FLEET, "approve", "added", null, "task-7", forLogin = null))
        assertTrue(notifier.awaitIdle())
        assertEquals(setOf("/3/device/$phone", "/3/device/$bobPhone"), apns.sent.map { it.path }.toSet())
        assertEquals("task-7", (payload(apns.sent.first())["fm"] as Map<*, *>)["actionId"])
    }

    @Test
    fun `a result line pushes once to the session owner's devices, other lines push nothing`() {
        settings.set(CREDS)
        register(alice.token)
        val bob = tokens.mint("bob", "iPhone", null, "bob", EngineTokens.FIRST_MATE).token
        register(bob, device = "ee".repeat(32))
        val s = session()
        val pod = tokens.mint("alice", "pod", null, "engine", EngineTokens.FIRST_MATE_POD, s.id).token
        val lines = listOf("""{"type":"assistant","message":{}}""", """{"type":"result","result":"done"}""").map { json.writeValueAsString(it) }
        val r = mvc.post("/first-mate/pod/${s.id}/outbox") {
            contentType = MediaType.APPLICATION_JSON; content = """{"from":1,"lines":[${lines.joinToString(",")}]}"""
            header("Authorization", "Bearer $pod")
        }.andReturn()
        assertEquals(200, r.response.status, r.response.contentAsString)
        assertTrue(notifier.awaitIdle())
        assertEquals(listOf("/3/device/$phone"), apns.sent.map { it.path })
        assertEquals(mapOf("type" to "chat", "session" to s.id, "repo" to "acme/firstmate"), payload(apns.sent.single())["fm"])
    }

    @Test
    fun `APNs saying the token is gone deletes the row`() {
        settings.set(CREDS)
        register(alice.token)
        apns.answer = { ApnsClient.classify(410, """{"reason":"Unregistered"}""") }
        events.publish(EngineEvent.Needs("acme/firstmate", "decision", "added", 3, "d-1", forLogin = "alice"))
        assertTrue(notifier.awaitIdle())
        assertNull(devices.get(phone))
        assertEquals(ApnsResult.Gone(400, "BadDeviceToken"), ApnsClient.classify(400, """{"reason":"BadDeviceToken"}"""))
    }

    @Test
    fun `a 500 or a refused connection is logged once and the request and the frame still succeed`() {
        settings.set(CREDS)
        register(alice.token)
        apns.answer = { ApnsClient.classify(500, """{"reason":"InternalServerError"}""") }
        val live = session()
        val pod = tokens.mint("alice", "pod", null, "engine", EngineTokens.FIRST_MATE_POD, live.id).token
        val line = json.writeValueAsString("""{"type":"result"}""")
        val r = mvc.post("/first-mate/pod/${live.id}/outbox") {
            contentType = MediaType.APPLICATION_JSON; content = """{"from":1,"lines":[$line]}"""; header("Authorization", "Bearer $pod")
        }.andReturn()
        assertEquals(200, r.response.status)
        assertTrue(notifier.awaitIdle())
        assertEquals(1, loggedAbove(Level.WARN).count { it.formattedMessage.contains("500") && it.formattedMessage.contains("InternalServerError") })
        assertNotNull(devices.get(phone), "a failure keeps the row")

        val port = ServerSocket(0).use { it.localPort }
        val refused = JdkApnsClient(mapOf(PushDevices.PRODUCTION to "http://127.0.0.1:$port")).send(ApnsRequest(PushDevices.PRODUCTION, "/3/device/$phone", emptyMap(), "{}"))
        assertTrue(refused is ApnsResult.Failed && refused.status == null, refused.toString())
        apns.answer = { refused }
        logged.list.clear()
        events.publish(EngineEvent.Needs("acme/firstmate", "question", "added", 4, "q-4", forLogin = "alice"))
        assertTrue(notifier.awaitIdle())
        assertEquals(1, loggedAbove(Level.WARN).size, loggedAbove(Level.WARN).map { it.formattedMessage }.toString())
    }

    @Test
    fun `with no credentials or no device nothing is sent and nothing is logged above DEBUG`() {
        register(alice.token)
        logged.list.clear()
        events.publish(EngineEvent.Needs("acme/firstmate", "question", "added", 5, "q-5", forLogin = "alice"))
        assertTrue(notifier.awaitIdle(), "q-5 is handled before the credentials are set")
        settings.set(CREDS)
        events.publish(EngineEvent.Needs("acme/firstmate", "question", "added", 6, "q-6", forLogin = "carol"))
        assertTrue(notifier.awaitIdle())
        assertEquals(emptyList(), apns.sent)
        assertEquals(emptyList(), loggedAbove(Level.INFO).map { it.formattedMessage })
    }

    @Test
    fun `the JWT is ES256 and verifies with the key's public half`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val jwt = ApnsJwt.sign(pair.private, "ABCDE12345", "TEAM123456", java.time.Instant.ofEpochSecond(1_700_000_000))
        val (h, c, sig) = jwt.split(".")
        val dec = Base64.getUrlDecoder()
        assertEquals(mapOf("alg" to "ES256", "kid" to "ABCDE12345"), json.readValue<Map<String, Any?>>(dec.decode(h)))
        assertEquals(mapOf("iss" to "TEAM123456", "iat" to 1_700_000_000), json.readValue<Map<String, Any?>>(dec.decode(c)))
        val verifier = java.security.Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(pair.public); update("$h.$c".toByteArray()) }
        assertEquals(64, dec.decode(sig).size)
        assertTrue(verifier.verify(dec.decode(sig)))
        val jwts = ApnsJwt()
        assertEquals(jwts.token(CREDS), jwts.token(CREDS), "cached")
    }
}
