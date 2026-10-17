package com.codefrak.engine.ci

/** The check a repo's pushed branches wait for, and the path prefixes that make a branch need it. */
data class CiCheck(val name: String, val paths: List<String>)

/** A repo's check from its `engine.target_repo` row (`TargetRepos`), or null when the row sets none. */
fun interface RepoCiChecks {
    fun ciCheck(repo: String): CiCheck?
}
