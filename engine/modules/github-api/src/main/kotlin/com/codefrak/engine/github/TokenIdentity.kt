package com.codefrak.engine.github

/**
 * Whose token acts on a repo, and what it may do (engine/README.md, "Each repo's token"): the token's login, its
 * classic scopes (engine/README.md, "The token's scopes"; null, unknown, for a fine-grained token) and the identity a
 * commit made with it carries. Null when the repo has no token or GitHub would not say, and the engine starts either
 * way. The engine's is `GitHubTokenIdentity` (engine-github), one `GET /user` per token, held in memory.
 */
interface TokenIdentity {
    /** The login of the token that acts on `repo`, or null when it has none or GitHub would not say. */
    fun login(repo: String): String?

    /** The classic scopes of the token that acts on `repo`, or null when unknown. */
    fun scopes(repo: String): Set<String>?

    /**
     * Who a commit on `repo` is by: its token's account ([TokenUser.commitIdentity]), so a commit, its push
     * and its PR are one identity on GitHub. Nothing stands in when the repo has no token or GitHub would
     * not say who it is: the commit is not made.
     */
    fun commitIdentity(repo: String): CommitIdentity
}
