package com.codefrak.engine.refactor

import com.codefrak.engine.workitems.FakeTickets
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.TicketState
import com.codefrak.engine.workitems.WorkItemFlags
import com.codefrak.engine.workitems.WorkItemSourceRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** [openRefactorKeys] on a [FakeTickets]: the trailer keys of the repo's open tickets flagged `refactor`, and of no other. Plain JUnit. */
class OpenRefactorKeysTest {
    private val tickets = FakeTickets()
    private val repo = "acme/widgets"

    private fun file(title: String, key: String, refactor: Boolean, repo: String = this.repo) =
        tickets.create(NewWorkItem(repo, "# $title\n\n## Goal\n\n<!-- engine: refactor-scan #7 · p3 · by alice · key $key -->",
            "feature", WorkItemSourceRef(WorkItemSourceRef.RAISED), mode = "confirm", flags = WorkItemFlags(refactor = refactor)))

    @Test
    fun `a ticket flagged refactor is listed by openRefactorKeys, and one without the flag is not`() {
        file("Split engine/jobs", "engine/jobs", refactor = true)
        file("Split engine/intake", "engine/intake", refactor = false)
        assertEquals(setOf("engine/jobs"), tickets.openRefactorKeys(repo))
    }

    @Test
    fun `an ended refactor ticket and one in another repo are not listed`() {
        val ended = file("Split engine/jobs", "engine/jobs", refactor = true)
        tickets.setState(ended.id, TicketState.DONE, "merged")
        file("Split engine/smith", "engine/smith", refactor = true, repo = "acme/other")
        assertEquals(emptySet(), tickets.openRefactorKeys(repo))
    }
}
