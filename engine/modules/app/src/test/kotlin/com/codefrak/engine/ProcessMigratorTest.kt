package com.codefrak.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The job executor is off in every test context (build.gradle.kts), so the failed job below is run by hand.
@SpringBootTest
class ProcessMigratorTest(
    @Autowired val migrator: ProcessMigrator,
    @Autowired val repository: RepositoryService,
    @Autowired val runtime: RuntimeService,
    @Autowired val tasks: TaskService,
    @Autowired val management: ManagementService,
) {
    private val logger = LoggerFactory.getLogger(ProcessMigrator::class.java) as Logger
    private val logged = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun capture() { logged.start(); logger.addAppender(logged) }

    @AfterEach
    fun release() { logger.detachAppender(logged); logged.stop() }

    private fun deploy(key: String, body: String, name: String? = null): String {
        repository.createDeployment().apply { if (name != null) name(name) }.addString("$key.bpmn", """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="t">
  <bpmn:process id="$key" isExecutable="true" camunda:historyTimeToLive="1">
$body
  </bpmn:process>
</bpmn:definitions>""").deploy()
        return repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult().id
    }

    private fun waitsAt(activity: String) = """
    <bpmn:startEvent id="s" />
    <bpmn:userTask id="$activity" />
    <bpmn:endEvent id="e" />
    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="$activity" />
    <bpmn:sequenceFlow id="f2" sourceRef="$activity" targetRef="e" />"""

    private fun definitionOf(id: String) = runtime.createProcessInstanceQuery().processInstanceId(id).singleResult().processDefinitionId

    private fun lines(level: Level) = logged.list.filter { it.level == level }.map { it.formattedMessage }

    @Test
    fun `a waiting instance moves to the newest version and runs on it`() {
        val key = "migrate-waiting"
        deploy(key, waitsAt("wait"))
        val id = runtime.startProcessInstanceByKey(key).id
        // v2 adds a step after the one the instance waits at.
        val v2 = deploy(key, """
    <bpmn:startEvent id="s" />
    <bpmn:userTask id="wait" />
    <bpmn:userTask id="added" />
    <bpmn:endEvent id="e" />
    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="wait" />
    <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="added" />
    <bpmn:sequenceFlow id="f3" sourceRef="added" targetRef="e" />""")

        migrator.migrate(setOf(key))

        assertEquals(v2, definitionOf(id))
        assertTrue(lines(Level.INFO).any { "$key v2" in it && "moved 1 from v1 (1)" in it }, lines(Level.INFO).toString())
        tasks.complete(tasks.createTaskQuery().processInstanceId(id).singleResult().id)
        val added = tasks.createTaskQuery().processInstanceId(id).singleResult()
        assertEquals("added", added.taskDefinitionKey)
        assertEquals(v2, added.processDefinitionId)
        tasks.complete(added.id)
        assertEquals(0L, runtime.createProcessInstanceQuery().processInstanceId(id).count())
    }

    @Test
    fun `an instance with an open incident moves and keeps its incident`() {
        val key = "migrate-incident"
        val failing = """
    <bpmn:startEvent id="s" />
    <bpmn:serviceTask id="fails" camunda:asyncBefore="true" camunda:expression="${'$'}{noSuchBean.run()}" />
    <bpmn:endEvent id="e" />
    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="fails" />
    <bpmn:sequenceFlow id="f2" sourceRef="fails" targetRef="e" />"""
        deploy(key, failing)
        val id = runtime.startProcessInstanceByKey(key).id
        val job = management.createJobQuery().processInstanceId(id).singleResult()
        management.setJobRetries(job.id, 1)
        runCatching { management.executeJob(job.id) }
        assertEquals(1L, runtime.createIncidentQuery().processInstanceId(id).count())
        val v2 = deploy(key, failing)

        migrator.migrate(setOf(key))

        assertEquals(v2, definitionOf(id))
        val incident = runtime.createIncidentQuery().processInstanceId(id).singleResult()
        assertEquals(v2, incident.processDefinitionId)
        runtime.deleteProcessInstance(id, "test done")
    }

    @Test
    fun `an instance waiting at a renamed activity stays on its version with a warning`() {
        val key = "migrate-renamed"
        val v1 = deploy(key, waitsAt("wait"))
        val id = runtime.startProcessInstanceByKey(key).id
        deploy(key, waitsAt("wait-renamed"))

        migrator.migrate(setOf(key))

        assertEquals(v1, definitionOf(id))
        assertTrue(lines(Level.WARN).any { "$key v1 -> v2 skipped" in it }, lines(Level.WARN).toString())
        assertTrue(lines(Level.INFO).any { "$key v2" in it && "left 1 on v1 (1)" in it }, lines(Level.INFO).toString())
        runtime.deleteProcessInstance(id, "test done")
    }

    /** A flow job's diagrams (a `job:<id>` deployment, "Custom jobs and the SDK") are never migrated: the job's to version. */
    @Test
    fun `an instance of a job deployment stays where it is, and the key is logged as ignored`() {
        val key = "migrate-plugin"
        val name = com.codefrak.engine.jobs.PluginFlows.deploymentName("test")
        val v1 = deploy(key, waitsAt("wait"), name)
        val id = runtime.startProcessInstanceByKey(key).id
        deploy(key, waitsAt("wait"), name)
        try {
            migrator.migrate(setOf(key))

            assertEquals(v1, definitionOf(id))
            assertEquals(listOf("process migration ignores 1 plugin flow definition(s) of job deployments: $key"), lines(Level.INFO))
            assertTrue(lines(Level.WARN).isEmpty())
        } finally {
            runtime.deleteProcessInstance(id, "test done")
            repository.createDeploymentQuery().deploymentName(name).list().forEach { repository.deleteDeployment(it.id, true) }
        }
    }

    @Test
    fun `a key with nothing stale says so`() {
        val key = "migrate-current"
        deploy(key, waitsAt("wait"))
        deploy(key, waitsAt("wait"))
        val id = runtime.startProcessInstanceByKey(key).id

        migrator.migrate(setOf(key))

        assertEquals(listOf("process migration $key v2: nothing stale"), lines(Level.INFO))
        assertTrue(lines(Level.WARN).isEmpty())
        runtime.deleteProcessInstance(id, "test done")
    }
}
