package com.codefrak.engine.proposal

import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.delegate.DelegateExecution

internal fun DelegateExecution.proposal() =
    ProposeResult.parse(getVariable(Vars.PROPOSAL) as? ByteArray ?: error("no proposal drafted"))
internal fun DelegateExecution.path() = proposal().path ?: error("the last propose run drafted no doc")
internal fun DelegateExecution.signature() = "— engine proposal `$processInstanceId`"

/** Who decided (the user who answered the decision), the one `startProposalBuild` authorizes the build as. */
internal const val DECIDED_BY = "decidedBy"

/** The build ticket as filed (`Ticket` bytes), which `startProposalBuild` starts from. */
internal const val BUILT_SNAPSHOT = "builtTicketSnapshot"
