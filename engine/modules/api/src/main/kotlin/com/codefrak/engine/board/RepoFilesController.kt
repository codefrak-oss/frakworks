package com.codefrak.engine.board

import com.codefrak.engine.errorResponse
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Unauthenticated, read-only: a target repo's tree and one file at a ref ([RepoFiles]), what the desktop's
 * Repos branch browses (engine/README.md, `GET /repos/{owner}/{repo}/tree` and `/file`). A Smith sandbox reads
 * its own ticket's repo only: the engine's token reaches every target repo, and a Smith's own does not.
 */
@RestController
class RepoFilesController(private val files: RepoFiles, private val repos: TargetRepos, private val sandbox: SmithSandbox) {
    private val log = LoggerFactory.getLogger(RepoFilesController::class.java)

    /**
     * `{commit, truncated, entries: [{path, type, sha}]}` at `ref` (absent: the default branch). 404 `{error}` for
     * a repo that is not a target repo or a ref GitHub cannot resolve, 502 `{error}` when GitHub fails; 403 to a
     * Smith sandbox on another repo, 503 when the caller cannot be told from a Smith.
     */
    @GetMapping("/repos/{owner}/{repo}/tree", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun tree(@PathVariable owner: String, @PathVariable repo: String, @RequestParam(required = false) ref: String?,
             request: HttpServletRequest? = null): ResponseEntity<Any> {
        refused(request, "$owner/$repo")?.let { return it }
        val full = repos.find("$owner/$repo")?.fullName ?: return errorResponse(HttpStatus.NOT_FOUND, "$owner/$repo is not a target repo")
        val tree = try { files.tree(full, ref?.ifBlank { null }) } catch (e: Exception) {
            log.warn("{} tree {}: {}", full, ref, e.message)
            return errorResponse(HttpStatus.BAD_GATEWAY, "GitHub: ${e.message}")
        } ?: return errorResponse(HttpStatus.NOT_FOUND, "$full has no ref $ref")
        return ResponseEntity.ok(tree)
    }

    /**
     * The file at `path` at `ref` (absent: the default branch), its bytes whole, typed by its extension
     * (`application/octet-stream` when that says nothing), with its blob SHA as `X-Blob-Sha`. 404 `{error}` for a
     * repo that is not a target repo, a ref GitHub cannot resolve, or a path that is no file there; 502 `{error}`
     * when GitHub fails; 403 to a Smith sandbox on another repo, 503 when the caller cannot be told from a Smith.
     */
    @GetMapping("/repos/{owner}/{repo}/file")
    fun file(@PathVariable owner: String, @PathVariable repo: String, @RequestParam path: String,
             @RequestParam(required = false) ref: String?, request: HttpServletRequest? = null): ResponseEntity<Any> {
        refused(request, "$owner/$repo")?.let { return it }
        val full = repos.find("$owner/$repo")?.fullName ?: return errorResponse(HttpStatus.NOT_FOUND, "$owner/$repo is not a target repo")
        val file = try { files.file(full, path, ref?.ifBlank { null }) } catch (e: RepoFiles.Missing) {
            return errorResponse(HttpStatus.NOT_FOUND, e.message ?: "no $path in $full")
        } catch (e: Exception) {
            log.warn("{} file {} at {}: {}", full, path, ref, e.message)
            return errorResponse(HttpStatus.BAD_GATEWAY, "GitHub: ${e.message}")
        }
        // A repo's .html or .svg is served as its bytes, never as a page of the engine's origin.
        return ResponseEntity.ok().contentType(contentType(path)).header("X-Blob-Sha", file.sha)
            .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "sandbox")
            .body(file.bytes)
    }

    /** 403/503 to a Smith sandbox outside its own repo, as [smithRepoRefusal]; null to go on. */
    private fun refused(request: HttpServletRequest?, repo: String): ResponseEntity<Any>? =
        smithRepoRefusal(sandbox, request, repo, log)?.let { (status, message) -> errorResponse(status, message) }

    /** By extension: Spring's MIME table, with markdown named first since a repo is mostly that and code. */
    private fun contentType(path: String): MediaType {
        val name = path.substringAfterLast('/')
        if (name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")) return MediaType.TEXT_MARKDOWN
        return MediaTypeFactory.getMediaType(name).orElse(MediaType.APPLICATION_OCTET_STREAM)
    }

}
