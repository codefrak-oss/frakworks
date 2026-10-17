package com.codefrak.engine.webhook

/**
 * What a [WebhookWake] wakes (engine/README.md, "Engine modules"), implemented by the flow packages: one scan of every
 * target repo, and one pass over the flows waiting for an engine check.
 */
interface FlowWakes {
    /** Scans every target repo and answers what it acted on, one `repo#issue flow outcome` line each. */
    fun scan(): List<String>

    /** Wakes the flows waiting for an engine check that has completed. */
    fun pollChecks()
}
