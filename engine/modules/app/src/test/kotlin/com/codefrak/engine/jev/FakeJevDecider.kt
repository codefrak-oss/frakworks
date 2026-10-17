package com.codefrak.engine.jev

import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * A JevDecider that answers every noul question with [noul] and every choice
 * question with [choice] (the option and its confidence, and the rest on `new` on a
 * `friction-group` choice of a group), null for no answer,
 * except a question whose key is in [answers], which gets that answer (a score's too),
 * writes its `engine.jev_call` row like the real one, and keeps what it was asked
 * and the rows' ids, in order. With [noAccount] it answers nothing and records the
 * row as the real one does with no usable jev account: no account, the error
 * [HttpJevDecider.NO_ACCOUNT].
 */
class FakeJevDecider(private val calls: JevCalls) : JevDecider {
    private val json = jacksonObjectMapper()
    var noul: Double? = null
    var choice: Pair<String, Double>? = null
    var noAccount = false
    val answers = mutableMapOf<String, JevAnswer>()
    val questionSets = mutableListOf<Map<String, JevQuestion>>()
    val asked = mutableListOf<Triple<String, JevContext, Any>>()
    val ids = mutableListOf<String>()

    /** [asked] and [ids] without the lone `friction-two-things` calls (#1112), for tests about grouping alone. */
    val groupAsked get() = asked.filter { it.first != "friction-two-things" }
    val groupIds get() = ids.filterIndexed { i, _ -> asked[i].first != "friction-two-things" }

    fun reset() { noul = null; choice = null; noAccount = false; answers.clear(); questionSets.clear(); asked.clear(); ids.clear() }

    override fun decide(useCase: String, context: JevContext, state: Any, questions: Map<String, JevQuestion>, threshold: Double?): JevDecision {
        asked += Triple(useCase, context, state)
        questionSets += questions
        if (noAccount) {
            val id = calls.record(useCase, context, null, "jev-1.13.0", "$state", null, HttpJevDecider.NO_ACCOUNT, null, threshold)
            ids += id
            return JevDecision(id, null)
        }
        val answers = questions.mapNotNull { (key, q) ->
            if (key in this.answers) return@mapNotNull key to this.answers.getValue(key)
            when (q.type) {
                "noul" -> noul?.let { key to JevAnswer("noul", noul = it) }
                "choice" -> choice?.let { (c, p) ->
                    val rest = if (useCase == "friction-group" && c != "new" && (q.criteria as? Map<*, *>)?.containsKey("new") == true) mapOf("new" to 1.0 - p) else emptyMap()
                    key to JevAnswer("choice", choice = c, probabilities = mapOf(c to p) + rest, confidence = p)
                }
                else -> null
            }
        }.toMap().takeIf { it.isNotEmpty() }
        val response = answers?.let { json.writeValueAsString(mapOf("model" to "jev-1.13.0", "answers" to it)) }
        val id = calls.record(useCase, context, "fake-jev", "jev-1.13.0", "$state", response,
            if (answers == null) "no answer (fake)" else null, 1, threshold)
        ids += id
        return JevDecision(id, answers)
    }
}
