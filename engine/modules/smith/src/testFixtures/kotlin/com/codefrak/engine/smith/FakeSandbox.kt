package com.codefrak.engine.smith

/**
 * A sandbox whose exits a test fires by hand: `exit(handle, code)` delivers
 * the die event to whoever subscribed with `watchExits`, and `status()` says
 * what the scripted `states` map says (running unless a test set it), or
 * throws for a handle in `gone`, the way Docker does for a removed container.
 */
class FakeSandbox : SmithSandbox {
    val started = mutableListOf<SmithSpec>()
    val removed = mutableListOf<String>()
    /** What `output` hands back, by handle and path; absent means the Smith wrote no such file. */
    val files = mutableMapOf<Pair<String, String>, ByteArray>()
    /** What `logs` hands back, by handle, instead of the default. */
    val logText = mutableMapOf<String, String>()
    /** Every handle `logs` was asked for: the race test counts one read per run. */
    val logged = mutableListOf<String>()
    val states = mutableMapOf<String, SandboxStatus>()
    val gone = mutableSetOf<String>()
    val unreachable = mutableSetOf<String>()
    private val listeners = mutableListOf<(SandboxExit) -> Unit>()
    val watchers get() = listeners.size

    /** Handles are `container-<issue>` and issue numbers repeat across tests: each test starts from running sandboxes. */
    fun reset() { followers.clear(); states.clear(); gone.clear(); unreachable.clear(); logged.clear(); removed.clear(); stopped.clear(); files.clear(); logText.clear(); oom.clear() }

    override fun start(spec: SmithSpec): String { started += spec; return "container-${spec.issue}" }
    override fun status(handle: String): SandboxStatus {
        if (handle in gone) return SandboxStatus(false, null)
        if (handle in unreachable) throw IllegalStateException("Cannot connect to the Docker daemon")
        return states[handle] ?: SandboxStatus(true, null)
    }
    override fun watchExits(listener: (SandboxExit) -> Unit, onReconnect: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
    /** Who follows each sandbox's stdout (`follow`): the line and end callbacks, by handle. */
    val followers = java.util.concurrent.ConcurrentHashMap<String, Pair<(String) -> Unit, (Throwable?) -> Unit>>()
    override fun follow(handle: String, fromNow: Boolean, onLine: (String) -> Unit, onEnd: (Throwable?) -> Unit): AutoCloseable {
        followers[handle] = onLine to onEnd
        return AutoCloseable { followers.remove(handle) }
    }
    /** The Smith writes `line` to stdout. */
    fun print(handle: String, line: String) { followers[handle]?.first?.invoke(line) }
    /** Fire the die event for `handle`, as Docker would: on the caller's thread, with the run id from the label. Ends its followed log first. */
    fun exit(handle: String, code: Long, runId: String? = started.last { "container-${it.issue}" == handle }.runId) {
        states[handle] = SandboxStatus(false, code)
        followers.remove(handle)?.second?.invoke(null)
        listeners.forEach { it(SandboxExit(handle, runId, code)) }
    }
    /** Far past the 4000-char VARCHAR a String variable lives in (the first live run's failure). */
    override fun logs(handle: String, maxLines: Int): String {
        logged += handle
        return logText[handle] ?: ("x".repeat(50_000) + "smith: done ($handle)")
    }
    override fun output(handle: String, path: String): ByteArray? = files[handle to path]
    /** What `oomKilled` hands back, by handle; absent means not killed. */
    val oom = mutableMapOf<String, Boolean>()
    override fun oomKilled(handle: String): Boolean = oom[handle] ?: false
    /** What `evicted` hands back, by handle; absent means not evicted. */
    val evictions = mutableMapOf<String, Boolean>()
    override fun evicted(handle: String): Boolean = evictions[handle] ?: false
    override fun remove(handle: String) { removed += handle }
    val stopped = mutableListOf<String>()
    override fun stop(handle: String) { stopped += handle }
}
