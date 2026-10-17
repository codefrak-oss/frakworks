package com.codefrak.engine.intake

import com.codefrak.engine.doctor.DoctorBugLinks
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.friction.FrictionBugFilings
import com.codefrak.engine.friction.FrictionFixFilings
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The doctor-bug/friction-bug/friction-fix linking block [FileUnlabeledTicketDelegate] runs right after
 * filing its issue. The doctor bug's row is an update in the step's own transaction; a failure there is
 * logged. The friction links run in transactions of their own (`REQUIRES_NEW`), each needing a
 * second connection while the command holds its first, which emptied the pool when 11 intakes filed at
 * once (#1650): they run after the command commits, on a thread of its own ([EngineEvents.afterCommitApart]),
 * and a failure there is only logged.
 */
@Component
class FiledTicketLinks(
    private val events: EngineEvents,
    private val doctorBugs: DoctorBugLinks,
    private val frictionBugs: FrictionBugFilings,
    private val frictionFixes: FrictionFixFilings,
) {
    private val log = LoggerFactory.getLogger(FiledTicketLinks::class.java)

    fun link(doctorBug: Number?, frictionBug: Number?, intake: String, repo: String, ticket: Long, body: String) {
        doctorBug?.let { bug ->
            runCatching { doctorBugs.filed(bug.toLong(), ticket) }
                .onFailure { log.warn("intake {}: doctor bug {} not marked filed: {}", intake, bug, it.message) }
        }
        events.afterCommitApart("intake-links") { linkFriction(frictionBug, intake, repo, ticket, body) }
    }

    private fun linkFriction(frictionBug: Number?, intake: String, repo: String, ticket: Long, body: String) {
        frictionBug?.let { group ->
            runCatching { frictionBugs.filed(group.toLong(), ticket) }
                .onFailure { log.warn("intake {}: friction bug of g{} not marked filed: {}", intake, group, it.message) }
        }
        // A ticket naming `friction group g<id>` fixes those groups (engine/README.md, "Friction notes", "Fixed by").
        runCatching { frictionFixes.filed(repo, ticket, body) }
            .onFailure { log.warn("intake {}: friction groups named by #{} not linked: {}", intake, ticket, it.message) }
        // A group whose candidate fix is this intake (linked by its id) is fixed by the ticket now.
        runCatching { frictionFixes.intakeFiled(intake, repo, ticket) }
            .onFailure { log.warn("intake {}: friction groups pending on it not linked to #{}: {}", intake, ticket, it.message) }
    }
}
