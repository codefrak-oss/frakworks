package com.codefrak.engine.github

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CliGitPush against a real git remote on disk (`file://`), with bundles made the way run-smith.sh makes them. */
class CliGitPushTest {
    private val root = Files.createTempDirectory("push-test-").toFile()
    private val remote = File(root, "acme/widgets.git")
    private val tokens = RepoTokens { "unused-by-file-remotes" }
    /** The token's account, as the fake's `GET /user` says: the engine's commits are by it. */
    private val push = CliGitPush(tokens, GitHubTokenIdentity(FakeIssueDetails(), tokens), gitUrl = "file://${root.absolutePath}")

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "init.defaultBranch=main") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.toList()}: $out" }
        return out.trim()
    }

    /** A Smith's round: a shallow clone of `from`, one commit on smith/issue-5, bundled since where it started. */
    private fun smithRound(from: String, file: String): ByteArray {
        val work = Files.createTempDirectory(root.toPath(), "smith-").toFile()
        git(work, "clone", "--quiet", "--depth", "50", "--branch", from, "file://${remote.absolutePath}", "repo")
        val repo = File(work, "repo")
        git(repo, "checkout", "--quiet", "-B", "smith/issue-5")
        val base = git(repo, "rev-parse", "HEAD")
        File(repo, file).apply { parentFile.mkdirs() }.writeText("changed\n")
        git(repo, "add", file); git(repo, "commit", "--quiet", "-m", "work on $file")
        val bundle = File(work, "commits.bundle")
        git(repo, "bundle", "create", "--quiet", bundle.absolutePath, "$base..smith/issue-5")
        return bundle.readBytes()
    }

    @Test
    fun `round 1 creates the branch, round 2 fast-forwards it, a rewrite and other branches are refused`() {
        remote.mkdirs(); git(remote, "init", "--quiet", "--bare")
        val seed = File(root, "seed").apply { mkdirs() }
        git(seed, "init", "--quiet"); File(seed, "README.md").writeText("hi\n")
        git(seed, "add", "."); git(seed, "commit", "--quiet", "-m", "seed")
        git(seed, "push", "--quiet", "file://${remote.absolutePath}", "HEAD:main")

        val first = push.push("acme/widgets", "smith/issue-5", smithRound("main", "a.txt"), "main")
        assertEquals(listOf("a.txt"), first.paths)
        val second = push.push("acme/widgets", "smith/issue-5", smithRound("smith/issue-5", "engine/b.txt"), "main")
        assertEquals(listOf("work on engine/b.txt", "work on a.txt", "seed"), git(remote, "log", "--format=%s", "smith/issue-5").lines())
        assertEquals(git(remote, "rev-parse", "smith/issue-5"), second.head, "the pushed head, for the engine check")
        assertEquals(listOf("a.txt", "engine/b.txt"), second.paths, "every path the branch changes against main")

        // A bundle cut from main again would rewrite the branch: not a fast-forward.
        val before = git(remote, "rev-parse", "smith/issue-5")
        val rewrite = smithRound("main", "c.txt")
        val refused = assertThrows<IllegalStateException> { push.push("acme/widgets", "smith/issue-5", rewrite, "main") }.message!!
        assertTrue(refused.startsWith(GitPush.REWRITES + "smith/issue-5 (the branch is at $before; the bundle's "), refused)
        val bundleHead = String(rewrite, Charsets.ISO_8859_1).lines().drop(1).first { !it.startsWith("-") }.substringBefore(' ')
        assertTrue("at $bundleHead does not contain it" in refused, refused)
        assertEquals(before, git(remote, "rev-parse", "smith/issue-5"), "the remote's branch is untouched")
        assertThrows<IllegalArgumentException> { push.push("acme/widgets", "main", ByteArray(0), "main") }
        assertEquals(listOf("seed"), git(remote, "log", "--format=%s", "main").lines())
        root.deleteRecursively()
    }

    /** A remote with main (seed) and smith/issue-5 one commit ahead of it, changing `branchFile`. */
    private fun seeded(branchFile: String) {
        remote.mkdirs(); git(remote, "init", "--quiet", "--bare")
        val seed = File(root, "seed").apply { mkdirs() }
        git(seed, "init", "--quiet"); File(seed, "README.md").writeText("hi\n"); File(seed, "a.txt").writeText("a\n")
        git(seed, "add", "."); git(seed, "commit", "--quiet", "-m", "seed")
        git(seed, "push", "--quiet", "file://${remote.absolutePath}", "HEAD:main")
        push.push("acme/widgets", "smith/issue-5", smithRound("main", branchFile), "main")
    }

    /** One commit on main in the remote, changing `file`. */
    private fun moveMain(file: String) {
        val work = Files.createTempDirectory(root.toPath(), "main-").toFile()
        git(work, "clone", "--quiet", "--branch", "main", "file://${remote.absolutePath}", "repo")
        val repo = File(work, "repo")
        File(repo, file).writeText("main's change\n")
        git(repo, "add", file); git(repo, "commit", "--quiet", "-m", "main on $file"); git(repo, "push", "--quiet", "origin", "main")
    }

    private fun refs() = git(remote, "for-each-ref", "--format=%(refname) %(objectname)")

    @Test
    fun `a clean merge comes back as a bundle, pushes nothing, and its push fast-forwards the branch to a two-parent commit`() {
        seeded("b.txt")
        assertEquals(MergeResult.Current, push.mergeDefault("acme/widgets", "smith/issue-5", "main"), "the branch has main already")
        moveMain("c.txt")
        val branchHead = git(remote, "rev-parse", "smith/issue-5"); val mainHead = git(remote, "rev-parse", "main")
        val before = refs()

        val clean = push.mergeDefault("acme/widgets", "smith/issue-5", "main") as MergeResult.Clean

        assertEquals(before, refs(), "nothing pushed")
        assertEquals(branchHead, clean.oldHead)
        push.push("acme/widgets", "smith/issue-5", clean.bundle, "main")
        assertEquals(clean.merge, git(remote, "rev-parse", "smith/issue-5"))
        assertEquals("$branchHead $mainHead", git(remote, "log", "-1", "--format=%P", "smith/issue-5"))
        assertEquals("Fleet Bot <7+fleet-bot@users.noreply.github.com>", git(remote, "log", "-1", "--format=%an <%ae>", "smith/issue-5"))
        assertEquals(MergeResult.Current, push.mergeDefault("acme/widgets", "smith/issue-5", "main"))
        root.deleteRecursively()
    }

    /** A propose round: a clone of `from`, `files` written on smith/issue-5 in one commit, bundled since where it started. */
    private fun proposeRound(from: String, files: Map<String, String>): ByteArray {
        val work = Files.createTempDirectory(root.toPath(), "propose-").toFile()
        git(work, "clone", "--quiet", "--depth", "50", "--branch", from, "file://${remote.absolutePath}", "repo")
        val repo = File(work, "repo")
        git(repo, "checkout", "--quiet", "-B", "smith/issue-5")
        val base = git(repo, "rev-parse", "HEAD")
        files.forEach { (name, text) -> File(repo, name).apply { parentFile.mkdirs() }.writeText(text) }
        git(repo, "add", "-A"); git(repo, "commit", "--quiet", "-m", "draft")
        val bundle = File(work, "commits.bundle")
        git(repo, "bundle", "create", "--quiet", bundle.absolutePath, "$base..smith/issue-5")
        return bundle.readBytes()
    }

    @Test
    fun `proposal changes list the paths against main, the doc before and after, and the pitch at the head, pushing nothing`() {
        remote.mkdirs(); git(remote, "init", "--quiet", "--bare")
        val seed = File(root, "seed").apply { mkdirs() }
        File(seed, "docs/proposals").mkdirs()
        git(seed, "init", "--quiet"); File(seed, "README.md").writeText("hi\n"); File(seed, "docs/proposals/old.md").writeText("# Old\nDecision log:\n- a\n")
        git(seed, "add", "."); git(seed, "commit", "--quiet", "-m", "seed")
        git(seed, "push", "--quiet", "file://${remote.absolutePath}", "HEAD:main")
        val before = refs()

        val fresh = push.proposalChanges("acme/widgets", "smith/issue-5", "main",
            proposeRound("main", mapOf("docs/proposals/new.md" to "# New\n", "docs/proposals/new.html" to "<svg></svg>\n", "README.md" to "changed\n")),
            "docs/proposals/new.md", "docs/proposals/new.html")
        assertEquals(BundleChanges(listOf("README.md", "docs/proposals/new.html", "docs/proposals/new.md"), null, "# New", "<svg></svg>"), fresh)

        val revisit = push.proposalChanges("acme/widgets", "smith/issue-5", "main",
            proposeRound("main", mapOf("docs/proposals/old.md" to "# Old\nDecision log:\n- a\n- b\n")), "docs/proposals/old.md", "docs/proposals/old.html")
        assertEquals(BundleChanges(listOf("docs/proposals/old.md"), "# Old\nDecision log:\n- a", "# Old\nDecision log:\n- a\n- b"), revisit)

        assertEquals(before, refs(), "nothing pushed")
        assertThrows<IllegalArgumentException> { push.proposalChanges("acme/widgets", "main", "main", ByteArray(0), "docs/proposals/x.md", null) }
        root.deleteRecursively()
    }

    @Test
    fun `a redraft fetched on top of the previous round's unpushed draft is one two-commit bundle that pushes as a fast-forward`() {
        remote.mkdirs(); git(remote, "init", "--quiet", "--bare")
        val seed = File(root, "seed").apply { mkdirs() }
        File(seed, "docs/proposals").mkdirs()
        git(seed, "init", "--quiet"); File(seed, "docs/proposals/old.md").writeText("# Old\nDecision log:\n- a\n")
        git(seed, "add", "."); git(seed, "commit", "--quiet", "-m", "seed")
        git(seed, "push", "--quiet", "file://${remote.absolutePath}", "HEAD:main")
        val first = proposeRound("main", mapOf("docs/proposals/old.md" to "# Old\nDecision log:\n- a\n\nround 1\n"))

        // Round 2, as run-smith.sh does it: the previous bundle fetched onto smith/issue-5 over main, bundled since main.
        val work = Files.createTempDirectory(root.toPath(), "redraft-").toFile()
        git(work, "clone", "--quiet", "--depth", "50", "--branch", "main", "file://${remote.absolutePath}", "repo")
        val repo = File(work, "repo")
        val start = git(repo, "rev-parse", "HEAD")
        File(work, "in.bundle").writeBytes(first)
        git(repo, "fetch", "--quiet", File(work, "in.bundle").absolutePath, "refs/heads/smith/issue-5")
        git(repo, "checkout", "--quiet", "-b", "smith/issue-5", "FETCH_HEAD")
        File(repo, "docs/proposals/old.md").writeText("# Old\nDecision log:\n- a\n\nround 2\n")
        File(repo, "docs/proposals/old.html").writeText("<svg></svg>\n")
        git(repo, "add", "-A"); git(repo, "commit", "--quiet", "-m", "redraft")
        val out = File(work, "commits.bundle")
        git(repo, "bundle", "create", "--quiet", out.absolutePath, "$start..smith/issue-5")
        val second = out.readBytes()

        val changes = push.proposalChanges("acme/widgets", "smith/issue-5", "main", second, "docs/proposals/old.md", "docs/proposals/old.html")
        assertEquals(BundleChanges(listOf("docs/proposals/old.html", "docs/proposals/old.md"), "# Old\nDecision log:\n- a",
            "# Old\nDecision log:\n- a\n\nround 2", "<svg></svg>"), changes)
        push.push("acme/widgets", "smith/issue-5", second, "main")
        assertEquals(listOf("redraft", "draft", "seed"), git(remote, "log", "--format=%s", "smith/issue-5").lines())
        root.deleteRecursively()
    }

    @Test
    fun `a bundle whose sole head is not the branch pushes into the branch, several heads with none of it are refused`() {
        remote.mkdirs(); git(remote, "init", "--quiet", "--bare")
        val seed = File(root, "seed").apply { mkdirs() }
        git(seed, "init", "--quiet"); File(seed, "README.md").writeText("hi\n")
        git(seed, "add", "."); git(seed, "commit", "--quiet", "-m", "seed")
        git(seed, "push", "--quiet", "file://${remote.absolutePath}", "HEAD:main")
        git(seed, "checkout", "--quiet", "-b", "smith/issue-5")
        File(seed, "a.txt").writeText("a\n"); git(seed, "add", "a.txt"); git(seed, "commit", "--quiet", "-m", "work")
        // A Smith's own bundle: its head table names HEAD, not refs/heads/smith/issue-5.
        val out = File(root, "commits.bundle")
        git(seed, "bundle", "create", "--quiet", out.absolutePath, "main..HEAD")
        val pushed = push.push("acme/widgets", "smith/issue-5", out.readBytes(), "main")
        assertEquals(listOf("a.txt"), pushed.paths)
        assertEquals(git(seed, "rev-parse", "HEAD"), git(remote, "rev-parse", "smith/issue-5"))

        git(seed, "branch", "other"); git(seed, "branch", "another")
        git(seed, "bundle", "create", "--quiet", out.absolutePath, "main..other", "main..another")
        val refused = assertThrows<IllegalStateException> { push.push("acme/widgets", "smith/issue-6", out.readBytes(), "main") }
        assert(refused.message!!.startsWith(GitPush.MISSING_BRANCH + "refs/heads/smith/issue-6")) { refused.message!! }
        root.deleteRecursively()
    }

    @Test
    fun `a bundle based on a commit the remote does not have fails naming it, not with upload-pack's not our ref`() {
        remote.mkdirs(); git(remote, "init", "--quiet", "--bare")
        git(remote, "config", "uploadpack.allowFilter", "true"); git(remote, "config", "uploadpack.allowAnySHA1InWant", "true")
        val seed = File(root, "seed").apply { mkdirs() }
        git(seed, "init", "--quiet"); File(seed, "docs/proposals").mkdirs(); File(seed, "docs/proposals/a.md").writeText("# A\n")
        git(seed, "add", "."); git(seed, "commit", "--quiet", "-m", "seed")
        git(seed, "push", "--quiet", "file://${remote.absolutePath}", "HEAD:main")
        // An unpushed commit the Smith bases its bundle on, as a redraft's START that never reached the remote.
        File(seed, "docs/proposals/a.md").writeText("# A\nunpushed\n"); git(seed, "commit", "--quiet", "-am", "unpushed")
        val base = git(seed, "rev-parse", "HEAD")
        git(seed, "checkout", "--quiet", "-b", "smith/issue-5")
        File(seed, "docs/proposals/a.md").writeText("# A\ndraft\n"); git(seed, "commit", "--quiet", "-am", "draft")
        val out = File(root, "commits.bundle")
        git(seed, "bundle", "create", "--quiet", out.absolutePath, "$base..smith/issue-5")
        val bundle = out.readBytes()

        val changes = assertThrows<IllegalStateException> { push.proposalChanges("acme/widgets", "smith/issue-5", "main", bundle, "docs/proposals/a.md", null) }
        assert(base in changes.message!! && "not our ref" !in changes.message!!) { changes.message!! }
        // A rejected result, so its retry reruns the Smith (IncidentController.reruns).
        assert(changes.message!!.startsWith("the smith's result breaks the rules: the bundle cannot be applied")) { changes.message!! }
        val pushed = assertThrows<IllegalStateException> { push.push("acme/widgets", "smith/issue-5", bundle, "main") }
        assert(base in pushed.message!!) { pushed.message!! }

        // The same draft bundled from a base the remote has goes through.
        val main = git(seed, "rev-parse", "main~1")
        git(seed, "bundle", "create", "--quiet", out.absolutePath, "$main..smith/issue-5")
        val ok = push.proposalChanges("acme/widgets", "smith/issue-5", "main", out.readBytes(), "docs/proposals/a.md", null)
        assertEquals(listOf("docs/proposals/a.md"), ok.paths)
        root.deleteRecursively()
    }

    @Test
    fun `a bundle with several heads and none the branch's ref fails as an unappliable bundle naming it, not with git fetch's couldn't find remote ref`() {
        remote.mkdirs(); git(remote, "init", "--quiet", "--bare")
        val seed = File(root, "seed").apply { mkdirs() }
        git(seed, "init", "--quiet"); File(seed, "README.md").writeText("hi\n")
        git(seed, "add", "."); git(seed, "commit", "--quiet", "-m", "seed")
        git(seed, "push", "--quiet", "file://${remote.absolutePath}", "HEAD:main")
        val base = git(seed, "rev-parse", "HEAD")
        git(seed, "checkout", "--quiet", "-b", "other")
        File(seed, "a.txt").writeText("a\n"); git(seed, "add", "."); git(seed, "commit", "--quiet", "-m", "work")
        val out = File(root, "commits.bundle")
        git(seed, "branch", "another")
        git(seed, "bundle", "create", "--quiet", out.absolutePath, "$base..other", "$base..another")

        val failed = assertThrows<IllegalStateException> { push.push("acme/widgets", "smith/issue-5", out.readBytes(), "main") }
        assert(failed.message!!.startsWith(GitPush.MISSING_BRANCH + "refs/heads/smith/issue-5")) { failed.message!! }
        assert("refs/heads/other" in failed.message!! && "couldn't find remote ref" !in failed.message!!) { failed.message!! }
        assertEquals("", git(remote, "for-each-ref", "refs/heads/smith"))
        root.deleteRecursively()
    }

    @Test
    fun `a conflicting merge lists its files and leaves the remote as it was, and other branches are refused`() {
        seeded("a.txt")
        moveMain("a.txt")
        val before = refs()

        assertEquals(MergeResult.Conflict(listOf("a.txt")), push.mergeDefault("acme/widgets", "smith/issue-5", "main"))
        assertEquals(before, refs())
        assertThrows<IllegalArgumentException> { push.mergeDefault("acme/widgets", "main", "smith/issue-5") }
        root.deleteRecursively()
    }

    @Test
    fun `an edit commits one file on top of the branch as the token's account, pushes it fast-forward, and a no-op makes nothing`() {
        seeded("docs/x.md")
        val old = git(remote, "rev-parse", "smith/issue-5")

        val head = push.commitEdit("acme/widgets", "smith/issue-5", "docs/x.md", "Decision log: accepted") {
            assertEquals("changed\n", it, "the text as it is, last newline kept"); it + "- logged\n"
        }
        assertEquals(git(remote, "rev-parse", "smith/issue-5"), head)
        assertEquals(old, git(remote, "rev-parse", "smith/issue-5^"), "one commit on the old head")
        assertEquals("changed\n- logged", git(remote, "show", "smith/issue-5:docs/x.md"))
        assertEquals("Fleet Bot <7+fleet-bot@users.noreply.github.com> Decision log: accepted",
            git(remote, "log", "-1", "--format=%an <%ae> %s", "smith/issue-5"))
        assertEquals("Fleet Bot <7+fleet-bot@users.noreply.github.com>", git(remote, "log", "-1", "--format=%cn <%ce>", "smith/issue-5"), "committer too")
        assertEquals("docs/x.md", git(remote, "show", "--name-only", "--format=", "smith/issue-5"), "one file changed, the rest kept")
        assertEquals("a", git(remote, "show", "smith/issue-5:a.txt"))

        val before = refs()
        assertEquals(null, push.commitEdit("acme/widgets", "smith/issue-5", "docs/x.md", "nothing") { it })
        assertEquals(before, refs())
        assertThrows<IllegalArgumentException> { push.commitEdit("acme/widgets", "main", "a.txt", "no") { it + "x" } }
        root.deleteRecursively()
    }

    @Test
    fun `a push the remote rejects throws PushRejected with the ref and the hook's reason`() {
        remote.mkdirs(); git(remote, "init", "--quiet", "--bare")
        val seed = File(root, "seed").apply { mkdirs() }
        git(seed, "init", "--quiet"); File(seed, "README.md").writeText("hi\n")
        git(seed, "add", "."); git(seed, "commit", "--quiet", "-m", "seed")
        git(seed, "push", "--quiet", "file://${remote.absolutePath}", "HEAD:main")
        // GitHub's words for a token without `workflow` scope, said by a pre-receive hook.
        File(remote, "hooks/pre-receive").apply {
            writeText("#!/bin/sh\necho 'refusing to allow a Personal Access Token to create or update workflow `.github/workflows/images.yml` without `workflow` scope' >&2\nexit 1\n")
            setExecutable(true)
        }

        val rejected = assertThrows<PushRejected> { push.push("acme/widgets", "smith/issue-5", smithRound("main", ".github/workflows/images.yml"), "main") }
        assertEquals("smith/issue-5", rejected.ref)
        assertEquals("push rejected: the token lacks the workflow scope for .github/workflows/images.yml; give the repo a token with workflow scope (PUT /repos/{owner}/{name}/token), then retry",
            rejected.message!!.lines().first())
        assertEquals("", git(remote, "branch", "--list", "smith/issue-5"), "nothing was pushed")
        root.deleteRecursively()
    }

    @Test
    fun `a push whose commits add a line a deployment pattern matches is refused naming file and line, never the pattern or line`() {
        remote.mkdirs(); git(remote, "init", "--quiet", "--bare")
        val seed = File(root, "seed").apply { mkdirs() }
        git(seed, "init", "--quiet"); File(seed, "README.md").writeText("hi\n")
        git(seed, "add", "."); git(seed, "commit", "--quiet", "-m", "seed")
        git(seed, "push", "--quiet", "file://${remote.absolutePath}", "HEAD:main")
        var rules: DeploymentRules? = DeploymentRules("# ours\nchang[e]d\n", "")
        val gated = CliGitPush(tokens, GitHubTokenIdentity(FakeIssueDetails(), tokens), gitUrl = "file://${root.absolutePath}", patterns = { rules })

        val refused = assertThrows<IllegalStateException> { gated.push("acme/widgets", "smith/issue-5", smithRound("main", "conf/a.txt"), "main") }.message!!
        assertTrue(refused.startsWith(GitPush.UNAPPLIABLE) && "conf/a.txt:1 (commit " in refused, refused)
        assertTrue("chang" !in refused, "neither the pattern nor the line: $refused")
        assertTrue(run(remote, "rev-parse", "--verify", "--quiet", "smith/issue-5") != 0, "nothing pushed")

        rules = DeploymentRules("chang[e]d", "changed")
        gated.push("acme/widgets", "smith/issue-5", smithRound("main", "conf/a.txt"), "main")
        rules = null
        gated.push("acme/widgets", "smith/issue-5", smithRound("smith/issue-5", "conf/b.txt"), "main")
    }

    private fun run(dir: File, vararg args: String): Int = ProcessBuilder(listOf("git") + args).directory(dir).start().waitFor()

    @Test
    fun `the scan reads line numbers per hunk and skips removed lines, comments and blank patterns`() {
        val log = "\u0000commit abc\n\ndiff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1,2 @@\n-old hit\n+fine\n+a hit\n@@ -9,0 +20 @@\n+hit again\n"
        val hits = DeploymentValueScan.scan(log, DeploymentRules("\n# hit\nhit\n", "hit again"))
        assertEquals(listOf(DeploymentHit("abc", "x", 2)), hits)
    }
}
