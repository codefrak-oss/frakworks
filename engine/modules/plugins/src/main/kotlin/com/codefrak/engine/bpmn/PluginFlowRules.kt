package com.codefrak.engine.bpmn

import com.codefrak.sdk.CallableSmith
import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.impl.BpmnModelConstants
import org.operaton.bpm.model.bpmn.instance.BaseElement
import org.operaton.bpm.model.bpmn.instance.CallActivity
import org.operaton.bpm.model.bpmn.instance.Expression
import org.operaton.bpm.model.bpmn.instance.ScriptTask
import org.operaton.bpm.model.bpmn.instance.Task
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonExecutionListener
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonField
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonIn
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputParameter
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonOut
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonOutputParameter
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonTaskListener
import org.operaton.bpm.model.xml.instance.ModelElementInstance

/**
 * The rules a plugin jar's diagram keeps on top of [FlowRules.problems] (engine/README.md, "Custom jobs and the SDK":
 * flow jobs), which `jobs/PluginFlows` applies when a flow job is registered.
 */
object PluginFlowRules {
    /** What a name or class binds to: the plugin jar's own context and loader, and the engine's. */
    interface Binding {
        /** A bean of that name in the jar's own context (not its SDK parent). */
        fun inPlugin(name: String): Boolean
        /** A class the jar's loader defines itself (not one it finds in the engine's). */
        fun classInPlugin(fqcn: String): Boolean
        /** A bean of that name in the engine's context: the engine's resolver finds it first, so a plugin may not name it. */
        fun inEngine(name: String): Boolean
        /** A class the engine's own loader can load. */
        fun classInEngine(fqcn: String): Boolean
    }

