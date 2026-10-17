package com.codefrak.engine.friction

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * With the pokes on (as the engine runs): an insert wakes the grouper after its commit and the worker thread,
 * `friction-grouper`, groups the note; the scheduled poke's bean exists. No Jev account is configured, so the real
 * decider records "no usable jev account" and a note with a new key founds its own group as `no-jev`.
 */
@SpringBootTest
@TestPropertySource(properties = ["engine.friction.group-enabled=true"])
class FrictionGrouperWakeTest {
    @Autowired lateinit var notes: FrictionNotes
    @Autowired lateinit var grouper: FrictionGrouper
    @Autowired lateinit var groups: FrictionGroups
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `an insert pokes the worker, which groups the note off the inserting thread`() {
        val t0 = OffsetDateTime.parse("2026-10-04T18:49:35Z")
        val a = FrictionNote("a", "acme/a", 7, "run-a", null, t0, FrictionNote.AS_YOU_GO, "tooling", "no Gradle", "gradle: not found", null)
        val b = a.copy(id = "b", notedAt = t0.plusMinutes(1), summary = "no Docker", evidence = "docker: not found")
        notes.insert(a)
        notes.insert(b)
        grouper.settle()
        val back = notes.list(FrictionFilter()).associateBy { it.id }
        assertEquals("new", assertNotNull(back["a"]).groupedBy, "the first note founds with no call")
        assertEquals("no-jev", assertNotNull(back["b"]).groupedBy, "no jev account: its own group at once")
        assertEquals(2, groups.count())
        assertEquals(0, groups.ungrouped())
        assertTrue(context.getBeansOfType(FrictionGroupTick::class.java).isNotEmpty(), "the scheduled poke is there when the pokes are on")
        assertTrue(Thread.getAllStackTraces().keys.any { it.name == FrictionGrouper.THREAD })
    }
}
