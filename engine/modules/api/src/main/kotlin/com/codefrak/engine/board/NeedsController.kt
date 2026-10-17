package com.codefrak.engine.board

import com.codefrak.engine.repos.TargetRepos
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Unauthenticated, read-only: everything waiting on a person ([Needs]), oldest first; only `repo`'s when given, which must be a
 * target repo; with `for=<login>`, only the rows for that login or for everyone (`for` null).
 */
@RestController
class NeedsController(private val needs: Needs, private val repos: TargetRepos) {
    @GetMapping("/needs")
    fun needs(@RequestParam(required = false) repo: String?, @RequestParam(name = "for", required = false) forLogin: String?): List<Need> =
        needs.list(repo?.let { targetRepo(repos, it) }, forLogin?.trim()?.takeIf { it.isNotEmpty() })
}
