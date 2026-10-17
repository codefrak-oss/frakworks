package com.codefrak.engine.ci

import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.smith.WorkResult
import com.codefrak.engine.smith.WorkResultListener
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.stereotype.Component

/** A work run's answer to a triaged ci finding is its Jev call's outcome ([CiTriage.diagnosed]), told by `read result`. */
@Component
class CiTriageListener(private val calls: JevCalls) : WorkResultListener {
    override fun accepted(execution: DelegateExecution, work: WorkResult) = CiTriage.diagnosed(execution, work, calls)
}
