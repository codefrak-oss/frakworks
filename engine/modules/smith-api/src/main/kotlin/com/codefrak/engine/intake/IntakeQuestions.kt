package com.codefrak.engine.intake

import com.codefrak.engine.smith.Answer
import org.operaton.bpm.engine.HistoryService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

/** `engine.intake_question`: what refiners asked and what the user answered, per intake. */
@Repository
class IntakeQuestions(private val jdbc: JdbcClient, private val history: HistoryService) {
    companion object {
        /**
         * Where a wait filed as a HITLP task is decided (#2462): "Decide it on <url>" when the server gave a decision
         * URL, else "HITLP task <id> on the engine's HITLP server".
         */
        fun answerHint(decisionUrl: String?, taskId: String): String =
            decisionUrl?.takeIf { it.isNotBlank() }?.let { "Decide it on $it" } ?: "HITLP task $taskId on the engine's HITLP server"

        private const val SELECT = "SELECT id, intake, full_name, issue, question, asked_at, answer, answered_by, answered_at, asked_of FROM engine.intake_question"
        private val ROW = { rs: ResultSet, _: Int ->
            IntakeQuestion(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                rs.getObject(6, OffsetDateTime::class.java), rs.getString(7), rs.getString(8), rs.getObject(9, OffsetDateTime::class.java), rs.getString(10))
        }
    }

    /**
     * Records a question; [askedOf] names the live parent (`#<n>`) a child's question waits on (#100049), null for one
     * asked of the user.
     */
    fun ask(intake: String, repo: String, issue: Long, question: String, askedOf: String? = null) {
        jdbc.sql("INSERT INTO engine.intake_question (intake, full_name, issue, question, asked_of) VALUES (:p, :r, :i, :q, :o)")
            .param("p", intake).param("r", repo).param("i", issue).param("q", question).param("o", askedOf).update()
    }

    /** The question an intake is waiting on (of the user or of a live parent), or null when it is not waiting. */
    fun pending(intake: String): IntakeQuestion? =
        jdbc.sql("$SELECT WHERE intake = :p AND answered_at IS NULL ORDER BY asked_at DESC, id DESC").param("p", intake)
            .query(ROW).optional().orElse(null)

    /** Every question still waiting on live parent [askedOf] (`#<n>`) in [repo], oldest first; one per child flow at most. */
    fun waitingOn(repo: String, askedOf: String): List<IntakeQuestion> =
        jdbc.sql("$SELECT WHERE full_name = :r AND asked_of = :o AND answered_at IS NULL ORDER BY asked_at, id")
            .param("r", repo).param("o", askedOf).query(ROW).list()

    /**
     * Hands a question waiting on a live parent to the user: clears `asked_of`, so the row reads as one asked of the user
     * and [open] lists it. False when it was answered meanwhile or never waited on a parent.
     */
    fun escalate(id: Long): Boolean =
        jdbc.sql("UPDATE engine.intake_question SET asked_of = NULL WHERE id = :id AND answered_at IS NULL AND asked_of IS NOT NULL")
            .param("id", id).update() == 1

    /**
     * A follow-up the user added to an intake still refining ([IntakeAmender]): a row answered as it is written, so it
     * never waits on anyone and the next refine run's `answers` carries it.
     */
    fun followUp(intake: String, repo: String, issue: Long, question: String, text: String, by: String) {
        jdbc.sql("""INSERT INTO engine.intake_question (intake, full_name, issue, question, answer, answered_by, answered_at)
                    VALUES (:p, :r, :i, :q, :a, :b, CURRENT_TIMESTAMP)""")
            .param("p", intake).param("r", repo).param("i", issue).param("q", question).param("a", text).param("b", by).update()
    }

    /** False when it was already answered. */
    fun answer(id: Long, answer: String, by: String): Boolean =
        jdbc.sql("UPDATE engine.intake_question SET answer = :a, answered_by = :b, answered_at = CURRENT_TIMESTAMP WHERE id = :id AND answered_at IS NULL")
            .param("a", answer).param("b", by).param("id", id).update() == 1

    /**
     * Settles every question still waiting on a cancelled flow, so it leaves the "needs you" list:
     * the answer records the cancel (`cancelled: <reason>`) and `by`.
     */
    fun withdraw(intake: String, by: String, reason: String) {
        jdbc.sql("UPDATE engine.intake_question SET answer = :a, answered_by = :b, answered_at = CURRENT_TIMESTAMP WHERE intake = :p AND answered_at IS NULL")
            .param("a", "cancelled: $reason").param("b", by).param("p", intake).update()
    }

    /** Every question on an intake, oldest first, answered or not. */
    fun list(intake: String): List<IntakeQuestion> =
        jdbc.sql("$SELECT WHERE intake = :p ORDER BY asked_at, id").param("p", intake).query(ROW).list()

    /**
     * The answered ones, as the brief carries them to the next refine run, in the order they were answered: the newest
     * answer is last, which the duplicate question's record reads, even when a follow-up was added while it waited.
     */
    fun answered(intake: String): List<Answer> =
        list(intake).filter { it.answer != null }.sortedWith(compareBy({ it.answeredAt }, { it.id })).map { Answer(it.question, it.answer!!, it.answeredBy!!) }

    /**
     * Every question waiting on the user, across intakes, oldest first: a UI's "needs you" list. A question waiting on a
     * live parent (`asked_of` set, #100049) is not on it until the parent escalates it. A question whose flow has ended unanswered (superseded, deleted, or finished) is settled here
     * first, answered `closed: flow ended` by `engine`, so it leaves the list; that also settles rows
     * left open before this check existed. Only an instance history records as ended counts: one
     * history does not know yet (a flow still committing its start) is left waiting.
     */
    fun open(): List<IntakeQuestion> {
        val waiting = jdbc.sql("$SELECT WHERE answered_at IS NULL AND asked_of IS NULL ORDER BY asked_at, id").query(ROW).list()
        if (waiting.isEmpty()) return waiting
        val ended = history.createHistoricProcessInstanceQuery().processInstanceIds(waiting.map { it.intake }.toSet())
            .finished().list().associate { it.id to it.deleteReason }
        for ((intake, reason) in ended) close(intake, reason)
        return waiting.filter { it.intake !in ended }
    }

    /** Settles every question still waiting on a flow that ended without answering it. */
    private fun close(intake: String, reason: String?) {
        val why = "closed: flow ended" + (reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        jdbc.sql("UPDATE engine.intake_question SET answer = :a, answered_by = 'engine', answered_at = CURRENT_TIMESTAMP WHERE intake = :p AND answered_at IS NULL")
            .param("a", why).param("p", intake).update()
    }
}

