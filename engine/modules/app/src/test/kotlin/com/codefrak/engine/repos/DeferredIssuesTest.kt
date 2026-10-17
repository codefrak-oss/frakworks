package com.codefrak.engine.repos

import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.IssueSnapshot
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against H2 with Flyway's migrations applied, the same scripts Postgres runs. */
@SpringBootTest
class DeferredIssuesTest(@Autowired val deferred: DeferredIssues) {
    private val repo = "acme/deferred-test"
    private val snapshot = IssueSnapshot(7, "Tidy", "tidy the \"readme\"\n", "someone")

    @Test
    fun `an approval waits once, is replaced by a fresh one, and is removed`() {
        val open = Blocker(repo, 3, "open")
        assertTrue(deferred.defer(Deferred(repo, 7, 100, "lundmatt1", "feature", "confirm", snapshot, listOf(open), tier = 4)))
        val first = assertNotNull(deferred.get(repo, 7))
        assertEquals(Triple(100L, "confirm", 4), Triple(first.labelEvent, first.featureMode, first.tier))
        assertEquals(snapshot, first.snapshot, "the snapshot round-trips")
        assertEquals(listOf(open), first.blockers)
        assertNotNull(first.firstSeen)

        val closed = Blocker(repo, 3, "closed", "completed", 12, true)
        deferred.checked(repo, 7, listOf(closed), setOf("$repo#3"))
        assertEquals(listOf(closed) to setOf("$repo#3"), assertNotNull(deferred.get(repo, 7)).let { it.blockers to it.stalled })

        val edited = snapshot.copy(title = "Tidy all")
        assertFalse(deferred.defer(Deferred(repo, 7, 101, "friend", "feature", "auto", edited, listOf(open))))
        val replaced = assertNotNull(deferred.get(repo, 7))
        assertEquals(listOf(101L, "friend", "auto", edited, emptySet<String>()),
            listOf(replaced.labelEvent, replaced.authorizedBy, replaced.featureMode, replaced.snapshot, replaced.stalled))
        assertEquals(first.firstSeen, replaced.firstSeen)
        assertEquals(1, deferred.list(repo).size)

        deferred.remove(repo, 7)
        assertNull(deferred.get(repo, 7))
        assertEquals(emptyList(), deferred.list(repo))
    }
}
