package com.codefrak.engine.bpmn

import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.BaseElement
import org.operaton.bpm.model.bpmn.instance.CallActivity
import org.operaton.bpm.model.bpmn.instance.EndEvent
import org.operaton.bpm.model.bpmn.instance.Error
import org.operaton.bpm.model.bpmn.instance.Event
import org.operaton.bpm.model.bpmn.instance.FlowNode
import org.operaton.bpm.model.bpmn.instance.Gateway
import org.operaton.bpm.model.bpmn.instance.Message
import org.operaton.bpm.model.bpmn.instance.ReceiveTask
import org.operaton.bpm.model.bpmn.instance.SequenceFlow
import org.operaton.bpm.model.bpmn.instance.StartEvent
import org.operaton.bpm.model.bpmn.instance.SubProcess
import org.operaton.bpm.model.bpmn.instance.Task
import org.operaton.bpm.model.bpmn.instance.TextAnnotation
import org.operaton.bpm.model.bpmn.instance.UserTask
import org.operaton.bpm.model.bpmn.instance.bpmndi.BpmnEdge
import org.operaton.bpm.model.bpmn.instance.bpmndi.BpmnShape
import org.operaton.bpm.model.bpmn.instance.dc.Bounds

/**
 * The diagram rules (engine/README.md, "Diagram rules"). [problems] is the house style every deployed diagram keeps,
 * which `BpmnStyleTest` holds the repo's diagrams to at build time. Every problem is one line prefixed with [plane],
 * the file's label, so a report of many diagrams reads.
 */
object FlowRules {
    private data class Point(val x: Double, val y: Double)

    /** Every style rule on one diagram, in rule order. */
    fun problems(model: BpmnModelInstance, plane: String): List<String> =
        ids(model, plane) + lowercase(model, plane) + drawn(model, plane) + crossings(model, plane) + labels(model, plane)

    /** The id prefix an element's type takes, most specific type first. */
    private val prefixes = listOf(
        UserTask::class.java to "user-task", ReceiveTask::class.java to "receive", CallActivity::class.java to "call", SubProcess::class.java to "subprocess",
        Task::class.java to "task", StartEvent::class.java to "start", EndEvent::class.java to "end", Event::class.java to "event",
        Gateway::class.java to "gateway", SequenceFlow::class.java to "flow", Message::class.java to "message", Error::class.java to "error",
    )
    private val kebab = Regex("""^[a-z0-9]+(-[a-z0-9]+)*$""")

    /** Ids are lowercase kebab-case, type first (`task-create-issue`). */
    fun ids(model: BpmnModelInstance, plane: String): List<String> {
        val checked = listOf(FlowNode::class.java, SequenceFlow::class.java, Message::class.java, Error::class.java)
            .flatMap { model.getModelElementsByType(it) }
        return checked.mapNotNull { e ->
            val prefix = prefixes.firstOrNull { it.first.isInstance(e) }?.second
            val label = e.getAttributeValue("name")?.replace("\n", " ") ?: "unnamed"
            when {
                prefix == null -> "$plane: ${e.id} ($label) is a ${e.elementType.typeName}, which has no prefix yet"
                !kebab.matches(e.id) || !e.id.startsWith("$prefix-") -> "$plane: ${e.id} ($label) should be $prefix-<words>"
                else -> null
            }
        }
    }

    /** Names and annotation text are lowercase. */
    fun lowercase(model: BpmnModelInstance, plane: String): List<String> {
        val names = model.getModelElementsByType(BaseElement::class.java).mapNotNull { e -> e.getAttributeValue("name")?.let { e.id to it } }
        val texts = model.getModelElementsByType(TextAnnotation::class.java).map { it.id to (it.text?.textContent ?: "") }
        return (names + texts).filter { (_, text) -> text != text.lowercase() }.map { (id, text) -> "$plane: $id \"${text.replace("\n", " ")}\"" }
    }

