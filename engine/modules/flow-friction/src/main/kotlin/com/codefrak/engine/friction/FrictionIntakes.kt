package com.codefrak.engine.friction

import com.codefrak.engine.intake.IntakeTickets
import org.operaton.bpm.engine.HistoryService
import org.springframework.stereotype.Component

/** Where an intake linked as a group's candidate fix stands ([FrictionIntakes]). */
sealed interface IntakeFate {
    /** No `intake` process instance has that id. */
    object Unknown : IntakeFate
    /** Still running in [repo], no ticket filed yet. */
    data class Running(val repo: String) : IntakeFate
    /** It filed [repo]#[issue] (its `ticket` variable), running still or ended. */
    data class Filed(val repo: String, val issue: Long) : IntakeFate
    /** It ended with no ticket: cancelled, closed, answered, a duplicate, refused, insufficient-permissions, anything. */
    object Ended : IntakeFate
}

/** An intake's [IntakeFate] by its id; an interface so [FrictionFixes] is tested without real intakes. */
fun interface IntakeFates {
    fun of(intake: String): IntakeFate
}

/** [IntakeFates] over Operaton's history (the `intake` process instance with that id) and its `engine.intake_ticket` row. */
@Component
class FrictionIntakes(private val history: HistoryService, private val links: IntakeTickets) : IntakeFates {
    companion object {
        const val INTAKE_PROCESS_KEY = "intake"
    }

    override fun of(intake: String): IntakeFate {
        val instance = history.createHistoricProcessInstanceQuery().processInstanceId(intake).processDefinitionKey(INTAKE_PROCESS_KEY)
            .singleResult() ?: return IntakeFate.Unknown
        val link = links.get(intake)
        val ticket = link?.ticket
        val repo = link?.repo
        return when {
            ticket != null && repo != null -> IntakeFate.Filed(repo, ticket)
            instance.endTime != null -> IntakeFate.Ended
            else -> IntakeFate.Running(repo.orEmpty())
        }
    }
}
