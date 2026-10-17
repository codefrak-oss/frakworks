package com.codefrak.engine.smith

/**
 * The modes of the incident-fix process (engine/README.md, "Fix and Propose a fix") and the doctor's (engine/README.md,
 * "Doctor"): a run that fails, or leaves a broken result, is the run's outcome, never an incident of its own.
 */
val INCIDENT_MODES = setOf("fix", "diagnose", "doctor")

/**
 * The live parent's mode (engine/README.md, "Split features", child-work mode `live`): a long session that ends, by its
 * budget or by failing, is the parent's outcome `ended` with the reason ([PARENT_ENDED]), never an incident; the flow
 * decides what follows. Like [INCIDENT_MODES], never rerun (SmithReruns).
 */
val PARENT_MODES = setOf("parent")

/** A mode whose failed run is its outcome rather than an incident, and is never rerun: [INCIDENT_MODES] and [PARENT_MODES]. */
fun failureIsOutcome(mode: String?): Boolean = mode in INCIDENT_MODES || mode in PARENT_MODES

/** Why a parent run ended without a result the flow can use (a failed run, a reaped sandbox, no result.json); null when it has one. */
const val PARENT_ENDED = "parentEnded"

/** Why a fix, diagnose or doctor run could not be used (a failed run, a broken result); null when it could. */
const val FIX_ERROR = "fixError"

/** A work run's outcome when `read result` turned its result down and the round runs again. */
const val RESULT_REJECTED = "rejected"

/** The start of the refusal past [Vars.CI_NOT_MINE_MAX] declining rounds: `IncidentRetrier`'s `rerunCi` retry matches it. */
const val CI_NOT_MINE_CAP = "declined the engine check's failure as not this change's"
