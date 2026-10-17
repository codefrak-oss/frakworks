package com.codefrak.engine.hitlp

/**
 * What hands a HITLP decision record back to the flow that waited on it (#2458): [HitlpPoller] calls every bean of
 * this type once a request's task is terminal, inside the transaction that stores the record, so a throw leaves the
 * row open and it is polled again next tick. An answerer ignores a [request] that is not its own (by its `kind` or
 * user task key). The flow modules implement it; none does yet.
 */
interface HitlpAnswerer {
    fun complete(request: HitlpRequest, record: Map<String, Any?>)
}
