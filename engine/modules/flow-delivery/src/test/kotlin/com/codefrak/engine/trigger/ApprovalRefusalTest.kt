package com.codefrak.engine.trigger

import com.codefrak.engine.feature.FeatureMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [approvalRefusal]: the eight-branch refusal precedence, pulled out of [IssueScanner.consider]. */
class ApprovalRefusalTest {
    private val throws: () -> Boolean = { throw AssertionError("should not have been called") }
    private val throwsEdited: () -> Boolean = { throw AssertionError("should not have been called") }

    private fun refusal(
        kinds: Set<String> = setOf("feature"),
        flow: String? = "feature",
        modes: Set<String> = setOf(FeatureMode.label(FeatureMode.AUTO)),
        actor: String = "someone",
        parked: Boolean = false,
        actorTrusted: () -> Boolean = { true },
        alreadyRunning: () -> Boolean = { false },
        kindChangedAfter: Boolean = false,
        editedAfter: () -> Boolean = { false },
    ) = approvalRefusal(kinds, flow, modes, actor, parked, actorTrusted, alreadyRunning, kindChangedAfter, editedAfter)

    @Test
    fun `no kind label refuses`() {
        assertEquals(
            "authorized, but it has no kind label (`chore`, `bug`, `feature`, `proposal`, `research`, `intake`) to say which flow to run",
            refusal(kinds = emptySet(), flow = null, actorTrusted = throws, alreadyRunning = throws, editedAfter = throwsEdited),
        )
    }

    @Test
    fun `more than one kind label refuses`() {
        assertEquals(
            "authorized with more than one kind label (`bug`, `feature`); keep one and authorize again",
            refusal(kinds = setOf("bug", "feature"), flow = null, actorTrusted = throws, alreadyRunning = throws, editedAfter = throwsEdited),
        )
    }

    @Test
    fun `a feature with no mode label refuses`() {
        assertEquals(
            "a feature needs exactly one mode label, `${FeatureMode.label(FeatureMode.AUTO)}` or `${FeatureMode.label(FeatureMode.CONFIRM)}`, " +
                "when it is authorized; it had none. Label it and authorize again",
            refusal(modes = emptySet(), actorTrusted = throws, alreadyRunning = throws, editedAfter = throwsEdited),
        )
    }

    @Test
    fun `a feature with more than one mode label refuses`() {
        val modes = setOf(FeatureMode.label(FeatureMode.AUTO), FeatureMode.label(FeatureMode.CONFIRM))
        assertEquals(
            "a feature needs exactly one mode label, `${FeatureMode.label(FeatureMode.AUTO)}` or `${FeatureMode.label(FeatureMode.CONFIRM)}`, " +
                "when it is authorized; it had `${FeatureMode.label(FeatureMode.AUTO)}`, `${FeatureMode.label(FeatureMode.CONFIRM)}`. Label it and authorize again",
            refusal(modes = modes, actorTrusted = throws, alreadyRunning = throws, editedAfter = throwsEdited),
        )
    }

    @Test
    fun `an untrusted actor refuses`() {
        assertEquals(
            "authorized by @someone${IssueScanner.NOT_TRUSTED}",
            refusal(actor = "someone", actorTrusted = { false }, alreadyRunning = throws, editedAfter = throwsEdited),
        )
    }

    @Test
    fun `an already running or PR'd flow refuses`() {
        assertEquals(
            "a feature on it is still running or has opened a PR (see the engine's handled issues); one run at a time, and none after a PR",
            refusal(flow = "feature", parked = false, alreadyRunning = { true }, editedAfter = throwsEdited),
        )
    }

    @Test
    fun `parked skips the already-running check`() {
        assertEquals(
            null,
            refusal(flow = "feature", parked = true, alreadyRunning = throws, editedAfter = { false }),
        )
    }

    @Test
    fun `a flow outside the work flows is not checked against the already-running branch`() {
        assertEquals(
            null,
            refusal(kinds = setOf("research"), flow = "research", modes = emptySet(), alreadyRunning = throws, editedAfter = { false }),
        )
    }

    @Test
    fun `a kind changed after the approval refuses`() {
        assertEquals(
            "its kind label changed after it was authorized; authorize it again",
            refusal(kindChangedAfter = true, editedAfter = throwsEdited),
        )
    }

    @Test
    fun `edited after the approval refuses`() {
        assertEquals(
            "edited after it was authorized; what was approved is not what is there now. Authorize it again (remove and re-add `${IssueScanner.AUTHORIZED}`)",
            refusal(editedAfter = { true }),
        )
    }

    @Test
    fun `nothing wrong returns null`() {
        assertNull(refusal())
    }
}
