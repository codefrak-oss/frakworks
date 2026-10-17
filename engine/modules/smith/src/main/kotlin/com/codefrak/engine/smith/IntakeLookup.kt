package com.codefrak.engine.smith

/**
 * What a Smith run needs of the intake flow (engine/README.md, "Engine modules"), implemented by the `intake` package:
 * the earlier intakes a ticket names, for the brief, and the refiner's own tier, for a `refine` run.
 */
interface IntakeLookup : LineageSources {
    /** The intakes [text] names in [repo], resolved; a ticket's own ask item [own] is not one. */
    fun references(repo: String, text: String, own: Long?): List<IntakeRef>

    /** The tier every `refine` run resolves its model and effort from. */
    fun refinerTier(): Int
}

/** A retro note's home (`friction`): records one item of a run's retro.json and answers null, or why it was skipped. */
fun interface RetroNotes {
    fun record(repo: String?, issue: Long?, runId: String, item: Any?, jobId: String?): String?
}
