package com.codefrak.engine.firstmate

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FirstMateArgvTest {
    private val prompt = FirstMatePrompt("", listOf("Read"), emptyList())
    private fun allowed(argv: List<String>) = argv[argv.indexOf("--allowedTools") + 1].split(",")

    @Test
    fun `a repo's own MCP servers are allowed in the pod, as the desktop allows them`() {
        assertEquals(listOf("Read", FIRST_MATE_CHECKOUT_TOOL, "mcp__hitlp__*"),
            allowed(firstMateArgv("m", "low", prompt, repoServers = listOf("hitlp"))))
    }

    @Test
    fun `with no repo servers the allow list is the shared one plus checkout`() {
        assertEquals(listOf("Read", FIRST_MATE_CHECKOUT_TOOL), allowed(firstMateArgv("m", "low", prompt)))
    }
}
