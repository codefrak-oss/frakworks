package com.codefrak.engine.mcp

import com.codefrak.engine.repos.AddRepo
import com.codefrak.engine.repos.CHILD_MODES
import com.codefrak.engine.repos.FULL_NAME
import com.codefrak.engine.repos.TargetRepo
import com.codefrak.engine.repos.scanMinutes

/**
 * read_repos, add_repo, edit_repo and remove_repo (#1021), on [FrakworksMcp] for First mate only: GET, POST, PATCH
 * and DELETE /repos, in-process ([FrakworksEngine.repos] and on). No tool sets or clears a repo's token or webhook
 * secret: a secret passed through a tool call lands in First mate's context.
 */
class RepoTool(private val engine: FrakworksEngine) {
    companion object {
        /** The `PATCH /repos/{owner}/{name}` fields edit_repo takes; add_repo takes the first four. */
        val REPO_FIELDS = listOf("ciCheck", "ciPaths", "securityReview", "scanIntervalMinutes", "doctorBugs", "engineDefects", "frictionBugs", "docsFirst", "childMode")
        private val ADD_FIELDS = REPO_FIELDS.take(4)
        /** First mate's repo tools: a Smith's call to one is refused naming its run. */
        val NAMES = listOf("read_repos", "add_repo", "edit_repo", "remove_repo")

        private val repoProps: Array<Pair<String, Any?>> = arrayOf(
            "ciCheck" to mapOf("type" to listOf("string", "null"), "description" to "The engine check: the check run's name the engine waits for on a ticket's PR; null clears it (no check)."),
            "ciPaths" to mapOf("type" to listOf("array", "null"), "items" to mapOf("type" to "string"),
                "description" to "Path prefixes (no commas) a PR must touch for the check to be waited on; empty or null: every path."),
            "securityReview" to prop("boolean", "false: the repo's chores, bugs and features skip the security review."),
            "scanIntervalMinutes" to prop("integer", "How often the engine scans the repo on its own, in whole minutes.", "minimum" to 1, "maximum" to 1440),
        )

        val TOOLS: List<McpTool> = listOf(
            McpTool("read_repos",
                "Read-only: the engine's target repos, as GET /repos lists them: each one's fullName, its engine check " +
                    "(ciCheck, ciPaths), securityReview, scanIntervalMinutes, doctorBugs, engineDefects, frictionBugs, docsFirst, childMode, publishWorkItems, whether a token and " +
                    "a webhook secret are set and since when (never the values), and scanCalls; tokenPermission (GitHub's permission of the repo " +
                    "token's account: admin, maintain, write, triage, read; null when it has no token or GitHub would not say) and " +
                    "callerPermission (yours, from your login; null without one or when GitHub would not say), both cached for the scan " +
                    "interval. Pass `repo` for that one only.",
                obj("repo" to prop("string", "Only this target repo, owner/name."))),
            McpTool("add_repo",
                "Add a target repo the engine works (POST /repos), when the user asks to. Says whether it was added or was " +
                    "already there (then nothing changes). doctorBugs, engineDefects and frictionBugs are off on a new repo: turn them on " +
                    "with edit_repo. Its token and webhook secret are set in the desktop's Config > Repos, never here.",
                obj("repo" to prop("string", "The repo to add, owner/name."), *repoProps, required = listOf("repo"))),
            McpTool("edit_repo",
                "Change a target repo's settings (PATCH /repos/<owner>/<name>), when the user asks to: a key given sets it " +
                    "(null clears ciCheck or ciPaths), a key left out stays as it is; give at least one. engineDefects needs " +
                    "doctorBugs on, and only one repo can have it; frictionBugs is independent of both. Returns the repo as read_repos shows it. Not its token or " +
                    "webhook secret: those are set in the desktop's Config > Repos.",
                obj("repo" to prop("string", "The target repo to change, owner/name."), *repoProps,
                    "doctorBugs" to prop("boolean", "The doctor files engine defects it sees on the repo's stuck tickets; false also clears engineDefects."),
                    "engineDefects" to prop("boolean", "This is the one repo those engine defects are filed in; needs doctorBugs."),
                    "frictionBugs" to prop("boolean", "The engine files one bug per group of friction notes whose note Jev marks as two things, in the note's repo; starts authorized runs."),
                    "docsFirst" to prop("boolean", "The repo is mostly documentation: its tickets default to the light docs path (a chore at a low tier)."),
                    "childMode" to mapOf("type" to "string", "enum" to CHILD_MODES,
                        "description" to "How a feature's split is cut and its children worked: serial (one after another, a replan between), parallel (children that can run at the same time) or live (parallel, with the parent's session running on)."),
                    required = listOf("repo"))),
            McpTool("remove_repo",
                "Remove a target repo (DELETE /repos/<owner>/<name>), when the user asks to: the engine stops working it, " +
                    "and its grants and token go with it. Refused while a flow runs on it, naming each flow.",
                obj("repo" to prop("string", "The target repo to remove, owner/name."), required = listOf("repo"))),
        )

        /** `repo` from a repo tool's arguments: owner/name, as POST /repos takes it, else the error to return. */
        private fun repoArg(args: Map<String, Any?>): Pair<String?, McpToolResult?> {
            if (!given(args, "repo")) return null to noRepo()
            val repo = args["repo"] as? String
            if (repo == null || !FULL_NAME.matches(repo)) return null to McpToolResult.error("repo is owner/name")
            return repo to null
        }

        /** A repo write needs the user's login, though the API takes none: no login, no header, so not First mate's desktop. */
        private fun noRepoLogin(what: String) =
            McpToolResult.error("a GitHub login is needed to $what (a bearer token the engine issued, `Authorization: Bearer frk_...`)")

        /** GET /repos' row: the tokens and the webhook secret only as set or not, and since when. */
        private fun repoRow(r: TargetRepo) = linkedMapOf(
            "fullName" to r.fullName, "addedAt" to iso(r.addedAt),
            "ciCheck" to r.ciCheck, "ciPaths" to r.ciPaths, "securityReview" to r.securityReview, "scanIntervalMinutes" to r.scanIntervalMinutes,
            "doctorBugs" to r.doctorBugs, "engineDefects" to r.engineDefects, "frictionBugs" to r.frictionBugs, "docsFirst" to r.docsFirst, "childMode" to r.childMode,
            "publishWorkItems" to r.publishWorkItems,
            "hasToken" to r.hasToken, "tokenPermission" to r.tokenPermission, "callerPermission" to r.callerPermission, "tokenSetAt" to iso(r.tokenSetAt),
            "hasWebhookSecret" to r.hasWebhookSecret, "webhookSecretSetAt" to iso(r.webhookSecretSetAt),
            "hasSmithToken" to r.hasSmithToken, "smithTokenSetAt" to iso(r.smithTokenSetAt),
            "scanCalls" to r.scanCalls?.let { linkedMapOf("listing" to it.listing, "authorized" to it.authorized, "deferred" to it.deferred, "checks" to it.checks) },
            "scanCallsPerScan" to r.scanCallsPerScan,
        )
    }

