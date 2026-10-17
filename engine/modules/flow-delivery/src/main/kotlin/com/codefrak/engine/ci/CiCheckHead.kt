package com.codefrak.engine.ci

import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.smith.Vars
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.codefrak.engine.ci.CiCheckHead")

/**
 * The head the wait's check is read on: the PR's current head, not the one the engine pushed. Anyone may push to the
 * branch while the flow waits (a merge of main, a human's fix), and GitHub runs the check on the new head and never
 * on the old one again (#1586). A moved head is written to `headSha`, and `ciRetriggers` starts over for it. With no
 * PR, or GitHub not answering, the stored head stands.
 */
internal fun followHead(pulls: GitHubPulls, repo: String, vars: Map<String, Any?>, set: (String, Any?) -> Unit): String {
    val stored = vars[Vars.HEAD_SHA] as? String ?: error("no pushed head to check")
    val pr = (vars[Vars.PR] as? Number)?.toLong() ?: return stored
    val current = runCatching { pulls.headSha(repo, pr) }
        .onFailure { log.warn("{}#{}: could not read the PR's head, waiting on {}: {}", repo, pr, stored.take(12), it.message) }
        .getOrNull()
    if (current == null || current == stored) return stored
    log.info("{} PR #{}: head moved from {} to {}: the check wait follows it", repo, pr, stored.take(12), current.take(12))
    set(Vars.HEAD_SHA, current)
    set(Vars.CI_RETRIGGERS, null)
    return current
}
