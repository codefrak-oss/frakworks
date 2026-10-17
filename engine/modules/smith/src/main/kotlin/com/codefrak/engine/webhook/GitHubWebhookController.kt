package com.codefrak.engine.webhook

import com.codefrak.engine.engineJson
import com.codefrak.engine.board.BoardRows
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.ROW_WAIT
import com.codefrak.engine.events.rowWithin
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * GitHub's webhook deliveries, served by the engine itself (engine/README.md, "When it scans"):
 * the one path the Tailscale Funnel fronts, so the one path of the API that is not otherwise
 * unauthenticated. Each target repo has its own webhook secret, sealed on its row ([WebhookSecrets]):
 * the payload's `repository.full_name` is read, unverified, only to pick whose secret checks the
 * delivery's `X-Hub-Signature-256`. A delivery that names no repo, a repo that is no target repo or
 * one with no secret, or that has a bad or missing signature, is 401, and nothing else in it is
 * read. A signed one is 204 whatever its event, and wakes the scan ([WebhookWake]) only when its
 * event and action are allowlisted. Of the payload only that repo name, its top-level `action`, the
 * `issue.number` (or `pull_request.number`), for a label event `label.name`, and for an `issues`
 * event `issue.state` are read, and it is never kept or logged. The last three feed one `ticket` frame
 * per `issues`, `issue_comment` or `pull_request` delivery (engine/README.md, "Events"), read after the signature is verified and
 * never before; the engine's own GitHub writes come back through here like anyone's, so
 * `RestGitHub` publishes none. For a `push` delivery, `ref`, `before`, `after` and
 * `repository.default_branch` are read, after the signature, into one `push` frame; a push is not
 * allowlisted, so it wakes no scan. There is no engine-wide secret.
 *
 * An `issues` delivery whose `issue.state` is `open` puts the issue's board row on its `ticket` frame, read by number
 * ([BoardRows.row]) because GitHub's open-issue list lags a new issue by seconds. The read runs on a virtual thread
 * ([rowWithin]) and the delivery waits for it at most [ROW_WAIT], well inside GitHub's ten-second delivery timeout, so a slow GitHub read
 * never makes GitHub retry the delivery: past the bound, or when the read fails, the frame goes out with `issue: null`.
 * The frame is still published on the request thread, so frames keep the deliveries' order.
 */
