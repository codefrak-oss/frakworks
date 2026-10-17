package com.codefrak.engine.hitlp

/**
 * How a judge escalation `/mcp/judge` filed (engine/README.md, "The judge server") sits in `engine.hitlp_request`: kind
 * [KIND] (no [HitlpAnswerer] takes it: no flow waits on it), `process_instance` `judge:<callId>`, `user_task_key` the
 * caller it was filed for ([owner]: `login:<login>` lowercased, or `run:<runId>` for a Smith), `judge_call_id` the Jev call.
 */
object JudgeEscalation {
    const val KIND = "JUDGE"
    const val LOGIN = "login:"
    const val RUN = "run:"

    fun processInstance(callId: String) = "judge:$callId"
    fun loginOwner(login: String) = LOGIN + login.trim().lowercase()
    fun runOwner(runId: String) = RUN + runId

    /** Whether [row] is a judge escalation filed for [owner]. */
    fun ownedBy(row: HitlpRequest?, owner: String): Boolean = row != null && row.kind == KIND && row.judgeCallId != null && row.userTaskKey == owner

    /** The login [row] was filed for, null when a Smith filed it or it is no judge escalation. */
    fun login(row: HitlpRequest): String? = row.userTaskKey.takeIf { row.kind == KIND && it.startsWith(LOGIN) }?.removePrefix(LOGIN)
}
