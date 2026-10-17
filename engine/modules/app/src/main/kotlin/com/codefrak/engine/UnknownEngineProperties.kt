package com.codefrak.engine

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.PropertiesLoaderUtils

/**
 * The engine refuses to start on an `engine.*` property it does not know (engine/README.md, "Configuration"):
 * one whose name is not a key of the classpath `application.properties`, from any property source Spring has
 * loaded (an `ENGINE_*` environment variable, a system property, a command-line argument, a config file). A
 * setting that moved or was removed (`ENGINE_CI_CHECK_REPOS`, #908) then fails the start, naming the property
 * and where it came from, instead of lingering in a deployment's `.env` or compose file and deciding nothing,
 * or something. Runs last among the environment post-processors (after the config files are loaded), before
 * any bean exists, so no flow or scheduler starts.
 *
 * Names are compared in relaxed-binding form: case and the separators `.`, `_` and `-` ignored, so
 * `ENGINE_SMITH_IMAGE_REFRESH_INTERVAL` and `engine.smith.imageRefreshInterval` are
 * `engine.smith.image-refresh-interval`. Registered in META-INF/spring.factories.
 */
class UnknownEngineProperties : EnvironmentPostProcessor, Ordered {
    override fun getOrder() = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val unknown = unknownEngineProperties(environment, declaredKeys())
        if (unknown.isNotEmpty()) throw IllegalStateException(
            "Unrecognised engine.* properties, not keys of application.properties (engine/README.md, \"Configuration\"); " +
                "delete them or declare them:\n  " + unknown.joinToString("\n  "),
        )
    }

    companion object {
        /** `engine.smith.image-refresh-interval`, `ENGINE_SMITH_IMAGE_REFRESH_INTERVAL` and `engine.smith.imageRefreshInterval` alike. */
        fun relaxed(name: String) = name.lowercase().filter(Char::isLetterOrDigit)

        /** The keys of the classpath application.properties, the engine's declared settings. */
        fun declaredKeys(): Set<String> =
            PropertiesLoaderUtils.loadProperties(ClassPathResource("application.properties")).stringPropertyNames()

        /** Every engine.* property in [environment] that is not one of [declared], as "name (from source)". */
        fun unknownEngineProperties(environment: ConfigurableEnvironment, declared: Set<String>): List<String> {
            val known = declared.filter { it.startsWith("engine.") }.map { relaxed(it) }.toSet()
            return environment.propertySources
                .filterIsInstance<EnumerablePropertySource<*>>()
                // Boot's view over every other source: each name in it is already checked where it came from.
                .filter { it.name != "configurationProperties" }
                .flatMap { source ->
                    val env = source is SystemEnvironmentPropertySource
                    source.propertyNames
                        .filter { val n = it.lowercase(); n.startsWith("engine.") || (env && n.startsWith("engine_")) }
                        .filter { relaxed(it) !in known }
                        .map { "$it (from ${source.name})" }
                }
        }
    }
}
