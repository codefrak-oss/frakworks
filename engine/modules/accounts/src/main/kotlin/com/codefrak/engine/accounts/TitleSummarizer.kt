package com.codefrak.engine.accounts

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/** A summary title, or null and why there is none. */
data class TitleAnswer(val title: String?, val why: String?)

/**
 * The ask item's title (engine/README.md, "Intake"): a one-line summary of the
 * change an ask asks for, as the user would see it. Never throws: no answer is a
 * null title and the reason, and the caller falls back to the ask's first line.
 */
fun interface TitleSummarizer {
    fun summarize(ask: String): TitleAnswer
}

/** The bean: [AnthropicTitles] on the account [AiAccounts.peek] picks for anthropic, 10 s at most. */
@Component
class AccountTitleSummarizer(
    accounts: AiAccounts,
    @Value("\${engine.anthropic.upstream:https://api.anthropic.com}") upstream: String,
) : TitleSummarizer by AnthropicTitles(upstream, Duration.ofSeconds(10), {
    accounts.peek("anthropic", AnthropicTitles.MODEL)?.let { accounts.credential(it) }
})
