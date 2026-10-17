package com.codefrak.engine.intake

import java.time.OffsetDateTime

/**
 * One row of `engine.intake_question`; `answer`, `answeredBy` and `answeredAt` are null while it waits. `askedOf` names
 * the live parent (`#<n>`) a child's question waits on (#100049; engine/docs/feature.md, "Children ask the parent"),
 * null while it waits on the user, which is every row the parent escalated and every row of a flow with no live parent.
 */
data class IntakeQuestion(
    val id: Long, val intake: String, val repo: String, val issue: Long, val question: String, val askedAt: OffsetDateTime,
    val answer: String?, val answeredBy: String?, val answeredAt: OffsetDateTime?, val askedOf: String? = null,
)
