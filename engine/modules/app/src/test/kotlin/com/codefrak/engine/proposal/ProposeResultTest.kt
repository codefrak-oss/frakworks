package com.codefrak.engine.proposal

import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.github.BundleChanges
import com.codefrak.engine.smith.ReadResultDelegate
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.value.TypedValue
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The propose mode's result rules (engine/README.md, "Proposal"), and readResult applying them. */
class ProposeResultTest {
    private val path = "docs/proposals/widgets.md"
    private val pitch = "docs/proposals/widgets.html"
    private val page = PitchRulesTest.EXAMPLE
    private fun drafted(path: String = this.path, questions: String = "[]", pitch: String? = this.pitch) = ProposeResult.parse(
        ("""{"outcome":"drafted","path":"$path",""" + pitch?.let { """"pitch":"$it",""" }.orEmpty() +
            """"summary":"Build widgets.","questions":$questions}""").toByteArray())

    /** What a draft's bundle changes: the doc and its pitch, unless a test says otherwise. */
    private fun changes(paths: List<String> = listOf(path, pitch), before: String? = null, after: String? = doc(), page: String? = this.page) =
        BundleChanges(paths, before, after, page)

    private fun doc(vararg log: String) = (listOf("# Widgets", "Status: accepted", "Decision log:") + log.map { "- $it" } +
        listOf("Ticket: #5", "", "## The question", "", "Widgets?")).joinToString("\n")

    private val old = doc("2026-09-01 shelved — too early", "2026-09-20 accepted (#4)")

    @Test
    fun `a new doc touching only docs-proposals passes`() {
        assertEquals(emptyList(), drafted().problems())
        assertEquals(emptyList(), drafted().changeProblems(changes()))
    }

    @Test
    fun `a bundle touching a path outside docs-proposals is refused`() {
        val problems = drafted().changeProblems(changes(listOf(path, pitch, "engine/README.md")))
        assertEquals(1, problems.size, "$problems")
        assertTrue("outside docs/proposals/: engine/README.md" in problems.single(), problems.single())
    }

    @Test
    fun `a drafted result with no bundle is refused`() {
        assertTrue(drafted().changeProblems(null).single().contains("no bundle"))
        assertEquals(emptyList(), ProposeResult.parse("""{"outcome":"ask","question":"Which?"}""".toByteArray()).changeProblems(null))
    }

    @Test
    fun `a path not a doc under docs-proposals is refused`() {
        listOf("docs/widgets.md", "docs/proposals/widgets.txt", "docs/proposals/../../engine/x.md", "docs/proposals/sub/x.md", "docs/proposals/.md")
            .forEach { p -> assertTrue(drafted(p).problems().any { "is not a doc in docs/proposals/" in it }, p) }
        assertTrue(ProposeResult.parse("""{"outcome":"drafted","summary":"x"}""".toByteArray()).problems().any { "needs the doc's path" in it })
    }

    @Test
    fun `the bundle must change the named doc and leave it`() {
        assertTrue(drafted().changeProblems(changes(listOf("docs/proposals/other.md", pitch), after = null)).any { "does not change $path" in it })
        assertTrue(drafted().changeProblems(changes(before = old, after = null)).any { "leaves no $path" in it })
    }

    @Test
    fun `a revisit that drops a decision-log line is refused`() {
        val dropped = doc("2026-09-20 accepted (#4)")
        val problems = drafted().changeProblems(changes(before = old, after = dropped))
        assertEquals(1, problems.size, "$problems")
        assertTrue("drops decision-log lines" in problems.single() && "2026-09-01 shelved — too early" in problems.single(), problems.single())
        // An edited line is a dropped one.
        assertEquals(1, drafted().changeProblems(changes(before = old, after = doc("2026-09-01 shelved", "2026-09-20 accepted (#4)"))).size)
    }

    @Test
    fun `a revisit that keeps every decision-log line and adds one passes`() {
        val kept = doc("2026-09-01 shelved — too early", "2026-09-20 accepted (#4)", "2026-09-26 revised (#5)")
        assertEquals(emptyList(), drafted().changeProblems(changes(before = old, after = kept)))
        assertEquals(listOf("- 2026-09-01 shelved — too early", "- 2026-09-20 accepted (#4)"), ProposeResult.decisionLog(old))
    }

    @Test
    fun `a drafted result needs its pitch, a page beside the doc with the same slug`() {
        assertTrue(drafted(pitch = null).problems().any { "needs the pitch's path" in it && PitchRules.GUIDE in it })
        listOf("docs/pitches/widgets.html", "widgets.html", "docs/proposals/sub/widgets.html", "docs/proposals/widgets.htm", "docs/proposals/.html")
            .forEach { p -> assertTrue(drafted(pitch = p).problems().any { "is not a page in docs/proposals/" in it }, p) }
        assertEquals(listOf("pitch 'docs/proposals/gadgets.html' is not the doc's: docs/proposals/widgets.html"),
            drafted(pitch = "docs/proposals/gadgets.html").problems())
    }

