package com.codefrak.engine.smith

import com.codefrak.engine.summarize.CountedDigest
import com.codefrak.engine.summarize.LiveRun
import com.codefrak.engine.summarize.LiveRunSessions
import org.springframework.stereotype.Component

/** [LiveRunSessions] for the summarize endpoint (engine-jev): the live runs and their lines from [SmithSessions].
 *  A live run is an open `engine.smith_session` row, not [SmithTelemetry.active]: that is only the replica that follows the run,
 *  and the Service sends the request to either replica (#1716). */
@Component
class SmithLiveRunSessions(private val sessions: SmithSessions) : LiveRunSessions {
    override fun latest(repo: String, issue: Long): LiveRun? =
        sessions.running(repo, issue)?.let { LiveRun(it.runId, it.mode, it.model) }

    override fun liveReview(repo: String, issue: Long): LiveRun? =
        sessions.running(repo, issue, "review")?.let { LiveRun(it.runId, it.mode, it.model) }

    override fun digest(runId: String): String = sessions.digest(runId)

    override fun counted(runId: String): CountedDigest = sessions.lines(runId).let { CountedDigest(SessionDigest.digest(it, SessionDigest.DIGEST_CHARS), it.size) }

    override fun recent(runId: String): String = sessions.recent(runId)
}
