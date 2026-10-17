package com.codefrak.engine.doctor

import com.codefrak.engine.smith.ResultHandler
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.stereotype.Component

/** A doctor run's result (`read result`, [ResultHandler]): a broken one is the run's outcome, never an incident. */
@Component
class DoctorResultHandler : ResultHandler {
    override val modes = setOf("doctor")

    override fun handle(execution: DelegateExecution, mode: String, bytes: ByteArray, enforce: (List<String>) -> Unit) = DoctorResult.parse(bytes).outcome

    override fun problems(mode: String, bytes: ByteArray) = DoctorResult.parse(bytes).problems()
}
