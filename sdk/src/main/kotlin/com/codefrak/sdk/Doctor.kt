package com.codefrak.sdk

/**
 * An open incident a ticket's flow is stuck on (engine/README.md, "Doctor"): the Operaton incident's id, the
 * process instance and process it is on, the activity it failed at, its type (`failedJob`, `failedExternalTask`),
 * its message, a failed job's id, and the ticket the flow works.
 */
data class Incident(
    val id: String, val processInstanceId: String, val processKey: String, val activityId: String?, val type: String,
    val message: String?, val jobId: String?, val repo: String, val issue: Long,
)

/** What a [Doctor] makes of an incident: a summary for the doctor's history, and what the engine does with it. */
data class Diagnosis(val summary: String, val remedy: Remedy)

/** The engine's two answers to a plugin diagnosis: retry the incident, or record the diagnosis and move on. */
enum class Remedy { RETRY, NONE }

/**
 * A plugin doctor (engine/README.md, "Custom jobs and the SDK"): the engine's doctor asks every plugin doctor
 * bean, in order, about a stuck ticket's open incident before it starts a Smith doctor run. The first non-null
 * [Diagnosis] is recorded in the doctor's history with target `plugin:<bean>`; a `RETRY` retries the incident,
 * `NONE` records it and moves on. With no answer from any, the Smith doctor runs as it does today. An exception
 * is logged and counts as no answer.
 */
interface Doctor {
    fun diagnose(incident: Incident): Diagnosis?
}
