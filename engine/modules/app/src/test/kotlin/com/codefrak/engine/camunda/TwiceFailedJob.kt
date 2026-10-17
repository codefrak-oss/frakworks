package com.codefrak.engine.camunda

import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate

/** Throws the instance's `boom` variable as its message. */
class BoomDelegate : JavaDelegate {
    override fun execute(execution: DelegateExecution) { throw IllegalStateException(execution.getVariable("boom") as String) }
}

/**
 * A job failed twice with two messages, as a retry (`retries = 1` on the same job) after GitHub's 500 then 422 would:
 * run by hand, so it needs `operaton.bpm.job-execution.enabled=false`. Answers the instance and the job.
 */
object TwiceFailedJob {
    const val KEY = "job-attempts-test"
    const val FIRST = "GitHub answered 500 on the PR create"
    const val SECOND = "422: A pull request already exists"

    fun run(repository: RepositoryService, runtime: RuntimeService, management: ManagementService): Pair<String, String> {
        if (repository.createProcessDefinitionQuery().processDefinitionKey(KEY).count() == 0L)
            repository.createDeployment().addString("$KEY.bpmn", """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="t">
  <bpmn:process id="$KEY" isExecutable="true" camunda:historyTimeToLive="1">
    <bpmn:startEvent id="s" />
    <bpmn:serviceTask id="boom" camunda:asyncBefore="true" camunda:class="${BoomDelegate::class.java.name}" />
    <bpmn:endEvent id="e" />
    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="boom" />
    <bpmn:sequenceFlow id="f2" sourceRef="boom" targetRef="e" />
  </bpmn:process>
</bpmn:definitions>""").deploy()
        val instance = runtime.startProcessInstanceByKey(KEY, mapOf("boom" to FIRST))
        val job = management.createJobQuery().processInstanceId(instance.id).singleResult()
        management.setJobRetries(job.id, 1)
        runCatching { management.executeJob(job.id) }
        runtime.setVariable(instance.id, "boom", SECOND)
        management.setJobRetries(job.id, 1)
        runCatching { management.executeJob(job.id) }
        return instance.id to job.id
    }
}
