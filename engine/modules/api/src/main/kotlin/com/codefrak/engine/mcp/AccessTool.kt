package com.codefrak.engine.mcp

import com.codefrak.engine.repos.LOGIN

/**
 * read_access, add_access and remove_access (#1636), on [FrakworksMcp] for First mate only: GET, POST and
 * DELETE /access, in-process ([FrakworksEngine.access] and on). The writes are `by` the caller's login, and
 * remove_access refuses that login itself, so First mate cannot lock the user out.
 */
class AccessTool(private val engine: FrakworksEngine) {
    companion object {
        /** First mate's access tools: a Smith's call to one is refused naming its run. */
        val NAMES = listOf("read_access", "add_access", "remove_access")

        private val loginProp = mapOf("type" to "string", "description" to "A GitHub login, e.g. octocat (no @).")

        val TOOLS: List<McpTool> = listOf(
            McpTool("read_access",
                "Read-only: the engine's trusted logins, as GET /access lists them (one engine-wide list: a login on it counts " +
                    "on every target repo), and the history of every grant and revoke, oldest first, with who made it and when.",
                obj()),
            McpTool("add_access",
                "Make a GitHub login trusted on the engine (POST /access), when the user asks to, recorded as by the user's " +
                    "login. Says whether it was added or was already there (then nothing changes).",
                obj("login" to loginProp, required = listOf("login"))),
            McpTool("remove_access",
                "Stop trusting a GitHub login (DELETE /access/<login>), when the user asks to, recorded as by the user's " +
                    "login. Refused for the user's own login: that would lock them out.",
                obj("login" to loginProp, required = listOf("login"))),
        )

        private fun noLogin(what: String) =
            McpToolResult.error("a GitHub login is needed to $what (a bearer token the engine issued, `Authorization: Bearer frk_...`)")

        /** `login` from the arguments, as POST /access takes it, else the error to return. */
        private fun loginArg(args: Map<String, Any?>): Pair<String?, McpToolResult?> {
            val login = args["login"] as? String
            if (login == null || !LOGIN.matches(login)) return null to McpToolResult.error("login is a GitHub login (letters, digits and dashes, at most 39)")
            return login to null
        }
    }

    fun call(tool: String, args: Map<String, Any?>, caller: McpCaller.Nobody): McpToolResult = when (tool) {
        "read_access" -> readAccess(args)
        "add_access" -> addAccess(caller, args)
        else -> removeAccess(caller, args)
    }

    private fun readAccess(args: Map<String, Any?>): McpToolResult {
        refuseOthers("read_access", args, emptyList())?.let { return it }
        val view = engine.access()
        return McpToolResult.of(linkedMapOf(
            "logins" to view.logins.map { it.login },
            "history" to view.history.map {
                linkedMapOf("changedAt" to iso(it.changedAt), "change" to it.change,
                    "login" to it.login, "changedBy" to it.changedBy)
            },
        ))
    }

    private fun addAccess(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        refuseOthers("add_access", args, listOf("login"))?.let { return it }
        val (target, bad) = loginArg(args)
        if (target == null) return bad!!
        val by = login(caller) ?: return noLogin("add a trusted login")
        return McpToolResult.of(if (engine.addAccess(target, by)) linkedMapOf("ok" to true, "login" to target, "outcome" to "added")
            else linkedMapOf("ok" to true, "login" to target, "outcome" to "already there", "note" to "$target was already a trusted login; nothing was changed"))
    }

    private fun removeAccess(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        refuseOthers("remove_access", args, listOf("login"))?.let { return it }
        val (target, bad) = loginArg(args)
        if (target == null) return bad!!
        val by = login(caller) ?: return noLogin("remove a trusted login")
        if (target.equals(by, ignoreCase = true))
            return McpToolResult.error("remove_access refuses your own login ($by): that would lock you out; nothing was changed")
        return if (engine.removeAccess(target, by)) McpToolResult.of(linkedMapOf("ok" to true, "login" to target, "outcome" to "removed"))
            else McpToolResult.error("$target is not a trusted login; nothing was changed")
    }
}
