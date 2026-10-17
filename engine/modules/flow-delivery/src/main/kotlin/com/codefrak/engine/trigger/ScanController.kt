package com.codefrak.engine.trigger

import com.codefrak.engine.ci.CiCheckWaits
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Unauthenticated, like the rest of the API: scan now, e.g. for an issue labelled a moment ago,
 * then look at the engine checks flows wait for; the flows it woke are `checked` actions.
 */
@RestController
class ScanController(private val scanner: IssueScanner, private val ciWaits: CiCheckWaits) {
    @PostMapping("/scan")
    fun scan(): ScanReport = scanner.scan().let { it.copy(actions = it.actions + ciWaits.poll()) }
}
