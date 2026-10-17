package com.codefrak.engine.camunda

import org.operaton.bpm.engine.HistoryService

/** A process instance's variables by name, from Operaton's history; a repeated name keeps its last value. */
fun HistoryService.variablesOf(instance: String): Map<String, Any?> =
    createHistoricVariableInstanceQuery().processInstanceId(instance).list().associate { it.name to it.value }

/** A process instance's variables by name, the process's own scope first; an unreadable value is null. */
fun HistoryService.ownScopeVariablesOf(instance: String): Map<String, Any?> =
    createHistoricVariableInstanceQuery().processInstanceId(instance).list()
        .sortedBy { it.activityInstanceId != instance }
        .groupBy { it.name }.mapValues { (_, vs) -> vs.firstNotNullOfOrNull { runCatching { it.value }.getOrNull() } }
