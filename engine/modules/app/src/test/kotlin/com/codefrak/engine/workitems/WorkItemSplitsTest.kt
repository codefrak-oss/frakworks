package com.codefrak.engine.workitems

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A split's children as work items ([WorkItemSplits]) over the H2 store. */
@SpringBootTest
class WorkItemSplitsTest(@Autowired val store: WorkItemStore, @Autowired val splits: WorkItemSplits, @Autowired val gate: WorkItemGate,
                         @Autowired val jdbc: JdbcClient) {
    @BeforeEach
    fun clear() { jdbc.sql("DELETE FROM engine.work_item").update() }

    private fun parent() = store.create(NewWorkItem("acme/widgets", "# Wide\n\nA wide feature.", "feature", WorkItemSourceRef(WorkItemSourceRef.ASK, "i-1")))

    @Test
    fun `a retried filing pass reuses the child filed for the part, found by its source ref`() {
        val parent = parent()
        val first = splits.childItem(parent.id, 1, "chore", null, 7, "# Part one\n\nbody")
        val again = splits.childItem(parent.id, 1, "chore", null, 7, "# Part one\n\nbody")
        assertEquals(first, again)
        assertEquals(1, store.list("acme/widgets").count { it.source.kind == WorkItemSourceRef.SPLIT_CHILD })
        val child = store.get(first)!!
        assertEquals(WorkItemSourceRef(WorkItemSourceRef.SPLIT_CHILD, "${parent.id}#1"), child.source)
        assertEquals(7, child.tier); assertTrue(child.trusted)
        assertEquals(listOf(first), store.blockers(parent.id))
    }

    @Test
    fun `the parent waits on its child, running or not, and finishing the child readies it`() {
        val parent = parent()
        assertTrue(gate.start(parent.id))
        val child = splits.childItem(parent.id, 1, "chore", null, null, "# Part one\n\nbody")
        assertEquals(WorkItem.BLOCKED, store.get(parent.id)!!.state)
        assertEquals(listOf(parent.id), gate.finish(child))
        assertEquals(WorkItem.READY, store.get(parent.id)!!.state)
        assertTrue(splits.done(child))
    }
}
