package com.codefrak.engine.trigger

import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.feature.FeatureMode
import com.codefrak.engine.github.IssueEvent

/** The labels among `of` the issue carried when it was authorized, replayed from its label events. */
fun labelsAt(of: Set<String>, approval: IssueEvent, events: List<IssueEvent>): Set<String> {
    val labels = mutableSetOf<String>()
    for (e in events.takeWhile { it.id != approval.id }) {
        if (e.label !in of) continue
        if (e.event == "labeled") labels += e.label!! else if (e.event == "unlabeled") labels -= e.label!!
    }
    return labels
}

/** The kind labels the issue carried when it was authorized. */
fun kindsAt(approval: IssueEvent, events: List<IssueEvent>) = labelsAt(ProcessFlowStarter.FLOWS, approval, events)

fun kindChangedAfter(approval: IssueEvent, events: List<IssueEvent>): Boolean =
    events.dropWhile { it.id != approval.id }.drop(1)
        .any { it.event in setOf("labeled", "unlabeled") && (it.label in ProcessFlowStarter.FLOWS || it.label in FeatureMode.LABELS) }
