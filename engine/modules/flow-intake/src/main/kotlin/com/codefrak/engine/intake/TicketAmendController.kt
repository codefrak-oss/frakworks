package com.codefrak.engine.intake

import com.codefrak.engine.smith.SmithRefusal
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import com.codefrak.engine.refuseBy
import com.codefrak.engine.tokenLogin
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** `POST /ticket/{owner}/{repo}/{n}/amend`'s body: the user's late addition; the login is the bearer token's (a `by` is 400). */
data class AmendTicketRequest(val text: String = "", val by: String? = null)

/** amend_ticket over HTTP ([TicketAmender]); a Smith sandbox is refused by its source address, as `POST /intake` tells it. */
@RestController
class TicketAmendController(private val amender: TicketAmender, private val sandbox: SmithSandbox) {
    private val log = LoggerFactory.getLogger(TicketAmendController::class.java)

    @PostMapping("/ticket/{owner}/{repo}/{n}/amend")
    fun amend(@PathVariable owner: String, @PathVariable repo: String, @PathVariable n: Long,
              @RequestBody body: AmendTicketRequest, request: HttpServletRequest? = null): Map<String, Any?> {
        SmithRefusal.refuseSmith(sandbox, request, log, "call amend_ticket: tickets are the user's to amend")
        refuseBy(body.by)
        return result(amender.amend("$owner/$repo", n, body.text, request.tokenLogin()))
    }

    companion object {
        /** The JSON amend_ticket and the endpoint answer. */
        fun result(done: TicketAmended): Map<String, Any?> = linkedMapOf("ok" to true, "repo" to done.repo, "ticket" to done.ticket,
            "work" to done.work, "lost" to done.lost, "failedWrites" to done.failedWrites)
    }
}
