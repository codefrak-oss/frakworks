package com.codefrak.engine.ci

import org.junit.jupiter.api.Test
import org.operaton.bpm.model.bpmn.Bpmn
import org.operaton.bpm.model.bpmn.instance.ServiceTask
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonFailedJobRetryTimeCycle
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertEquals

/**
 * The async GitHub/git tasks in `reusable-ci-check.bpmn` must back off between
 * retries: on #1574 a 500 from the check-runs API burned Operaton's default of 3
 * immediate retries in ~2 s on `task-ci-check-timed-out`. Plain JUnit, no Spring
 * context: it parses the BPMN straight off the classpath.
 */
class CiCheckRetryCycleTest {
    @Test
    fun `every async GitHub task in reusable-ci-check has a spaced failedJobRetryTimeCycle`() {
        val resource = PathMatchingResourcePatternResolver().getResource("classpath:reusable-ci-check.bpmn")
        val model = resource.inputStream.use { Bpmn.readModelFromStream(it) }

        listOf("task-ci-conflict", "task-ci-check-retrigger", "task-ci-check-timed-out", "task-read-ci-check").forEach { id ->
            val task = model.getModelElementById(id) as ServiceTask
            val cycle = task.extensionElements
                ?.getUniqueChildElementByType(OperatonFailedJobRetryTimeCycle::class.java)

            assertEquals("R5/PT1M", cycle?.textContent, "$id should carry operaton:failedJobRetryTimeCycle")
        }
    }
}
