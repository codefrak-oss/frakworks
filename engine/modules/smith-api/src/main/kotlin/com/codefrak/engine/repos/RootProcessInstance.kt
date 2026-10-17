package com.codefrak.engine.repos

import org.operaton.bpm.engine.RuntimeService

/** The outermost process instance id a sub-process instance runs under, walking `subProcessInstanceId` links up. */
fun RuntimeService.rootProcessInstanceId(processInstanceId: String): String {
    var id = processInstanceId
    while (true) id = createProcessInstanceQuery().subProcessInstanceId(id).singleResult()?.id ?: return id
}
