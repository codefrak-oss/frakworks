package com.codefrak.engine.ci

import com.codefrak.engine.repos.TargetRepos
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * Reads `main`'s builds for flaky tests (engine/README.md, "Flaky tests"): each completed run of the `images` workflow
 * ([WORKFLOW]) on the default branch of the `engineDefects` repo, once ever. A successful run's engine build job
 * ([JOB]) has its log read, and each `FLAKY-TEST:` line is filed or commented through [FlakyTests.file]; a failed or
 * cancelled run files nothing. [MainBuildWatermark] keeps the last run read per repo, so a second pass, or a restart,
 * reads nothing twice. Runs in the engine-check pass ([CiCheckWaits.poll]): on each repo's scan interval, at startup,
 * on POST /scan and on a webhook wake. A run still going stops the pass there, for the next one: its log is not final.
 * A run whose log cannot be read is read again on the next pass; a failure to file or start is logged and the run
 * counts as read.
 */
@Component
class MainBuildFlakes(private val runs: CheckRuns, private val flaky: FlakyTests, private val repos: TargetRepos,
                      private val watermark: MainBuildWatermark) {
    private val log = LoggerFactory.getLogger(MainBuildFlakes::class.java)

    /** `only`: the repos the pass is for (matched ignoring case), every repo when null. The tickets filed or commented, in order. */
    @Synchronized
    fun poll(only: Set<String>? = null): List<String> {
        val repo = repos.engineDefectsRepo() ?: return emptyList()
        if (only != null && only.none { it.equals(repo, ignoreCase = true) }) return emptyList()
        val branch = runCatching { runs.defaultBranch(repo) }.getOrDefault("main")
        val last = watermark.last(repo)
        val fresh = runs.workflowRuns(repo, WORKFLOW, branch, LIMIT).filter { last == null || it.id > last }.sortedBy { it.id }
        val filed = mutableListOf<String>()
        for (run in fresh) {
            if (run.status != "completed") break
            if (run.conclusion == "success") {
                val text = runCatching { engineJobs(repo, run.id).map { it to runs.log(repo, it.id) } }
                    .onFailure { log.warn("{}: could not read images run {}; read again on the next pass: {}", repo, run.id, it.message) }
                    .getOrNull() ?: break
                for ((job, logText) in text) {
                    val where = "on `$branch`'s build (images run ${run.runNumber ?: run.id}${run.url?.let { ", $it" }.orEmpty()})"
                    runCatching { filed += flaky.record(repo, logText, where, job.url ?: run.url.orEmpty(), run.headSha.orEmpty()) }
                        .onFailure { log.warn("{}: could not file images run {}'s flaky tests: {}", repo, run.id, it.message) }
                }
            }
            watermark.set(repo, run.id)
        }
        return filed
    }

    private fun engineJobs(repo: String, runId: Long) = runs.jobs(repo, runId).filter { it.name?.contains(JOB, ignoreCase = true) == true }

    companion object {
        /** The workflow that builds the engine image on each merge: .github/workflows/images.yml. */
        const val WORKFLOW = "images.yml"
        /** Its matrix entry for the engine image; the job's name carries it. */
        const val JOB = "frakworks-engine"
        /** The newest runs looked at in a pass. */
        const val LIMIT = 20
    }
}

/** The last `images` run read per repo (`engine.flaky_build_watermark`), for [MainBuildFlakes]. Open for tests. */
@Component
open class MainBuildWatermark(private val jdbc: JdbcClient) {
    open fun last(repo: String): Long? = jdbc.sql("SELECT run_id FROM engine.flaky_build_watermark WHERE repo = :r")
        .param("r", repo.lowercase()).query(Long::class.java).optional().orElse(null)

    open fun set(repo: String, runId: Long) {
        val updated = jdbc.sql("UPDATE engine.flaky_build_watermark SET run_id = :id, updated_at = CURRENT_TIMESTAMP WHERE repo = :r AND run_id < :id")
            .param("r", repo.lowercase()).param("id", runId).update()
        if (updated == 0 && last(repo) == null)
            jdbc.sql("INSERT INTO engine.flaky_build_watermark (repo, run_id, updated_at) VALUES (:r, :id, CURRENT_TIMESTAMP)")
                .param("r", repo.lowercase()).param("id", runId).update()
    }
}
