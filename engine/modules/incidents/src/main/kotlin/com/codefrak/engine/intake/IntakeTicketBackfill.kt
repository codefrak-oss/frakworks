package com.codefrak.engine.intake

import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.HistoryService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Copies the links of intakes started before `engine.intake_ticket` from Operaton's history into it, at startup: the
 * only reader of an intake's `issue`, `issueUrl` and `ticket` variables outside the flow. An intake already in the table
 * is skipped, so a second run changes nothing. An intake whose history has no issue (it failed before creating one)
 * has no link to copy and is logged.
 */
@Component
class IntakeTicketBackfill(private val history: HistoryService, private val store: IntakeTickets) {
    private val log = LoggerFactory.getLogger(IntakeTicketBackfill::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() = runCatching { run() }.onFailure { log.warn("intake_ticket backfill failed: {}", it.message) }.getOrNull()

    /** The number of rows it wrote. */
    fun run(): Int {
        val known = store.ids()
        val instances = history.createHistoricProcessInstanceQuery().processDefinitionKey(IntakeRefs.INTAKE).list()
            .filter { it.id !in known }
        if (instances.isEmpty()) return 0
        val vars = instances.map { it.id }.chunked(500).flatMap { ids ->
            history.createHistoricVariableInstanceQuery().processInstanceIdIn(*ids.toTypedArray()).variableNameIn(*NAMES).list()
        }.groupBy { it.processInstanceId }
        var wrote = 0
        var skipped = 0
        for (instance in instances) {
            val v = vars[instance.id].orEmpty().associateBy { it.name }
            val repo = v[Vars.REPO]?.value as? String
            val issue = (v[Vars.ISSUE]?.value as? Number)?.toLong()
            val url = v["issueUrl"]?.value as? String
            if (repo == null || issue == null || url == null) { skipped++; continue }
            val ticket = v["ticket"]
            val row = IntakeTicket(instance.id, repo, issue, url, (ticket?.value as? Number)?.toLong())
            val filedAt = ticket?.takeIf { row.ticket != null }?.let { (it.createTime ?: instance.endTime)?.toInstant() }
            if (store.backfill(row, instance.startTime.toInstant(), filedAt)) wrote++
        }
        if (wrote > 0 || skipped > 0) log.info("intake_ticket backfill: {} rows written, {} intakes without an issue in history", wrote, skipped)
        return wrote
    }

    private companion object {
        val NAMES = arrayOf(Vars.REPO, Vars.ISSUE, "issueUrl", "ticket")
    }
}
