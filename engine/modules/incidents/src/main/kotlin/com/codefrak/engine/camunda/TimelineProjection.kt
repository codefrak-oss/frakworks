package com.codefrak.engine.camunda

import com.codefrak.engine.at
import com.codefrak.engine.engineJson
import com.codefrak.engine.events.FlowRecorder
import com.codefrak.engine.intake.IntakeTickets
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.Date

/** A root flow's row of `engine.ticket_flow`: its ticket, kind, the ticket's title and the flow's tier. */
data class TicketFlowRow(val root: String, val repo: String?, val issue: Long?, val kind: String, val title: String?, val tier: Int?)

/** A process instance of `engine.flow_instance`; times are epoch milliseconds. */
data class FlowRow(val id: String, val root: String, val superInstance: String?, val kind: String, val started: Long, val ended: Long?, val cancelled: Boolean)

/** An activity instance of `engine.flow_activity`. */
data class ActivityRow(val id: String, val instance: String, val activityId: String, val activityType: String?, val started: Long, val ended: Long?)

/** An incident of `engine.flow_incident`; [rootCause] null, or itself, for a root cause ([RootCauseIncidents]). */
data class IncidentRow(val id: String, val instance: String, val activityId: String?, val message: String?, val rootCause: String?, val created: Long, val ended: Long?)

/** A `receive-ci-check` wait of `engine.ci_wait`, keyed by its activity instance. */
data class CiWaitRow(val id: String, val instance: String, val started: Long, val ended: Long?, val check: String?, val conclusion: String?, val outcome: String?)

/**
 * The ticket timeline's projection in the engine's schema (engine/README.md, "Ticket timeline"): what the history
 * handler records of every flow ([FlowRecorder], inside the command's transaction) and what the timeline reads back,
 * with no history query. Every write is an upsert (update, else insert), so the one-time backfill ([TimelineBackfill])
 * and the live handler can both write a flow's rows and a flow backfilled mid-way is completed, not duplicated.
 */
@Repository
class TimelineProjection(private val jdbc: JdbcClient, private val intakes: IntakeTickets) : FlowRecorder {
    private val log = LoggerFactory.getLogger(TimelineProjection::class.java)

