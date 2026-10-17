package com.codefrak.engine.smith

import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.accounts.credentialEnv

/** The container label carrying the run id (`SmithSpec.runId`): what a `die` event is correlated by. */
const val RUN_LABEL = "frakworks.smith.run"
/** The labels carrying the run's ticket: what [callerAt] reports as a caller's issue and repo. */
const val ISSUE_LABEL = "frakworks.smith.issue"
const val REPO_LABEL = "frakworks.smith.repo"
/** The label carrying a mode `job` run's job id, on a sandbox with no [ISSUE_LABEL]. */
const val JOB_LABEL = "frakworks.smith.job"

/** A sandbox's labels: the run and repo always, the issue when the run has one, the job id when it is a job run. */
fun sandboxLabels(spec: SmithSpec): Map<String, String> = buildMap {
    put(RUN_LABEL, spec.runId); put(REPO_LABEL, spec.repo)
    spec.issue?.let { put(ISSUE_LABEL, it.toString()) }
    spec.jobId?.let { put(JOB_LABEL, it) }
}

/**
 * A Smith sandbox's environment: its inputs, the one repo-scoped [ghToken], the
 * one AI [credential] (env var name to value, null on a dry run), and
 * `ENGINE_URL`, a plain URL, when [engineUrl] is set, and for a refine the token's
 * scopes (never the token) as `SMITH_GH_SCOPES`, and the token's account as the [committer] of the
 * Smith's commits (`SMITH_GIT_NAME`, `SMITH_GIT_EMAIL`; null on a dry run), a parent run's turn budget as `SMITH_TURNS`
 * ([SmithSpec.turns], absent for every other mode), and the repo's Smith-audience MCP
 * servers as `SMITH_MCP_SERVERS` ([mcpServers], [SmithMcpServers.forRun]'s JSON, headers opened; absent when the repo
 * has none). Nothing else from the engine's own environment. A top-level function (like [sandboxHostConfig]) so a
 * test can assert on the exact key set (engine/README.md, "The frakworks server in a Smith").
 */
/**
 * The AI credential (env var name to value, null if [SmithSpec.account] is
 * null) and the dry-run-aware committer (null on a dry run) for a Smith's
 * [sandboxEnv]: the lookup [DockerSandbox.start] and
 * `KubernetesSandboxConfig.kubernetesSandbox`'s `environment` lambda both did
 * inline, byte for byte the same.
 */
fun smithCredentialAndCommitter(
    spec: SmithSpec, accounts: AiAccounts, identity: com.codefrak.engine.github.TokenIdentity,
): Pair<Pair<String, String>?, com.codefrak.engine.github.CommitIdentity?> {
    val credential = spec.account?.let { id ->
        val account = accounts.get(id) ?: throw IllegalArgumentException("no ai account $id")
        val token = accounts.credential(id) ?: throw IllegalStateException("ai account $id has no credential")
        credentialEnv(account.provider) to token
    }
    // A dry run commits nothing, so it needs no committer and GitHub is not asked.
    val committer = if (spec.dryRun) null else identity.commitIdentity(spec.repo)
    return credential to committer
}

/** [INPUT_DIR]-rooted tar of a Smith's [inputs] and [modes]: what both sandboxes copy in before start. */
fun inputsTar(inputs: Map<String, ByteArray>, modes: Map<String, Int>): ByteArray {
    val dir = INPUT_DIR.removePrefix("/work/")
    return tarOf(dir, inputs.mapKeys { "$dir/${it.key}" }, modes.mapKeys { "$dir/${it.key}" })
}

/** The "smith sandbox started" log line's run fragment: a ticket run's issue, or a job run's job id. */
fun runLabel(spec: SmithSpec): String = spec.issue?.let { "issue=#$it" } ?: "job=${spec.jobId}"

fun sandboxEnv(
    spec: SmithSpec, ghToken: String, credential: Pair<String, String>?, engineUrl: String,
    committer: com.codefrak.engine.github.CommitIdentity? = null,
    mcpServers: String? = null,
): List<String> {
    val env = mutableListOf(
        "SMITH_REPO=${spec.repo}", "SMITH_ID=${spec.runId}",
        "SMITH_MODEL=${spec.model}", "SMITH_EFFORT=${spec.effort}",
        "SMITH_DRY_RUN=${if (spec.dryRun) "1" else "0"}", "SMITH_MODE=${spec.mode}",
    )
    // One of the two: a ticket run's issue, or a job run's job id (run-smith.sh takes either).
    spec.issue?.let { env += "SMITH_ISSUE=$it" }
    spec.jobId?.let { env += "SMITH_JOB=$it" }
    spec.dryOutcome?.let { env += "SMITH_DRY_OUTCOME=$it" }
    spec.ghScopes?.let { env += "SMITH_GH_SCOPES=$it" }
    // A parent run's turn budget (engine/docs/smith-runs.md, "Turn budget"); every other run keeps the script's default.
    spec.turns?.let { env += "SMITH_TURNS=$it" }
    // The repo's Smith token (RepoTokens.forSmith): no Issues permission, never the repo token.
    env += "GH_TOKEN=$ghToken"
    committer?.let { env += "SMITH_GIT_NAME=${it.name}"; env += "SMITH_GIT_EMAIL=${it.email}" }
    if (credential != null) {
        env += "${credential.first}=${credential.second}"
        env += "SMITH_ACCOUNT=${spec.account}"
    }
    if (engineUrl.isNotBlank()) env += "ENGINE_URL=$engineUrl"
    // The repo's servers (#2449): in this env only, which both sandboxes keep out of their specs (envSecret, withEnv).
    mcpServers?.takeIf { it.isNotBlank() }?.let { env += "${SmithMcpServers.ENV}=$it" }
    return env
}
