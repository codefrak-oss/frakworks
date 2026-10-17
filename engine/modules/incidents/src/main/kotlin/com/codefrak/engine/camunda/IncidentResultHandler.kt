package com.codefrak.engine.camunda

import com.codefrak.engine.smith.ResultHandler
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.stereotype.Component

/** The incident-fix process's fix and diagnose runs' results (`read result`, [ResultHandler]): a broken one is the run's outcome, never an incident. */
@Component
class IncidentResultHandler : ResultHandler {
    override val modes = setOf("fix", "diagnose")

    override fun handle(execution: DelegateExecution, mode: String, bytes: ByteArray, enforce: (List<String>) -> Unit) =
        if (mode == "fix") FixResult.parse(bytes).outcome else DiagnoseResult.parse(bytes).outcome

    override fun problems(mode: String, bytes: ByteArray) =
        if (mode == "fix") FixResult.parse(bytes).problems() else DiagnoseResult.parse(bytes).problems()
}
