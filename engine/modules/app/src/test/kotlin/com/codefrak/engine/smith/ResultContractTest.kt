package com.codefrak.engine.smith

import com.codefrak.engine.feature.AcceptResult
import com.codefrak.engine.feature.DesignResult
import com.codefrak.engine.intake.DraftTicket
import com.codefrak.engine.intake.RefineResult
import com.codefrak.engine.proposal.ProposeResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** smith/schemas and the pass-along fields (engine/README.md, "Smith results"). */
class ResultContractTest {
    private val modes = listOf("refine", "work", "reproduce", "verify", "design", "replan", "accept", "review", "security", "propose", "fix", "diagnose", "doctor", "job", "check")

    @Test
    fun `every mode that writes result json has a schema in the jar`() {
        modes.forEach { assertTrue(ResultSchemas.of(it) != null, "result-schemas/$it.schema.json") }
    }

    @Test
    fun `object-valued evidence passes the schema and parses as a fenced JSON block (#235)`() {
        val bytes = """{"outcome":"understood","rootCause":"x","testable":true,"evidence":{"logsOrCode":["a.kt:1"],"ticketMatch":"stuck","confidence":"high"}}""".toByteArray()
        assertEquals(emptyList(), ResultSchemas.problems("reproduce", bytes))
        val result = ReproduceResult.parse(bytes)
        assertEquals(emptyList(), result.problems())
        val evidence = result.evidence!!
        assertTrue(evidence.startsWith("```json\n{") && evidence.endsWith("}\n```"), evidence)
        assertTrue("\"logsOrCode\"" in evidence && "\"confidence\" : \"high\"" in evidence, evidence)
    }

    @Test
    fun `string pass-along fields read exactly as before`() {
        val result = WorkResult.parse("""{"outcome":"done","title":"t","summary":"Joined them.","addressed":[{"finding":"review-1-1","how":"fixed"}]}""".toByteArray())
        assertEquals("Joined them.", result.summary)
        assertEquals("fixed", result.addressed.single().how)
    }

    @Test
    fun `pass-along fields in other result types take any JSON`() {
        val work = """{"outcome":"done","title":"t","summary":["one","two"],"addressed":[{"finding":"review-1-1","how":{"file":"a.kt"}}]}""".toByteArray()
        assertEquals(emptyList(), ResultSchemas.problems("work", work))
        val parsed = WorkResult.parse(work)
        assertTrue(parsed.summary!!.startsWith("```json\n[") && "\"two\"" in parsed.summary!!, parsed.summary)
        assertTrue("\"file\" : \"a.kt\"" in parsed.addressed.single().how)

        val accept = """{"outcome":"accepted","summary":42,"criteria":[{"criterion":"it builds","met":true,"how":true}]}""".toByteArray()
        assertEquals(emptyList(), ResultSchemas.problems("accept", accept))
        assertEquals("42", AcceptResult.parse(accept).summary)
        assertEquals("- [x] it builds: true", AcceptResult.parse(accept).checklist())

        val refine = """{"outcome":"ask","question":{"which":["a","b"]}}""".toByteArray()
        assertEquals(emptyList(), ResultSchemas.problems("refine", refine))
        assertTrue(RefineResult.parse(refine).question!!.startsWith("```json"))

        val design = """{"outcome":"planned","plan":{"summary":{"what":"x"},"acceptance":["a",{"b":1}],"stages":[{"title":"one","detail":["x"]}]},"questions":[]}""".toByteArray()
        assertEquals(emptyList(), ResultSchemas.problems("design", design))
        assertEquals(emptyList(), DesignResult.parse(design).problems())
    }

    @Test
    fun `a withdrawn refine result passes with a reason, and fails without one or with a ticket, kind, repo or blockedBy`() {
        val ok = """{"outcome":"withdrawn","reason":"Drop it, #2685 already covers this"}"""
        assertEquals(emptyList(), ResultSchemas.problems("refine", ok.toByteArray()))
        assertEquals(emptyList(), RefineResult.parse(ok.toByteArray()).problems())
        assertEquals(listOf("outcome 'withdrawn' needs a reason"), RefineResult(outcome = "withdrawn").problems())
        val bad = RefineResult(outcome = "withdrawn", reason = "drop it", ticket = DraftTicket("t", "b", 2, "p3", 2), kind = "chore",
            repo = "acme/tickets", blockedBy = listOf("#1")).problems()
        assertEquals(4, bad.size, bad.toString())
        listOf("ticket", "kind", "repo", "blockedBy").forEach { f -> assertTrue(bad.any { it.endsWith("drop the $f") }, bad.toString()) }
    }

