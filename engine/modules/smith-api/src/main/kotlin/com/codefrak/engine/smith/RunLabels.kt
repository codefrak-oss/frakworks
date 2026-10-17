package com.codefrak.engine.smith

/**
 * What one live run is labelled with, on its VictoriaLogs rows and on its
 * `smith_run_active` series (engine/README.md, "Transcripts and the live-run
 * gauge"). `host` is the same value on both: the desktop's transcript pane
 * narrows its log query by the gauge's `host`. `account` is the
 * `engine.ai_account` the run bills to ([SmithSpec.account]; null on a dry
 * run): it is on neither the rows nor the series, only on `GET /accounts`'s
 * `running` ([SmithTelemetry.running]).
 */
data class RunLabels(val runId: String, val issue: Long?, val repo: String, val model: String, val phase: String, val host: String,
                     val account: String? = null,
                     /** A mode `job` run's job id, on a run with no [issue]. */
                     val jobId: String? = null) {
    /** One value per run and the same on every row of it, so the newest `filename` is the newest run; `job-<jobId>-...` for a job run. */
    val filename get() = (issue?.let { "issue-$it" } ?: "job-$jobId") + "-$phase-$runId.log"
    /** The old gauge's purpose label: `security-review`, `review`, `job` for a committing run, else the mode itself. */
    val kind get() = when (phase) {
        "security" -> "security-review"
        "review" -> "review"
        in COMMITTING_MODES -> "job"
        else -> phase
    }

    companion object {
        fun of(spec: SmithSpec, host: String) = RunLabels(spec.runId, spec.issue, spec.repo, spec.model, spec.mode, host, spec.account, spec.jobId)
    }
}
