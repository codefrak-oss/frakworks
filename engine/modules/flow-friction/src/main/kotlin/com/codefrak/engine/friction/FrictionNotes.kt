package com.codefrak.engine.friction

import org.operaton.bpm.engine.HistoryService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** What a Smith or First mate gives for one friction note: note_friction's arguments, or one item of a Smith's retro answer. */
data class FrictionInput(val kind: String, val summary: String, val evidence: String? = null, val severity: String? = null) {
    /** [parse]'s answer: the note, or why it is refused. */
    sealed interface Parsed {
        data class Ok(val input: FrictionInput) : Parsed
        data class Bad(val why: String) : Parsed
    }

    companion object {
        val KINDS = listOf("bug", "design", "docs", "tooling", "other")
        val SEVERITIES = listOf("low", "medium", "high")
        val ARGS = listOf("kind", "summary", "evidence", "severity")
        const val MAX_SUMMARY = 2000
        const val MAX_EVIDENCE = 8000

        private fun quoted(v: Any?) = if (v is String) "\"$v\"" else v.toString()

        /**
         * [args] as a note, or why not: a key other than [ARGS], a kind outside [KINDS], a blank or overlong summary,
         * evidence that is not text or too long, a severity outside [SEVERITIES]. JSON null is as absent; [what] names the caller in the refusal.
         */
        fun parse(args: Map<String, Any?>, what: String = "note_friction"): Parsed {
            val extra = args.keys.filter { it !in ARGS }
            if (extra.isNotEmpty()) return Parsed.Bad("$what takes only ${ARGS.joinToString(", ") { quoted(it) }}, not ${extra.joinToString(", ") { quoted(it) }}")
            val kind = args["kind"]
            if (kind !is String || kind !in KINDS) return Parsed.Bad("kind is one of ${KINDS.joinToString(", ") { quoted(it) }}, not ${quoted(kind)}")
            val summary = (args["summary"] as? String)?.trim().orEmpty()
            if (summary.isEmpty()) return Parsed.Bad("a note needs a summary")
            if (summary.length > MAX_SUMMARY) return Parsed.Bad("a summary is at most $MAX_SUMMARY characters")
            val evidence = args["evidence"]
            if (evidence != null && evidence !is String) return Parsed.Bad("evidence is text, not ${quoted(evidence)}")
            if (evidence is String && evidence.length > MAX_EVIDENCE) return Parsed.Bad("evidence is at most $MAX_EVIDENCE characters")
            val severity = args["severity"]
            if (severity != null && severity !in SEVERITIES)
                return Parsed.Bad("severity is one of ${SEVERITIES.joinToString(", ") { quoted(it) }}, not ${quoted(severity)}")
            return Parsed.Ok(FrictionInput(kind, summary, (evidence as String?)?.trim()?.ifEmpty { null }, severity as String?))
        }
    }
}

/**
 * One row of `engine.friction_note`. The group fields (V39, V41; engine/README.md, "Friction notes") are the store's: the
 * [signature] is computed at insert ([FrictionKey]), [groupId], [groupedBy] and [jevCallId] are written by the
 * grouper ([FrictionGrouper]), null while the note waits for it, and [groupedLogin] by a user's pull-out
 * ([FrictionCorrections]). [twoThings] and [twoThingsCallId] (V43) are Jev's mark that the note describes two
 * different things, written by the grouper and cleared by a user ([FrictionCorrections.clearTwoThings]).
 */
data class FrictionNote(
    val id: String, val repo: String?, val issue: Long?, val runId: String?, val processInstanceId: String?,
    val notedAt: OffsetDateTime, val source: String, val kind: String, val summary: String,
    val evidence: String?, val severity: String?,
    /** First mate's caller's GitHub login, on a [FIRST_MATE] note; null on a Smith's. */
    val login: String? = null,
    /** The exact key, [FrictionKey.of] over the summary and evidence; null only on a note not yet inserted. */
    val signature: String? = null,
    val groupId: Long? = null,
    /** How it joined its group ([GroupedBy]); null while ungrouped. */
    val groupedBy: String? = null,
    /** The `friction-group` Jev call that grouped it, when one was made. */
    val jevCallId: String? = null,
    /** The login that pulled it out of its group (V41; [GroupedBy.USER]); null on every other row. */
    val groupedLogin: String? = null,
    /** Jev's `two-things` value when it marked the note at or above `engine.jev.friction-two-things`; null when unmarked. */
    val twoThings: Double? = null,
    /** The call that marked it: the `friction-group` call, or a lone `friction-two-things` one. */
    val twoThingsCallId: String? = null,
    /** The job id of a mode `job` sandbox or a plugin (V45), which has no ticket; null on every other note. */
    val jobId: String? = null,
) {
    companion object {
        /** A note_friction call during the run. */
        const val AS_YOU_GO = "as-you-go"
        /** An item of the retro turn's answer, after the run. */
        const val RETRO = "retro"
        /** A note_friction call from First mate (#968): no run or ticket, stamped with the caller's login. */
        const val FIRST_MATE = "first-mate"
        val SOURCES = listOf(AS_YOU_GO, RETRO, FIRST_MATE)

        /** The columns [read] takes, in its order. */
        const val COLUMNS = "id, repo, issue, run_id, process_instance_id, noted_at, source, kind, summary, evidence, severity, login, " +
            "signature, group_id, grouped_by, jev_call_id, grouped_login, two_things, two_things_call_id, job_id"
        const val COLUMN_COUNT = 20

        /** A row of [COLUMNS]. */
        fun read(rs: ResultSet) = FrictionNote(rs.getString(1), rs.getString(2), rs.getObject(3, Long::class.javaObjectType), rs.getString(4),
            rs.getString(5), rs.getObject(6, OffsetDateTime::class.java), rs.getString(7), rs.getString(8),
            rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
            rs.getString(13), rs.getObject(14, Long::class.javaObjectType), rs.getString(15), rs.getString(16), rs.getString(17),
            rs.getObject(18, Double::class.javaObjectType), rs.getString(19), jobId = rs.getString(20))
    }
}

