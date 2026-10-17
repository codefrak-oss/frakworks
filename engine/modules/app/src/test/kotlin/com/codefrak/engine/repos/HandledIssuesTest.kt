package com.codefrak.engine.repos

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against H2 with Flyway's migrations applied, the same scripts Postgres runs. */
@SpringBootTest
class HandledIssuesTest(@Autowired val handled: HandledIssues) {
    private val repo = "acme/handled-test"

    @Test
    fun `an approval is claimed once, then started`() {
        assertFalse(handled.seen(repo, 7, 100))
        assertTrue(handled.claim(repo, 7, 100, "chore", "started", null))
        assertFalse(handled.claim(repo, 7, 100, "chore", "started", null))
        assertTrue(handled.seen(repo, 7, 100))
        assertEquals(listOf<String?>(null), handled.startedBefore(repo, 7, "chore"), "claimed, no instance yet")
        assertEquals(emptyList(), handled.startedBefore(repo, 7, "intake"))

        handled.started(repo, 7, 100, "pi-1")
        assertEquals("pi-1", handled.list(repo).single().processInstance)
        assertEquals(listOf<String?>("pi-1"), handled.startedBefore(repo, 7, "chore"))

        // A fresh label on the same issue is a new approval.
        assertTrue(handled.claim(repo, 7, 101, "chore", "refused", "already running"))
        assertEquals(listOf(100L, 101L), handled.list(repo).map { it.labelEvent })

        handled.release(repo, 7, 100); handled.release(repo, 7, 101)
        assertEquals(emptyList(), handled.list(repo))
    }

    @Test
    fun `a refusal is not a start, and release gives the claim back`() {
        assertTrue(handled.claim(repo, 8, 200, "chore", "refused", "no access"))
        assertEquals(emptyList(), handled.startedBefore(repo, 8, "chore"))
        assertNull(handled.list(repo).single().processInstance)

        handled.outcome(repo, 8, 200, "started", "x".repeat(500))
        assertEquals(1, handled.startedBefore(repo, 8, "chore").size)
        assertEquals(400, handled.list(repo).single().detail!!.length)

        handled.release(repo, 8, 200)
        assertFalse(handled.seen(repo, 8, 200))
        assertTrue(handled.claim(repo, 8, 200, "chore", "started", null))
        handled.release(repo, 8, 200)
    }
}
