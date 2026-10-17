package com.codefrak.engine.bpmn

import org.junit.jupiter.api.Test
import org.operaton.bpm.model.bpmn.Bpmn
import org.operaton.bpm.model.bpmn.BpmnModelInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plugin rules of [PluginFlowRules.pluginProblems] (engine/README.md, "Custom jobs and the SDK": flow jobs) on inline
 * diagrams with a fake [PluginFlowRules.Binding]. Plain JUnit. The five style rules are covered by `BpmnStyleTest` on the
 * repo's diagrams; the drawings here are left out, since only the plugin rules are asked.
 */
class PluginFlowRulesTest {
    private val binding = object : PluginFlowRules.Binding {
        val plugin = setOf("mine", "alsoMine", "shared")
        val engine = setOf("noteJobEnded", "jobStore", "shared")
        override fun inPlugin(name: String) = name in plugin
        override fun classInPlugin(fqcn: String) = fqcn == "com.acme.Mine"
        override fun inEngine(name: String) = name in engine
        override fun classInEngine(fqcn: String) = fqcn.startsWith("com.codefrak.engine.")
    }

    private fun model(body: String): BpmnModelInstance = Bpmn.readModelFromStream("""<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
  <bpmn:process id="p" isExecutable="true">
$body
  </bpmn:process>
</bpmn:definitions>""".byteInputStream())

    private fun problems(body: String) = PluginFlowRules.pluginProblems(model(body), "p.bpmn", binding)

    private fun single(body: String): String {
        val found = problems(body)
        assertEquals(1, found.size, found.toString())
        return found.single()
    }

    @Test
    fun `a conforming diagram passes`() {
        val found = problems("""
    <bpmn:startEvent id="start-p" />
    <bpmn:callActivity id="call-callable-smith" calledElement="callable-smith">
      <bpmn:extensionElements>
        <operaton:in sourceExpression="${'$'}{'count the docs'}" target="prompt" />
        <operaton:in source="repo" target="repo" />
        <operaton:in source="jobId" target="jobId" />
        <operaton:out source="result" target="result" />
      </bpmn:extensionElements>
    </bpmn:callActivity>
    <bpmn:serviceTask id="task-mine" operaton:delegateExpression="${'$'}{mine}">
      <bpmn:extensionElements>
        <operaton:executionListener event="end" delegateExpression="${'$'}{alsoMine}" />
        <operaton:executionListener event="start" expression="${'$'}{mine.execute(execution)}" />
      </bpmn:extensionElements>
    </bpmn:serviceTask>
    <bpmn:serviceTask id="task-class" operaton:class="com.acme.Mine" />
    <bpmn:userTask id="user-task-ask">
      <bpmn:extensionElements>
        <operaton:taskListener event="create" delegateExpression="${'$'}{alsoMine}" />
      </bpmn:extensionElements>
    </bpmn:userTask>
    <bpmn:endEvent id="end-p" />
    <bpmn:sequenceFlow id="flow-a" sourceRef="start-p" targetRef="task-mine">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${'$'}{outcome == 'done' and not empty result}</bpmn:conditionExpression>
    </bpmn:sequenceFlow>""")
        assertEquals(emptyList(), found)
    }

    @Test
    fun `a script task and a script listener are refused`() {
        val found = problems("""
    <bpmn:scriptTask id="task-script" scriptFormat="javascript">
      <bpmn:script>1 + 1</bpmn:script>
    </bpmn:scriptTask>
    <bpmn:serviceTask id="task-mine" operaton:delegateExpression="${'$'}{mine}">
      <bpmn:extensionElements>
        <operaton:executionListener event="start">
          <operaton:script scriptFormat="groovy">println 1</operaton:script>
        </operaton:executionListener>
      </bpmn:extensionElements>
    </bpmn:serviceTask>""")
        assertEquals(2, found.size, found.toString())
        assertTrue(found.any { "task-script is a script task" in it }, found.toString())
        assertTrue(found.any { "a listener on task-mine is a script listener" in it }, found.toString())
    }

