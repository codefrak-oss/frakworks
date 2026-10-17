package com.codefrak.engine.intake

import com.codefrak.engine.github.TokenIdentity
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.ResultHandler
import com.codefrak.engine.smith.repo
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.stereotype.Component

/**
 * A refine run's result (`read result`, [ResultHandler]): the repo's token's known scopes decide it, since a ticket
 * whose Allowed scope reaches .github/workflows/ needs `workflow` to push; a `file` gets Jev's pick of kind, feature
 * mode and tier ([RefinePicker]). A `repo` that is not a registered target repo is refused, naming them; a ticket filed in
 * another repo is checked against that repo's token's scopes.
 */
@Component
class RefineResultHandler(private val identity: TokenIdentity, private val repos: TargetRepos,
                          private val refinePicker: RefinePicker? = null) : ResultHandler {
    override val modes = setOf("refine")

    override fun handle(execution: DelegateExecution, mode: String, bytes: ByteArray, enforce: (List<String>) -> Unit): String =
        RefineResult.parse(bytes).also {
            val unknown = RefineResult.repoProblem(it.repo, repos.list().map { r -> r.fullName })
            val target = it.repo?.let { r -> repos.find(r.trim())?.fullName } ?: execution.repo()
            enforce(it.problems(identity.scopes(target)) + listOfNotNull(unknown)); refinePicker?.pick(execution, it)
        }.outcome
}
