package com.codefrak.engine.smith

/** A live run's tokens so far, summed over its distinct messages (subagents' included: they are billed). */
data class LiveTokens(val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long)

/**
 * What a run's live tally (`LiveTally`, engine-smith) knows of it so far. `turns` is the closing `result`'s
 * `num_turns` once it was read, else [countedTurns]; `countedTurns` the tally's own count, kept for the comparison
 * `collect result` logs. All null before the first `assistant` event.
 */
data class LiveFigures(val turns: Long?, val tokens: LiveTokens?, val countedTurns: Long?)
