package com.codefrak.engine.jev

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A stored response read back into picks (JevAnswers); no Jev call is made. */
class JevAnswersTest {
    private fun row(response: String?) = JevAnswers.summary(JevAnswers.picks(JevAnswers.parse(response)))

    @Test
    fun `a choice's pick is its option at its confidence`() {
        assertEquals("retry" to 0.93,
            row("""{"model":"jev-1.13.0","answers":{"remedy":{"type":"choice","choice":"retry","probabilities":{"retry":0.93,"investigate":0.07},"confidence":0.93}}}"""))
        val pick = JevAnswers.picks(JevAnswers.parse("""{"answers":{"remedy":{"type":"choice","choice":"retry","probabilities":{"retry":0.93},"confidence":0.93}}}""")).single()
        assertEquals(JevPick("remedy", "choice", "retry", 0.93, mapOf("retry" to 0.93)), pick)
    }

    @Test
    fun `a noul's pick is yes at a half or above, with the noul as its confidence`() {
        assertEquals("yes" to 0.95, row("""{"answers":{"accepts":{"type":"noul","noul":0.95}}}"""))
        assertEquals("no" to 0.2, row("""{"answers":{"accepts":{"type":"noul","noul":0.2}}}"""))
    }

    @Test
    fun `refine-pick's three picks join in the order kind, mode, tier, at the lowest confidence`() {
        val body = """{"answers":{"tier":{"type":"score","score":3.2,"probabilities":{"3":0.7,"2":0.3},"confidence":0.7},
            "featureMode":{"type":"choice","choice":"auto","confidence":0.9},"kind":{"type":"choice","choice":"feature","confidence":0.99}}}"""
        assertEquals(listOf("kind", "featureMode", "tier"), JevAnswers.picks(JevAnswers.parse(body)).map { it.question })
        assertEquals("feature · auto · tier 4" to 0.7, row(body))
    }

    @Test
    fun `a failed call, an unreadable body or one with no answers has no pick`() {
        assertEquals(null to null, row(null))
        assertEquals(null to null, row("{kind=Answer(type=choice)}"))
        assertEquals(null to null, row("""{"answers":{}}"""))
        assertNull(JevAnswers.parse("""{"model":"jev-1.13.0"}"""))
    }
}
