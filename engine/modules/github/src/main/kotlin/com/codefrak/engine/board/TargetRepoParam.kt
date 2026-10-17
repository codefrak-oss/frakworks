package com.codefrak.engine.board

import com.codefrak.engine.repos.TargetRepo
import com.codefrak.engine.repos.TargetRepos
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** `repo`'s name as the target repo row spells it, matched ignoring case; 400 when it is not one. */
fun targetRepo(repos: TargetRepos, repo: String): String =
    repos.find(repo)?.fullName ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$repo is not a target repo")

/** `fullName`'s target repo row, matched ignoring case; 404 when it is not one. */
fun targetRepoOr404(repos: TargetRepos, fullName: String): TargetRepo =
    repos.find(fullName) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$fullName is not a target repo")
