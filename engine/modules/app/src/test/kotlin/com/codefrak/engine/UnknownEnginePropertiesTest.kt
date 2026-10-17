package com.codefrak.engine

import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The startup check (engine/README.md, "Configuration"): a SpringApplication with no beans of its own, so each
 * start is cheap, but with every environment post-processor Boot loads from spring.factories, ours included.
 */
class UnknownEnginePropertiesTest {
    /** The only bean: not a @Configuration, so the engine's own component scan never picks it up. */
    class Empty

    /** Starts and closes an empty application with [environment] variables and [args], as the engine would get them. */
    private fun start(environment: Map<String, Any> = emptyMap(), vararg args: String) {
        val env = StandardEnvironment()
        env.propertySources.addFirst(SystemEnvironmentPropertySource("test-environment", environment))
        SpringApplication(Empty::class.java).apply {
            setWebApplicationType(WebApplicationType.NONE)
            setEnvironment(env)
        }.run(*args).close()
    }

    private fun failure(environment: Map<String, Any> = emptyMap(), vararg args: String): String {
        val e = assertFailsWith<Exception> { start(environment, *args) }
        return generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString("\n")
    }

    @Test
    fun `an unknown engine property on the command line stops the start, named`() {
        val message = failure(emptyMap(), "--engine.nope=1")
        assertContains(message, "engine.nope (from commandLineArgs)")
    }

    @Test
    fun `an unknown ENGINE_ variable stops the start, named as given with its source`() {
        val message = failure(mapOf("ENGINE_CI_CHECK_REPOS" to "acme/repo"), "--engine.nope=1")
        assertContains(message, "ENGINE_CI_CHECK_REPOS (from test-environment)")
        assertContains(message, "engine.nope (from commandLineArgs)")
    }

    @Test
    fun `the HITLP server's old environment variables are unknown and stop the start`() {
        // #2500: the server is a setting (PUT /settings/hitlp), and nothing reads these any more.
        val message = failure(mapOf("ENGINE_HITLP_URL" to "https://hitlp.example/mcp"))
        assertContains(message, "ENGINE_HITLP_URL (from test-environment)")
        assertContains(failure(mapOf("ENGINE_HITLP_TOKEN" to "t")), "ENGINE_HITLP_TOKEN (from test-environment)")
    }

    @Test
    fun `an unknown engine system property stops the start`() {
        System.setProperty("engine.nope-either", "1")
        try {
            assertContains(failure(), "engine.nope-either (from systemProperties)")
        } finally {
            System.clearProperty("engine.nope-either")
        }
    }

    @Test
    fun `relaxed-binding forms of declared keys start`() {
        start(
            mapOf(
                "ENGINE_SMITH_IMAGE_REFRESH_INTERVAL" to "PT1M",
                "ENGINE_SMITH_NETWORK" to "frakworks_default",
                // Not the engine's: the container gets them, the check leaves them alone.
                "SPRING_DATASOURCE_USERNAME" to "engine",
                "PATH" to "/usr/bin",
                "HOSTNAME" to "engine",
                "JAVA_TOOL_OPTIONS" to "-XX:MaxRAMPercentage=60",
            ),
            "--engine.smith.imageRefreshInterval=PT2M", "--engine.smith.image-pull-timeout=PT1M", "--spring.nope=1",
        )
    }

    @Test
    fun `every key of application properties is known, in its own form and the environment's`() {
        val declared = UnknownEngineProperties.declaredKeys()
        val env = StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("declared", declared.associateWith { "x" }))
        env.propertySources.addFirst(
            SystemEnvironmentPropertySource("vars", declared.associate { it.uppercase().replace('.', '_').replace("-", "") to "x" }),
        )
        assertEquals(emptyList<String>(), UnknownEngineProperties.unknownEngineProperties(env, declared))
        assertTrue(declared.any { it.startsWith("engine.") }, "no engine.* key read from application.properties")
    }
}
