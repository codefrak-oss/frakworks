package com.codefrak.engine.doctor

/** Process variables of `doctor` (bpmn/doctor.bpmn). */
object DoctorVars {
    /** The doctor's process definition key (bpmn/doctor.bpmn). */
    const val PROCESS = "doctor"
    /** The ticket's repo and issue: not `repo`/`issue`, so the doctor run is not taken for the ticket's own flow (`IssueInstances`). */
    const val REPO = "doctorRepo"; const val ISSUE = "doctorIssue"
    /** The ticket's root flow, and the position (`TicketPosition.keyText`) it was stuck at. */
    const val ROOT = "doctorRoot"; const val POSITION = "doctorPosition"
    /** What `apply the remedy` made of the run: `applied`, `none` or `failed`; the remedy; why it failed; the Smith's diagnosis. */
    const val OUTCOME = "doctorOutcome"; const val REMEDY = "doctorRemedy"; const val DETAIL = "doctorDetail"; const val DIAGNOSIS = "doctorDiagnosis"
}
