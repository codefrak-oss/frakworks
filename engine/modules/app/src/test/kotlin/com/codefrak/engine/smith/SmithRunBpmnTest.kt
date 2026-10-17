package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import org.operaton.bpm.model.bpmn.Bpmn
import org.operaton.bpm.model.bpmn.instance.ServiceTask
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonFailedJobRetryTimeCycle
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertEquals

/**
 * `task-start-smith` (`smith-run.bpmn`) must back off between retries: the
 * 2026-09-25 chore #99 incident burned Operaton's default of 3 immediate
 * retries in under a second, on the same job that races the orphan check
 * (DockerSandboxReclaimTest) in `DockerSandbox`. Plain JUnit, no Spring
 * context: it parses the BPMN straight off the classpath.
 */
class SmithRunBpmnTest {
    @Test
    fun `task-start-smith has a failedJobRetryTimeCycle, not Operaton's immediate-retry default`() {
        val resource = PathMatchingResourcePatternResolver().getResource("classpath:bpmn/smith-run.bpmn")
        val model = resource.inputStream.use { Bpmn.readModelFromStream(it) }

        val task = model.getModelElementById("task-start-smith") as ServiceTask
        val cycle = task.extensionElements
            ?.getUniqueChildElementByType(OperatonFailedJobRetryTimeCycle::class.java)

        assertEquals("R3/PT30S", cycle?.textContent, "task-start-smith should carry operaton:failedJobRetryTimeCycle")
    }

    @Test
    fun `task-open-pr has a failedJobRetryTimeCycle in every flow that pushes`() {
        listOf("chore", "bug", "feature", "proposal").forEach { flow ->
            val resource = PathMatchingResourcePatternResolver().getResource("classpath:$flow.bpmn")
            val model = resource.inputStream.use { Bpmn.readModelFromStream(it) }

            val task = model.getModelElementById("task-open-pr") as ServiceTask
            val cycle = task.extensionElements
                ?.getUniqueChildElementByType(OperatonFailedJobRetryTimeCycle::class.java)

            assertEquals("R3/PT30S", cycle?.textContent, "$flow.bpmn's task-open-pr should carry operaton:failedJobRetryTimeCycle")
        }
    }

    @Test
    fun `task-rebase-pr has a failedJobRetryTimeCycle spanning a GitHub rate-limit window in every flow`() {
        listOf("chore", "bug", "feature", "proposal").forEach { flow ->
            val resource = PathMatchingResourcePatternResolver().getResource("classpath:$flow.bpmn")
            val model = resource.inputStream.use { Bpmn.readModelFromStream(it) }

            val task = model.getModelElementById("task-rebase-pr") as ServiceTask
            val cycle = task.extensionElements
                ?.getUniqueChildElementByType(OperatonFailedJobRetryTimeCycle::class.java)

            assertEquals("R5/PT15M", cycle?.textContent, "$flow.bpmn's task-rebase-pr should carry operaton:failedJobRetryTimeCycle")
        }
    }

    @Test
    fun `every async service task in feature_bpmn has a spaced failedJobRetryTimeCycle`() {
        // #2581: task-file-child burned Operaton's three immediate retries in 0.5 s and sat on an incident.
        val resource = PathMatchingResourcePatternResolver().getResource("classpath:feature.bpmn")
        val model = resource.inputStream.use { Bpmn.readModelFromStream(it) }

        val tasks = model.getModelElementsByType(ServiceTask::class.java).filter { it.isOperatonAsyncBefore }
        assertEquals(true, tasks.any { it.id == "task-file-child" })
        tasks.forEach { task ->
            val cycle = task.extensionElements?.getUniqueChildElementByType(OperatonFailedJobRetryTimeCycle::class.java)?.textContent
            assertEquals(true, cycle != null && cycle.matches(Regex("R\\d+/PT\\d+[SM]")), "${task.id} should carry a spaced retry cycle, has $cycle")
        }
    }
}
