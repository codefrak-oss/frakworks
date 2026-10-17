package com.codefrak.engine.trigger

import com.codefrak.engine.github.Blocker
import com.codefrak.engine.smith.IntakeRef

/**
 * For a blocker that is an ask item (engine/README.md, "Blocked by", "An earlier intake"): what became of the
 * intake, so the comment says the dependency ended rather than merged. Empty for any other issue, or when it cannot tell.
 * An intake that rewrote its issue into its ticket (`ticket == issue`, one issue per ask) and was
 * then closed is a hand-closed blocker: the note says so, and [IssueScanner.resume] treats it as any other.
 */
internal fun intakeNote(blocker: Blocker, ref: IntakeRef?): String {
    if (ref == null) return ""
    return when (ref.state) {
        IntakeRef.ENDED -> ": it is intake `${ref.intake}`'s issue, and that intake ended without filing a ticket (${ref.ended})"
        IntakeRef.FILED -> if (ref.ticket == blocker.number) ": it is intake `${ref.intake}`'s issue, refined into this ticket and then closed"
            else ": it is intake `${ref.intake}`'s issue, and that intake filed #${ref.ticket}; to wait for it, block this on #${ref.ticket}"
        else -> ": it is intake `${ref.intake}`'s issue, and that intake has not filed a ticket"
    }
}
