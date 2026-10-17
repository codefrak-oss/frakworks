package com.codefrak.engine

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ProcessEngineConfiguration
import org.operaton.bpm.engine.RuntimeService
import kotlin.test.assertFalse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertTrue

@SpringBootTest
class HelloProcessTest(
    @Autowired val runtimeService: RuntimeService,
    @Autowired val engineConfiguration: ProcessEngineConfiguration,
) {
    @Test
    fun `hello runs start to end through the delegate`() {
        val instance = runtimeService.startProcessInstanceByKey("hello")
        assertTrue(instance.isEnded)
    }

    // This class does not set job-execution.enabled itself; build.gradle.kts turns the
    // executor off for every test context, so no background executor races hand-driven jobs.
    @Test
    fun `no job executor runs in tests`() {
        val executor = (engineConfiguration as org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl).jobExecutor
        assertFalse(executor != null && executor.isActive)
    }
}
