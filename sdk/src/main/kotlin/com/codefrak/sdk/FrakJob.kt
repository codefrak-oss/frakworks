package com.codefrak.sdk

/**
 * A custom job a plugin jar contributes (engine/README.md, "Custom jobs and the SDK"): the engine calls
 * [execute] once per run of the job, with the run's [JobContext]. A job is a Spring bean in the plugin's
 * `Frak-Plugin-Package`; the engine, not the plugin, decides when it runs (a later part of #1212 registers
 * and schedules jobs; this interface is what they implement).
 */
interface FrakJob {
    /** One run of the job. Throwing fails the run; the engine records the exception and moves on. */
    fun execute(ctx: JobContext)
}

/**
 * What one run of a [FrakJob] sees: its id, the target repo it runs for, its configuration, and the
 * process variables of the flow instance it runs in, which a job reads with [getVariable] and writes
 * with [setVariable] (a value set here reaches the flow's next step, and a `callable-smith` it starts).
 */
interface JobContext {
    /** The run's job id: what a Smith sandbox started for it is named by (`smith-job-<jobId>-<run8>`). */
    val jobId: String
    /** The target repo the job runs for, `owner/name`; every job has one (CLAUDE.md, "there is no default repo"). */
    val repo: String
    /** The job's configuration, as the engine stored it when the job was registered; empty when it has none. */
    val config: Map<String, Any?>
    /** A process variable of the run's flow instance, or null when it has none by that name. */
    fun getVariable(name: String): Any?
    /** Sets a process variable on the run's flow instance; null removes it. */
    fun setVariable(name: String, value: Any?)
}
