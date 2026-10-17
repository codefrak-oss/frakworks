package com.codefrak.engine.mcp

import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.github.PushRejected
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * A work Smith's push_chunk (#100043): its work-in-progress commits, bundled in the sandbox (`git bundle create
 * <START>..<branch>`) and passed base64, pushed to its `smith/issue-<n>` branch mid-run, so a live parent sees the
 * code before the run ends. The push is [GitPush.push], the one `open/update pr` makes: fast-forward only, a bundle
 * that rewrites pushed commits refused (`CliGitPush.refuseRewrite`), a bundle above [MAX_BYTES] refused before it is
 * decoded. After the push an [EngineEvent.Push] for `refs/heads/smith/issue-<n>` goes on the bus, so the parent's
 * await_children ([ChildWatch]) wakes as a `push` whether or not GitHub's webhook delivers one; its `before` is empty,
 * since the engine does not read the branch's old head.
 *
 * The push is outside every flow step: no engine check wait, security review, review run or PR open or update
 * follows it. Those run once, on the run's done push, as today (engine/docs/chore.md, "open/update pr"); the final
 * `commits.bundle` from `START` fast-forwards over the chunks. Listed to a run whose mode is `work` on a ticket only
 * ([listed]); a call from any other run, or a job run, is refused naming the run.
 */
class PushChunkTool(private val engine: FrakworksEngine, private val git: GitPush?, private val pulls: GitHubPulls?,
                    private val events: EngineEvents?) {
    private val log = LoggerFactory.getLogger(PushChunkTool::class.java)

    companion object {
        const val NAME = "push_chunk"
        /** A run whose mode this is may push chunks. */
        const val WORK_MODE = "work"
        /** The largest bundle a chunk may be, decoded. */
        const val MAX_BYTES = 32L * 1024 * 1024
        /** The longest base64 text [MAX_BYTES] decodes from: four characters per three bytes, padded. */
        const val MAX_CHARS = (MAX_BYTES + 2) / 3 * 4
        /** The longest `message` kept. */
        const val MESSAGE_MAX = 500

        val TOOL = McpTool(NAME,
            "Work runs only: push the commits you have so far to your smith/issue-<n> branch now, mid-run, so a live parent " +
                "(or a reader of the branch) sees them before your run ends. Make the bundle with " +
                "`git bundle create /tmp/chunk.bundle <START>..HEAD` (START as your prompt names it) and pass it as " +
                "`base64 -w0 /tmp/chunk.bundle`. The push is fast-forward only, as the engine's done push: a bundle that " +
                "rewrites commits already pushed, or one above 32 MiB, is refused. Nothing else runs on a chunk (no engine " +
                "check, review or PR): the reviews run once, on your result's commits.bundle, which must contain the chunks. " +
                "Answers {head, paths}: the pushed head and every path the branch changes against the default branch.",
            obj(
                "bundle" to prop("string", "The git bundle of <START>..HEAD on your branch, base64."),
                "message" to prop("string", "What the chunk is, for the engine's log; at most $MESSAGE_MAX characters."),
                required = listOf("bundle"),
            ))
    }

    /** Whether [caller]'s run is listed the tool: a `work` run on a ticket, never a job run. */
    fun listed(caller: McpCaller.Smith): Boolean = refusal(caller) == null

    /** Why [caller] may not push a chunk, or null when it may. */
    private fun refusal(caller: McpCaller.Smith): String? {
        val c = caller.caller
        if (c.jobId != null || c.issue == null) return "run ${c.runId} is a job run: push_chunk is a work run's, on its ticket's branch"
        if (c.repo == null) return "no run repo for run ${c.runId}: push_chunk pushes to a Smith's run's repo"
        val mode = engine.smithRun(c.runId)?.run?.phase ?: return "run ${c.runId} is not a Smith run the engine knows"
        if (mode != WORK_MODE) return "run ${c.runId} is a $mode run: push_chunk is a work run's, on its ticket's branch"
        return null
    }

    fun call(args: Map<String, Any?>, caller: McpCaller.Smith): McpToolResult {
        refusal(caller)?.let { return McpToolResult.error(it) }
        refuseOthers(NAME, args, listOf("bundle", "message"))?.let { return it }
        val git = git ?: return McpToolResult.error("this engine has no git push: push_chunk cannot push")
        val pulls = pulls ?: return McpToolResult.error("this engine reads no GitHub repos: push_chunk cannot find the default branch")
        val text = args["bundle"] as? String
        if (text.isNullOrBlank()) return McpToolResult.error("not a bundle: ${json(args["bundle"])} (\"bundle\", the git bundle as a non-empty base64 string)")
        val message = args["message"]
        if (message != null && (message !is String || message.length > MESSAGE_MAX))
            return McpToolResult.error("not a message: a string of at most $MESSAGE_MAX characters")
        val encoded = text.filterNot { it.isWhitespace() }
        if (encoded.length > MAX_CHARS) return McpToolResult.error(tooLarge(encoded.length.toLong() / 4 * 3))
        val bundle = try { Base64.getDecoder().decode(encoded) } catch (e: IllegalArgumentException) {
            return McpToolResult.error("not a bundle: \"bundle\" is not base64 (${e.message})")
        }
        if (bundle.size > MAX_BYTES) return McpToolResult.error(tooLarge(bundle.size.toLong()))
        val c = caller.caller
        val repo = c.repo!!
        val branch = "smith/issue-${c.issue}"
        val pushed = try {
            git.push(repo, branch, bundle, pulls.defaultBranch(repo))
        } catch (e: PushRejected) {
            return McpToolResult.error("push_chunk: ${e.message}")
        } catch (e: IllegalStateException) {
            val why = e.message ?: "git failed"
            return McpToolResult.error(
                if (why.startsWith(GitPush.REWRITES)) "push_chunk refused: the bundle rewrites commits already pushed to $branch; " +
                    "a chunk fast-forwards the branch only (bundle <START>..HEAD on top of what is pushed): ${why.removePrefix(GitPush.UNAPPLIABLE)}"
                else "push_chunk failed: ${why.removePrefix(GitPush.UNAPPLIABLE)}")
        }
        val ref = "refs/heads/$branch"
        log.info("chunk pushed by run {} to {} of {}: {}{}", c.runId, branch, repo, pushed.head, (message as? String)?.let { " ($it)" } ?: "")
        events?.publish(EngineEvent.Push(repo, ref, "", pushed.head, default = false))
        return McpToolResult.of(linkedMapOf("head" to pushed.head, "paths" to pushed.paths))
    }

    private fun tooLarge(bytes: Long) = "push_chunk refused: the bundle is about ${bytes / (1024 * 1024)} MiB, above the ${MAX_BYTES / (1024 * 1024)} MiB a chunk may be; " +
        "push smaller chunks (bundle fewer commits, or leave large files out)"
}
