package com.codefrak.engine.camunda

import com.codefrak.engine.github.issueUrl
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.springframework.stereotype.Component

/**
 * Every root process instance tied to an issue, newest first: the ones `handled_issue`
 * started on it (IssueBoard's first lookup), the intake whose issue it is (its
 * `engine.intake_ticket` row, the board's second lookup), and any flow carrying the issue as its `repo`
 * and `issue` variables (a chore started from an intake, a bug). What
 * `GET /camunda/instances?issueUrl=` lists and `GET /ticket/{owner}/{repo}/{n}/timeline` reads.
 */
@Component
class IssueInstances(private val history: HistoryService, private val handled: HandledIssues, private val intakes: IntakeRecords) {
    fun roots(repo: String, issue: Long): List<HistoricProcessInstance> {
        val fromRows = handled.list(repo).filter { it.issue == issue }.mapNotNull { it.processInstance }
            .mapNotNull { history.createHistoricProcessInstanceQuery().processInstanceId(it).singleResult() }
        val byUrl = listOfNotNull(intakes.intakeOf(issueUrl(repo, issue)))
            .mapNotNull { history.createHistoricProcessInstanceQuery().processInstanceId(it).singleResult() }
        val byRepo = history.createHistoricProcessInstanceQuery()
            .variableValueEquals(Vars.REPO, repo).variableValueEquals(Vars.ISSUE, issue).list()
        return (fromRows + byUrl + byRepo).filter { it.superProcessInstanceId == null }.distinctBy { it.id }
            .sortedByDescending { it.startTime }
    }
}
