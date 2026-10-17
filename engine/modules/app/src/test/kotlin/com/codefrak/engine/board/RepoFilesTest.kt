package com.codefrak.engine.board

import com.codefrak.engine.github.FakeIssueDetails
import com.codefrak.engine.github.RepoTree
import com.codefrak.engine.github.RepoTreeEntry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RepoFiles] on a stand-in GitHub: what it asks GitHub, and what it answers from memory. Plain JUnit, no Spring. */
class RepoFilesTest {
    private val c1 = "a".repeat(40)
    private val c2 = "b".repeat(40)
    private val github = FakeIssueDetails().apply {
        commits["o/r" to null] = c1
        commits["o/r" to "main"] = c1
        repoTrees["o/r" to c1] = RepoTree(false, listOf(
            RepoTreeEntry("README.md", "blob", "r1"), RepoTreeEntry("src", "tree", "d1"), RepoTreeEntry("src/a.kt", "blob", "a1")))
        repoTrees["o/r" to c2] = RepoTree(false, listOf(
            RepoTreeEntry("README.md", "blob", "r1"), RepoTreeEntry("src", "tree", "d2"), RepoTreeEntry("src/a.kt", "blob", "a2")))
        raw[Triple("o/r", "README.md", c1)] = "# hi".toByteArray()
        raw[Triple("o/r", "README.md", c2)] = "# hi".toByteArray()
        raw[Triple("o/r", "src/a.kt", c1)] = "val a = 1".toByteArray()
        raw[Triple("o/r", "src/a.kt", c2)] = "val a = 2".toByteArray()
    }
    private val files = RepoFiles(github)

    @Test
    fun `a tree is the default branch's head, and read once while the head is unchanged`() {
        val first = files.tree("o/r", null)!!
        assertEquals(c1, first.commit)
        assertFalse(first.truncated)
        assertEquals(listOf("README.md", "src", "src/a.kt"), first.entries.map { it.path })
        assertEquals(RepoTreeEntry("src", "tree", "d1"), first.entries[1])
        assertEquals(first, files.tree("o/r", null))
        assertEquals(listOf("o/r" to c1), github.treeReads, "one git/trees request for two tree calls")
        assertEquals(2, github.commitReads.size, "the ref is resolved each time")
        github.commits["o/r" to null] = c2
        assertEquals(c2, files.tree("o/r", null)!!.commit)
        assertEquals(listOf("o/r" to c1, "o/r" to c2), github.treeReads, "a new head is a new tree")
    }

    @Test
    fun `a ref resolves to its commit, a commit SHA a kept tree is at costs nothing, and an unknown ref is null`() {
        assertEquals(c1, files.tree("o/r", "main")!!.commit)
        github.commitReads.clear()
        assertEquals(c1, files.tree("o/r", c1.uppercase())!!.commit)
        assertTrue(github.commitReads.isEmpty(), "no commits call for a kept commit")
        assertNull(files.tree("o/r", "no-such-branch"))
        assertEquals(1, github.treeReads.size)
    }

    @Test
    fun `a file is read once per blob SHA, even at a new commit`() {
        val f = files.file("o/r", "README.md", null)
        assertEquals("# hi", f.bytes.decodeToString())
        assertEquals("r1", f.sha)
        assertEquals("# hi", files.file("o/r", "README.md", null).bytes.decodeToString())
        assertEquals(1, github.contentReads.size, "the second read makes no contents request")
        github.commits["o/r" to null] = c2
        assertEquals("# hi", files.file("o/r", "README.md", null).bytes.decodeToString())
        assertEquals(1, github.contentReads.size, "README.md's blob is the same at c2")
        assertEquals("val a = 2", files.file("o/r", "src/a.kt", null).bytes.decodeToString())
        assertEquals(listOf(Triple("o/r", "README.md", c1), Triple("o/r", "src/a.kt", c2)), github.contentReads)
    }

    @Test
    fun `a file at the commit of a tree shown needs no ref resolution`() {
        files.tree("o/r", null)
        github.commitReads.clear()
        assertEquals("val a = 1", files.file("o/r", "src/a.kt", c1).bytes.decodeToString())
        assertTrue(github.commitReads.isEmpty())
        assertEquals(1, github.treeReads.size)
    }

    @Test
    fun `a missing path, a directory, a bad path or an unknown ref is Missing, with no contents request`() {
        assertThrows<RepoFiles.Missing> { files.file("o/r", "nope.md", null) }
        assertThrows<RepoFiles.Missing> { files.file("o/r", "src", null) }
        assertThrows<RepoFiles.Missing> { files.file("o/r", "../x", null) }
        assertThrows<RepoFiles.Missing> { files.file("o/r", "/README.md", null) }
        assertThrows<RepoFiles.Missing> { files.file("o/r", "README.md", "gone") }
        assertTrue(github.contentReads.isEmpty())
    }

    @Test
    fun `in a truncated tree a path it does not list is read from the contents API, and kept by its git blob SHA`() {
        github.repoTrees["o/r" to c1] = RepoTree(true, listOf(RepoTreeEntry("README.md", "blob", "r1")))
        val f = files.file("o/r", "src/a.kt", null)
        assertEquals("val a = 1", f.bytes.decodeToString())
        // `git hash-object` of "val a = 1" (no newline).
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.update("blob 9\u0000".toByteArray())
        assertEquals(md.digest("val a = 1".toByteArray()).joinToString("") { "%02x".format(it) }, f.sha)
        assertThrows<RepoFiles.Missing> { files.file("o/r", "src/b.kt", null) }
    }

    @Test
    fun `GitHub failing throws, and is not Missing`() {
        github.failing += "o/r"
        val e = assertThrows<IllegalStateException> { files.tree("o/r", null) }
        assertEquals("GitHub 502", e.message)
        assertThrows<IllegalStateException> { files.file("o/r", "README.md", null) }
    }
}
