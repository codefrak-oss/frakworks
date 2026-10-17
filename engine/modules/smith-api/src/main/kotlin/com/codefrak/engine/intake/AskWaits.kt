package com.codefrak.engine.intake

import com.codefrak.engine.board.NeedKeys
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.smith.rootOf
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.model.bpmn.instance.FlowNode
import org.operaton.bpm.model.bpmn.instance.UserTask

/**
 * What the posting delegates of every ask wait share when they file it as a HITLP task (#2462): the user task the flow
 * waits at next ([answerTask]), the `ask` block of a Smith's question ([askBlock]), the run that asked it ([runId]) and
 * the login the wait is for ([askedBy]).
 */
object AskWaits {
    private val FENCE = Regex("```ask\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
    private val KEY = Regex("""^\s*-?\s*(\w+):\s*(.*)$""")

    /** The newest Smith run under [execution]'s root flow, else the flow itself: who a Smith's question is on behalf of. */
    fun runId(execution: DelegateExecution, stats: SmithRunStats): String =
        runCatching { stats.latestRunId(rootOf(execution)) }.getOrNull() ?: execution.processInstanceId

    /** The person the flow's variables say asked for its ticket ([NeedKeys.askerIn]), else the repo's owner. */
    fun askedBy(execution: DelegateExecution, repo: String): String =
        NeedKeys.askerIn(execution.variables) ?: repo.substringBefore('/')

    /** The id of the first user task the flow reaches from the activity [execution] is at (`user-task-answer-*`). */
    fun answerTask(execution: DelegateExecution): String {
        var node: FlowNode = execution.bpmnModelElementInstance as? FlowNode ?: error("${execution.currentActivityId} is no flow node")
        repeat(5) {
            node = node.outgoing.firstOrNull()?.target ?: error("nothing follows ${node.id}")
            if (node is UserTask) return node.id
        }
        error("no user task follows ${execution.currentActivityId}")
    }

    /**
     * The last `ask` block in [question] (bots/smith.md, "the ask block") as `{kind, options: [{value, label}]}`; null
     * when it has none. Only a block's `kind` and its options' `id` and `label` lines are read.
     */
    fun askBlock(question: String): Map<String, Any?>? {
        val body = FENCE.findAll(question).lastOrNull()?.groupValues?.get(1) ?: return null
        var kind: String? = null
        val options = mutableListOf<MutableMap<String, Any?>>()
        var inOptions = false
        for (line in body.lines()) {
            val m = KEY.find(line) ?: continue
            val (key, value) = m.destructured
            when {
                !line.startsWith(" ") && key == "options" -> inOptions = true
                !line.startsWith(" ") -> { inOptions = false; if (key == "kind") kind = value.trim() }
                inOptions && key == "id" && line.trimStart().startsWith("-") -> options += mutableMapOf("value" to value.trim(), "label" to value.trim())
                inOptions && key == "label" && options.isNotEmpty() -> options.last()["label"] = value.trim()
            }
        }
        return linkedMapOf("kind" to kind, "options" to options)
    }
}
