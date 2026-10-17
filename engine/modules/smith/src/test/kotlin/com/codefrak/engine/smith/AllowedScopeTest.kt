package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllowedScopeTest {
    private val ticket = """
        ## Goal

        Fence the work. `docs/elsewhere.md` is prose, not scope.

        ## Allowed scope

        - Tier: 2
        - `engine/src/**`
        - `engine/bpmn/chore.bpmn`, `engine/bpmn/bug.bpmn`, `engine/bpmn/feature.bpmn`: the out-of-scope edge
        - `engine/smith/run-smith.sh` and `engine/smith/schemas/**`
        - Docs: engine/README.md, the fence
        - May push to main: no
        **Not** a bullet: `desktop/**`

        ## Acceptance

        - `docs/proposals/x.md` is marked built
    """.trimIndent()

    @Test
    fun `workflow paths and push-to-main grants are read from the ticket as before`() {
        assertTrue(AllowedScope.allowsWorkflows("## Allowed scope\n\n- `.github/workflows/images.yml`\n"))
        assertTrue(AllowedScope.allowsWorkflows("## Allowed scope\n\n- `.github/**`\n"))
        assertFalse(AllowedScope.allowsWorkflows(ticket))
        assertFalse(AllowedScope.allowsWorkflows("## Goal\n\n- `.github/workflows/x.yml`\n\n## Allowed scope\n\n- `engine/**`\n"))
        assertTrue(AllowedScope.grantsPushToMain("## Allowed scope\n\n- **May push to main:** yes, with review\n"))
        assertFalse(AllowedScope.grantsPushToMain(ticket))
        assertFalse(AllowedScope.grantsPushToMain("- May not push to main: yes\n"))
    }

    @Test
    fun `a line that keeps paths out grants nothing to the workflows check`() {
        // The three Allowed scopes #857's refiners drafted: each was refused as reaching .github/workflows/.
        val drafted = listOf(
            "- May touch: `engine/README.md`\n- May not touch: .github/**, engine/src/main/resources/processes/**, anything under bots/ other than bots/README.md\n- May push to main: no",
            "- `engine/**`\n- Nothing under `.github/**`, `observability/**`, or `bots/proposals/**`",
            "- `engine/**`, `compose.yaml`\n- Must not touch: `.github/**`, any `.bpmn` file, `docs/proposals/webhook-wake.md` or `bots/proposals/**` (history stays)",
        ).map { "## Allowed scope\n\n$it\n\n## Context\n" }
        drafted.forEach { assertFalse(AllowedScope.allowsWorkflows(it), it) }

        listOf("**Do not touch:** `.github/workflows/**`", "Don't touch `.github/**`", "Out of scope: `.github/**`", "Never `.github/workflows/x.yml`",
            "Off-limits: .github/**", "Excluded: `.github/**`", "No changes under `.github/**`", "Not: `.github/**`").forEach {
            assertFalse(AllowedScope.allowsWorkflows("## Allowed scope\n\n- `engine/**`\n- $it\n"), it)
        }
    }

    @Test
    fun `what is nested under an excluding line, or bulleted under an excluding lead-in, is kept out with it`() {
        val nested = "## Allowed scope\n\n- `engine/**`\n- May not touch:\n  - `.github/**`\n  - `bots/proposals/**`\n- `docs/x.md`\n"
        assertFalse(AllowedScope.allowsWorkflows(nested))
        val headed = "## Allowed scope\n\nMay touch:\n\n- `engine/**`\n\n**Must not touch:**\n\n- `.github/**`\n- `desktop/**`\n\nAlso:\n\n- `docs/x.md`\n"
        assertFalse(AllowedScope.allowsWorkflows(headed))
    }

    @Test
    fun `a granting line is read up to its exception`() {
        val body = "## Allowed scope\n\n- `.github/**` except `.github/workflows/**`\n- `bots/**` but not `bots/smith.md`\n- `no/x.md`\n- not/y.md\n- `docs/except/z.md`\n"
        assertTrue(AllowedScope.allowsWorkflows(body), "`.github/**` is still granted")
        assertFalse(AllowedScope.allowsWorkflows("## Allowed scope\n\n- `engine/**`, excluding `.github/workflows/**`\n"))
    }

    @Test
    fun `grantCount counts the distinct paths and globs the granting lines name`() {
        assertEquals(7, AllowedScope.grantCount(ticket), "engine/src/**, three bpmn files, run-smith.sh, schemas/**, README.md")
        assertEquals(0, AllowedScope.grantCount("## Goal\n\n- `engine/src/**`\n"), "no Allowed scope section")
        assertEquals(4, AllowedScope.grantCount("""
            ## Allowed scope

            - observability/desktop/src/renderer/flow.jsx and the stylesheet(s) under observability/desktop/src/renderer/
            - `bots/**` except `bots/smith.md`
            - May not touch: .github/**, engine/bpmn/**
            - Tier: 2, e.g. none
            - `engine/src/**` again: engine/src/**.
        """.trimIndent()), "flow.jsx, renderer/, bots/** (cut at except) and engine/src/** once; May not touch and e.g. count for nothing")
    }
}
