package com.codefrak.engine.feature

import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.IssueListing
import com.codefrak.engine.github.IssueSnapshot
import org.junit.jupiter.api.Test
import org.operaton.bpm.model.bpmn.Bpmn
import org.operaton.bpm.model.bpmn.instance.SequenceFlow
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2535: a feature flow started before `childMode` existed failed at `live parent?` after `task-file-child` had filed
 * the child, and each retry filed it again.
 */
class FileChildRetryTest {
    private val at = OffsetDateTime.parse("2026-10-12T00:00:00Z")
    private fun listing(n: Long, title: String, body: String, author: String = "bot") = IssueListing(n, title, author, listOf("feature"), "https://github.com/o/r/issues/$n", at, at, body)
    private val filedBody = "## Goal\n\nX\n\nPart 1 of 3 of #2498.\n\n<!-- engine: split of #2498 · tier 6 · points 3 · p2 -->\n"

    @Test
    fun `a retry finds the child already filed for the draft and reuses it`() {
        val open = listOf(listing(9, "Other", "Part 1 of 3 of #2498."), listing(2532, "X", filedBody))
        assertEquals(2532L, filedChild(open, "bot", 2498, 1, 3, "X")?.number)
    }

    @Test
    fun `no child is reused for another part, parent or title`() {
        val open = listOf(listing(2532, "X", filedBody))
        assertNull(filedChild(open, "bot", 2498, 2, 3, "X"))
        assertNull(filedChild(open, "bot", 2499, 1, 3, "X"))
        assertNull(filedChild(open, "bot", 2498, 1, 3, "Y"))
        assertNull(filedChild(emptyList(), "bot", 2498, 1, 3, "X"))
    }

    @Test
    fun `an issue with the draft's title and markers written by another login is not reused`() {
        val open = listOf(listing(2540, "X", filedBody, author = "mallory"))
        assertNull(filedChild(open, "bot", 2498, 1, 3, "X"))
        assertEquals(2532L, filedChild(open + listing(2532, "X", filedBody, author = "Bot"), "bot", 2498, 1, 3, "X")?.number)
    }

    @Test
    fun `a retry finds a reused child that already closed among the parent's closed blockers (#2542)`() {
        val read = { n: Long -> mapOf(2532L to IssueSnapshot(2532, "X", filedBody, "bot"), 2533L to IssueSnapshot(2533, "Y", filedBody, "bot")).getValue(n) }
        val blockers = listOf(Blocker("o/r", 2533, "closed"), Blocker("o/r", 2532, "closed", "completed", 2541, true))
        assertEquals(2532L, filedClosedChild("o/r", blockers, read, "bot", 2498, 1, 3, "X")?.number)
        // An open blocker is the open-issue lookup's, and one written by another login is never reused.
        assertNull(filedClosedChild("o/r", listOf(Blocker("o/r", 2532, "open")), read, "bot", 2498, 1, 3, "X"))
        assertNull(filedClosedChild("o/r", blockers, read, "mallory", 2498, 1, 3, "X"))
    }

    @Test
    fun `live parent? reads childMode and liveFallback so a flow without them goes the serial way`() {
        val model = Bpmn.readModelFromFile(File(bpmnDir(), "feature.bpmn"))
        val flows = model.getModelElementsByType(SequenceFlow::class.java).filter { it.source.id == "gateway-live-parent" }
        val conditions = flows.mapNotNull { it.conditionExpression?.textContent }
        assertTrue(conditions.isNotEmpty())
        conditions.forEach { c ->
            // A bare identifier throws "Cannot resolve identifier" on a flow that has no such variable.
            assertFalse(Regex("""(?<![\w'.])(childMode|liveFallback)\b""").containsMatchIn(c), c)
            assertTrue("execution.getVariable('childMode')" in c, c)
        }
    }

    private fun bpmnDir(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "bpmn") }.first { File(it, "feature.bpmn").exists() }
}
