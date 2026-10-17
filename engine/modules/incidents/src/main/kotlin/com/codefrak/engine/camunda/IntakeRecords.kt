package com.codefrak.engine.camunda

import com.codefrak.engine.intake.IntakeQuestion

/**
 * What a ticket's instances and timeline read of the intake flow (engine/README.md, "Engine modules"), implemented by
 * the `intake` package: the intake whose issue an issue is, and the questions an instance asked.
 */
interface IntakeRecords {
    /** The intake process instance whose issue [issueUrl] is, or null. */
    fun intakeOf(issueUrl: String): String?

    /** The questions [instance] asked, in order (`engine.intake_question`). */
    fun questions(instance: String): List<IntakeQuestion>
}
