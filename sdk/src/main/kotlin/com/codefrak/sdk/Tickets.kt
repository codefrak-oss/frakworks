package com.codefrak.sdk

/** A ticket as the engine read it: an issue in a target repo. */
data class Ticket(val repo: String, val number: Long, val title: String, val body: String, val author: String)

/**
 * Tickets through the engine's GitHub access (engine/README.md, "Custom jobs and the SDK"): every method
 * names its repo, `owner/name`, since the engine works many target repos and has no default one. The engine
 * writes with the repo's own token (engine/README.md, "Each repo's token"); a repo the engine does not have
 * is refused.
 */
interface Tickets {
    /** Files an issue; answers its number. */
    fun create(repo: String, title: String, body: String, labels: List<String> = emptyList()): Long
    /** The issue as it is now. */
    fun read(repo: String, number: Long): Ticket
    /** Changes what is given and leaves the rest: a null title, body or labels is untouched; `labels` replaces the set. */
    fun update(repo: String, number: Long, title: String? = null, body: String? = null, labels: List<String>? = null)
    /** Closes the issue with a GitHub state reason, `completed` or `not_planned`. */
    fun close(repo: String, number: Long, reason: String = COMPLETED)

    companion object {
        const val COMPLETED = "completed"
        const val NOT_PLANNED = "not_planned"
    }
}
