package com.codefrak.engine.feature

import com.codefrak.engine.smith.PARENT_ENDED
import com.codefrak.engine.smith.ReadResultDelegate
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [AcceptResult.problems], [AcceptResult.checklist] and [ReplanResult.problems], [ParentResult.problems] and a parent run read as `ended`: a feature's accept and replan results. */
class FeatureResultsTest {
    @Test
    fun `an unknown accept outcome is refused`() {
        val result = AcceptResult("bogus", "done")
        assertTrue(result.problems().single().startsWith("outcome 'bogus' is not one of"))
    }

    @Test
    fun `a null or blank summary is refused`() {
        assertEquals(listOf("an accept result needs a summary"), AcceptResult("accepted", null).problems())
        assertEquals(listOf("an accept result needs a summary"), AcceptResult("accepted", "   ").problems())
    }

    @Test
    fun `outcome 'unmet' needs a criterion with met false`() {
        val result = AcceptResult("unmet", "not all done", listOf(Criterion("a", true), Criterion("b", null)))
        assertEquals(listOf("outcome 'unmet' needs a criterion with met: false"), result.problems())
    }

    @Test
    fun `a met false criterion under another outcome is refused`() {
        val accepted = AcceptResult("accepted", "done", listOf(Criterion("a", false)))
        assertEquals(listOf("a criterion is not met; the outcome is 'unmet'"), accepted.problems())
        val unverified = AcceptResult("unverified", "done", listOf(Criterion("a", false)))
        assertEquals(listOf("a criterion is not met; the outcome is 'unmet'"), unverified.problems())
    }

    @Test
    fun `outcome 'accepted' with an unchecked criterion is refused`() {
        val result = AcceptResult("accepted", "done", listOf(Criterion("a", true), Criterion("b", null)))
        assertEquals(listOf("a criterion was not checked; the outcome is 'unverified'"), result.problems())
    }

    @Test
    fun `a clean result answers no problems`() {
        assertEquals(emptyList(), AcceptResult("accepted", "done", listOf(Criterion("a", true))).problems())
        assertEquals(emptyList(), AcceptResult("unmet", "not done", listOf(Criterion("a", false))).problems())
        assertEquals(emptyList(), AcceptResult("unverified", "unsure", listOf(Criterion("a", null))).problems())
    }

    @Test
    fun `checklist marks x, space and question mark, and appends how only when it's non-blank`() {
        val result = AcceptResult(
            "unverified", "x",
            listOf(
                Criterion("done", true, "the test passes"),
                Criterion("fast", false),
                Criterion("safe", null, "   "),
            ),
        )
        assertEquals(
            "- [x] done: the test passes\n" +
                "- [ ] fast\n" +
                "- [?] safe",
            result.checklist(),
        )
    }

    private fun filedChild(i: Int) = FiledChild(i, "Filed child $i", 100L + i, "https://example/$i", "chore", state = SplitState.MERGED)

    private fun draft(title: String) = ChildDraft(
        title = title,
        body = "## Goal\nShip it.\n## Acceptance criteria\n- done",
        kind = "chore",
        dependsOn = emptyList(),
        points = 3,
    )

    @Test
    fun `an unknown replan outcome is refused`() {
        assertEquals(
            listOf("outcome 'bogus' is not one of ${ReplanResult.OUTCOMES}"),
            ReplanResult("bogus").problems(emptyList()),
        )
    }

    @Test
    fun `outcome 'revise' with no children is refused`() {
        val result = ReplanResult("revise", emptyList())
        assertTrue(result.problems(emptyList()).any { it == "outcome 'revise' needs the remaining children, rewritten (or say 'done' to drop them)" })
    }

    @Test
    fun `outcome 'revise' exceeding ChildDraft MAX names the filed and revised counts`() {
        val filed = (0 until 15).map { filedChild(it) }
        val children = listOf(draft("New child a"), draft("New child b"))
        val result = ReplanResult("revise", children)
        assertEquals(
            listOf("a split has at most ${ChildDraft.MAX} parts over its life: 15 filed plus 2 revised is 17"),
            result.problems(filed),
        )
    }

