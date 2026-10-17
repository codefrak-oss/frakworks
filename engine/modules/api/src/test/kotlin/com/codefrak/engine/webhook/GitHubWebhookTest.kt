package com.codefrak.engine.webhook

import com.codefrak.engine.board.BoardIssue
import com.codefrak.engine.board.BoardRows
import com.codefrak.engine.ci.CiCheckWaits
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.trigger.IssueScanWakes
import com.codefrak.engine.trigger.IssueScanner
import com.codefrak.engine.trigger.ScanAction
import com.codefrak.engine.trigger.ScanReport
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubWebhookTest {
    private val secret = "a-long-random-string"
    private val nothing = ScanReport(emptyList(), emptyList())
    private val scanner = mock(IssueScanner::class.java).also { `when`(it.scan()).thenReturn(nothing) }
    private val ciWaits = mock(CiCheckWaits::class.java)
    private val slept = mutableListOf<Duration>()
    private var now = Instant.parse("2026-10-02T12:00:00Z")
    /** The lease holder unless a test says otherwise; a follower's hand-overs counted, one pending at most. */
    private val leader = object : WakeLeader {
        var holder = true
        var handed = 0
        var pending = false
        override fun leading() = holder
        override fun hand() { handed++; pending = true }
        override fun take() = pending.also { pending = false }
    }
    private val wake = WebhookWake(IssueScanWakes(scanner, ciWaits), leader, Duration.ofSeconds(15)).apply {
        sleep = { slept += it }
        dispatch = { it() }  // inline, so a test sees the wake's scans when the request returns
        clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
    }
    private val repo = "acme/widgets"
    /** Each repo's own secret: [repo]'s is [secret], `acme/other`'s another, `acme/bare` has none. */
    private val secrets = WebhookSecrets { mapOf(repo to secret, "acme/other" to "the-other-repo's-secret")[it] }
    private val bus = EngineEvents(jacksonObjectMapper())
    /** The board rows by number, as [com.codefrak.engine.board.IssueBoard.row] reads them; each read recorded. */
    private val rowsRead = mutableListOf<Long>()
    private var rowRead: (Long) -> BoardIssue? = { n ->
        val t = OffsetDateTime.parse("2026-10-05T12:50:57Z")
        BoardIssue(repo, n, "Issue $n", "someone", listOf("chore"), "https://github.com/$repo/issues/$n", t, t,
            kind = "chore", authorized = false, flow = null, pendingQuestion = null)
    }
    private val rows = BoardRows { _, n -> rowsRead += n; rowRead(n) }
    private val controller = GitHubWebhookController(wake, secrets, bus, rows)
    private fun mvc(): MockMvc = MockMvcBuilders.standaloneSetup(controller).build()

    private fun sign(body: ByteArray, key: String = secret): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    private fun deliver(event: String, body: ByteArray, signature: String? = sign(body), mvc: MockMvc = mvc()) =
        mvc.post("/hooks/github") {
            header("X-GitHub-Event", event)
            signature?.let { header("X-Hub-Signature-256", it) }
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = body
        }

    private fun payload(action: String?, repo: String? = this.repo): ByteArray {
        val from = repo?.let { ""","repository":{"full_name":"$it"}""" }.orEmpty()
        return (if (action == null) """{"zen":"hi"$from}""" else """{"action":"$action","issue":{"number":3}$from}""").toByteArray()
    }

    @Test
    fun `a correctly signed delivery is 204 with an empty body, and wakes`() {
        deliver("issues", payload("labeled")).andExpect {
            status { isNoContent() }
            content { string("") }
        }
        verify(scanner, times(2)).scan()
    }

    @Test
    fun `a missing, malformed or wrong signature is 401 and never scans`() {
        val body = payload("labeled")
        val good = sign(body)
        for (signature in listOf(null, "", good.removePrefix("sha256="), "sha1=" + good.removePrefix("sha256="), good.dropLast(1), good + "0", sign(body, "another-secret"))) {
            deliver("issues", body, signature).andExpect { status { isUnauthorized() } }
        }
        verify(scanner, never()).scan()
        verify(ciWaits, never()).poll()
    }

    @Test
    fun `every allowlisted event and action wakes`() {
        val pairs = listOf(
            "issues" to "labeled", "issues" to "unlabeled", "issues" to "reopened", "issues" to "closed",
            "pull_request" to "opened", "pull_request" to "synchronize", "pull_request" to "closed",
            "pull_request_review" to "submitted", "check_suite" to "completed", "ping" to null,
        )
        for ((event, action) in pairs) {
            now = now.plusSeconds(2)
            deliver(event, payload(action)).andExpect { status { isNoContent() } }
        }
        verify(scanner, times(2 * pairs.size)).scan()
        verify(ciWaits, times(2 * pairs.size)).poll()
    }

    @Test
    fun `a signed delivery that is not allowlisted is 204 and wakes nothing`() {
        for ((event, body) in listOf(
            "issues" to payload("edited"), "push" to payload(null), "pull_request" to payload("labeled"),
            "check_suite" to payload("requested"), "ping" to payload("labeled"),
            "issues" to """{"action":7,"repository":{"full_name":"acme/widgets"}}""".toByteArray(),
        )) {
            now = now.plusSeconds(2)
            deliver(event, body).andExpect { status { isNoContent() } }
        }
        verify(scanner, never()).scan()
    }

    @Test
    fun `deliveries within a second wake once, and one after the window wakes again`() {
        deliver("issues", payload("labeled")).andExpect { status { isNoContent() } }
        now = now.plusMillis(999)
        deliver("pull_request", payload("opened")).andExpect { status { isNoContent() } }
        verify(scanner, times(2)).scan()  // one wake: its scan and its follow-up
        now = now.plusMillis(1)
        deliver("issues", payload("closed")).andExpect { status { isNoContent() } }
        verify(scanner, times(4)).scan()
    }

    @Test
    fun `a body over 1 MiB is refused and wakes nothing`() {
        val body = ByteArray(GitHubWebhookController.MAX_BODY + 1) { ' '.code.toByte() }
        deliver("issues", body).andExpect { status { isPayloadTooLarge() } }
        verify(scanner, never()).scan()
    }

    @Test
    fun `a body with no length is read up to one byte past the cap and no further`() {
        val cap = GitHubWebhookController.MAX_BODY
        val atCap = ByteArray(cap) { 1 }
        assertContentEquals(atCap, GitHubWebhookController.readCapped(ByteArrayInputStream(atCap)))
        val over = ByteArrayInputStream(ByteArray(cap + 10))
        assertNull(GitHubWebhookController.readCapped(over))
        assertEquals(9, over.available())
    }

    @Test
    fun `a delivery is checked against its own repo's secret, and one naming no repo with a secret is 401`() {
        // Signed with acme/widgets's secret but naming another repo: that repo's secret decides.
        val other = payload("labeled", "acme/other")
        deliver("issues", other, sign(other)).andExpect { status { isUnauthorized() } }
        deliver("issues", other, sign(other, "the-other-repo's-secret")).andExpect { status { isNoContent() } }
        verify(scanner, times(2)).scan()
        // No repo in the payload, a repo with no secret, a repo that is no target repo, and a body that is not a JSON object.
        for (body in listOf(payload("labeled", null), payload("labeled", "acme/bare"), payload("labeled", "nobody/nothing"),
            "not json".toByteArray(), """["labeled"]""".toByteArray(), """{"repository":"acme/widgets"}""".toByteArray(), ByteArray(0))) {
            now = now.plusSeconds(2)
            deliver("issues", body, sign(body)).andExpect { status { isUnauthorized() } }
        }
        verify(scanner, times(2)).scan()
    }

    @Test
    fun `a wake scans and checks, sleeps the follow-up, then scans and checks again`() {
        deliver("issues", payload("labeled"))
        val order = inOrder(scanner, ciWaits)
        order.verify(scanner).scan()
        order.verify(ciWaits).poll()
        order.verify(scanner).scan()
        order.verify(ciWaits).poll()
        assertEquals(listOf(Duration.ofSeconds(15)), slept)
    }

    @Test
    fun `a follow-up that finds what the first scan missed still acts on it`() {
        val lagged = ScanReport(listOf(ScanAction("acme/widgets", 18, "chore", "started", "instance-1")), emptyList())
        `when`(scanner.scan()).thenReturn(nothing, lagged)  // the listing had not caught up the first time
        deliver("issues", payload("labeled")).andExpect { status { isNoContent() } }
        verify(scanner, times(2)).scan()
    }

    @Test
    fun `a failing scan or check pass does not stop the wake or break the endpoint`() {
        `when`(scanner.scan()).thenThrow(IllegalStateException("GitHub is down"))
        `when`(ciWaits.poll()).thenThrow(IllegalStateException("GitHub is down"))
        deliver("issues", payload("labeled")).andExpect { status { isNoContent() } }
        verify(scanner, times(2)).scan()
        verify(ciWaits, times(2)).poll()
        assertEquals(listOf(Duration.ofSeconds(15)), slept)
    }

    // The stream (engine/README.md, "Events"): one `ticket` frame per issues, issue_comment or pull_request delivery.

    private val full = ""","repository":{"full_name":"acme/widgets","private":true},"sender":{"login":"someone"}"""

    @Test
    fun `a verified delivery is one ticket frame that reads the number, the action and a label's name, and nothing else`() = EventRecorder(bus).use { rec ->
        val labeled = ("""{"action":"labeled","issue":{"number":3,"title":"Secret title","body":"secret body","labels":[{"name":"x"}]},""" +
            """"label":{"name":"authorized","color":"0e8a16"}$full}""").toByteArray()
        deliver("issues", labeled).andExpect { status { isNoContent() } }
        val frame = rec.of("ticket").single()
        assertEquals(setOf("repo", "number", "action", "label", "prNumber", "issue", "at"), frame.data.propertyNames().toSet(), "only the named fields")
        assertEquals(listOf("acme/widgets", 3L, "labeled", "authorized", null),
            listOf(frame.str("repo"), frame.long("number"), frame.str("action"), frame.str("label"), frame.long("prNumber")))
        verify(scanner, times(2)).scan()

        now = now.plusSeconds(2)
        deliver("issues", """{"action":"edited","issue":{"number":4,"title":"t"}$full}""".toByteArray()).andExpect { status { isNoContent() } }
        val edited = rec.of("ticket").last()
        assertEquals(listOf(4L, "edited", null), listOf(edited.long("number"), edited.str("action"), edited.str("label")), "no label outside a label event")
        verify(scanner, times(2)).scan() // edited is not allowlisted: a frame, no wake

        deliver("issue_comment", """{"action":"created","issue":{"number":5},"comment":{"body":"hi"}$full}""".toByteArray()).andExpect { status { isNoContent() } }
        assertEquals(5L to "commented", rec.of("ticket").last().let { it.long("number") to it.str("action") })
        verify(scanner, times(2)).scan() // an issue_comment delivery wakes nothing

        now = now.plusSeconds(2)
        deliver("pull_request", """{"action":"opened","pull_request":{"number":9,"title":"t"}$full}""".toByteArray()).andExpect { status { isNoContent() } }
        val pr = rec.of("ticket").last()
        assertEquals(listOf(9L, "pr", 9L), listOf(pr.long("number"), pr.str("action"), pr.long("prNumber")))
        assertEquals(4, rec.of("ticket").size)
    }

    // The `ticket` frame's `issue`: the board row by number for an open issue's `issues` delivery, null otherwise.

    @Test
    fun `an opened delivery carries the new issue's board row, read by number`() = EventRecorder(bus).use { rec ->
        deliver("issues", """{"action":"opened","issue":{"number":1034,"state":"open","title":"t"}$full}""".toByteArray())
            .andExpect { status { isNoContent() } }
        val frame = rec.of("ticket").single()
        assertEquals(listOf(1034L, "opened"), listOf(frame.long("number"), frame.str("action")))
        val issue = frame.data["issue"]
        assertEquals(listOf("acme/widgets", 1034L, "Issue 1034", "chore"),
            listOf(issue["repo"].asString(), issue["number"].asLong(), issue["title"].asString(), issue["kind"].asString()))
        assertEquals(listOf(1034L), rowsRead)
    }

    @Test
    fun `a closed issue, an issue comment and a pull request carry issue null and read no row`() = EventRecorder(bus).use { rec ->
        deliver("issues", """{"action":"closed","issue":{"number":3,"state":"closed"}$full}""".toByteArray()).andExpect { status { isNoContent() } }
        deliver("issue_comment", """{"action":"created","issue":{"number":3,"state":"open"}$full}""".toByteArray()).andExpect { status { isNoContent() } }
        now = now.plusSeconds(2)
        deliver("pull_request", """{"action":"opened","pull_request":{"number":9,"state":"open"}$full}""".toByteArray()).andExpect { status { isNoContent() } }
        val frames = rec.of("ticket")
        assertEquals(listOf("closed", "commented", "pr"), frames.map { it.str("action") })
        frames.forEach { assertTrue(it.data["issue"].isNull, "${it.str("action")}: issue null") }
        assertEquals(emptyList(), rowsRead)
    }

    @Test
    fun `a row that fails or is slower than the bound leaves issue null, and the delivery is answered within the bound`() = EventRecorder(bus).use { rec ->
        rowRead = { error("GitHub 502") }
        deliver("issues", """{"action":"labeled","issue":{"number":3,"state":"open"},"label":{"name":"x"}$full}""".toByteArray())
            .andExpect { status { isNoContent() } }
        assertTrue(rec.of("ticket").single().data["issue"].isNull)

        controller.rowWait = Duration.ofMillis(200)
        val release = java.util.concurrent.CountDownLatch(1)
        rowRead = { release.await(); null }
        val started = System.nanoTime()
        now = now.plusSeconds(2)
        deliver("issues", """{"action":"edited","issue":{"number":4,"state":"open"}$full}""".toByteArray()).andExpect { status { isNoContent() } }
        val took = Duration.ofNanos(System.nanoTime() - started)
        release.countDown()
        assertTrue(took < Duration.ofSeconds(2), "answered at the bound, not when GitHub was: $took")
        val slow = rec.of("ticket").last()
        assertEquals(4L, slow.long("number"))
        assertTrue(slow.data["issue"].isNull)
    }

    @Test
    fun `a bad signature, another event, a delivery with no number or no action publishes nothing`() = EventRecorder(bus).use { rec ->
        val body = payload("labeled")
        deliver("issues", body, sign(body, "another-secret")).andExpect { status { isUnauthorized() } }
        deliver("issues", body, null).andExpect { status { isUnauthorized() } }
        for ((event, b) in listOf("check_suite" to payload("completed"), "ping" to payload(null), "push" to payload(null),
            "issues" to payload(null), "issues" to """{"action":"labeled","issue":{"number":"3"}$full}""".toByteArray(),
            "issue_comment" to """{"action":"created"$full}""".toByteArray())) {
            now = now.plusSeconds(2)
            deliver(event, b).andExpect { status { isNoContent() } }
        }
        assertEquals(emptyList(), rec.of("ticket"))
    }

    // The `push` frame: a verified push delivery, its five fields, and no wake.

    private fun pushPayload(ref: String?, defaultBranch: String? = "main", before: String? = "0000000000000000000000000000000000000001",
                            after: String? = "0000000000000000000000000000000000000002"): ByteArray {
        val top = listOfNotNull(ref?.let { "\"ref\":\"$it\"" }, before?.let { "\"before\":\"$it\"" }, after?.let { "\"after\":\"$it\"" },
            "\"pusher\":{\"name\":\"someone\"}", "\"commits\":[{\"id\":\"x\",\"message\":\"secret\"}]").joinToString(",")
        val branch = defaultBranch?.let { ""","default_branch":"$it"""" }.orEmpty()
        return """{$top,"repository":{"full_name":"acme/widgets","private":true$branch}}""".toByteArray()
    }

    @Test
    fun `a verified push delivery is one push frame with repo, ref, before, after and default, and wakes nothing`() = EventRecorder(bus).use { rec ->
        deliver("push", pushPayload("refs/heads/main")).andExpect { status { isNoContent() } }
        val frame = rec.of("push").single()
        assertEquals(setOf("repo", "ref", "before", "after", "default", "at"), frame.data.propertyNames().toSet(), "only the named fields")
        assertEquals(listOf("acme/widgets", "refs/heads/main", "0000000000000000000000000000000000000001", "0000000000000000000000000000000000000002"),
            listOf(frame.str("repo"), frame.str("ref"), frame.str("before"), frame.str("after")))
        assertEquals(true, frame.data["default"].asBoolean())
        assertEquals(false, frame.data["at"].isNull)

        now = now.plusSeconds(2)
        deliver("push", pushPayload("refs/heads/feature")).andExpect { status { isNoContent() } }
        assertEquals(false, rec.of("push").last().data["default"].asBoolean(), "not the default branch")
        deliver("push", pushPayload("refs/heads/main", defaultBranch = null)).andExpect { status { isNoContent() } }
        assertEquals(false, rec.of("push").last().data["default"].asBoolean(), "no default branch named: false")
        assertEquals(3, rec.of("push").size)
        verify(scanner, never()).scan()  // a push is not allowlisted: a frame, no wake
        assertEquals(emptyList(), rec.of("ticket"))
    }

    @Test
    fun `a push with a bad signature, or without ref, before or after, publishes no push frame`() = EventRecorder(bus).use { rec ->
        val body = pushPayload("refs/heads/main")
        deliver("push", body, sign(body, "another-secret")).andExpect { status { isUnauthorized() } }
        deliver("push", body, null).andExpect { status { isUnauthorized() } }
        for (b in listOf(pushPayload(null), pushPayload("refs/heads/main", before = null), pushPayload("refs/heads/main", after = null),
            """{"ref":7,"before":"a","after":"b"$full}""".toByteArray())) {
            deliver("push", b).andExpect { status { isNoContent() } }
        }
        deliver("issues", """{"action":"edited","ref":"refs/heads/main","before":"a","after":"b","issue":{"number":4}$full}""".toByteArray())
            .andExpect { status { isNoContent() } }
        assertEquals(emptyList(), rec.of("push"))
        verify(scanner, never()).scan()
    }

    @Test
    fun `a wake runs off the request thread`() {
        val queued = mutableListOf<() -> Unit>()
        wake.dispatch = { queued += it }
        deliver("issues", payload("labeled")).andExpect { status { isNoContent() } }
        verify(scanner, never()).scan()
        queued.single()()
        verify(scanner, times(2)).scan()
    }

    @Test
    fun `a follower scans nothing on a delivery and hands the wake to the holder, which wakes once on its next tick`() {
        leader.holder = false
        deliver("issues", payload("labeled")).andExpect { status { isNoContent() } }
        wake.relayed()
        verify(scanner, never()).scan()
        verify(ciWaits, never()).poll()
        assertEquals(1, leader.handed)

        // The same engine as the holder: it takes the handed wake once.
        leader.holder = true
        wake.relayed()
        verify(scanner, times(2)).scan()
        now = now.plusSeconds(2)
        wake.relayed()
        verify(scanner, times(2)).scan()
    }
}