    @Test
    fun `an insufficient-permissions refine result passes the schema with or without a blocker, and a non-string blocker fails it`() {
        val ticket = """"kind":"chore","ticket":{"title":"t","body":"b","tier":5,"priority":"p3"},"reason":"Needs a mac signing secret.""""
        assertEquals(emptyList(), ResultSchemas.problems("refine", """{"outcome":"insufficient-permissions",$ticket}""".toByteArray()))
        assertEquals(emptyList(), ResultSchemas.problems("refine", """{"outcome":"insufficient-permissions",$ticket,"blocker":"Needs a mac signing secret"}""".toByteArray()))
        assertEquals(listOf("/blocker"), ResultSchemas.problems("refine", """{"outcome":"insufficient-permissions",$ticket,"blocker":3}""".toByteArray())
            .map { it.substringBefore(':') })
    }

    @Test
    fun `a refine result may name the repo its ticket belongs in as owner-name, and the rules refuse another shape`() {
        val ticket = """"kind":"chore","ticket":{"title":"t","body":"b","tier":5,"priority":"p3","points":2}"""
        val named = """{"outcome":"file",$ticket,"repo":"acme/widgets"}""".toByteArray()
        assertEquals(emptyList(), ResultSchemas.problems("refine", named))
        assertEquals("acme/widgets", RefineResult.parse(named).repo)
        assertEquals(emptyList(), RefineResult.parse(named).problems())
        assertEquals(listOf("/repo"), ResultSchemas.problems("refine", """{"outcome":"file",$ticket,"repo":"widgets"}""".toByteArray())
            .map { it.substringBefore(':') })
        assertEquals(listOf("repo 'widgets' is not owner/name"), RefineResult.parse("""{"outcome":"file",$ticket,"repo":"widgets"}""".toByteArray()).problems())
        assertEquals("repo 'acme/nowhere' is not a target repo; the target repos are acme/tickets, acme/widgets",
            RefineResult.repoProblem("acme/nowhere", listOf("acme/widgets", "acme/tickets")))
        assertEquals(null, RefineResult.repoProblem("ACME/widgets", listOf("acme/widgets")))
    }

    @Test
    fun `a file refine result needs the ticket's points in the schema, as the engine's rules do`() {
        val ticket = """"kind":"chore","ticket":{"title":"t","body":"b","tier":5,"priority":"p3""""
        assertEquals(listOf("/ticket"), ResultSchemas.problems("refine", """{"outcome":"file",$ticket}}""".toByteArray())
            .map { it.substringBefore(':') })
        assertEquals(emptyList(), ResultSchemas.problems("refine", """{"outcome":"file",$ticket,"points":2}}""".toByteArray()))
    }

    @Test
    fun `only a feature's points go above 13 in the refine schema`() {
        fun result(kind: String, points: Int) =
            """{"outcome":"file","kind":"$kind","featureMode":"auto","ticket":{"title":"t","body":"b","tier":5,"priority":"p3","points":$points}}""".toByteArray()
        assertEquals(emptyList(), ResultSchemas.problems("refine", result("feature", 34)))
        assertTrue(ResultSchemas.problems("refine", result("chore", 21)).isNotEmpty(), "a chore at 21")
        assertEquals(emptyList(), ResultSchemas.problems("refine", result("chore", 13)))
        assertTrue(ResultSchemas.problems("refine", result("feature", 89)).isNotEmpty(), "89 is off the scale")
    }

