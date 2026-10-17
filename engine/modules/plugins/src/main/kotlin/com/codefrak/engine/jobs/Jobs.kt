package com.codefrak.engine.jobs

import com.codefrak.engine.engineJson
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.JsonNode
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * What a job is: the `engine.job.kind` column, and which `config` keys the kind takes
 * (engine/README.md, "Custom jobs and the SDK").
 */
enum class JobKind(val keys: Set<String>) {
    /** `{"prompt": markdown}`: a `callable-smith` run on the prompt. */
    PROMPT(setOf("prompt")),
    /** `{"jar", "bean"}`: the `FrakJob` bean of a plugin jar in `PluginJars.DIRECTORY`, loaded at registration (`PluginJars`). */
    BEAN(setOf("jar", "bean")),
    /** `{"jar", "process"}`: a process of a plugin jar's `bpmn/` diagrams, deployed as `job:<id>` at registration (`PluginFlows`) and called by `job`. */
    FLOW(setOf("jar", "process")),
    /**
     * `{}`: a system-owned refactoring job (engine/README.md, "Refactoring jobs"), made by enabling `engine.refactor_config`
     * for a repo, never by `POST /jobs`; its run reads the repo's config row, not its own config.
     */
    REFACTOR(emptySet());

    val id get() = name.lowercase()

    /** Refuses with an [IllegalArgumentException] naming the reason: a missing key, an extra one, or a blank value. */
    fun check(config: JsonNode?) {
        if (keys.isEmpty()) {
            require(config == null || config.isNull || (config.isObject && config.size() == 0)) { "a $id job takes no config" }
            return
        }
        require(config != null && config.isObject) { "config is an object with ${keys.joinToString()}" }
        val given = config.propertyNames().toSet()
        val extra = given - keys
        require(extra.isEmpty()) { "a $id job's config takes ${keys.sorted().joinToString()}, not ${extra.sorted().joinToString()}" }
        val missing = keys - given
        require(missing.isEmpty()) { "a $id job's config needs ${missing.sorted().joinToString()}" }
        keys.forEach { k -> require(config[k]?.let { it.isString && it.asString().isNotBlank() } == true) { "config.$k is a non-blank string" } }
    }

    companion object {
        /** The kind by its id, or an [IllegalArgumentException] naming the kinds. */
        fun of(id: String?): JobKind = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown job kind '$id'; there is ${entries.joinToString { it.id }}")
    }
}

/** Who owns a job (`engine.job.owner`, V55): a user registered it, or the engine made it for a repo's refactoring config. */
enum class JobOwner {
    USER, SYSTEM;

    val id get() = name.lowercase()

    companion object {
        fun of(id: String): JobOwner = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown job owner '$id'")
    }
}

/** One `engine.job` row; `config` and `signal` are the JSON texts as stored. */
data class Job(
    val id: String, val name: String, val kind: JobKind, val repo: String, val config: String, val signal: String,
    val createdBy: String?, val createdAt: OffsetDateTime, val nextRunAt: OffsetDateTime?, val lastRunAt: OffsetDateTime?,
    val lastInstance: String?, val owner: JobOwner = JobOwner.USER,
) {
    /** The signal, parsed; a stored row always parses. */
    fun parsedSignal(): Signal = Signal.parse(signal)

    /** The config as a node. */
    fun configNode(): JsonNode = engineJson.readTree(config)
}

/** `engine.job` (V46). */
@Repository
class JobStore(private val jdbc: JdbcClient) : JobCreators {
    /** Inserts a job, its `next_run_at` from the signal at [now], and returns it; the id is minted here unless given ([newId]). */
    fun insert(name: String, kind: JobKind, repo: String, config: JsonNode, signal: Signal, createdBy: String?, now: Instant = Instant.now(), id: String = newId(),
               owner: JobOwner = JobOwner.USER): Job {
        jdbc.sql("""INSERT INTO engine.job (id, name, kind, repo, config, signal, created_by, created_at, next_run_at, owner)
            VALUES (:id, :n, :k, :r, :c, :s, :b, :at, :next, :o)""")
            .param("id", id).param("n", name).param("k", kind.id).param("r", repo).param("c", engineJson.writeValueAsString(config))
            .param("s", signalJson(signal)).param("b", createdBy).param("at", now.atOffset(ZoneOffset.UTC))
            .param("next", signal.next(now)?.atOffset(ZoneOffset.UTC)).param("o", owner.id).update()
        return get(id)!!
    }

