package com.codefrak.engine

import com.codefrak.engine.jobs.PluginFlows
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.migration.MigratingProcessInstanceValidationException
import org.operaton.bpm.engine.migration.MigrationPlanValidationException
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Moves running process instances onto the newest deployed version of their
 * key at startup (engine/README.md, "Process migration"). Operaton never does
 * this by itself, so a BPMN change would otherwise strand every in-flight
 * instance, children of `smith-run` included, on the definition it started on.
 *
 * It runs first among the `ApplicationReadyEvent` listeners: after the BPMN is
 * deployed (while the process engine bean is built), before Operaton's
 * `ProcessApplicationEventPublisher` starts the job executor and before the
 * startup scans correlate anything. Operaton's `PostDeployEvent` is published
 * only for an `@EnableProcessApplication` app, which this engine is not.
 *
 * Per key, each stale (old version, latest version) pair gets
 * `mapEqualActivities().updateEventTriggers()` and a synchronous `execute()`.
 * A plan or instance that fails validation (an activity removed or renamed)
 * is logged at WARN and those instances stay where they are; nothing here
 * ever stops the engine from starting. A version pair that needs more than
 * equal-id mapping gets its explicit instructions in [explicitMappings].
 */
@Component
class ProcessMigrator(
    private val repository: RepositoryService,
    private val runtime: RuntimeService,
) {
    private val log = LoggerFactory.getLogger(ProcessMigrator::class.java)

    /**
     * Activity mappings a BPMN change needs on top of equal ids, by key and
     * (old version, new version): old activity id to new activity id. Empty
     * until a PR removes or renames an activity a running instance can wait at.
     */
    internal val explicitMappings: Map<String, Map<Pair<Int, Int>, Map<String, String>>> = emptyMap()

    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun atStartup() = migrate()

    /** Migrates every key, or only [keys] (the tests, which share one database). Never throws. */
    fun migrate(keys: Set<String>? = null) {
        val latest = runCatching { repository.createProcessDefinitionQuery().latestVersion().list() }
            .getOrElse { log.warn("process migration skipped: could not list process definitions: {}", it.toString()); return }
        // A flow job's diagrams (a `job:<id>` deployment, "Custom jobs and the SDK") are the job's to version: never migrated.
        val jobDeployments = runCatching { repository.createDeploymentQuery().deploymentNameLike("${PluginFlows.deploymentName("")}%").list().map { it.id }.toSet() }
            .getOrElse { log.warn("process migration: could not list job deployments, none skipped: {}", it.toString()); emptySet() }
        val (plugin, own) = latest.filter { keys == null || it.key in keys }.sortedBy { it.key }.partition { it.deploymentId in jobDeployments }
        if (plugin.isNotEmpty()) log.info("process migration ignores {} plugin flow definition(s) of job deployments: {}", plugin.size, plugin.joinToString { it.key })
        own.forEach { def ->
            try {
                migrateKey(def.key, def.id, def.version)
            } catch (e: Exception) {
                log.warn("process migration of {} failed, its instances stay where they are: {}", def.key, e.toString())
            }
        }
    }

    private fun migrateKey(key: String, latestId: String, latestVersion: Int) {
        // One query per key: which definitions its running instances are on.
        val stale = runtime.createProcessInstanceQuery().processDefinitionKey(key).list()
            .filter { it.processDefinitionId != latestId }
            .groupBy({ it.processDefinitionId }, { it.id })
        if (stale.isEmpty()) {
            log.info("process migration {} v{}: nothing stale", key, latestVersion)
            return
        }
        val moved = sortedMapOf<Int, Int>()
        val stayed = sortedMapOf<Int, Int>()
        stale.forEach { (oldId, ids) ->
            val oldVersion = repository.getProcessDefinition(oldId).version
            if (migratePair(key, oldId, oldVersion, latestId, latestVersion, ids)) moved[oldVersion] = ids.size
            else stayed[oldVersion] = ids.size
        }
        log.info("process migration {} v{}: {}{}", key, latestVersion,
            if (moved.isEmpty()) "moved none" else "moved ${moved.values.sum()} from ${moved.entries.joinToString { "v${it.key} (${it.value})" }}",
            if (stayed.isEmpty()) "" else "; left ${stayed.values.sum()} on ${stayed.entries.joinToString { "v${it.key} (${it.value})" }}")
    }

    private fun migratePair(key: String, oldId: String, oldVersion: Int, newId: String, newVersion: Int, ids: List<String>): Boolean {
        val plan = try {
            val builder = runtime.createMigrationPlan(oldId, newId)
            explicitMappings[key]?.get(oldVersion to newVersion)?.forEach { (from, to) -> builder.mapActivities(from, to) }
            builder.mapEqualActivities().updateEventTriggers().build()
        } catch (e: MigrationPlanValidationException) {
            log.warn("process migration {} v{} -> v{} skipped, the plan fails validation: {}", key, oldVersion, newVersion, oneLine(e))
            return false
        }
        return try {
            runtime.newMigration(plan).processInstanceIds(ids).execute()
            true
        } catch (e: MigratingProcessInstanceValidationException) {
            log.warn("process migration {} v{} -> v{} skipped, an instance fails validation: {}", key, oldVersion, newVersion, oneLine(e))
            false
        } catch (e: Exception) {
            log.warn("process migration {} v{} -> v{} failed, its instances stay where they are: {}", key, oldVersion, newVersion, e.toString())
            false
        }
    }

    /** Operaton's validation messages carry the report, one failure per line; a log line gets them on one. */
    private fun oneLine(e: Exception) = (e.message ?: e.toString()).trim().replace(Regex("\\s*\n\\s*"), " | ")
}
