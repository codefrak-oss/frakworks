package com.codefrak.engine.mcp

import com.codefrak.engine.EngineBuild

/**
 * read_version (#100101), on [FrakworksMcp] for both roles: the engine's build version and commit, what `GET /version`
 * answers, from the same [EngineBuild]. Read-only, no arguments, no `repo`: it is about the engine, and whoever could
 * attach the server may call it. Without an [EngineBuild] (a test that builds the server by hand) both are null.
 */
class VersionTool(private val build: EngineBuild?) {
    companion object {
        const val NAME = "read_version"

        val TOOL = McpTool(NAME,
            "Read-only: the engine's build version and the git commit its image was built from, as GET /version " +
                "answers, {version, commit}; each null when the build did not record it.",
            obj())
    }

    fun call(args: Map<String, Any?>): McpToolResult {
        refuseOthers(NAME, args, emptyList())?.let { return it }
        val v = build?.version()
        return McpToolResult.of(linkedMapOf("version" to v?.version, "commit" to v?.commit))
    }
}
