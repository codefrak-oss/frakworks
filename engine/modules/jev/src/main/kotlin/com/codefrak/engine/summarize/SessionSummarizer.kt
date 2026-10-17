package com.codefrak.engine.summarize

import com.codefrak.engine.accounts.AiAccounts
import org.springframework.stereotype.Component

/** What the summarize endpoint shows: `summary` at most [SUMMARY_WORDS] words, `now` at most [NOW_WORDS]. */
data class SessionSummary(val summary: String, val now: String) {
    /** The limits, enforced here whatever the model answered. */
    fun limited() = SessionSummary(limitWords(summary, SUMMARY_WORDS), limitWords(now, NOW_WORDS))

    companion object {
        const val SUMMARY_WORDS = 20
        /** "Fewer than 15 words". */
        const val NOW_WORDS = 14
    }
}

/** The first `n` whitespace-separated words of `text`, joined by single spaces; an ellipsis marks a cut. */
fun limitWords(text: String, n: Int): String {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return if (words.size <= n) words.joinToString(" ") else words.take(n).joinToString(" ") + "…"
}

/** No account to summarize with: the endpoint answers 503. */
class SummaryUnavailable(message: String) : RuntimeException(message)

/** The provider failed or answered something unusable: the endpoint answers 502. */
class SummaryFailed(message: String) : RuntimeException(message)

/** Turns a stored session's digest into a [SessionSummary]; tests use a fake. */
interface SessionSummarizer {
    /**
     * `context` says whose run it is (repo, issue, mode); `digest` is [com.codefrak.engine.smith.SmithSessions.digest];
     * `recent` is [com.codefrak.engine.smith.SmithSessions.recent], the last turns, which `now` is answered from.
     */
    fun summarize(context: String, digest: String, recent: String): SessionSummary
}

/** The credential the summary call sends: an Anthropic OAuth token, or null when the engine holds none usable. */
fun interface SummaryCredential {
    fun anthropic(): String?
}

/**
 * The anthropic `engine.ai_account` the picker would choose next, read with
 * [AiAccounts.peek] so the Smiths' rotation is not moved (engine/README.md,
 * "Summarize: a live run summarized"). No new secret: the accounts Smiths run on.
 */
@Component
class AccountSummaryCredential(private val accounts: AiAccounts) : SummaryCredential {
    override fun anthropic(): String? = accounts.peek("anthropic")?.let { accounts.credential(it) }
}