    /** A new signal for the job, and its `next_run_at` from that signal at [now] (a refactoring schedule change). */
    fun updateSignal(id: String, signal: Signal, now: Instant = Instant.now()) {
        jdbc.sql("UPDATE engine.job SET signal = :s, next_run_at = :next WHERE id = :id")
            .param("s", signalJson(signal)).param("next", signal.next(now)?.atOffset(ZoneOffset.UTC)).param("id", id).update()
    }

    private fun signalJson(signal: Signal): String = when (signal) {
        is Signal.Every -> """{"kind":"every","every":${signal.seconds}}"""
        Signal.Execute -> """{"kind":"execute"}"""
        is Signal.At -> """{"kind":"at","at":"${signal.at}"}"""
    }

    fun get(id: String): Job? = jdbc.sql("$SELECT WHERE id = :id").param("id", id).query(ROW).optional().orElse(null)
    override fun createdBy(id: String): String? = get(id)?.createdBy

    /** Every job, or those of one repo, oldest first. */
    fun list(repo: String? = null): List<Job> =
        if (repo == null) jdbc.sql("$SELECT ORDER BY created_at, id").query(ROW).list()
        else jdbc.sql("$SELECT WHERE LOWER(repo) = :r ORDER BY created_at, id").param("r", repo.lowercase()).query(ROW).list()

    /** The jobs whose `next_run_at` has come at [now]. */
    fun due(now: Instant): List<Job> =
        jdbc.sql("$SELECT WHERE next_run_at IS NOT NULL AND next_run_at <= :now ORDER BY next_run_at, id")
            .param("now", now.atOffset(ZoneOffset.UTC)).query(ROW).list()

    /** False when there was no such job. */
    fun delete(id: String): Boolean = jdbc.sql("DELETE FROM engine.job WHERE id = :id").param("id", id).update() == 1

    /** A run started: `last_run_at`, `last_instance`, and the next due time (null for a signal with no clock). */
    fun markStarted(id: String, instance: String, at: Instant, nextRunAt: Instant?) {
        jdbc.sql("UPDATE engine.job SET last_run_at = :at, last_instance = :i, next_run_at = :next WHERE id = :id")
            .param("at", at.atOffset(ZoneOffset.UTC)).param("i", instance).param("next", nextRunAt?.atOffset(ZoneOffset.UTC)).param("id", id).update()
    }

    /** A tick skipped the job (its last instance still runs): only the next due time moves, so the missed run is not caught up. */
    fun pushNext(id: String, nextRunAt: Instant?) {
        jdbc.sql("UPDATE engine.job SET next_run_at = :next WHERE id = :id").param("next", nextRunAt?.atOffset(ZoneOffset.UTC)).param("id", id).update()
    }

    companion object {
        /** A fresh job id: the registrar mints one before it loads a bean job's jar, so the loader knows the holder. */
        fun newId(): String = UUID.randomUUID().toString()
        private const val SELECT = "SELECT id, name, kind, repo, config, signal, created_by, created_at, next_run_at, last_run_at, last_instance, owner FROM engine.job"
        private val ROW = { rs: ResultSet, _: Int ->
            Job(rs.getString("id"), rs.getString("name"), JobKind.of(rs.getString("kind")), rs.getString("repo"), rs.getString("config"),
                rs.getString("signal"), rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime::class.java),
                rs.getObject("next_run_at", OffsetDateTime::class.java), rs.getObject("last_run_at", OffsetDateTime::class.java),
                rs.getString("last_instance"), JobOwner.of(rs.getString("owner")))
        }
    }
}