    @Test
    fun `a delegate, an expression or a class that is not the jar's is refused, naming it`() {
        assertEquals("p.bpmn: task-x: delegateExpression \"\${nobody}\" names nobody, which is no bean of the jar",
            single("""<bpmn:serviceTask id="task-x" operaton:delegateExpression="${'$'}{nobody}" />"""))
        assertEquals("p.bpmn: task-x: expression \"\${nobody.run()}\" names nobody, which is no bean of the jar",
            single("""<bpmn:serviceTask id="task-x" operaton:expression="${'$'}{nobody.run()}" />"""))
        assertEquals("p.bpmn: task-x: class com.acme.Other is not in the jar",
            single("""<bpmn:serviceTask id="task-x" operaton:class="com.acme.Other" />"""))
        assertEquals("p.bpmn: a listener on task-x: delegateExpression \"\${nobody}\" names nobody, which is no bean of the jar",
            single("""<bpmn:serviceTask id="task-x" operaton:delegateExpression="${'$'}{mine}">
      <bpmn:extensionElements><operaton:executionListener event="start" delegateExpression="${'$'}{nobody}" /></bpmn:extensionElements>
    </bpmn:serviceTask>"""))
        assertEquals("p.bpmn: task-x: delegateExpression \"plain\" names no bean",
            single("""<bpmn:serviceTask id="task-x" operaton:delegateExpression="plain" />"""))
    }

    @Test
    fun `an engine bean or class is refused even when the jar has the name too, and in any expression`() {
        assertEquals("p.bpmn: task-x: delegateExpression \"\${noteJobEnded}\" names noteJobEnded, an engine bean; a plugin diagram binds only the jar's own beans",
            single("""<bpmn:serviceTask id="task-x" operaton:delegateExpression="${'$'}{noteJobEnded}" />"""))
        assertEquals("p.bpmn: task-x: delegateExpression \"\${shared}\" names shared, an engine bean; a plugin diagram binds only the jar's own beans",
            single("""<bpmn:serviceTask id="task-x" operaton:delegateExpression="${'$'}{shared}" />"""), "the engine's resolver wins, so the name is the engine's")
        assertEquals("p.bpmn: task-x: class com.codefrak.engine.jobs.NoteJobEndedDelegate is an engine class; a plugin diagram binds only the jar's own classes",
            single("""<bpmn:serviceTask id="task-x" operaton:class="com.codefrak.engine.jobs.NoteJobEndedDelegate" />"""))
        assertEquals("p.bpmn: flow-a: \"\${jobStore.get(jobId) != null}\" names jobStore, an engine bean",
            single("""<bpmn:startEvent id="start-p" /><bpmn:endEvent id="end-p" />
    <bpmn:sequenceFlow id="flow-a" sourceRef="start-p" targetRef="end-p">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${'$'}{jobStore.get(jobId) != null}</bpmn:conditionExpression>
    </bpmn:sequenceFlow>"""))
        assertEquals("p.bpmn: call-callable-smith: \"\${jobStore.list()}\" names jobStore, an engine bean",
            single("""<bpmn:callActivity id="call-callable-smith" calledElement="callable-smith">
      <bpmn:extensionElements><operaton:in sourceExpression="${'$'}{jobStore.list()}" target="prompt" /></bpmn:extensionElements>
    </bpmn:callActivity>"""))
    }

    @Test
    fun `a call activity calls only callable-smith, with named variables`() {
        assertEquals("p.bpmn: call-other calls \"job\"; a plugin diagram calls only callable-smith",
            single("""<bpmn:callActivity id="call-other" calledElement="job" />"""))
        assertEquals("p.bpmn: call-callable-smith passes in variables=\"all\"; name them (prompt, repo, jobId in; result out)",
            single("""<bpmn:callActivity id="call-callable-smith" calledElement="callable-smith">
      <bpmn:extensionElements><operaton:in variables="all" /><operaton:out source="result" target="result" /></bpmn:extensionElements>
    </bpmn:callActivity>"""))
    }
}
