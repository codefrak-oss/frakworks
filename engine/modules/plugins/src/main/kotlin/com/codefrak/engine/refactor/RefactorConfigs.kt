package com.codefrak.engine.refactor

import com.codefrak.engine.engineJson
import com.codefrak.engine.badRequest
import com.codefrak.engine.refuseUnknownFields
import com.codefrak.engine.jobs.JobKind
import com.codefrak.engine.jobs.JobOwner
import com.codefrak.engine.jobs.JobStore
import com.codefrak.engine.jobs.Signal
import com.codefrak.engine.repos.TargetRepos
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.ZoneOffset

/**
 * Reads and writes a repo's refactoring config and keeps its one system job in step (engine/README.md, "Refactoring
 * jobs"): enabling inserts the job (owner `system`, kind `refactor`, signal `every <schedule>`), disabling deletes it,
 * a schedule change while enabled rewrites its signal. One transaction per [set].
 */
@Component
class RefactorConfigs(private val store: RefactorConfigStore, private val jobs: JobStore, private val repos: TargetRepos) {
    private val log = LoggerFactory.getLogger(RefactorConfigs::class.java)

    /** `GET`: the repo's config, or 404 for a repo that is not a target. */
    fun get(repo: String): RefactorConfig {
        if (!repos.contains(repo)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "$repo is not a target repo")
        return store.get(repo)
    }

    /**
     * `PUT`: every key optional, an absent one keeps the current value; `values` as JSON null returns to the shipped text,
     * `tier` as JSON null to the job run's default.
     * 400 names what is wrong. Returns the config as it now is.
     */
    @Transactional
    fun set(repo: String, body: JsonNode, login: String?, now: Instant = Instant.now()): RefactorConfig {
        val current = get(repo)
        if (!body.isObject) throw badRequest("the body is an object with enabled, schedule, values, maxTickets, maxLines, approval and tier")
        refuseUnknownFields(body.propertyNames().toSet().sorted(), KEYS, KEYS.joinToString(", "))
        val enabled = body["enabled"]?.let { it.takeIf { n -> n.isBoolean }?.asBoolean() ?: throw badRequest("enabled is true or false") } ?: current.enabled
        val schedule = body["schedule"]?.let {
            it.takeIf { n -> n.isIntegralNumber && n.asLong() >= RefactorConfig.MIN_SCHEDULE }?.asLong()
                ?: throw badRequest("schedule is a whole number of seconds, at least ${RefactorConfig.MIN_SCHEDULE}")
        } ?: current.schedule
        val (values, valuesDefault) = when (val v = body["values"]) {
            null -> current.values to current.valuesDefault
            else -> if (v.isNull) RefactorValues.DEFAULT to true
            else v.takeIf { it.isString && it.asString().isNotBlank() && it.asString().length <= RefactorConfig.MAX_VALUES_LENGTH }?.asString()?.let { it to false }
                ?: throw badRequest("values is a non-blank text of at most ${RefactorConfig.MAX_VALUES_LENGTH} characters, or null for the shipped text")
        }
        val maxTickets = body["maxTickets"]?.let {
            it.takeIf { n -> n.isIntegralNumber && n.asInt() in RefactorConfig.TICKETS_RANGE }?.asInt()
                ?: throw badRequest("maxTickets is a whole number from ${RefactorConfig.TICKETS_RANGE.first} to ${RefactorConfig.TICKETS_RANGE.last}")
        } ?: current.maxTickets
        val maxLines = body["maxLines"]?.let {
            it.takeIf { n -> n.isIntegralNumber && n.asInt() in RefactorConfig.LINES_RANGE }?.asInt()
                ?: throw badRequest("maxLines is a whole number from ${RefactorConfig.LINES_RANGE.first} to ${RefactorConfig.LINES_RANGE.last}")
        } ?: current.maxLines
        val approval = body["approval"]?.let {
            try { RefactorApproval.of(it.takeIf { n -> n.isString }?.asString() ?: "") } catch (e: IllegalArgumentException) { throw badRequest(e.message!!) }
        } ?: current.approval
        val tier = when (val t = body["tier"]) {
            null -> current.tier
            else -> if (t.isNull) null
            else t.takeIf { it.isIntegralNumber && it.asInt() in RefactorConfig.TIER_RANGE }?.asInt()
                ?: throw badRequest("tier is a whole number from ${RefactorConfig.TIER_RANGE.first} to ${RefactorConfig.TIER_RANGE.last}, or null for the job run's default")
        }
        val by = login?.trim()?.takeIf { it.isNotEmpty() }

        // The one system job: made on enabling, deleted on disabling, its signal rewritten on a schedule change.
        val existing = current.jobId?.let { jobs.get(it) }
        val jobId = when {
            enabled && existing == null -> jobs.insert(JOB_NAME_PREFIX + repo, JobKind.REFACTOR, repo, engineJson.createObjectNode(), Signal.Every(schedule), by, now, owner = JobOwner.SYSTEM).id
                .also { log.info("refactoring on {} enabled by {}: system job {} every {} s", repo, by, it, schedule) }
            enabled -> existing!!.id.also { if (schedule != current.schedule) jobs.updateSignal(it, Signal.Every(schedule), now) }
            existing != null -> {
                // A running instance finishes; its delegates find the row disabled and end the run `failed: refactoring is off`.
                jobs.delete(existing.id)
                log.info("refactoring on {} disabled by {}: system job {} deleted", repo, by, existing.id)
                null
            }
            else -> null
        }
        val next = RefactorConfig(repo, enabled, schedule, values, valuesDefault, maxTickets, maxLines, approval, jobId, now.atOffset(ZoneOffset.UTC), by, tier)
        store.upsert(next, now, by)
        return next
    }

    companion object {
        val KEYS = setOf("enabled", "schedule", "values", "maxTickets", "maxLines", "approval", "tier")
        /** The system job's name is this and the repo. */
        const val JOB_NAME_PREFIX = "refactor "
    }
}