    fun call(tool: String, args: Map<String, Any?>, caller: McpCaller.Nobody): McpToolResult = when (tool) {
        "read_repos" -> readRepos(caller, args)
        "add_repo" -> addRepo(caller, args)
        "edit_repo" -> editRepo(caller, args)
        else -> removeRepo(caller, args)
    }

    private fun readRepos(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        refuseOthers("read_repos", args, listOf("repo"))?.let { return it }
        val asked = if (given(args, "repo")) repoArg(args).let { (repo, bad) -> repo ?: return bad!! } else null
        val rows = engine.repos(login(caller)).filter { asked == null || it.fullName.equals(asked, ignoreCase = true) }
        if (asked != null && rows.isEmpty()) return McpToolResult.error("$asked is not a target repo")
        return McpToolResult.of(linkedMapOf("count" to rows.size, "repos" to rows.map(::repoRow)))
    }

    /** POST /repos: its own checks, its 400s and its `added` event; a key POST /repos does not take is refused here, not ignored. */
    private fun addRepo(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        refuseOthers("add_repo", args, listOf("repo") + ADD_FIELDS)?.let { return it }
        val (repo, bad) = repoArg(args)
        if (repo == null) return bad!!
        login(caller) ?: return noRepoLogin("add a target repo")
        val check = args["ciCheck"]
        if (check != null && check !is String) return McpToolResult.error("ciCheck is a string or null")
        val paths = args["ciPaths"]
        if (paths != null && (paths !is List<*> || paths.any { it !is String })) return McpToolResult.error("ciPaths is an array of strings or null")
        val review = args["securityReview"]
        if ("securityReview" in args && review !is Boolean) return McpToolResult.error("securityReview is true or false")
        val interval = if ("scanIntervalMinutes" in args) scanMinutes(args["scanIntervalMinutes"]) else null
        @Suppress("UNCHECKED_CAST")
        val added = engine.addRepo(AddRepo(repo, check as String?, paths as List<String>?, review as Boolean? ?: true, interval), caller.request)
        return McpToolResult.of(if (added) linkedMapOf("ok" to true, "repo" to repo, "outcome" to "added")
            else linkedMapOf("ok" to true, "repo" to repo, "outcome" to "already there", "note" to "$repo was already a target repo; nothing was changed"))
    }

    /** PATCH /repos/{owner}/{name} with every key but `repo` as its body, so its checks, refusals and `changed` event are the API's. */
    private fun editRepo(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        val (asked, bad) = repoArg(args)
        if (asked == null) return bad!!
        val fields = args - "repo"
        if (fields.isEmpty()) return McpToolResult.error("edit_repo needs at least one of ${quoted(REPO_FIELDS)} besides \"repo\"")
        login(caller) ?: return noRepoLogin("edit a target repo")
        // The row's own spelling when it is one; else the API's 404 says it is not a target repo.
        val repo = engine.targetRepo(asked) ?: asked
        return McpToolResult.of(repoRow(engine.editRepo(repo, fields, caller.request)))
    }

    /** DELETE /repos/{owner}/{name}: its 409 names each running flow, its 404 says it is not a target repo. */
    private fun removeRepo(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        refuseOthers("remove_repo", args, listOf("repo"))?.let { return it }
        val (asked, bad) = repoArg(args)
        if (asked == null) return bad!!
        login(caller) ?: return noRepoLogin("remove a target repo")
        val repo = engine.targetRepo(asked) ?: asked
        engine.removeRepo(repo)
        return McpToolResult.of(linkedMapOf("ok" to true, "repo" to repo, "outcome" to "removed"))
    }
}
