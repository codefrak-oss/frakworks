package com.codefrak.engine.mcp

import com.codefrak.engine.repos.RepoDocs

/**
 * list_private_docs and read_private_doc on [FrakworksMcp] (#1711; engine/README.md, "Private documents"): the private
 * docs the user keeps against a target repo. A Smith's take no repo: they read its own run's repo, from its container's
 * labels, so a Smith cannot read another repo's docs. The text is for the run; `bots/smith.md` forbids quoting it into
 * anything public. First mate's (#1735) take a required `repo`, a target repo, as the user's REST read does.
 */
class PrivateDocTools(private val docs: RepoDocs?, private val engine: FrakworksEngine) {
    companion object {
        const val LIST = "list_private_docs"
        const val READ = "read_private_doc"

        val LIST_TOOL = McpTool(LIST,
            "Read-only: the private documents (design docs, specs) the user keeps for your run's repo outside its git " +
                "history: each one's name, length and when it was last updated. Read one with $READ. Never quote them " +
                "into an issue, PR, commit or file of a public repo.",
            obj())

        val READ_TOOL = McpTool(READ,
            "Read-only: one private document of your run's repo, by the name $LIST gives. It comes in pages of " +
                "${FrakworksMcp.PR_CHAR_BUDGET} characters: when more remains, the page ends with a line naming the " +
                "`offset` to pass for the next page. Use what it says; never quote it into anything public.",
            obj(
                "name" to prop("string", "The doc's name, as $LIST gives it."),
                "offset" to prop("integer", "The character position the returned page starts at (default 0).", "minimum" to 0),
                required = listOf("name"),
            ))

        private val REPO_PROP = prop("string", "owner/name: the target repo whose private documents to read.")

        val MATE_LIST_TOOL = McpTool(LIST,
            "Read-only: the private documents (design docs, specs) the user keeps for a target repo outside its git " +
                "history: each one's name, length and when it was last updated. Read one with $READ. Always pass `repo`.",
            obj("repo" to REPO_PROP, required = listOf("repo")))

        val MATE_READ_TOOL = McpTool(READ,
            "Read-only: one private document of a target repo, by the name $LIST gives. It comes in pages of " +
                "${FrakworksMcp.PR_CHAR_BUDGET} characters: when more remains, the page ends with a line naming the " +
                "`offset` to pass for the next page. Always pass `repo`.",
            obj(
                "repo" to REPO_PROP,
                "name" to prop("string", "The doc's name, as $LIST gives it."),
                "offset" to prop("integer", "The character position the returned page starts at (default 0).", "minimum" to 0),
                required = listOf("repo", "name"),
            ))

    }

    fun list(args: Map<String, Any?>, caller: McpCaller.Smith): McpToolResult {
        if (args.isNotEmpty()) return McpToolResult.error("$LIST takes no arguments, not ${quoted(args.keys.toList())}")
        val (repo, store) = repoOf(caller) ?: return noRepo(caller)
        return listed(repo, store)
    }

    /** First mate's list_private_docs: `repo` required, a target repo. */
    fun mateList(args: Map<String, Any?>): McpToolResult {
        val extra = args.keys.filter { it != "repo" }
        if (extra.isNotEmpty()) return McpToolResult.error("$LIST takes only \"repo\", not ${quoted(extra)}")
        val (repo, store) = mateRepo(args).getOrElse { return (it as Refused).result }
        return listed(repo, store)
    }

    private fun listed(repo: String, store: RepoDocs): McpToolResult {
        val all = store.list(repo)
        if (all.isEmpty()) return McpToolResult("$repo has no private documents.")
        return McpToolResult("The private documents of $repo:\n\n" +
            all.joinToString("\n") { "- ${it.name} (${it.chars} characters, updated ${it.updatedAt.toInstant()})" })
    }

    fun read(args: Map<String, Any?>, caller: McpCaller.Smith): McpToolResult {
        val extra = args.keys.filter { it != "name" && it != "offset" }
        if (extra.isNotEmpty()) return McpToolResult.error("$READ takes only \"name\" and \"offset\", not ${quoted(extra)}")
        return readDoc(args) { repoOf(caller)?.let { Result.success(it) } ?: Result.failure(Refused(noRepo(caller))) }
    }

    /** First mate's read_private_doc: `repo` required, a target repo. */
    fun mateRead(args: Map<String, Any?>): McpToolResult {
        val extra = args.keys.filter { it != "repo" && it != "name" && it != "offset" }
        if (extra.isNotEmpty()) return McpToolResult.error("$READ takes only \"repo\", \"name\" and \"offset\", not ${quoted(extra)}")
        return readDoc(args) { mateRepo(args) }
    }

    private fun readDoc(args: Map<String, Any?>, where: () -> Result<Pair<String, RepoDocs>>): McpToolResult {
        val name = (args["name"] as? String)?.takeIf { RepoDocs.NAME.matches(it) }
            ?: return McpToolResult.error("not a doc name: ${json(args["name"])} ($LIST gives them)")
        val offset = when (val o = args["offset"]) {
            null -> 0
            else -> wholeNumberOf(o)?.takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt()
                ?: return McpToolResult.error("not an offset: ${json(o)} (a non-negative integer)")
        }
        val (repo, store) = where().getOrElse { return (it as Refused).result }
        val text = store.read(repo, name) ?: return McpToolResult.error("$repo has no private document $name ($LIST gives its names)")
        val page = FrakworksMcp.pageText(text, offset)
            ?: return McpToolResult.error("offset $offset is past the end: $name is ${text.length} characters long")
        return McpToolResult("The private document $name of $repo (do not quote it into anything public):\n\n$page")
    }

    /** A refusal carried through a [Result]. */
    private class Refused(val result: McpToolResult) : Exception()

    /** First mate's `repo`: required and a target repo, refused naming the target repos otherwise. */
    private fun mateRepo(args: Map<String, Any?>): Result<Pair<String, RepoDocs>> {
        val store = docs ?: return Result.failure(Refused(McpToolResult.error("the engine has no private document store")))
        val (repo, refused) = requiredTargetRepo(engine, args)
        if (refused != null) return Result.failure(Refused(refused))
        return Result.success(repo!! to store)
    }

    private fun repoOf(caller: McpCaller.Smith): Pair<String, RepoDocs>? {
        val repo = caller.caller.repo ?: return null
        return repo to (docs ?: return null)
    }

    private fun noRepo(caller: McpCaller.Smith) = McpToolResult.error(
        if (docs == null) "the engine has no private document store" else "Smith run ${caller.caller.runId} works in no target repo, so it has no private documents")
}
