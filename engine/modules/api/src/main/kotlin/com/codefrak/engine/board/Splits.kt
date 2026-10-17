package com.codefrak.engine.board

import com.codefrak.engine.feature.SplitState
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.HistoryService

/** The split state on a feature instance, running or ended; null for any other flow, or none. Read by the open and the closed board. */
fun HistoryService.splitOf(instance: String?): SplitState? {
    instance ?: return null
    val bytes = createHistoricVariableInstanceQuery().processInstanceId(instance).variableName(Vars.SPLIT).singleResult()?.value as? ByteArray
    return bytes?.let { runCatching { SplitState.parse(it) }.getOrNull() }
}
