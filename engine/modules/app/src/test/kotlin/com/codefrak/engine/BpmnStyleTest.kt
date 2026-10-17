package com.codefrak.engine

import com.codefrak.engine.bpmn.FlowRules
import org.junit.jupiter.api.Test
import org.operaton.bpm.model.bpmn.Bpmn
import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * House style for every deployed diagram (engine/README.md, "Diagram rules"): the build's call of [FlowRules], the
 * rules themselves, which `jobs/PluginFlows` applies to a plugin jar's diagrams too. Plain JUnit, no Spring context:
 * it reads the BPMN files straight off the classpath.
 */
class BpmnStyleTest {
    private data class Diagram(val file: String, val model: BpmnModelInstance)

    /** Crossings that cannot be drawn away, as "file: flowA x flowB" (sorted ids), each with its reason. */
    private val allowedCrossings = mapOf(
        "feature.bpmn: flow-overflow-design x flow-rebase-conflict" to
            "the overflow loop encloses do the work and its outcome gateway; a conflict round has to cross it",
        "feature.bpmn: flow-merge-rejected x flow-overflow-design" to
            "a hitlp merge the user does not accept runs back to the work along the conflict round's line, across the same loop",
        "intake.bpmn: flow-answer-refine x flow-outcome-withdrawn" to
            "the answer loop back to refine encloses the area under the outcome gateway; the withdrawn branch has to leave through it",
    )

    private val diagrams: List<Diagram> by lazy {
        PathMatchingResourcePatternResolver().getResources("classpath*:**/*.bpmn")
            .map { r -> Diagram(r.filename!!, r.inputStream.use { Bpmn.readModelFromStream(it) }) }
            .also { assertTrue(it.size >= 4, "found only ${it.map(Diagram::file)}") }
    }

    private fun report(rule: String, problems: List<String>) {
        if (problems.isNotEmpty()) fail("$rule:\n  " + problems.joinToString("\n  "))
    }

    private fun check(rule: String, problems: (BpmnModelInstance, String) -> List<String>) =
        report(rule, diagrams.flatMap { problems(it.model, it.file) })

    @Test
    fun `ids are kebab-case and start with their element type`() =
        check("Ids must be lowercase kebab-case, type first (task-create-issue); set them in the Modeler properties panel", FlowRules::ids)

    @Test
    fun `names and annotations are lowercase`() =
        check("Names and annotation text must be lowercase (answer the smith's question, not Answer the Smith's question)", FlowRules::lowercase)

    @Test
    fun `every element is drawn and every flow is drawn from its source to its target`() =
        check("Drawing does not match the process", FlowRules::drawn)

    @Test
    fun `flows do not cross each other`() =
        check("Crossing flows; redraw them, or list the pair in allowedCrossings with a reason") { model, file -> FlowRules.crossings(model, file).filter { it !in allowedCrossings } }

    @Test
    fun `labels are placed and sit off every line`() =
        check("Labels must be placed in the file and must not touch a flow; drag them clear in the Modeler", FlowRules::labels)
}