    @Test
    fun `the bundle must change the pitch and leave it, and the pitch must pass its rules`() {
        assertTrue(drafted().changeProblems(changes(listOf(path))).any { "does not change $pitch" in it })
        assertEquals(listOf("the bundle leaves no $pitch"), drafted().changeProblems(changes(page = null)))
        val problems = drafted().changeProblems(changes(page = page.replace("<style>", "<style>\n  @import 'theme.css';")))
        assertTrue(problems.single().startsWith("$pitch: no @import"), "$problems")
    }

    @Test
    fun `an ask needs its question, and questions are checked as a design's`() {
        assertTrue(ProposeResult.parse("""{"outcome":"ask"}""".toByteArray()).problems().any { "needs a question" in it })
        assertTrue(drafted(questions = """[{"question":"q","confidence":2}]""").problems().any { "no assumption" in it })
        assertTrue(drafted(questions = """[{"question":"q","assumption":"a","confidence":2}]""").problems().any { "is not 0-1" in it })
    }

    @Test
    fun `an important question makes a drafted result ask`() {
        assertEquals(null, drafted(questions = """[{"question":"Which?","assumption":"A","confidence":0.9,"impact":"low"}]""").userQuestion())
        val asked = drafted(questions = """[{"question":"Which?","assumption":"A","confidence":0.4,"impact":"low"}]""").userQuestion()!!
        assertTrue(asked.startsWith("The draft is ready") && "**Which?**" in asked && asked.endsWith("```ask\nkind: text\nquestion: Which?\n```"), asked)
    }

    // readResult itself, on a stand-in execution: the git fake answers what the bundle changes.

    private fun execution(result: String, bundle: ByteArray?): Pair<DelegateExecution, MutableMap<String, Any?>> {
        val vars = mutableMapOf<String, Any?>(Vars.RESULT to "success", Vars.MODE to "propose", Vars.OUTPUT to result.toByteArray(),
            Vars.BUNDLE to bundle, Vars.REPO to "acme/widgets", Vars.ISSUE to 5L)
        val proxy = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DelegateExecution::class.java)) { _, method, args ->
            when (method.name) {
                "getVariable" -> vars[args[0] as String]
                "setVariable" -> { vars[args[0] as String] = args[1].let { if (it is TypedValue) it.value else it }; null }
                "getProcessInstanceId" -> "pi-1"
                else -> null
            }
        } as DelegateExecution
        return proxy to vars
    }

    @Test
    fun `readResult checks the bundle through git, keeps the result, and asks for an important question`() {
        val git = FakeGitPush()
        val read = ReadResultDelegate(listOf(ProposeResultHandler(git, FakeGitHubPulls())))
        val result = """{"outcome":"drafted","path":"$path","pitch":"$pitch","summary":"s","questions":[]}"""

        git.changes += changes(before = old, after = doc("2026-09-01 shelved — too early", "2026-09-20 accepted (#4)", "2026-09-26 revised (#5)"))
        val (ok, vars) = execution(result, "bundle".toByteArray())
        read.execute(ok)
        assertEquals("drafted", vars["outcome"])
        assertEquals(result, String(vars[Vars.PROPOSAL] as ByteArray))
        assertEquals(listOf("acme/widgets", "smith/issue-5", "main", "bundle", path, pitch), git.changeCalls.single())

        // A first refusal reruns the draft (#1742); a second in a row parks on the incident.
        git.changes += changes(listOf(path, pitch, "engine/x.kt"))
        val (first, firstVars) = execution(result, "bundle".toByteArray())
        read.execute(first)
        assertEquals("rejected", firstVars["outcome"])
        assertEquals(true, firstVars[Vars.RULES_REJECTED])
        git.changes += changes(listOf(path, pitch, "engine/x.kt"))
        assertThrows<IllegalStateException> { read.execute(execution(result, "bundle".toByteArray()).also { it.second[Vars.RULES_REJECTED] = true }.first) }
        assertThrows<IllegalStateException> { read.execute(execution(result, null).also { it.second[Vars.RULES_REJECTED] = true }.first) }

        val important = """{"outcome":"drafted","path":"$path","pitch":"$pitch","summary":"s","questions":[{"question":"q","assumption":"a","confidence":0.5,"impact":"low"}]}"""
        git.changes += changes()
        val (asks, askVars) = execution(important, "bundle".toByteArray())
        read.execute(asks)
        assertEquals("ask", askVars["outcome"])
    }
}
