package com.codefrak.engine.camunda

/**
 * Whether an incident is its own root cause: an `Incident` or `HistoricIncident` with no
 * `rootCauseIncidentId`, or one naming itself. Takes the two plain fields rather than either
 * incident type, since both expose them without a shared supertype.
 */
object RootCauseIncidents {
    fun isRootCause(rootCauseIncidentId: String?, id: String): Boolean =
        rootCauseIncidentId == null || rootCauseIncidentId == id

    /** As [isRootCause], plus: also a root cause when its own cause isn't among [knownIds] (already ended). */
    fun isRootCause(rootCauseIncidentId: String?, id: String, knownIds: Set<String>): Boolean =
        isRootCause(rootCauseIncidentId, id) || rootCauseIncidentId !in knownIds
}
