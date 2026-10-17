package com.codefrak.engine.summarize

/** A run's digest and the number of `engine.smith_session_line` rows it was built from, read together. */
data class CountedDigest(val digest: String, val lines: Int)

/** A live Smith run, as the summarize endpoint names it: its id, its mode (`work`, `review`, ...) and its model. */
data class LiveRun(val runId: String, val phase: String, val model: String)

/**
 * The live runs and their session lines the summarize endpoint reads: `SmithLiveRunSessions` in the root
 * project, over `SmithTelemetry` and `SmithSessions` (engine/README.md, "Engine modules").
 */
interface LiveRunSessions {
    /** The latest-started live run on `repo`#`issue` (`repo` as the target repo row spells it, matched ignoring case), or null when none runs. */
    fun latest(repo: String, issue: Long): LiveRun?
    /** The newest live run on `repo`#`issue` whose mode is `review`, or null when none runs (the review prediction, #1746). */
    fun liveReview(repo: String, issue: Long): LiveRun?
    /** The run's session so far, cut to fit a model call; blank when it has written nothing. */
    fun digest(runId: String): String
    /** [digest] with the count of the lines it was built from. */
    fun counted(runId: String): CountedDigest
    /** The run's last turns. */
    fun recent(runId: String): String
}
