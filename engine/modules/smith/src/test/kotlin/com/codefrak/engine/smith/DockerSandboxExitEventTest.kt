package com.codefrak.engine.smith

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.dockerjava.api.model.Event
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `exitOf` (DockerSandbox.kt) on the `die` event a current daemon writes: no
 * top-level `id`, the container id in `Actor.ID`. Reading `event.id` alone
 * dropped every exit (2026-09-25), so each run waited for the backstop.
 */
class DockerSandboxExitEventTest {
    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val current = """{"Type":"container","Action":"die","Actor":{"ID":"c11a3857585e7318a80bab8da9c960fdbfe56aa77cad31c541d3b2ce210f623f",
        "Attributes":{"execDuration":"0","exitCode":"6","frakworks.smith.run":"0f321117-b91f-11f1-be6c-f286d435ff9f","image":"frakworks-smith:local","name":"smith-275-0f321117"}},
        "scope":"local","time":1790369561,"timeNano":1790369561462171276}"""

    @Test
    fun `a current daemon's die event names the container in the actor`() {
        val exit = exitOf(mapper.readValue(current, Event::class.java))!!
        assertEquals("c11a3857585e7318a80bab8da9c960fdbfe56aa77cad31c541d3b2ce210f623f", exit.handle)
        assertEquals("0f321117-b91f-11f1-be6c-f286d435ff9f", exit.runId)
        assertEquals(6L, exit.exitCode)
    }

    @Test
    fun `a legacy die event with a top-level id still maps`() {
        val legacy = """{"status":"die","id":"abc123","from":"alpine","Type":"container","Action":"die","time":1}"""
        val exit = exitOf(mapper.readValue(legacy, Event::class.java))!!
        assertEquals("abc123", exit.handle)
        assertNull(exit.runId)
        assertEquals(-1L, exit.exitCode)
    }

    @Test
    fun `an event naming no container is dropped`() {
        assertNull(exitOf(mapper.readValue("""{"Type":"container","Action":"die","time":1}""", Event::class.java)))
    }
}
