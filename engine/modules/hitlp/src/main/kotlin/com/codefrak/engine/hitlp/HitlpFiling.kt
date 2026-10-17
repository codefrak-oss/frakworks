package com.codefrak.engine.hitlp

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One flow wait the engine files as a HITLP 0.2 task (#2458, part 4 of #2443): the user task it stands for
 * ([processInstance], [userTaskKey], the [nth] time the flow reached it), its [kind], the ticket ([repo], [issue]), the
 * [question] text, the [askBlock] (`options`, or `kind` `proposal` / `research` for the verbs those waits take), the
 * login the work is for ([askedBy], the asker vars), the run that reached it ([runId], [runLink]) and, for a merge or a
 * legacy acceptance, the pull request ([pr], [head]).
 */
data class FlowWait(
    val processInstance: String,
    val userTaskKey: String,
    val kind: HitlpAsserted.Kind,
    val repo: String,
    val issue: Long,
    val question: String,
    val askBlock: Map<String, Any?>? = null,
    val askedBy: String? = null,
    val runId: String? = null,
    val runLink: String? = null,
    val pr: Long? = null,
    val head: String? = null,
    val nth: Int = 1,
) {
    val assertedWait: HitlpAsserted.Wait get() = HitlpAsserted.Wait(kind, repo, issue, runId = runId, runLink = runLink, askedBy = askedBy)
}

/** What [HitlpFiling] filed: the stored [request] row, the server's [handle] and the [decisionUrl] a person decides at, null when the server gave none. */
data class HitlpFiled(val request: HitlpRequest, val handle: HitlpHandle, val decisionUrl: String?)

/**
 * Files a [FlowWait] on the HITLP server (#2458; the request shapes are #2457's Goal). [ask] is `human.ask`: options
 * from the ask block (a proposal's verbs, a research ack's, or the block's own `options`); a proposal's and a research
 * ack's `responseSchema` enumerates the verbs (the flows branch on them), every other ask's is a plain `{type: string}`,
 * so the user may answer a choice in their own words (#100096); deadline now + 7 days, `defaultOnTimeout: fail`.
 * [approve] is `human.approve` for a merge or a legacy acceptance: `action` naming the PR or the feature, `payload
 * {repo, pr, head}` and its [HitlpSchemas.digest], `scope {maxUses: 1, notAfter: deadline}`, deadline now + 24 h,
 * `requiresHuman: true`. Both carry [HitlpAsserted.envelope]; `task.ttl` is the deadline's remaining milliseconds.
 *
 * Each request is checked with [HitlpSchemas.requesterProblem] and filed through [HitlpRequester]; a problem, or no
 * server configured, is an [IllegalStateException] naming it. The `engine.hitlp_request` row is stored with the
 * idempotency key `<processInstance>:<userTaskKey>:<nth>`, `:r<n>` added on the n-th re-file. The posting delegates use it (#2462, #2467).
 */
@Component
class HitlpFiling(private val requester: HitlpRequester, private val requests: HitlpRequests) {
    companion object {
        val ASK_TTL: Duration = Duration.ofDays(7)
        val APPROVE_TTL: Duration = Duration.ofHours(24)
        const val DECISION_URL = "io.hitlp/decisionUrl"
        val PROPOSAL_VERBS = listOf("accept", "accept and build", "accept and build auto", "revise", "shelve", "reject")
        val RESEARCH_VERBS = listOf("accept", "save", "drop")
        private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private val URL = Regex("""https?://\S+""")

        /**
         * The envelope's `priority` (HITLP spec 4): a merge or an acceptance holds a live ticket, so it is `urgent` and a
         * surface interrupts for it (the desktop's popup, a time-sensitive push); a question is `high`.
         */
        const val APPROVE_PRIORITY = "urgent"
        const val ASK_PRIORITY = "high"

        /** The idempotency key of [wait], filed for the [refile]-th time again (0: the first filing). */
        fun key(wait: FlowWait, refile: Int = 0): String =
            "${wait.processInstance}:${wait.userTaskKey}:${wait.nth}" + (if (refile > 0) ":r$refile" else "")

        /** The `human.ask` arguments for [wait] at [now]. */
        fun askRequest(wait: FlowWait, refile: Int = 0, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): Map<String, Any?> {
            val options = options(wait.askBlock)
            return linkedMapOf<String, Any?>(
                "idempotencyKey" to key(wait, refile),
                "deadline" to iso(now.plus(ASK_TTL)),
                "defaultOnTimeout" to "fail",
                "priority" to ASK_PRIORITY,
                "context" to context(wait, "${wait.repo}#${wait.issue}"),
                "question" to wait.question,
                "responseSchema" to if (options == null || !verbMenu(wait.askBlock)) mapOf("type" to "string") else mapOf("type" to "string", "enum" to options.map { it["value"] }),
            ).apply { if (options != null) put("options", options) } + HitlpAsserted.envelope(wait.assertedWait, now)
        }

        /** The `human.approve` arguments for [wait] (a merge or a legacy acceptance) at [now]. */
        fun approveRequest(wait: FlowWait, refile: Int = 0, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): Map<String, Any?> {
            val pr = requireNotNull(wait.pr) { "an approve names its pull request: pr is required" }
            val action = if (wait.kind == HitlpAsserted.Kind.MERGE) "merge PR #$pr of ${wait.repo}" else "accept the merged feature #${wait.issue} of ${wait.repo}"
            val payload = linkedMapOf("repo" to wait.repo, "pr" to pr, "head" to wait.head)
            val deadline = iso(now.plus(APPROVE_TTL))
            return linkedMapOf<String, Any?>(
                "idempotencyKey" to key(wait, refile),
                "deadline" to deadline,
                "defaultOnTimeout" to "fail",
                "priority" to APPROVE_PRIORITY,
                "context" to context(wait, wait.question),
                "action" to action,
                "payload" to payload,
                "payloadDigest" to HitlpSchemas.digest(payload),
                "scope" to mapOf("maxUses" to 1, "notAfter" to deadline),
            ) + HitlpAsserted.envelope(wait.assertedWait, now) + ("requiresHuman" to true)
        }

        /**
         * A request's `context`: its [summary], and the repo (`owner/name`) and ticket (an integer) in `data`, so a
         * surface talking to the HITLP server directly can place the task (#100013; the schema closes `context`).
         */
        fun context(wait: FlowWait, summary: String): Map<String, Any?> =
            linkedMapOf("summary" to summary, "data" to linkedMapOf("repo" to wait.repo, "ticket" to wait.issue))

        /** The options of an ask block: its own `options`, a proposal's or a research ack's verbs; null for a free answer. */
        @Suppress("UNCHECKED_CAST")
        fun options(block: Map<String, Any?>?): List<Map<String, Any?>>? {
            if (block == null) return null
            (block["options"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let { list ->
                return list.map { o -> if (o is Map<*, *>) o as Map<String, Any?> else mapOf("value" to o.toString(), "label" to o.toString()) }
            }
            val verbs = when (block["kind"]) { "proposal" -> PROPOSAL_VERBS; "research" -> RESEARCH_VERBS; else -> return null }
            return verbs.map { mapOf("value" to it, "label" to it) }
        }

        /** Whether [block]'s options are a proposal's or a research ack's verbs, which the flows branch on: their schema keeps its `enum`. */
        fun verbMenu(block: Map<String, Any?>?): Boolean =
            block != null && (block["options"] as? List<*>).isNullOrEmpty() && block["kind"] in setOf("proposal", "research")

        /** Where a person decides [handle]: `_meta` `io.hitlp/decisionUrl`, else the first URL in its `statusMessage`, else null. */
        fun decisionUrl(handle: HitlpHandle): String? =
            handle.meta?.get(DECISION_URL)?.toString()?.takeIf { it.isNotBlank() }
                ?: handle.statusMessage?.let { URL.find(it)?.value?.trimEnd('.', ',', ')', ';') }

        private fun iso(t: OffsetDateTime) = ISO.format(t.withOffsetSameInstant(ZoneOffset.UTC).withNano(0))
    }

    /** Files [wait] as a `human.ask`. */
    fun ask(wait: FlowWait, refile: Int = 0): HitlpFiled {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        return file(wait, HitlpPrimitives.ASK, askRequest(wait, refile, now), now)
    }

    /** Files [wait] (a merge or a legacy acceptance) as a `human.approve`. */
    fun approve(wait: FlowWait, refile: Int = 0): HitlpFiled {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        return file(wait, HitlpPrimitives.APPROVE, approveRequest(wait, refile, now), now)
    }

    private fun file(wait: FlowWait, primitive: String, args: Map<String, Any?>, now: OffsetDateTime): HitlpFiled {
        check(requester.configured) { HitlpRequester.UNSET }
        HitlpSchemas.requesterProblem(primitive, args)?.let { throw IllegalStateException("hitlp request for ${args["idempotencyKey"]} breaks the $primitive schema: $it") }
        val deadline = OffsetDateTime.parse(args["deadline"] as String)
        val handle = requester.call("human.$primitive", args, Duration.between(now, deadline).toMillis().coerceAtLeast(1))
        val row = requests.save(HitlpRequest(taskId = handle.taskId, idempotencyKey = args["idempotencyKey"] as String,
            processInstance = wait.processInstance, userTaskKey = wait.userTaskKey, kind = wait.kind.name,
            pollIntervalMs = handle.pollInterval.toInt(), createdAt = now, nextPollAt = now.plusNanos(handle.pollInterval * 1_000_000L)))
        return HitlpFiled(row, handle, decisionUrl(handle))
    }
}
