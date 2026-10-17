package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `callerIp` and `SmithCaller.of`: the two pieces [DockerSandbox.callerAt] and [KubernetesSandbox.runAt] share. */
class SmithCallerTest {
    @Test
    fun `callerIp strips brackets and the IPv4-mapped prefix`() {
        assertEquals("::1", callerIp("[::1]"))
        assertEquals("172.18.0.5", callerIp("::ffff:172.18.0.5"))
        assertEquals("172.18.0.5", callerIp("172.18.0.5"))
        assertNull(callerIp(""))
        assertNull(callerIp("[]"))
    }

    @Test
    fun `SmithCaller of is null without RUN_LABEL`() {
        assertNull(SmithCaller.of(mapOf(ISSUE_LABEL to "7")))
    }

    @Test
    fun `SmithCaller of leaves issue null on a non-numeric ISSUE_LABEL`() {
        val caller = SmithCaller.of(mapOf(RUN_LABEL to "run-1", ISSUE_LABEL to "not-a-number", REPO_LABEL to "o/r"))
        assertEquals(SmithCaller("run-1", null, "o/r", null), caller)
    }

    @Test
    fun `SmithCaller of reads every label`() {
        val caller = SmithCaller.of(mapOf(RUN_LABEL to "run-1", ISSUE_LABEL to "7", REPO_LABEL to "o/r", JOB_LABEL to "job-1"))
        assertEquals(SmithCaller("run-1", 7L, "o/r", "job-1"), caller)
    }
}
