package com.codefrak.engine.workitems

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Dependencies gate a start as Blocked by does: [WorkItemGate] over the H2 store. */
@SpringBootTest
class WorkItemGateTest(@Autowired val store: WorkItemStore, @Autowired val gate: WorkItemGate, @Autowired val jdbc: JdbcClient) {
    @BeforeEach
    fun clear() { jdbc.sql("DELETE FROM engine.work_item").update() }

    private fun item(vararg blockers: String) =
        store.create(NewWorkItem("acme/widgets", "work", "chore", WorkItemSourceRef(WorkItemSourceRef.SPLIT_CHILD, "parent"), blockedBy = blockers.toList()))

    @Test
    fun `a blocked item does not start until its blocker is done, then becomes ready and starts`() {
        val blocker = item()
        val blocked = item(blocker.id)
        assertEquals(WorkItem.BLOCKED, store.get(blocked.id)!!.state)
        assertFalse(gate.start(blocked.id))
        assertEquals(WorkItem.BLOCKED, store.get(blocked.id)!!.state)

        assertTrue(gate.start(blocker.id))
        assertFalse(gate.start(blocked.id), "a running blocker still blocks")
        assertEquals(listOf(blocked.id), gate.finish(blocker.id))
        assertEquals(WorkItem.READY, store.get(blocked.id)!!.state)
        assertTrue(gate.start(blocked.id))
        assertEquals(WorkItem.RUNNING, store.get(blocked.id)!!.state)
    }

    @Test
    fun `an item with two blockers waits for the last one`() {
        val a = item(); val b = item()
        val c = item(a.id, b.id)
        assertEquals(emptyList(), gate.finish(a.id))
        assertEquals(WorkItem.BLOCKED, store.get(c.id)!!.state)
        assertFalse(gate.startable(c.id))
        assertEquals(listOf(c.id), gate.finish(b.id))
        assertTrue(gate.startable(c.id))
    }

    @Test
    fun `a done blocker added later does not block`() {
        val a = item(); gate.finish(a.id)
        val b = item(a.id)
        assertEquals(WorkItem.READY, store.get(b.id)!!.state)
        assertTrue(gate.start(b.id))
    }
}
