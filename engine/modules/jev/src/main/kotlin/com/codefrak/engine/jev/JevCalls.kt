package com.codefrak.engine.jev

import com.codefrak.engine.engineJson
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import tools.jackson.module.kotlin.readValue

/** One row of `engine.jev_call`; `response` is null on a failed call, `error` on a good one. */
data class JevCall(
    val id: String, val calledAt: OffsetDateTime, val useCase: String, val processInstance: String?, val repo: String?,
    val issue: Long?, val account: String?, val model: String, val request: String, val response: String?, val error: String?,
    val latencyMs: Long?, val threshold: Double?, val action: String?, val outcome: String?, val outcomeAt: OffsetDateTime?,
)

/**
 * Which calls `GET /jev/calls` answers; each field null for any. `action` and an `outcome` value match as a prefix;
 * `outcome` [SET] or [UNSET] asks whether the call has one at all. `since` is inclusive, `until` exclusive, so one
 * run's calls are those between its start and its end; `processInstance` keeps the calls one flow made.
 */
data class JevFilter(
    val useCase: String? = null, val repo: String? = null, val issue: Long? = null, val action: String? = null,
    val outcome: String? = null, val since: OffsetDateTime? = null, val until: OffsetDateTime? = null,
    val processInstance: String? = null,
) {
    fun where(): Pair<String, Map<String, Any>> {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        useCase?.let { clauses += "use_case = :useCase"; params["useCase"] = it }
        repo?.let { clauses += "repo = :repo"; params["repo"] = it }
        issue?.let { clauses += "issue = :issue"; params["issue"] = it }
        action?.let { clauses += "action LIKE :action ESCAPE '!'"; params["action"] = like(it) }
        when (outcome) {
            null -> {}
            SET -> clauses += "outcome IS NOT NULL"
            UNSET -> clauses += "outcome IS NULL"
            else -> { clauses += "outcome LIKE :outcome ESCAPE '!'"; params["outcome"] = like(outcome) }
        }
        processInstance?.let { clauses += "process_instance = :processInstance"; params["processInstance"] = it }
        since?.let { clauses += "called_at >= :since"; params["since"] = it }
        until?.let { clauses += "called_at < :until"; params["until"] = it }
        return (if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")) to params
    }

    companion object {
        const val SET = "set"; const val UNSET = "unset"
        private fun like(prefix: String) = prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
    }
}

/**
 * `engine.jev_call`: every Jev call, written by [HttpJevDecider] as it is
 * made; the flow that asked sets the [setAction] it took and, once it
 * learns whether the pick was right, the [recordOutcome] (engine/README.md,
 * "Jev accounts").
 */
@Repository
class JevCalls(private val jdbc: JdbcClient) {
    /** Writes the call and answers its id. */
    fun record(
        useCase: String, context: JevContext, account: String?, model: String, request: String,
        response: String?, error: String?, latencyMs: Long?, threshold: Double?,
    ): String {
        val id = UUID.randomUUID().toString()
        jdbc.sql("""INSERT INTO engine.jev_call (id, use_case, process_instance, repo, issue, account, model, request, response, error, latency_ms, threshold)
            VALUES (:id, :u, :p, :r, :i, :a, :m, :q, :s, :e, :l, :t)""")
            .param("id", id).param("u", useCase).param("p", context.processInstance).param("r", context.repo).param("i", context.issue)
            .param("a", account).param("m", model).param("q", request).param("s", response).param("e", error)
            .param("l", latencyMs).param("t", threshold).update()
        return id
    }

    /**
     * What the engine did with the pick (re-asked, kept the rule's reading, ...), and the `threshold` it
     * applied when that differs from the one the call was made with; false when there is no such call.
     * Cuts [action] at the column's [ACTION_CHARS] characters.
     */
    fun setAction(id: String, action: String, threshold: Double? = null): Boolean {
        val a = action.take(ACTION_CHARS)
        return if (threshold == null) jdbc.sql("UPDATE engine.jev_call SET action = :a WHERE id = :id").param("a", a).param("id", id).update() == 1
        else jdbc.sql("UPDATE engine.jev_call SET action = :a, threshold = :t WHERE id = :id")
            .param("a", a).param("t", threshold).param("id", id).update() == 1
    }

    /**
     * Merges `{key: value}` into the call's stored `response` (a JSON object; a response that is not one is kept under
     * `raw`, a null one starts empty), so what the engine made of the answer (the SSH judge's reasoning) is on the row.
     * False when there is no such call.
     */
    fun annotate(id: String, key: String, value: Any?): Boolean {
        val call = get(id) ?: return false
        val base: Map<String, Any?> = call.response?.let { r ->
            runCatching { engineJson.readValue<Map<String, Any?>>(r) }.getOrElse { mapOf("raw" to r) } } ?: emptyMap()
        return jdbc.sql("UPDATE engine.jev_call SET response = :s WHERE id = :id")
            .param("s", engineJson.writeValueAsString(base + (key to value))).param("id", id).update() == 1
    }

    /** What the flow learned afterwards: whether the pick was right. False when there is no such call. */
    fun recordOutcome(id: String, outcome: String): Boolean =
        jdbc.sql("UPDATE engine.jev_call SET outcome = :o, outcome_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("o", outcome).param("id", id).update() == 1

    fun get(id: String): JevCall? =
        jdbc.sql("$SELECT WHERE id = :id").param("id", id).query(ROW).optional().orElse(null)

    /** The calls [filter] keeps, newest first, `limit` of them from `offset`, and how many it keeps in all (`GET /jev/calls`). */
    fun list(filter: JevFilter, limit: Int, offset: Long): Pair<List<JevCall>, Long> {
        val (where, params) = filter.where()
        val total = jdbc.sql("SELECT COUNT(*) FROM engine.jev_call$where").params(params).query(Long::class.java).single()
        val rows = jdbc.sql("$SELECT$where ORDER BY called_at DESC, id DESC LIMIT :limit OFFSET :offset")
            .params(params).param("limit", limit).param("offset", offset).query(ROW).list()
        return rows to total
    }

    /** Every call from `since` on, oldest first, for `GET /jev/summary`. */
    fun window(since: OffsetDateTime): List<JevCall> =
        jdbc.sql("$SELECT WHERE called_at >= :since ORDER BY called_at, id").param("since", since).query(ROW).list()

    private companion object {
        /** engine.jev_call.action is VARCHAR(100); the text is cut there. */
        const val ACTION_CHARS = 100
        const val SELECT = "SELECT id, called_at, use_case, process_instance, repo, issue, account, model, request, response, error, " +
            "latency_ms, threshold, action, outcome, outcome_at FROM engine.jev_call"
        val ROW = { rs: ResultSet, _: Int ->
            JevCall(rs.getString(1), rs.getObject(2, OffsetDateTime::class.java), rs.getString(3), rs.getString(4), rs.getString(5),
                (rs.getObject(6) as? Number)?.toLong(), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                (rs.getObject(12) as? Number)?.toLong(), (rs.getObject(13) as? Number)?.toDouble(), rs.getString(14), rs.getString(15),
                rs.getObject(16, OffsetDateTime::class.java))
        }
    }
}
