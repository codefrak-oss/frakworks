package com.codefrak.engine.trigger

import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.feature.FeatureMode
import com.codefrak.engine.github.IssueEvent
import java.time.OffsetDateTime

/**
 * The refusal text for an approval, or null when it starts the flow: [IssueScanner.consider]'s
 * eight-branch precedence, pulled out pure so each branch is unit-tested on its own. `actorTrusted`,
 * `alreadyRunning` and `editedAfter` are lambdas so their I/O (`Access.trusted`, `HandledIssues.startedBefore`,
 * the body-edit read) runs only when the `when` reaches that branch, as the single expression did before.
 */
internal fun approvalRefusal(
    kinds: Set<String>,
    flow: String?,
    modes: Set<String>,
    actor: String,
    parked: Boolean,
    actorTrusted: () -> Boolean,
    alreadyRunning: () -> Boolean,
    kindChangedAfter: Boolean,
    editedAfter: () -> Boolean,
): String? = when {
    flow == null && kinds.isEmpty() ->
        "authorized, but it has no kind label (${ProcessFlowStarter.FLOWS.joinToString { "`$it`" }}) to say which flow to run"
    flow == null ->
        "authorized with more than one kind label (${kinds.joinToString { "`$it`" }}); keep one and authorize again"
    flow == "feature" && modes.size != 1 ->
        "a feature needs exactly one mode label, `${FeatureMode.label(FeatureMode.AUTO)}` or `${FeatureMode.label(FeatureMode.CONFIRM)}`, " +
            "when it is authorized; it had ${if (modes.isEmpty()) "none" else modes.joinToString { "`$it`" }}. Label it and authorize again"
    !actorTrusted() ->
        "authorized by @$actor${IssueScanner.NOT_TRUSTED}"
    !parked && flow in ProcessFlowStarter.WORK_FLOWS && alreadyRunning() ->
        "a $flow on it is still running or has opened a PR (see the engine's handled issues); one run at a time, and none after a PR"
    kindChangedAfter ->
        "its kind label changed after it was authorized; authorize it again"
    editedAfter() ->
        "edited after it was authorized; what was approved is not what is there now. Authorize it again (remove and re-add `${IssueScanner.AUTHORIZED}`)"
    else -> null
}

/** A title change shows as a `renamed` event; a body edit only in GraphQL's lastEditedAt. */
internal fun editedAfter(approvalAt: OffsetDateTime, events: List<IssueEvent>, bodyEditedAt: OffsetDateTime?): Boolean =
    events.any { it.event == "renamed" && it.at > approvalAt } ||
        (bodyEditedAt != null && bodyEditedAt > approvalAt)
