package com.codefrak.engine

import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.ExecutionListener
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.springframework.stereotype.Component

/**
 * For an activity that must not be reached until it is built: a start listener
 * (on a user task) or the delegate (on a service task). With `asyncBefore` on
 * that activity, the throw fails its job and leaves an incident on it instead
 * of a silent wait or a wrong write. Bound by `${notBuilt}`.
 */
@Component("notBuilt")
class NotBuiltListener : ExecutionListener, JavaDelegate {
    override fun notify(execution: DelegateExecution) = notBuilt(execution)

    override fun execute(execution: DelegateExecution) = notBuilt(execution)

    private fun notBuilt(execution: DelegateExecution): Nothing =
        throw UnsupportedOperationException("${execution.currentActivityId} is not built yet")
}
