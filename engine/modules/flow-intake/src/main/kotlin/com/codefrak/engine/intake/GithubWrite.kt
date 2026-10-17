package com.codefrak.engine.intake

import org.slf4j.Logger

/**
 * Runs one GitHub write; true, or false logged on [log] and appended to [failed] as `"$what: ${e.message}"`,
 * when it throws. [logPrefix] names the caller ("cancel", "amend") in the log line, and [log] is the
 * caller's own logger, so the log output is unchanged from each class's private copy of this.
 */
fun githubWrite(failed: MutableList<String>, what: String, logPrefix: String, log: Logger, write: () -> Unit): Boolean =
    try {
        write()
        true
    } catch (e: Exception) {
        log.warn("{}: {} failed: {}", logPrefix, what, e.message)
        failed += "$what: ${e.message}"
        false
    }
