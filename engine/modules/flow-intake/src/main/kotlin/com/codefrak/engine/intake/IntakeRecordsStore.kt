package com.codefrak.engine.intake

import com.codefrak.engine.camunda.IntakeRecords
import org.springframework.stereotype.Component

/** [IntakeRecords] from [IntakeTickets] and [IntakeQuestions]. */
@Component
class IntakeRecordsStore(private val tickets: IntakeTickets, private val questions: IntakeQuestions) : IntakeRecords {
    override fun intakeOf(issueUrl: String) = tickets.byIssueUrl(issueUrl)?.intake
    override fun questions(instance: String) = questions.list(instance)
}
