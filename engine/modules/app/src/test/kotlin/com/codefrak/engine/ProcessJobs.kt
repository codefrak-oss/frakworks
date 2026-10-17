package com.codefrak.engine

import com.codefrak.engine.smith.SmithExitNotifier
import org.operaton.bpm.engine.ManagementService

/** Drains every due job, round by round, until none are left or `rounds` is exhausted. */
fun ManagementService.runAllJobs(exits: SmithExitNotifier, rounds: Int = 100, messages: Boolean = false) {
    repeat(rounds) {
        // Exited sandboxes reach their waiting runs through the backstop scan: no event stream in tests.
        exits.scanExits()
        val query = createJobQuery().withRetriesLeft()
        // The post-deployment check's timers (its 12 h safety poll, its check waits, its deadline) fire only when a test
        // says so: a flow that reaches call-post-deploy-check waits there for a deploy the test never sends.
        val held = createJobQuery().timers().processDefinitionKey("post-deploy-check").list().map { it.id }.toSet()
        val jobs = (if (messages) query.messages().list() else query.list()).filterNot { it.id in held }
        if (jobs.isEmpty()) return
        jobs.forEach { runCatching { executeJob(it.id) } }
    }
    error("no quiescence in $rounds job rounds")
}