    companion object {
        /** The variables a root flow's `engine.ticket_flow` row takes. */
        private val ROOT_VARIABLES = setOf(Vars.REPO, Vars.ISSUE, Vars.TIER, Vars.TICKET, Vars.FILED_TICKET)
        private fun ms(rs: ResultSet, col: String): Long? = rs.getObject(col, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli()
    }

    // ---- the handler's writes ----

    override fun processStarted(id: String, root: String, superInstance: String?, kind: String, at: Date) {
        val updated = jdbc.sql("UPDATE engine.flow_instance SET root = :root, super_instance = :super, kind = :kind, started = :at WHERE id = :id")
            .param("root", root).param("super", superInstance).param("kind", kind).param("at", at.at()).param("id", id).update()
        if (updated == 0) jdbc.sql("INSERT INTO engine.flow_instance (id, root, super_instance, kind, started) VALUES (:id, :root, :super, :kind, :at)")
            .param("id", id).param("root", root).param("super", superInstance).param("kind", kind).param("at", at.at()).update()
        if (superInstance == null) {
            val roots = jdbc.sql("UPDATE engine.ticket_flow SET kind = :kind, started = :at WHERE root = :id")
                .param("kind", kind).param("at", at.at()).param("id", id).update()
            if (roots == 0) jdbc.sql("INSERT INTO engine.ticket_flow (root, kind, started) VALUES (:id, :kind, :at)")
                .param("id", id).param("kind", kind).param("at", at.at()).update()
        }
    }

    override fun processEnded(id: String, root: String, at: Date, cancelled: Boolean) {
        val updated = jdbc.sql("UPDATE engine.flow_instance SET ended = :at, cancelled = :c WHERE id = :id")
            .param("at", at.at()).param("c", cancelled).param("id", id).update()
        // An end for an instance the projection never saw start (started before V67, not yet backfilled): kept, with its end as its start.
        if (updated == 0) jdbc.sql("INSERT INTO engine.flow_instance (id, root, super_instance, kind, started, ended, cancelled) VALUES (:id, :root, NULL, :kind, :at, :at, :c)")
            .param("id", id).param("root", root).param("kind", "unknown").param("at", at.at()).param("c", cancelled).update()
        jdbc.sql("UPDATE engine.ticket_flow SET ended = :at WHERE root = :id").param("at", at.at()).param("id", id).update()
    }

    override fun activityStarted(id: String, instance: String, root: String, activityId: String, activityType: String?, seq: Long, at: Date) {
        val updated = jdbc.sql("UPDATE engine.flow_activity SET instance = :i, root = :root, activity_id = :a, activity_type = :t, seq = :seq, started = :at WHERE id = :id")
            .param("i", instance).param("root", root).param("a", activityId).param("t", activityType).param("seq", seq).param("at", at.at()).param("id", id).update()
        if (updated == 0) jdbc.sql("INSERT INTO engine.flow_activity (id, instance, root, activity_id, activity_type, seq, started) VALUES (:id, :i, :root, :a, :t, :seq, :at)")
            .param("id", id).param("i", instance).param("root", root).param("a", activityId).param("t", activityType).param("seq", seq).param("at", at.at()).update()
        if (activityId == TicketTimelineController.CI_WAIT) {
            val check = jdbc.sql("SELECT ci_check FROM engine.flow_instance WHERE id = :i").param("i", instance).query(String::class.java).optional().orElse(null)
            val waits = jdbc.sql("UPDATE engine.ci_wait SET instance = :i, root = :root, started = :at, check_name = COALESCE(check_name, :check) WHERE id = :id")
                .param("i", instance).param("root", root).param("at", at.at()).param("check", check).param("id", id).update()
            if (waits == 0) jdbc.sql("INSERT INTO engine.ci_wait (id, instance, root, started, check_name) VALUES (:id, :i, :root, :at, :check)")
                .param("id", id).param("i", instance).param("root", root).param("at", at.at()).param("check", check).update()
        }
    }

    override fun activityEnded(id: String, instance: String, root: String, activityId: String, activityType: String?, seq: Long, at: Date) {
        val updated = jdbc.sql("UPDATE engine.flow_activity SET ended = :at WHERE id = :id").param("at", at.at()).param("id", id).update()
        if (updated == 0) jdbc.sql("INSERT INTO engine.flow_activity (id, instance, root, activity_id, activity_type, seq, started, ended) VALUES (:id, :i, :root, :a, :t, :seq, :at, :at)")
            .param("id", id).param("i", instance).param("root", root).param("a", activityId).param("t", activityType).param("seq", seq).param("at", at.at()).update()
        if (activityId == TicketTimelineController.CI_WAIT)
            jdbc.sql("UPDATE engine.ci_wait SET ended = :at WHERE id = :id").param("at", at.at()).param("id", id).update()
    }

    override fun incident(id: String, instance: String, root: String, activityId: String?, message: String?, rootCause: String?, state: String,
                          createdAt: Date, endedAt: Date?) {
        val updated = jdbc.sql("UPDATE engine.flow_incident SET state = :s, ended = COALESCE(:ended, ended), message = COALESCE(:m, message) WHERE id = :id")
            .param("s", state).param("ended", endedAt?.at()).param("m", message?.take(4000)).param("id", id).update()
        if (updated == 0) jdbc.sql("""INSERT INTO engine.flow_incident (id, instance, root, activity_id, message, root_cause, created, ended, state)
            VALUES (:id, :i, :root, :a, :m, :rc, :c, :ended, :s)""")
            .param("id", id).param("i", instance).param("root", root).param("a", activityId).param("m", message?.take(4000)).param("rc", rootCause)
            .param("c", createdAt.at()).param("ended", endedAt?.at()).param("s", state).update()
    }

    override fun variable(instance: String, root: String, name: String, value: Any?, rootScope: Boolean) {
        when (name) {
            Vars.CI_CHECK -> {
                jdbc.sql("UPDATE engine.flow_instance SET ci_check = :v WHERE id = :i").param("v", value as? String).param("i", instance).update()
                jdbc.sql("UPDATE engine.ci_wait SET check_name = :v WHERE instance = :i AND ended IS NULL").param("v", value as? String).param("i", instance).update()
            }
            Vars.CI_CONCLUSION -> stampLatestWait(instance, "conclusion", value as? String)
            Vars.CI_OUTCOME -> stampLatestWait(instance, "outcome", value as? String)
            Vars.OUTPUT -> if (instance == root) refineResult(root, value as? ByteArray)
            in ROOT_VARIABLES -> if (instance == root && rootScope) rootVariable(root, name, value)
        }
    }

    /** `ciConclusion` and `ciOutcome` are stamped on the instance's newest `receive-ci-check` wait: the open one at a correlation, the one just left at `read check`. */
    private fun stampLatestWait(instance: String, column: String, value: String?) {
        val latest = jdbc.sql("SELECT id FROM engine.ci_wait WHERE instance = :i ORDER BY started DESC").param("i", instance)
            .query(String::class.java).list().firstOrNull() ?: return
        jdbc.sql("UPDATE engine.ci_wait SET $column = :v WHERE id = :id").param("v", value).param("id", latest).update()
    }

    /** A wait `read check` re-ran the check after ended `rerun`: the backfill's word on every wait but an instance's last. */
    fun ciWaitRerun(id: String) {
        jdbc.sql("UPDATE engine.ci_wait SET outcome = 'rerun' WHERE id = :id").param("id", id).update()
    }

    private fun rootVariable(root: String, name: String, value: Any?) {
        when (name) {
            Vars.REPO -> jdbc.sql("UPDATE engine.ticket_flow SET repo = :v WHERE root = :r").param("v", value as? String).param("r", root).update()
            Vars.ISSUE -> jdbc.sql("UPDATE engine.ticket_flow SET issue = :v WHERE root = :r").param("v", (value as? Number)?.toLong()).param("r", root).update()
            Vars.TIER -> jdbc.sql("UPDATE engine.ticket_flow SET tier = :v WHERE root = :r").param("v", (value as? Number)?.toInt()).param("r", root).update()
            // The title only, and the first seen: an amendment rewriting the ticket leaves it.
            Vars.TICKET, Vars.FILED_TICKET -> {
                val title = (value as? ByteArray)?.let { runCatching { Brief.ticket(it).title }.getOrNull() } ?: return
                jdbc.sql("UPDATE engine.ticket_flow SET title = COALESCE(title, :v) WHERE root = :r").param("v", title.take(1000)).param("r", root).update()
            }
        }
    }

    /**
     * An intake root's `output` (the refine result `call-refine-ask` copies onto it), parsed once into its `engine.intake_ticket`
     * row's `refine_outcome`, `blocker` and `reason`: the timeline's `blocked`. Any other root's `output` is not an intake's.
     * Written in its own transaction ([IntakeTickets.refined]), not the command's: `file ticket` runs in the same command and
     * stamps the same row through `REQUIRES_NEW` writes, which would wait on a row lock this transaction held.
     */
    private fun refineResult(root: String, bytes: ByteArray?) {
        val flow = ticketFlow(root) ?: return
        if (flow.kind != TicketTimelineController.INTAKE) return
        val result = bytes?.let { runCatching { engineJson.readTree(it) }.getOrNull() } ?: return
        val text = { name: String -> result.get(name)?.takeIf { !it.isNull }?.let { if (it.isString) it.asString() else it.toString() } }
        intakes.refined(root, flow.repo, flow.issue, text("outcome"), text("blocker"), text("reason"))
    }

    // ---- the timeline's reads ----

    fun ticketFlow(root: String): TicketFlowRow? =
        jdbc.sql("SELECT root, repo, issue, kind, title, tier FROM engine.ticket_flow WHERE root = :r").param("r", root)
            .query { rs, _ -> TicketFlowRow(rs.getString(1), rs.getString(2), rs.getObject(3, Long::class.javaObjectType), rs.getString(4), rs.getString(5), rs.getObject(6, Int::class.javaObjectType)) }
            .optional().orElse(null)

    /**
     * Every root flow tied to `repo`#`issue`, newest first, as [IssueInstances.roots] finds them in history: the flows carrying
     * the issue as their `repo` and `issue`, the ones `engine.handled_issue` started on it, and its intake (`engine.intake_ticket`).
     */
    fun roots(repo: String, issue: Long, handled: List<String>, intake: String?): List<FlowRow> {
        val ids: List<String> = jdbc.sql("SELECT root FROM engine.ticket_flow WHERE repo = :r AND issue = :i").param("r", repo).param("i", issue)
            .query { rs, _ -> rs.getString(1) }.list() + handled + listOfNotNull(intake)
        if (ids.isEmpty()) return emptyList()
        return ids.distinct().mapNotNull { instance(it) }.filter { it.superInstance == null }.sortedByDescending { it.started }
    }

    fun instance(id: String): FlowRow? =
        jdbc.sql("SELECT $FLOW_COLS FROM engine.flow_instance WHERE id = :id").param("id", id).query { rs, _ -> flow(rs) }.optional().orElse(null)

    /** The root and every process it called, directly or not. */
    fun tree(root: String): List<FlowRow> =
        jdbc.sql("SELECT $FLOW_COLS FROM engine.flow_instance WHERE root = :r ORDER BY started").param("r", root).query { rs, _ -> flow(rs) }.list()

    /** Oldest first; activities started in the same millisecond by Operaton's sequence counter, as the live handler saw them and the backfill replays them. */
    fun activities(root: String): List<ActivityRow> =
        jdbc.sql("SELECT id, instance, activity_id, activity_type, started, ended FROM engine.flow_activity WHERE root = :r ORDER BY started, seq, id").param("r", root)
            .query { rs, _ -> ActivityRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), ms(rs, "started")!!, ms(rs, "ended")) }.list()

    fun incidents(root: String): List<IncidentRow> =
        jdbc.sql("SELECT id, instance, activity_id, message, root_cause, created, ended FROM engine.flow_incident WHERE root = :r ORDER BY created").param("r", root)
            .query { rs, _ -> IncidentRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), ms(rs, "created")!!, ms(rs, "ended")) }.list()

    fun ciWaits(root: String): List<CiWaitRow> =
        jdbc.sql("SELECT id, instance, started, ended, check_name, conclusion, outcome FROM engine.ci_wait WHERE root = :r ORDER BY started").param("r", root)
            .query { rs, _ -> CiWaitRow(rs.getString(1), rs.getString(2), ms(rs, "started")!!, ms(rs, "ended"), rs.getString(5), rs.getString(6), rs.getString(7)) }.list()

    /** The Smith runs under [root] as [AskedBefore.askedBefore] reads them: when each ended (the instance's end), whether it runs, its question. */
    fun askedRuns(root: String): List<AskedRun> =
        jdbc.sql("""SELECT f.ended, s.question FROM engine.smith_run_stats s JOIN engine.flow_instance f ON f.id = s.run_id WHERE s.root = :r""").param("r", root)
            .query { rs, _ -> val end = ms(rs, "ended"); AskedRun(end ?: Long.MAX_VALUE, end == null, rs.getString(2)) }.list()

    // ---- the backfill's bookkeeping ----

    fun backfilled(repo: String, issue: Long): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM engine.timeline_backfill WHERE repo = :r AND issue = :i").param("r", repo).param("i", issue)
            .query(Long::class.java).single() > 0

    fun recordBackfill(repo: String, issue: Long, source: String, roots: Int, at: OffsetDateTime = OffsetDateTime.now()) {
        val updated = jdbc.sql("UPDATE engine.timeline_backfill SET backfilled_at = :at, source = :s, roots = :n WHERE repo = :r AND issue = :i")
            .param("at", at).param("s", source).param("n", roots).param("r", repo).param("i", issue).update()
        if (updated == 0) jdbc.sql("INSERT INTO engine.timeline_backfill (repo, issue, backfilled_at, source, roots) VALUES (:r, :i, :at, :s, :n)")
            .param("r", repo).param("i", issue).param("at", at).param("s", source).param("n", roots).update()
    }

    /** The tickets `engine.handled_issue` and `engine.intake_ticket` know with no backfill row yet. */
    fun ticketsWithoutBackfill(): List<Pair<String, Long>> =
        jdbc.sql("""SELECT DISTINCT t.repo, t.issue FROM (
                SELECT full_name AS repo, issue FROM engine.handled_issue WHERE process_instance IS NOT NULL
                UNION SELECT full_name AS repo, issue FROM engine.intake_ticket) t
            WHERE NOT EXISTS (SELECT 1 FROM engine.timeline_backfill b WHERE b.repo = t.repo AND b.issue = t.issue)""")
            .query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list()

    /**
     * Every projection row of the ticket's roots, gone: for the parity test and a backfill run again by hand. The Smith run
     * rows (`engine.smith_run_stats`) and their findings stay: since #1976 they are the one home of a run's model, effort,
     * tier, turns, duration and review number, which the delegates write and Operaton's history no longer has, so a backfill
     * can only fill them in ([SmithRunStats.backfill] keeps what is there), never rebuild them.
     */
    fun clear(roots: List<String>) {
        for (root in roots) {
            for (table in listOf("flow_activity", "flow_incident", "ci_wait", "flow_instance"))
                jdbc.sql("DELETE FROM engine.$table WHERE root = :r").param("r", root).update()
            jdbc.sql("DELETE FROM engine.ticket_flow WHERE root = :r").param("r", root).update()
        }
    }

    private fun flow(rs: ResultSet) = FlowRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), ms(rs, "started")!!, ms(rs, "ended"), rs.getBoolean(7))

    private val FLOW_COLS = "id, root, super_instance, kind, started, ended, cancelled"
}
