package com.codefrak.engine.doctor

import com.codefrak.engine.ProcessFakes
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** GET /doctor/history (engine/README.md, "Doctor"): the JSON a ticket's doctor runs read as. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class DoctorControllerTest {
    @Autowired lateinit var controller: DoctorController
    @Autowired lateinit var history: DoctorHistory
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var sightings: DoctorSightings

    private val repo = "acme/doctor-history"
    private fun json(url: String): JsonNode = mapper.valueToTree(controller.history(url))

    @Test
    fun `a ticket's runs, newest first, with their fields and types`() {
        val since = OffsetDateTime.of(2026, 9, 27, 10, 0, 0, 0, ZoneOffset.UTC)
        history.start("run-a", repo, 7, "root-1", "chore:task-open-pr@none,incident:inc-1", since)
        history.finish("run-a", "A Docker blip.", "retry", "inc-1", "applied", null, "smith-run-a")
        history.start("run-b", repo, 7, "root-1", "smith-run:event-smith-exited@none", since.plusMinutes(5))
        history.start("run-c", repo, 8, "root-2", "chore:task-open-pr@none", since)

        val body = json("https://github.com/$repo/issues/7")
        assertEquals("https://github.com/$repo/issues/7", body["issueUrl"].asString())
        val runs = body["runs"]
        assertEquals(2, runs.size())
        assertEquals(listOf("run-b", "run-a"), runs.toList().map { it["id"].asString() }, "newest first")

        val done = runs[1]
        assertEquals(setOf("id", "repo", "issue", "rootInstanceId", "position", "stuckSince", "startedAt", "endedAt", "diagnosis",
            "remedy", "target", "outcome", "detail", "smithRunId", "sighting"), done.propertyNames().toSet())
        assertEquals(repo, done["repo"].asString())
        assertTrue(done["issue"].isNumber)
        assertEquals(7, done["issue"].asInt())
        assertEquals("root-1", done["rootInstanceId"].asString())
        assertEquals(listOf("chore:task-open-pr@none", "incident:inc-1"), done["position"].toList().map { it.asString() })
        assertTrue(done["stuckSince"].isString && done["startedAt"].isString && done["endedAt"].isString)
        assertEquals("A Docker blip.", done["diagnosis"].asString())
        assertEquals("retry", done["remedy"].asString())
        assertEquals("inc-1", done["target"].asString())
        assertEquals("applied", done["outcome"].asString())
        assertTrue(done["detail"].isNull)
        assertEquals("smith-run-a", done["smithRunId"].asString(), "the smith-run whose log is the run's transcript")

        val going = runs[0]
        assertEquals("running", going["outcome"].asString())
        assertTrue(done["sighting"].isNull, "a run with no sighting row")
        listOf("endedAt", "diagnosis", "remedy", "target", "detail", "smithRunId", "sighting").forEach { assertTrue(going[it].isNull, "$it is null while running") }
    }

    @Test
    fun `a run's sighting, on GET doctor history and GET doctor runs, counts the tickets and runs its signature was seen on`() {
        val r = "acme/doctor-sightings"
        val since = OffsetDateTime.of(2026, 9, 28, 10, 0, 0, 0, ZoneOffset.UTC)
        val signature = Signature.of("engine", listOf("smith-run:task-remove-sandbox@none"),
            listOf(IncidentShape("task-remove-sandbox", "failedJob", "No such container: 9c2e41f0ab77")))
        listOf(Triple("sight-a", 1L, "root-s1"), Triple("sight-b", 1L, "root-s1"), Triple("sight-c", 2L, "root-s2")).forEach { (id, issue, root) ->
            history.start(id, r, issue, root, "smith-run:task-remove-sandbox@none,incident:$id", since)
            history.finish(id, "x", null, null, "none", null)
            sightings.record(NewSighting(id, null, r, issue, root, "unfixable", null, "engine", "No such container", signature))
        }
        history.start("sight-d", r, 1, "root-s1", "chore:task-open-pr@none", since.plusMinutes(1))

        val runs = json("https://github.com/$r/issues/1")["runs"].toList()
        assertEquals(listOf("sight-d", "sight-a", "sight-b").toSet(), runs.map { it["id"].asString() }.toSet())
        assertTrue(runs.first { it["id"].asString() == "sight-d" }["sighting"].isNull, "a run still going has none")
        val seen = runs.first { it["id"].asString() == "sight-a" }["sighting"]
        assertEquals(setOf("id", "kind", "where", "bug", "tickets", "runs", "signature"), seen.propertyNames().toSet())
        assertTrue(seen["id"].isNumber)
        assertEquals("unfixable", seen["kind"].asString())
        assertEquals("engine", seen["where"].asString())
        assertTrue(seen["bug"].isNull, "nothing is filed yet")
        assertEquals(2, seen["tickets"].asInt(), "the signature's tickets, while it has no bug")
        assertEquals(3, seen["runs"].asInt())
        assertEquals(signature.hash, seen["signature"].asString(), "the signature, on a bug or not, so a reader counts distinct defects")

        val across = (mapper.valueToTree<JsonNode>(controller.runs("2026-09-28T00:00:00Z")))["runs"].toList().filter { it["repo"].asString() == r }
        assertEquals(seen, across.first { it["id"].asString() == "sight-a" }["sighting"], "the same sighting on GET /doctor/runs")
    }

    @Test
    fun `a ticket with no runs is an empty list, and a URL that is not an issue is a 400`() {
        assertEquals(0, json("https://github.com/$repo/issues/999")["runs"].size())
        listOf("acme/doctor-history#7", "https://github.com/$repo/pull/7", "https://example.com/$repo/issues/7").forEach {
            val refused = assertFailsWith<ResponseStatusException>(it) { controller.history(it) }
            assertEquals(HttpStatus.BAD_REQUEST, refused.statusCode)
        }
    }

    @Test
    fun `without an issueUrl, one row per ticket with runs, newest last run first`() {
        val r = "acme/doctor-listing"
        val since = OffsetDateTime.of(2026, 9, 27, 12, 0, 0, 0, ZoneOffset.UTC)
        history.start("list-a", r, 1, "root-l1", "chore:task-open-pr@none", since)
        history.finish("list-a", "d", "retry", "inc-9", "applied", null)
        history.start("list-b", r, 1, "root-l1", "chore:task-open-pr@none", since.plusMinutes(1))
        history.finish("list-b", "d", "cancel", null, "failed", "no")
        history.start("list-c", r, 2, "root-l2", "chore:task-open-pr@none", since.plusMinutes(2))

        val body: JsonNode = mapper.valueToTree(controller.get(null))
        val mine = body["tickets"].toList().filter { it["repo"].asString() == r }
        assertEquals(listOf(2, 1), mine.map { it["issue"].asInt() }, "newest last run first")
        assertEquals(setOf("repo", "issue", "runs", "lastStartedAt", "lastOutcome"), mine[0].propertyNames().toSet())
        assertEquals(1, mine[0]["runs"].asInt())
        assertEquals("running", mine[0]["lastOutcome"].asString())
        assertEquals(2, mine[1]["runs"].asInt())
        assertEquals("failed", mine[1]["lastOutcome"].asString())
        assertEquals(listOf("list-b", "list-a"), (controller.get("https://github.com/$r/issues/1") as DoctorRuns).runs.map { it.id })
    }

    /** Moves a run's `started_at` (the table defaults it to now). */
    private fun startedAt(id: String, at: OffsetDateTime) =
        jdbc.sql("UPDATE engine.doctor_run SET started_at = :s WHERE id = :id").param("s", at).param("id", id).update()

    @Test
    fun `GET doctor runs answers the runs across tickets since a time, newest first`() {
        val r1 = "acme/doctor-runs-a"
        val r2 = "acme/doctor-runs-b"
        val stuck = OffsetDateTime.of(2026, 9, 1, 8, 0, 0, 0, ZoneOffset.UTC)
        val base = OffsetDateTime.of(2026, 9, 10, 12, 0, 0, 0, ZoneOffset.UTC)
        history.start("runs-a", r1, 1, "root-r1", "chore:task-open-pr@none,incident:inc-7", stuck)
        history.finish("runs-a", "A blip.", "retry", "inc-7", "applied", null)
        history.start("runs-b", r2, 2, "root-r2", "chore:task-open-pr@none", stuck)
        history.start("runs-c", r1, 1, "root-r1", "chore:task-open-pr@none,incident:inc-7", stuck)
        history.start("runs-old", r2, 3, "root-r3", "chore:task-open-pr@none", stuck)
        startedAt("runs-a", base)
        startedAt("runs-b", base.plusHours(1))
        startedAt("runs-c", base.plusHours(2))
        startedAt("runs-old", base.minusDays(2))

        val body: JsonNode = mapper.valueToTree(controller.runs("2026-09-09T00:00:00Z"))
        assertEquals(setOf("since", "runs"), body.propertyNames().toSet())
        assertTrue(body["since"].isString)
        val mine = body["runs"].toList().filter { it["repo"].asString() in setOf(r1, r2) }
        assertEquals(listOf("runs-c", "runs-b", "runs-a"), mine.map { it["id"].asString() }, "newest first, across tickets, the older run out")
        assertEquals(setOf("id", "repo", "issue", "rootInstanceId", "position", "stuckSince", "startedAt", "endedAt", "diagnosis",
            "remedy", "target", "outcome", "detail", "smithRunId", "sighting"), mine[2].propertyNames().toSet(), "the same run fields as /doctor/history")
        assertEquals(listOf("chore:task-open-pr@none", "incident:inc-7"), mine[2]["position"].toList().map { it.asString() })
        assertEquals("applied", mine[2]["outcome"].asString())

        val later = controller.runs("2026-09-10T13:00:00+00:00").runs.filter { it.repo in setOf(r1, r2) }.map { it.id }
        assertEquals(listOf("runs-c", "runs-b"), later, "an offset date-time works as well")
    }

    @Test
    fun `GET doctor runs defaults to the last 7 days, and a since that does not parse is a 400`() {
        val r = "acme/doctor-runs-default"
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        history.start("runs-recent", r, 1, "root-d1", "chore:task-open-pr@none", now.minusDays(1))
        history.start("runs-eight", r, 2, "root-d2", "chore:task-open-pr@none", now.minusDays(9))
        startedAt("runs-recent", now.minusDays(1))
        startedAt("runs-eight", now.minusDays(8))

        val answer = controller.runs(null)
        assertTrue(answer.since.isBefore(now.minusDays(7).plusMinutes(1)) && answer.since.isAfter(now.minusDays(7).minusMinutes(1)))
        assertEquals(listOf("runs-recent"), answer.runs.filter { it.repo == r }.map { it.id })
        listOf("yesterday", "2026-09-21", "1695000000").forEach {
            val refused = assertFailsWith<ResponseStatusException>(it) { controller.runs(it) }
            assertEquals(HttpStatus.BAD_REQUEST, refused.statusCode)
        }
    }

    @Test
    fun `the tick is off in tests unless a test turns it on`() {
        assertEquals(0, context.getBeansOfType(DoctorTick::class.java).size, "src/test/resources/config sets engine.doctor.enabled=false")
    }
}
