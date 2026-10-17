package com.codefrak.engine.github

/**
 * `GET /user` as [IssueDetails.user] reads it: the token's login, its classic scopes (null when unknown),
 * and the account's `id` and display `name` (null or blank when it has set none), for [commitIdentity].
 */
data class TokenUser(val login: String, val scopes: Set<String>?, val id: Long, val name: String? = null) {
    /**
     * Who a commit made with this token is by (engine/README.md, "Each repo's token"): the account's
     * name, or its login when it has none, and GitHub's noreply address for it, which links the commit
     * to the account whether or not its own address is public.
     */
    val commitIdentity: CommitIdentity
        get() = CommitIdentity(name?.takeIf { it.isNotBlank() } ?: login, "$id+$login@users.noreply.github.com")
}

/** A commit's author and committer. */
data class CommitIdentity(val name: String, val email: String)

/**
 * A repo's token's classic OAuth scopes, from GitHub's `X-OAuth-Scopes` response header
 * (engine/README.md, "The token's scopes"). A fine-grained token sends no such header,
 * so its scopes are unknown (null), and nothing is refused on them.
 */
object TokenScopes {
    const val HEADER = "X-OAuth-Scopes"
    /** The scope GitHub requires for any push that touches `.github/workflows/`. */
    const val WORKFLOW = "workflow"

    /** `"repo, workflow"` to `{repo, workflow}`; an empty header is a token with no scopes; no header is null (unknown). */
    fun parse(header: String?): Set<String>? =
        header?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
}
