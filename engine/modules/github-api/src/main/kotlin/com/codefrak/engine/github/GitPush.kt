package com.codefrak.engine.github

/** What [GitPush.mergeDefault] found: the branch already has the default branch, merges cleanly, or conflicts. */
sealed interface MergeResult {
    data object Current : MergeResult
    /** The merge commit on top of `oldHead`, as a bundle `oldHead..merge` that [GitPush.push] fast-forwards the branch to. */
    class Clean(val oldHead: String, val merge: String, val bundle: ByteArray) : MergeResult
    /** The files git could not merge; nothing was made. */
    data class Conflict(val files: List<String>) : MergeResult
}

/**
 * What a propose Smith's bundle changes (engine/README.md, "Smith results"): every path the branch
 * changes against the default branch, the doc's text on the default branch ([before], null when
 * it is not there) and at the bundle's head ([after], null when the bundle deletes it), and the pitch
 * page's text at the head ([pitch], null when there is none).
 */
data class BundleChanges(val paths: List<String>, val before: String?, val after: String?, val pitch: String? = null)

/** What [GitPush.push] left on the branch: its head, and every path the branch changes against the default branch. */
data class Pushed(val head: String, val paths: List<String>)

/**
 * The remote refused the push (`! [remote rejected]`): a token scope or a branch-protection rule, which
 * fails the same way however often it is tried. `ref` is the rejected ref, `reason` git's reason for it,
 * and the message says the cause and, where it is known, the fix (engine/README.md, "Incidents").
 */
class PushRejected(val ref: String, val reason: String, message: String) : RuntimeException(message) {
    companion object {
        private val REJECTED = Regex("""^\s*! \[remote rejected] (\S+) -> \S+ \((.*)\)\s*$""", RegexOption.MULTILINE)
        private val WORKFLOW_SCOPE = Regex("""refusing to allow a Personal Access Token to create or update workflow `?([^`\s]+)`? without `?workflow`? scope""")

        /**
         * The rejection in git push's stderr, or null when it has none (a network error, a 5xx, a
         * non-fast-forward). GitHub puts its reason in the bracket; a pre-receive hook says only "pre-receive
         * hook declined" there and gives its reason on `remote:` lines, so those are the reason then.
         */
        fun parse(stderr: String): PushRejected? {
            val rejected = REJECTED.find(stderr) ?: return null
            val ref = rejected.groupValues[1]
            val remote = stderr.lines().filter { it.startsWith("remote:") }.map { it.removePrefix("remote:").trim() }.filter { it.isNotEmpty() }
            val reason = rejected.groupValues[2].takeUnless { it.endsWith("hook declined") && remote.isNotEmpty() } ?: remote.joinToString(" ")
            val workflow = WORKFLOW_SCOPE.find(stderr)?.groupValues?.get(1)
            val message = if (workflow != null)
                "push rejected: the token lacks the workflow scope for $workflow; give the repo a token with workflow scope (PUT /repos/{owner}/{name}/token), then retry"
            else "push rejected: $ref: $reason"
            return PushRejected(ref, reason, "$message\n\n${stderr.take(2000)}".trim())
        }
    }
}

/** Pushes a Smith's commits (a git bundle) to a branch; the Smith itself never pushes. Tests use a fake. */
interface GitPush {
    companion object {
        /** What an unappliable bundle's message starts with: the rejected-result prefix `IncidentController` reruns the Smith on. */
        const val UNAPPLIABLE = "the smith's result breaks the rules: "

        /** What the message of a bundle without the branch's ref starts with: an unappliable bundle too, so a retry reruns the Smith. */
        const val MISSING_BRANCH = "${UNAPPLIABLE}the bundle cannot be applied: it has no "

        /** What the message of a bundle that does not contain the branch's head starts with: unappliable, so a retry reruns the Smith. */
        const val REWRITES = "${UNAPPLIABLE}the bundle cannot be applied: it rewrites "
    }

    /**
     * Fast-forward only: a bundle that rewrites the branch's history is refused. Returns the pushed head
     * and what the branch changes against `default`, for the engine check (engine/README.md, "Chore").
     * A push the remote rejects throws [PushRejected]; any other failure, IllegalStateException.
     */
    fun push(repo: String, branch: String, bundle: ByteArray, default: String): Pushed

    /**
     * Merges `default` into `branch` without pushing anything: the merge comes back as a bundle, which
     * [push] then pushes like any round, before its engine check and reviews (engine/README.md, "Chore").
     * `smith/` branches only.
     */
    fun mergeDefault(repo: String, branch: String, default: String): MergeResult

    /**
     * What `branch` on the remote has over `default`, as a bundle of `refs/heads/<branch>`: the pushed head bundled
     * again for the reviews of a round that committed nothing and kept no earlier bundle (#1537). `smith/` branches only.
     */
    fun headBundle(repo: String, branch: String, default: String): ByteArray

    /**
     * [headBundle] when `branch` is on the remote and has commits `default` has not, else null: a work round re-run on a
     * branch that already holds its commits (#1722), whose `done` then has those commits reviewed. `smith/` branches only.
     */
    fun aheadBundle(repo: String, branch: String, default: String): ByteArray?

    /**
     * What `bundle` on `branch` changes against `default`, `path` before and after, and `pitch` (when named) at
     * the head; pushes nothing. `smith/` branches only.
     */
    fun proposalChanges(repo: String, branch: String, default: String, bundle: ByteArray, path: String, pitch: String?): BundleChanges

    /**
     * One commit by the engine on top of `branch` that sets `path` to `edit` of its text at the head, pushed
     * fast-forward (engine/README.md, "Proposal": the decision log). Returns the new head, or null when
     * `edit` changed nothing and nothing was made. `smith/` branches only.
     */
    fun commitEdit(repo: String, branch: String, path: String, message: String, edit: (String) -> String): String?

    /**
     * One commit by the engine on top of `branch`, the repo's default branch, adding or replacing `files`
     * (path to text), pushed fast-forward (engine/README.md, "Research": `save`). Returns the new head. The
     * one write the engine makes outside `smith/` branches; a branch that is not `default` is refused.
     */
    fun commitFiles(repo: String, branch: String, default: String, files: Map<String, String>, message: String): String
}