    private val expression = Regex("""[$#]\{([^}]*)}""")
    private val identifier = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val quoted = Regex("""'[^']*'|"[^"]*"""")
    private val keywords = setOf("and", "or", "not", "empty", "null", "true", "false", "eq", "ne", "lt", "gt", "le", "ge", "div", "mod", "instanceof")

    /**
     * The rules a plugin jar's diagram keeps on top of [FlowRules.problems]: no script task and no script listener; every
     * `operaton:delegateExpression`, `operaton:expression` and `operaton:class` on a task or a listener binds in the
     * jar ([Binding.inPlugin] by the expression's leading identifier, [Binding.classInPlugin] by the class name) and not
     * in the engine (a name both have resolves to the engine's at run time, so the diagram is refused for it); no
     * other expression in the diagram (a condition, an `in`/`out` `sourceExpression`) names an engine bean either; every
     * `callActivity` calls `callable-smith` and none of its `operaton:in` elements has `variables="all"` (the variables
     * are named: `prompt`, `repo`, `jobId` in, `result` out).
     */
    fun pluginProblems(model: BpmnModelInstance, plane: String, binding: Binding): List<String> {
        val problems = mutableListOf<String>()
        model.getModelElementsByType(ScriptTask::class.java).forEach { problems += "$plane: ${it.id} is a script task; a plugin diagram has no scripts" }
        val listeners: List<ModelElementInstance> = model.getModelElementsByType(OperatonExecutionListener::class.java) + model.getModelElementsByType(OperatonTaskListener::class.java)
        listeners.forEach { l ->
            val script = (l as? OperatonExecutionListener)?.operatonScript ?: (l as? OperatonTaskListener)?.operatonScript
            if (script != null) problems += "$plane: a listener on ${ownerOf(l)} is a script listener; a plugin diagram has no scripts"
        }
        val bound: List<Pair<String, ModelElementInstance>> = model.getModelElementsByType(Task::class.java).map { it.id to (it as ModelElementInstance) } +
            listeners.map { "a listener on ${ownerOf(it)}" to it }
        bound.forEach { (where, element) ->
            listOf("delegateExpression", "expression").forEach { attribute ->
                val text = element.getAttributeValueNs(BpmnModelConstants.OPERATON_NS, attribute) ?: return@forEach
                val name = leadingIdentifier(text)
                when {
                    name == null -> problems += "$plane: $where: $attribute \"$text\" names no bean"
                    binding.inEngine(name) -> problems += "$plane: $where: $attribute \"$text\" names $name, an engine bean; a plugin diagram binds only the jar's own beans"
                    !binding.inPlugin(name) -> problems += "$plane: $where: $attribute \"$text\" names $name, which is no bean of the jar"
                }
            }
            element.getAttributeValueNs(BpmnModelConstants.OPERATON_NS, "class")?.let { fqcn ->
                when {
                    binding.classInEngine(fqcn) -> problems += "$plane: $where: class $fqcn is an engine class; a plugin diagram binds only the jar's own classes"
                    !binding.classInPlugin(fqcn) -> problems += "$plane: $where: class $fqcn is not in the jar"
                }
            }
        }
        // Any other expression: a condition, a sourceExpression, a field, an input or output parameter; none names an engine bean.
        otherExpressions(model).forEach { (where, text) ->
            identifiers(text).filter { binding.inEngine(it) && !binding.inPlugin(it) }.distinct().forEach { name ->
                problems += "$plane: $where: \"${text.trim()}\" names $name, an engine bean"
            }
        }
        model.getModelElementsByType(CallActivity::class.java).forEach { call ->
            if (call.calledElement != CallableSmith.PROCESS_KEY) problems += "$plane: ${call.id} calls \"${call.calledElement}\"; a plugin diagram calls only ${CallableSmith.PROCESS_KEY}"
            call.extensionElements?.getChildElementsByType(OperatonIn::class.java)?.forEach { into ->
                if (into.operatonVariables == "all") problems += "$plane: ${call.id} passes in variables=\"all\"; name them (prompt, repo, jobId in; result out)"
            }
        }
        return problems.distinct()
    }

    /** Every expression text of the diagram that is not a task's or listener's binding, with where it sits. */
    private fun otherExpressions(model: BpmnModelInstance): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        model.getModelElementsByType(Expression::class.java).forEach { e -> e.textContent?.takeIf { it.isNotBlank() }?.let { found += idOf(e) to it } }
        model.getModelElementsByType(OperatonIn::class.java).forEach { e -> e.operatonSourceExpression?.let { found += idOf(e) to it } }
        model.getModelElementsByType(OperatonOut::class.java).forEach { e -> e.operatonSourceExpression?.let { found += idOf(e) to it } }
        model.getModelElementsByType(OperatonField::class.java).forEach { f ->
            f.operatonExpression?.let { found += idOf(f) to it }
            f.operatonExpressionChild?.textContent?.let { found += idOf(f) to it }
            f.operatonStringValue?.let { found += idOf(f) to it }
            f.operatonString?.textContent?.let { found += idOf(f) to it }
        }
        model.getModelElementsByType(OperatonInputParameter::class.java).forEach { p -> p.textContent?.let { found += idOf(p) to it } }
        model.getModelElementsByType(OperatonOutputParameter::class.java).forEach { p -> p.textContent?.let { found += idOf(p) to it } }
        return found
    }

    /** The first identifier of the first `${...}` in [text], or null for a literal or a plain string. */
    private fun leadingIdentifier(text: String): String? =
        expression.find(text)?.groupValues?.get(1)?.let { body -> identifier.find(quoted.replace(body, "''"))?.value?.takeIf { it !in keywords } }

    /** Every identifier of every `${...}` in [text], quoted strings and EL keywords left out. */
    private fun identifiers(text: String): List<String> =
        expression.findAll(text).flatMap { m -> identifier.findAll(quoted.replace(m.groupValues[1], "''")).map { it.value } }.filter { it !in keywords }.toList()

    private fun ownerOf(listener: ModelElementInstance): String = idOf(listener.parentElement?.parentElement ?: listener)

    private fun idOf(e: ModelElementInstance): String =
        generateSequence(e) { it.parentElement }.firstNotNullOfOrNull { (it as? BaseElement)?.id ?: it.getAttributeValue("id") } ?: e.elementType.typeName
}
