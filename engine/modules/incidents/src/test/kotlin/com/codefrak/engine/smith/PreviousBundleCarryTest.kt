package com.codefrak.engine.smith

import com.codefrak.engine.camunda.IncidentRetrier
import com.codefrak.engine.camunda.IncidentRetrier.Companion.SMITH_RUN
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #1469: a no-commit round run again must still find the earlier round's bundle to re-check. */
class PreviousBundleCarryTest {
    /** What CollectSmithDelegate does to BUNDLE/PREVIOUS_BUNDLE on a committing-mode run. */
    private fun collect(state: Pair<ByteArray?, ByteArray?>, sandboxBundle: ByteArray?): Pair<ByteArray?, ByteArray?> =
        sandboxBundle to CollectSmithDelegate.carriedBundle(state.first, state.second)

    @Test
    fun `the 1439 sequence keeps round 0's bundle through a rerun of the no-commit round`() {
        val b0 = "round0".toByteArray()
        var state: Pair<ByteArray?, ByteArray?> = null to null
        state = collect(state, b0) // round 0 commits
        assertNull(state.second)
        state = collect(state, null) // round 1, no commit, rejected or its incident retried
        assertContentEquals(b0, state.second)
        state = collect(state, null) // round 1 run again: before the fix PREVIOUS_BUNDLE became null here
        assertNull(state.first)
        assertContentEquals(b0, state.second)
        state = collect(state, null)
        assertContentEquals(b0, state.second)
    }

    @Test
    fun `a newer commit replaces the kept bundle`() {
        val b1 = "round1".toByteArray()
        assertContentEquals(b1, CollectSmithDelegate.carriedBundle(b1, "round0".toByteArray()))
    }

    @Test
    fun `a decline with no pull request to re-check reruns the Smith`() {
        // #1537: `open/update pr` bundles the pushed head again instead; the only rule left is a missing pull request.
        assertTrue(IncidentRetrier.reruns(SMITH_RUN, "success",
            "the smith's result breaks the rules: declined the engine check's failure, but there is no pull request whose check to re-run"))
    }
}

/** #2248 (friction g6): a propose run that leaves no bundle keeps the draft the next round's brief cites. */
class ProposalDraftCarryTest {
    @Test
    fun `a second-round propose run gets the first round's draft after a run that bundled nothing`() {
        val draft = "round1 draft".toByteArray()
        var bundle = CollectSmithDelegate.collectedBundle("propose", draft, null) // round 1 commits its draft
        bundle = CollectSmithDelegate.collectedBundle("propose", null, bundle) // round 2 dies before bundling
        assertContentEquals(draft, bundle) // the rerun's /work/in/commits.bundle is still round 1's draft
    }

    @Test
    fun `a new draft replaces the old one`() {
        val r2 = "round2".toByteArray()
        assertContentEquals(r2, CollectSmithDelegate.collectedBundle("propose", r2, "round1".toByteArray()))
    }

    @Test
    fun `a work run that bundles nothing still clears the bundle`() {
        assertNull(CollectSmithDelegate.collectedBundle("work", null, "earlier".toByteArray()))
    }
}
