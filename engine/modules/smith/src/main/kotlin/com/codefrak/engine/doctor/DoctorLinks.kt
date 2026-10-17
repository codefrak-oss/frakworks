package com.codefrak.engine.doctor

/**
 * What the intake and bug flows tell the doctor's `engine.doctor_bug` (engine/README.md, "Doctor"); `DoctorBugStore`
 * implements it, so neither flow module depends on the doctor's.
 */
interface DoctorBugLinks {
    /** The bug [id]'s intake filed it as [issue]: `open`, from `filing`. */
    fun filed(id: Long, issue: Long)
    /** The bug [id]'s intake was cancelled before it filed: `declined`, with no issue; it keeps its signature. */
    fun declineUnfiled(id: Long)
    /** The bug flow on [repo]#[issue] labelled it `needs-repro`: its doctor bug, if it has one and is `open`, is `needs-repro`. */
    fun needsRepro(repo: String, issue: Long): Boolean
}

/** Ends a doctor run from outside it (`DoctorRunEnder`): what `FlowCanceller` cancels a doctor run through. */
interface DoctorRunEnding {
    /** Stops the doctor run [id]'s Smiths, deletes it and finishes its row with [outcome] and [detail]; answers how many sandboxes it stopped. */
    fun end(id: String, outcome: String, detail: String): Int

    companion object {
        /** The outcome of a run a person (or a Smith) cancelled. */
        const val CANCELLED = "cancelled"
        /** The outcome of a run the tick ended because its ticket's flow ended or left the run's position. */
        const val ENDED = "ended"
    }
}
