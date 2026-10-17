package com.codefrak.engine.ci

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GET /builds/active's source: a flow parked at `receive-ci-check` is one build, listed until its check message arrives. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class ActiveBuildsTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var repository: RepositoryService
    @Autowired lateinit var builds: ActiveBuilds

    private var deployment: String? = null

    @BeforeEach
    fun deploy() {
        deployment = repository.createDeployment().addString("$KEY.bpmn", MODEL).deploy().id
    }

    @AfterEach
    fun undeploy() {
        deployment?.let { repository.deleteDeployment(it, true) }
    }

    private fun mine() = builds.list().builds.filter { it.repo == REPO }

    @Test
    fun `a flow waiting on its check is listed with repo, issue and start, and leaves once the check completes`() {
        assertTrue(mine().isEmpty())
        val first = runtime.startProcessInstanceByKey(KEY, mapOf(Vars.REPO to REPO, Vars.ISSUE to 7L,
            Vars.CI_CHECK to "engine", Vars.HEAD_SHA to "abc123"))
        val second = runtime.startProcessInstanceByKey(KEY, mapOf(Vars.REPO to REPO, Vars.ISSUE to 8L))
        val listed = mine()
        assertEquals(listOf(first.id, second.id).toSet(), listed.map { it.processInstanceId }.toSet())
        val one = listed.single { it.processInstanceId == first.id }
        assertEquals(7L, one.issue); assertEquals("engine", one.check); assertEquals("abc123", one.headSha)
        assertTrue(listed.zipWithNext().all { (a, b) -> !a.since.isAfter(b.since) })
        assertEquals("PT3H", builds.list().timeout)

        runtime.createMessageCorrelation(CI_CHECK_MESSAGE).processInstanceId(first.id).correlate()
        assertEquals(listOf(second.id), mine().map { it.processInstanceId })
        runtime.deleteProcessInstance(second.id, "test")
        assertTrue(mine().isEmpty())
    }

    companion object {
        const val KEY = "active-builds-test"
        const val REPO = "codefrak-oss/active-builds-test"

        /** A stand-in for reusable-ci-check: one wait at `receive-ci-check` on its message. */
        val MODEL = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn" id="d-$KEY" targetNamespace="http://codefrak.com/test">
  <bpmn:message id="m" name="$CI_CHECK_MESSAGE" />
  <bpmn:process id="$KEY" isExecutable="true" operaton:historyTimeToLive="1">
    <bpmn:startEvent id="s" />
    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="${ActiveBuilds.CI_WAIT_ACTIVITY}" />
    <bpmn:receiveTask id="${ActiveBuilds.CI_WAIT_ACTIVITY}" messageRef="m" />
    <bpmn:sequenceFlow id="f2" sourceRef="${ActiveBuilds.CI_WAIT_ACTIVITY}" targetRef="e" />
    <bpmn:endEvent id="e" />
  </bpmn:process>
</bpmn:definitions>"""
    }
}
