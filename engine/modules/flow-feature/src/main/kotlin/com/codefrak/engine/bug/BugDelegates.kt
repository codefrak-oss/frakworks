package com.codefrak.engine.bug

import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import com.codefrak.engine.workitems.flag
import com.codefrak.engine.doctor.DoctorBugLinks
import com.codefrak.engine.smith.ReproduceResult
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.VerifyResult
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.springframework.stereotype.Component

/**
 * `task-mark-not-reproduced` in bpmn/bug.bpmn: the reproduce Smith could
 * neither make the bug happen nor find a cause it is confident in. Says what
 * it tried and labels the ticket [NEEDS_REPRO], leaving it open: failing to
 * reproduce in a sandbox is not evidence the bug is gone. Nothing was changed.
 * More detail in the body and a fresh `authorized` runs it again. A doctor
 * bug on the ticket is `needs-repro` from here (engine/README.md, "Doctor").
 * Bound by `${markNotReproduced}`.
 */
@Component("markNotReproduced")
class MarkNotReproducedDelegate(private val tickets: Tickets, private val doctorBugs: DoctorBugLinks) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue()
        val tried = ReproduceResult.parse(execution.getVariable(Vars.REPRODUCTION) as? ByteArray ?: error("no reproduction to report")).summary!!.trim()
        tickets.commentOn(repo, issue, "Could not reproduce this or find its cause, so nothing was changed; leaving it open.\n\n$tried\n\n" +
            "To try again, add what you saw (steps, logs, when it happens) to the body and authorize it again (remove and re-add `authorized`).\n\n— engine bug `${execution.processInstanceId}`")
        tickets.flag(repo, issue, NEEDS_REPRO)
        doctorBugs.needsRepro(repo, issue)
    }

    companion object { const val NEEDS_REPRO = "needs-repro" }
}

/**
 * `task-report-still-broken`: the fix merged, but the verify Smith still
 * reproduces the bug on the default branch. Comments why and leaves the
 * ticket open for a human; the flow ends, since another round would start
 * from a PR that is already merged. Bound by `${reportStillBroken}`.
 */
@Component("reportStillBroken")
class ReportStillBrokenDelegate(private val tickets: Tickets) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val verification = VerifyResult.parse(execution.getVariable(Vars.VERIFICATION) as? ByteArray ?: error("no verification to report"))
        tickets.commentOn(execution.repo(), execution.issue(), "Merged #${execution.getVariable(Vars.PR)}, but the bug still reproduces " +
            "on the default branch:\n\n${verification.summary!!.trim()}\n\nLeaving this open for a human.\n\n— engine bug `${execution.processInstanceId}`")
    }
}
