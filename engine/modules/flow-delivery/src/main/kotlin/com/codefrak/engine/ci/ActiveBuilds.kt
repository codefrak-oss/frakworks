package com.codefrak.engine.ci

import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** One build in progress: a flow parked at `receive-ci-check`, waiting on the engine check of [repo]#[issue]'s pushed head. */
data class ActiveBuild(
    val repo: String?,
    val issue: Long?,
    val check: String?,
    val headSha: String?,
    val since: Instant,
    val processInstanceId: String,
)

/** Every build in progress, oldest first, and the check timeout (`engine.ci-check.timeout`, ISO-8601) that bounds each wait. */
data class ActiveBuildsView(val builds: List<ActiveBuild>, val timeout: String)

/**
 * The builds the engine is waiting on: each unfinished `receive-ci-check` (bpmn/reusable-ci-check.bpmn) is one engine check
 * of one pushed branch, its start the activity's. `reusable-ci-check` takes every variable in, so repo, issue, check and head
 * are the flow's own. No GitHub call.
 */
@Component
class ActiveBuilds(private val history: HistoryService, private val runtime: RuntimeService, private val ciChecks: CiChecks) {
    fun list(): ActiveBuildsView {
        val waits = history.createHistoricActivityInstanceQuery().activityId(CI_WAIT_ACTIVITY).unfinished().list()
        val builds = waits.mapNotNull { a ->
            val vars = runCatching {
                runtime.getVariables(a.processInstanceId, listOf(Vars.REPO, Vars.ISSUE, Vars.CI_CHECK, Vars.HEAD_SHA))
            }.getOrNull() ?: return@mapNotNull null
            ActiveBuild(
                repo = vars[Vars.REPO] as? String,
                issue = (vars[Vars.ISSUE] as? Number)?.toLong(),
                check = vars[Vars.CI_CHECK] as? String,
                headSha = vars[Vars.HEAD_SHA] as? String,
                since = a.startTime.toInstant(),
                processInstanceId = a.processInstanceId,
            )
        }.sortedBy { it.since }
        return ActiveBuildsView(builds, ciChecks.timeout)
    }

    companion object {
        const val CI_WAIT_ACTIVITY = "receive-ci-check"
    }
}

/** Unauthenticated, read-only: the builds in progress ([ActiveBuilds]), for the desktop app's Build gate strip. */
@RestController
class ActiveBuildsController(private val builds: ActiveBuilds) {
    @GetMapping("/builds/active")
    fun active(): ActiveBuildsView = builds.list()
}
