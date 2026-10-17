package com.codefrak.engine.jobs

/** What a job's flow ends with (`noteJobEnded`, bpmn/job.bpmn): `NoteJobEndedDelegate.OUTCOME`, set by the job's own steps. */
const val JOB_OUTCOME = "outcome"

/** Who created a custom job (`JobStore`): what the refactoring job's scan files its tickets as, without the root's job store. */
fun interface JobCreators {
    /** Job [id]'s creator, or null for no such job. */
    fun createdBy(id: String): String?
}
