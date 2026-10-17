package com.codefrak.engine.jobs

import com.codefrak.engine.bpmn.FlowRules
import com.codefrak.engine.bpmn.PluginFlowRules
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.model.bpmn.Bpmn
import org.operaton.bpm.model.bpmn.instance.Process
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream

/** A flow job's jar diagrams were refused; [problems] is every problem found, the registrar's 400 lists them one per line. */
class PluginFlowException(val problems: List<String>) : RuntimeException(problems.joinToString("\n"))

/**
 * The flow branch of the job registrar (engine/README.md, "Custom jobs and the SDK": flow jobs). [register] loads the
 * job's jar as a bean job's is loaded ([PluginJars.acquireContext]), reads every `bpmn/<name>.bpmn` in it, holds each to
 * [FlowRules.problems] and [PluginFlowRules.pluginProblems] against the jar's own beans and classes, checks the config's
 * `process` is one of the jar's executable processes and that no deployment defines any of the jar's keys yet, and
 * deploys them all in one deployment named `job:<id>` with source `plugin`, in the same transaction as the row
 * insert: Operaton runs its commands on the application's transaction manager, so a refusal or a failing insert
 * leaves no row, no deployment and no loaded context. [undeploy] is `DELETE /jobs/{id}`'s: 409 while an instance of
 * the deployment's definitions runs, else the deployment goes (cascade false: finished history stays).
 */
@Component
class PluginFlows(
    private val pluginJars: PluginJars, private val store: JobStore, private val repository: RepositoryService,
    private val runtime: RuntimeService, private val engine: ApplicationContext,
) {
    private val log = LoggerFactory.getLogger(PluginFlows::class.java)

    @Transactional
    fun register(name: String, repo: String, config: JsonNode, signal: Signal, login: String?, id: String): Job {
        val jar = config["jar"].asString()
        val key = config["process"].asString()
        val entry = pluginJars.acquireContext(jar, id)
        try {
            val diagrams = entry.diagrams()
            val problems = mutableListOf<String>()
            val keys = mutableListOf<String>()
            if (diagrams.isEmpty()) problems += "jar $jar has no bpmn/*.bpmn diagram"
            val binding = entry.binding(engine)
            diagrams.forEach { (entryName, bytes) ->
                val model = try { Bpmn.readModelFromStream(ByteArrayInputStream(bytes)) } catch (e: Exception) {
                    problems += "$entryName: cannot be parsed: ${e.message ?: e::class.java.simpleName}"; return@forEach
                }
                problems += FlowRules.problems(model, entryName)
                problems += PluginFlowRules.pluginProblems(model, entryName, binding)
                keys += model.getModelElementsByType(Process::class.java).filter { it.isExecutable }.map { it.id }
            }
            if (diagrams.isNotEmpty() && key !in keys) problems += "process $key is not an executable process of $jar's diagrams (${keys.sorted().joinToString().ifEmpty { "none" }})"
            keys.forEach { k ->
                repository.createProcessDefinitionQuery().processDefinitionKey(k).latestVersion().singleResult()?.let { def ->
                    val holder = repository.createDeploymentQuery().deploymentId(def.deploymentId).singleResult()?.name ?: def.deploymentId
                    problems += "process $k is already deployed by $holder; a key belongs to one deployment"
                }
            }
            if (problems.isNotEmpty()) throw PluginFlowException(problems)
            val builder = repository.createDeployment().name(deploymentName(id)).source(SOURCE)
            diagrams.forEach { (entryName, bytes) -> builder.addInputStream(entryName, ByteArrayInputStream(bytes)) }
            val deployment = builder.deploy()
            val job = store.insert(name, JobKind.FLOW, repo, config, signal, login, id = id)
            log.info("job {} ({}) deployed {} ({}: {}) from {}", name, id, deployment.name, deployment.id, keys.sorted().joinToString(), jar)
            return job
        } catch (e: Exception) {
            pluginJars.release(id)
            throw e
        }
    }

    /**
     * Undeploys the job's `job:<id>` deployment: a 409 [ResponseStatusException] while any instance of its definitions
     * runs; nothing when the job has no deployment. The caller deletes the row and releases the jar after.
     */
    fun undeploy(job: Job) {
        val deployment = repository.createDeploymentQuery().deploymentName(deploymentName(job.id)).singleResult() ?: return
        val running = runtime.createProcessInstanceQuery().deploymentId(deployment.id).count()
        if (running > 0) throw ResponseStatusException(HttpStatus.CONFLICT, "job ${job.id}'s flow is running in $running instance(s) of ${deployment.name}; it cannot be deleted until they end")
        repository.deleteDeployment(deployment.id, false)
        log.info("job {} ({}) undeployed {} ({})", job.name, job.id, deployment.name, deployment.id)
    }

    companion object {
        /** The deployment source every plugin flow deployment carries. */
        const val SOURCE = "plugin"
        private const val PREFIX = "job:"
        /** The deployment name of a job's diagrams. */
        fun deploymentName(jobId: String) = "$PREFIX$jobId"
        /** The job id a deployment name carries, or null for any other deployment. */
        fun jobIdOf(deploymentName: String?): String? = deploymentName?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotEmpty() }
    }
}
