package com.codefrak.engine.trigger

import com.codefrak.engine.chore.IssueCloseWake
import org.operaton.bpm.engine.impl.cfg.TransactionState
import org.operaton.bpm.engine.impl.context.Context
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Wakes what waits on an issue once the flow on it ends with its own close (`task-close-ticket`,
 * `task-close-nothing-to-do`): a split feature parked on that child resumes at once rather than at
 * the next scan ([IssueScanner.wakeWaitingOn]). This matters for a feature child: GitHub saw
 * its close at the merge, and its flow ends later, after its accept Smith's check, with no event a wake
 * could forward. The close task runs straight into the flow's end event, so after the commit the
 * flow has ended and the scanner's [IssueScanner.resume] checks see it as merged.
 */
@Component
class CloseWake(private val scanner: IssueScanner) : IssueCloseWake {
    private val log = LoggerFactory.getLogger(CloseWake::class.java)

    /** Called from the close delegate: wakes after its transaction commits, and never if it rolls back. */
    override fun afterCommit(repo: String, issue: Long) {
        val context = Context.getCommandContext() ?: return wake(repo, issue)
        context.transactionContext.addTransactionListener(TransactionState.COMMITTED) { wake(repo, issue) }
    }

    /**
     * On a thread of its own, which this one does not wait for: the committed command's context and
     * transaction are still bound to this thread, and the wake's own commands must not join them. This
     * thread still holds its database connection in the after-commit callback, and the wake needs one of
     * its own, so waiting for it deadlocked the pool once enough flows closed at once (2026-10-06: 21
     * intakes finishing together held all ten connections, each waiting on a wake that could get none).
     * A failure is only logged; the next scan is the backstop.
     */
    private fun wake(repo: String, issue: Long) = dispatch {
        runCatching { scanner.wakeWaitingOn(repo, issue) }.onFailure { log.warn("close wake on {}#{} failed: {}", repo, issue, it.message) }
    }

    /** Starts the wake and returns. Replaced in tests that assert on the woken parent right after the close's job. */
    var dispatch: (() -> Unit) -> Unit = { task -> Thread.ofVirtual().name("close-wake").start { task() } }
}
