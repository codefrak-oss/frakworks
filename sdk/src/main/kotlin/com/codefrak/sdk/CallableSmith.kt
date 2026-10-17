package com.codefrak.sdk

/**
 * The `callable-smith` process (engine/bpmn/callable-smith.bpmn; engine/README.md, "Custom jobs and the
 * SDK"): a plugin's diagram calls it with a [PROMPT] and a [REPO], and it runs one Smith in mode `job` on that
 * prompt and sets [RESULT], the run's result JSON (`{"outcome": "done"|"failed", "summary": ...}`), before it
 * ends. The job id is the caller's `jobId` variable when it has one, else the callable-smith instance's id.
 */
object CallableSmith {
    /** The process definition key. */
    const val PROCESS_KEY = "callable-smith"
    /**
     * In: the prompt the Smith is given verbatim, plus the sandbox's common rules. A String, or UTF-8 bytes for a prompt
     * longer than the 4000 characters a String variable holds (the engine's own refactoring run stores it so).
     */
    const val PROMPT = "prompt"
    /** In: the target repo, `owner/name`, cloned into the sandbox on a `job/<jobId>` branch. */
    const val REPO = "repo"
    /**
     * Out: the run's result JSON as text, or as UTF-8 bytes when it is longer than the 4000 characters a String
     * variable holds (a result that long as a String would fail the flush); a reader takes either.
     */
    const val RESULT = "result"
}
