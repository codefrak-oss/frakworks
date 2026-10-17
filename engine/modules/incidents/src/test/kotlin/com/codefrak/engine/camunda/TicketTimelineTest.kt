package com.codefrak.engine.camunda

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The pure part: cutting the gaps between steps into waits, and the totals. Times in seconds, times 1000. */
class TicketTimelineTest {
    private fun s(a: Long, b: Long) = Span(a * 1000, b * 1000)
    private fun step(a: Long, b: Long, cost: Double? = null) = PlacedStep(
        TimelineStep("coding", 1, a, b, (b - a) * 1000, null, null, cost, null, "done", false), s(a, b))
    private val ticket = TimelineTicket(1, "t", "OPEN", 1, "chore", null)
    private fun activity(a: Long, b: Long, name: String) = Mark(Mark.Kind.ACTIVITY, s(a, b), name)
    private fun cause(kind: Mark.Kind, a: Long, b: Long, detail: Map<String, Any?> = emptyMap()) = Mark(kind, s(a, b), kind.name, detail)
    private fun List<TimelineWait>.shape() = map { Triple(it.start, it.end, it.cause) }

    @Test
    fun `the stretches before the first step, between steps and after the last are gaps`() {
        assertEquals(listOf(s(0, 10), s(20, 30), s(40, 50)), TicketTimeline.gaps(s(0, 50), listOf(s(30, 40), s(10, 20))))
        assertEquals(listOf(s(25, 30)), TicketTimeline.gaps(s(0, 30), listOf(s(0, 20), s(5, 25))), "overlapping steps are one")
        assertEquals(listOf(s(0, 30)), TicketTimeline.gaps(s(0, 30), emptyList()))
    }

    @Test
    fun `a gap with no person or stuck mark is one engine wait naming the activities in it`() {
        val waits = TicketTimeline.cut(s(10, 20), listOf(activity(5, 12, "a"), activity(12, 15, "b"), activity(30, 40, "c")))
        assertEquals(listOf(Triple(10L, 20L, "engine")), waits.shape())
        assertEquals(listOf(mapOf("name" to "a", "start" to 10L, "end" to 12L), mapOf("name" to "b", "start" to 12L, "end" to 15L)),
            waits.single().payload["steps"], "clamped to the wait")
    }

    @Test
    fun `several causes in one gap are waits in time order, the engine's between them`() {
        val marks = listOf(
            cause(Mark.Kind.AUTHORIZATION, 0, 10),
            cause(Mark.Kind.QUESTION, 12, 20, mapOf("question" to "q?", "answer" to null)),
            cause(Mark.Kind.STUCK, 25, 40, mapOf("incident" to "task-x: boom", "retrier" to "alice")),
        )
        val waits = TicketTimeline.cut(s(5, 30), marks)
        assertEquals(listOf(Triple(5L, 10L, "person-authorization"), Triple(10L, 12L, "engine"), Triple(12L, 20L, "person-question"),
            Triple(20L, 25L, "engine"), Triple(25L, 30L, "stuck")), waits.shape())
        assertEquals(mapOf("question" to "q?", "answer" to null), waits[2].payload)
        assertEquals(listOf("incident", "retrier", "steps"), waits[4].payload.keys.toList())
        assertEquals("alice", waits[4].payload["retrier"])
    }

    @Test
    fun `where marks overlap, stuck beats a question beats authorization`() {
        val marks = listOf(cause(Mark.Kind.AUTHORIZATION, 0, 30), cause(Mark.Kind.QUESTION, 5, 25), cause(Mark.Kind.STUCK, 10, 15))
        assertEquals(listOf(Triple(0L, 5L, "person-authorization"), Triple(5L, 10L, "person-question"), Triple(10L, 15L, "stuck"),
            Triple(15L, 25L, "person-question"), Triple(25L, 30L, "person-authorization")), TicketTimeline.cut(s(0, 30), marks).shape())
    }

    @Test
    fun `a receive-ci-check in a gap is a ci wait between the engine's, and the totals identity holds`() {
        val ci = Mark(Mark.Kind.CI, s(35, 45), "receive-ci-check", mapOf("check" to "engine", "conclusion" to "success"))
        val marks = listOf(activity(30, 32, "task-open-pr"), activity(35, 45, "receive-ci-check"), ci, activity(45, 46, "task-read-ci-check"))
        val t = TicketTimeline.build(ticket, listOf(step(10, 30, 1.0), step(50, 60, 0.5)), s(10, 60), marks)
        assertEquals(listOf(Triple(30L, 35L, "engine"), Triple(35L, 45L, "ci"), Triple(45L, 50L, "engine")), t.waits.shape())
        val wait = t.waits[1]
        assertEquals(listOf("steps", "conclusion", "check"), wait.payload.keys.toList())
        assertEquals("success", wait.payload["conclusion"]); assertEquals("engine", wait.payload["check"])
        assertEquals(listOf(mapOf("name" to "receive-ci-check", "start" to 35L, "end" to 45L)), wait.payload["steps"])
        with(t.totals) {
            assertEquals(10_000L, ciMs); assertEquals(10_000L, engineMs); assertEquals(0L, stuckMs); assertEquals(0L, waitingPersonMs)
            assertEquals(waitingMs, waitingPersonMs + stuckMs + ciMs + engineMs)
            assertEquals(elapsedMs, workingMs + waitingMs)
        }
        assertEquals(0L, TicketTimeline.build(ticket, listOf(step(10, 30)), s(0, 40), emptyList()).totals.ciMs, "no CI wait")
    }

