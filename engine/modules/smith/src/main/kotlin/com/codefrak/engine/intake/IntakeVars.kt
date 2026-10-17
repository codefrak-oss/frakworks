package com.codefrak.engine.intake

import com.codefrak.engine.smith.Vars

// The intake flow's process variables other flows set or read (engine/README.md, "Engine modules").

/** Who POST /intake says submitted the ask; taken as given until the API is authenticated. */
const val SUBMITTED_BY = "submittedBy"

/** The Smith run an intake was filed from, found by the engine from the caller's address (IntakeController.submit); absent for anyone else. */
const val REQUESTED_BY_RUN = "requestedByRun"
const val REQUESTED_BY_ISSUE = "requestedByIssue"
const val REQUESTED_BY_REPO = "requestedByRepo"

/** POST /intake's `authorize`: absent = the submitter authorizes if they may; false = never at filing. */
const val AUTHORIZE = "authorize"

/**
 * The engine actor (`Actor.System`'s id) that authorized an intake the engine started itself, not over HTTP:
 * `doctor` for a doctor bug (engine/README.md, "Doctor") and `friction` for a two-things friction bug ("Friction
 * notes"). POST /intake never sets it, so a login named `doctor` or `friction` is never taken for the engine's
 * actor; absent on every other intake.
 */
const val AUTHORIZED_BY_ENGINE = "authorizedByEngine"

/** The `engine.doctor_bug` row a doctor intake files (engine/README.md, "Doctor"); absent on every other intake. */
const val DOCTOR_BUG = "doctorBug"

/** The `engine.friction_group` whose two-things bug a friction intake files (engine/README.md, "Friction notes"); absent on every other intake. */
const val FRICTION_BUG = "frictionBug"

/** Who authorized an intake: its submitter, or the trusted login that started its ask item. Null until someone has. */
const val AUTHORIZED_BY = "authorizedBy"
/** The Claude Code session name `POST /intake` was asked to tell the filed ticket to; absent when it was not. */
const val NOTIFY_SESSION = "notifySession"
const val FILED_TICKET = Vars.FILED_TICKET
/** The intake's `ask` work item's id (OpenAskItemDelegate); `issue` is its ticket number. */
const val ASK_ITEM = "askItem"
/** Why a filed ticket's authorization does not carry over (FileTicketDelegate): its dependencies, one line each; null when none. */
const val DEPENDENCY_HELD = "dependencyHeld"
/**
 * The repo the filed ticket is in (`owner/name`, the target repo's own spelling): the intake's `repo` unless the refiner
 * named another target repo ([RefineResult.repo]). Set by the file steps; read by every step after them.
 */
const val TICKET_REPO = "ticketRepo"

/** The repo the intake's ticket is in: [TICKET_REPO], or the intake's own repo on an instance filed before it existed. */
fun org.operaton.bpm.engine.delegate.DelegateExecution.ticketRepo(): String =
    getVariable(TICKET_REPO) as? String ?: getVariable(Vars.REPO) as String

/** [AUTHORIZED_BY_ENGINE]'s actors: the doctor (`ApplyDoctorRemedyDelegate.ACTOR`) and the friction grouper (`FrictionBugs.ACTOR`). */
const val DOCTOR_ACTOR = "doctor"
const val FRICTION_ACTOR = "friction"

/** The longest answer to an intake's or a ticket's question (`FrakworksMcp.MAX_ANSWER_CHARS`, `TicketAmender`). */
const val MAX_ANSWER_CHARS = 20000
