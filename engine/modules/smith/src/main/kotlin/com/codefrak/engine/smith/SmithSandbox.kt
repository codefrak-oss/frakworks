package com.codefrak.engine.smith

/** What one Smith run needs: the engine's view of a run, provider-agnostic. */
data class SmithSpec(
    val runId: String,
    val repo: String,
    /** The ticket the run works; null for a mode `job` run, which has [jobId] instead (engine/README.md, "Custom jobs and the SDK"). */
    val issue: Long?,
    val model: String,
    val effort: String,
    val dryRun: Boolean,
    /** `work`, `refine`, `review`, `security`, `job`, … (smith/run-smith.sh). */
    val mode: String = "work",
    /** The outcome a dry run writes to its result file. */
    val dryOutcome: String? = null,
    /** Files put in [INPUT_DIR] before the start, by name: the brief, and a security review's bundle. */
    val inputs: Map<String, ByteArray> = emptyMap(),
    /** The file mode of an [inputs] entry, by the same name; one not named here is 0644. */
    val inputModes: Map<String, Int> = emptyMap(),
    /**
     * The `engine.ai_account` the run bills to; its credential goes into the
     * sandbox as the provider's env var. Null only on a dry run, which needs
     * no credential.
     */
    val account: String? = null,
    /**
     * For a refine run: the repo's token's classic scopes, comma-separated, `none`, or `unknown` (`TokenIdentity.scopes`),
     * so the refiner can refuse a ticket reaching `.github/workflows/` without `workflow`. Null for other modes.
     */
    val ghScopes: String? = null,
    /**
     * For a mode `job` run: the job id (`CallableSmith`), which names the sandbox `smith-job-<jobId>-<run8>`, labels it
     * `frakworks.smith.job` and reaches it as `SMITH_JOB`. Null on a run with an [issue].
     */
    val jobId: String? = null,
    /**
     * The run's turn budget (`claude -p --max-turns`), as `SMITH_TURNS`; null leaves run-smith.sh's default (200). Set
     * by `start smith` for mode `parent` alone, from `engine.smith.parent-turns` (#100042; engine/docs/smith-runs.md, "Turn budget").
     */
    val turns: Int? = null,
) {
    init { require(issue != null || jobId != null) { "a smith run has an issue or a job id" } }
}

/**
 * The Smith run a request came from, as the engine found it (engine/README.md,
 * "The frakworks server in a Smith"): the labels of the running sandbox at the
 * request's source address, never what the request claims.
 */
data class SmithCaller(val runId: String, val issue: Long?, val repo: String?, val jobId: String? = null) {
    companion object
}

/** A [SmithCaller] from [labels] (container labels or pod annotations), or null without [RUN_LABEL]. */
fun SmithCaller.Companion.of(labels: Map<String, String>): SmithCaller? {
    val runId = labels[RUN_LABEL] ?: return null
    return SmithCaller(runId, labels[ISSUE_LABEL]?.toLongOrNull(), labels[REPO_LABEL], labels[JOB_LABEL])
}

/**
 * [address] stripped to a plain IP, or null when that leaves nothing: an
 * IPv6 address may come bracketed (`[::1]`), and as an IPv4-mapped IPv6
 * address (`::ffff:172.18.0.5`). What `POST /intake` identifies a calling
 * Smith by: a Smith cannot choose its address (the sandbox drops every
 * capability, `NET_RAW` and `NET_ADMIN` with them).
 */
fun callerIp(address: String): String? {
    val ip = address.removePrefix("[").removeSuffix("]").removePrefix("::ffff:")
    return ip.ifBlank { null }
}

/** Where a sandbox is in its life. `exitCode` is set only once `exited`. */
data class SandboxStatus(val running: Boolean, val exitCode: Long?)

/**
 * One sandbox exit, as the provider reports it: the handle `start` returned,
 * the run id the sandbox was started for (`SmithSpec.runId`, from the
 * container's `frakworks.smith.run` label; null if the provider cannot say),
 * and the exit code.
 */
data class SandboxExit(val handle: String, val runId: String?, val exitCode: Long)

/** Where the engine puts a Smith's inputs before it starts (smith/run-smith.sh). */
const val INPUT_DIR = "/work/in"
/** What earlier rounds left for this run; absent on a first round. */
const val BRIEF_FILE = "brief.json"
/** The same name in and out: a work Smith leaves it in /work/out, a security Smith reviews it from [INPUT_DIR]. */
const val BUNDLE_FILE = "commits.bundle"

/**
 * Where a bug's reproduce Smith leaves the files its repro needs (engine/README.md, "Bug"):
 * `collect result` keeps the directory, and the fix and verify runs find it at [INPUT_DIR]/[REPRO_INPUT].
 */
const val REPRO_DIR = "/work/out/repro"
/** The name [REPRO_DIR]'s files have under [INPUT_DIR] in the later runs. */
const val REPRO_INPUT = "repro"
/** The cap on [REPRO_DIR] as Docker's tar of it: past it the run is collected without the files. */
const val REPRO_MAX_BYTES = 32L * 1024 * 1024

/** Where a Smith writes its structured output (smith/run-smith.sh). */
const val OUTPUT_PATH = "/work/out/result.json"

/** `result` for a run that exited 0 but wrote no [OUTPUT_PATH]: failed, so a retry runs it again, but not an overflow. */
const val NO_RESULT = "no-result"

/** Where a work Smith leaves its commits as a git bundle; the engine pushes them. */
const val BUNDLE_PATH = "/work/out/$BUNDLE_FILE"

