package com.codefrak.engine.refactor

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The prompt a refactoring run's Smith reads (engine/README.md, "Refactoring jobs"); pure, no Spring. */
class RefactorPromptTest {
    private val values = "# Values\n\n- **Small modules.** One thing each.\n- **No dead code.** It goes.\n"

    @Test
    fun `the prompt quotes the values verbatim, states both caps, lists the open keys and names the summary shape`() {
        val prompt = RefactorPrompt.build(values, 2, 300, setOf("engine/jobs", "bots/readme"))
        assertContains(prompt, "${RefactorPrompt.VALUES_HEADING}\n\n${values.trimEnd()}\n")
        assertContains(prompt, "at most 2 small, self-contained refactorings")
        assertContains(prompt, "at most 300 changed lines")
        assertContains(prompt, "counts its moved lines once, not as both added and removed, plus every other line of the new file: its KDoc, package line and imports")
        assertContains(prompt, "At most 2 proposals, each `lines` at most 300")
        val open = prompt.substringAfter(RefactorPrompt.OPEN_HEADING).substringBefore(RefactorPrompt.SHAPE_HEADING)
        assertEquals(listOf("- `bots/readme`", "- `engine/jobs`"), open.lines().filter { it.startsWith("- ") }, "sorted, under the do-not-propose heading")
        assertContains(prompt, """{"proposals": [{"key": "<slug>", "title": "<ticket title>", "body": "<ticket body, markdown>", "lines": <estimated changed lines>}]}""")
        assertContains(prompt, "`## Context`")
        assertTrue(prompt.indexOf(RefactorPrompt.VALUES_HEADING) < prompt.indexOf(RefactorPrompt.OPEN_HEADING), "values, then open keys, then the shape")
        assertTrue(prompt.indexOf(RefactorPrompt.OPEN_HEADING) < prompt.indexOf(RefactorPrompt.SHAPE_HEADING))
    }

    @Test
    fun `with no open keys it says so instead of listing`() {
        val prompt = RefactorPrompt.build(values, 1, 50, emptyList())
        assertContains(prompt, "None: no refactoring ticket from an earlier run is open.")
        assertFalse(prompt.substringAfter(RefactorPrompt.OPEN_HEADING).substringBefore(RefactorPrompt.SHAPE_HEADING).lines().any { it.startsWith("- ") })
        assertContains(prompt, "at most 1 small")
    }

    @Test
    fun `the shipped values are in the jar and read as markdown with the checklist`() {
        val shipped = RefactorValues.DEFAULT
        assertTrue(shipped.startsWith("# Refactoring values"), shipped.take(40))
        assertContains(shipped, "## The checklist a proposal answers")
        assertContains(shipped, "Small files with one responsibility")
        assertContains(shipped, "Dependencies point one way")
        assertContains(shipped, "A boundary is a build unit, not a folder")
    }
}
