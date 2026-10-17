package com.codefrak.engine.friction

import com.codefrak.engine.TOKEN_LOGIN_ATTRIBUTE
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.smith.SmithCaller
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /friction/notes`, `/friction/groups` and `/friction/summary`, and the corrections `PATCH /friction/notes/{id}` and
 * `POST /friction/groups/{id}/merge`, over MockMvc against a seeded `engine.friction_note` in H2. The sandbox is
 * [ScriptedSandbox], so a request's source address can be made a Smith's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class FrictionControllerTest {
    @TestConfiguration
    class Fakes {
        @Bean fun sandbox() = ScriptedSandbox()
        /** `i-run` is a running intake in acme/a, `i-end` one that ended without a ticket; any other id is unknown. */
        @Bean @Primary fun intakeFates() = IntakeFates {
            when (it) { "i-run" -> IntakeFate.Running("acme/a"); "i-end" -> IntakeFate.Ended; else -> IntakeFate.Unknown }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var notes: FrictionNotes
    @Autowired lateinit var groups: FrictionGroups
    @Autowired lateinit var groupFixes: FrictionGroupFixes
    @Autowired lateinit var groupBugs: FrictionGroupBugs
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var calls: JevCalls
    @Autowired lateinit var sandbox: ScriptedSandbox

    private val json = jacksonObjectMapper()
    // Truncated so a seed round-trips the column exactly: `old` is `now.minusDays(40)` as it is, and `isEqual` compares the nanos too.
    private val now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)
    private val day1 = now.minusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0)
    private val day2 = now.minusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)

    private fun note(id: String, at: OffsetDateTime, repo: String? = "acme/a", issue: Long? = 7, kind: String = "docs",
                     source: String = FrictionNote.AS_YOU_GO, severity: String? = null, login: String? = null) =
        FrictionNote(id, repo, issue, if (issue == null) null else "run-$id", if (issue == null) null else "root-$id", at, source, kind,
            "summary $id", "evidence $id", severity, login)

    @BeforeEach
    fun seed() {
        jdbc.sql("DELETE FROM engine.friction_note").update()
        jdbc.sql("DELETE FROM engine.friction_signature").update()
        jdbc.sql("DELETE FROM engine.friction_group").update()
        jdbc.sql("DELETE FROM engine.jev_call").update()
        sandbox.callers.clear(); sandbox.runAtFails = false
        listOf(
            note("a1", day1, kind = "bug", severity = "high"),
            note("a2", day1.plusHours(1), kind = "docs", source = FrictionNote.RETRO, severity = "low"),
            note("b1", day1.plusHours(2), repo = "acme/b", issue = 9, kind = "tooling", severity = "medium"),
            note("b2", day2, repo = "acme/b", issue = 9, kind = "bug", source = FrictionNote.RETRO),
            note("f1", day2.plusHours(1), repo = null, issue = null, kind = "design", source = FrictionNote.FIRST_MATE, login = "lundmatt1"),
            note("f2", day2.plusHours(2), repo = "acme/a", issue = null, kind = "other", source = FrictionNote.FIRST_MATE, severity = "high", login = "lundmatt1"),
            note("old", now.minusDays(40), kind = "bug", severity = "high"),
        ).forEach { notes.insert(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(url: String): T = json.readValue<Any>(mvc.get(url).andExpect { status { isOk() } }.andReturn().response.contentAsString) as T
    private fun ids(url: String) = get<List<Map<String, Any?>>>(url).map { it["id"] }

    @Test
    fun `the notes come newest first with every field in snake_case`() {
        assertEquals(listOf("f2", "f1", "b2", "b1", "a2", "a1", "old"), ids("/friction/notes"))
        val rows = get<List<Map<String, Any?>>>("/friction/notes")
        val a1 = rows.single { it["id"] == "a1" }
        assertEquals(listOf("id", "repo", "issue", "run_id", "process_instance_id", "noted_at", "source", "kind", "summary", "evidence",
            "severity", "login", "signature", "group_id", "grouped_by", "jev_call_id", "grouped_login", "jev_confidence", "two_things", "two_things_call_id"), a1.keys.toList())
        assertEquals(FrictionKey.of("summary a1", "evidence a1"), a1["signature"])
        assertEquals(listOf(null, null, null, null, null), listOf(a1["group_id"], a1["grouped_by"], a1["jev_call_id"], a1["grouped_login"], a1["jev_confidence"]),
            "ungrouped until the grouper runs")
        assertEquals(listOf("acme/a", 7, "run-a1", "root-a1", "as-you-go", "bug", "summary a1", "evidence a1", "high", null),
            listOf(a1["repo"], a1["issue"], a1["run_id"], a1["process_instance_id"], a1["source"], a1["kind"], a1["summary"], a1["evidence"],
                a1["severity"], a1["login"]))
        val at = a1["noted_at"] as String
        assertTrue(at.endsWith("Z"), at)
        assertTrue(OffsetDateTime.parse(at).isEqual(day1), at)
        val f1 = rows.single { it["id"] == "f1" }
        assertEquals(listOf(null, null, null, "first-mate", "lundmatt1"), listOf(f1["repo"], f1["issue"], f1["run_id"], f1["source"], f1["login"]))
    }

    @Test
    fun `since, repo, kind and source filter, and limit defaults to 100 and is capped at 500`() {
        assertEquals(listOf("f2", "f1", "b2", "b1", "a2", "a1"), ids("/friction/notes?since=${now.minusDays(7).toInstant()}"))
        assertEquals(listOf("f2", "f1", "b2"), ids("/friction/notes?since=${day2.toLocalDate()}"), "a date is its UTC midnight")
        assertEquals(listOf("b2", "b1"), ids("/friction/notes?repo=acme/b"))
        assertEquals(listOf("b2", "a1", "old"), ids("/friction/notes?kind=bug"))
        assertEquals(listOf("b2", "a2"), ids("/friction/notes?source=retro"))
        assertEquals(listOf("f2"), ids("/friction/notes?repo=acme/a&source=first-mate"))
        assertEquals(listOf("f2", "f1"), ids("/friction/notes?limit=2"))
        assertEquals(7, ids("/friction/notes?limit=100000").size, "over 500 is capped, not refused")

        jdbc.sql("DELETE FROM engine.friction_note").update()
        (1..520).forEach { notes.insert(note("n$it", now.minusMinutes(it.toLong()))) }
        assertEquals(100, ids("/friction/notes").size, "100 by default")
        assertEquals(500, ids("/friction/notes?limit=501").size, "capped at 500")
        assertEquals("n1", ids("/friction/notes?limit=501").first())

        mvc.get("/friction/notes?limit=0").andExpect { status { isBadRequest() } }
        mvc.get("/friction/notes?kind=nope").andExpect { status { isBadRequest() } }
        mvc.get("/friction/notes?source=nope").andExpect { status { isBadRequest() } }
        mvc.get("/friction/notes?since=yesterday").andExpect { status { isBadRequest() } }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `the summary counts the window by day, kind, source, severity and repo`() {
        val s = get<Map<String, Any?>>("/friction/summary")
        assertEquals(setOf("from", "to", "total", "per_day", "by_kind", "by_source", "by_severity", "by_repo", "groups", "ungrouped"), s.keys)
        assertEquals(6, s["total"], "the 40-day-old note is outside the default 7 days")
        assertEquals(0, s["groups"]); assertEquals(6, s["ungrouped"])
        assertTrue((s["from"] as String).endsWith("Z"))
        val perDay = (s["per_day"] as List<Map<String, Any?>>).associate { it["day"] to it["count"] }
        assertEquals(8, perDay.size, "every UTC day from 7 days ago to today")
        assertEquals(now.minusDays(7).toLocalDate().toString(), perDay.keys.first())
        assertEquals(now.toLocalDate().toString(), perDay.keys.last())
        assertEquals(3, perDay[day1.toLocalDate().toString()])
        assertEquals(3, perDay[day2.toLocalDate().toString()])
        assertEquals(0, perDay[now.minusDays(2).toLocalDate().toString()], "a day with no notes is 0")
        assertEquals(6, perDay.values.sumOf { it as Int })
        assertEquals(mapOf("bug" to 2, "design" to 1, "docs" to 1, "tooling" to 1, "other" to 1), s["by_kind"])
        assertEquals(mapOf("as-you-go" to 2, "retro" to 2, "first-mate" to 2), s["by_source"])
        assertEquals(mapOf("low" to 1, "medium" to 1, "high" to 2, "unset" to 2), s["by_severity"])
        assertEquals(mapOf("acme/a" to 3, "acme/b" to 2, "none" to 1), s["by_repo"])

        val b = get<Map<String, Any?>>("/friction/summary?repo=acme/b&since=${now.minusDays(30).toInstant()}")
        assertEquals(2, b["total"])
        assertEquals(31, (b["per_day"] as List<*>).size)
        assertEquals(mapOf("acme/b" to 2), b["by_repo"])
        assertEquals(mapOf("as-you-go" to 1, "retro" to 1, "first-mate" to 0), b["by_source"], "every source, zeros included")

        val all = get<Map<String, Any?>>("/friction/summary?since=${now.minusDays(90).toInstant()}")
        assertEquals(7, all["total"])
        mvc.get("/friction/summary?since=nope").andExpect { status { isBadRequest() } }
    }

    private fun stored(id: String) = notes.list(FrictionFilter(limit = 1000)).single { it.id == id }

    /** A `friction-group` call row whose `group` answer is [choice] at [confidence], as the real decider stores it; null for no answer. */
    private fun call(choice: String?, confidence: Double = 0.9, useCase: String = FrictionGroupCheck.USE_CASE): String {
        val response = choice?.let { """{"model":"jev-1.13.0","answers":{"group":{"type":"choice","choice":"$it","probabilities":{"$it":$confidence},"confidence":$confidence}}}""" }
        return calls.record(useCase, JevContext(null, "acme/a", 7), "jev", "jev-1.13.0", "{}", response, if (response == null) "no answer" else null, 1, 0.8)
    }
    private fun outcome(call: String) = calls.get(call)!!.outcome
    private fun signatureGroup(key: String): Long? =
        jdbc.sql("SELECT group_id FROM engine.friction_signature WHERE signature = :s").param("s", key).query(Long::class.javaObjectType).optional().orElse(null)
    private fun from(address: String) = RequestPostProcessor { it.remoteAddr = address; it }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `the groups come newest activity first with their counts, kind, repos, severity and members, filtered the same way`() {
        val g1 = groups.found(stored("a1"), "new", null, day1)
        groups.join("b2", g1, "jev", "call-x"); groups.join("old", g1, "exact", null)
        // b1 founds g2, not a2: a1 and a2 share a key (`evidence a#` once the digit is normalised), and a key points at one group.
        val g2 = groups.found(stored("b1"), "new", "call-y", day1.plusHours(2))
        groups.join("a2", g2, "jev", null)
        // f1 and f2 stay ungrouped.
        fun groupIds(url: String) = get<List<Map<String, Any?>>>(url).map { (it["id"] as Number).toLong() }
        fun counts(url: String) = get<List<Map<String, Any?>>>(url).map { (it["id"] as Number).toLong() to (it["count"] as Int to it["total"] as Int) }

        val rows = get<List<Map<String, Any?>>>("/friction/groups")
        assertEquals(listOf(g1, g2), rows.map { (it["id"] as Number).toLong() }, "g1's newest member (b2) is newer than g2's (b1)")
        val first = rows[0]
        assertEquals(listOf("id", "title", "count", "total", "newest", "oldest", "kind", "repos", "severity", "bug", "fix", "state", "members"), first.keys.toList())
        assertEquals(listOf(null, "open"), listOf(first["fix"], first["state"]), "an unlinked group is open")
        assertEquals(listOf("summary a1", 3, 3, "bug", listOf("acme/b", "acme/a"), "high"),
            listOf(first["title"], first["count"], first["total"], first["kind"], first["repos"], first["severity"]))
        assertTrue(OffsetDateTime.parse(first["newest"] as String).isEqual(day2))
        assertTrue(OffsetDateTime.parse(first["oldest"] as String).isEqual(now.minusDays(40)))
        val members = first["members"] as List<Map<String, Any?>>
        assertEquals(listOf("b2", "a1", "old"), members.map { it["id"] }, "newest first, as /friction/notes rows")
        assertEquals(listOf("id", "repo", "issue", "run_id", "process_instance_id", "noted_at", "source", "kind", "summary", "evidence",
            "severity", "login", "signature", "group_id", "grouped_by", "jev_call_id", "grouped_login", "jev_confidence", "two_things", "two_things_call_id"), members[0].keys.toList())
        assertEquals(listOf(g1, "jev", "call-x", FrictionKey.of("summary b2", "evidence b2")),
            listOf((members[0]["group_id"] as Number).toLong(), members[0]["grouped_by"], members[0]["jev_call_id"], members[0]["signature"]))
        assertEquals(listOf("new", null), listOf(members[1]["grouped_by"], members[1]["jev_call_id"]))
        val second = rows[1]
        assertEquals(listOf(2, 2, "mixed", "medium"), listOf(second["count"], second["total"], second["kind"], second["severity"]), "a tie of kinds is mixed")

        assertEquals(listOf(g1 to (2 to 3), g2 to (2 to 2)), counts("/friction/groups?since=${now.minusDays(7).toInstant()}"), "the 40-day-old member is out of the count, in the total")
        assertEquals(listOf(g2 to (1 to 2)), counts("/friction/groups?kind=tooling"), "a group shows when one member passes, with the count that pass")
        assertEquals(listOf(g1, g2), groupIds("/friction/groups?source=retro"))
        assertEquals(listOf(g2, g1), groupIds("/friction/groups?repo=acme/a"), "g2's passing member (a2) is newer than g1's (a1)")
        assertEquals(listOf(g1), groupIds("/friction/groups?limit=1"), "limit counts groups")
        assertEquals(emptyList<Long>(), groupIds("/friction/groups?kind=design"), "an ungrouped note is in no group")
        mvc.get("/friction/groups?limit=0").andExpect { status { isBadRequest() } }
        mvc.get("/friction/groups?kind=nope").andExpect { status { isBadRequest() } }
        mvc.get("/friction/groups?source=nope").andExpect { status { isBadRequest() } }
        mvc.get("/friction/groups?since=yesterday").andExpect { status { isBadRequest() } }

        val s = get<Map<String, Any?>>("/friction/summary")
        assertEquals(2, s["groups"], "the window's distinct groups")
        assertEquals(2, s["ungrouped"], "f1 and f2 wait for the grouper")
        assertEquals(6, s["total"], "the totals still count raw notes")
        val b = get<Map<String, Any?>>("/friction/summary?repo=acme/b")
        assertEquals(2, b["groups"]); assertEquals(0, b["ungrouped"])
    }

    @Test
    fun `a summary since more than 366 days ago is refused, so per_day stays bounded`() {
        mvc.get("/friction/summary?since=0001-01-01").andExpect { status { isBadRequest() } }
        mvc.get("/friction/summary?since=-4000-01-01T00:00:00Z").andExpect { status { isBadRequest() } }
        mvc.get("/friction/summary?since=${now.minusDays(400).toInstant()}").andExpect { status { isBadRequest() } }
        val year = get<Map<String, Any?>>("/friction/summary?since=${now.minusDays(365).toInstant()}")
        assertTrue((year["per_day"] as List<*>).size <= 367)
    }

    @Test
    fun `an empty window is a zero total with every day at 0`() {
        jdbc.sql("DELETE FROM engine.friction_note").update()
        val s = get<Map<String, Any?>>("/friction/summary?since=${now.minusDays(1).toInstant()}")
        assertEquals(0, s["total"])
        assertTrue((s["per_day"] as List<*>).all { (it as Map<*, *>)["count"] == 0 })
        assertEquals(emptyMap<String, Any?>(), s["by_repo"])
        assertEquals(mapOf("bug" to 0, "design" to 0, "docs" to 0, "tooling" to 0, "other" to 0), s["by_kind"])
        assertNull(s["by_severity"].let { (it as Map<*, *>).values.firstOrNull { v -> v != 0 } })
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `jev_confidence is the group answer's confidence of the note's call, read once per call, null without one`() {
        val c1 = call("g1", 0.91)
        val none = call(null)
        val other = call("g1", 0.5, useCase = "intake-duplicate")
        val g1 = groups.found(stored("a1"), "new", null, day1)
        groups.join("b2", g1, "jev", c1); groups.join("old", g1, "jev", c1); groups.join("b1", g1, "jev", none); groups.join("a2", g1, "exact", other)
        val rows = get<List<Map<String, Any?>>>("/friction/notes").associateBy { it["id"] }
        assertEquals(0.91, rows.getValue("b2")["jev_confidence"])
        assertEquals(0.91, rows.getValue("old")["jev_confidence"], "two notes of one call read the same confidence")
        assertNull(rows.getValue("b1")["jev_confidence"], "a call with no answer")
        assertEquals(0.5, rows.getValue("a2")["jev_confidence"], "whatever call the note names: the reader does not judge the use case")
        assertNull(rows.getValue("a1")["jev_confidence"], "no call")
        groups.join("f1", g1, "jev", "no-such-call")
        assertNull(get<List<Map<String, Any?>>>("/friction/notes").single { it["id"] == "f1" }["jev_confidence"], "a call that is gone")
        val members = (get<List<Map<String, Any?>>>("/friction/groups").single()["members"] as List<Map<String, Any?>>).associateBy { it["id"] }
        assertEquals(0.91, members.getValue("b2")["jev_confidence"], "the members of /friction/groups carry it too")
        assertNull(members.getValue("a1")["grouped_login"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `a pull-out founds a group with the note alone as user, moves its key unless shared, and records the jev call's miss`() {
        // a1 founds g1; a2 (sharing a1's key: digits normalise) joins as exact; b2 joins by jev at 0.91; old by jev at 0.85 with its
        // own key. Not b1: it shares b2's key (`evidence b#`), so the key would be inserted twice and b2's would be shared.
        val g1 = groups.found(stored("a1"), "new", null, day1)
        groups.join("a2", g1, "exact", null)
        val cb2 = call("g$g1", 0.91); groups.join("b2", g1, "jev", cb2); groups.addSignature(stored("b2"), g1, day2)
        val cOld = call("g$g1", 0.85); groups.join("old", g1, "jev", cOld); groups.addSignature(stored("old"), g1, day1)
        val keyA = FrictionKey.of("summary a1", "evidence a1")
        val keyB2 = FrictionKey.of("summary b2", "evidence b2")

        val pulled = json.readValue<Map<String, Any?>>(mvc.patch("/friction/notes/b2") {
            contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"group":null}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        val g2 = (pulled["group_id"] as Number).toLong()
        assertTrue(g2 != g1)
        assertEquals(listOf("b2", "user", "lundmatt1", cb2, 0.91), listOf(pulled["id"], pulled["grouped_by"], pulled["grouped_login"], pulled["jev_call_id"], pulled["jev_confidence"]),
            "the note keeps its call; the row says who moved it")
        assertEquals("summary b2", groups.get(g2)!!.title)
        assertEquals(listOf("b2"), groups.get(g2)!!.members.map { it.id })
        assertEquals(g2, signatureGroup(keyB2), "b2's key, shared by no other member of g1, goes with it")
        assertEquals("joined g$g1: pulled out to g$g2 by @lundmatt1", outcome(cb2))
        assertNull(outcome(cOld), "the other jev call is untouched")

        // a2 shares a1's key: pulling a2 out leaves the key with g1.
        mvc.patch("/friction/notes/a2") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"group":null}""" }.andExpect { status { isOk() } }
        val g3 = stored("a2").groupId!!
        assertTrue(g3 != g1 && g3 != g2)
        assertEquals(g1, signatureGroup(keyA), "an exact key cannot point two ways: it stays with the old group")
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM engine.friction_signature WHERE group_id = :g").param("g", g3).query(Int::class.javaObjectType).single())
        assertEquals("new" to null, stored("a1").let { it.groupedBy to it.groupedLogin }, "a1 is unchanged")
        assertEquals(listOf("a1", "old"), groups.get(g1)!!.members.map { it.id })

        val rows = get<List<Map<String, Any?>>>("/friction/groups")
        assertEquals(setOf(g1, g2, g3), rows.map { (it["id"] as Number).toLong() }.toSet())
        val s = get<Map<String, Any?>>("/friction/summary")
        assertEquals(3, s["groups"])

        mvc.patch("/friction/notes/b2") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"group":null,"by":"lundmatt1"}""" }.andExpect { status { isBadRequest(); reason("by is not a field: the bearer token names the login") } }
        mvc.patch("/friction/notes/b2") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"group":$g1}""" }
            .andExpect { status { isBadRequest() } }
        mvc.patch("/friction/notes/nope") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"group":null}""" }.andExpect { status { isNotFound() } }
        mvc.patch("/friction/notes/f1") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"group":null}""" }
            .andExpect { status { isConflict() } }
        mvc.patch("/friction/notes/b2") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"group":null}""" }
            .andExpect { status { isConflict() } }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `a merge moves every note and signature, deletes the group, keeps the target's title, and records the founder's miss and the target's kept joins`() {
        // b2's key is not added to g1: b1 shares it (`evidence b#`) and founds g2 with it below, and a key points at one group.
        val g1 = groups.found(stored("a1"), "new", null, day1)
        val c1 = call("g$g1", 0.9); groups.join("b2", g1, "jev", c1)
        val judged = call("g$g1", 0.88); groups.join("old", g1, "jev", judged); calls.recordOutcome(judged, "joined g$g1: kept")
        val founder = call("new", 0.8)
        val g2 = groups.found(stored("b1"), "new", founder, day1.plusHours(2))
        val c2 = call("g$g2", 0.83); groups.join("a2", g2, "jev", c2)
        groups.join("f2", g2, "fallback", call(null))

        val merged = json.readValue<Map<String, Any?>>(mvc.post("/friction/groups/$g2/merge") {
            contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"into":$g1}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(g1, (merged["id"] as Number).toLong())
        assertEquals("summary a1", merged["title"], "the target keeps its title")
        assertEquals(6, merged["total"])
        assertEquals(listOf("f2", "b2", "b1", "a2", "a1", "old"), (merged["members"] as List<Map<String, Any?>>).map { it["id"] })
        assertNull(groups.get(g2), "the merged group is deleted")
        assertEquals(g1, signatureGroup(FrictionKey.of("summary b1", "evidence b1")), "its signatures move")
        assertEquals("new" to founder, stored("b1").let { it.groupedBy to it.jevCallId }, "moved notes keep their grouped_by and call")
        assertEquals("jev" to c2, stored("a2").let { it.groupedBy to it.jevCallId })
        assertEquals("new g$g2: merged into g$g1 by @lundmatt1", outcome(founder))
        assertEquals("joined g$g1: kept", outcome(c1), "the receiving group's jev joins are confirmed")
        assertEquals("joined g$g1: kept", outcome(judged), "one already judged is not rewritten")
        assertNull(outcome(c2), "the moved jev note's call is not the receiving group's")
        assertEquals(listOf(g1), get<List<Map<String, Any?>>>("/friction/groups").map { (it["id"] as Number).toLong() })

        mvc.post("/friction/groups/$g1/merge") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"into":$g1}""" }.andExpect { status { isConflict() } }
        mvc.post("/friction/groups/$g1/merge") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"into":999999}""" }.andExpect { status { isNotFound() } }
        mvc.post("/friction/groups/999999/merge") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"into":$g1}""" }.andExpect { status { isNotFound() } }
        mvc.post("/friction/groups/$g1/merge") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"into":$g1,"by":"lundmatt1"}""" }.andExpect { status { isBadRequest(); reason("by is not a field: the bearer token names the login") } }
        mvc.post("/friction/groups/$g1/merge") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{}""" }.andExpect { status { isBadRequest() } }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `groups carry fix and state, and state filters, unsolved being every group not fixed`() {
        val open = groups.found(stored("a1"), "new", null, day1)
        val filed = groups.found(stored("b1"), "new", null, day1)
        val fixed = groups.found(stored("f1"), "new", null, day1)
        val recurred = groups.found(stored("old"), "new", null, day1)
        groups.join("b2", recurred, "jev", null)
        groupFixes.setFix(filed, "acme/a", 1300, FrictionFix.FILED, null)
        groupFixes.setFix(fixed, "acme/a", 1301, FrictionFix.FIXED, day2.plusDays(1))
        groupFixes.setFix(recurred, "acme/b", 1302, FrictionFix.FIXED, day1)
        fun states(url: String) = get<List<Map<String, Any?>>>(url).associate { (it["id"] as Number).toLong() to it["state"] }
        assertEquals(mapOf(open to "open", filed to "fix-filed", fixed to "fixed", recurred to "recurred"), states("/friction/groups"), "no state: every group")
        val row = get<List<Map<String, Any?>>>("/friction/groups").single { (it["id"] as Number).toLong() == recurred }
        val fix = row["fix"] as Map<String, Any?>
        assertEquals(listOf("repo", "issue", "state", "recurredAt"), fix.keys.toList())
        assertEquals(listOf("acme/b", 1302, "fixed"), listOf(fix["repo"], fix["issue"], fix["state"]))
        assertTrue(OffsetDateTime.parse(fix["recurredAt"] as String).isEqual(day2), "the first note after the merge")
        assertEquals(setOf(fixed), states("/friction/groups?state=fixed").keys)
        assertEquals(setOf(filed), states("/friction/groups?state=fix-filed").keys)
        assertEquals(setOf(open, filed, recurred), states("/friction/groups?state=unsolved").keys)
        assertEquals(1, states("/friction/groups?state=unsolved&limit=1").size, "limit counts the groups that pass")
        mvc.get("/friction/groups?state=done").andExpect { status { isBadRequest() } }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `Fixed by sets and clears a group's link, with its 400s and 404`() {
        val g = groups.found(stored("a1"), "new", null, day1)
        fun patch(id: Long, body: String) = mvc.patch("/friction/groups/$id") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = body }
        val set = json.readValue<Map<String, Any?>>(patch(g, """{"fix":{"repo":"acme/a","issue":1234}}""")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(listOf(g, "fix-filed"), listOf((set["id"] as Number).toLong(), set["state"]))
        assertEquals(mapOf("repo" to "acme/a", "issue" to 1234, "state" to "fix-filed", "recurredAt" to null), set["fix"])
        assertEquals(1, (set["members"] as List<*>).size, "answered as a /friction/groups row")
        val cleared = json.readValue<Map<String, Any?>>(patch(g, """{"fix":null}""")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(listOf(null, "open"), listOf(cleared["fix"], cleared["state"]))
        patch(g, """{"fix":{"repo":"acme/a","issue":1},"by":"lundmatt1"}""").andExpect { status { isBadRequest(); reason("by is not a field: the bearer token names the login") } }
        patch(g, """{"fix":{"repo":"not a repo","issue":1}}""").andExpect { status { isBadRequest() } }
        patch(g, """{"fix":{"repo":"acme/a","issue":0}}""").andExpect { status { isBadRequest() } }
        patch(g, """{}""").andExpect { status { isBadRequest() } }
        patch(g, """{"fix":null,"x":1}""")
            .andExpect { status { isBadRequest(); reason(containsString("unknown field(s): x; only fix")) } }
        patch(999999, """{"fix":{"repo":"acme/a","issue":1}}""").andExpect { status { isNotFound() } }
        patch(999999, """{"fix":null}""").andExpect { status { isNotFound() } }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `a candidate fix by intake id links a group or a note's group as fix-pending, with its 400s, 404s and 409s`() {
        val g = groups.found(stored("a1"), "new", null, day1)
        fun post(url: String, body: String) = mvc.post(url) { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = body }
        val set = json.readValue<Map<String, Any?>>(post("/friction/groups/$g/fix-intake", """{"intake":"i-run"}""")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals("fix-pending", set["state"])
        assertEquals(mapOf("intake" to "i-run", "repo" to "acme/a", "issue" to null, "state" to "fix-pending"), set["fix"])
        val listed = get<List<Map<String, Any?>>>("/friction/groups?state=fix-pending").single()
        assertEquals(listOf(g, "fix-pending"), listOf((listed["id"] as Number).toLong(), listed["state"]))
        assertEquals(emptyList(), get<List<Map<String, Any?>>>("/friction/groups?state=fix-filed"), "pending is not fix-filed")
        val g2 = groups.found(stored("b1"), "new", null, day1)
        post("/friction/notes/b1/fix-intake", """{"intake":"i-run"}""").andExpect { status { isOk() } }
        assertEquals("i-run", groupFixes.pending(g2)!!.intake)
        post("/friction/groups/999999/fix-intake", """{"intake":"i-run"}""").andExpect { status { isNotFound() } }
        post("/friction/notes/nope/fix-intake", """{"intake":"i-run"}""").andExpect { status { isNotFound() } }
        post("/friction/notes/b2/fix-intake", """{"intake":"i-run"}""").andExpect { status { isConflict() } }
        post("/friction/groups/$g/fix-intake", """{"intake":"i-none"}""").andExpect { status { isNotFound() } }
        post("/friction/groups/$g/fix-intake", """{"intake":"i-end"}""").andExpect { status { isConflict() } }
        post("/friction/groups/$g/fix-intake", """{"intake":" "}""").andExpect { status { isBadRequest() } }
        post("/friction/groups/$g/fix-intake", """{"intake":"i-run","by":"lundmatt1"}""").andExpect { status { isBadRequest(); reason("by is not a field: the bearer token names the login") } }
        post("/friction/groups/$g/fix-intake", """{"intake":"i-run","x":1}""")
            .andExpect { status { isBadRequest(); reason(containsString("unknown field(s): x; only intake")) } }
        groupFixes.setFix(g, "acme/a", 1234, FrictionFix.FILED, null)
        post("/friction/groups/$g/fix-intake", """{"intake":"i-run"}""").andExpect { status { isConflict() } }
        assertEquals(1234L, groupFixes.fix(g)!!.issue)
    }

    @Test
    fun `a Smith sandbox may not pull out, merge or set a fix, and an unreachable sandbox provider is a 503`() {
        val g1 = groups.found(stored("a1"), "new", null, day1)
        groups.join("b2", g1, "jev", null)
        val g2 = groups.found(stored("b1"), "new", null, day1)
        sandbox.callers["10.9.0.7"] = SmithCaller("run-x", 7, "acme/a")
        mvc.patch("/friction/notes/b2") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"group":null}"""; with(from("10.9.0.7")) }
            .andExpect { status { isForbidden() } }
        mvc.post("/friction/groups/$g2/merge") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"into":$g1}"""; with(from("10.9.0.7")) }
            .andExpect { status { isForbidden() } }
        assertEquals(g1, stored("b2").groupId); assertNotNull(groups.get(g2))
        sandbox.runAtFails = true
        mvc.patch("/friction/notes/b2") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"group":null}"""; with(from("10.9.0.8")) }
            .andExpect { status { isServiceUnavailable() } }
        mvc.post("/friction/groups/$g2/merge") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"into":$g1}"""; with(from("10.9.0.8")) }
            .andExpect { status { isServiceUnavailable() } }
        mvc.patch("/friction/groups/$g1") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"fix":null}"""; with(from("10.9.0.8")) }
            .andExpect { status { isServiceUnavailable() } }
        sandbox.runAtFails = false
        mvc.patch("/friction/groups/$g1") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"fix":{"repo":"acme/a","issue":1}}"""; with(from("10.9.0.7")) }
            .andExpect { status { isForbidden() } }
        assertNull(groupFixes.fix(g1), "a Smith's link is not set")
        mvc.post("/friction/groups/$g2/merge") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"into":$g1}"""; with(from("10.9.0.8")) }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `POST friction fixes sync runs one pass and answers its report, refused to a Smith with 403 and 503 when the sandbox provider cannot be asked`() {
        @Suppress("UNCHECKED_CAST")
        val report = json.readValue<Any>(mvc.post("/friction/fixes/sync").andExpect { status { isOk() } }.andReturn().response.contentAsString) as Map<String, Any?>
        assertEquals(listOf("linked", "changed", "recurred", "skipped"), report.keys.toList())
        assertEquals(emptyList<Any>(), report["recurred"])
        sandbox.callers["10.9.0.7"] = SmithCaller("run-x", 7, "acme/a")
        mvc.post("/friction/fixes/sync") { with(from("10.9.0.7")) }
            .andExpect { status { isForbidden(); reason(containsString("a Smith sandbox (run run-x) may not run the friction fix sync")) } }
        sandbox.runAtFails = true
        mvc.post("/friction/fixes/sync") { with(from("10.9.0.8")) }.andExpect { status { isServiceUnavailable() } }
    }

    @Test
    fun `twoThings null clears the mark and writes its outcome, group null still pulls out, and a body with both keys or neither is 400`() {
        val g1 = groups.create("summary a1", now)
        listOf("a1", "a2", "b2").forEach { groups.join(it, g1, GroupedBy.JEV, null) }
        val lone = calls.record(FrictionGroupCheck.TWO_THINGS_USE_CASE, com.codefrak.engine.jev.JevContext(null, "acme/a", 7), "fake", "jev-1.13.0", "{}",
            """{"answers":{"two-things":{"type":"noul","noul":0.86}}}""", null, 1, 0.8)
        groups.mark("a1", 0.86, lone)
        val row = get<List<Map<String, Any?>>>("/friction/notes").single { it["id"] == "a1" }
        assertEquals(0.86 to lone, row["two_things"] to row["two_things_call_id"])
        assertEquals(mapOf("repo" to null, "issue" to null, "state" to null).keys, (get<List<Map<String, Any?>>>("/friction/groups").single()["bug"] as? Map<*, *>)?.keys ?: mapOf("repo" to null, "issue" to null, "state" to null).keys)
        val cleared = json.readValue<Map<String, Any?>>(mvc.patch("/friction/notes/a1") {
            contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"twoThings":null}""" }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(null to lone, cleared["two_things"] to cleared["two_things_call_id"], "the mark goes, the call stays named")
        assertEquals(g1, (cleared["group_id"] as Number).toLong(), "its group is untouched")
        assertEquals("two things: cleared by @lundmatt1", calls.get(lone)!!.outcome)
        mvc.patch("/friction/notes/a1") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"twoThings":null}""" }
            .andExpect { status { isConflict() } }
        mvc.patch("/friction/notes/nope") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"twoThings":null}""" }
            .andExpect { status { isNotFound() } }
        mvc.patch("/friction/notes/a2") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"twoThings":0.9}""" }
            .andExpect { status { isBadRequest(); reason(containsString("twoThings is null")) } }
        mvc.patch("/friction/notes/a2") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"twoThings":null,"by":"lundmatt1"}""" }
            .andExpect { status { isBadRequest(); reason("by is not a field: the bearer token names the login") } }
        mvc.patch("/friction/notes/a2") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"group":null,"twoThings":null}""" }
            .andExpect { status { isBadRequest(); reason(containsString("one of {\"group\": null}")) } }
        mvc.patch("/friction/notes/a2") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{}""" }
            .andExpect { status { isBadRequest(); reason(containsString("{\"twoThings\": null}")) } }
        mvc.patch("/friction/notes/a2") { contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"nope":null}""" }
            .andExpect { status { isBadRequest(); reason(containsString("unknown field(s): nope")) } }
        // A pull-out as before, with a bug on the group shown in the row.
        groupBugs.claimBug(g1, "acme/a"); groupBugs.bugFiled(g1, 44)
        assertEquals(mapOf("repo" to "acme/a", "issue" to 44, "state" to "open"), get<List<Map<String, Any?>>>("/friction/groups").single()["bug"])
        val pulled = json.readValue<Map<String, Any?>>(mvc.patch("/friction/notes/a2") {
            contentType = MediaType.APPLICATION_JSON; requestAttr(TOKEN_LOGIN_ATTRIBUTE, "lundmatt1"); content = """{"group":null}""" }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertTrue((pulled["group_id"] as Number).toLong() != g1)
        assertEquals("user", pulled["grouped_by"])
    }
}