    @Test
    fun `the propose schema takes a drafted doc with questions, and an ask`() {
        val drafted = """{"outcome":"drafted","path":"docs/proposals/x.md","pitch":"docs/proposals/x.html","summary":["what","why"],"questions":[{"question":"q","assumption":"a","confidence":0.8,"impact":"low"}]}""".toByteArray()
        assertEquals(emptyList(), ResultSchemas.problems("propose", drafted))
        assertEquals(emptyList(), ProposeResult.parse(drafted).problems())
        val ask = """{"outcome":"ask","question":"Which one?"}""".toByteArray()
        assertEquals(emptyList(), ResultSchemas.problems("propose", ask))
        assertEquals(emptyList(), ProposeResult.parse(ask).problems())
    }

    @Test
    fun `an empty pass-along value fails the schema, and an empty object reads as blank to the rules`() {
        val bytes = """{"outcome":"understood","rootCause":"x","testable":true,"evidence":{}}""".toByteArray()
        assertEquals(listOf("/evidence"), ResultSchemas.problems("reproduce", bytes).map { it.substringBefore(':') })
        assertTrue(ReproduceResult.parse(bytes).problems().any { "needs the evidence" in it })
    }

    @Test
    fun `a split or revise child without kind or dependsOn fails the schema naming the child and the field, and an empty dependsOn passes`() {
        val full = """{"title":"One","body":"b","kind":"chore","dependsOn":[],"points":1}"""
        for ((mode, head) in listOf("design" to """"outcome":"split","plan":{"summary":"s"}""", "replan" to """"outcome":"revise","summary":"s"""")) {
            fun problems(second: String) = ResultSchemas.problems(mode, """{$head,"children":[$full,$second]}""".toByteArray())
            val noKind = problems("""{"title":"Two","body":"b","dependsOn":[0],"points":5}""")
            assertEquals(listOf("/children/1"), noKind.map { it.substringBefore(':') }, "$noKind")
            assertTrue("kind" in noKind.single(), noKind.single())
            val noDeps = problems("""{"title":"Two","body":"b","kind":"bug","points":5}""")
            assertEquals(listOf("/children/1"), noDeps.map { it.substringBefore(':') }, "$noDeps")
            assertTrue("dependsOn" in noDeps.single(), noDeps.single())
            assertEquals(emptyList(), problems("""{"title":"Two","body":"b","kind":"bug","dependsOn":[0],"points":5}"""))
        }
    }

    @Test
    fun `branch fields stay strict, each failure naming its field`() {
        val reproduce = ResultSchemas.problems("reproduce", """{"outcome":"reproduced","testable":"yes"}""".toByteArray())
        assertEquals(listOf("/testable"), reproduce.map { it.substringBefore(':') }, "$reproduce")
        assertTrue("boolean" in reproduce.single(), reproduce.single())

        val design = ResultSchemas.problems("design", """{"outcome":"planned","plan":{"summary":"s"},"questions":[{"question":"q","confidence":"high","impact":"huge"}]}""".toByteArray())
        assertEquals(setOf("/questions/0/confidence", "/questions/0/impact"), design.map { it.substringBefore(':') }.toSet(), "$design")

        val refine = ResultSchemas.problems("refine", """{"outcome":"file","kind":"epic","featureMode":1}""".toByteArray())
        assertEquals(setOf("/kind", "/featureMode"), refine.map { it.substringBefore(':') }.toSet(), "$refine")

        val propose = ResultSchemas.problems("propose", """{"outcome":"drafted","path":3,"pitch":["docs/proposals/x.html"],"summary":"s","questions":[{"question":"q","confidence":"high","impact":"huge"}]}""".toByteArray())
        assertEquals(setOf("/path", "/pitch", "/questions/0/confidence", "/questions/0/impact"), propose.map { it.substringBefore(':') }.toSet(), "$propose")
        assertEquals(listOf("/outcome"), ResultSchemas.problems("propose", """{"outcome":"done"}""".toByteArray()).map { it.substringBefore(':') })

        assertEquals(listOf("/outcome"), ResultSchemas.problems("review", """{"outcome":"passed"}""".toByteArray()).map { it.substringBefore(':') })
        assertEquals(listOf("/"), ResultSchemas.problems("verify", """{"summary":"x"}""".toByteArray()).map { it.substringBefore(':') })
        assertTrue(ResultSchemas.problems("verify", "not json".toByteArray()).single().startsWith("result.json is not JSON"))
    }
}
