package com.codefrak.engine.accounts

import com.codefrak.engine.engineJson
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime

/** Tokens per kind, as an Anthropic reply's `usage` block names them; a null kind is unknown. */
data class ClaudeTokens(val input: Long?, val output: Long?, val cacheRead: Long?, val cacheWrite: Long?) {
    companion object {
        /** The `usage` block of a `/v1/messages` reply body; null when the body has none. Nothing else of the body is kept. */
        fun ofReply(body: String?): ClaudeTokens? = runCatching {
            val usage = engineJson.readValue<Map<String, Any?>>(body ?: return null)["usage"] as? Map<*, *> ?: return null
            val n = { k: String -> (usage[k] as? Number)?.toLong() }
            ClaudeTokens(n("input_tokens"), n("output_tokens"), n("cache_read_input_tokens"), n("cache_creation_input_tokens"))
        }.getOrNull()
    }
}

/** One row of `engine.claude_usage`: what one Smith run or one of the engine's own calls consumed. */
data class ClaudeUsage(
    /** `smith-run`, `doctor`, `refactor-scan`, `job`, `summarizer`, `usage-probe` or `credential-verify` ([ClaudeUsageLedger.CALLERS]). */
    val caller: String,
    /** A Smith run's role ([ClaudeUsageLedger.roleOf] of its mode); null for the engine's own calls. */
    val role: String? = null,
    val runId: String? = null, val repo: String? = null, val issue: Long? = null,
    val account: String? = null, val model: String? = null,
    val started: OffsetDateTime? = null, val ended: OffsetDateTime,
    val durationMs: Long? = null, val costUsd: Double? = null, val tokens: ClaudeTokens? = null,
)

/** Where the engine's direct Claude calls are recorded; tests and the code that builds a caller by hand use [NONE]. */
fun interface ClaudeUsageSink {
    fun record(u: ClaudeUsage)

    companion object {
        val NONE = ClaudeUsageSink { }
    }
}

/** One row of `GET /accounts/consumption`: the sums of one role or caller over the window; null where no row of it knew the figure. */
data class ConsumptionRow(
    /** The role (`coding`, `correctness`, …) of a ticket round, else the caller (`doctor`, `summarizer`, …); `total` on the total row. */
    val key: String, val caller: String, val calls: Long,
    val costUsd: Double?, val durationMs: Long?,
    val inputTokens: Long?, val outputTokens: Long?, val cacheReadTokens: Long?, val cacheWriteTokens: Long?,
    /** How many of the rows lacked a figure, per column: a sum over part of the calls is shown as such. */
    val unknown: Map<String, Long> = emptyMap(),
)

/**
 * `engine.claude_usage`, the consumption ledger (engine/README.md, "Consumption ledger"): written by
 * `collect result` for every Smith run and by the summarizer and the credential probe for the
 * engine's own calls; read by `GET /accounts/consumption`. A failed write never fails its caller.
 */
@Repository
class ClaudeUsageLedger(private val jdbc: JdbcClient) : ClaudeUsageSink {
    private val log = LoggerFactory.getLogger(ClaudeUsageLedger::class.java)

