package com.codefrak.engine.board

import com.codefrak.engine.github.RepoContents
import com.codefrak.engine.github.RepoTree
import com.codefrak.engine.github.RepoTreeEntry
import org.springframework.stereotype.Component

/** What `GET /repos/{owner}/{repo}/tree` answers: the commit the ref resolved to, GitHub's `truncated`, and every entry. */
data class RepoTreeView(val commit: String, val truncated: Boolean, val entries: List<RepoTreeEntry>)

/**
 * A target repo's files at a ref, read from GitHub for the desktop's Repos branch (engine/README.md,
 * `GET /repos/{owner}/{repo}/tree` and `/file`). A ref costs one commits call to resolve (none when it is a
 * commit SHA this cache already holds a tree for: what the desktop passes when it opens a file of a tree it
 * showed); a tree is read once per commit, and a file once per blob SHA, both kept in memory. The engine
 * records no repo's head SHA anywhere else ([ProposalDocs] reads `HEAD:` through GraphQL and keeps only blob
 * oids), so there is none to reuse. Nothing here polls.
 */
@Component
class RepoFiles(private val contents: RepoContents) {
    /** A file at a ref: its path, its blob SHA and its bytes, whole. */
    class File(val path: String, val sha: String, val bytes: ByteArray)

    /** Why a file read found nothing: no such ref, no such path, or a path that names a directory. */
    class Missing(message: String) : RuntimeException(message)

    companion object {
        /** Trees kept, by `repo@commit`: a handful of repos at a commit or two each. */
        const val TREES = 32
        /** File bytes kept, by `repo@blob`, in all; a file over [MAX_FILE] is served but not kept. */
        const val MAX_BYTES = 64L * 1024 * 1024
        const val MAX_FILE = 8L * 1024 * 1024
        private val SHA = Regex("^[0-9a-fA-F]{40}$")
        /** A relative path of plain segments: no empty, `.` or `..` segment, no backslash. */
        fun validPath(path: String): Boolean =
            path.isNotEmpty() && path.length <= 4096 && '\\' !in path && '\u0000' !in path &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private val trees = object : LinkedHashMap<String, RepoTree>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RepoTree>?) = size > TREES
    }
    private val files = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var fileBytes = 0L

    private fun key(repo: String, sha: String) = "${repo.lowercase()}@${sha.lowercase()}"

    /** The commit `ref` names, from the cache when it is a commit SHA a kept tree is at; null when GitHub cannot resolve it. */
    private fun commit(repo: String, ref: String?): String? {
        if (ref != null && SHA.matches(ref) && synchronized(trees) { key(repo, ref) in trees }) return ref.lowercase()
        return contents.commitSha(repo, ref)
    }

    private fun treeAt(repo: String, commit: String): RepoTree =
        synchronized(trees) { trees[key(repo, commit)] } ?: contents.repoTree(repo, commit).also { synchronized(trees) { trees[key(repo, commit)] = it } }

    /** `repo`'s tree at `ref` (null: the default branch), or null when GitHub cannot resolve the ref. Throws when GitHub fails. */
    fun tree(repo: String, ref: String?): RepoTreeView? {
        val commit = commit(repo, ref) ?: return null
        val tree = treeAt(repo, commit)
        return RepoTreeView(commit, tree.truncated, tree.entries)
    }

    /**
     * The file at `path` at `ref` (null: the default branch). Its blob SHA comes from the commit's tree, so a
     * file read again with its blob unchanged costs no contents call; in a truncated tree that does not list the
     * path, the contents API is asked directly and the answer kept by the blob SHA git gives those bytes.
     * Throws [Missing] for no such ref or path, anything else when GitHub fails.
     */
    fun file(repo: String, path: String, ref: String?): File {
        if (!validPath(path)) throw Missing("no $path in $repo")
        val commit = commit(repo, ref) ?: throw Missing("$repo has no ref $ref")
        val tree = treeAt(repo, commit)
        val entry = tree.entries.firstOrNull { it.path == path }
        if (entry != null && entry.type != "blob") throw Missing("$path in $repo is a ${if (entry.type == "tree") "directory" else entry.type}, not a file")
        if (entry == null && !tree.truncated) throw Missing("no $path in $repo at $commit")
        entry?.let { e -> synchronized(files) { files[key(repo, e.sha)] }?.let { return File(path, e.sha, it) } }
        val bytes = contents.fileBytes(repo, path, commit) ?: throw Missing("no $path in $repo at $commit")
        val sha = entry?.sha ?: blobSha(bytes)
        keep(key(repo, sha), bytes)
        return File(path, sha, bytes)
    }

    private fun keep(key: String, bytes: ByteArray) {
        if (bytes.size > MAX_FILE) return
        synchronized(files) {
            files.put(key, bytes)?.let { fileBytes -= it.size }
            fileBytes += bytes.size
            val oldest = files.entries.iterator()
            while (fileBytes > MAX_BYTES && oldest.hasNext()) { fileBytes -= oldest.next().value.size; oldest.remove() }
        }
    }

    /** Git's blob SHA-1 of `bytes`: `blob <size>\0` then the bytes. */
    private fun blobSha(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray())
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
