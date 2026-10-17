package com.codefrak.engine.smith

import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.WorkItemRefused
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `task-note-rerun` in bpmn/reusable-smith-run.bpmn (engine/README.md, "Incidents"): the run `call-smith-run` called
 * ended on its account's session or spend limit with another account usable, or ended failed for the first time in
 * its step, or was evicted by the cluster ([Vars.EVICT_NOTE]) ([Vars.RERUN]), so the call activity is entered again and a new Smith runs on a new pick. Says so on the
 * ticket's issue, in a retry's words, so the thread shows why there were two runs. A timed rerun ([Vars.RERUN_AT]: every
 * account of the provider was limited) is noted once, before `event-wait-reset` waits, naming the reset it waits for. Never fails: a comment refused (an unknown ticket) must not park the flow on the incident the rerun avoids.
 * A run with no issue (mode `job`, `callable-smith`) has no thread: nothing is written for it.
 */
@Component("noteSmithRerun")
class NoteSmithRerunDelegate(private val tickets: Tickets) : JavaDelegate {
    private val log = LoggerFactory.getLogger(NoteSmithRerunDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val failed = execution.getVariable(Vars.FAIL_NOTE) as? String
        val evicted = execution.getVariable(Vars.EVICT_NOTE) as? String
        val note = evicted ?: failed?.let { "$it; this is its one automatic rerun, a second failure parks on an incident" }
            ?: execution.getVariable(Vars.LIMIT_NOTE) as? String
        val at = (execution.getVariable(Vars.RERUN_AT) as? java.util.Date)?.toInstant()
        log.warn("flow {}: smith run ended {}; started again{}", execution.processInstanceId, note, at?.let { " at $it" } ?: "")
        val issue = execution.issueOrNull() ?: return
        runCatching {
            val repo = execution.repo()
            val root = (execution as? org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity)?.rootProcessInstanceId
                ?: execution.processInstanceId
            val kind = execution.processEngineServices.runtimeService.createProcessInstanceQuery().processInstanceId(root)
                .singleResult()?.processDefinitionId?.substringBefore(':') ?: "flow"
            val again = if (at != null) "starts its Smith run again on this $kind (`$root`) at $at" else "started its Smith run again on this $kind (`$root`)"
            val item = tickets.forTicket(repo, issue) ?: throw WorkItemRefused("no ticket $repo#$issue")
            tickets.comment(item.id, "engine", "The engine $again: the last one ended " +
                "${note ?: "on its account's session or spend limit"}.\n\n— engine")
        }.onFailure { log.warn("flow {}: rerun comment not posted: {}", execution.processInstanceId, it.message) }
    }
}
