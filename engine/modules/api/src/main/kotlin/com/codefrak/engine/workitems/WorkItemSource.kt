package com.codefrak.engine.workitems

/**
 * An outside tracker work items come from and may be published to (engine/docs/work-items.md, "Sources"). GitHub is the
 * one built ([GitHubWorkItemSource], a stub not wired into any flow yet); a Jira source is another implementation of
 * this interface, registered as a bean beside it. [import] answers what to create, with [NewWorkItem.trusted] false:
 * outside text is fenced. [publish] is a one-way copy; nothing a source answers back is read.
 */
interface WorkItemSource {
    /** The source's name, as `source_ref` prefixes it: `github`, later `jira`. */
    val name: String

    /** The item to create for [ref] (an `owner/name#n`, a Jira key) in target repo [repo], or null when there is none. */
    fun import(repo: String, ref: String): NewWorkItem?

    /** Publishes [item] one way; answers the source's reference to the published copy. */
    fun publish(item: WorkItem): String
}
