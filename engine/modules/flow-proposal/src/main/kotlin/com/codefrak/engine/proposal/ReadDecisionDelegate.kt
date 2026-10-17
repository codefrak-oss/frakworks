package com.codefrak.engine.proposal

import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.jev.JevDecider
import com.codefrak.engine.jev.JevQuestion
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * `task-read-decision`: the user's last answer, read by Jev as a `choice` over the six verbs (engine/README.md,
 * "Proposal"), the state being the answer and the proposal's summary, and resolved by [DecisionVerb.read]:
 * a pick at `engine.jev.decision-floor` or above is taken (`engine.jev.decision-build-floor` for the two
 * build verbs); below it, or with no answer, the rule ([DecisionVerb.parse]) reads it as before. Sets
 * `decision` for the gateway, `decisionWhy`, and [DECIDED_BY]. The call's action is what was done with the
 * pick; its outcome is the verb the user wrote, when the rule reads one, or else how the answer to the
 * re-ask it led to was read. A structured decision ([Vars.DECISION_CHOICE], the Pitch view's choice) is taken as
 * given instead: no rule, no Jev call. Bound by `${readDecision}`.
 */
@Component("readDecision")
class ReadDecisionDelegate(
    private val jev: JevDecider,
    private val calls: JevCalls,
    @Value("\${engine.jev.decision-floor:0.8}") private val floor: Double = 0.8,
    @Value("\${engine.jev.decision-build-floor:0.95}") private val buildFloor: Double = 0.95,
) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val answer = Brief.answers(execution.getVariable(Vars.ANSWERS) as? ByteArray).lastOrNull() ?: error("no answer to read")
        (execution.getVariable(Vars.DECISION_CHOICE) as? String)?.let { verb ->
            // A structured decision (the Pitch view's choice): taken as given, never parsed, no Jev call.
            val why = execution.getVariable(Vars.DECISION_CHOICE_WHY) as? String
            (execution.getVariable(JEV_CALL) as? String)?.let { calls.recordOutcome(it, "the answer to the re-ask was a choice: $verb by @${answer.by}") }
            execution.removeVariable(Vars.DECISION_CHOICE); execution.removeVariable(Vars.DECISION_CHOICE_WHY)
            execution.setVariable(JEV_CALL, null)
            execution.setVariable(Vars.DECISION, verb)
            execution.setVariable(Vars.DECISION_WHY, why)
            execution.setVariable(DECIDED_BY, answer.by)
            return
        }
        val text = answer.answer.trim()
        val call = runCatching {
            jev.decide("proposal-decision", JevContext(execution.processInstanceId, execution.repo(), execution.issue()),
                mapOf("answer" to text, "proposal" to execution.proposal().summary?.trim()), mapOf(QUESTION to VERBS), floor)
        }.onFailure { log.warn("jev proposal-decision call failed; keeping the rule's reading: {}", it.message) }.getOrNull()
        val picked = call?.answers?.get(QUESTION)
        val pick = picked?.choice?.let { c -> DecisionVerb.WRITTEN.entries.firstOrNull { it.value == c }?.key }
        val reading = DecisionVerb.read(text, pick, picked?.confidence, floor, buildFloor)
        (execution.getVariable(JEV_CALL) as? String)?.let {
            calls.recordOutcome(it, "the answer to the re-ask read as ${reading.decision.verb}: @${answer.by}: $text")
        }
        call?.let {
            calls.setAction(it.callId, reading.action, buildFloor.takeIf { pick == DecisionVerb.BUILD || pick == DecisionVerb.BUILD_AUTO })
            reading.outcome?.let { o -> calls.recordOutcome(it.callId, o) }
        }
        execution.setVariable(JEV_CALL, call?.callId.takeIf { reading.decision.verb == DecisionVerb.UNCLEAR })
        execution.setVariable(Vars.DECISION, reading.decision.verb)
        execution.setVariable(Vars.DECISION_WHY, reading.decision.why)
        execution.setVariable(DECIDED_BY, answer.by)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReadDecisionDelegate::class.java)

        /** The process variable holding the `engine.jev_call` id of a call whose reading was re-asked, for its outcome. */
        const val JEV_CALL = "decisionJevCall"

        const val QUESTION = "decision"
        val VERBS = JevQuestion.choice(
            "The `answer` is the user's reply when asked to decide on the `proposal` (its summary). " +
                "Which decision does the answer give?",
            mapOf(
                "accept" to "Accept the proposal as written: log it, merge the doc, close the ticket; nothing is built yet",
                "accept and build" to "Accept it and file a feature that builds it now, in confirm mode: the design's open questions come to the user",
                "accept and build auto" to "Accept it and file a feature that builds it now, in auto mode with no confirmations",
                "revise" to "Redraft the doc: the user asks for changes, names what to change, or questions part of it",
                "shelve" to "Park it for later: not now, but not rejected",
                "reject" to "Reject it: do not do this",
            ),
        )
    }
}