    @Test
    fun `a ci wait with nothing recorded has null check and conclusion, and stuck or a person's wait beats it`() {
        val ci = Mark(Mark.Kind.CI, s(0, 30), "receive-ci-check", mapOf("check" to null, "conclusion" to null))
        val waits = TicketTimeline.cut(s(0, 30), listOf(ci, cause(Mark.Kind.STUCK, 5, 10), cause(Mark.Kind.AUTHORIZATION, 20, 25)))
        assertEquals(listOf(Triple(0L, 5L, "ci"), Triple(5L, 10L, "stuck"), Triple(10L, 20L, "ci"), Triple(20L, 25L, "person-authorization"),
            Triple(25L, 30L, "ci")), waits.shape())
        assertEquals(mapOf("steps" to emptyList<Any>(), "conclusion" to null, "check" to null), waits[0].payload)
    }

    /**
     * `runSpan` is what `TicketTimelineController.step` uses to place a Smith run at its
     * `reusable-smith-run` wrapper's own edges instead of the inner `smith-run`'s: the case
     * from #243 (issue #236) — a `call-refine-ask` whose wrapper ran 70s end to end while the
     * inner run's own timestamps only covered the middle of it.
     */
    @Test
    fun `a run that finished cleanly is placed at its wrapper's edges, not its own`() {
        assertEquals(0L to 70L, TicketTimeline.runSpan(2L, 68L, null, false, 0L, 70L))
    }

    @Test
    fun `no wrapper known (or a run still going) keeps the run's own reading`() {
        assertEquals(2L to 68L, TicketTimeline.runSpan(2L, 68L, null, false, null, null))
        assertEquals(2L to null, TicketTimeline.runSpan(2L, null, null, false, null, null))
        assertEquals(0L to null, TicketTimeline.runSpan(2L, null, null, false, 0L, null), "still going: no end even once the wrapper start is known")
    }

    @Test
    fun `an incident keeps the tighter end, not the wrapper's, since that isn't where the run stopped working`() {
        assertEquals(0L to 20L, TicketTimeline.runSpan(2L, 68L, 20L, false, 0L, 70L), "retried in place and finished: still cut at the incident")
        assertEquals(0L to 20L, TicketTimeline.runSpan(2L, null, 20L, true, 0L, null), "still stuck: cut at the incident, not left open")
    }

    @Test
    fun `totals add up by construction, and an open wait runs to the span's end`() {
        val now = 100L
        val t = TicketTimeline.build(ticket, listOf(step(10, 30, 1.25), step(50, 60, 0.1)), s(0, now),
            listOf(cause(Mark.Kind.STUCK, 30, 45), cause(Mark.Kind.QUESTION, 60, now)))
        assertEquals(listOf(Triple(0L, 10L, "engine"), Triple(30L, 45L, "stuck"), Triple(45L, 50L, "engine"), Triple(60L, 100L, "person-question")),
            t.waits.shape())
        with(t.totals) {
            assertEquals(100_000L, elapsedMs)
            assertEquals(30_000L, workingMs)
            assertEquals(70_000L, waitingMs)
            assertEquals(40_000L, waitingPersonMs); assertEquals(15_000L, stuckMs); assertEquals(0L, ciMs); assertEquals(15_000L, engineMs)
            assertEquals(1.35, costUsd)
        }
    }

    @Test
    fun `blocked is only for insufficient-permissions, uses the blocker verbatim, and otherwise cuts reason's first sentence to 20 words`() {
        assertEquals(null, TicketTimeline.blocked("file", "b", "r"))
        assertEquals(null, TicketTimeline.blocked("ask", null, null))
        assertEquals(null, TicketTimeline.blocked("withdrawn", null, "Drop it, #2685 already covers this"), "a withdrawn ask is no blocked banner")
        assertEquals(TimelineBlocked("insufficient-permissions", "Needs a signing secret", "Long reason. More."),
            TicketTimeline.blocked("insufficient-permissions", " Needs a signing secret ", "Long reason. More."))
        assertEquals("Needs a mac secret.", TicketTimeline.blocked("insufficient-permissions", null, "Needs a mac secret. And a host.")!!.summary)
        assertEquals("Needs a mac secret.", TicketTimeline.blocked("insufficient-permissions", "  ", "\nNeeds a mac secret.\nAnd a host.")!!.summary, "blank blocker")
        val long = (1..30).joinToString(" ") { "w$it" }
        assertEquals((1..20).joinToString(" ") { "w$it" } + "…", TicketTimeline.blocked("insufficient-permissions", null, long)!!.summary)
        assertEquals((1..20).joinToString(" ") { "w$it" } + "…", TicketTimeline.blocked("insufficient-permissions", long, "r")!!.summary,
            "a blocker over the limit is cut too")
        assertEquals("v1.2 is out of scope.", TicketTimeline.firstSentence("v1.2 is out of scope. Next."), "a dot inside a word is no sentence end")
    }

    @Test
    fun `a refine result stored before the rename, with outcome capability, is blocked as insufficient-permissions`() {
        assertEquals(TimelineBlocked("insufficient-permissions", "Needs a signing secret", "r"),
            TicketTimeline.blocked("capability", "Needs a signing secret", "r"))
    }
}
