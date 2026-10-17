package com.codefrak.engine.firstmate

/**
 * Where a session's first mate runs (engine/README.md, "First-mate sessions"): a pod started with the session's MCP config,
 * whose `fm-relay` pulls the phone's messages and pushes claude's output itself (`FirstMatePodController`), stopped when
 * the session ends or freezes. Its bean is
 * [FirstMateSessionPodsBean], a [KubernetesFirstMateSessionPods].
 */
interface FirstMateSessionPods {
    /** Starts [session]'s pod; with [restore], a frozen session's snapshot, the pod resumes from it. */
    fun start(session: FirstMateSession, mcp: FirstMateMcp, restore: FirstMateSnapshot? = null)
    fun stop(session: FirstMateSession)
    /** Runs `sh -c` [script] in [session]'s pod with [stdin] fed to it; its stdout, or null when the session has no pod. */
    fun exec(session: FirstMateSession, script: String, stdin: ByteArray? = null): ByteArray?
}
