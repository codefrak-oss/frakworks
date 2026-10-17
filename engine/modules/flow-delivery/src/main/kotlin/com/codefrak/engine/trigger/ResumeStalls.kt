package com.codefrak.engine.trigger

import com.codefrak.engine.github.Blocker

/** Whether a `reauthorize` need appeared, went, or is unaffected: [stallsAfter]'s answer to [IssueScanner.resume]'s two `if` checks. */
internal enum class ReauthorizeNeed { ADDED, REMOVED, UNCHANGED }

/** [stallsAfter]'s answer: the new stalled set, the blockers newly closed without a merge (to comment on), and the reauthorize need change. */
internal data class ResumeStall(val stalled: Set<String>, val toComment: List<Blocker>, val need: ReauthorizeNeed)

/**
 * [IssueScanner.resume]'s stalled-set bookkeeping, pulled out pure. `running`'s blockers clear any
 * stall recorded for them; `unmerged`'s blockers not already stalled are added and are the ones to
 * comment on. `resume` always passes one of the two empty — its branches are mutually exclusive — so
 * one function serves both call sites.
 */
internal fun stallsAfter(stalled: Set<String>, running: List<Blocker>, unmerged: List<Blocker>): ResumeStall {
    val cleared = stalled - running.map { "${it.repo}#${it.number}" }.toSet()
    val fresh = unmerged.filter { "${it.repo}#${it.number}" !in cleared }
    val after = cleared + fresh.map { "${it.repo}#${it.number}" }
    val need = when {
        stalled.isNotEmpty() && after.isEmpty() -> ReauthorizeNeed.REMOVED
        stalled.isEmpty() && after.isNotEmpty() -> ReauthorizeNeed.ADDED
        else -> ReauthorizeNeed.UNCHANGED
    }
    return ResumeStall(after, fresh, need)
}
