package com.codefrak.engine.workitems

import com.codefrak.engine.events.CreatedEvents
import com.codefrak.engine.intake.DraftTicket
import com.codefrak.engine.intake.RefinePick
import com.codefrak.engine.intake.RefineResult
import com.codefrak.engine.intake.TicketFiler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The intake's work-item path (engine/docs/work-items.md, "Asks as work items"): an ask filed as a work item, no GitHub. */
@SpringBootTest
class WorkItemIntakeTest(@Autowired val store: WorkItemStore, @Autowired val intake: WorkItemIntake,
                         @Autowired val events: CreatedEvents, @Autowired val jdbc: JdbcClient) {
    @BeforeEach
    fun clear() { jdbc.sql("DELETE FROM engine.work_item").update() }

    @Test
    fun `an ask runs through refinement into a filed work item, its tier a field, with no GitHub client`() {
        val filer = TicketFiler(store, events, intake)
        val ask = intake.askItem("acme/widgets", "intake-7", "Make the widget blue")
        val asked = store.get(ask)!!
        assertEquals(WorkItem.ASK, asked.kind); assertEquals(WorkItemSourceRef(WorkItemSourceRef.ASK, "intake-7"), asked.source)
        assertTrue(asked.trusted); assertEquals(WorkItem.READY, asked.state)

        val refined = RefineResult("file", DraftTicket("Blue widget", "## Goal\n\nBlue.", 6, "p2", 3), kind = "feature", featureMode = "auto")
        val pick = RefinePick.refiner(refined)
        val filed = filer.fileWorkItem("acme/widgets", "intake-7", "Make the widget blue", refined.ticket!!.title,
            refined.ticket!!.body, pick.kind, pick.featureMode, pick.tier)

        assertEquals(ask, filed.id)
        val item = store.get(filed.id)!!
        assertEquals("feature", item.kind); assertEquals("auto", item.mode); assertEquals(6, WorkItemIntake.tierOf(item))
        assertEquals(WorkItemSourceRef(WorkItemSourceRef.ASK, "intake-7"), item.source)
        assertFalse(item.description.contains("<!-- engine:")); assertTrue(item.description.contains("Blue widget"))
        assertTrue(item.entries.any { it.body == "refined into a feature at tier 6" })
        // A retry finds the same ask item, and a filed item is no longer an ask.
        assertEquals(ask, intake.askItem("acme/widgets", "intake-7", "again"))
        assertThrows<WorkItemRefused> { store.refine(ask, "bug", 2, null, "x") }
    }
}
