package com.codefrak.engine.sdk

import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.jev.JevDecider
import com.codefrak.sdk.Jev
import com.codefrak.sdk.JevAnswer
import com.codefrak.sdk.JevDecision
import com.codefrak.sdk.JevQuestion
import org.springframework.stereotype.Component

/**
 * [Jev] over the engine's [JevDecider] (engine/README.md, "Custom jobs and the SDK"), for one caller: every call
 * goes through the decider, so `HttpJevDecider` writes its `engine.jev_call` row with the [repo] and the flow
 * instance ([processInstance]) as it does for the engine's own calls. The engine makes one per job run ([EngineJevs]).
 */
class EngineJev(private val decider: JevDecider, private val repo: String?, private val processInstance: String? = null) : Jev {
    override fun decide(useCase: String, questions: Map<String, JevQuestion>, state: Any, threshold: Double?): JevDecision {
        val decision = decider.decide(useCase, JevContext(processInstance, repo, null), state,
            questions.mapValues { (_, q) -> com.codefrak.engine.jev.JevQuestion(q.type, q.instructions, q.criteria) }, threshold)
        return JevDecision(decision.callId, decision.answers?.mapValues { (_, a) ->
            JevAnswer(a.type, a.noul, a.choice, a.score, a.probabilities, a.confidence)
        })
    }
}

/** Makes the [EngineJev] a job run gets: its calls are logged against its repo and flow instance. */
@Component
class EngineJevs(private val decider: JevDecider) {
    fun forJob(repo: String?, processInstance: String? = null): Jev = EngineJev(decider, repo, processInstance)
}
