package com.codefrak.engine.board

import com.codefrak.engine.feature.DesignResult
import com.codefrak.engine.feature.Plan
import com.codefrak.engine.feature.PlanQuestion
import com.codefrak.engine.repos.TargetRepo
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `GET /ticket/{owner}/{repo}/{n}/design-deck`: a confirm design's deck, 404 for a ticket without one or a stranger repo. */
class DesignDeckEndpointTest {
    private val decks = mock(DesignDecks::class.java)
    private val repos = mock(TargetRepos::class.java).also {
        `when`(it.find("o/r")).thenReturn(TargetRepo("o/r", OffsetDateTime.now()))
    }
    private val controller = DesignDeckController(decks, repos)

    @Test
    fun `a confirm design is served as its deck`() {
        `when`(decks.result("o/r", 5)).thenReturn(DesignResult("planned", Plan("the plan", acceptance = listOf("ok")),
            listOf(PlanQuestion("which?", "this", 0.3, "high"))))
        val answer = controller.deck("o", "r", 5)
        assertEquals(HttpStatus.OK, answer.statusCode)
        val html = (answer.body as DesignDeckView).html
        assertTrue("which?" in html && "the plan" in html && "prefers-color-scheme: dark" in html)
    }

    @Test
    fun `a ticket with no confirm design, or a repo that is not a target, is 404`() {
        assertEquals(HttpStatus.NOT_FOUND, controller.deck("o", "r", 6).statusCode)
        assertEquals(HttpStatus.NOT_FOUND, controller.deck("x", "y", 5).statusCode)
    }
}
