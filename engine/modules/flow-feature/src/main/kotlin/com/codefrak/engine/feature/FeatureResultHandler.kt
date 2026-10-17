package com.codefrak.engine.feature

import com.codefrak.engine.repos.DEFAULT_CHILD_MODE
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.PART_LINE
import com.codefrak.engine.smith.SplitDraft
import com.codefrak.engine.smith.SplitDraftRules
import com.codefrak.engine.smith.ResultHandler
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.repo
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.stereotype.Component
import org.operaton.bpm.engine.variable.Variables

/**
 * A feature's design, replan and accept runs' results (`read result`, [ResultHandler]). [repos] gives the repo's
 * child-work mode a split freezes on the feature instance (engine/README.md, "Split features"); null (a test) means `serial`.
 */
@Component
class FeatureResultHandler(private val repos: TargetRepos? = null) : ResultHandler {
    override val modes = setOf("design", "replan", "accept")

    override fun handle(execution: DelegateExecution, mode: String, bytes: ByteArray, enforce: (List<String>) -> Unit): String = when (mode) {
        "design" -> {
            val design = DesignResult.parse(bytes)
            enforce(design.problems())
            if (design.outcome == "split") {
                // Depth 1: a child (its body carries the part line) may not split again; the prompt said so.
                val body = (execution.getVariable(Vars.TICKET) as? ByteArray)?.let { Brief.ticket(it).body }.orEmpty()
                PART_LINE.find(body)?.let { error("a child ticket (${it.value}) may not split again: the depth limit is 1; plan it in one run or ask") }
                execution.setVariable(Vars.SPLIT, Variables.byteArrayValue(SplitState(design.children).bytes()))
                // The repo's child-work mode, frozen now: later runs of this feature read it here, not from the repo row mid-split.
                execution.setVariable(Vars.CHILD_MODE, repos?.childMode(execution.repo()) ?: DEFAULT_CHILD_MODE)
            }
            if (design.outcome != "ask") execution.setVariable(Vars.PLAN, Variables.byteArrayValue(DesignResult.planBytes(design.plan!!)))
            execution.setVariable(Vars.OVERFLOWED, false)
            // Confirm mode stops for the questions the Smith is not sure enough to assume; auto lets every assumption stand.
            val confirm = execution.getVariable(Vars.FEATURE_MODE) != FeatureMode.AUTO
            if (design.outcome != "ask" && confirm && design.userQuestion() != null) "ask" else design.outcome
        }
        "replan" -> {
            val replan = ReplanResult.parse(bytes)
            var split = SplitState.of(execution.getVariable(Vars.SPLIT) as? ByteArray) ?: error("no split state to replan")
            // The just-closed child first (merged or satisfied), so it counts as a filed child in the restating check.
            // A child its work split puts its drafts in its place first, so `continue` is checked on them too.
            (execution.getVariable(Vars.CLOSED_CHILD) as? Number)?.toLong()?.let {
                split = split.closed(it, (execution.getVariable(Vars.CLOSED_CHILD_PR) as? Number)?.toLong(), ChildDraft.parse(execution.getVariable(Vars.CLOSED_CHILD_SPLIT) as? ByteArray))
            }
            enforce(replan.problems(split.filed, split.drafts))
            execution.setVariable(Vars.REPLAN, Variables.byteArrayValue(bytes))
            replan.outcome
        }
        else -> {
            val acceptance = AcceptResult.parse(bytes)
            enforce(acceptance.problems())
            execution.setVariable(Vars.ACCEPTANCE, Variables.byteArrayValue(bytes))
            acceptance.outcome
        }
    }
}

/** The design run's draft rules for a split work result (engine/README.md, "A child that splits"), for `read result`. */
@Component
class ChildSplitRules : SplitDraftRules {
    override fun problems(children: List<SplitDraft>): List<String> = buildList {
        if (children.size !in ChildDraft.MIN..ChildDraft.MAX) add("outcome 'split' needs ${ChildDraft.MIN}-${ChildDraft.MAX} children, not ${children.size}")
        addAll(ChildDraft.problems(children.map(ChildDraft::of)))
    }
}