/** The modes whose Smith commits, leaving [BUNDLE_PATH]: a work run, and a propose run drafting its doc. A `job` run commits nothing the engine pushes. */
val COMMITTING_MODES = setOf("work", "propose")

/**
 * The usage meter's last reading (engine/README.md, "The usage meter"),
 * written by the sandbox's own `meter-proxy.py`; absent when the Smith made
 * no Anthropic call.
 */
const val METER_PATH = "/work/out/meter.json"

/**
 * The sandbox's cgroup v2 `memory.peak` in bytes, as text: `run-smith.sh`
 * copies it here on exit. Absent when the kernel has no `memory.peak`.
 */
const val MEMORY_PEAK_PATH = "/work/out/memory.peak"

/**
 * The retro turn's friction notes (engine/README.md, "The retro"): a JSON array of
 * `{kind, summary, evidence?, severity?}` `run-smith.sh` writes after resuming the
 * main session once; absent when the retro was off, failed or found nothing parsable.
 */
const val RETRO_PATH = "/work/out/retro.json"

/** The modes whose run gets a retro turn, and whose [RETRO_PATH] `collect result` reads. */
val RETRO_MODES = setOf("work", "review", "security")

/**
 * The one boundary between the process engine and whatever runs a Smith.
 * `DockerSandbox` starts a sibling container, `KubernetesSandbox` a Job on the
 * k3s cluster, and `SmithSandboxes` routes between them; tests use a fake.
 */
interface SmithSandbox {
    /** Create the sandbox, put `spec.inputs` in [INPUT_DIR], start it; returns its handle. */
    fun start(spec: SmithSpec): String
    /**
     * One look at the sandbox: the backstop scan (`SmithExitNotifier`) uses it; the process itself does not poll.
     * A sandbox the provider no longer knows is `running = false, exitCode = null`; any other failure throws,
     * so a provider that cannot be reached is never mistaken for every run having exited.
     */
    fun status(handle: String): SandboxStatus
    /**
     * Subscribe to sandbox exits: `listener` is called, on the provider's own
     * thread, once for every Smith sandbox that exits from now on, and
     * `onReconnect` whenever the subscription was lost and re-established
     * (exits in between may have been missed: the caller scans). Closing the
     * result ends the subscription. The default subscribes to nothing, so a
     * fake that only scripts `status()` still works with the backstop alone.
     */
    fun watchExits(listener: (SandboxExit) -> Unit, onReconnect: () -> Unit = {}): AutoCloseable = AutoCloseable {}
    /**
     * Follow the sandbox's stdout as it is written (engine/README.md, "Smith
     * runs", "Transcripts and the live-run gauge"): `onLine` gets each line,
     * without its newline, on the provider's own thread; `onEnd` once when
     * the stream ends, null when the sandbox exited, the error otherwise.
     * `fromNow` skips what was written before the call (a run resumed after
     * an engine restart). Closing the result stops following. The default
     * follows nothing and never calls either, so a fake that does not script
     * it still works: the run's gauge then ends at `collect result`.
     */
    fun follow(handle: String, fromNow: Boolean, onLine: (String) -> Unit, onEnd: (Throwable?) -> Unit): AutoCloseable =
        AutoCloseable {}
    /** The last `maxLines` lines of stdout+stderr. */
    fun logs(handle: String, maxLines: Int = 200): String
    /**
     * A file the Smith wrote ([OUTPUT_PATH], [BUNDLE_PATH] or [METER_PATH]), or null if it
     * wrote none. Readable after exit, until `remove`.
     */
    fun output(handle: String, path: String = OUTPUT_PATH): ByteArray?
    /**
     * A directory the Smith wrote ([REPRO_DIR]) as a tar archive (Docker's copy-from-container
     * format: entries named `<dir's name>/...`, with their modes), or null if there is none.
     * Throws [OutputTooLarge] when the archive passes [maxBytes]. Readable after exit, until `remove`.
     * The default reads nothing.
     */
    fun outputDir(handle: String, path: String, maxBytes: Long): ByteArray? = null
    /** Whether the kernel OOM-killed the sandbox (inspect `State.OOMKilled`); null when unknown. Readable until `remove`. */
    fun oomKilled(handle: String): Boolean? = null
    /**
     * Whether the cluster evicted or preempted the sandbox (engine/README.md, "Smith runs", "Eviction"): a node-pressure
     * eviction, an API eviction or a preemption, never an OOM kill or the Smith's own failure; null when unknown.
     * Only Kubernetes evicts; the default (Docker, fakes) never reports one. Readable until `remove`.
     */
    fun evicted(handle: String): Boolean? = null
    fun remove(handle: String)
    /**
     * Stops a sandbox still running (a short grace, then killed) and removes it;
     * a gone one is not an error. What a cancelled flow does (engine/README.md, "Smith runs").
     */
    fun stop(handle: String) = remove(handle)
    /**
     * The running Smith sandbox at [address] (a request's source IP) on the Smith
     * network, or null if none is there. Throws if the provider cannot be asked, so
     * an unreachable provider is never taken for "not a Smith". The default knows no
     * sandbox addresses.
     */
    fun runAt(address: String): SmithCaller? = null
}

/** What [SmithSandbox.outputDir] throws for a directory past its cap. */
class OutputTooLarge(val path: String, val maxBytes: Long) : RuntimeException("$path is larger than $maxBytes bytes")
