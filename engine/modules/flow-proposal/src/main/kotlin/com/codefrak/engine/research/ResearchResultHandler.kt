package com.codefrak.engine.research

import com.codefrak.engine.smith.ResultHandler
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.stereotype.Component
import org.operaton.bpm.engine.variable.Variables

/** A research run's result (`read result`, [ResultHandler]): it commits nothing, and its answer rides inline (engine/README.md, "Research"). */
@Component
class ResearchResultHandler : ResultHandler {
    override val modes = setOf("research")

    override fun handle(execution: DelegateExecution, mode: String, bytes: ByteArray, enforce: (List<String>) -> Unit): String {
        val research = ResearchResult.parse(bytes)
        enforce(research.problems() + listOfNotNull("a research run commits nothing: the run left a bundle".takeIf { execution.getVariable(Vars.BUNDLE) != null }))
        execution.setVariable(Vars.RESEARCH, Variables.byteArrayValue(bytes))
        return research.outcome
    }
}
