package com.codefrak.engine.doctor

import com.codefrak.engine.github.issueUrl
import java.time.OffsetDateTime

/**
 * The ask a doctor bug's intake is filed with: the sighting, as the doctor saw it. [render]'s first line is the
 * intake's title ("Engine defect: …", "Defect in owner/name: …" for `where: target`, "Regression of #n: …" for one
 * after [regressionOf]'s fix); `Kind: bug` makes the refiner file a bug.
 */
object DoctorBugAsk {
    /** The diagnosis the ask carries, at most. */
    const val MAX_DIAGNOSIS = 4000

    /** The ask for [new]'s sighting, filed in [home]; [stuckSince] is the doctor run's stuck ticket's incident start. */
    fun render(new: NewSighting, diagnosis: String?, home: String, regressionOf: DoctorBugRef?, stuckSince: OffsetDateTime?): String = buildString {
        val what = new.summary?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: "a stuck ticket the doctor could not fix"
        val old = regressionOf?.let { SightingCheck.name(home, it.repo, it.issue) }
        appendLine(when {
            old != null -> "Regression of $old: $what"
            new.where == "target" -> "Defect in ${new.repo}: $what"
            else -> "Engine defect: $what"
        })
        appendLine("Kind: bug").appendLine()
        val judged = if (new.where == "target") "a defect in its own repo (`where: target`)" else "an engine defect (`where: engine`)"
        appendLine("The engine's doctor caught ${new.repo}#${new.issue} stuck on what its Smith judged $judged " +
            "and filed this ask itself (engine/README.md, \"Doctor\"). Repeats of the same signature are lumped into the bug it files.").appendLine()
        if (regressionOf != null) appendLine("This is a regression of ${regressionOf.repo}#${regressionOf.issue}, closed as fixed: the same signature, " +
            "seen on an episode that started after the fix was in. That bug stays closed; this one takes its signature.").appendLine()
        appendLine("- Stuck ticket: ${issueUrl(new.repo, new.issue)}, flow `${new.rootInstanceId}`${stuckSince?.let { ", stuck since $it" }.orEmpty()}")
        appendLine("- Sighting: `${new.kind}`, doctor run `${new.doctorRunId}`${new.runClass?.let { ", failed run's class `$it`" }.orEmpty()}")
        appendLine("- Leaves: ${new.signature.leaves.joinToString(", ") { "`$it`" }.ifEmpty { "none" }}")
        if (new.signature.shape.isNotEmpty()) {
            appendLine("- Incidents:")
            new.signature.shape.forEach { appendLine("  - `$it`") }
        }
        appendLine("- Signature: `${new.signature.hash}`")
        new.summary?.takeIf { it.isNotBlank() }?.let { appendLine().appendLine("Summary (the doctor's Smith):").appendLine().appendLine(it.trim()) }
        diagnosis?.takeIf { it.isNotBlank() }?.let { appendLine().appendLine("Diagnosis (the doctor's Smith):").appendLine().appendLine(it.trim().take(MAX_DIAGNOSIS)) }
    }.trimEnd()
}
