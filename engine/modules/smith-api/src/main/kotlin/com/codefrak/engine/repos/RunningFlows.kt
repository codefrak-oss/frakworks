package com.codefrak.engine.repos

import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issueOf
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.stereotype.Component

/** One flow still running on a repo: its outermost process's key and instance, and its issue if it has one. */
data class RunningFlow(val processKey: String, val issue: Long?, val instanceId: String)

/**
 * The flows still running on a repo, found by the `repo` variable every flow on an issue carries
 * (matched ignoring case), suspended ones too; a called process (a chore's Smith run) counts as the
 * flow that called it. What stops `DELETE /repos/{owner}/{name}` (engine/README.md, "Its own tables").
 */
@Component
class RunningFlows(private val runtime: RuntimeService, private val repository: RepositoryService) {
    fun inRepo(repo: String): List<RunningFlow> {
        val matched = runtime.createProcessInstanceQuery().variableValueEquals(Vars.REPO, repo).matchVariableValuesIgnoreCase().list()
        return matched.groupBy { runtime.rootProcessInstanceId(it.id) }.map { (root, instances) ->
            val instance = runtime.createProcessInstanceQuery().processInstanceId(root).singleResult() ?: instances.first()
            val issue = (listOf(root) + instances.map { it.id }).firstNotNullOfOrNull { issueOf(it) }
            RunningFlow(repository.getProcessDefinition(instance.processDefinitionId).key, issue, instance.id)
        }.sortedBy { it.instanceId }
    }

    private fun issueOf(instance: String): Long? = runCatching { runtime.issueOf(instance) }.getOrNull()
}