    /** In its own transaction, so a failed insert never marks the caller's rollback-only. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun record(u: ClaudeUsage) {
        runCatching {
            jdbc.sql("""
                INSERT INTO engine.claude_usage (caller, role, run_id, repo, issue, account, model, started, ended, duration_ms, cost_usd,
                    input_tokens, output_tokens, cache_read_tokens, cache_write_tokens)
                VALUES (:caller, :role, :run, :repo, :issue, :account, :model, :started, :ended, :duration, :cost, :input, :output, :read, :write)""".trimIndent())
                .param("caller", u.caller).param("role", u.role).param("run", u.runId).param("repo", u.repo).param("issue", u.issue)
                .param("account", u.account).param("model", u.model).param("started", u.started).param("ended", u.ended)
                .param("duration", u.durationMs).param("cost", u.costUsd)
                .param("input", u.tokens?.input).param("output", u.tokens?.output)
                .param("read", u.tokens?.cacheRead).param("write", u.tokens?.cacheWrite)
                .update()
        }.onFailure { log.warn("claude usage of {} {} not recorded: {}", u.caller, u.runId ?: "", it.message) }
    }

    /** Every row that ended in [since, until), oldest first. */
    fun between(since: OffsetDateTime, until: OffsetDateTime): List<ClaudeUsage> =
        jdbc.sql("""
            SELECT caller, role, run_id, repo, issue, account, model, started, ended, duration_ms, cost_usd,
                input_tokens, output_tokens, cache_read_tokens, cache_write_tokens
            FROM engine.claude_usage WHERE ended >= :since AND ended < :until ORDER BY ended""".trimIndent())
            .param("since", since).param("until", until)
            .query { rs, _ ->
                val l = { i: Int -> rs.getObject(i, Long::class.javaObjectType) }
                val tokens = listOf(l(12), l(13), l(14), l(15))
                ClaudeUsage(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), l(5), rs.getString(6), rs.getString(7),
                    rs.getObject(8, OffsetDateTime::class.java), rs.getObject(9, OffsetDateTime::class.java), l(10),
                    rs.getObject(11, Double::class.javaObjectType),
                    if (tokens.all { it == null }) null else ClaudeTokens(tokens[0], tokens[1], tokens[2], tokens[3]))
            }.list()

    /**
     * The consumption rows of [since, until): one per Smith-run role (caller `smith-run`), then one
     * per other caller, then `total`. A column's sum is null when no row in it knew the figure.
     */
    fun consumption(since: OffsetDateTime, until: OffsetDateTime): List<ConsumptionRow> {
        val rows = between(since, until)
        val groups = rows.groupBy { (if (it.caller == SMITH_RUN) it.role ?: "coding" else it.caller) to it.caller }
        val ordered = groups.entries.sortedWith(compareBy({ it.key.second != SMITH_RUN }, { it.key.first }))
        return ordered.map { (k, us) -> sum(k.first, k.second, us) } + sum(TOTAL, TOTAL, rows)
    }

    private fun sum(key: String, caller: String, us: List<ClaudeUsage>): ConsumptionRow {
        val columns = mapOf<String, (ClaudeUsage) -> Number?>(
            "costUsd" to { it.costUsd }, "durationMs" to { it.durationMs },
            "inputTokens" to { it.tokens?.input }, "outputTokens" to { it.tokens?.output },
            "cacheReadTokens" to { it.tokens?.cacheRead }, "cacheWriteTokens" to { it.tokens?.cacheWrite },
        )
        val known = columns.mapValues { (_, f) -> us.mapNotNull(f) }
        val long = { c: String -> known[c]!!.takeIf { it.isNotEmpty() }?.sumOf { it.toLong() } }
        return ConsumptionRow(key, caller, us.size.toLong(),
            known["costUsd"]!!.takeIf { it.isNotEmpty() }?.sumOf { it.toDouble() }, long("durationMs"),
            long("inputTokens"), long("outputTokens"), long("cacheReadTokens"), long("cacheWriteTokens"),
            known.mapValues { (_, v) -> us.size.toLong() - v.size }.filterValues { it > 0 })
    }

    companion object {
        const val SMITH_RUN = "smith-run"
        const val TOTAL = "total"
        val CALLERS = listOf(SMITH_RUN, "doctor", "refactor-scan", "job", "summarizer", "usage-probe", "credential-verify")

        /** A Smith run's role as the ticket timeline names it (TicketTimelineController.ROLES): `work` is `coding`, `review` is `correctness`, any other mode its own name. */
        fun roleOf(mode: String): String = when (mode) {
            "work" -> "coding"
            "review" -> "correctness"
            else -> mode
        }

        /** A Smith run's caller, by the root flow it ran under: the doctor's and the refactor scan's are not ticket rounds. */
        fun callerOf(rootFlowKind: String?): String = when (rootFlowKind) {
            "doctor" -> "doctor"
            "refactor-scan" -> "refactor-scan"
            "job" -> "job"
            else -> SMITH_RUN
        }
    }
}
