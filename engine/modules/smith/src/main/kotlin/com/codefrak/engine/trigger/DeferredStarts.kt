package com.codefrak.engine.trigger

import com.codefrak.engine.repos.Deferred

/**
 * One approval the scan acted on: `outcome` started, refused, ignored, deferred (waiting on an
 * open "blocked by" issue; engine/README.md, "Blocked by"), or resumed (a split feature woken by
 * its child's merge, or re-authorized after its child closed without one; "Split features"), and why.
 */
data class ScanAction(val repo: String, val issue: Long, val flow: String, val outcome: String, val detail: String)

/** Where a start blocked by an open issue waits (`IssueScanner`): what the intake flow defers a filed ticket through. */
interface DeferredStarts {
    /**
     * Records `row` as waiting, and says so on the ticket the first time: a replaced approval on an
     * issue already waiting says nothing new. Nothing is claimed, so the approval stays unhandled.
     */
    fun defer(row: Deferred): ScanAction
}
