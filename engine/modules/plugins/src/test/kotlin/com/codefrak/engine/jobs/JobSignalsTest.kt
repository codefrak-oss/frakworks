package com.codefrak.engine.jobs

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The signal model (engine/README.md, "Custom jobs and the SDK"): parsed from the row's JSON by `kind`. No Spring. */
class JobSignalsTest {
    private val now = Instant.parse("2026-10-06T10:00:00Z")

    @Test
    fun `every and execute parse, and next is now plus every or nothing`() {
        val every = Signal.parse("""{"kind":"every","every":90}""")
        assertEquals(Signal.Every(90), every)
        assertEquals(now.plusSeconds(90), every.next(now))
        assertEquals("every", every.kind)

        val execute = Signal.parse("""{"kind":"execute"}""")
        assertEquals(Signal.Execute, execute)
        assertNull(execute.next(now))
        assertEquals("execute", execute.kind)
    }

    @Test
    fun `every below one second, a missing every, and extra keys are refused naming the rule`() {
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("""{"kind":"every","every":0}""") }.message!!, "at least 1")
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("""{"kind":"every","every":-5}""") }.message!!, "at least 1")
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("""{"kind":"every","every":"10"}""") }.message!!, "whole number")
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("""{"kind":"every"}""") }.message!!, "whole number")
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("""{"kind":"every","every":5,"cron":"x"}""") }.message!!, "cron")
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("""{"kind":"execute","every":5}""") }.message!!, "only kind")
    }

    @Test
    fun `at parses an instant, is due until it and has nothing due from it on`() {
        val at = Signal.parse("""{"kind":"at","at":"2026-10-06T18:00:00Z"}""")
        assertEquals(Signal.At(Instant.parse("2026-10-06T18:00:00Z")), at)
        assertEquals("at", at.kind)
        assertEquals(Instant.parse("2026-10-06T18:00:00Z"), at.next(now))
        assertNull(at.next(Instant.parse("2026-10-06T18:00:00Z")))
        assertNull(at.next(Instant.parse("2026-10-07T00:00:00Z")))
    }

    @Test
    fun `at refuses a malformed instant, a missing or non-string at, and extra keys`() {
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("""{"kind":"at","at":"2026-13-40"}""") }.message!!, "not an ISO-8601 instant")
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("""{"kind":"at"}""") }.message!!, "ISO-8601 instant string")
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("""{"kind":"at","at":1760000000}""") }.message!!, "ISO-8601 instant string")
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("""{"kind":"at","at":"2026-10-06T18:00:00Z","every":5}""") }.message!!, "not every")
    }

    @Test
    fun `an unknown kind, a missing kind and a non-object are refused, the kinds named`() {
        val unknown = assertFailsWith<IllegalArgumentException> { Signal.parse("""{"kind":"cron","at":"0 9 * * *"}""") }.message!!
        assertContains(unknown, "unknown signal kind 'cron'")
        assertContains(unknown, "every, execute, at")
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("""{"every":5}""") }.message!!, "needs a kind")
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("""[1]""") }.message!!, "object")
        assertContains(assertFailsWith<IllegalArgumentException> { Signal.parse("not json") }.message!!, "not JSON")
    }
}
