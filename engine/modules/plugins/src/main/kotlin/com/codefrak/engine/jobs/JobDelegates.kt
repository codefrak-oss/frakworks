package com.codefrak.engine.jobs

import com.codefrak.engine.engineJson
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.repo
import com.codefrak.sdk.JobContext
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.readValue

/** The `job` frame for this instance (engine/README.md, "Events"). */
private fun DelegateExecution.jobEvent(state: String, outcome: String? = null) = EngineEvent.Job(
    repo = repo(), jobId = getVariable(Vars.JOB_ID) as String, name = getVariable(JobStarter.NAME) as? String ?: "",
    kind = getVariable(JobStarter.KIND) as String, instance = processInstanceId, state = state, outcome = outcome,
    owner = getVariable(JobStarter.OWNER) as? String ?: "user",
)

/** `task-note-job-started` in bpmn/job.bpmn: the run's `engine.job_run` row ([JobRuns]) and the `job` frame `started`, after the start commits. */
@Component("noteJobStarted")
class NoteJobStartedDelegate(private val events: EngineEvents, private val runs: JobRuns) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        runs.started(execution.processInstanceId, execution.getVariable(Vars.JOB_ID) as String, execution.repo(),
            execution.getVariable(JobStarter.KIND) as String)
        events.publishAfterCommit(execution.jobEvent("started"))
    }
}

/**
 * `task-note-job-ended` in bpmn/job.bpmn: the `job` frame `ended` with `outcome`: the callable-smith's for a prompt
 * job (`done` or `failed`), `failed: <message>` for a bean job whose `FrakJob` threw; `done` when a branch set none.
 * The run's `engine.job_run` row is closed with the outcome and `summary` ([JobRuns.ended]).
 */
@Component("noteJobEnded")
class NoteJobEndedDelegate(private val events: EngineEvents, private val runs: JobRuns) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val outcome = execution.getVariable(OUTCOME) as? String ?: "done"
        runs.ended(execution.processInstanceId, outcome, execution.getVariable(SUMMARY) as? String)
        events.publishAfterCommit(execution.jobEvent("ended", outcome))
    }

    companion object {
        const val OUTCOME = JOB_OUTCOME
        /** What the run filed, or why nothing (a refactor run's). */
        const val SUMMARY = "summary"
    }
}

/**
 * `task-run-bean-job` in bpmn/job.bpmn: the job's `FrakJob` from its jar ([PluginJars.acquire], a no-op while the job holds
 * the loaded jar, the reload after an engine restart) run with an [ExecutionJobContext] over this execution. An exception,
 * the job's or the loader's, sets `outcome` to `failed: <message>` and the flow goes on to `task-note-job-ended`: no incident.
 */
@Component("runBeanJob")
class RunBeanJobDelegate(private val store: JobStore, private val pluginJars: PluginJars) : JavaDelegate {
    private val log = LoggerFactory.getLogger(RunBeanJobDelegate::class.java)
    override fun execute(execution: DelegateExecution) {
        val jobId = execution.getVariable(Vars.JOB_ID) as String
        val name = execution.getVariable(JobStarter.NAME) as? String ?: jobId
        try {
            val job = store.get(jobId) ?: throw IllegalStateException("job $jobId no longer exists")
            val config = job.configNode()
            val frakJob = pluginJars.acquire(config["jar"].asString(), config["bean"].asString(), jobId)
            frakJob.execute(ExecutionJobContext(jobId, job.repo, engineJson.readValue(job.config), execution))
        } catch (e: Exception) {
            log.warn("job {} ({}) failed: {}", name, jobId, e.toString())
            execution.setVariable(NoteJobEndedDelegate.OUTCOME, "failed: ${e.message ?: e::class.java.simpleName}")
        }
    }
}

/** The [JobContext] a bean job runs with: the job's id, repo and config, and the `job` instance's variables through its execution. */
class ExecutionJobContext(
    override val jobId: String, override val repo: String, override val config: Map<String, Any?>, private val execution: DelegateExecution,
) : JobContext {
    override fun getVariable(name: String): Any? = execution.getVariable(name)
    override fun setVariable(name: String, value: Any?) { if (value == null) execution.removeVariable(name) else execution.setVariable(name, value) }
}

/**
 * `task-prepare-flow-job` in bpmn/job.bpmn: the job's row by `jobId`, its jar held for the job ([PluginJars.acquireContext],
 * a no-op while the job holds it, the reload after an engine restart) so the plugin flow's `${name}`s resolve
 * ([PluginBeanResolver]), and `jobProcessKey` set to the config's `process`, the key `call-plugin-flow` calls with every
 * variable of this instance. A job gone from the table or a jar gone from the directory throws: an incident on this task.
 */
@Component("prepareFlowJob")
class PrepareFlowJobDelegate(private val store: JobStore, private val pluginJars: PluginJars) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val jobId = execution.getVariable(Vars.JOB_ID) as String
        val job = store.get(jobId) ?: throw IllegalStateException("job $jobId no longer exists")
        val config = job.configNode()
        pluginJars.acquireContext(config["jar"].asString(), jobId)
        execution.setVariable(PROCESS_KEY_VAR, config["process"].asString())
    }

    companion object {
        /** The variable `call-plugin-flow`'s `calledElement` reads. */
        const val PROCESS_KEY_VAR = "jobProcessKey"
    }
}
