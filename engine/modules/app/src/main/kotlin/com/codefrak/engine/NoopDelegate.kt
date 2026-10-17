package com.codefrak.engine

import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Placeholder for any task not built yet: logs the activity and moves on. Bound by `${noop}`. */
@Component("noop")
class NoopDelegate : JavaDelegate {
    private val log = LoggerFactory.getLogger(NoopDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        log.info("noop: {} ({}) in {}", execution.currentActivityId, execution.currentActivityName,
            execution.processDefinitionId.substringBefore(':'))
    }
}
