package com.codefrak.engine.github

/** One entry of a directory on a repo's default branch: its file name and its git object id. */
data class TreeEntry(val name: String, val oid: String)

/** One entry of a repo's recursive tree: its path from the root, GitHub's `type` (`blob`, `tree`, `commit` for a submodule) and object SHA. */
data class RepoTreeEntry(val path: String, val type: String, val sha: String)

/** A repo's whole tree at one commit, and GitHub's `truncated` flag (the recursive read stops at 100,000 entries or 7 MB). */
data class RepoTree(val truncated: Boolean, val entries: List<RepoTreeEntry>)

/** A text file on a repo's default branch: its text and its blob SHA, which a [RepoContents.putFile] over it names. */
data class RepoFile(val text: String, val sha: String)

/** What the engine reads from GitHub about a repo's git tree, rather than an issue; a sibling of [IssueDetails]. Tests use a fake. */
interface RepoContents {
    /**
     * The entries of directory `path` on the repo's default branch, each with its blob oid; empty when the
     * directory is not there. Throws unless an implementation reads trees.
     */
    fun treeEntries(repo: String, path: String): List<TreeEntry> = throw UnsupportedOperationException("treeEntries")
    /** The text of each blob in `oids`, by oid; an oid GitHub has no blob for is left out. Throws unless an implementation reads blobs. */
    fun blobTexts(repo: String, oids: Collection<String>): Map<String, String> = throw UnsupportedOperationException("blobTexts")
    /**
     * The commit SHA `ref` (a branch, a tag or a SHA; null: the default branch) names; null when GitHub cannot
     * resolve it. Throws unless an implementation resolves refs.
     */
    fun commitSha(repo: String, ref: String?): String? = throw UnsupportedOperationException("commitSha")
    /** The repo's whole tree at commit `sha`, read recursively. Throws unless an implementation reads trees. */
    fun repoTree(repo: String, sha: String): RepoTree = throw UnsupportedOperationException("repoTree")
    /** A file's bytes at `ref`, GitHub's contents API read raw; null when the file is not there. Throws unless an implementation reads files. */
    fun fileBytes(repo: String, path: String, ref: String): ByteArray? = throw UnsupportedOperationException("fileBytes")
    /** A file's text and blob SHA on the default branch (the contents API); null when the file is not there. Throws unless an implementation reads files. */
    fun file(repo: String, path: String): RepoFile? = throw UnsupportedOperationException("file")
    /**
     * Replaces existing file `path` on the default branch with [text] in one commit (`PUT /repos/{r}/contents/{path}`
     * with the [sha] it replaces, so a concurrent change is GitHub's 409, never a lost write), as the repo's own token,
     * authored by GitHub login [authorLogin]; the new commit's SHA. Throws unless an implementation writes files.
     */
    fun putFile(repo: String, path: String, text: String, sha: String, message: String, authorLogin: String): String =
        throw UnsupportedOperationException("putFile")
}