    @Test
    fun `outcome 'done' answers no problems regardless of children, drafts or filed`() {
        val filed = listOf(filedChild(0))
        val drafts = listOf(draft("Leftover draft"))
        assertEquals(emptyList(), ReplanResult("done", drafts).problems(filed, drafts))
        assertEquals(emptyList(), ReplanResult("done").problems(emptyList(), emptyList()))
    }

    @Test
    fun `a steer to an open filed child is accepted beside any outcome`() {
        val filed = listOf(filedChild(0), filedChild(1).copy(state = "open"))
        assertEquals(emptyList(), ReplanResult("continue", steer = listOf(Steer(101, "use the v2 loader"))).problems(filed))
        assertEquals(emptyList(), ReplanResult("done", steer = listOf(Steer(101, "stop at the parser"))).problems(filed))
    }

    @Test
    fun `a steer to an unknown or closed child, or with a blank message, is refused naming the child`() {
        val filed = listOf(filedChild(0), filedChild(1).copy(state = "open"))
        assertEquals(listOf("steer[0]: #999 is not a filed child of this split"),
            ReplanResult("continue", steer = listOf(Steer(999, "x"))).problems(filed))
        assertEquals(listOf("steer[0]: #100 is merged, not open: only an open child can be steered"),
            ReplanResult("continue", steer = listOf(Steer(100, "x"))).problems(filed))
        assertEquals(listOf("steer[0]: the message to #101 is blank"),
            ReplanResult("continue", steer = listOf(Steer(101, "  "))).problems(filed))
    }

    @Test
    fun `a parent result keeps the replan's rules and accepts 'ended' with a summary`() {
        val filed = listOf(filedChild(0), filedChild(1).copy(state = SplitState.OPEN))
        assertEquals(emptyList(), ParentResult("continue", steer = listOf(Steer(101, "use the v2 loader")), summary = "on course").problems(filed))
        assertEquals(emptyList(), ParentResult("done", summary = "covered").problems(filed))
        assertEquals(emptyList(), ParentResult("ended", summary = "turn budget ran low").problems(filed))
    }

    @Test
    fun `a parent result is refused for an unknown outcome, a steer to a closed child, or an 'ended' with children and no summary`() {
        val filed = listOf(filedChild(0), filedChild(1).copy(state = SplitState.OPEN))
        assertEquals(listOf("outcome 'bogus' is not one of ${ParentResult.OUTCOMES}"), ParentResult("bogus").problems(filed))
        assertEquals(listOf("steer[0]: #100 is merged, not open: only an open child can be steered"),
            ParentResult("continue", steer = listOf(Steer(100, "x"))).problems(filed))
        val ended = ParentResult("ended", children = listOf(draft("Later"))).problems(filed)
        assertTrue(ended.any { "needs a summary" in it } && ended.any { "carries no children" in it }, "$ended")
    }

    private fun parentRun(vars: Map<String, Any?>): MutableMap<String, Any?> {
        val set = mutableMapOf<String, Any?>()
        val execution = mock(DelegateExecution::class.java)
        `when`(execution.processInstanceId).thenReturn("parent-1")
        `when`(execution.getVariable(anyString())).thenAnswer { (vars + set)[it.getArgument<String>(0)] }
        `when`(execution.setVariable(anyString(), org.mockito.ArgumentMatchers.any())).thenAnswer { set[it.getArgument<String>(0)] = it.getArgument(1); null }
        ReadResultDelegate().execute(execution)
        return set
    }

    @Test
    fun `a failed, reaped or result-less parent run is outcome 'ended' with the reason, not an incident`() {
        for (result in listOf("failed", "reaped")) {
            val set = parentRun(mapOf(Vars.MODE to "parent", Vars.RESULT to result))
            assertEquals("ended", set["outcome"])
            assertTrue("ended $result" in set[PARENT_ENDED] as String, "${set[PARENT_ENDED]}")
        }
        val none = parentRun(mapOf(Vars.MODE to "parent", Vars.RESULT to "success"))
        assertEquals("ended", none["outcome"])
        assertTrue("wrote no" in none[PARENT_ENDED] as String, "${none[PARENT_ENDED]}")
    }
}
