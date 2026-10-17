package com.codefrak.engine.smith

import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.delegate.DelegateExecution

fun DelegateExecution.repo() = getVariable(Vars.REPO) as String
fun DelegateExecution.issue() = (getVariable(Vars.ISSUE) as Number).toLong()
/** A flow that set no mode runs as `work`. */
fun DelegateExecution.mode(): String = getVariable(Vars.MODE) as? String ?: "work"
/** A job run has no issue. */
fun DelegateExecution.issueOrNull(): Long? = (getVariable(Vars.ISSUE) as? Number)?.toLong()
/** A flow that set no round is on its first. */
fun DelegateExecution.round(): Int = (getVariable(Vars.ROUND) as? Number)?.toInt() ?: 0
/** Absent when the flow resolved no tier (a fixed model/effort with no tier behind it). */
fun DelegateExecution.tier(): Int? = (getVariable(Vars.TIER) as? Number)?.toInt()

/** [instanceId]'s flow's repo, if it has one. */
fun RuntimeService.repoOf(instanceId: String): String? = getVariable(instanceId, Vars.REPO) as? String
/** [instanceId]'s flow's issue, if it has one. */
fun RuntimeService.issueOf(instanceId: String): Long? = (getVariable(instanceId, Vars.ISSUE) as? Number)?.toLong()
