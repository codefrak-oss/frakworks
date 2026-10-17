package com.codefrak.engine.doctor

import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.PassAlong
import tools.jackson.module.kotlin.readValue

/**
 * What let a stuck ticket stick (engine/README.md, "Doctor", sightings): `where` is `engine` (the engine or the
 * framework), `target` (the stuck ticket's repo) or `none` (a human must act, but nothing is broken); `summary` is
 * one line. It is part of the sighting's signature ([Signature]).
 */
data class DoctorDefect(val where: String, val summary: String? = null)

/**
 * What a doctor Smith writes (engine/README.md, "Doctor"): why the ticket is stuck, and one remedy from the menu,
 * which the engine applies ([ApplyDoctorRemedyDelegate]). `target` names what `retry` and `run-job` act on: an
 * incident id or a job id from the brief's `stuck`. `defect` is required with every outcome but `healthy`, and an
 * applied remedy's is never `where: none`: a stuck ticket the doctor could fix with no human is one the engine could
 * have fixed itself. The Smith cannot touch the engine, Operaton or the database.
 */
data class DoctorResult(
    val outcome: String, @PassAlong val diagnosis: String? = null, val target: String? = null, @PassAlong val summary: String? = null,
    val defect: DoctorDefect? = null,
) {
    fun problems(): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        if (diagnosis.isNullOrBlank()) add("a doctor result needs a diagnosis")
        if (outcome in TARGETED && target.isNullOrBlank()) add("a $outcome needs a target: the ${if (outcome == "retry") "incident" else "job"} id from the brief")
        if (outcome != "healthy" && defect == null) add("a $outcome needs a defect: {\"where\": engine|target|none, \"summary\"}")
        defect?.let {
            if (it.where !in WHERES) add("defect.where '${it.where}' is not one of $WHERES")
            if (it.summary.isNullOrBlank()) add("a defect needs a summary")
            if (outcome in REMEDIES && it.where == "none") add("a $outcome is applied with no human, so its defect.where is engine or target, never none")
        }
    }

    companion object {
        /**
         * `healthy`: nothing is wrong; `unfixable`: a human must act; `retry`: retry an open incident; `collect-exit`:
         * deliver the exit of a Smith sandbox that exited unnoticed; `run-job`: run a due job the executor left.
         */
        val OUTCOMES = setOf("healthy", "unfixable", "retry", "collect-exit", "run-job")
        /** The remedies that act on one thing the brief names. */
        val TARGETED = setOf("retry", "run-job")
        /** The outcomes that change nothing. */
        val NO_REMEDY = setOf("healthy", "unfixable")
        /** The remedies the engine applies. */
        val REMEDIES = OUTCOMES - NO_REMEDY
        /** Where a defect is: `defect.where`. */
        val WHERES = setOf("engine", "target", "none")
        fun parse(bytes: ByteArray): DoctorResult = engineJson.readValue(bytes)
    }
}
