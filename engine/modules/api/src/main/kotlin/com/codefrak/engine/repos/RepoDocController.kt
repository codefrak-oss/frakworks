package com.codefrak.engine.repos

import com.codefrak.engine.tokenLogin
import com.codefrak.engine.badRequest
import com.codefrak.engine.board.targetRepoOr404
import com.codefrak.engine.smith.SmithRefusal.refuseSmith
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * A target repo's private documents (engine/README.md, "Private documents"; #1711): the user's, by REST, never a
 * Smith's. A Smith sandbox is refused every call here, reads too: it reads its own run's repo's docs through the
 * frakworks MCP server, and a REST read would let it name another repo. PUT and DELETE are the bearer token's login,
 * stamped on the row.
 */
@RestController
class RepoDocController(private val repos: TargetRepos, private val docs: RepoDocs, private val sandbox: SmithSandbox) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun repo(owner: String, name: String, request: HttpServletRequest, what: String): String {
        refuseSmith(sandbox, request, log, what)
        return targetRepoOr404(repos, "$owner/$name").fullName
    }

    private fun docName(doc: String) = doc.takeIf { RepoDocs.NAME.matches(it) }
        ?: throw badRequest("a doc's name is 1 to 100 letters, digits, '.', '_' or '-', not only dots")

    /** The repo's docs, name order: `name`, `chars`, `updatedAt`, `updatedBy`; never their content. */
    @GetMapping("/repos/{owner}/{name}/docs")
    fun list(@PathVariable owner: String, @PathVariable name: String, request: HttpServletRequest): List<RepoDocInfo> =
        docs.list(repo(owner, name, request, "read a repo's private docs"))

    /** One doc's whole text, `text/markdown`; 404 when there is none by that name. */
    @GetMapping("/repos/{owner}/{name}/docs/{doc}", produces = ["text/markdown;charset=UTF-8"])
    fun read(@PathVariable owner: String, @PathVariable name: String, @PathVariable doc: String, request: HttpServletRequest): String {
        val repo = repo(owner, name, request, "read a repo's private docs")
        return docs.read(repo, docName(doc)) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$repo has no private doc $doc")
    }

    /** Puts the doc (the body, as text), replacing one by the same name: 201 when new, 204 when replaced. */
    @PutMapping("/repos/{owner}/{name}/docs/{doc}", consumes = [MediaType.ALL_VALUE])
    fun put(@PathVariable owner: String, @PathVariable name: String, @PathVariable doc: String, @RequestBody body: String,
            request: HttpServletRequest): ResponseEntity<Unit> {
        val repo = repo(owner, name, request, "write a repo's private docs")
        val by = request.tokenLogin()
        val n = docName(doc)
        if (body.isBlank()) throw badRequest("a private doc is not empty")
        if (body.length > RepoDocs.MAX_CHARS) throw badRequest("a private doc is at most ${RepoDocs.MAX_CHARS} characters; this one is ${body.length}")
        return ResponseEntity.status(if (docs.put(repo, n, body, by)) HttpStatus.CREATED else HttpStatus.NO_CONTENT).build()
    }

    /** 204, or 404 when there is none by that name. */
    @DeleteMapping("/repos/{owner}/{name}/docs/{doc}")
    fun delete(@PathVariable owner: String, @PathVariable name: String, @PathVariable doc: String,
               request: HttpServletRequest): ResponseEntity<Unit> {
        val repo = repo(owner, name, request, "delete a repo's private docs")
        request.tokenLogin()
        return if (docs.delete(repo, docName(doc))) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }
}