    /** Every element is drawn and every flow is drawn from its source to its target. */
    fun drawn(model: BpmnModelInstance, plane: String): List<String> {
        val tolerance = 5.0
        fun Bounds.touches(p: Point) = p.x in (x - tolerance)..(x + width + tolerance) && p.y in (y - tolerance)..(y + height + tolerance)
        val shapes = model.getModelElementsByType(BpmnShape::class.java).associateBy { it.bpmnElement?.id }
        val edges = model.getModelElementsByType(BpmnEdge::class.java).associateBy { it.bpmnElement?.id }
        val undrawn = model.getModelElementsByType(FlowNode::class.java).filter { it.id !in shapes }.map { "$plane: ${it.id} has no shape" }
        val flows = model.getModelElementsByType(SequenceFlow::class.java).mapNotNull { f ->
            val points = edges[f.id]?.waypoints?.map { Point(it.x, it.y) }
            val from = shapes[f.source.id]?.bounds
            val to = shapes[f.target.id]?.bounds
            when {
                points == null || points.size < 2 -> "$plane: ${f.id} has no line"
                from == null || to == null -> null // reported above as an undrawn element
                !from.touches(points.first()) -> "$plane: ${f.id} does not start on ${f.source.id}"
                !to.touches(points.last()) -> "$plane: ${f.id} does not end on ${f.target.id}"
                else -> null
            }
        }
        return undrawn + flows
    }

    /** Flows do not cross each other; each crossing is "`plane`: flowA x flowB" with the ids sorted, the key `allowedCrossings` takes. */
    fun crossings(model: BpmnModelInstance, plane: String): List<String> {
        // Per plane: a collapsed sub-process draws on its own plane, with its own coordinates.
        val planes = model.getModelElementsByType(BpmnEdge::class.java).filter { it.bpmnElement is SequenceFlow }.groupBy { it.parentElement }
            .values.map { edges -> edges.map { e -> e.bpmnElement.id to e.waypoints.map { Point(it.x, it.y) }.zipWithNext() } }
        return planes.flatMap { lines -> lines.indices.flatMap { i -> (i + 1 until lines.size).map { lines[i] to lines[it] } } }
            .filter { (a, b) -> a.second.any { s -> b.second.any { t -> crosses(s, t) } } }
            .map { (a, b) -> "$plane: ${listOf(a.first, b.first).sorted().joinToString(" x ")}" }
    }

    /** Labels are placed in the file and sit off every line. */
    fun labels(model: BpmnModelInstance, plane: String): List<String> =
        model.getModelElementsByType(BpmnEdge::class.java).filter { it.bpmnElement is SequenceFlow }.groupBy { it.parentElement }.flatMap { (diPlane, edges) ->
            val lines = edges.map { e -> e.bpmnElement.id to e.waypoints.map { Point(it.x, it.y) }.zipWithNext() }
            val shapes = model.getModelElementsByType(BpmnShape::class.java).filter { it.parentElement == diPlane }
            // A task's name sits inside its box; gateways, events and flows carry an outside label.
            val labelled = shapes.filter { it.bpmnElement is Gateway || it.bpmnElement is Event }
                .map { Triple(it.bpmnElement.id, it.bpmnElement.getAttributeValue("name"), it.bpmnLabel?.bounds) } +
                edges.map { Triple(it.bpmnElement.id, it.bpmnElement.getAttributeValue("name"), it.bpmnLabel?.bounds) }
            labelled.filter { !it.second.isNullOrBlank() }.mapNotNull { (id, _, bounds) ->
                if (bounds == null) return@mapNotNull "$plane: $id has a name but no placed label"
                val hit = lines.filter { (_, segments) -> segments.any { bounds.hits(it) } }.map { it.first }
                if (hit.isEmpty()) null else "$plane: $id's label sits on ${hit.joinToString()}"
            }
        }

    /** Whether a line segment passes through or touches the box. */
    private fun Bounds.hits(s: Pair<Point, Point>): Boolean {
        val (a, b) = s
        if (maxOf(a.x, b.x) < x || minOf(a.x, b.x) > x + width || maxOf(a.y, b.y) < y || minOf(a.y, b.y) > y + height) return false
        fun inside(p: Point) = p.x in x..(x + width) && p.y in y..(y + height)
        if (inside(a) || inside(b)) return true
        val c = listOf(Point(x, y), Point(x + width, y), Point(x + width, y + height), Point(x, y + height))
        return (c + c.first()).zipWithNext().any { crosses(s, it) }
    }

    /** Proper crossing only: touching at an end (a shared source or join) or running alongside does not count. */
    private fun crosses(s: Pair<Point, Point>, t: Pair<Point, Point>): Boolean {
        fun side(a: Point, b: Point, c: Point) = Math.signum((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x))
        val d1 = side(t.first, t.second, s.first); val d2 = side(t.first, t.second, s.second)
        val d3 = side(s.first, s.second, t.first); val d4 = side(s.first, s.second, t.second)
        return d1 * d2 < 0 && d3 * d4 < 0
    }
}
