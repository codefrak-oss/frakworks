package com.codefrak.engine.camunda

import java.time.OffsetDateTime

/**
 * The open incident a flow is stuck on, as the issues board and the incident-fix endpoints name it:
 * `id` is the root cause's (what `POST /camunda/incidents/{id}/retry`, `/fix` and `/propose` take),
 * `processInstanceId` the process it is on, which may be a called one.
 */
data class IncidentView(
    val id: String, val activityId: String?, val type: String, val message: String?,
    val processInstanceId: String, val createdAt: OffsetDateTime?,
)
