package com.codefrak.engine.repos

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocsOnlyTest {
    @Test
    fun `markdown anywhere, docs directories and READMEs are docs`() {
        assertTrue(DocsOnly.of(listOf("servers/nas/setup.md", "README.md", "CLAUDE.md", "AGENTS.md", "docs/diagram.svg",
            "engine/docs/proposals/x.html", "README", "notes/a.MDX")))
    }

    @Test
    fun `a mixed diff is not docs-only`() {
        assertFalse(DocsOnly.of(listOf("README.md", "engine/src/Main.kt")))
        assertFalse(DocsOnly.of(listOf("docs/a.md", "scripts/local-up.sh")))
    }

    @Test
    fun `no paths, code, and look-alikes are not docs`() {
        assertFalse(DocsOnly.of(emptyList()))
        assertFalse(DocsOnly.of(listOf("build.gradle.kts")))
        assertFalse(DocsOnly.isDoc("mydocs/run.sh"))
        assertFalse(DocsOnly.isDoc("docs"))
        assertFalse(DocsOnly.isDoc(".github/workflows/ci.yml"))
        assertFalse(DocsOnly.isDoc("md"))
    }
}
