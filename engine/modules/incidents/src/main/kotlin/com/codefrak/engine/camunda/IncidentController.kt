package com.codefrak.engine.camunda

import com.codefrak.engine.refuseBy
import com.codefrak.engine.tokenLogin
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** Empty: the retrier is the bearer token's login, which must be trusted; a `by` is 400. */
data class RetryRequest(val by: String? = null)

/**
 * `action`: `job` when the failed job was given one more try, `smith` when the failed
 * Smith run was started again. `rootInstanceId` is the flow the incident was on.
 */
data class Retried(val incidentId: String, val rootInstanceId: String, val action: String, val activityId: String?)

/** Retries an incident (engine/README.md, "Incidents"): [IncidentRetrier.retry] over HTTP. */
@RestController
class IncidentController(private val retrier: IncidentRetrier) {
    @PostMapping("/camunda/incidents/{id}/retry")
    fun retry(@PathVariable id: String, @RequestBody(required = false) body: RetryRequest? = null, request: HttpServletRequest? = null): Retried {
        refuseBy(body?.by)
        return retrier.retry(id, request.tokenLogin())
    }
}
