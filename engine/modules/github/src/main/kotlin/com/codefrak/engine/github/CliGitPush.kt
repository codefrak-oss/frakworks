package com.codefrak.engine.github

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The git CLI in the engine image: a blobless bare clone, the bundle fetched
 * into `branch`, pushed. The token goes in as an HTTP header through git's
 * environment config, never in argv or a URL.
 */
@Component
class CliGitPush(
    private val tokens: RepoTokens,
    /** Who the engine's own commits on a repo are by: its token's account (engine/README.md, "Each repo's token"). */
    private val identity: TokenIdentity,
    @Value("\${engine.github.git-url:https://github.com}") private val gitUrl: String,
    /** The sealed deployment patterns every push is checked against ([refuseDeploymentValues]). */
    private val patterns: DeploymentPatternSource = DeploymentPatternSource.NONE,
) : GitPush {
    private val log = LoggerFactory.getLogger(CliGitPush::class.java)

    /** The changed paths come from the same clone, trees only (no blobs), as in [proposalChanges]. */
    override fun push(repo: String, branch: String, bundle: ByteArray, default: String): Pushed = withClone(repo, branch) { dir, clone ->
        val file = dir.resolve("commits.bundle").also { Files.write(it, bundle) }
        val source = verify(repo, clone, bundle, file, branch)
        refuseRewrite(clone, file, source, branch)
        refuseDeploymentValues(clone, file, source, branch, default)
        git(clone, "fetch", "--quiet", file.toString(), "$source:refs/heads/$branch")
        val pushed = run(clone, emptyMap(), "push", "--quiet", "origin", "refs/heads/$branch:refs/heads/$branch")
        if (pushed.exit != 0) PushRejected.parse(pushed.err)?.let { throw it }
        check(pushed.exit == 0) { "git push failed (${pushed.exit}): ${(pushed.err + pushed.out).take(2000)}" }
        val head = git(clone, "rev-parse", "refs/heads/$branch")
        val paths = git(clone, "diff", "--name-only", "--no-renames", "-z", "refs/heads/$default...refs/heads/$branch")
            .split('\u0000').filter { it.isNotEmpty() }
        log.info("pushed {} to {}: {}", branch, repo, head)
        Pushed(head, paths)
    }

    /**
     * `merge-base --is-ancestor` says whether the branch has `default` already; if not, `merge-tree
     * --write-tree` merges without a work tree, and `commit-tree` makes the two-parent commit as
     * the repo's token's account ([committer]). A conflict lists its files and makes nothing.
     */
    override fun mergeDefault(repo: String, branch: String, default: String): MergeResult = withClone(repo, branch) { dir, clone ->
        val ours = "refs/heads/$branch"; val theirs = "refs/heads/$default"
        val ancestor = run(clone, emptyMap(), "merge-base", "--is-ancestor", theirs, ours)
        if (ancestor.exit == 0) return@withClone MergeResult.Current
        check(ancestor.exit == 1) { "git merge-base failed (${ancestor.exit}): ${ancestor.err.take(2000)}" }
        val oldHead = git(clone, "rev-parse", ours)
        val merged = run(clone, emptyMap(), "merge-tree", "--write-tree", "--name-only", "--no-messages", ours, theirs)
        val lines = merged.out.lines().filter { it.isNotBlank() }
        if (merged.exit == 1) return@withClone MergeResult.Conflict(lines.drop(1).distinct())
        check(merged.exit == 0) { "git merge-tree failed (${merged.exit}): ${merged.err.take(2000)}" }
        val commit = run(clone, committer(repo), "commit-tree", lines.first(), "-p", oldHead, "-p", theirs, "-m", "Merge $default into $branch")
        check(commit.exit == 0) { "git commit-tree failed (${commit.exit}): ${commit.err.take(2000)}" }
        val merge = commit.out.trim()
        git(clone, "update-ref", ours, merge, oldHead)
        val file = dir.resolve("merge.bundle")
        git(clone, "bundle", "create", "--quiet", file.toString(), "$oldHead..$ours")
        log.info("merged {} into {} of {}: {}", default, branch, repo, merge)
        MergeResult.Clean(oldHead, merge, Files.readAllBytes(file))
    }

    /** The blobs the bundle needs are fetched lazily from the remote by `bundle create`. */
    override fun headBundle(repo: String, branch: String, default: String): ByteArray = withClone(repo, branch) { dir, clone ->
        val file = dir.resolve("head.bundle")
        git(clone, "bundle", "create", "--quiet", file.toString(), "refs/heads/$branch", "^refs/heads/$default")
        Files.readAllBytes(file)
    }

    override fun aheadBundle(repo: String, branch: String, default: String): ByteArray? = withClone(repo, branch) { dir, clone ->
        if (run(clone, emptyMap(), "rev-parse", "--verify", "--quiet", "refs/heads/$branch").exit != 0) return@withClone null
        if (git(clone, "rev-list", "--count", "refs/heads/$default..refs/heads/$branch").toInt() == 0) return@withClone null
        val file = dir.resolve("head.bundle")
        git(clone, "bundle", "create", "--quiet", file.toString(), "refs/heads/$branch", "^refs/heads/$default")
        Files.readAllBytes(file)
    }

    /**
     * The bundle is fetched into a scratch ref (forced: a redraft's bundle sits on the PR branch, which may
     * differ from anything local), then diffed from its merge base with `default`, with no rename detection,
     * so a file moved in from outside `docs/proposals/` shows its old path too. The blobs the texts need
     * come from the bundle or, lazily, from the remote.
     */
    override fun proposalChanges(repo: String, branch: String, default: String, bundle: ByteArray, path: String, pitch: String?): BundleChanges =
        withClone(repo, branch) { dir, clone ->
            val head = "refs/proposal/head"; val base = "refs/heads/$default"
            val paths = fetchedPaths(repo, dir, clone, branch, default, bundle, head)
            BundleChanges(paths, text(clone, base, path), text(clone, head, path), pitch?.let { text(clone, head, it) })
        }

    /**
     * Verifies `bundle`, fetches its `branch` into the scratch ref `head` (forced: the bundle may differ from
     * anything local), and lists what it changes from its merge base with `default`, with no rename detection,
     * so a moved file shows its old path too.
     */
    private fun fetchedPaths(repo: String, dir: Path, clone: Path, branch: String, default: String, bundle: ByteArray, head: String): List<String> {
        val file = dir.resolve("commits.bundle").also { Files.write(it, bundle) }
        val source = verify(repo, clone, bundle, file, branch)
        git(clone, "fetch", "--quiet", file.toString(), "+$source:$head")
        return git(clone, "diff", "--name-only", "--no-renames", "-z", "refs/heads/$default...$head").split('\u0000').filter { it.isNotEmpty() }
    }

    /**
     * `git bundle verify`, after checking the bundle's prerequisites are commits the clone has. The clone is
     * blobless, so git takes a missing prerequisite for a promisor object and asks the remote for it, which
     * answers `upload-pack: not our ref`. A blobless clone has every commit the remote's refs reach, so
     * `rev-list --all` (commits only, no blob fetched) says what is there, and a prerequisite outside it is
     * named: the Smith based its bundle on a commit the remote does not have.
     *
     * Returns the bundle's ref to fetch into `branch`: `refs/heads/<branch>` when the bundle has it, else its
     * sole head whatever its name (a Smith's own `git bundle create <base>..HEAD` records `HEAD`). Several heads
     * and none of them `branch` is refused here ([GitPush.MISSING_BRANCH]), not left to fail at the fetch. The name does not widen what is
     * pushed: the ref written is always `branch`, and scope is checked on the commits, not on the bundle's names.
     */
    private fun verify(repo: String, clone: Path, bundle: ByteArray, file: Path, branch: String): String {
        val needed = bundlePrerequisites(bundle)
        if (needed.isNotEmpty()) {
            val have = git(clone, "rev-list", "--all").lines().toSet()
            val missing = needed.filter { it !in have }
            // A rejected result (IncidentController.reruns): the sandbox and its commits are gone, so a retry reruns the Smith.
            check(missing.isEmpty()) {
                "${GitPush.UNAPPLIABLE}the bundle cannot be applied: its prerequisite commit(s) ${missing.joinToString(", ")} are not on any branch of $repo " +
                    "(the Smith's bundle starts from a commit that never reached the remote)"
            }
        }
        git(clone, "bundle", "verify", "--quiet", file.toString())
        val heads = git(clone, "bundle", "list-heads", file.toString()).lines().filter { it.isNotBlank() }.map { it.substringAfter(' ') }.distinct()
        val wanted = "refs/heads/$branch"
        if (wanted in heads) return wanted
        check(heads.size == 1) {
            // A rejected result: pushing the same bundle again can only fail the same way.
            "${GitPush.MISSING_BRANCH}$wanted (it has ${heads.joinToString(", ").ifEmpty { "no refs" }}; " +
                "the Smith's bundle was not made from its branch)"
        }
        return heads.single()
    }

    /**
     * A round after the first push finds `branch` in the clone: the bundle's `source` must contain its head, else the
     * fetch is a non-fast-forward, which `git fetch --quiet` refuses with no output at all (#2165). The bundle goes into
     * a scratch ref first so `merge-base --is-ancestor` can see it; the branch itself is never force-updated.
     */
    private fun refuseRewrite(clone: Path, file: Path, source: String, branch: String) {
        val ref = "refs/heads/$branch"
        if (run(clone, emptyMap(), "rev-parse", "--verify", "--quiet", ref).exit != 0) return
        val scratch = "refs/bundle/head"
        git(clone, "fetch", "--quiet", file.toString(), "+$source:$scratch")
        val ancestor = run(clone, emptyMap(), "merge-base", "--is-ancestor", ref, scratch)
        if (ancestor.exit == 0) return
        check(ancestor.exit == 1) { "git merge-base failed (${ancestor.exit}): ${ancestor.err.take(2000)}" }
        // A rejected result: the sandbox is gone, pushing the same bundle again can only fail the same way.
        error("${GitPush.REWRITES}$branch (the branch is at ${git(clone, "rev-parse", ref)}; the bundle's $source at " +
            "${git(clone, "rev-parse", scratch)} does not contain it; the Smith's bundle was not made on top of the branch)")
    }

    /**
     * The gate for every Smith push, chunk or done (#100115): the lines the bundle's commits add since what is pushed
     * (the branch, or `default` on a first push) are checked against the sealed deployment patterns, and a hit refuses
     * the push naming file, line and commit only, never the pattern or the line. No pattern set: nothing is checked.
     */
    private fun refuseDeploymentValues(clone: Path, file: Path, source: String, branch: String, default: String) {
        val rules = patterns.rules() ?: return
        val scratch = "refs/bundle/scan"
        git(clone, "fetch", "--quiet", file.toString(), "+$source:$scratch")
        val ref = "refs/heads/$branch"
        val base = if (run(clone, emptyMap(), "rev-parse", "--verify", "--quiet", ref).exit == 0) ref else "refs/heads/$default"
        val log = git(clone, "log", "-p", "-U0", "--no-color", "--no-renames", "--no-ext-diff", "--format=${DeploymentValueScan.FORMAT}", "$base..$scratch")
        val hits = DeploymentValueScan.scan(log, rules)
        if (hits.isEmpty()) return
        // A rejected result: the same bundle can only fail the same way.
        error("${GitPush.UNAPPLIABLE}the bundle carries deployment values (a sealed deployment pattern matched) at " +
            "${hits.take(20).joinToString(", ")}${if (hits.size > 20) " and ${hits.size - 20} more" else ""}; " +
            "remove them from the commits (rewrite the unpushed ones) and bundle again")
    }

    /**
     * The new blob goes in with `hash-object -w`, the tree through a scratch index (`read-tree` of the head,
     * `update-index --cacheinfo`, `write-tree`), so a bare clone needs no work tree. The commit is by
     * the repo's token's account ([committer]); a plain push is fast-forward only, so a branch moved meanwhile refuses it.
     */
    override fun commitEdit(repo: String, branch: String, path: String, message: String, edit: (String) -> String): String? =
        withClone(repo, branch) { dir, clone ->
            val ref = "refs/heads/$branch"
            val oldHead = git(clone, "rev-parse", ref)
            val mode = git(clone, "ls-tree", ref, "--", path).substringBefore(' ')
            check(mode.isNotEmpty()) { "$path is not on $branch" }
            // Untrimmed, unlike text(): the edit keeps the file's last newline.
            val read = run(clone, emptyMap(), "cat-file", "blob", "$ref:$path")
            check(read.exit == 0) { "git cat-file failed (${read.exit}): ${read.err.take(2000)}" }
            val before = read.out
            val after = edit(before)
            if (after == before) return@withClone null
            val file = dir.resolve("edit").also { Files.writeString(it, after) }
            val blob = git(clone, "hash-object", "-w", file.toString())
            commitTree(dir, clone, repo, branch, oldHead, path, message) { index ->
                gitEnv(clone, index, "update-index", "--cacheinfo", "$mode,$blob,$path")
            }
        }

    /** As [commitEdit], the blobs written with `hash-object -w` and the tree through a scratch index; new paths in regular mode. */
    override fun commitFiles(repo: String, branch: String, default: String, files: Map<String, String>, message: String): String {
        require(branch == default) { "the engine commits files to the default branch only, not $branch" }
        return withClone(repo, branch, allowDefault = true) { dir, clone ->
            val ref = "refs/heads/$branch"
            val oldHead = git(clone, "rev-parse", ref)
            commitTree(dir, clone, repo, branch, oldHead, files.keys, message) { index ->
                files.entries.forEachIndexed { i, (path, text) ->
                    val file = dir.resolve("file-$i").also { Files.writeString(it, text) }
                    val blob = git(clone, "hash-object", "-w", file.toString())
                    gitEnv(clone, index, "update-index", "--add", "--cacheinfo", "100644,$blob,$path")
                }
            }
        }
    }

    /**
     * The scratch-index commit dance [commitEdit] and [commitFiles] share: `read-tree` of `oldHead` into a
     * `GIT_INDEX_FILE` scratch index, the caller's blob writes via `writeBlobs`, `write-tree`, `commit-tree`
     * as the repo's token's account ([committer]), a fast-forward push, and the commit's log line. `what`
     * is the one piece that differs between callers and is logged as-is (commitEdit's path, commitFiles'
     * file names).
     */
    private fun commitTree(
        dir: Path,
        clone: Path,
        repo: String,
        branch: String,
        oldHead: String,
        what: Any,
        message: String,
        writeBlobs: (index: Map<String, String>) -> Unit,
    ): String {
        val ref = "refs/heads/$branch"
        val index = mapOf("GIT_INDEX_FILE" to dir.resolve("index").toString())
        gitEnv(clone, index, "read-tree", oldHead)
        writeBlobs(index)
        val tree = gitEnv(clone, index, "write-tree")
        val commit = gitEnv(clone, committer(repo), "commit-tree", tree, "-p", oldHead, "-m", message)
        git(clone, "push", "--quiet", "origin", "$commit:$ref")
        log.info("committed {} to {} of {}: {}", what, branch, repo, commit)
        return commit
    }

    /**
     * `path` at `ref`, or null when it is not there. Whether it is there comes from the tree alone, so a
     * blob that fails to fetch is an error, never a "new doc" that would skip the decision-log rule.
     */
    private fun text(clone: Path, ref: String, path: String): String? {
        if (git(clone, "ls-tree", "--name-only", ref, "--", path).isEmpty()) return null
        return git(clone, "cat-file", "blob", "$ref:$path")
    }

    /** A fresh blobless bare clone of `repo` in a temp dir, removed after `block`; `smith/` branches only, unless `allowDefault` ([commitFiles]). */
    private fun <T> withClone(repo: String, branch: String, allowDefault: Boolean = false, block: (dir: Path, clone: Path) -> T): T {
        require(allowDefault || branch.startsWith("smith/")) { "the engine pushes only smith/ branches, not $branch" }
        // The repo's token, for every git command of this clone: set here, read by [run], cleared with the clone.
        val previous = authToken.get()
        authToken.set(tokens.forRepo(repo))
        val dir = Files.createTempDirectory("push-")
        try {
            val clone = dir.resolve("repo.git")
            git(dir, "clone", "--quiet", "--bare", "--filter=blob:none", "$gitUrl/$repo.git", clone.toString())
            return block(dir, clone)
        } finally {
            dir.toFile().deleteRecursively()
            if (previous == null) authToken.remove() else authToken.set(previous)
        }
    }

    /** The token of the repo whose clone this thread is working in ([withClone]); a git command outside one has none. */
    private val authToken = ThreadLocal<String>()

    private class Ran(val exit: Int, val out: String, val err: String)

    /** Stdout alone, so a command's answer (a tree, a commit) is not mixed with its warnings. */
    private fun git(cwd: Path, vararg args: String): String = gitEnv(cwd, emptyMap(), *args)

    private fun gitEnv(cwd: Path, env: Map<String, String>, vararg args: String): String {
        val ran = run(cwd, env, *args)
        check(ran.exit == 0) {
            val said = (ran.err + ran.out).take(2000)
            // `--quiet` can make a refusal silent: name the whole command rather than leave the message blank.
            if (said.isBlank()) "git ${args[0]} failed (${ran.exit}) with no output: git ${args.joinToString(" ")}"
            else "git ${args[0]} failed (${ran.exit}): $said"
        }
        return ran.out.trim()
    }

    private fun run(cwd: Path, env: Map<String, String>, vararg args: String): Ran {
        val token = authToken.get() ?: error("git ${args.firstOrNull()} outside withClone: no repo token")
        val basic = Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray())
        val err = Files.createTempFile("git-", ".err")
        try {
            val process = ProcessBuilder(listOf("git") + args).directory(cwd.toFile()).redirectError(err.toFile()).apply {
                environment() += mapOf(
                    "GIT_TERMINAL_PROMPT" to "0",
                    "GIT_CONFIG_COUNT" to "1",
                    "GIT_CONFIG_KEY_0" to "http.$gitUrl/.extraheader",
                    "GIT_CONFIG_VALUE_0" to "Authorization: Basic $basic",
                ) + env
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(10, TimeUnit.MINUTES)) { "git ${args[0]} timed out" }
            return Ran(process.exitValue(), output, Files.readString(err))
        } finally {
            Files.deleteIfExists(err)
        }
    }

    /** git's author and committer for a commit the engine makes on `repo`: [TokenIdentity.commitIdentity]. */
    private fun committer(repo: String): Map<String, String> = identity.commitIdentity(repo).let {
        mapOf("GIT_AUTHOR_NAME" to it.name, "GIT_AUTHOR_EMAIL" to it.email, "GIT_COMMITTER_NAME" to it.name, "GIT_COMMITTER_EMAIL" to it.email)
    }
}
