package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals

/**
 * `SmithExitNotifier.deliver` and the event path's bounded retry, on their
 * own: a message with nothing waiting for it is a no-op, not an error, and a
 * die that beats the start job's commit is retried a bounded number of times
 * before being left to the backstop. Same context as SmithRunProcessTest.
 */
@SpringBootTest
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class SmithExitTest {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun sandbox() = FakeSandbox()
    }

    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: SandboxExitNotifier

    private fun start(issue: Long) = runtime.startProcessInstanceByKey("smith-run", mapOf(
        Vars.REPO to "acme/widgets", Vars.ISSUE to issue, Vars.MODEL to "claude-sonnet-5",
        Vars.EFFORT to "low", Vars.DRY_RUN to true,
    ))

    @Test
    fun `an exit for a run nobody knows is ignored`() {
        assertEquals(SmithExitNotifier.Delivery.ALREADY, exits.deliver(SandboxExit("container-none", "no-such-instance", 0)))
        assertEquals(SmithExitNotifier.Delivery.NOT_WAITING, exits.deliver(SandboxExit("container-none", null, 0)), "no run has that handle")
    }

    @Test
    fun `an exit that beats the run's wait is retried a bounded number of times, then dropped`() {
        exits.earlyRetries = 2; exits.earlyRetryMillis = 0
        try {
            val instance = start(60L)   // its start job has not run: no subscription yet
            assertEquals(3, exits.onExit(SandboxExit("container-60", instance.id, 0)), "one try plus two retries")

            management.executeJob(management.createJobQuery().processInstanceId(instance.id).singleResult().id)
            assertEquals(1, exits.onExit(SandboxExit("container-60", instance.id, 0)), "waiting now: delivered first time")
            assertEquals(1, exits.onExit(SandboxExit("container-60", instance.id, 0)), "already collected: a no-op, no retry")
        } finally {
            exits.earlyRetries = 6; exits.earlyRetryMillis = 10_000L
        }
    }
}
