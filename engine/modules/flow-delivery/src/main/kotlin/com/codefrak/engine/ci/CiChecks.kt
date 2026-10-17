package com.codefrak.engine.ci

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Which repos have an engine check (engine/README.md, "Chore": `wait for the engine check`): the
 * repo's `engine.target_repo` row ([RepoCiChecks]) and nothing else; a row with no `ci_check` has
 * no check. A check with no paths (a row's empty `ci_paths`) is needed for every path.
 * `engine.ci-check.enabled` false (ENGINE_CI_CHECK_ENABLED, the runner-outage switch) turns every check off.
 */
@Component
class CiChecks(
    private val rows: RepoCiChecks,
    /** How long a flow waits for the check before the incident: the boundary timer's `${ciChecks.timeout}`. */
    @Value("\${engine.ci-check.timeout:PT3H}") val timeout: String,
    @Value("\${engine.ci-check.enabled:true}") private val enabled: Boolean,
    /** How long after the wait begins, and between tries, a check with no run on the head is re-triggered. */
    @Value("\${engine.ci-check.retrigger-after:PT20M}") val retriggerAfter: String = "PT20M",
    /** How many times one wait re-triggers a check that has not started; 0 never does. */
    @Value("\${engine.ci-check.retriggers:2}") val retriggers: Int = 2,
) {
    /**
     * The re-trigger boundary timer's `${ciChecks.retriggerCycle}`: every [retriggerAfter] while the wait lasts (its
     * timeout ends it). The delegate, not the cycle's repeat count, bounds the re-triggers to [retriggers].
     */
    val retriggerCycle: String get() = "R/$retriggerAfter"

    fun check(repo: String): CiCheck? = rows.ciCheck(repo)

    /** The check `repo`'s branch must pass when it changes `paths`, or null when there is none to wait for. */
    fun needed(repo: String, paths: List<String>): String? {
        if (!enabled) return null
        return check(repo)?.takeIf { c -> c.paths.isEmpty() || paths.any { p -> c.paths.any { p.startsWith(it) } } }?.name
    }
}
