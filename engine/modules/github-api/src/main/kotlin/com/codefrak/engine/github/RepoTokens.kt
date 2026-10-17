package com.codefrak.engine.github

/**
 * Which GitHub token acts on a repo (engine/README.md, "Each repo's token"): the repo's own,
 * sealed on its `engine.target_repo` row, and no other. Every GitHub client asks here per call,
 * so a token set through `PUT /repos/{o}/{n}/token` is used at once, and a Smith sandbox gets
 * its ticket's repo's token and no other. A repo without one has no token: nothing stands in.
 * The engine's is `StoredRepoTokens` (engine-github), over the target repo registry.
 */
fun interface RepoTokens {
    /** The repo's token, or null when it has none. */
    fun resolve(repo: String): String?

    /** [resolve]'s token, or a failure naming how to give the repo one. */
    fun forRepo(repo: String): String = resolve(repo)
        ?: throw IllegalStateException("no GitHub token for $repo: PUT /repos/$repo/token")

    /** The repo's Smith token (no Issues permission), or null when it has none; never the repo token. */
    fun resolveSmith(repo: String): String? = null

    /**
     * [resolveSmith]'s token, the GH_TOKEN of a Smith sandbox, or a failure naming how to give the repo one. There is
     * no fallback to [forRepo]: the repo token can write issues, and a Smith must not.
     */
    fun forSmith(repo: String): String = resolveSmith(repo)
        ?: throw IllegalStateException("no Smith token for $repo: PUT /repos/$repo/smith-token")
}

/** The repo's owner and name, split from `owner/name`. */
fun splitRepo(repo: String): Pair<String, String> =
    repo.split('/', limit = 2).also { require(it.size == 2) { "repo must be owner/name: $repo" } }.let { it[0] to it[1] }
