package com.codefrak.engine.smith

import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.springframework.stereotype.Component

/** "remove sandbox": free the container once the result is safely committed. */
@Component("removeSmith")
class RemoveSmithDelegate(private val sandbox: SmithSandbox) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        sandbox.remove(execution.getVariable(Vars.HANDLE) as String)
    }
}