@RestController
class GitHubWebhookController(
    private val wake: WebhookWake,
    private val secrets: WebhookSecrets,
    private val events: EngineEvents,
    @Lazy private val rows: BoardRows,
) {
    /** How long a delivery waits for its `ticket` frame's row; [ROW_WAIT], shortened by tests. */
    var rowWait: Duration = ROW_WAIT

    @PostMapping("/hooks/github")
    fun hook(request: HttpServletRequest): ResponseEntity<Void> {
        // Refused before a byte is read when it says it is too big; read at most one byte past the cap otherwise.
        if (request.contentLengthLong > MAX_BODY) return status(HttpStatus.PAYLOAD_TOO_LARGE)
        val body = readCapped(request.inputStream) ?: return status(HttpStatus.PAYLOAD_TOO_LARGE)
        val repo = repo(body) ?: return status(HttpStatus.UNAUTHORIZED)
        val secret = secrets.webhookSecret(repo) ?: return status(HttpStatus.UNAUTHORIZED)
        if (!verify(secret, body, request.getHeader("X-Hub-Signature-256"))) return status(HttpStatus.UNAUTHORIZED)
        val event = request.getHeader("X-GitHub-Event") ?: ""
        val action = action(body)
        wake.offer(event, action)
        ticket(event, action, repo, body)?.let { events.publish(if (wantsRow(event, body)) it.copy(issue = rows.rowWithin(repo, it.number, rowWait)) else it) }
        push(event, repo, body)?.let { events.publish(it) }
        return status(HttpStatus.NO_CONTENT)
    }

    private fun status(status: HttpStatus) = ResponseEntity.status(status).build<Void>()

    companion object {
        /** 1 MiB, as the old receiver had it; a delivery is a few KB. */
        const val MAX_BODY = 1 shl 20

        /** The body, or null when it is longer than [MAX_BODY] (chunked, so no Content-Length said so). */
        fun readCapped(input: InputStream): ByteArray? {
            val bytes = input.readNBytes(MAX_BODY + 1)
            return bytes.takeIf { it.size <= MAX_BODY }
        }

        /** `"sha256=" + hex(HMAC-SHA256(secret, body))`, compared in constant time. */
        fun verify(secret: String, body: ByteArray, header: String?): Boolean {
            if (header == null || !header.startsWith("sha256=")) return false
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            val expected = "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
            return MessageDigest.isEqual(expected.toByteArray(), header.toByteArray())
        }

        /** The payload's `repository.full_name`, or null: absent, not a string, or the body is not a JSON object. Unverified. */
        fun repo(body: ByteArray): String? = runCatching {
            engineJson.readTree(body).get("repository")?.get("full_name")?.takeIf { it.isString }?.asString()
        }.getOrNull()

        /** The payload's top-level `action`, or null: absent, not a string, or the body is not a JSON object. */
        fun action(body: ByteArray): String? = runCatching {
            engineJson.readTree(body).get("action")?.takeIf { it.isString }?.asString()
        }.getOrNull()

        /** The deliveries that are a `ticket` frame, and the frame's `action` for the two whose own is not it. */
        private val TICKET_EVENTS = mapOf("issues" to null, "issue_comment" to "commented", "pull_request" to "pr")

        /**
         * The `ticket` frame a verified delivery is, or null: an `issues` delivery with its `action` as GitHub names it
         * and, for `labeled` and `unlabeled`, `label.name`; an `issue_comment` one as `commented`; a `pull_request` one as
         * `pr` with the pull request's number as `number` and `prNumber`. Nothing else of the payload is read, and a
         * delivery of another event, with no action, or with no number is no frame.
         */
        fun ticket(event: String, action: String?, repo: String, body: ByteArray): EngineEvent.Ticket? {
            if (event !in TICKET_EVENTS || action == null) return null
            return runCatching {
                val root = engineJson.readTree(body)
                val key = if (event == "pull_request") "pull_request" else "issue"
                val number = root.get(key)?.get("number")?.takeIf { it.isIntegralNumber }?.asLong() ?: return null
                val label = if (event == "issues" && action in LABEL_ACTIONS) root.get("label")?.get("name")?.takeIf { it.isString }?.asString() else null
                EngineEvent.Ticket(repo, number, TICKET_EVENTS[event] ?: action, label, number.takeIf { event == "pull_request" })
            }.getOrNull()
        }

        private val LABEL_ACTIONS = setOf("labeled", "unlabeled")

        /**
         * Whether the `ticket` frame carries the issue's board row: an `issues` delivery whose `issue.state` is `open`.
         * Not a closed issue (the board does not list it), an `issue_comment` or a `pull_request` delivery.
         */
        fun wantsRow(event: String, body: ByteArray): Boolean = event == "issues" && runCatching {
            engineJson.readTree(body).get("issue")?.get("state")?.takeIf { it.isString }?.asString() == "open"
        }.getOrDefault(false)

        /**
         * The `push` frame a verified `push` delivery is, or null for another event or a payload without `ref`,
         * `before` or `after` as strings. `default` is whether `ref` is `refs/heads/<repository.default_branch>`
         * (false when the payload names no default branch). Nothing else of the payload is read.
         */
        fun push(event: String, repo: String, body: ByteArray): EngineEvent.Push? {
            if (event != "push") return null
            return runCatching {
                val root = engineJson.readTree(body)
                fun str(name: String) = root.get(name)?.takeIf { it.isString }?.asString()
                val ref = str("ref") ?: return null
                val before = str("before") ?: return null
                val after = str("after") ?: return null
                val defaultBranch = root.get("repository")?.get("default_branch")?.takeIf { it.isString }?.asString()
                EngineEvent.Push(repo, ref, before, after, default = defaultBranch != null && ref == "refs/heads/$defaultBranch")
            }.getOrNull()
        }
    }
}
