package com.codefrak.engine.trigger

import com.codefrak.engine.ci.CiCheckWaits
import com.codefrak.engine.webhook.FlowWakes
import org.springframework.stereotype.Component

/** A webhook wake's work ([FlowWakes]): the [IssueScanner]'s scan and the [CiCheckWaits]' poll. */
@Component
class IssueScanWakes(private val scanner: IssueScanner, private val ciWaits: CiCheckWaits) : FlowWakes {
    override fun scan() = scanner.scan().actions.map { "${it.repo}#${it.issue} ${it.flow} ${it.outcome}" }
    override fun pollChecks() { ciWaits.poll() }
}
