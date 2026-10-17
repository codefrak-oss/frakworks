package com.codefrak.engine.feature

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** [ChildDraft]'s ATX heading parser (engine/README.md): indent, hash count, trailing hashes, case, merging. */
class ChildDraftSectionsTest {
    @Test
    fun `headings give lowercased keys to the lines under them`() {
        val sections = ChildDraft.sections(
            """
            ## Goal
            Ship it.
            ### Acceptance criteria ##
            - done
            """.trimIndent(),
        )
        assertEquals(listOf("Ship it."), sections["goal"])
        assertEquals(listOf("- done"), sections["acceptance criteria"])
    }

    @Test
    fun `an indent of three spaces is a heading, four is body text`() {
        val sections = ChildDraft.sections(
            """
            ## Goal
               ## Still goal
                ## Not a heading
            """.trimIndent(),
        )
        assertEquals(listOf("    ## Not a heading"), sections["still goal"])
    }

    @Test
    fun `seven hashes is not a heading`() {
        val sections = ChildDraft.sections(
            """
            ## Goal
            ####### x
            """.trimIndent(),
        )
        assertEquals(listOf("####### x"), sections["goal"])
    }

    @Test
    fun `a hash needs a space after it to start a heading`() {
        val sections = ChildDraft.sections(
            """
            ## Goal
            #x
            # x
            """.trimIndent(),
        )
        assertEquals(listOf("#x"), sections["goal"])
        assertEquals(emptyList(), sections["x"])
    }

    @Test
    fun `trailing hashes are stripped from the heading name`() {
        val sections = ChildDraft.sections("## Goal ##")
        assertEquals(setOf("goal"), sections.keys)
    }

    @Test
    fun `lines before the first heading are in no section`() {
        val sections = ChildDraft.sections(
            """
            Some preamble.
            ## Goal
            Ship it.
            """.trimIndent(),
        )
        assertEquals(setOf("goal"), sections.keys)
        assertEquals(listOf("Ship it."), sections["goal"])
    }

    @Test
    fun `two headings of the same name merge their lines in order`() {
        val sections = ChildDraft.sections(
            """
            ## Goal
            First.
            ### Acceptance criteria
            - a
            ## Goal
            Second.
            """.trimIndent(),
        )
        assertEquals(listOf("First.", "Second."), sections["goal"])
    }

    @Test
    fun `an empty body is an empty map`() {
        assertEquals(emptyMap(), ChildDraft.sections(""))
    }
}
