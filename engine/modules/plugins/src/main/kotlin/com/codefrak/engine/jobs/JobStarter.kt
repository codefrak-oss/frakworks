package com.codefrak.engine.jobs

import com.codefrak.engine.smith.Vars
import com.codefrak.sdk.CallableSmith
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Starts one run of a job: a `job` process instance (bpmn/job.bpmn) with the job's row as variables, and the row's
 * `last_run_at`, `last_instance` and `next_run_at` stamped (engine/README.md, "Custom jobs and the SDK"). A job's runs
 * never overlap: [running] is what the controller's 409s and the ticker's skip ask.
 */
@Component
class JobStarter(private val runtime: RuntimeService, private val store: JobStore) {
    private val log = LoggerFactory.getLogger(JobStarter::class.java)

    /** Starts the job's process and returns the instance's id. */
    fun start(job: Job, now: Instant = Instant.now()): String {
        val vars = mutableMapOf<String, Any?>(
            Vars.JOB_ID to job.id, Vars.REPO to job.repo, KIND to job.kind.id, NAME to job.name, CONFIG to job.config, OWNER to job.owner.id,
        )
        if (job.kind == JobKind.PROMPT) vars[CallableSmith.PROMPT] = job.configNode()["prompt"]?.asString()
        val instance = runtime.startProcessInstanceByKey(PROCESS_KEY, vars).id
        store.markStarted(job.id, instance, now, job.parsedSignal().next(now))
        log.info("job {} ({}) on {}: started {}", job.name, job.id, job.repo, instance)
        return instance
    }

    /** The id of the active `job` instance running this job, or null when none is. */
    fun running(job: Job): String? =
        runtime.createProcessInstanceQuery().processDefinitionKey(PROCESS_KEY).variableValueEquals(Vars.JOB_ID, job.id)
            .list().firstOrNull()?.id

    companion object {
        /** The root process of bpmn/job.bpmn. */
        const val PROCESS_KEY = "job"
        /** The job's kind, name, config (the JSON text) and owner as `job` instance variables; `jobId`, `repo` and `prompt` are `Vars`'. */
        const val KIND = "kind"; const val NAME = "name"; const val CONFIG = "config"; const val OWNER = "owner"
    }
}
