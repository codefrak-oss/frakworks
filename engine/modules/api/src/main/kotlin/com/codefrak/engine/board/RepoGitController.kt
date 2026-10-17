package com.codefrak.engine.board

import com.codefrak.engine.engineJson
import com.codefrak.engine.github.RepoTokens
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * A read-only passthrough of git's smart-HTTP read protocol to GitHub, per target repo (engine/README.md,
 * `GET /repos/{owner}/{repo}/git/info/refs` and `POST .../git/git-upload-pack`): `git clone $ENGINE_URL/repos/<o>/<n>/git` works from a
 * machine with no GitHub credential, because the engine forwards the two upload-pack routes to
 * `https://github.com/<o>/<n>.git` as `x-access-token:<the repo's token>` over basic auth. It is two routes
 * forwarded byte for byte, not a git implementation: the engine keeps no clone, and both bodies are streamed,
 * never held whole. `receive-pack` (a push) is refused before GitHub is asked: the token can push, and a desktop
 * reading through the engine must not. A Smith sandbox reaches its own ticket's repo only, as [smithRepoRefusal].
 */
@RestController
class RepoGitController(
    private val repos: TargetRepos,
    private val tokens: RepoTokens,
    private val sandbox: SmithSandbox,
    @Value("\${engine.github.git-url:https://github.com}") private val gitUrl: String,
) {
    private val log = LoggerFactory.getLogger(RepoGitController::class.java)
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build()

    /**
     * The ref advertisement: forwarded to GitHub for `service=git-upload-pack`; 403 `{error}` for `git-receive-pack`
     * (or any other service), with no upstream call. 404 `{error}` for a repo that is not a target repo or has no
     * token, 502 `{error}` when GitHub fails; 403 to a Smith sandbox on another repo, 503 when the caller cannot be
     * told from a Smith.
     */
    @GetMapping("/repos/{owner}/{repo}/git/info/refs")
    fun infoRefs(@PathVariable owner: String, @PathVariable repo: String, @RequestParam(required = false) service: String?,
                 request: HttpServletRequest, response: HttpServletResponse) {
        if (service != UPLOAD_PACK) return error(response, HttpStatus.FORBIDDEN, "$owner/$repo through the engine is read-only: only service=$UPLOAD_PACK")
        forward(owner, repo, "info/refs?service=$UPLOAD_PACK", request, response)
    }

    /** The upload-pack exchange (what a clone or fetch sends after the advertisement), streamed both ways. Errors as [infoRefs]. */
    @PostMapping("/repos/{owner}/{repo}/git/git-upload-pack")
    fun uploadPack(@PathVariable owner: String, @PathVariable repo: String, request: HttpServletRequest, response: HttpServletResponse) =
        forward(owner, repo, UPLOAD_PACK, request, response)

    /** A push: 403 `{error}`, and GitHub is never called. */
    @PostMapping("/repos/{owner}/{repo}/git/git-receive-pack")
    fun receivePack(@PathVariable owner: String, @PathVariable repo: String, response: HttpServletResponse) =
        error(response, HttpStatus.FORBIDDEN, "$owner/$repo through the engine is read-only: no git-receive-pack")

    private fun forward(owner: String, repo: String, route: String, request: HttpServletRequest, response: HttpServletResponse) {
        if (refused(request, response, "$owner/$repo")) return
        val full = repos.find("$owner/$repo")?.fullName ?: return error(response, HttpStatus.NOT_FOUND, "$owner/$repo is not a target repo")
        val token = tokens.resolve(full) ?: return error(response, HttpStatus.NOT_FOUND, "$full has no token: PUT /repos/$full/token")
        val builder = HttpRequest.newBuilder(URI.create("$gitUrl/$full.git/$route"))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray()))
            .header("User-Agent", USER_AGENT)
        for (name in PASSED_IN) request.getHeader(name)?.let { builder.header(name, it) }
        if (request.method == "POST") builder.POST(HttpRequest.BodyPublishers.ofInputStream { request.inputStream })
        else builder.GET()
        val upstream: HttpResponse<InputStream> = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IOException) {
            log.warn("{} git {}: {}", full, route, e.message)
            return error(response, HttpStatus.BAD_GATEWAY, "GitHub: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return error(response, HttpStatus.BAD_GATEWAY, "GitHub: interrupted")
        }
        val status = upstream.statusCode()
        log.info("{} git {}: {}", full, route.substringBefore('?'), status)
        if (status >= 500) {
            upstream.body().close()
            return error(response, HttpStatus.BAD_GATEWAY, "GitHub: HTTP $status")
        }
        // GitHub's own 401/403/404 goes through as it is, so git shows GitHub's message.
        response.status = status
        for (name in PASSED_OUT) upstream.headers().firstValue(name).ifPresent { response.setHeader(name, it) }
        response.setHeader("Cache-Control", "no-cache")
        // A failure from here on cannot become a 502: the status is out, so the connection is closed and git sees a short body.
        upstream.body().use { it.streamTo(response.outputStream) }
    }

    /** [InputStream.transferTo] with a flush per chunk, so a slow pack reaches the client as GitHub sends it. */
    private fun InputStream.streamTo(out: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            out.flush()
        }
    }

    /** True (and 403 or 503 written) when [smithRepoRefusal] refuses the request on [repo]; false to go on. */
    private fun refused(request: HttpServletRequest, response: HttpServletResponse, repo: String): Boolean {
        val refusal = smithRepoRefusal(sandbox, request, repo, log) ?: return false
        error(response, refusal.first, refusal.second)
        return true
    }

    private fun error(response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.outputStream.write(engineJson.writeValueAsBytes(mapOf("error" to message)))
        response.outputStream.flush()
    }

    companion object {
        const val UPLOAD_PACK = "git-upload-pack"
        /** GitHub's smart HTTP answers the protocol only to a `git/` agent. */
        const val USER_AGENT = "git/2.0 (frakworks-engine)"
        /** Request headers forwarded when present; `Git-Protocol` carries the client's protocol v2 offer. */
        val PASSED_IN = listOf("Content-Type", "Content-Encoding", "Git-Protocol", "Accept")
        /** Response headers forwarded when present. */
        val PASSED_OUT = listOf("Content-Type", "Content-Encoding")
    }
}
