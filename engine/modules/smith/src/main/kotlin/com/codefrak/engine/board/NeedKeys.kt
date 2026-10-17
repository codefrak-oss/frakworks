package com.codefrak.engine.board

import com.codefrak.engine.intake.DOCTOR_ACTOR
import com.codefrak.engine.intake.FRICTION_ACTOR
import com.codefrak.engine.intake.SUBMITTED_BY
import com.codefrak.engine.smith.Vars

/** What the flows tell `GET /needs` (`Needs`, in the root) with: the user tasks it names, and who a row is for. */
object NeedKeys {
    /**
     * A confirm-mode feature parked at the user's acceptance, on a feature process deployed before #583. The
     * current feature.bpmn has no such task (an accept Smith checks every feature); kept so an instance still
     * parked there shows as a need and can be answered.
     */
    const val ACCEPTANCE_TASK = "user-task-answer-acceptance"
    /** A PR changing the hitlp module or its migrations, waiting for the user's acceptance before its merge (chore, bug, feature). */
    const val MERGE_ACCEPTANCE_TASK = "user-task-answer-merge"
    /** A proposal waiting on the user's verb (bpmn/proposal.bpmn). */
    const val DECISION_TASK = "user-task-answer-decision"
    /** Askers that are parts of the engine, not people: a row they asked for is everyone's (`for` null). */
    val ENGINE_ACTORS = setOf(DOCTOR_ACTOR, FRICTION_ACTOR, "engine")

    /** The person a flow's variables say asked for its ticket: `askedBy`, else `submittedBy`; null for an engine actor or neither. */
    fun askerIn(vars: Map<String, Any?>): String? =
        ((vars[Vars.ASKED_BY] as? String) ?: (vars[SUBMITTED_BY] as? String))?.takeIf { it.isNotBlank() && it !in ENGINE_ACTORS }
}
