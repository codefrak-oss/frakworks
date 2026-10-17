package com.codefrak.engine.mcp

import com.codefrak.engine.camunda.JobAttempts
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.Incident

/**
 * read_job_attempts (#1174), on [DiagnosticsMcpServer]: every failed attempt of a job, oldest first, from Operaton's
 * historic job log ([JobAttempts]), the rows `GET /camunda/instances/{id}` serves as an incident's `attempts`.
 * By `job_id`, or by `incident_id`: an open `failedJob` incident's job (its `configuration`).
 */
class JobAttemptsTool(private val runtime: RuntimeService, private val attempts: JobAttempts) {
    companion object {
        const val NAME = "read_job_attempts"
        private val ARGS = listOf("incident_id", "job_id")

        val TOOL = McpTool(NAME,
            "Read-only: every failed attempt of a job, oldest first, from the engine's historic job log: {jobId, attempts}, " +
                "each attempt {at, message, retriesLeft} (its time, its exception message, the retries the job had left). " +
                "The incident's message is the last of them. Pass exactly one of `incident_id` (an open failedJob incident, " +
                "as act_on_incident takes it) or `job_id`.",
            mapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "incident_id" to linkedMapOf("type" to "string", "description" to "An open failedJob incident's id (an incident row's actionId); its job is read."),
                    "job_id" to linkedMapOf("type" to "string", "description" to "The job's id."),
                ),
                "additionalProperties" to false,
            ))
    }

    fun call(args: Map<String, Any?>): McpToolResult {
        val extra = args.keys.filter { it !in ARGS }
        if (extra.isNotEmpty())
            return McpToolResult.error("$NAME takes only ${ARGS.joinToString(", ") { json(it) }}, not ${extra.joinToString(", ") { json(it) }}")
        val incidentId = args["incident_id"]; val jobId = args["job_id"]
        if ((incidentId == null) == (jobId == null)) return McpToolResult.error("$NAME takes exactly one of \"incident_id\" or \"job_id\"")
        val job = if (jobId != null) {
            (jobId as? String)?.takeIf { it.isNotBlank() } ?: return McpToolResult.error("not a job id: ${json(jobId)}")
        } else {
            val id = (incidentId as? String)?.takeIf { it.isNotBlank() } ?: return McpToolResult.error("not an incident id: ${json(incidentId)}")
            val incident = runtime.createIncidentQuery().incidentId(id).singleResult()
                ?: return McpToolResult.error("no open incident $id")
            if (incident.incidentType != Incident.FAILED_JOB_HANDLER_TYPE)
                return McpToolResult.error("incident $id is a ${incident.incidentType} incident, not a failedJob: it has no job")
            incident.configuration
        }
        return McpToolResult.of(linkedMapOf("jobId" to job, "attempts" to attempts.rows(job)))
    }
}
