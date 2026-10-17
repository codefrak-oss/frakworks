package com.codefrak.engine.sdk

import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.OUTPUT_PATH
import com.codefrak.engine.smith.Vars
import com.codefrak.sdk.CallableSmith
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `task-prepare-job` in bpmn/callable-smith.bpmn (engine/README.md, "Custom jobs and the SDK"): the job id is the
 * caller's `jobId` when it gave one, else this instance's id; the mode is `job`; a missing [CallableSmith.PROMPT] or
 * [CallableSmith.REPO] is refused here, before any sandbox starts. The prompt is a String, or the bytes a long one is
 * stored as (`Brief.textValue`: a String variable stops at 4000 characters, and a refactoring run's prompt is longer).
 */
@Component("prepareCallableSmith")
class PrepareCallableSmithDelegate : JavaDelegate {
    private val log = LoggerFactory.getLogger(PrepareCallableSmithDelegate::class.java)
    override fun execute(execution: DelegateExecution) {
        val prompt = Brief.text(execution.getVariable(CallableSmith.PROMPT))
        require(!prompt.isNullOrBlank()) { "callable-smith needs a ${CallableSmith.PROMPT}" }
        require(!(execution.getVariable(CallableSmith.REPO) as? String).isNullOrBlank()) { "callable-smith needs a ${CallableSmith.REPO} (owner/name): there is no default repo" }
        val jobId = (execution.getVariable(Vars.JOB_ID) as? String)?.takeIf { it.isNotBlank() } ?: execution.processInstanceId
        execution.setVariable(Vars.JOB_ID, jobId)
        execution.setVariable(Vars.MODE, "job")
        // A job run has no ticket: a caller's issue would make the run a ticket's and be written to.
        if (execution.getVariable(Vars.ISSUE) != null) execution.setVariable(Vars.ISSUE, null)
        log.info("callable-smith {}: job {} on {}", execution.processInstanceId, jobId, execution.getVariable(CallableSmith.REPO))
    }
}

/**
 * `task-set-result` in bpmn/callable-smith.bpmn: [CallableSmith.RESULT] is the job run's `result.json` as text
 * (`Vars.OUTPUT`, which `read result` has already checked against job.schema.json), for the caller to read. A result
 * past [Brief.STRING_VARIABLE_MAX] characters is stored as UTF-8 bytes instead (`Brief.textValue`), since a String
 * variable that long fails the flush; `Brief.text` reads either form.
 */
@Component("setCallableResult")
class SetCallableResultDelegate : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val bytes = execution.getVariable(Vars.OUTPUT) as? ByteArray ?: error("the job run left no $OUTPUT_PATH")
        val text = String(bytes, Charsets.UTF_8)
        execution.setVariable(CallableSmith.RESULT, if (text.length <= Brief.STRING_VARIABLE_MAX) text else Brief.textValue(text))
    }
}