/** read_friction_notes' filters; null is no filter. */
data class FrictionFilter(
    val repo: String? = null, val issue: Long? = null, val kind: String? = null, val source: String? = null,
    val since: OffsetDateTime? = null, val limit: Int = 100,
    /** Groups only: a group state ([FrictionFix.STATES]) or [FrictionFix.UNSOLVED]; null for every group. */
    val state: String? = null,
)

/** `engine.friction_note` and the root-flow lookup notes are stamped with. An interface so the MCP tools are tested without Spring. */
interface FrictionLog {
    fun insert(note: FrictionNote)
    /** Newest first, at most [FrictionFilter.limit]. */
    fun list(filter: FrictionFilter): List<FrictionNote>
    /** The root process instance of Smith run [runId], or null when the engine's history has none. */
    fun rootOf(runId: String): String?
}

/**
 * Stamps [input] with where it came from, Smith run [runId] on [repo]#[issue] and its root flow, and inserts it.
 * The Smith supplies none of the stamped fields. A mode `job` sandbox, or a plugin, has no ticket: [issue] is null
 * and [jobId] its job id (a plugin outside any run has no [runId] either).
 */
fun FrictionLog.record(repo: String?, issue: Long?, runId: String?, input: FrictionInput, source: String,
                       now: OffsetDateTime = OffsetDateTime.now(), jobId: String? = null): FrictionNote {
    val root = runId?.let { runCatching { rootOf(it) }.getOrNull() }
    val note = FrictionNote(UUID.randomUUID().toString(), repo, issue, runId, root, now, source,
        input.kind, input.summary, input.evidence, input.severity, signature = FrictionKey.of(input.summary, input.evidence), jobId = jobId)
    insert(note)
    return note
}

/** Stamps First mate's [input] with the caller's [login] and the [repo] it named, if any, and inserts it: no ticket, run or flow. */
fun FrictionLog.recordFirstMate(login: String, repo: String?, input: FrictionInput, now: OffsetDateTime = OffsetDateTime.now()): FrictionNote {
    val note = FrictionNote(UUID.randomUUID().toString(), repo, null, null, null, now, FrictionNote.FIRST_MATE,
        input.kind, input.summary, input.evidence, input.severity, login, FrictionKey.of(input.summary, input.evidence))
    insert(note)
    return note
}

/**
 * `engine.friction_note` (engine/README.md, "Its own tables"), written by note_friction and `collect result`'s retro.
 * Every insert writes the note's exact key ([FrictionKey]) in the same statement, for every source, and pokes the
 * grouper once the transaction has committed; nothing here groups.
 */
@Repository
class FrictionNotes(private val jdbc: JdbcClient, private val history: HistoryService, private val grouper: FrictionGrouper) : FrictionLog {
    private companion object {
        const val SELECT = "SELECT ${FrictionNote.COLUMNS} FROM engine.friction_note"
    }

    /**
     * In its own transaction, as `SmithRunStats.record`: a failed insert never marks a surrounding one rollback-only.
     * The key is [FrictionKey.of] over the summary and evidence, whatever [FrictionNote.signature] says. The grouper
     * is woken after the commit, never on this thread's time: the drain runs on its own thread.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun insert(note: FrictionNote) {
        jdbc.sql("""
            INSERT INTO engine.friction_note (id, repo, issue, run_id, process_instance_id, noted_at, source, kind, summary, evidence, severity, login, signature, job_id)
            VALUES (:id, :repo, :issue, :run, :pi, :at, :source, :kind, :summary, :evidence, :severity, :login, :signature, :job)""".trimIndent())
            .param("id", note.id).param("repo", note.repo).param("issue", note.issue).param("run", note.runId)
            .param("pi", note.processInstanceId).param("at", note.notedAt).param("source", note.source)
            .param("kind", note.kind).param("summary", note.summary).param("evidence", note.evidence)
            .param("severity", note.severity).param("login", note.login)
            .param("signature", FrictionKey.of(note.summary, note.evidence)).param("job", note.jobId)
            .update()
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = grouper.wake()
            })
        } else grouper.wake()
    }

    override fun list(filter: FrictionFilter): List<FrictionNote> {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        filter.repo?.let { clauses += "repo = :repo"; params["repo"] = it }
        filter.issue?.let { clauses += "issue = :issue"; params["issue"] = it }
        filter.kind?.let { clauses += "kind = :kind"; params["kind"] = it }
        filter.source?.let { clauses += "source = :source"; params["source"] = it }
        filter.since?.let { clauses += "noted_at >= :since"; params["since"] = it }
        val where = if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
        return jdbc.sql("$SELECT$where ORDER BY noted_at DESC, id DESC LIMIT :limit")
            .params(params).param("limit", filter.limit)
            .query { rs, _ -> FrictionNote.read(rs) }.list()
    }

    override fun rootOf(runId: String): String? =
        history.createHistoricProcessInstanceQuery().processInstanceId(runId).singleResult()?.rootProcessInstanceId
}
