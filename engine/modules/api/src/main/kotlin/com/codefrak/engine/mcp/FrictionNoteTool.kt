package com.codefrak.engine.mcp

import com.codefrak.engine.friction.FrictionInput
import com.codefrak.engine.friction.FrictionNote
import com.codefrak.engine.friction.FrictionLog
import com.codefrak.engine.friction.record
import com.codefrak.engine.friction.recordFirstMate

/**
 * note_friction (#876, #968), on [FrakworksMcp] for both roles, built like [TicketWriteTools]/[IntakeTools]: a
 * Smith's `({ kind, summary, evidence?, severity? })` writes one `as-you-go` row stamped from its run, through
 * [friction] alone, never [FrakworksEngine], so a note writes nothing to GitHub and puts nothing in front of the
 * user; First mate's takes the same four and an optional `repo`, stamped `first-mate` with the caller's login, no
 * ticket and no run.
 */
class FrictionNoteTool(private val friction: FrictionLog, private val engine: FrakworksEngine) {
    companion object {
        const val NAME = "note_friction"

        /** First mate's schema: the Smith's four plus the optional target repo the friction was met in. */
        val TOOL = McpTool(NAME,
            "Note friction and carry on: something you lacked that shaped your answer (a tool you do not have, a " +
                "repo, file, ticket, transcript or engine state you could not read, a tool call that failed or was " +
                "refused, an instruction you had to read twice). The engine keeps it in a table with the user's login " +
                "and the repo you pass; nothing goes to GitHub. A ticket that fixes it is still submit_ask's.",
            obj(
                "kind" to prop("string", "What it is.", "enum" to FrictionInput.KINDS),
                "summary" to prop("string", "What constrained the answer, in a sentence or two.", "maxLength" to FrictionInput.MAX_SUMMARY),
                "evidence" to prop("string", "The exact tool, error text or missing thing.", "maxLength" to FrictionInput.MAX_EVIDENCE),
                "severity" to prop("string", "How much it cost the answer.", "enum" to FrictionInput.SEVERITIES),
                "repo" to prop("string", "owner/name: the target repo the friction was met in (the selected repo), when there is one."),
                required = listOf("kind", "summary"),
            ))

        /** A Smith's schema: no `repo`, a run already has its own. */
        val SMITH_TOOL = McpTool(NAME,
            "Note friction and carry on: something that slowed you down, surprised you or looks wrong (a bug, bad " +
                "design, confusing docs, awkward tooling) but needs no ticket of its own and no work now. The engine " +
                "keeps it in a table with your run and ticket; nothing goes to GitHub or to the user. Out-of-scope work " +
                "that deserves a ticket is submit_ask's.",
            obj(
                "kind" to prop("string", "What it is.", "enum" to FrictionInput.KINDS),
                "summary" to prop("string", "What slowed you down or looks wrong, in a sentence or two.", "maxLength" to FrictionInput.MAX_SUMMARY),
                "evidence" to prop("string", "The exact error, path, command or workaround.", "maxLength" to FrictionInput.MAX_EVIDENCE),
                "severity" to prop("string", "How much it cost you.", "enum" to FrictionInput.SEVERITIES),
                required = listOf("kind", "summary"),
            ))
    }

    /** A Smith's note_friction: one `as-you-go` row stamped from the caller's container labels; never [engine]. */
    fun call(args: Map<String, Any?>, caller: McpCaller.Smith): McpToolResult {
        val input = when (val p = FrictionInput.parse(args)) {
            is FrictionInput.Parsed.Bad -> return McpToolResult.error(p.why)
            is FrictionInput.Parsed.Ok -> p.input
        }
        val run = caller.caller
        // A job sandbox (mode job) has no ticket: its note carries a null issue and its job id.
        val note = try { friction.record(run.repo, run.issue, run.runId, input, FrictionNote.AS_YOU_GO, jobId = run.jobId) } catch (e: Exception) {
            return McpToolResult.error("the note was not recorded: ${e.message}; carry on with your work")
        }
        return McpToolResult.of(linkedMapOf("ok" to true, "id" to note.id))
    }

    /**
     * First mate's note_friction (#968): one `first-mate` row with the caller's login and the target repo it named, if any,
     * and no ticket or run; never [engine]. Refused, recording nothing, without a login.
     */
    fun call(args: Map<String, Any?>, caller: McpCaller.Nobody): McpToolResult {
        refuseOthers(NAME, args, FrictionInput.ARGS + "repo")?.let { return it }
        val input = when (val p = FrictionInput.parse(args - "repo")) {
            is FrictionInput.Parsed.Bad -> return McpToolResult.error(p.why)
            is FrictionInput.Parsed.Ok -> p.input
        }
        if (given(args, "repo") && (args["repo"] as? String)?.let { FrakworksMcp.REPO_FULL.matches(it) } != true)
            return McpToolResult.error("not an owner/name repo: ${json(args["repo"])}")
        val by = login(caller) ?: return noLogin("note")
        val repo = (args["repo"] as String?)?.let { asked -> engine.targetRepo(asked) ?: return McpToolResult.error("$asked is not a target repo") }
        val note = try { friction.recordFirstMate(by, repo, input) } catch (e: Exception) {
            return McpToolResult.error("the note was not recorded: ${e.message}; carry on with your answer")
        }
        return McpToolResult.of(linkedMapOf("ok" to true, "id" to note.id))
    }
}
